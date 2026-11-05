package com.logan.mediaforge.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.logan.mediaforge.core.InpaintEngine
import com.logan.mediaforge.core.MattingEngine

/**
 * 全屏 AI 抠图编辑器：
 * - AI 自动识别主体（人物/动物/花草/物品通用），背景整体变完全透明，输出透明 PNG；
 * - 「涂抹保留」：模型没抠到、想要的部分圈回来（强制不透明）；
 * - 「涂抹擦除」：混进来的背景或水印文字/LOGO 抹掉（强制透明）；
 * - 「智能排除水印/LOGO」：自动剔除贴边小连通域（AI 生成水印文字、平台角标、作者署名的典型特征），
 *   剔除数量会显示在任务详情里；
 * - 点「开始抠图」直接创建任务，进度与结果预览在「任务」页查看与下载。
 */
@Composable
fun MattingEditor(
    bmp: Bitmap,
    modelAvailable: Boolean,
    onDismiss: () -> Unit,
    onStart: (keep: List<InpaintEngine.Stroke>, erase: List<InpaintEngine.Stroke>, excludeMarks: Boolean) -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            EditorBody(bmp, modelAvailable, onDismiss, onStart)
        }
    }
}

@Composable
private fun EditorBody(
    bmp: Bitmap,
    modelAvailable: Boolean,
    onDismiss: () -> Unit,
    onStart: (List<InpaintEngine.Stroke>, List<InpaintEngine.Stroke>, Boolean) -> Unit
) {
    // keep=保留（强制不透明） erase=擦除（强制透明）
    var eraseMode by remember { mutableStateOf(false) }
    var brushPct by remember { mutableStateOf(6f) }        // 笔刷直径占图宽百分比 2..20
    var excludeMarks by remember { mutableStateOf(true) }
    val keepStrokes = remember { mutableStateListOf<InpaintEngine.Stroke>() }
    val eraseStrokes = remember { mutableStateListOf<InpaintEngine.Stroke>() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 抠图（透明背景）", style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) { Text("关闭") }
        }
        Text(
            (if (modelAvailable)
                "内置 U2Net AI 模型（双模型保险：全量版+轻量兼容版，自动选择设备可用的那个），" +
                "自动识别主体，背景与水印变完全透明，输出透明 PNG。" +
                "抠得不准的地方用下面的笔刷卡回来或抹掉。"
            else
                "内置 AI 模型不可用，将使用「背景色智能识别」引擎：适合纯色/渐变背景，" +
                "复杂背景请多用「涂抹保留」圈住主体。") +
            " 全程离线处理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- 图片 + 涂抹区域 ----
        StrokeEditor(
            bmp = bmp,
            keepStrokes = keepStrokes,
            eraseStrokes = eraseStrokes,
            eraseMode = eraseMode,
            brushPct = brushPct
        )

        // ---- 笔刷模式 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !eraseMode, onClick = { eraseMode = false }, label = { Text("涂抹保留") })
            FilterChip(selected = eraseMode, onClick = { eraseMode = true }, label = { Text("涂抹擦除") })
        }
        Text(
            "笔刷大小：直径约为图片宽度的 ${brushPct.toInt()}%" +
                (if (eraseMode) "（把不要的部分抹成透明）" else "（把想要的部分圈成不透明）"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(value = brushPct, onValueChange = { brushPct = it }, valueRange = 2f..20f)

        // ---- 智能排除水印/LOGO ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("智能排除水印文字 / LOGO", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "自动剔除「贴边小区域」（AI 生成水印、平台角标、作者署名的典型特征），剔除数量会显示在任务详情；主体不受影响",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = excludeMarks, onCheckedChange = { excludeMarks = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    if (eraseMode) {
                        if (eraseStrokes.isNotEmpty()) eraseStrokes.removeAt(eraseStrokes.size - 1)
                    } else {
                        if (keepStrokes.isNotEmpty()) keepStrokes.removeAt(keepStrokes.size - 1)
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("撤销一笔") }
            OutlinedButton(
                onClick = { keepStrokes.clear(); eraseStrokes.clear() },
                modifier = Modifier.weight(1f)
            ) { Text("清空涂抹") }
        }

        Button(
            onClick = { onStart(keepStrokes.toList(), eraseStrokes.toList(), excludeMarks) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("开始抠图（输出透明 PNG，结果在「任务」页下载）") }

        Text(
            "提示：涂抹不是必须的，直接点「开始抠图」AI 也会自动识别主体；" +
                "涂抹保留/擦除是在 AI 结果上的手动精修。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 涂抹编辑画布：绿色=保留（不透明），红色=擦除（透明）。
 * 触摸只在图片内容上生效，与去水印页同一套平滑笔迹渲染，所见即所得。
 */
@Composable
private fun StrokeEditor(
    bmp: Bitmap,
    keepStrokes: MutableList<InpaintEngine.Stroke>,
    eraseStrokes: MutableList<InpaintEngine.Stroke>,
    eraseMode: Boolean,
    brushPct: Float
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(380.dp)) {
        // 关键修复：pointerInput 闭包只在首次创建时捕获参数值，直接读参数会永远是旧值
        // （表现为涂抹时粗细自动变回上一次设置）。用 rememberUpdatedState 保证
        // 手势进行中每一刻都读到最新的笔刷粗细与擦除/保留模式。
        val curBrushPct by rememberUpdatedState(brushPct)
        val curEraseMode by rememberUpdatedState(eraseMode)
        val density = LocalDensity.current
        val cw = with(density) { maxWidth.toPx() }
        val ch = with(density) { maxHeight.toPx() }
        if (bmp == null || cw <= 0f || ch <= 0f) return@BoxWithConstraints

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val fit = minOf(cw / bw, ch / bh)
        val drawW = bw * fit
        val drawH = bh * fit
        val offX = (cw - drawW) / 2f
        val offY = (ch - drawH) / 2f

        fun toNorm(p: Offset): Pair<Float, Float> = Pair(
            ((p.x - offX) / drawW).coerceIn(0f, 1f),
            ((p.y - offY) / drawH).coerceIn(0f, 1f)
        )

        val ptsBuf = remember(bmp) { mutableListOf<Pair<Float, Float>>() }
        var paintTick by remember(bmp) { mutableIntStateOf(0) }
        var activeErase by remember(bmp) { mutableStateOf(false) }   // 当前在画的笔是擦除还是保留

        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "抠图编辑",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val insideImage = start.x >= offX && start.x <= offX + drawW &&
                            start.y >= offY && start.y <= offY + drawH
                        if (!insideImage) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.all { it.changedToUp() }) break
                            }
                            return@awaitEachGesture
                        }
                        var started = false
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null) {
                                if (ev.changes.all { it.changedToUp() }) break else continue
                            }
                            if (ev.changes.size > 1) {
                                // 双指：放弃本笔，避免误涂
                                ptsBuf.clear()
                                break
                            }
                            if (!started) {
                                val dx = ch.position.x - start.x
                                val dy = ch.position.y - start.y
                                if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                                    started = true
                                    activeErase = curEraseMode
                                    ptsBuf.clear()
                                    ptsBuf.add(toNorm(ch.position))
                                } else if (ch.changedToUp()) {
                                    // 原地点按（没拖动）：也算一笔涂抹，轻点水印即生效
                                    activeErase = curEraseMode
                                    val stroke = InpaintEngine.Stroke(
                                        listOf(toNorm(start)), curBrushPct / 100f / 2f
                                    )
                                    if (activeErase) eraseStrokes.add(stroke) else keepStrokes.add(stroke)
                                    paintTick++
                                    break
                                }
                            }
                            if (started) {
                                if (ch.positionChange() != Offset.Zero) {
                                    ptsBuf.add(toNorm(ch.position))
                                    paintTick++
                                    ch.consume()
                                }
                            }
                            if (ch.changedToUp()) {
                                if (started) {
                                    val pts = ptsBuf.toList()
                                    // 单点/短划也有效（>=1 个点即成一笔），粗细取当前滑块值
                                    if (pts.isNotEmpty()) {
                                        val stroke = InpaintEngine.Stroke(pts, curBrushPct / 100f / 2f)
                                        if (activeErase) eraseStrokes.add(stroke) else keepStrokes.add(stroke)
                                    }
                                    ptsBuf.clear()
                                }
                                break
                            }
                        }
                    }
                }
        ) {
            clipRect(offX, offY, offX + drawW, offY + drawH) {
                val tick = paintTick
                for (s in keepStrokes)
                    drawStroke(s, offX, offY, drawW, drawH, Color(0x8C69F0AE))
                for (s in eraseStrokes)
                    drawStroke(s, offX, offY, drawW, drawH, Color(0x8CEF5350))
                if (ptsBuf.isNotEmpty()) {
                    drawStroke(
                        InpaintEngine.Stroke(ptsBuf.toList(), brushPct / 100f / 2f),
                        offX, offY, drawW, drawH,
                        if (activeErase) Color(0xB3EF5350) else Color(0xB369F0AE)
                    )
                }
            }
        }
    }
}

/** 与去水印页一致的平滑笔迹渲染（二次贝塞尔 + 圆头描边） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
    s: InpaintEngine.Stroke, offX: Float, offY: Float, drawW: Float, drawH: Float, color: Color
) {
    if (s.pts.isEmpty()) return
    val halfPx = s.halfWidthFrac * drawW
    if (s.pts.size == 1) {
        drawCircle(color, halfPx, Offset(offX + s.pts[0].first * drawW, offY + s.pts[0].second * drawH))
        return
    }
    val path = Path()
    var px = offX + s.pts[0].first * drawW
    var py = offY + s.pts[0].second * drawH
    path.moveTo(px, py)
    for (i in 1 until s.pts.size) {
        val x = offX + s.pts[i].first * drawW
        val y = offY + s.pts[i].second * drawH
        path.quadraticBezierTo(px, py, (px + x) / 2f, (py + y) / 2f)
        px = x; py = y
    }
    path.lineTo(px, py)
    drawPath(path, color, style = Stroke(width = halfPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
