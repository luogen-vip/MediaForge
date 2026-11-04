package com.logan.mediaforge.core

import com.arthenica.ffmpegkit.FFprobeKit

data class MediaInfo(
    val durationMs: Long,
    val formatName: String,
    val bitrateKbps: Long?,
    val videoCodec: String?,
    val width: Int?,
    val height: Int?,
    val audioCodec: String?,
    val sampleRate: Int?,
    val channelLayout: String?
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
            val streams = mi.streams ?: emptyList()
            for (st in streams) {
                when (st.type) {
                    "video" -> if (vc == null) {
                        vc = st.codec
                        w = st.width?.toInt()
                        h = st.height?.toInt()
                    }
                    "audio" -> if (ac == null) {
                        ac = st.codec
                        sr = st.sampleRate?.toIntOrNull()
                        chLayout = st.channelLayout
                    }
                }
            }
            MediaInfo(
                durationMs = durationMs,
                formatName = mi.format ?: "",
                bitrateKbps = mi.bitrate?.toDoubleOrNull()?.toLong(),
                videoCodec = vc, width = w, height = h,
                audioCodec = ac, sampleRate = sr, channelLayout = chLayout
            )
        } catch (e: Exception) {
            null
        }
    }
}
