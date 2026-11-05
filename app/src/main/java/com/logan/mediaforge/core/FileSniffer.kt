package com.logan.mediaforge.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class Detection(
    val realName: String,
    val realExt: String,
    val mime: String,
    val category: String,          // AUDIO / VIDEO / IMAGE / ARCHIVE / DOCUMENT / EXECUTABLE / TEXT / OTHER
    val isText: Boolean = false,
    val corrupted: String? = null, // 损坏/截断迹象说明，null=未见异常
    val mismatch: Boolean = false, // 声称的后缀与真实格式不符
    val declaredExt: String = ""
)

/**
 * 文件真实格式识别器 —— 基于「魔数（magic number）」嗅探文件内容本身，
 * 不信任扩展名。可识别被改名、伪造后缀、截断损坏的文件。
 */
object FileSniffer {

    fun querySize(resolver: ContentResolver, uri: Uri): Long {
        return try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun detect(resolver: ContentResolver, uri: Uri, declaredExt: String): Detection? {
        return try {
            val size = querySize(resolver, uri)
            val head = ByteArray(8192)
            // 尾部读 64KB：ZIP 容器（APK/JAR/DOCX/XLSX…）的中央目录在文件末尾，
            // 所有条目名都明文存在中央目录里，扫尾部才能可靠区分容器类型
            val tail = ByteArray(65536)
            var hlen = 0
            var tlen = 0
            resolver.openInputStream(uri)?.use { ins ->
                hlen = readUpTo(ins, head)
                if (size > head.size.toLong()) {
                    // 注意：当前位置已在 hlen，目标位置是 size - tail.size（读到文件末尾为止），
                    // 旧版少扣了已读的 hlen 导致 skip 越过 EOF、尾部永远读不到
                    val target = size - tail.size
                    var skipped = hlen.toLong()
                    if (target > skipped) {
                        while (skipped < target) {
                            val s = ins.skip(target - skipped)
                            if (s <= 0) break
                            skipped += s
                        }
                    }
                    tlen = readUpTo(ins, tail)
                }
            } ?: return null
            analyze(head, hlen, tail, tlen, size, declaredExt)
        } catch (e: Exception) {
            null
        }
    }

    fun detect(file: File, declaredExt: String = file.extension): Detection? {
        return try {
            val size = file.length()
            val head = ByteArray(8192)
            val tail = ByteArray(65536)
            var hlen = 0
            var tlen = 0
            FileInputStream(file).use { ins -> hlen = readUpTo(ins, head) }
            if (size > head.size.toLong()) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(maxOf(head.size.toLong(), size - tail.size))
                    var off = 0
                    while (off < tail.size) {
                        val r = raf.read(tail, off, tail.size - off)
                        if (r <= 0) break
                        off += r
                    }
                    tlen = off
                }
            }
            analyze(head, hlen, tail, tlen.coerceAtLeast(0), size, declaredExt)
        } catch (e: Exception) {
            null
        }
    }

    private fun readUpTo(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private fun analyze(head: ByteArray, hlen: Int, tail: ByteArray, tlen: Int, size: Long, declaredExt: String): Detection {
        val dec = declaredExt.lowercase()

        fun at(off: Int, vararg sig: Int): Boolean =
            hlen >= off + sig.size && (0 until sig.size).all { (head[off + it].toInt() and 0xFF) == sig[it] }

        fun atStr(off: Int, s: String): Boolean =
            hlen >= off + s.length && (0 until s.length).all { (head[off + it].toInt() and 0xFF) == s[it].code }

        fun headContains(s: String): Boolean {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            if (hlen < bytes.size) return false
            var i = 0
            while (i <= hlen - bytes.size) {
                var ok = true
                for (j in bytes.indices) {
                    if ((head[i + j].toInt() and 0xFF) != (bytes[j].toInt() and 0xFF)) { ok = false; break }
                }
                if (ok) return true
                i++
            }
            return false
        }

        fun tailContains(vararg sig: Int): Boolean {
            if (tlen < sig.size) return false
            return (0..tlen - sig.size).any { i ->
                (0 until sig.size).all { j -> (tail[i + j].toInt() and 0xFF) == sig[j] }
            }
        }

        fun tailHasStr(s: String): Boolean {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            if (tlen < bytes.size) return false
            return (0..tlen - bytes.size).any { i ->
                (0 until bytes.size).all { j -> (tail[i + j].toInt() and 0xFF) == (bytes[j].toInt() and 0xFF) }
            }
        }

        fun det(name: String, ext: String, mime: String, cat: String, corrupted: String? = null, isText: Boolean = false): Detection =
            Detection(name, ext, mime, cat, isText, corrupted,
                mismatch = ext.isNotEmpty() && dec.isNotEmpty() && ext != dec, declaredExt = dec)

        if (size == 0L) {
            return Detection("空文件（0 字节）", dec, "application/octet-stream", "OTHER",
                corrupted = "文件大小为 0 字节，可能是空文件或已损坏", mismatch = dec.isNotEmpty(), declaredExt = dec)
        }

        // ---------- 压缩包 / 文档容器 ----------
        // ZIP 是个容器家族：APK/JAR/EPUB/DOCX/XLSX/PPTX 本质都是 ZIP。
        // 判定规则：优先找容器特有特征（头部的 mimetype/首条目，或末尾中央目录里的条目名），
        // 找到真实特征就报真实类型（如 APK）；一个特征都没有才是普通 ZIP。
        // 这样「真 APK 改了名」和「真 ZIP 改名 .apk」都能给出正确的真实格式。
        if (at(0, 0x50, 0x4B, 0x03, 0x04)) {
            val isApk = headContains("AndroidManifest.xml") || headContains("classes.dex") ||
                tailHasStr("AndroidManifest.xml") || tailHasStr("classes.dex")
            val isJar = headContains("META-INF/MANIFEST.MF") || tailHasStr("META-INF/MANIFEST.MF")
            val (name, ext) = when {
                isApk -> "APK（安卓安装包）" to "apk"
                isJar -> "JAR（Java 归档）" to "jar"
                headContains("application/epub+zip") -> "EPUB（电子书）" to "epub"
                headContains("word/") || tailHasStr("word/") -> "DOCX（Word 文档）" to "docx"
                headContains("xl/") || tailHasStr("xl/") -> "XLSX（Excel 表格）" to "xlsx"
                headContains("ppt/") || tailHasStr("ppt/") -> "PPTX（演示文稿）" to "pptx"
                else -> "ZIP（压缩包）" to "zip"
            }
            val cor = if (!tailContains(0x50, 0x4B, 0x05, 0x06))
                "缺少 ZIP 结尾目录（EOCD），文件可能不完整或被截断" else null
            return det(name, ext, "application/zip", "ARCHIVE", cor)
        }
        if (at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return det("7Z（压缩包）", "7z", "application/x-7z-compressed", "ARCHIVE")
        if (at(0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return det("RAR（压缩包）", "rar", "application/vnd.rar", "ARCHIVE")
        if (at(0, 0x1F, 0x8B)) return det("GZIP（压缩数据）", "gz", "application/gzip", "ARCHIVE")
        if (size > 512 && hlen >= 265 && atStr(257, "ustar")) return det("TAR（归档）", "tar", "application/x-tar", "ARCHIVE")

        // ---------- 文档 ----------
        if (atStr(0, "%PDF-"))
            return det("PDF（文档）", "pdf", "application/pdf", "DOCUMENT",
                if (!tailHasStr("%%EOF")) "缺少 %%EOF 结尾标记，PDF 可能被截断" else null)

        // ---------- 图片 ----------
        if (at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            return det("PNG（图片）", "png", "image/png", "IMAGE",
                if (!tailContains(0x49, 0x45, 0x4E, 0x44)) "缺少 IEND 结束块，PNG 可能被截断" else null)
        if (at(0, 0xFF, 0xD8, 0xFF))
            return det("JPEG（图片）", "jpg", "image/jpeg", "IMAGE",
                if (!tailContains(0xFF, 0xD9)) "缺少结束标记 FFD9，JPEG 可能被截断" else null)
        if (atStr(0, "GIF87a") || atStr(0, "GIF89a"))
            return det("GIF（图片）", "gif", "image/gif", "IMAGE",
                if (!(tlen > 0 && (tail[tlen - 1].toInt() and 0xFF) == 0x3B)) "缺少结束字节 0x3B，GIF 可能被截断" else null)
        if (atStr(0, "BM") && size >= 54 &&
            (at(14, 0x28, 0, 0, 0) || at(14, 0x0C, 0, 0, 0) || at(14, 0x6C, 0, 0, 0) || at(14, 0x7C, 0, 0, 0) || at(14, 0x80, 0, 0, 0)))
            return det("BMP（位图）", "bmp", "image/bmp", "IMAGE")
        if (at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A)) return det("TIFF（图片）", "tif", "image/tiff", "IMAGE")
        if (at(0, 0x00, 0x00, 0x01, 0x00)) return det("ICO（图标）", "ico", "image/x-icon", "IMAGE")

        // RIFF 家族
        if (atStr(0, "RIFF") && hlen >= 12) {
            when (String(head, 8, 4, Charsets.ISO_8859_1)) {
                "WEBP" -> return det("WebP（图片）", "webp", "image/webp", "IMAGE")
                "WAVE" -> return det("WAV（无损音频）", "wav", "audio/wav", "AUDIO")
                "AVI " -> return det("AVI（视频）", "avi", "video/x-msvideo", "VIDEO")
            }
        }

        // EBML 家族
        if (at(0, 0x1A, 0x45, 0xDF, 0xA3)) return when {
            headContains("webm") -> det("WebM（视频）", "webm", "video/webm", "VIDEO")
            headContains("matroska") -> det("MKV（视频）", "mkv", "video/x-matroska", "VIDEO")
            else -> det("EBML 容器（Matroska/WebM）", "mkv", "video/x-matroska", "VIDEO")
        }

        // ISO-BMFF 家族
        if (hlen >= 12 && atStr(4, "ftyp")) {
            val brand = String(head, 8, 4, Charsets.ISO_8859_1)
            return when {
                brand.startsWith("qt") -> det("MOV（QuickTime 视频）", "mov", "video/quicktime", "VIDEO")
                brand.contains("m4a") -> det("M4A（音频）", "m4a", "audio/mp4", "AUDIO")
                brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1") || brand.startsWith("heif")
                    -> det("HEIC/HEIF（图片）", "heic", "image/heic", "IMAGE")
                brand.startsWith("3gp") -> det("3GP（视频）", "3gp", "video/3gpp", "VIDEO")
                else -> det("MP4（视频）", "mp4", "video/mp4", "VIDEO")
            }
        }

        // ---------- 音频 ----------
        if (atStr(0, "ID3")) return det("MP3（音频）", "mp3", "audio/mpeg", "AUDIO")
        if (at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9)) return det("AAC（ADTS 音频）", "aac", "audio/aac", "AUDIO")
        if (at(0, 0xFF) && hlen >= 2 && (head[1].toInt() and 0xE0) == 0xE0 &&
            head[1] != 0xF1.toByte() && head[1] != 0xF9.toByte()) {
            // MPEG 音频帧头：bit4-3=版本，bit2-1=层（01=Layer III，10=Layer II，11=Layer I）。
            // Layer II 就是 MP2，之前一律判成 mp3 导致 MP2 目标校验永远失败
            val layer = (head[1].toInt() shr 1) and 0x3
            return if (layer == 2) det("MP2（音频）", "mp2", "audio/mpeg", "AUDIO")
            else det("MP3（音频）", "mp3", "audio/mpeg", "AUDIO")
        }
        if (atStr(0, "fLaC")) return det("FLAC（无损音频）", "flac", "audio/flac", "AUDIO")
        if (atStr(0, "OggS")) return when {
            headContains("OpusHead") -> det("OPUS（音频）", "opus", "audio/ogg", "AUDIO")
            headContains("theora") -> det("OGG（Theora 视频）", "ogv", "video/ogg", "VIDEO")
            else -> det("OGG（音频）", "ogg", "audio/ogg", "AUDIO")
        }
        if (atStr(0, "#!AMR")) return det("AMR（语音）", "amr", "audio/amr", "AUDIO")
        if (at(0, 0x0B, 0x77)) return det("AC-3（杜比音频，推测）", "ac3", "audio/ac3", "AUDIO")
        if (atStr(0, "FORM") && hlen >= 12 && (atStr(8, "AIFF") || atStr(8, "AIFC")))
            return det("AIFF（无损音频）", "aiff", "audio/aiff", "AUDIO")
        if (at(0, 0x30, 0x26, 0xB2, 0x75)) return when {
            dec == "wmv" -> det("WMV（视频）", "wmv", "video/x-ms-wmv", "VIDEO")
            else -> det("WMA（音频）", "wma", "audio/x-ms-wma", "AUDIO")
        }
        if (atStr(0, "MThd")) return det("MIDI（音序）", "mid", "audio/midi", "AUDIO")

        // ---------- 可执行 / 其他 ----------
        if (atStr(0, "SQLite format 3")) return det("SQLite（数据库）", "db", "application/x-sqlite3", "OTHER")
        if (at(0, 0x7F, 0x45, 0x4C, 0x46)) return det("ELF（可执行/共享库）", "elf", "application/x-executable", "EXECUTABLE")
        if (at(0, 0x4D, 0x5A)) return det("EXE/DLL（Windows 可执行）", "exe", "application/vnd.microsoft.portable-executable", "EXECUTABLE")
        if (at(0, 0xCA, 0xFE, 0xBA, 0xBE)) return det("Java Class（字节码）", "class", "application/java-vm-class", "EXECUTABLE")
        if (at(0, 0x00, 0x01, 0x00, 0x00)) return det("TTF（字体）", "ttf", "font/ttf", "OTHER")
        if (atStr(0, "OTTO")) return det("OTF（字体）", "otf", "font/otf", "OTHER")
        if (atStr(0, "wOFF")) return det("WOFF（网页字体）", "woff", "font/woff", "OTHER")
        if (atStr(0, "wOF2")) return det("WOFF2（网页字体）", "woff2", "font/woff2", "OTHER")

        // ---------- 文本类 ----------
        val text = asText(head, hlen)
        if (text != null) {
            val trimmed = text.trimStart()
            val lower = dec
            val (name, ext) = when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> "JSON（文本）" to "json"
                trimmed.startsWith("<?xml") -> "XML（文本）" to "xml"
                trimmed.startsWith("<!DOCTYPE html", true) || trimmed.startsWith("<html", true) -> "HTML（网页文本）" to "html"
                lower == "md" || lower == "markdown" -> "Markdown 文档（文本）" to "md"
                lower == "csv" -> "CSV（文本表格）" to "csv"
                lower == "log" -> "日志文件（文本）" to "log"
                lower == "yaml" || lower == "yml" -> "YAML（文本）" to "yaml"
                lower == "srt" -> "SRT 字幕（文本）" to "srt"
                lower == "vtt" -> "VTT 字幕（文本）" to "vtt"
                lower == "ini" || lower == "conf" || lower == "properties" -> "配置文件（文本）" to lower
                else -> "文本文件（TXT/纯文本）" to "txt"
            }
            val cor = if (size > 4096L && hlen < head.size) "仅读取到文件开头，内容可能异常" else null
            return det(name, ext, "text/plain", "TEXT", cor, isText = true)
        }

        // ---------- 未知 ----------
        val note = if (dec.isNotEmpty())
            "内容与任何已知格式签名都不匹配 —— 后缀 .$dec 疑似伪造（改过名），或文件是普通数据 / 已损坏"
        else "内容与任何已知格式签名都不匹配（无签名的二进制数据）"
        return Detection("未知二进制数据", dec, "application/octet-stream", "OTHER",
            corrupted = note, mismatch = dec.isNotEmpty(), declaredExt = dec)
    }

    private fun asText(head: ByteArray, hlen: Int): String? {
        if (hlen == 0) return null
        val n = minOf(hlen, 4096)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = decoder.decode(ByteBuffer.wrap(head, 0, n)).toString()
            var ok = 0
            for (ch in s) {
                val c = ch.code
                if (c == 9 || c == 10 || c == 13 || c in 32..126 || c > 160) ok++
            }
            if (s.isNotEmpty() && ok.toFloat() / s.length >= 0.9f) s else null
        } catch (e: Exception) {
            null
        }
    }
}
