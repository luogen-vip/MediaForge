package com.logan.mediaforge.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.Detection
import com.logan.mediaforge.core.ImageEngine
import com.logan.mediaforge.core.ImageMeta
import com.logan.mediaforge.core.MediaInfo
import com.logan.mediaforge.core.MediaProbe
import com.logan.mediaforge.core.aspectRatioText
import com.logan.mediaforge.core.channelLayoutCn
import com.logan.mediaforge.core.colorMatrixCn
import com.logan.mediaforge.core.colorRangeCn
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.core.fmtMs
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DetectScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(0L) }
    var det by remember { mutableStateOf<Detection?>(null) }
    var mediaInfo by remember { mutableStateOf<MediaInfo?>(null) }
    var imgSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var imgMeta by remember { mutableStateOf<ImageMeta.Info?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            val n = withContext(Dispatchers.IO) {
                try {
                    resolver.query(u, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
                    }
                } catch (e: Exception) { null }
            } ?: "file_${System.currentTimeMillis()}"
            val d = withContext(Dispatchers.IO) {
                FileSniffer.detect(resolver, u, n.substringAfterLast('.', ""))
            }
            val mi = withContext(Dispatchers.IO) {
                if (d?.category == "AUDIO" || d?.category == "VIDEO") {
                    val saf = FFmpegKitConfig.getSafParameterForRead(context, u)
                    if (saf != null) MediaProbe.probe(saf) else null
                } else null
            }
            val isz = withContext(Dispatchers.IO) {
                if (d?.category == "IMAGE") ImageEngine.decodeSize(context, u) else null
            }
            val im = withContext(Dispatchers.IO) {
                if (d?.category == "IMAGE") ImageMeta.parse(resolver, u) else null
            }
            uri = u
            name = n
            size = FileSniffer.querySize(resolver, u)
            det = d
            mediaInfo = mi
            imgSize = isz
            imgMeta = im
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("文件真实格式识别", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("读取文件「魔数」识别真实格式，不被后缀欺骗；音频/视频/图片还能进一步解析出比特率、采样率、声道、分辨率等全部信息。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(onClick = { pick.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("选择任意文件（图片 / 视频 / 音频 / APK / 压缩包 / 文档 …）")
        }

        if (uri != null && det != null) {
            SectionCard("识别结果：$name") {
                if (det!!.mismatch) {
                    Text(
                        "⚠ 后缀被改过！声称是 .${det!!.declaredExt}，但真实格式是 ${det!!.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold
                    )
                }
                if (det!!.corrupted != null) {
                    Text("⚠ 损坏迹象：${det!!.corrupted}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (!det!!.mismatch && det!!.corrupted == null) {
                    Text("✓ 文件格式与后缀一致，且未见损坏迹象",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
                InfoRow("文件名", name)
                InfoRow("声称后缀", if (det!!.declaredExt.isNotEmpty()) ".${det!!.declaredExt}" else "（无后缀）")
                InfoRow("真实格式", det!!.realName)
                InfoRow("格式类别", when (det!!.category) {
                    "AUDIO" -> "音频"; "VIDEO" -> "视频"; "IMAGE" -> "图片"; "ARCHIVE" -> "压缩包"
                    "DOCUMENT" -> "文档"; "EXECUTABLE" -> "可执行文件"; "TEXT" -> "文本"
                    else -> "其他"
                })
                InfoRow("文件大小", if (size > 0) fmtBytes(size) else "未知")
            }

            // 媒体详情：能识别出来的信息全部显示
            val mi = mediaInfo
            val isz = imgSize
            if (mi != null) {
                SectionCard("媒体详情", "由 FFprobe 真实解析；视频流与音频流参数分开列出（各自是各自的）") {
                    if (mi.durationMs > 0) InfoRow("时长", fmtMs(mi.durationMs))
                    if (mi.bitrateKbps != null && mi.bitrateKbps > 0) InfoRow("总码率", "${mi.bitrateKbps} kbps")
                    if (mi.formatName.isNotEmpty()) InfoRow("容器", mi.formatName)
                    if (mi.videoCodec != null) {
                        val ratio = if (mi.width != null && mi.height != null)
                            aspectRatioText(mi.width!!, mi.height!!) else ""
                        InfoRow("视频编码", mi.videoCodec!!)
                        if (mi.width != null && mi.height != null)
                            InfoRow("视频分辨率", "${mi.width}×${mi.height}${if (ratio != "") "（比例 $ratio）" else ""}")
                        if (mi.fps != null)
                            InfoRow("帧率", String.format(java.util.Locale.US, "%.2f fps", mi.fps))
                        if (mi.videoBitrateKbps != null) InfoRow("视频流码率", "${mi.videoBitrateKbps} kbps")
                        if (mi.videoBitDepth != null)
                            InfoRow("视频位深", "${mi.videoBitDepth}bit${if (mi.pixFmt != null) "（${mi.pixFmt}）" else ""}")
                        if (mi.colorSpace != null || mi.colorPrimaries != null)
                            InfoRow("色彩空间", colorMatrixCn(mi.colorSpace ?: mi.colorPrimaries))
                        if (mi.colorTransfer != null && mi.colorTransfer != "unknown")
                            InfoRow("传递函数", mi.colorTransfer!!)
                        if (mi.colorRange != null) InfoRow("色彩范围", colorRangeCn(mi.colorRange))
                    }
                    if (mi.audioCodec != null) {
                        InfoRow("音频编码", mi.audioCodec!!)
                        if (mi.audioBitrateKbps != null) InfoRow("音频流码率", "${mi.audioBitrateKbps} kbps")
                        if (mi.audioBitDepth != null) InfoRow("音频位深", "${mi.audioBitDepth}bit${if (mi.audioSampleFmt != null) "（${mi.audioSampleFmt}）" else ""}")
                        if (mi.sampleRate != null) InfoRow("采样率", "${mi.sampleRate} Hz")
                        InfoRow("声道", channelLayoutCn(mi.channelLayout))
                    }
                }
            } else if (isz != null) {
                SectionCard("图片详情", "真实解析（位深 / 色彩模式 / 内嵌 ICC 色彩空间）") {
                    InfoRow("分辨率", "${isz.first}×${isz.second}（比例 ${aspectRatioText(isz.first, isz.second)}）")
                    InfoRow("像素总量", fmtBytes(isz.first.toLong() * isz.second))
                    val m = imgMeta
                    if (m != null) {
                        InfoRow("位深与色彩模式", m.describe())
                        InfoRow("内嵌色彩空间（ICC）", m.colorSpace ?: "未内嵌（按 sRGB 处理）")
                    }
                }
            }
        }
    }
}
