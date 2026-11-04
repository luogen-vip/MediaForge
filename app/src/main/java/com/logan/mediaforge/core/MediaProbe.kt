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
    val channels: Int?
)

/** 媒体信息探测（FFprobe 真实解析，不猜后缀） */
object MediaProbe {

    fun probe(path: String): MediaInfo? {
        return try {
            val session = FFprobeKit.getMediaInformation(path) ?: return null
            val mi = session.mediaInformation ?: return null
            val durationMs = mi.duration?.toDoubleOrNull()?.toLong() ?: 0L
            var vc: String? = null
            var w: Int? = null
            var h: Int? = null
            var ac: String? = null
            var sr: Int? = null
            var ch: Int? = null
            val streams = mi.streams ?: emptyList()
            for (st in streams) {
                when (st.type) {
                    "video" -> if (vc == null) {
                        vc = st.codecName
                        w = st.width
                        h = st.height
                    }
                    "audio" -> if (ac == null) {
                        ac = st.codecName
                        sr = st.sampleRate?.toIntOrNull()
                        ch = st.channelCount
                    }
                }
            }
            MediaInfo(
                durationMs = durationMs,
                formatName = mi.format ?: "",
                bitrateKbps = mi.bitrate?.toDoubleOrNull()?.toLong(),
                videoCodec = vc, width = w, height = h,
                audioCodec = ac, sampleRate = sr, channels = ch
            )
        } catch (e: Exception) {
            null
        }
    }
}
