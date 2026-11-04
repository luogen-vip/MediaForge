package com.logan.mediaforge.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.fmtMs
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 可视化裁剪编辑器（转换前 / 转换后通用）：
 * - 时间轴高亮条：直观显示保留区间与当前试听位置（播放头）
 * - 双滑块选择开始 / 结束，实时显示区间与保留时长
 * - 试听：从区间起点开始播放，到区间终点自动停止 —— 听到的就是裁剪后保留的部分
 * - 播放中拖动滑块：立刻跳到拖到的位置继续播（拖到哪听到哪，所见即所听）
 * - durationMs <= 0 时只显示滑块与试听按钮，时间轴隐藏
 */
@Composable
fun TrimEditor(
    durationMs: Long,
    mediaPath: String? = null,
    mediaUri: Uri? = null,
    range: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onActualDurationMs: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // durSec 先用调用方传入的 durationMs 初始化；prepare 后以媒体文件实际时长为准
    // （旧版本任务的 durationMs 可能记录的是裁剪前的源时长，与实际文件不一致）
    var durSec by remember(mediaPath, mediaUri) { mutableFloatStateOf(if (durationMs > 0) durationMs / 1000f else 0f) }

    var playing by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var posMs by remember(mediaPath, mediaUri) { mutableIntStateOf(0) }
    var ready by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var failed by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    val player = remember(mediaPath, mediaUri) { MediaPlayer() }

    // 准备播放器（支持本地路径或 content Uri；视频源也可试听其中的声音）
    LaunchedEffect(mediaPath, mediaUri) {
        try {
            if (mediaUri != null) player.setDataSource(context, mediaUri)
            else if (mediaPath != null) player.setDataSource(mediaPath)
            else return@LaunchedEffect
            player.prepare()
            val d = try { player.duration.toLong() } catch (_: Exception) { 0L }
            if (d > 0) {
                durSec = d / 1000f
                onActualDurationMs(d)
            }
            ready = true
        } catch (e: Exception) {
            ready = false
            failed = true
        }
    }
    DisposableEffect(mediaPath, mediaUri) {
        onDispose {
            try { player.release() } catch (_: Exception) { }
        }
    }

    // 播放轮询：更新播放头位置；到达区间终点自动停止并回到起点
    LaunchedEffect(playing, range.start, range.endInclusive) {
        while (playing) {
            try {
                val p = player.currentPosition
                posMs = p
                if (durSec > 0f && p >= (range.endInclusive * 1000f).toInt()) {
                    try {
                        player.pause()
                        player.seekTo((range.start * 1000f).toInt())
                    } catch (_: Exception) { }
                    posMs = (range.start * 1000f).toInt()
                    playing = false
                }
            } catch (_: Exception) { }
            delay(100)
        }
    }

    // 记录上一次滑块位置，用于判断拖动的是哪一个端点
    var lastStart by remember { mutableFloatStateOf(range.start) }
    var lastEnd by remember { mutableFloatStateOf(range.endInclusive) }

    fun togglePlay() {
        if (!ready) return
        if (playing) {
            try { player.pause() } catch (_: Exception) { }
            playing = false
        } else {
            try {
                // 不在保留区间内时，先跳到区间起点再播；在区间内则从当前位置继续播到终点
                val posSec = posMs / 1000f
                if (durSec <= 0f || posSec < range.start || posSec >= range.endInclusive) {
                    player.seekTo((range.start * 1000f).toInt())
                }
                player.start()
                playing = true
            } catch (_: Exception) { }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ---------- 时间轴可视化 ----------
        if (durSec > 0f) {
            val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
            val selColor = MaterialTheme.colorScheme.primary
            val headColor = MaterialTheme.colorScheme.tertiary
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val trackH = 14.dp.toPx()
                    val y = (size.height - trackH) / 2f
                    val r = CornerRadius(trackH / 2f)
                    // 全曲轨道
                    drawRoundRect(color = trackColor, topLeft = Offset(0f, y),
                        size = Size(w, trackH), cornerRadius = r)
                    // 保留区间高亮
                    val s = w * (range.start / durSec)
                    val e = w * (range.endInclusive / durSec)
                    drawRoundRect(color = selColor, topLeft = Offset(s, y),
                        size = Size((e - s).coerceAtLeast(trackH), trackH), cornerRadius = r)
                    // 播放头
                    if (posMs > 0) {
                        val px = w * ((posMs / 1000f) / durSec)
                        drawLine(color = headColor, start = Offset(px, y - 6.dp.toPx()),
                            end = Offset(px, y + trackH + 6.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                        drawCircle(color = headColor, radius = 4.dp.toPx(),
                            center = Offset(px, y + trackH / 2f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("起点 ${fmtMs((range.start * 1000).toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (playing) "试听位置 ${fmtMs(posMs.toLong())}"
                     else "保留 ${fmtMs(((range.endInclusive - range.start) * 1000).toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playing) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("终点 ${fmtMs((range.endInclusive * 1000).toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------- 双滑块 ----------
        if (durSec > 0f) {
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    val movedStart = abs(r.start - lastStart) > abs(r.endInclusive - lastEnd)
                    onRangeChange(r)
                    // 播放中拖动滑块 → 立即跳到拖到的端点位置试听（拖到哪听到哪）
                    if (playing && ready) {
                        val target = if (movedStart) r.start else r.endInclusive
                        try {
                            player.seekTo((target * 1000f).toInt())
                            posMs = (target * 1000f).toInt()
                        } catch (_: Exception) { }
                    }
                    lastStart = r.start
                    lastEnd = r.endInclusive
                },
                valueRange = 0f..durSec
            )
        }

        // ---------- 试听按钮 ----------
        Button(onClick = { togglePlay() }, modifier = Modifier.fillMaxWidth().height(46.dp)) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null, modifier = Modifier.size(20.dp))
            Text(if (playing) "  停止试听" else "  试听选中区间（从起点播放到终点）",
                style = MaterialTheme.typography.labelLarge)
        }
        Text(if (durSec > 0f) "拖动滑块选好区间后点试听：听到的就是裁剪后保留的部分；播放到终点会自动停止。播放中拖动滑块，声音会立刻跳到你拖到的位置。"
             else if (failed) "无法读取该音频（文件可能已损坏），请直接输入开始/结束时间（秒 / mm:ss）"
             else "无法读取时长，请直接输入开始/结束时间（秒 / mm:ss）",
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
