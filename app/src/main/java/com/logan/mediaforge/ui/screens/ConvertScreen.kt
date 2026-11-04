package com.logan.mediaforge.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.logan.mediaforge.core.AudioFormats
import com.logan.mediaforge.core.AudioParams
import com.logan.mediaforge.core.Detection
import com.logan.mediaforge.core.MediaInfo
import com.logan.mediaforge.core.MediaProbe
import com.logan.mediaforge.core.Presets
import com.logan.mediaforge.core.VideoFormats
import com.logan.mediaforge.core.VideoParams
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.core.fmtMs
import com.logan.mediaforge.ui.AudioPlayer
import com.logan.mediaforge.ui.DropdownField
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import com.logan.mediaforge.ui.VideoPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SAMPLE_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000)

private fun parseTimeToSec(s: String): Double? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return try {
        if (t.contains(':')) {
            var sec = 0.0
            for (p in t.split(':')) sec = sec * 60 + p.toDouble()
            sec
        } else t.toDouble()
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvertScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 源文件状态
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf("") }
    var pickedSize by remember { mutableStateOf(0L) }
    var detection by remember { mutableStateOf<Detection?>(null) }
    var mediaInfo by remember { mutableStateOf<MediaInfo?>(null) }
    var isVideoSource by remember { mutableStateOf(false) }
    var toVideo by remember { mutableStateOf(false) }

    // 音频参数（0 = 保持默认 / 跟随源）
    var ext by remember { mutableStateOf("mp3") }
    var sr by remember { mutableStateOf(0) }
    var ch by remember { mutableStateOf(0) }
    var br by remember { mutableStateOf(0) }
    var depth by remember { mutableStateOf("16") }
    var vbr by remember { mutableStateOf(-1) }
    var comp by remember { mutableStateOf(-1) }

    // 视频参数
    var vExt by remember { mutableStateOf("mp4") }
    var vCodec by remember { mutableStateOf("h264") }
    var crf by remember { mutableStateOf(23) }
    var speed by remember { mutableStateOf("medium") }
    var scaleSel by remember { mutableStateOf("原画") }
    var fpsSel by remember { mutableStateOf(0) }
    var vAbr by remember { mutableStateOf(192) }

    // 预设
    var presetId by remember { mutableStateOf<String?>(null) }
    var presetTouched by remember { mutableStateOf(false) }

    // 裁剪
    var trimOn by remember { mutableStateOf(false) }
    var trimStart by remember { mutableStateOf("") }
    var trimEnd by remember { mutableStateOf("") }

    // 场景校验对话框
    var pendingWarnings by remember { mutableStateOf<List<String>?>(null) }

    val spec = AudioFormats.byExt(ext)

    fun touch() { if (presetId != null) presetTouched = true }

    fun applyPreset(id: String) {
        val p = Presets.byId(id) ?: return
        presetId = id
        presetTouched = false
        ext = p.params.ext
        sr = p.params.sampleRate
        ch = p.params.channels
        br = p.params.bitrate
        depth = p.params.bitDepth
        vbr = p.params.vbr
        comp = p.params.compression
    }

    fun onPicked(uri: Uri, videoHint: Boolean) {
        scope.launch {
            val resolver = context.contentResolver
            val name = withContext(Dispatchers.IO) {
                try {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
                    }
                } catch (e: Exception) { null }
            } ?: "file_${System.currentTimeMillis()}"
            val det = withContext(Dispatchers.IO) {
                FileSniffer.detect(resolver, uri, name.substringAfterLast('.', ""))
            }
            val info = withContext(Dispatchers.IO) {
                val saf = FFmpegKitConfig.getSafParameterForRead(context, uri)
                if (saf != null) MediaProbe.probe(saf) else null
            }
            val size = FileSniffer.querySize(resolver, uri)
            pickedUri = uri
            pickedName = name
            pickedSize = size
            detection = det
            mediaInfo = info
            isVideoSource = det?.category == "VIDEO" || (det == null && videoHint)
            toVideo = false
            presetId = null
            presetTouched = false
            trimOn = false
            trimStart = ""
            trimEnd = ""
            ext = if (det?.category == "AUDIO" && AudioFormats.byExt(det.realExt) != null) det.realExt else "mp3"
            sr = 0; ch = 0; br = 0; depth = "16"; vbr = -1; comp = -1
            vExt = "mp4"; vCodec = "h264"; crf = 23; speed = "medium"; scaleSel = "原画"; fpsSel = 0; vAbr = 192
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri, true)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri, false)
    }

    fun doConvert() {
        val uri = pickedUri ?: return
        val sSec = if (trimOn) parseTimeToSec(trimStart) else null
        val eSec = if (trimOn) parseTimeToSec(trimEnd) else null
        if (toVideo && isVideoSource) {
            val (sw, sh) = when (scaleSel) {
                "1080p (1920×1080)" -> 1920 to 1080
                "720p (1280×720)" -> 1280 to 720
                "480p (854×480)" -> 854 to 480
                "360p (640×360)" -> 640 to 360
                else -> 0 to 0
            }
            TaskRepository.startVideo(uri, null, pickedName,
                VideoParams(vExt, vCodec, crf, speed, sw, sh, fpsSel, vAbr))
        } else {
            if (trimOn) {
                if (sSec == null || eSec == null || eSec <= sSec) {
                    Toast.makeText(context, "裁剪时间无效：请填写开始/结束时间，且结束大于开始", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            val params = AudioParams(ext, sr, ch, br, depth, vbr, comp)
            TaskRepository.startAudio(
                uri, null, pickedName, params,
                if (trimOn && sSec != null) (sSec * 1000).toLong() else null,
                if (trimOn && eSec != null) (eSec * 1000).toLong() else null
            )
        }
        Toast.makeText(context, "已加入任务，可在「任务」页查看进度、播放与下载", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("视频 / 音频转换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("真实 FFmpeg 转码（非改后缀）。支持视频→音频、音频→音频（含同格式改参数）、视频→视频。上传大小无限制。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (pickedUri == null) {
            SectionCard("选择源文件", "从系统选择视频或音频（也可在「识别」页检验文件真实格式后再转换）") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pickVideo.launch("video/*") }, modifier = Modifier.weight(1f)) {
                        Text("选择视频")
                    }
                    Button(onClick = { pickAudio.launch("audio/*") }, modifier = Modifier.weight(1f)) {
                        Text("选择音频")
                    }
                }
            }
        } else {
            // -------- 源文件预览与信息 --------
            SectionCard("源文件：${pickedName}") {
                if (isVideoSource) {
                    VideoPreview(pickedUri!!, Modifier.fillMaxWidth().height(220.dp))
                } else {
                    AudioPlayer(mediaUri = pickedUri)
                }
                if (detection?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${detection?.declaredExt}，真实格式是 ${detection?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (detection?.corrupted != null) {
                    Text("⚠ ${detection?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("真实格式", detection?.realName ?: "未知")
                InfoRow("文件大小", if (pickedSize > 0) fmtBytes(pickedSize) else "未知")
                if (mediaInfo != null) {
                    InfoRow("时长", fmtMs(mediaInfo!!.durationMs))
                    if (mediaInfo!!.videoCodec != null) {
                        InfoRow("视频编码", "${mediaInfo!!.videoCodec} (${mediaInfo!!.width}×${mediaInfo!!.height})")
                    }
                    if (mediaInfo!!.audioCodec != null) {
                        InfoRow("音频编码", mediaInfo!!.audioCodec!!)
                        if (mediaInfo!!.sampleRate != null) InfoRow("采样率", "${mediaInfo!!.sampleRate} Hz")
                        if (mediaInfo!!.channelLayout != null) InfoRow("声道", mediaInfo!!.channelLayout!!)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { if (isVideoSource) pickVideo.launch("video/*") else pickAudio.launch("audio/*") }) {
                        Text("替换")
                    }
                    OutlinedButton(onClick = {
                        pickedUri = null; detection = null; mediaInfo = null; pickedName = ""; pickedSize = 0
                    }) {
                        Text("移除")
                    }
                }
            }

            // -------- 模式切换（视频源可转视频） --------
            if (isVideoSource) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !toVideo, onClick = { toVideo = false }, label = { Text("转为音频") })
                    FilterChip(selected = toVideo, onClick = { toVideo = true }, label = { Text("转为视频") })
                }
            }

            if (toVideo && isVideoSource) {
                // -------- 视频转视频参数 --------
                SectionCard("目标视频格式") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoFormats.ALL.forEach { f ->
                            FilterChip(
                                selected = vExt == f.ext,
                                onClick = { vExt = f.ext; vCodec = f.codecs.first() },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    val vspec = VideoFormats.byExt(vExt)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("视频编码", vspec?.codecs ?: listOf("h264"), { it }, vCodec,
                            { vCodec = it }, Modifier.weight(1f))
                        DropdownField("画质 CRF（越低越清晰）", (14..32).toList(), { "$it" }, crf,
                            { crf = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("编码速度", listOf("ultrafast", "veryfast", "medium", "slow", "veryslow"),
                            { it }, speed, { speed = it }, Modifier.weight(1f))
                        DropdownField("分辨率", listOf("原画", "1080p (1920×1080)", "720p (1280×720)", "480p (854×480)", "360p (640×360)"),
                            { it }, scaleSel, { scaleSel = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("帧率", listOf(0, 24, 30, 60), { if (it == 0) "保持原帧率" else "${it}fps" },
                            fpsSel, { fpsSel = it }, Modifier.weight(1f))
                        DropdownField("音轨码率", listOf(128, 192, 256, 320), { "${it}kbps" },
                            vAbr, { vAbr = it }, Modifier.weight(1f))
                    }
                }
            } else {
                // -------- 场景预设 --------
                SectionCard("场景预设", "点击自动套用该场景的标准参数；套用后仍可手动微调（偏离标准时会收到提醒）") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Presets.ALL.forEach { p ->
                            FilterChip(
                                selected = presetId == p.id,
                                onClick = { if (presetId == p.id) presetId = null else applyPreset(p.id) },
                                label = { Text(p.name) }
                            )
                        }
                    }
                    val active = presetId?.let { Presets.byId(it) }
                    if (active != null) {
                        Text("已选「${active.name}」：${active.desc}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                }

                // -------- 目标音频格式 --------
                SectionCard("目标音频格式", "全部为真实编码格式，可被播放器与发行平台正确识别") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioFormats.ALL.forEach { f ->
                            FilterChip(
                                selected = ext == f.ext,
                                onClick = {
                                    if (ext != f.ext) { ext = f.ext; touch(); vbr = -1; comp = -1 }
                                },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    if (spec?.notes?.isNotEmpty() == true) {
                        Text("提示：${spec.notes}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // -------- 音频参数 --------
                SectionCard("音频参数（每项都可「保持默认」= 跟随源文件）") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("采样率",
                            listOf(0) + SAMPLE_RATES,
                            { if (it == 0) "默认（跟随源）" else "$it Hz" },
                            sr, { sr = it; touch() }, Modifier.weight(1f))
                        DropdownField("声道",
                            listOf(0, 1, 2),
                            { if (it == 0) "默认（跟随源）" else if (it == 1) "单声道" else "立体声" },
                            ch, { ch = it; touch() }, Modifier.weight(1f))
                    }
                    if (spec?.isPcm == true) {
                        DropdownField("位深",
                            spec.bitDepths,
                            { if (it == "f32") "32bit 浮点" else "${it}bit" },
                            depth, { depth = it; touch() })
                    }
                    if (spec?.supportsCompression == true) {
                        DropdownField("FLAC 压缩等级", listOf(-1) + (0..9).toList(),
                            { if (it == -1) "默认（5）" else "等级 $it" },
                            comp, { comp = it; touch() })
                    }
                    if (spec?.supportsBitrate == true) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("VBR 可变码率", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(checked = vbr >= 0, onCheckedChange = { vbr = if (it) 4 else -1; touch() })
                        }
                        if (vbr >= 0) {
                            Text("VBR 质量：q$vbr（数字越大音质越好）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(value = vbr.coerceIn(0, 9).toFloat(), onValueChange = { vbr = it.toInt(); touch() },
                                valueRange = 0f..9f, steps = 8)
                        } else {
                            Text("比特率：${if (br > 0) "${br}kbps" else "默认（${spec.defaultBitrate}kbps）"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (if (br > 0) br else spec.defaultBitrate).coerceIn(spec.bitrateRange.first, spec.bitrateRange.last).toFloat(),
                                onValueChange = { br = it.toInt(); touch() },
                                valueRange = spec.bitrateRange.first.toFloat()..spec.bitrateRange.last.toFloat()
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 128, 192, 256, 320).filter { it in spec.bitrateRange }.forEach { v ->
                                    FilterChip(selected = br == v, onClick = { br = v; touch() }, label = { Text("${v}k") })
                                }
                                FilterChip(selected = br == 0, onClick = { br = 0; touch() }, label = { Text("默认") })
                            }
                        }
                    }
                }

                // -------- 裁剪 --------
                SectionCard("裁剪（可选）", "掐头去尾：填写开始/结束时间，支持秒数（75）或 mm:ss（1:15）") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("启用裁剪", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = trimOn, onCheckedChange = { trimOn = it })
                    }
                    if (trimOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = trimStart, onValueChange = { trimStart = it },
                                label = { Text("开始（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = trimEnd, onValueChange = { trimEnd = it },
                                label = { Text("结束（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        if (mediaInfo != null && mediaInfo!!.durationMs > 0) {
                            Text("源时长：${fmtMs(mediaInfo!!.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val preset = presetId?.let { Presets.byId(it) }
                    val params = AudioParams(ext, sr, ch, br, depth, vbr, comp)
                    val warnings = if (preset != null && presetTouched) Presets.conformanceWarnings(preset, params) else emptyList()
                    if (warnings.isEmpty()) doConvert() else pendingWarnings = warnings
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开始转换（加入任务）")
            }
        }
    }

    // -------- 场景参数提示对话框 --------
    pendingWarnings?.let { warnings ->
        AlertDialog(
            onDismissRequest = { pendingWarnings = null },
            title = { Text("场景参数提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("你可以按当前参数继续转换，或一键恢复该场景的标准参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingWarnings = null; doConvert() }) { Text("仍然转换") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val p = presetId?.let { Presets.byId(it) }
                        if (p != null) applyPreset(p.id)
                        pendingWarnings = null
                        doConvert()
                    }) { Text("恢复标准参数") }
                    TextButton(onClick = { pendingWarnings = null }) { Text("取消") }
                }
            }
        )
    }
}
