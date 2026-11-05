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
import com.logan.mediaforge.core.aspectRatioText
import com.logan.mediaforge.core.channelLayoutCn
import com.logan.mediaforge.core.colorGamutCn
import com.logan.mediaforge.core.colorMatrixCn
import com.logan.mediaforge.core.colorRangeCn
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.core.fmtMs
import com.logan.mediaforge.ui.AudioPlayer
import com.logan.mediaforge.ui.ConvertState
import com.logan.mediaforge.ui.DropdownField
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import com.logan.mediaforge.ui.TrimEditor
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
    // 双引擎交叉校验警告（防参数被篡改）
    var crossWarn by remember { mutableStateOf<String?>(null) }

    val spec = AudioFormats.byExt(st.ext)
    val extValue = st.ext
    val srValue = st.sr
    val chValue = st.ch
    val brValue = st.br
    val depthValue = st.depth
    val vbrValue = st.vbr
    val compValue = st.comp
    val presetIdValue = st.presetId

    fun touch() { if (st.presetId != null) st.presetTouched = true }

    fun applyPreset(id: String) {
        val p = Presets.byId(id) ?: return
        st.presetId = id
        st.presetTouched = false
        st.ext = p.params.ext
        st.sr = p.params.sampleRate
        st.ch = p.params.channels
        st.br = p.params.bitrate
        st.depth = p.params.bitDepth
        st.vbr = p.params.vbr
        st.comp = p.params.compression
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
            st.pickedUri = uri
            st.pickedName = name
            st.pickedSize = size
            st.detection = det
            st.mediaInfo = info
            crossWarn = info?.let { withContext(Dispatchers.IO) { MediaProbe.crossCheck(context, uri, it) } }
            st.isVideoSource = det?.category == "VIDEO" || (det == null && videoHint)
            st.toVideo = false
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
        val uri = st.pickedUri ?: return
        val sSec = if (st.trimOn) parseTimeToSec(st.trimStart) else null
        val eSec = if (st.trimOn) parseTimeToSec(st.trimEnd) else null
        if (st.toVideo && st.isVideoSource) {
            val (sw, sh) = when (st.scaleSel) {
                "1080p (1920×1080)" -> 1920 to 1080
                "720p (1280×720)" -> 1280 to 720
                "480p (854×480)" -> 854 to 480
                "360p (640×360)" -> 640 to 360
                else -> 0 to 0
            }
            TaskRepository.startVideo(uri, null, st.pickedName,
                VideoParams(
                    ext = st.vExt, vcodec = st.vCodec, crf = st.crf, speed = st.speed,
                    scaleW = sw, scaleH = sh, fps = st.fpsSel, audioBitrate = st.vAbr,
                    ratio = st.vRatio, ratioFit = st.vRatioFit,
                    srcW = st.mediaInfo?.width ?: 0, srcH = st.mediaInfo?.height ?: 0,
                    colorMatrix = st.vColorMatrix, colorRange = st.vColorRange
                )
            )
        } else {
            if (st.trimOn) {
                if (sSec == null || eSec == null || eSec <= sSec) {
                    Toast.makeText(context, "裁剪时间无效：请先拖动滑块或填写开始/结束时间", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            val params = AudioParams(extValue, srValue, chValue, brValue, depthValue, vbrValue, compValue)
            TaskRepository.startAudio(
                uri, null, st.pickedName, params,
                if (st.trimOn && sSec != null) (sSec * 1000).toLong() else null,
                if (st.trimOn && eSec != null) (eSec * 1000).toLong() else null
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

        if (st.pickedUri == null) {
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
            SectionCard("源文件：${st.pickedName}") {
                if (st.isVideoSource) {
                    VideoPreview(st.pickedUri!!, Modifier.fillMaxWidth())
                } else {
                    AudioPlayer(mediaUri = st.pickedUri)
                }
                if (st.detection?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${st.detection?.declaredExt}，真实格式是 ${st.detection?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (st.detection?.corrupted != null) {
                    Text("⚠ ${st.detection?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                crossWarn?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                InfoRow("真实格式", st.detection?.realName ?: "未知")
                InfoRow("文件大小", if (st.pickedSize > 0) fmtBytes(st.pickedSize) else "未知")
                val info = st.mediaInfo
                if (info != null) {
                    InfoRow("时长", fmtMs(info.durationMs))
                    if (info.videoCodec != null) {
                        val wh = "${info.width}×${info.height}"
                        val ratio = if (info.width != null && info.height != null)
                            aspectRatioText(info.width!!, info.height!!) else ""
                        InfoRow("视频编码", "${info.videoCodec} ($wh${if (ratio != "") " · $ratio" else ""})")
                        if (info.fps != null)
                            InfoRow("帧率", String.format(java.util.Locale.US, "%.2f fps", info.fps))
                        if (info.bitrateKbps != null) InfoRow("总码率", "${info.bitrateKbps} kbps")
                        // 视频码率与音频码率分开显示（各自是各自的参数）
                        if (info.videoBitrateKbps != null)
                            InfoRow("视频流码率", "${info.videoBitrateKbps} kbps")
                        if (info.videoBitDepth != null)
                            InfoRow("视频位深", "${info.videoBitDepth}bit${if (info.pixFmt != null) "（${info.pixFmt}）" else ""}")
                        if (info.colorSpace != null || info.colorPrimaries != null)
                            InfoRow("色彩空间", colorMatrixCn(info.colorSpace ?: info.colorPrimaries))
                        InfoRow("色域", colorGamutCn(info.colorPrimaries))
                        if (info.colorRange != null) InfoRow("色彩范围", colorRangeCn(info.colorRange))
                    }
                    if (info.audioCodec != null) {
                        InfoRow("音频编码", info.audioCodec!!)
                        // 有声视频：音频流自己的码率/位深/采样率（与视频流分开）
                        if (info.audioBitrateKbps != null)
                            InfoRow("音频流码率", "${info.audioBitrateKbps} kbps")
                        if (info.audioBitDepth != null) InfoRow("音频位深", "${info.audioBitDepth}bit")
                        InfoRow("采样率", if (info.sampleRate != null) "${info.sampleRate} Hz" else "未知")
                        InfoRow("声道", channelLayoutCn(info.channelLayout))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        if (st.isVideoSource) pickVideo.launch("video/*") else pickAudio.launch("audio/*")
                    }) {
                        Text("替换")
                    }
                    OutlinedButton(onClick = { st.resetSource() }) {
                        Text("移除")
                    }
                }
            }

            // -------- 模式切换（视频源可转视频） --------
            if (st.isVideoSource) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !st.toVideo, onClick = { st.toVideo = false; touch() },
                        label = { Text("转为音频") })
                    FilterChip(selected = st.toVideo, onClick = { st.toVideo = true; touch() },
                        label = { Text("转为视频") })
                }
            }

            if (st.toVideo && st.isVideoSource) {
                // -------- 视频转视频参数 --------
                SectionCard("目标视频格式") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoFormats.ALL.forEach { f ->
                            FilterChip(
                                selected = st.vExt == f.ext,
                                onClick = { st.vExt = f.ext; st.vCodec = f.codecs.first(); touch() },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    val vspec = VideoFormats.byExt(st.vExt)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("视频编码", vspec?.codecs ?: listOf("h264"), { it }, st.vCodec,
                            { st.vCodec = it; touch() }, Modifier.weight(1f))
                        DropdownField("画质 CRF（越低越清晰）", (14..32).toList(), { "$it" }, st.crf,
                            { st.crf = it; touch() }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("编码速度", SPEED_LABELS.keys.toList(),
                            { SPEED_LABELS[it] ?: it }, st.speed,
                            { st.speed = it; touch() }, Modifier.weight(1f))
                        DropdownField("分辨率",
                            listOf("原画", "1080p (1920×1080)", "720p (1280×720)", "480p (854×480)", "360p (640×360)"),
                            { it }, st.scaleSel, { st.scaleSel = it; touch() }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("帧率", listOf(0, 24, 30, 60),
                            { if (it == 0) "保持原帧率" else "${it}fps" },
                            st.fpsSel, { st.fpsSel = it; touch() }, Modifier.weight(1f))
                        DropdownField("音轨码率", listOf(128, 192, 256, 320), { "${it}kbps" },
                            st.vAbr, { st.vAbr = it; touch() }, Modifier.weight(1f))
                    }
                    // ---- 画面比例（真实生效：输出文件宽高比严格等于所选比例） ----
                    DropdownField(
                        "画面比例（选了比例输出就是这个比例）",
                        listOf(0f, 16f / 9f, 9f / 16f, 1f, 4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 21f / 9f),
                        {
                            when (it) {
                                0f -> "原比例（不变）"
                                16f / 9f -> "16:9（横屏）"
                                9f / 16f -> "9:16（竖屏/短视频）"
                                1f -> "1:1（正方形）"
                                4f / 3f -> "4:3"
                                3f / 4f -> "3:4（竖屏）"
                                3f / 2f -> "3:2"
                                2f / 3f -> "2:3（竖屏）"
                                else -> "21:9（宽银幕）"
                            }
                        },
                        st.vRatio, { st.vRatio = it; touch() }
                    )
                    if (st.vRatio > 0.01f) {
                        DropdownField(
                            "比例适配方式",
                            listOf("crop", "pad"),
                            { if (it == "crop") "裁剪填满（不变形，裁掉多余画面）" else "黑边适配（不变形，补黑边）" },
                            st.vRatioFit, { st.vRatioFit = it; touch() }
                        )
                        val tierH = when (st.scaleSel) {
                            "1080p (1920×1080)" -> 1080
                            "720p (1280×720)" -> 720
                            "480p (854×480)" -> 480
                            "360p (640×360)" -> 360
                            else -> 0
                        }
                        val srcH = st.mediaInfo?.height ?: 0
                        val baseH = if (tierH > 0) tierH else if (srcH > 0) srcH else 1080
                        var ow = Math.round(baseH * st.vRatio).toInt(); if (ow % 2 == 1) ow += 1
                        val oh = if (baseH % 2 == 1) baseH + 1 else baseH
                        Text(
                            "输出分辨率将约为 ${ow}×$oh（源分辨率 ${if (srcH > 0) "${st.mediaInfo?.width}×$srcH" else "未知"}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ---- 色彩空间（真实转换并写入元数据，ffprobe 可验证） ----
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField("色彩空间", listOf("", "bt709", "bt601", "bt2020"),
                            { if (it.isEmpty()) "保持原样" else colorMatrixCn(it) },
                            st.vColorMatrix, { st.vColorMatrix = it; touch() }, Modifier.weight(1f))
                        DropdownField("色彩范围", listOf("", "tv", "pc"),
                            { if (it.isEmpty()) "保持原样" else colorRangeCn(it) },
                            st.vColorRange, { st.vColorRange = it; touch() }, Modifier.weight(1f))
                    }
                }
            } else {
                // -------- 场景预设 --------
                SectionCard("场景预设", "点击自动套用该场景的标准参数；套用后仍可手动微调（偏离标准时会收到提醒）") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Presets.ALL.forEach { p ->
                            FilterChip(
                                selected = presetIdValue == p.id,
                                onClick = { if (presetIdValue == p.id) st.presetId = null else applyPreset(p.id) },
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
                                        st.ext = f.ext; touch(); st.vbr = -1; st.comp = -1
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
                            srValue, { st.sr = it; touch() }, Modifier.weight(1f))
                        DropdownField("声道",
                            listOf(0, 1, 2),
                            { if (it == 0) "默认（跟随源）" else if (it == 1) "单声道" else "立体声" },
                            chValue, { st.ch = it; touch() }, Modifier.weight(1f))
                    }
                    if (spec?.isPcm == true) {
                        DropdownField("位深",
                            spec.bitDepths,
                            { if (it == "f32") "32bit 浮点" else "${it}bit" },
                            depthValue, { st.depth = it; touch() })
                    }
                    if (spec?.supportsCompression == true) {
                        DropdownField("FLAC 压缩等级", listOf(-1) + (0..9).toList(),
                            { if (it == -1) "默认（5）" else "等级 $it" },
                            compValue, { st.comp = it; touch() })
                    }
                    if (spec?.supportsBitrate == true) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("VBR 可变码率", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(checked = vbrValue >= 0,
                                onCheckedChange = { st.vbr = if (it) 4 else -1; touch() })
                        }
                        if (vbrValue >= 0) {
                            Text("VBR 质量：q$vbrValue（数字越大音质越好）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(value = vbrValue.coerceIn(0, 9).toFloat(),
                                onValueChange = { st.vbr = it.toInt(); touch() },
                                valueRange = 0f..9f, steps = 8)
                        } else {
                            Text("比特率：${if (brValue > 0) "${brValue}kbps" else "默认（${spec.defaultBitrate}kbps）"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = (if (brValue > 0) brValue else spec.defaultBitrate)
                                    .coerceIn(spec.bitrateRange.first, spec.bitrateRange.last).toFloat(),
                                onValueChange = { st.br = it.toInt(); touch() },
                                valueRange = spec.bitrateRange.first.toFloat()..spec.bitrateRange.last.toFloat()
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(64, 128, 192, 256, 320).filter { it in spec.bitrateRange }.forEach { v ->
                                    FilterChip(selected = brValue == v, onClick = { st.br = v; touch() },
                                        label = { Text("${v}k") })
                                }
                                FilterChip(selected = brValue == 0, onClick = { st.br = 0; touch() },
                                    label = { Text("默认") })
                            }
                        }
                    }
                }

                // -------- 裁剪（可视化时间轴 + 试听 + 输入） --------
                SectionCard("裁剪（可选）", "拖动滑块选择保留区间 → 点「试听」确认起止点是否满意 → 满意后开始转换；也可直接输入时间（秒 / mm:ss）") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("启用裁剪", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = st.trimOn, onCheckedChange = { st.trimOn = it; touch() })
                    }
                    if (st.trimOn) {
                        val durSec = (st.mediaInfo?.durationMs ?: 0L) / 1000f
                        var trimRange by remember { mutableStateOf(0f..0f) }
                        LaunchedEffect(st.trimOn, durSec) {
                            if (durSec > 0f) trimRange = 0f..durSec
                        }
                        if (durSec > 0f) {
                            TrimEditor(
                                durationMs = st.mediaInfo!!.durationMs,
                                mediaUri = st.pickedUri,
                                range = trimRange,
                                onRangeChange = { r ->
                                    trimRange = r
                                    st.trimStart = secText(r.start)
                                    st.trimEnd = secText(r.endInclusive)
                                }
                            )
                            Text("源时长：${fmtMs(st.mediaInfo!!.durationMs)}${if (st.isVideoSource) "（视频源试听的是视频里的声音）" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = st.trimStart, onValueChange = { v ->
                                st.trimStart = v
                                parseTimeToSec(v)?.let { s ->
                                    if (durSec > 0f) {
                                        val ns = s.toFloat().coerceIn(0f, durSec)
                                        trimRange = ns..trimRange.endInclusive.coerceAtLeast(ns)
                                    }
                                }
                            },
                                label = { Text("开始（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = st.trimEnd, onValueChange = { v ->
                                st.trimEnd = v
                                parseTimeToSec(v)?.let { e ->
                                    if (durSec > 0f) {
                                        val ne = e.toFloat().coerceIn(0f, durSec)
                                        trimRange = trimRange.start.coerceAtMost(ne)..ne
                                    }
                                }
                            },
                                label = { Text("结束（秒 / mm:ss）") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val preset = presetIdValue?.let { Presets.byId(it) }
                    val params = AudioParams(extValue, srValue, chValue, brValue, depthValue, vbrValue, compValue)
                    val warnings = if (preset != null && st.presetTouched && !st.muteWarnings)
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
        var muteChecked by remember { mutableStateOf(ConvertState.muteWarnings) }
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
                    ConvertState.muteWarnings = muteChecked
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
