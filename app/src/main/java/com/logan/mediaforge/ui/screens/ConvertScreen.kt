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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
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
import com.logan.mediaforge.core.Presets
import com.logan.mediaforge.core.SPEED_LABELS
import com.logan.mediaforge.core.VideoFormats
import com.logan.mediaforge.core.VideoParams
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.MediaProbe
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.channelLayoutCn
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.core.fmtMs
import com.logan.mediaforge.ui.AudioPlayer
import com.logan.mediaforge.ui.ConvertState
import com.logan.mediaforge.ui.DropdownField
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import com.logan.mediaforge.ui.VideoPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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

private fun secText(sec: Float): String = String.format(Locale.US, "%.1f", sec)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConvertScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = ConvertState

    // 场景校验对话框（本页局部状态）
    var pendingWarnings by remember { mutableStateOf<List<String>?>(null) }

    val spec = AudioFormats.byExt(st.ext.value)
    val extValue = st.ext.value
    val srValue = st.sr.value
    val chValue = st.ch.value
    val brValue = st.br.value
    val depthValue = st.depth.value
    val vbrValue = st.vbr.value
    val compValue = st.comp.value
    val presetIdValue = st.presetId.value

    fun touch() { if (st.presetId.value != null) st.presetTouched.value = true }

    fun applyPreset(id: String) {
        val p = Presets.byId(id) ?: return
        st.presetId.value = id
        st.presetTouched.value = false
        st.ext.value = p.params.ext
        st.sr.value = p.params.sampleRate
        st.ch.value = p.params.channels
        st.br.value = p.params.bitrate
        st.depth.value = p.params.bitDepth
        st.vbr.value = p.params.vbr
        st.comp.value = p.params.compression
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
            st.pickedUri.value = uri
            st.pickedName.value = name
            st.pickedSize.value = size
            st.detection.value = det
            st.mediaInfo.value = info
            st.isVideoSource.value = det?.category == "VIDEO" || (det == null && videoHint)
            st.toVideo.value = false
            st.resetParams()
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri, true)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri, false)
    }

    fun doConvert() {
        val uri = st.pickedUri.value ?: return
        val sSec = if (st.trimOn.value) parseTimeToSec(st.trimStart.value) else null
        val eSec = if (st.trimOn.value) parseTimeToSec(st.trimEnd.value) else null
        if (st.toVideo.value && st.isVideoSource.value) {
            val (sw, sh) = when (st.scaleSel.value) {
                "1080p (1920×1080)" -> 1920 to 1080
                "720p (1280×720)" -> 1280 to 720
                "480p (854×480)" -> 854 to 480
                "360p (640×360)" -> 640 to 360
                else -> 0 to 0
            }
            TaskRepository.startVideo(uri, null, st.pickedName.value,
                VideoParams(st.vExt.value, st.vCodec.value, st.crf.value, st.speed.value, sw, sh, st.fpsSel.value, st.vAbr.value))
        } else {
            if (st.trimOn.value) {
                if (sSec == null || eSec == null || eSec <= sSec) {
                    Toast.makeText(context, "裁剪时间无效：请先拖动滑块或填写开始/结束时间", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            val params = AudioParams(extValue, srValue, chValue, brValue, depthValue, vbrValue, compValue)
            TaskRepository.startAudio(
                uri, null, st.pickedName.value, params,
                if (st.trimOn.value && sSec != null) (sSec * 1000).toLong() else null,
                if (st.trimOn.value && eSec != null) (eSec * 1000).toLong() else null
            )
        }
        Toast.makeText(context, "已加入任务，可在「任务」页查看进度、播放与下载", Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("视频 / 音频转换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("真实 FFmpeg 转码（非改后缀）。支持视频→音频、音频→音频（含同格式改参数）、视频→视频。上传大小无限制。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (st.pickedUri.value == null) {
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
            SectionCard("源文件：${st.pickedName.value}") {
                if (st.isVideoSource.value) {
                    VideoPreview(st.pickedUri.value!!, Modifier.fillMaxWidth().height(220.dp))
                } else {
                    AudioPlayer(mediaUri = st.pickedUri.value)
                }
                if (st.detection.value?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${st.detection.value?.declaredExt}，真实格式是 ${st.detection.value?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (st.detection.value?.corrupted != null) {
                    Text("⚠ ${st.detection.value?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("真实格式", st.detection.value?.realName ?: "未知")
                InfoRow("文件大小", if (st.pickedSize.value > 0) fmtBytes(st.pickedSize.value) else "未知")
                val info = st.mediaInfo.value
                if (info != null) {
                    InfoRow("时长", fmtMs(info.durationMs))
                    if (info.videoCodec != null) {
                        InfoRow("视频编码", "${info.videoCodec} (${info.width}×${info.height})")
                    }
                    if (info.audioCodec != null) {
                        InfoRow("音频编码", info.audioCodec!!)
                        InfoRow("采样率", if (info.sampleRate != null) "${info.sampleRate} Hz" else "未知")
                        InfoRow("声道", channelLayoutCn(info.channelLayout))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        if (st.isVideoSource.value) pickVideo.launch("video/*") else pickAudio.launch("audio/*")
                    }) {
                        Text("替换")
                    }
                    OutlinedButton(onClick = { st.resetSource() }) {
                        Text("移除")
                    }
                }
            }

            // -------- 模式切换（视频源可转视频） --------
            if (st.isVideoSource.value) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !st.toVideo.value, onClick = { st.toVideo.value = false; touch() },
                        label = { Text("转为音频") })
                    FilterChip(selected = st.toVideo.value, onClick = { st.toVideo.value = true; touch() },
                        label = { Text("转为视频") })
                }
            }

            if (st.toVideo.value && st.isVideoSource.value) {
                // -------- 视频转视频参数 --------
                SectionCard("目标视频格式") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoFormats.ALL.forEach { f ->
                            FilterChip(
                                selected = st.vExt.value == f.ext,
                                onClick = { st.vExt.value = f.ext; st.vCodec.value = f.codecs.first(); touch() },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    val vspec = VideoFormats.byExt(st.vExt.value)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("视频编码", vspec?.codecs ?: listOf("h264"), { it }, st.vCodec.value,
                            { st.vCodec.value = it; touch() }, Modifier.weight(1f))
                        DropdownField("画质 CRF（越低越清晰）", (14..32).toList(), { "$it" }, st.crf.value,
                            { st.crf.value = it; touch() }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("编码速度", SPEED_LABELS.keys.toList(),
                            { SPEED_LABELS[it] ?: it }, st.speed.value,
                            { st.speed.value = it; touch() }, Modifier.weight(1f))
                        DropdownField("分辨率",
                            listOf("原画", "1080p (1920×1080)", "720p (1280×720)", "480p (854×480)", "360p (640×360)"),
                            { it }, st.scaleSel.value, { st.scaleSel.value = it; touch() }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("帧率", listOf(0, 24, 30, 60),
                            { if (it == 0) "保持原帧率" else "${it}fps" },
                            st.fpsSel.value, { st.fpsSel.value = it; touch() }, Modifier.weight(1f))
                        DropdownField("音轨码率", listOf(128, 192, 256, 320), { "${it}kbps" },
                            st.vAbr.value, { st.vAbr.value = it; touch() }, Modifier.weight(1f))
                    }
                }
            } else {
                // -------- 场景预设 --------
                SectionCard("场景预设", "点击自动套用该场景的标准参数；套用后仍可手动微调（偏离标准时会收到提醒）") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Presets.ALL.forEach { p ->
                            FilterChip(
                                selected = presetIdValue == p.id,
                                onClick = { if (presetIdValue == p.id) st.presetId.value = null else applyPreset(p.id) },
                                label = { Text(p.name) }
                            )
                        }
                    }
                    val active = presetIdValue?.let { Presets.byId(it) }
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
                                selected = extValue == f.ext,
                                onClick = {
                                    if (extValue != f.ext) {
                                        st.ext.value = f.ext; touch(); st.vbr.value = -1; st.comp.value = -1
                                    }
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
                            srValue, { st.sr.value = it; touch() }, Modifier.weight(1f))
                        DropdownField("声道",
                            listOf(0, 1, 2),
                            { if (it == 0) "默认（跟随源）" else if (it == 1) "单声道" else "立体声" },
                            chValue, { st.ch.value = it; touch() }, Modifier.weight(1f))
                    }
                    if (spec?.isPcm == true) {
                        DropdownField("位深",
                            spec.bitDepths,
                            { if (it == "f32") "32bit 浮点" else "${it}bit" },
                            depthValue, { st.depth.value = it; touch() })
                    }
                    if (spec?.supportsCompression == true) {
                        DropdownField("FLAC 压缩等级", listOf(-1) + (0..9).toList(),
                            { if (it == -1) "默认（5）" else "等级 $it" },
                            compValue, { st.comp.value = it; touch() })
                    }
                    if (spec?.supportsBitrate == true) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("VBR 可变码率", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(checked = vbrValue >= 0,
                                onCheckedChange = { st.vbr.value = if (it) 4 else -1; touch() })
                        }
                        if (vbrValue >= 0) {
                            Text("VBR 质量：q$vbrValue（数字越大音质越好）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(value = vbrValue.coerceIn(0, 9).toFloat(),
                                onValueChange = { st.vbr.value = it.toInt(); touch() },
                                valueRange = 0f..9f, steps = 8)
                        } else {
                            Text("比特率：${if (brValue > 0) "${brValue}kbps" else "默认（${spec.defaultBitrate}kbps）"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (if (brValue > 0) brValue else spec.defaultBitrate)
                                    .coerceIn(spec.bitrateRange.first, spec.bitrateRange.last).toFloat(),
                                onValueChange = { st.br.value = it.toInt(); touch() },
                                valueRange = spec.bitrateRange.first.toFloat()..spec.bitrateRange.last.toFloat()
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 128, 192, 256, 320).filter { it in spec.bitrateRange }.forEach { v ->
                                    FilterChip(selected = brValue == v, onClick = { st.br.value = v; touch() },
                                        label = { Text("${v}k") })
                                }
                                FilterChip(selected = brValue == 0, onClick = { st.br.value = 0; touch() },
                                    label = { Text("默认") })
                            }
                        }
                    }
                }

                // -------- 裁剪（滑块 + 输入） --------
                SectionCard("裁剪（可选）", "拖动滑块两端选择保留区间，实时显示；也可直接输入时间（秒 / mm:ss）") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("启用裁剪", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = st.trimOn.value, onCheckedChange = { st.trimOn.value = it; touch() })
                    }
                    if (st.trimOn.value) {
                        val durSec = (st.mediaInfo.value?.durationMs ?: 0L) / 1000f
                        var trimRange by remember { mutableStateOf(0f..0f) }
                        LaunchedEffect(st.trimOn.value, durSec) {
                            if (durSec > 0f) trimRange = 0f..durSec
                        }
                        if (durSec > 0f) {
                            RangeSlider(
                                value = trimRange,
                                onValueChange = { r ->
                                    trimRange = r
                                    st.trimStart.value = secText(r.start)
                                    st.trimEnd.value = secText(r.endInclusive)
                                },
                                valueRange = 0f..durSec
                            )
                            Text(
                                "裁剪区间：${fmtMs((trimRange.start * 1000).toLong())} → ${fmtMs((trimRange.endInclusive * 1000).toLong())}，保留 ${fmtMs(((trimRange.endInclusive - trimRange.start) * 1000).toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("源时长：${fmtMs(st.mediaInfo.value!!.durationMs)}（预览播放可帮你确认裁剪位置）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = st.trimStart.value, onValueChange = { st.trimStart.value = it },
                                label = { Text("开始（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = st.trimEnd.value, onValueChange = { st.trimEnd.value = it },
                                label = { Text("结束（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val preset = presetIdValue?.let { Presets.byId(it) }
                    val params = AudioParams(extValue, srValue, chValue, brValue, depthValue, vbrValue, compValue)
                    val warnings = if (preset != null && st.presetTouched.value && !st.muteWarnings.value)
                        Presets.conformanceWarnings(preset, params) else emptyList()
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
        var muteChecked by remember { mutableStateOf(ConvertState.muteWarnings.value) }
        AlertDialog(
            onDismissRequest = { pendingWarnings = null },
            title = { Text("场景参数提示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Text("你可以按当前参数继续转换，或一键恢复该场景的标准参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = muteChecked, onCheckedChange = { muteChecked = it })
                        Text("以后不再提醒场景标准", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ConvertState.muteWarnings.value = muteChecked
                    pendingWarnings = null
                    doConvert()
                }) { Text("仍然转换") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val p = presetIdValue?.let { Presets.byId(it) }
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
