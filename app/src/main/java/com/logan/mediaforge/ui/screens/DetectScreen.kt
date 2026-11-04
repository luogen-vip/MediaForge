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
import com.logan.mediaforge.core.MediaInfo
import com.logan.mediaforge.core.MediaProbe
import com.logan.mediaforge.core.channelLayoutCn
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
            uri = u
            name = n
            size = FileSniffer.querySize(resolver, u)
            det = d
            mediaInfo = mi
            imgSize = isz
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
                SectionCard("媒体详情", "由 FFprobe 真实解析") {
                    if (mi.durationMs > 0) InfoRow("时长", fmtMs(mi.durationMs))
                    if (mi.bitrateKbps != null && mi.bitrateKbps > 0) InfoRow("总比特率", "${mi.bitrateKbps} kbps")
                    if (mi.formatName.isNotEmpty()) InfoRow("容器", mi.formatName)
                    if (mi.videoCodec != null) {
                        InfoRow("视频编码", mi.videoCodec!!)
                        if (mi.width != null && mi.height != null) InfoRow("视频分辨率", "${mi.width}×${mi.height}")
                    }
                    if (mi.audioCodec != null) {
                        InfoRow("音频编码", mi.audioCodec!!)
                        InfoRow("采样率", if (mi.sampleRate != null) "${mi.sampleRate} Hz" else "未知")
                        InfoRow("声道", channelLayoutCn(mi.channelLayout))
                    }
                }
            } else if (isz != null) {
                SectionCard("图片详情", "真实解析") {
                    InfoRow("分辨率", "${isz.first}×${isz.second}")
                    InfoRow("像素总量", fmtBytes(isz.first.toLong() * isz.second))
                }
            }
        }
    }
}
