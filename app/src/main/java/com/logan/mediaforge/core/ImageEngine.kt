package com.logan.mediaforge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * 图片真实转换引擎：
 * - JPEG/PNG/WebP：Android Bitmap 真实重新编码（像素级转换，非改后缀）
 * - BMP/TIFF 等：FFmpeg 真实转码
 */
object ImageEngine {

    fun decodeSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            return null
        }
        return if (opts.outWidth > 0) opts.outWidth to opts.outHeight else null
    }

    fun decodePreview(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= maxDim && opts.outHeight / (sample * 2) >= maxDim) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        } catch (e: Exception) {
            null
        }
    }

    fun convert(context: Context, uri: Uri, targetExt: String, w: Int, h: Int, quality: Int, out: File): Boolean {
        return try {
            val ok = when (targetExt) {
                "jpg" -> viaBitmap(context, uri, w, h, Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
                "png" -> viaBitmap(context, uri, w, h, Bitmap.CompressFormat.PNG, 100, out)
                "webp" -> viaBitmap(context, uri, w, h, webpFormat(), quality.coerceIn(10, 100), out)
                else -> viaFfmpeg(context, uri, targetExt, w, h, out)
            }
            ok && out.exists() && out.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun viaBitmap(context: Context, uri: Uri, w: Int, h: Int, fmt: Bitmap.CompressFormat, quality: Int, out: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return false
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 4096 || opts.outHeight / (sample * 2) >= 4096) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, o2)
        } ?: return false
        var result = bmp
        if (w > 0 && h > 0 && (w != bmp.width || h != bmp.height)) {
            result = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        val ok = out.outputStream().use { result.compress(fmt, quality, it) }
        if (result !== bmp) bmp.recycle()
        return ok
    }

    private fun viaFfmpeg(context: Context, uri: Uri, targetExt: String, w: Int, h: Int, out: File): Boolean {
        val input = FFmpegKitConfig.getSafParameterForRead(context, uri) ?: return false
        val args = mutableListOf("-y", "-i", input)
        if (w > 0 && h > 0) args += listOf("-vf", "scale=$w:$h:flags=lanczos")
        args += listOf("-frames:v", "1", out.absolutePath)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        return ReturnCode.isSuccess(session.returnCode)
    }
}
