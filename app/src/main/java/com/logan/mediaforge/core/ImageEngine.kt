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
 * - 支持先按归一化矩形真实裁剪（自由裁剪/比例裁剪），再缩放到目标分辨率
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

    /** 裁剪矩形（归一化 0..1）；默认全图 */
    data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
        val isFull: Boolean get() = l <= 0.0001f && t <= 0.0001f && r >= 0.9999f && b >= 0.9999f
    }

    fun convert(
        context: Context, uri: Uri, targetExt: String, w: Int, h: Int, quality: Int, out: File,
        crop: CropRect = CropRect()
    ): Boolean {
        // 防超大分辨率：限制单边与总像素，超出直接失败（任务页提示），不导致崩溃
        val cw = if (w > 0) w.coerceIn(1, 32767) else 0
        val ch = if (h > 0) h.coerceIn(1, 32767) else 0
        if (cw > 0 && ch > 0 && cw.toLong() * ch > 80_000_000L) return false
        val c = CropRect(
            crop.l.coerceIn(0f, 1f), crop.t.coerceIn(0f, 1f),
            crop.r.coerceIn(0f, 1f), crop.b.coerceIn(0f, 1f)
        )
        if (c.r - c.l < 0.01f || c.b - c.t < 0.01f) return false
        return try {
            val ok = when (targetExt) {
                "jpg" -> viaBitmap(context, uri, cw, ch, Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out, c)
                "png" -> viaBitmap(context, uri, cw, ch, Bitmap.CompressFormat.PNG, 100, out, c)
                "webp" -> viaBitmap(context, uri, cw, ch, webpFormat(), quality.coerceIn(10, 100), out, c)
                else -> viaFfmpeg(context, uri, targetExt, cw, ch, out, c)
            }
            ok && out.exists() && out.length() > 0
        } catch (e: OutOfMemoryError) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun viaBitmap(
        context: Context, uri: Uri, w: Int, h: Int, fmt: Bitmap.CompressFormat, quality: Int, out: File,
        crop: CropRect
    ): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return false
        var sample = 1
        // 有裁剪时降采样需保守一些，保证裁剪框对应的像素足够
        while (opts.outWidth / (sample * 2) >= 4096 || opts.outHeight / (sample * 2) >= 4096) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, o2)
        } ?: return false

        var cur = bmp
        try {
            // 第一步：真实裁剪（像素级，居中/自由区域都按归一化矩形取）
            if (!crop.isFull) {
                val sx = (cur.width * crop.l).toInt().coerceIn(0, cur.width - 1)
                val sy = (cur.height * crop.t).toInt().coerceIn(0, cur.height - 1)
                val sw = (cur.width * (crop.r - crop.l)).toInt().coerceIn(1, cur.width - sx)
                val sh = (cur.height * (crop.b - crop.t)).toInt().coerceIn(1, cur.height - sy)
                val cropped = try {
                    Bitmap.createBitmap(cur, sx, sy, sw, sh)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                } ?: return false
                if (cropped !== cur) cur.recycle()
                cur = cropped
            }
            // 第二步：缩放到目标分辨率
            var result = cur
            if (w > 0 && h > 0 && (w != cur.width || h != cur.height)) {
                result = try {
                    Bitmap.createScaledBitmap(cur, w, h, true)
                } catch (e: OutOfMemoryError) {
                    cur
                }
            }
            val ok = out.outputStream().use { result.compress(fmt, quality, it) }
            return ok
        } finally {
            if (cur !== bmp && !cur.isRecycled) cur.recycle()
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun viaFfmpeg(
        context: Context, uri: Uri, targetExt: String, w: Int, h: Int, out: File,
        crop: CropRect
    ): Boolean {
        val input = FFmpegKitConfig.getSafParameterForRead(context, uri) ?: return false
        val args = mutableListOf("-y", "-i", input)
        val vf = mutableListOf<String>()
        if (!crop.isFull) {
            // crop=w:h:x:y，用比例表达式：真实居中/自由区域裁剪
            vf += "crop=iw*${fmtF(crop.r - crop.l)}:ih*${fmtF(crop.b - crop.t)}:iw*${fmtF(crop.l)}:ih*${fmtF(crop.t)}"
        }
        if (w > 0 && h > 0) vf += "scale=$w:$h:flags=lanczos"
        if (vf.isNotEmpty()) args += listOf("-vf", vf.joinToString(","))
        args += listOf("-frames:v", "1", out.absolutePath)
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        return ReturnCode.isSuccess(session.returnCode)
    }

    private fun fmtF(v: Float): String = String.format(java.util.Locale.US, "%.6f", v)
}
