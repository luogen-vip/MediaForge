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
    val audioBitrate: Int = 192,
    // 新增：画面比例（真实生效：裁剪填满或黑边适配，输出宽高比严格等于所选比例）
    val ratio: Float = 0f,          // 0=保持原比例；否则为 w/h（如 16f/9f）
    val ratioFit: String = "crop",  // crop=裁剪填满 / pad=黑边适配
    val srcW: Int = 0,              // 源分辨率（用于计算输出尺寸）
    val srcH: Int = 0,
    // 新增：色彩空间（真实写入/转换，ffprobe 可验证）
    val colorMatrix: String = "",   // ""=保持 / bt709 / bt601 / bt2020
    val colorRange: String = ""     // ""=保持 / tv / pc
) {
    fun describeRatio(): String = when {
        ratio <= 0.01f -> ""
        else -> {
            val label = when {
                kotlin.math.abs(ratio - 16f / 9f) < 0.01f -> "16:9"
                kotlin.math.abs(ratio - 9f / 16f) < 0.01f -> "9:16"
                kotlin.math.abs(ratio - 1f) < 0.01f -> "1:1"
                kotlin.math.abs(ratio - 4f / 3f) < 0.01f -> "4:3"
                kotlin.math.abs(ratio - 3f / 4f) < 0.01f -> "3:4"
                kotlin.math.abs(ratio - 3f / 2f) < 0.01f -> "3:2"
                kotlin.math.abs(ratio - 2f / 3f) < 0.01f -> "2:3"
                kotlin.math.abs(ratio - 21f / 9f) < 0.01f -> "21:9"
                else -> String.format(java.util.Locale.US, "%.2f:1", ratio)
            }
            "比例 $label（${if (ratioFit == "pad") "黑边适配" else "裁剪填满"}）"
        }
    }

    fun describeColor(): String {
        if (colorMatrix.isEmpty() && colorRange.isEmpty()) return ""
        val parts = mutableListOf<String>()
        if (colorMatrix.isNotEmpty()) parts += when (colorMatrix) {
            "bt601" -> "BT.601"; "bt2020" -> "BT.2020"; else -> "BT.709"
        }
        if (colorRange.isNotEmpty()) parts += if (colorRange == "pc") "PC 全范围" else "TV 有限范围"
        return "色彩 ${parts.joinToString("/")}"
    }
}

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

    // ---- 滤镜链：比例裁剪/黑边 + 缩放 + 色彩空间（全部真实生效） ----
    val vf = mutableListOf<String>()
    if (p.ratio > 0.01f) {
        // 容器高度 = 分辨率档位指定高度，否则用源高度；宽度 = 高度 × 比例（偶数化）
        val baseH = if (p.scaleH > 0) p.scaleH else (if (p.srcH > 0) p.srcH else 1080)
        val outH = if (baseH % 2 == 1) baseH + 1 else baseH
        var outW = Math.round(outH * p.ratio).toInt()
        if (outW % 2 == 1) outW += 1
        outW = outW.coerceAtLeast(16)
        if (p.ratioFit == "pad") {
            // 黑边适配：等比缩小到容器内，居中补黑边 —— 画面完整不变形，输出严格 outW×outH
            vf += "scale=$outW:$outH:force_original_aspect_ratio=decrease:flags=lanczos"
            vf += "pad=$outW:$outH:(ow-iw)/2:(oh-ih)/2:black"
        } else {
            // 裁剪填满：等比放大铺满容器，居中裁剪 —— 无变形，输出严格 outW×outH
            vf += "scale=$outW:$outH:force_original_aspect_ratio=increase:flags=lanczos"
            vf += "crop=$outW:$outH"
        }
    } else if (p.scaleW > 0 && p.scaleH > 0) {
        // 老分辨率档位：改为等比适配不拉伸（避免把 9:16 源拉成 16:9）
        vf += "scale=${p.scaleW}:${p.scaleH}:force_original_aspect_ratio=decrease:flags=lanczos"
    }
    if (p.fps > 0) a += listOf("-r", p.fps.toString())

    // 色彩空间真实转换/标记（scale=out_color_matrix 做矩阵转换，输出标签写元数据）
    if (p.colorMatrix.isNotEmpty()) {
        vf += "scale=out_color_matrix=${p.colorMatrix}"
        val (prim, trc) = when (p.colorMatrix) {
            "bt601" -> "smpte170m" to "smpte170m"
            "bt2020" -> "bt2020" to "bt2020-10"
            else -> "bt709" to "bt709"
        }
        a += listOf("-colorspace", p.colorMatrix, "-color_primaries", prim, "-color_trc", trc)
    }
    if (p.colorRange.isNotEmpty()) {
        vf += "scale=in_range=auto:out_range=${p.colorRange}"
        a += listOf("-color_range", p.colorRange)
    }
    if (vf.isNotEmpty()) a += listOf("-vf", vf.joinToString(","))

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

// ---------------- 宽高比 / 色彩空间显示辅助 ----------------

/** 把 宽×高 化成易读的比例（如 16:9、9:16、1:1；无法匹配时给出最简整数比或小数比） */
fun aspectRatioText(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "未知"
    val r = w.toDouble() / h
    // 常见比例（差 2% 以内视为该比例）
    val named = listOf(
        "1:1" to 1.0, "4:3" to 4.0 / 3, "3:4" to 0.75,
        "16:9" to 16.0 / 9, "9:16" to 9.0 / 16,
        "3:2" to 1.5, "2:3" to 2.0 / 3,
        "21:9" to 21.0 / 9, "9:21" to 9.0 / 21,
        "5:4" to 1.25, "4:5" to 0.8,
        "2:1" to 2.0, "1:2" to 0.5,
        "2.35:1" to 2.35, "2.39:1" to 2.39, "1.85:1" to 1.85
    )
    for ((name, v) in named) {
        if (kotlin.math.abs(r - v) / v < 0.02) return name
    }
    // 最简整数比
    var a = w; var b = h
    var x = a; var y = b
    while (y != 0) { val t = x % y; x = y; y = t }
    val g = if (x > 0) x else 1
    a = w / g; b = h / g
    return if (a <= 120 && b <= 120) "$a:$b"
    else String.format(Locale.US, "%.2f:1", r)
}

/** FFmpeg 色彩空间矩阵 → 中文说明 */
fun colorMatrixCn(v: String?): String = when (v?.lowercase()) {
    null, "", "unknown" -> "未标注"
    "bt709" -> "BT.709（高清标准）"
    "bt470bg", "bt601", "smpte170m", "smpte170" -> "BT.601（标清）"
    "bt2020", "bt2020c", "bt2020nc" -> "BT.2020（4K/HDR 广色域）"
    "smpte240m" -> "SMPTE 240M"
    "fcc" -> "FCC（早期电视）"
    "gb r" -> "GBR（RGB 直通）"
    else -> v ?: "未标注"
}

/** FFmpeg 色彩范围 → 中文说明 */
fun colorRangeCn(v: String?): String = when (v?.lowercase()) {
    null, "", "unknown" -> "未标注"
    "tv", "mpeg" -> "TV 有限范围（16-235）"
    "pc", "jpeg", "full" -> "PC 全范围（0-255）"
    else -> v ?: "未标注"
}

/** FFmpeg color_primaries → 色域中文名（真实色域按原色定义判断） */
fun colorGamutCn(primaries: String?): String = when (primaries?.lowercase()) {
    null, "", "unknown" -> "未标注（多数按 BT.709/sRGB 解读）"
    "bt709" -> "Rec.709 / sRGB（标准色域）"
    "bt2020" -> "Rec.2020（广色域，4K/HDR）"
    "smpte431_2", "dci-p3", "display-p3" -> "DCI-P3（广色域）"
    "smpte432_1" -> "Display P3（广色域）"
    "bt470bg" -> "PAL / BT.601（标清）"
    "smpte170m" -> "NTSC / BT.601（标清）"
    "bt470m" -> "早期 NTSC（1953）"
    "film" -> "电影胶片色域"
    "smpte428" -> "ACES（电影工业）"
    else -> primaries ?: "未标注"
}

/** 图片 ICC 描述名 → 色域中文名 */
fun iccGamutCn(iccName: String?): String {
    val n = iccName?.lowercase() ?: return "未内嵌 ICC（一般按 sRGB 处理）"
    return when {
        n.contains("p3") || n.contains("dci") -> "DCI-P3 / Display P3（广色域）"
        n.contains("2020") || n.contains("rec2020") -> "Rec.2020（广色域）"
        n.contains("adobe") -> "Adobe RGB（广色域）"
        n.contains("prophoto") -> "ProPhoto RGB（超广色域）"
        n.contains("srgb") || n.contains("iec61966") -> "sRGB（标准色域）"
        else -> iccName
    }
}
