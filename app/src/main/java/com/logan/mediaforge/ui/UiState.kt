package com.logan.mediaforge.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.logan.mediaforge.core.Detection
import com.logan.mediaforge.core.MediaInfo

/**
 * 全局 UI 状态单例：
 * 切换底部导航、小窗/分屏导致 Activity 重建时，已选的源文件与参数都不会丢失。
 */
object ConvertState {
    var pickedUri by mutableStateOf<Uri?>(null)
    var pickedName by mutableStateOf("")
    var pickedSize by mutableStateOf(0L)
    var detection by mutableStateOf<Detection?>(null)
    var mediaInfo by mutableStateOf<MediaInfo?>(null)
    var isVideoSource by mutableStateOf(false)
    var toVideo by mutableStateOf(false)

    // 音频参数（0 = 保持默认 / 跟随源）
    var ext by mutableStateOf("mp3")
    var sr by mutableStateOf(0)
    var ch by mutableStateOf(0)
    var br by mutableStateOf(0)
    var depth by mutableStateOf("16")
    var vbr by mutableStateOf(-1)
    var comp by mutableStateOf(-1)

    // 视频参数
    var vExt by mutableStateOf("mp4")
    var vCodec by mutableStateOf("h264")
    var crf by mutableStateOf(23)
    var speed by mutableStateOf("medium")
    var scaleSel by mutableStateOf("原画")
    var fpsSel by mutableStateOf(0)
    var vAbr by mutableStateOf(192)

    // 预设
    var presetId by mutableStateOf<String?>(null)
    var presetTouched by mutableStateOf(false)
    var muteWarnings by mutableStateOf(false)

    // 裁剪
    var trimOn by mutableStateOf(false)
    var trimStart by mutableStateOf("")
    var trimEnd by mutableStateOf("")

    fun resetSource() {
        pickedUri = null; pickedName = ""; pickedSize = 0
        detection = null; mediaInfo = null; isVideoSource = false; toVideo = false
        presetId = null; presetTouched = false
        trimOn = false; trimStart = ""; trimEnd = ""
    }

    fun resetParams() {
        ext = if (detection?.category == "AUDIO" &&
            com.logan.mediaforge.core.AudioFormats.byExt(detection?.realExt ?: "") != null
        ) detection?.realExt ?: "mp3" else "mp3"
        sr = 0; ch = 0; br = 0; depth = "16"; vbr = -1; comp = -1
        vExt = "mp4"; vCodec = "h264"; crf = 23; speed = "medium"
        scaleSel = "原画"; fpsSel = 0; vAbr = 192
        presetId = null; presetTouched = false
        trimOn = false; trimStart = ""; trimEnd = ""
    }
}

object ImageState {
    var uri by mutableStateOf<Uri?>(null)
    var name by mutableStateOf("")
    var size by mutableStateOf(0L)
    var det by mutableStateOf<Detection?>(null)
    var preview by mutableStateOf<Bitmap?>(null)
    var origW by mutableStateOf(0)
    var origH by mutableStateOf(0)
    var wText by mutableStateOf("")
    var hText by mutableStateOf("")
    var keepRatio by mutableStateOf(true)
    var quality by mutableStateOf(92f)
    var extSel by mutableStateOf("jpg")
    var lastAspect by mutableStateOf(1f)

    fun resetSource() {
        uri = null; name = ""; size = 0; det = null; preview = null
        origW = 0; origH = 0; wText = ""; hText = ""
        lastAspect = 1f
    }
}
