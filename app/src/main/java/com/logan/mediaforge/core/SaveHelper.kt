package com.logan.mediaforge.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import java.io.File

/**
 * 保存 / 下载 / 重命名：
 * - 默认保存到系统「下载」文件夹的 MediaForge 子目录（文件管理器直接可见）
 * - 支持用户为每个类别（音频/图片/其他）自选 SAF 文件夹
 * - 重命名：MediaStore 更新或 SAF renameDocument，已下载文件即时生效
 */
object SaveHelper {

    data class SaveResult(val uri: Uri?, val name: String?, val error: String?)
    data class RenameResult(val file: File?, val uri: Uri?, val name: String?, val error: String?)

    fun download(context: Context, file: File, ext: String, category: String): SaveResult {
        val mime = mimeFor(ext)
        val name = file.name
        return try {
            val folder = SettingsRepo.folder(context, category)
            when {
                folder != null -> saveSaf(context, Uri.parse(folder), name, mime, file)
                Build.VERSION.SDK_INT >= 29 -> saveMediaStore(context, name, mime, file)
                else -> saveLegacy(file, name)
            }
        } catch (e: Exception) {
            SaveResult(null, null, "保存失败：${e.message}")
        }
    }

    private fun saveMediaStore(context: Context, name: String, mime: String, file: File): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MediaForge")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult(null, null, "无法写入下载文件夹")
        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            return SaveResult(null, null, "无法写入内容")
        }
        out.use { os -> file.inputStream().use { it.copyTo(os) } }
        return SaveResult(uri, name, null)
    }

    private fun saveLegacy(file: File, name: String): SaveResult {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(root, "MediaForge")
        if (!dir.exists() && !dir.mkdirs()) return SaveResult(null, null, "无法创建下载目录")
        val dst = uniqueFile(dir, name) ?: return SaveResult(null, null, "无法创建文件")
        file.inputStream().use { it.copyTo(dst.outputStream()) }
        return SaveResult(Uri.fromFile(dst), dst.name, null)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }

    private fun saveSaf(context: Context, treeUri: Uri, name: String, mime: String, file: File): SaveResult {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val docUri = DocumentsContract.createDocument(context.contentResolver, dirUri, mime, name)
                ?: return SaveResult(null, null, "所选文件夹创建文件失败")
            context.contentResolver.openOutputStream(docUri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            } ?: return SaveResult(null, null, "所选文件夹无法写入")
            SaveResult(docUri, DocumentsContract.getDocumentId(docUri).substringAfterLast(':'), null)
        } catch (e: Exception) {
            SaveResult(null, null, "保存到所选文件夹失败：${e.message}")
        }
    }

    /** 重命名：已下载的文件在文件管理器中即时生效 */
    fun rename(context: Context, outputFile: File?, downloadedUri: Uri?, newNameRaw: String, ext: String): RenameResult {
        var newName = newNameRaw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (newName.isEmpty()) return RenameResult(null, null, null, "名称不能为空")
        if (!newName.contains('.')) newName = "$newName.$ext"
        return try {
            if (downloadedUri != null) {
                val r = renameContent(context, downloadedUri, newName)
                if (r.error == null) return RenameResult(outputFile, r.uri, newName, null)
                return RenameResult(null, null, null, r.error)
            }
            if (outputFile != null && outputFile.exists()) {
                val target = File(outputFile.parentFile, newName)
                if (target.absolutePath != outputFile.absolutePath && target.exists())
                    return RenameResult(null, null, null, "同名文件已存在")
                if (outputFile.renameTo(target)) return RenameResult(target, null, newName, null)
                return RenameResult(null, null, null, "重命名失败（文件可能被占用）")
            }
            RenameResult(null, null, null, "没有可重命名的文件")
        } catch (e: Exception) {
            RenameResult(null, null, null, "重命名失败：${e.message}")
        }
    }

    private class RenameContentResult(val uri: Uri?, val error: String?)

    private fun renameContent(context: Context, uri: Uri, newName: String): RenameContentResult {
        return try {
            when {
                uri.scheme == "file" -> {
                    val f = File(uri.path ?: return RenameContentResult(null, "无效路径"))
                    val target = File(f.parentFile, newName)
                    if (f.renameTo(target)) RenameContentResult(Uri.fromFile(target), null)
                    else RenameContentResult(null, "重命名失败")
                }
                uri.authority == "media" -> {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                    val rows = context.contentResolver.update(uri, values, null, null)
                    if (rows > 0) RenameContentResult(uri, null)
                    else RenameContentResult(null, "系统拒绝重命名（非本应用创建的文件）")
                }
                else -> {
                    val newUri = DocumentsContract.renameDocument(context.contentResolver, uri, newName)
                        ?: return RenameContentResult(null, "重命名失败")
                    RenameContentResult(newUri, null)
                }
            }
        } catch (e: Exception) {
            RenameContentResult(null, "重命名失败：${e.message}")
        }
    }
}
