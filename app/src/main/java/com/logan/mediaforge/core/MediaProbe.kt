package com.logan.mediaforge.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFprobeKit

data class MediaInfo(
    val durationMs: Long,
    val formatName: String,
    val bitrateKbps: Long?,          // 总码率（容器）
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val audioCodec: String?,
    val sampleRate: Int?,
    val channelLayout: String?,
    // ---- 新增：视频流参数 ----
    val fps: Float? = null,              // 帧率
    val videoBitrateKbps: Long? = null,  // 视频流码率（与音频分开）
    val pixFmt: String? = null,          // 像素格式（如 yuv420p）
    val videoBitDepth: Int? = null,      // 视频位深
    val colorSpace: String? = null,      // 色彩空间矩阵
    val colorPrimaries: String? = null,  // 色域原色
    val colorTransfer: String? = null,   // 传递函数
    val colorRange: String? = null,      // 色彩范围 tv/pc
    // ---- 新增：音频流参数 ----
    val audioBitrateKbps: Long? = null,  // 音频流码率（与视频分开）
    val audioBitDepth: Int? = null,      // 音频位深（sample_fmt / bits_per_raw_sample）
    val audioSampleFmt: String? = null   // 采样格式
)

/** 媒体信息探测（FFprobe 真实解析，不猜后缀） */
object MediaProbe {

    fun probe(path: String): MediaInfo? {
        return try {
            val session = FFprobeKit.getMediaInformation(path) ?: return null
            val mi = session.mediaInformation ?: return null
            // duration 为秒（浮点字符串），转换为毫秒
            val d = mi.duration?.toDoubleOrNull() ?: 0.0
            val durationMs = if (d > 0) (d * 1000).toLong() else 0L
            var vc: String? = null
            var w: Int? = null
            var h: Int? = null
            var ac: String? = null
            var sr: Int? = null
            var chLayout: String? = null
            var fps: Float? = null
            var vBr: Long? = null
            var aBr: Long? = null
            var pixFmt: String? = null
            var vDepth: Int? = null
            var cs: String? = null
            var cp: String? = null
            var ct: String? = null
            var cr: String? = null
            var aDepth: Int? = null
            var aFmt: String? = null
            val streams = mi.streams ?: emptyList()
            for (st in streams) {
                when (st.type) {
                    "video" -> if (vc == null) {
                        vc = st.codec
                        w = st.width?.toInt()
                        h = st.height?.toInt()
                        fps = propFps(st)
                        vBr = propKbps(st, "bit_rate")
                        pixFmt = propStr(st, "pix_fmt")
                        vDepth = propInt(st, "bits_per_raw_sample") ?: pixDepthFromPixFmt(pixFmt)
                        cs = propStr(st, "color_space")
                        cp = propStr(st, "color_primaries")
                        ct = propStr(st, "color_transfer")
                        cr = propStr(st, "color_range")
                    }
                    "audio" -> if (ac == null) {
                        ac = st.codec
                        sr = st.sampleRate?.toIntOrNull()
                        chLayout = st.channelLayout
                        aBr = propKbps(st, "bit_rate")
                        aFmt = propStr(st, "sample_fmt")
                        aDepth = propInt(st, "bits_per_raw_sample")
                            ?: propInt(st, "bits_per_sample")
                            ?: depthFromSampleFmt(aFmt)
                    }
                }
            }
            MediaInfo(
                durationMs = durationMs,
                formatName = mi.format ?: "",
                bitrateKbps = mi.bitrate?.toDoubleOrNull()?.toLong(),
                videoCodec = vc, width = w, height = h,
                audioCodec = ac, sampleRate = sr, channelLayout = chLayout,
                fps = fps, videoBitrateKbps = vBr, pixFmt = pixFmt, videoBitDepth = vDepth,
                colorSpace = cs, colorPrimaries = cp, colorTransfer = ct, colorRange = cr,
                audioBitrateKbps = aBr, audioBitDepth = aDepth, audioSampleFmt = aFmt
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---- FFprobe 流属性安全读取（不同封装可能缺字段，全部容错） ----
    private fun propStr(st: com.arthenica.ffmpegkit.StreamInformation, key: String): String? = try {
        st.getStringProperty(key)?.takeIf { it.isNotEmpty() && it != "unknown" && it != "N/A" }
    } catch (e: Exception) { null }

    private fun propInt(st: com.arthenica.ffmpegkit.StreamInformation, key: String): Int? = try {
        st.getNumberProperty(key)?.toInt()?.takeIf { it > 0 }
    } catch (e: Exception) { null }

    private fun propKbps(st: com.arthenica.ffmpegkit.StreamInformation, key: String): Long? = try {
        st.getNumberProperty(key)?.toLong()?.div(1000)?.takeIf { it > 0 }
    } catch (e: Exception) { null }

    /** 帧率：优先 avg_frame_rate（如 30000/1001），回退 r_frame_rate */
    private fun propFps(st: com.arthenica.ffmpegkit.StreamInformation): Float? {
        for (key in listOf("avg_frame_rate", "r_frame_rate")) {
            val v = propStr(st, key) ?: continue
            val f = parseFraction(v)
            if (f != null && f > 0.5f && f < 500f) return f
        }
        return null
    }

    private fun parseFraction(s: String): Float? {
        val p = s.split("/")
        return try {
            when (p.size) {
                2 -> if (p[1].toFloat() != 0f) p[0].toFloat() / p[1].toFloat() else null
                else -> s.toFloat().takeIf { it > 0f }
            }
        } catch (e: Exception) { null }
    }

    /** 采样格式 → 位深：s16=16bit，s32=32bit，flt=32bit 浮点，dbl=64bit */
    fun depthFromSampleFmt(fmt: String?): Int? = when (fmt?.lowercase()?.removeSuffix("p")) {
        "s16", "u16" -> 16
        "s24" -> 24
        "s32", "flt", "float" -> 32
        "s64", "dbl", "double" -> 64
        "s8", "u8" -> 8
        else -> null
    }

    /** 像素格式 → 位深（常见格式） */
    fun pixDepthFromPixFmt(fmt: String?): Int? {
        val f = fmt?.lowercase() ?: return null
        return when {
            f.contains("10le") || f.contains("10be") || f.contains("10p") -> 10
            f.contains("12le") || f.contains("12be") -> 12
            f.contains("16le") || f.contains("16be") -> 16
            f.startsWith("yuv420p") || f.startsWith("yuvj420p") || f.startsWith("yuv422p") ||
                f.startsWith("yuv444p") || f == "gray" || f.startsWith("nv12") || f.startsWith("nv21") -> 8
            f.startsWith("rgb") || f.startsWith("bgr") || f.startsWith("argb") || f.startsWith("rgba") ||
                f.startsWith("bgra") || f.startsWith("abgr") -> 8
            f == "yuv420" || f == "yuv422" || f == "yuv444" -> 8
            else -> null
        }
    }

    /**
     * 双引擎交叉校验（防参数被伪造/篡改）：
     * FFprobe 与系统 MediaMetadataRetriever 是两套独立解析器，各自读容器头。
     * 宽高或时长对不上 → 头部信息异常（被改过或文件损坏），返回警告文本并给出两边的真实值；
     * 一致 → 返回 null。
     */
    fun crossCheck(context: Context, uri: Uri, info: MediaInfo): String? = try {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val mw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val mh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val md = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val problems = mutableListOf<String>()
            if (mw != null && mh != null && info.width != null && info.height != null &&
                (mw != info.width || mh != info.height)) {
                problems.add("分辨率：FFprobe=${info.width}×${info.height}，系统解码器=${mw}×${mh}")
            }
            if (md != null && md > 0 && info.durationMs > 0) {
                val diff = kotlin.math.abs(md - info.durationMs)
                if (diff > 1500 && diff > md * 0.05) {
                    problems.add("时长：FFprobe=${fmtMs(info.durationMs)}，系统解码器=${fmtMs(md)}")
                }
            }
            if (problems.isEmpty()) null
            else "⚠ 元数据交叉校验不一致（头部信息可能被篡改或文件异常，以实际解码为准）：\n" + problems.joinToString("\n")
        } finally {
            try { mmr.release() } catch (_: Exception) { }
        }
    } catch (e: Exception) {
        null // 系统解码器读不了不等于有问题（FFprobe 支持的格式更多），不误报
    }
}
