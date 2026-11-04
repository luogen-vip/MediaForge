package com.logan.mediaforge.core

import java.util.Locale

// ---------------- 工具函数 ----------------

fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    if (b < 1L shl 20) return String.format(Locale.US, "%.1f KB", b / 1024.0)
    if (b < 1L shl 30) return String.format(Locale.US, "%.1f MB", b / 1048576.0)
    return String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

fun msToSec(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)

fun mimeFor(ext: String): String = when (ext) {
    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "wav" -> "audio/wav"
    "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "opus" -> "audio/ogg"; "wma" -> "audio/x-ms-wma"
    "aiff" -> "audio/aiff"; "amr" -> "audio/amr"; "ac3" -> "audio/ac3"; "mp2" -> "audio/mpeg"
    "mp4" -> "video/mp4"; "mov" -> "video/quicktime"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"; "ts" -> "video/mp2ts"
    "jpg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; "tif" -> "image/tiff"
    else -> "application/octet-stream"
}

// ---------------- 音频格式规格 ----------------

data class AudioFormatSpec(
    val ext: String,
    val label: String,
    val isPcm: Boolean = false,
    val bitDepths: List<String> = emptyList(),
    val supportsBitrate: Boolean = false,
    val bitrateRange: IntRange = 32..320,
    val defaultBitrate: Int = 192,
    val supportsVbr: Boolean = false,
    val vbrMax: Int = 9,
    val supportsCompression: Boolean = false,
    val verifyKey: String = "",
    val notes: String = ""
)

data class AudioParams(
    val ext: String = "mp3",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitrate: Int = 0,
    val bitDepth: String = "16",
    val vbr: Int = -1,
    val compression: Int = -1
) {
    fun describe(): String {
        val spec = AudioFormats.byExt(ext)
        val parts = mutableListOf(spec?.label ?: ext.uppercase())
        if (spec?.isPcm == true) parts += if (bitDepth == "f32") "32bit 浮点" else "${bitDepth}bit"
        parts += if (sampleRate > 0) "${sampleRate}Hz" else "采样率默认"
        if (channels == 1) parts += "单声道"
        else if (channels == 2) parts += "立体声"
        else parts += "声道默认"
        if (spec?.supportsBitrate == true) {
            if (vbr in 0..spec.vbrMax) parts += "VBR q$vbr"
            else if (bitrate > 0) parts += "${bitrate}kbps"
            else parts += "码率默认"
        }
        if (spec?.supportsCompression == true && compression >= 0) parts += "压缩等级$compression"
        return parts.joinToString(" · ")
    }
}

object AudioFormats {
    val ALL = listOf(
        AudioFormatSpec("wav", "WAV 无损", isPcm = true, bitDepths = listOf("16", "24", "32", "f32"),
            verifyKey = "wav", notes = "PCM 无损，发行审核通用；可选 16/24/32bit 或 32bit 浮点"),
        AudioFormatSpec("mp3", "MP3", supportsBitrate = true, bitrateRange = 32..320, defaultBitrate = 320,
            supportsVbr = true, verifyKey = "mp3", notes = "320kbps 可满足发行上传要求"),
        AudioFormatSpec("m4a", "M4A (AAC)", supportsBitrate = true, bitrateRange = 32..512, defaultBitrate = 256,
            verifyKey = "m4a", notes = "通用性最好的有损格式"),
        AudioFormatSpec("aac", "AAC", supportsBitrate = true, bitrateRange = 32..512, defaultBitrate = 256,
            verifyKey = "aac", notes = "ADTS 裸流"),
        AudioFormatSpec("flac", "FLAC 无损", supportsCompression = true, verifyKey = "flac",
            notes = "无损压缩，可调 0-9 压缩等级"),
        AudioFormatSpec("ogg", "OGG (Vorbis)", supportsBitrate = true, bitrateRange = 32..500, defaultBitrate = 192,
            supportsVbr = true, verifyKey = "ogg"),
        AudioFormatSpec("opus", "OPUS", supportsBitrate = true, bitrateRange = 16..510, defaultBitrate = 192,
            verifyKey = "ogg", notes = "采样率将被限制在 48000Hz 以内"),
        AudioFormatSpec("wma", "WMA", supportsBitrate = true, bitrateRange = 32..192, defaultBitrate = 128,
            verifyKey = "asf"),
        AudioFormatSpec("aiff", "AIFF 无损", isPcm = true, bitDepths = listOf("16", "24", "32", "f32"),
            verifyKey = "aiff"),
        AudioFormatSpec("amr", "AMR 语音", supportsBitrate = false, verifyKey = "amr",
            notes = "语音标准：固定 8000Hz 单声道 12.2kbps"),
        AudioFormatSpec("ac3", "AC3 杜比", supportsBitrate = true, bitrateRange = 32..640, defaultBitrate = 384,
            verifyKey = "ac3"),
        AudioFormatSpec("mp2", "MP2", supportsBitrate = true, bitrateRange = 32..384, defaultBitrate = 256,
            verifyKey = "mp2"),
        AudioFormatSpec("alac", "ALAC 苹果无损", verifyKey = "m4a", notes = "输出为 M4A 容器（ALAC 编码）")
    )

    fun byExt(ext: String): AudioFormatSpec? = ALL.firstOrNull { it.ext == ext }
}

/**
 * 构建真实的 FFmpeg 转码参数。
 * 全部为真实编码（libmp3lame / aac / flac / pcm / libopus ...），绝非改后缀。
 */
fun buildAudioArgs(input: String, output: String, p: AudioParams, trimStartMs: Long?, trimEndMs: Long?): List<String> {
    val spec = AudioFormats.byExt(p.ext) ?: AudioFormats.ALL[1]
    val a = mutableListOf("-y")
    // 裁剪：-ss 作为输入选项（快速定位），-t 作为输出选项（时长）
    if (trimStartMs != null && trimStartMs > 0) a += listOf("-ss", msToSec(trimStartMs))
    a += listOf("-i", input)
    if (trimStartMs != null && trimEndMs != null && trimEndMs > trimStartMs) {
        a += listOf("-t", msToSec(trimEndMs - trimStartMs))
    } else if (trimStartMs == null && trimEndMs != null && trimEndMs > 0) {
        a += listOf("-t", msToSec(trimEndMs))
    }
    a += "-vn"

    when (p.ext) {
        "wav" -> a += listOf("-c:a", when (p.bitDepth) {
            "24" -> "pcm_s24le"; "32" -> "pcm_s32le"; "f32" -> "pcm_f32le"; else -> "pcm_s16le"
        })
        "aiff" -> a += listOf("-c:a", when (p.bitDepth) {
            "24" -> "pcm_s24be"; "32" -> "pcm_s32be"; "f32" -> "pcm_f32be"; else -> "pcm_s16be"
        })
        "mp3" -> {
            a += listOf("-c:a", "libmp3lame")
            if (p.vbr in 0..spec.vbrMax) a += listOf("-q:a", p.vbr.toString())
            else if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "m4a", "aac" -> {
            a += listOf("-c:a", "aac")
            if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "flac" -> {
            a += listOf("-c:a", "flac")
            if (p.compression in 0..9) a += listOf("-compression_level", p.compression.toString())
        }
        "ogg" -> {
            a += listOf("-c:a", "libvorbis")
            if (p.vbr in 0..spec.vbrMax) a += listOf("-q:a", p.vbr.toString())
            else if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "opus" -> {
            a += listOf("-c:a", "libopus")
            if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "wma" -> {
            a += listOf("-c:a", "wmav2")
            if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "amr" -> a += listOf("-c:a", "libopencore_amrnb")
        "ac3" -> {
            a += listOf("-c:a", "ac3")
            if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "mp2" -> {
            a += listOf("-c:a", "mp2")
            if (p.bitrate > 0) a += listOf("-b:a", "${p.bitrate}k")
        }
        "alac" -> a += listOf("-c:a", "alac")
    }

    when {
        p.ext == "amr" -> a += listOf("-ar", "8000", "-ac", "1")
        p.ext == "opus" -> {
            val sr = if (p.sampleRate in 1..48000) p.sampleRate else 48000
            a += listOf("-ar", sr.toString())
            if (p.channels > 0) a += listOf("-ac", p.channels.toString())
        }
        else -> {
            if (p.sampleRate > 0) a += listOf("-ar", p.sampleRate.toString())
            if (p.channels > 0) a += listOf("-ac", p.channels.toString())
        }
    }
    a += output
    return a
}

// ---------------- 视频格式规格 ----------------

data class VideoFormatSpec(val ext: String, val label: String, val codecs: List<String>, val verifyKey: String)

object VideoFormats {
    val ALL = listOf(
        VideoFormatSpec("mp4", "MP4 通用", listOf("h264", "h265"), "mp4"),
        VideoFormatSpec("mov", "MOV 苹果", listOf("h264"), "mov"),
        VideoFormatSpec("mkv", "MKV 开源", listOf("h264", "h265"), "matroska"),
        VideoFormatSpec("webm", "WEBM 网页", listOf("vp9"), "webm"),
        VideoFormatSpec("avi", "AVI 老牌", listOf("mpeg4"), "avi"),
        VideoFormatSpec("ts", "TS 直播流", listOf("h264"), "mpegts")
    )

    fun byExt(ext: String): VideoFormatSpec? = ALL.firstOrNull { it.ext == ext }
}

data class VideoParams(
    val ext: String = "mp4",
    val vcodec: String = "h264",
    val crf: Int = 23,
    val speed: String = "medium",
    val scaleW: Int = 0,
    val scaleH: Int = 0,
    val fps: Int = 0,
    val audioBitrate: Int = 192
)

fun buildVideoArgs(input: String, output: String, p: VideoParams): List<String> {
    val a = mutableListOf("-y", "-i", input)
    val codec = when (p.vcodec) {
        "h265" -> "libx265"; "vp9" -> "libvpx-vp9"; "mpeg4" -> "mpeg4"; else -> "libx264"
    }
    a += listOf("-c:v", codec)
    when (p.vcodec) {
        "h264", "h265" -> a += listOf("-crf", p.crf.toString(), "-preset", p.speed)
        "vp9" -> a += listOf("-crf", (p.crf + 8).toString(), "-b:v", "0")
        "mpeg4" -> a += listOf("-q:v", "5")
    }
    if (p.scaleW > 0 && p.scaleH > 0) a += listOf("-vf", "scale=${p.scaleW}:${p.scaleH}:flags=lanczos")
    if (p.fps > 0) a += listOf("-r", p.fps.toString())
    if (p.vcodec == "vp9") {
        a += listOf("-c:a", "libopus", "-b:a", "${p.audioBitrate.coerceAtMost(510)}k")
    } else {
        a += listOf("-c:a", "aac", "-b:a", "${p.audioBitrate}k")
    }
    if (p.ext == "mp4" || p.ext == "mov") a += listOf("-movflags", "+faststart")
    a += output
    return a
}

// ---------------- 场景预设 ----------------

data class Preset(val id: String, val name: String, val desc: String, val params: AudioParams)

object Presets {
    val ALL = listOf(
        Preset("release_wav", "发行歌曲·审核标准", "WAV 48kHz/16bit 立体声 —— 主流发行平台审核推荐",
            AudioParams("wav", 48000, 2, 0, "16")),
        Preset("release_mp3", "发行歌曲·MP3", "MP3 320kbps 44.1kHz 立体声 —— 平台通用上传",
            AudioParams("mp3", 44100, 2, 320)),
        Preset("hifi", "高保真收藏", "FLAC 无损 44.1kHz 立体声 —— 音质不变体积更小",
            AudioParams("flac", 44100, 2, 0, "16", -1, 5)),
        Preset("daily", "日常保存", "M4A 256kbps 44.1kHz 立体声 —— 均衡之选",
            AudioParams("m4a", 44100, 2, 256)),
        Preset("share", "社交发送", "M4A 128kbps —— 体积小方便传输",
            AudioParams("m4a", 44100, 2, 128)),
        Preset("voice", "播客/语音", "MP3 128kbps 单声道 —— 人声清晰",
            AudioParams("mp3", 44100, 1, 128))
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** 场景参数校验：用户改过参数后，检查是否仍符合该场景标准 */
    fun conformanceWarnings(preset: Preset, params: AudioParams): List<String> {
        val w = mutableListOf<String>()
        if (preset.id.startsWith("release")) {
            if (params.ext != "wav" && !(params.ext == "mp3" && params.bitrate >= 320))
                w.add("发行平台审核一般要求无损 WAV，或 MP3 320kbps 及以上；当前为 ${AudioFormats.byExt(params.ext)?.label ?: params.ext}")
            if (params.sampleRate in 1..44099) w.add("采样率 ${params.sampleRate}Hz 低于发行标准（≥44100Hz）")
            if (params.channels == 1) w.add("单声道不符合多数发行平台标准（建议立体声）")
        }
        if (preset.id == "hifi" && params.ext !in listOf("flac", "wav", "aiff", "alac"))
            w.add("高保真收藏建议使用 FLAC/WAV/AIFF 等无损格式")
        val spec = AudioFormats.byExt(params.ext)
        if (spec?.supportsBitrate == true && params.vbr < 0 && params.bitrate in 1..127)
            w.add("比特率 ${params.bitrate}kbps 偏低，可能出现明显音质损失")
        return w
    }
}

/** 声道布局转中文显示 */
fun channelLayoutCn(v: String?): String = when (v?.lowercase()) {
    null, "" -> "未知"
    "mono" -> "单声道"
    "stereo" -> "立体声"
    "2 channels" -> "立体声"
    "1 channels" -> "单声道"
    "5.1" -> "5.1 环绕声"
    "5.1(side)" -> "5.1 环绕声"
    "7.1" -> "7.1 环绕声"
    else -> v
}

/** 视频编码速度档位中文说明 */
val SPEED_LABELS = linkedMapOf(
    "ultrafast" to "极快（体积较大）",
    "veryfast" to "很快",
    "medium" to "均衡（推荐）",
    "slow" to "较慢（更清晰）",
    "veryslow" to "极慢（最清晰）"
)
