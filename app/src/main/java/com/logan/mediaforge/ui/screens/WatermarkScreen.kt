package com.logan.mediaforge.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.ImageEngine
import com.logan.mediaforge.core.InpaintEngine
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 去水印页全局状态（切页/重建不丢失） */
object WatermarkState {
    var uri by mutableStateOf<Uri?>(null)
    var name by mutableStateOf("")
    var size by mutableStateOf(0L)
    var detRealName by mutableStateOf("")
    var preview by mutableStateOf<Bitmap?>(null)
    var origW by mutableStateOf(0)
    var origH by mutableStateOf(0)
    var brushMode by mutableStateOf(false)      // false=框选 true=涂抹
    var brushPct by mutableStateOf(3f)          // 笔刷直径占图宽百分比（1..10）
    var box by mutableStateOf<InpaintEngine.BoxRect?>(null)   // 只保留一个框，可移动/缩放
    val strokes = mutableStateListOf<InpaintEngine.Stroke>()

    fun resetSource() {
        uri = null; name = ""; size = 0; detRealName = ""
        preview = null; origW = 0; origH = 0
        box = null
        strokes.clear()
    }

    fun hasMask(): Boolean = box != null || strokes.isNotEmpty()
}

@Composable
fun WatermarkScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st = WatermarkState

    fun onPicked(u: Uri) {
        scope.launch {
            val resolver = context.contentResolver
            val n = withContext(Dispatchers.IO) {
                try {
                    resolver.query(u, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
                    }
                } catch (e: Exception) { null }
            } ?: "image_${System.currentTimeMillis()}"
            val d = withContext(Dispatchers.IO) {
                try { FileSniffer.detect(resolver, u, n.substringAfterLast('.', ""))?.realName } catch (_: Exception) { null }
            }
            val wh = withContext(Dispatchers.IO) { ImageEngine.decodeSize(context, u) }
            val bmp = withContext(Dispatchers.IO) { ImageEngine.decodePreview(context, u, 1440) }
            st.uri = u
            st.name = n
            st.size = try { FileSniffer.querySize(resolver, u) } catch (_: Exception) { 0L }
            st.detRealName = d ?: "未知"
            st.preview = bmp
            st.origW = wh?.first ?: 0
            st.origH = wh?.second ?: 0
            st.box = null
            st.strokes.clear()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPicked(u)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("图片去水印", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "完全离线 · 框选或涂抹要去除的水印 → AI 根据周围画面智能补全，效果自然融入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (st.uri == null) {
            Button(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("第一步：选择图片")
            }
        } else {
            MaskEditor(
                modifier = Modifier.fillMaxWidth(),
                onStrokeDone = { st.strokes.add(it) }
            )

            // 模式选择
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = !st.brushMode,
                    onClick = { st.brushMode = false },
                    label = { Text("框选模式") }
                )
                FilterChip(
                    selected = st.brushMode,
                    onClick = { st.brushMode = true },
                    label = { Text("涂抹模式") }
                )
            }

            if (st.brushMode) {
                Text("笔刷大小：直径约为图片宽度的 ${st.brushPct.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = st.brushPct, onValueChange = { st.brushPct = it }, valueRange = 1f..10f)
            } else {
                Text("框选：拖动画一个框 · 拖框内部移动位置 · 拖四角圆点缩放大小（只保留一个框，重新拖动画新框即替换）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 去水印主按钮（显眼、靠前）
            val hasMask = st.hasMask()
            Button(
                onClick = {
                    val u = st.uri ?: return@Button
                    if (!st.hasMask()) {
                        Toast.makeText(context, "请先框选或涂抹要去除的水印区域", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    TaskRepository.startInpaint(u, st.name, st.box?.let { listOf(it) } ?: emptyList(), st.strokes.toList())
                    Toast.makeText(context, "去水印任务已创建，请到「任务」页查看进度，完成后可下载", Toast.LENGTH_LONG).show()
                },
                enabled = hasMask,
                modifier = Modifier.fillMaxWidth()
            ) { Text("第二步：开始去水印（结果在「任务」页下载）") }
            if (!hasMask) {
                Text("尚未标记任何区域：请先框选一个框，或切换到涂抹模式涂抹水印",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    st.box = null
                }, modifier = Modifier.weight(1f)) { Text("清除框选") }
                OutlinedButton(onClick = {
                    if (st.strokes.isNotEmpty()) st.strokes.removeAt(st.strokes.size - 1)
                }, modifier = Modifier.weight(1f)) { Text("撤销一笔涂抹") }
                OutlinedButton(onClick = {
                    st.box = null; st.strokes.clear()
                }, modifier = Modifier.weight(1f)) { Text("清空全部") }
            }

            Text(
                "文件：${st.name} · ${st.detRealName}" +
                    (if (st.origW > 0) " · ${st.origW}×${st.origH}" else "") +
                    (if (st.size > 0) " · ${fmtBytes(st.size)}" else "") +
                    " · 已标记：框选 ${if (st.box != null) 1 else 0} 处 / 涂抹 ${st.strokes.size} 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(onClick = { st.resetSource() }, modifier = Modifier.fillMaxWidth()) {
                Text("移除 / 重新选择图片")
            }
        }
    }
}

private enum class DragMode { NEW_BOX, MOVE_BOX, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, PAINT }

/**
 * 蒙版编辑器：固定高度容器内等比显示图片（Fit）。
 * 框选模式：单框，拖框内移动、拖四角缩放、拖空白处画新框（替换旧框）。
 * 涂抹模式：白色半透明高亮笔刷，像真实涂抹遮罩。
 */
@Composable
private fun MaskEditor(
    modifier: Modifier,
    onStrokeDone: (InpaintEngine.Stroke) -> Unit
) {
    val st = WatermarkState
    val bmp = st.preview
    BoxWithConstraints(modifier.height(340.dp)) {
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
        val handleR = with(density) { 11.dp.toPx() }

        fun toNorm(p: Offset): Pair<Float, Float> = Pair(
            ((p.x - offX) / drawW).coerceIn(0f, 1f),
            ((p.y - offY) / drawH).coerceIn(0f, 1f)
        )

        fun cornerPx(b: InpaintEngine.BoxRect, i: Int): Offset = when (i) {
            0 -> Offset(offX + b.l * drawW, offY + b.t * drawH)  // 左上
            1 -> Offset(offX + b.r * drawW, offY + b.t * drawH)  // 右上
            2 -> Offset(offX + b.l * drawW, offY + b.b * drawH)  // 左下
            else -> Offset(offX + b.r * drawW, offY + b.b * drawH) // 右下
        }

        var dragMode by remember(bmp) { mutableStateOf<DragMode?>(null) }
        var anchor by remember(bmp) { mutableStateOf(Offset.Zero) }
        var moveBase by remember(bmp) { mutableStateOf<InpaintEngine.BoxRect?>(null) }
        val ptsBuf = remember(bmp) { mutableListOf<Pair<Float, Float>>() }
        var paintTick by remember(bmp) { mutableStateOf(0) }   // 涂抹时触发重绘
        val brushHalf = st.brushPct / 100f / 2f

        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "去水印编辑",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            if (WatermarkState.brushMode) {
                                dragMode = DragMode.PAINT
                                ptsBuf.clear()
                                ptsBuf.add(toNorm(pos))
                            } else {
                                val b = WatermarkState.box
                                if (b != null) {
                                    val idx = (0..3).firstOrNull { i ->
                                        val c = cornerPx(b, i)
                                        abs(pos.x - c.x) <= handleR * 1.6f && abs(pos.y - c.y) <= handleR * 1.6f
                                    }
                                    dragMode = when (idx) {
                                        0 -> DragMode.RESIZE_TL
                                        1 -> DragMode.RESIZE_TR
                                        2 -> DragMode.RESIZE_BL
                                        3 -> DragMode.RESIZE_BR
                                        else -> null
                                    }
                                    if (dragMode == null && pos.x >= offX && pos.x <= offX + drawW &&
                                        pos.y >= offY && pos.y <= offY + drawH
                                    ) {
                                        // 在框内部 → 移动；在框外部 → 画新框（替换）
                                        val inside = pos.x >= offX + b.l * drawW && pos.x <= offX + b.r * drawW &&
                                            pos.y >= offY + b.t * drawH && pos.y <= offY + b.b * drawH
                                        if (inside) {
                                            dragMode = DragMode.MOVE_BOX
                                            moveBase = b
                                            anchor = pos
                                        } else {
                                            dragMode = DragMode.NEW_BOX
                                            val n = toNorm(pos)
                                            WatermarkState.box = InpaintEngine.BoxRect(n.first, n.second, n.first, n.second)
                                            anchor = Offset(n.first, n.second)
                                        }
                                    } else if (dragMode == null) {
                                        dragMode = null
                                    }
                                } else {
                                    dragMode = DragMode.NEW_BOX
                                    val n = toNorm(pos)
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, n.second, n.first, n.second)
                                    anchor = Offset(n.first, n.second)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val p = change.position
                            when (dragMode) {
                                DragMode.PAINT -> {
                                    ptsBuf.add(toNorm(p))
                                    paintTick++
                                }
                                DragMode.NEW_BOX -> {
                                    val n = toNorm(p)
                                    WatermarkState.box = InpaintEngine.BoxRect(anchor.x, anchor.y, n.first, n.second)
                                }
                                DragMode.MOVE_BOX -> {
                                    val base = moveBase ?: return@detectDragGestures
                                    val dx = ((p.x - anchor.x) / drawW).coerceIn(-base.l, 1f - base.r)
                                    val dy = ((p.y - anchor.y) / drawH).coerceIn(-base.t, 1f - base.b)
                                    WatermarkState.box = InpaintEngine.BoxRect(base.l + dx, base.t + dy, base.r + dx, base.b + dy)
                                }
                                DragMode.RESIZE_TL -> {
                                    val n = toNorm(p)
                                    val b = WatermarkState.box ?: return@detectDragGestures
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, n.second, b.r, b.b)
                                }
                                DragMode.RESIZE_TR -> {
                                    val n = toNorm(p)
                                    val b = WatermarkState.box ?: return@detectDragGestures
                                    WatermarkState.box = InpaintEngine.BoxRect(b.l, n.second, n.first, b.b)
                                }
                                DragMode.RESIZE_BL -> {
                                    val n = toNorm(p)
                                    val b = WatermarkState.box ?: return@detectDragGestures
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, b.t, b.r, n.second)
                                }
                                DragMode.RESIZE_BR -> {
                                    val n = toNorm(p)
                                    val b = WatermarkState.box ?: return@detectDragGestures
                                    WatermarkState.box = InpaintEngine.BoxRect(b.l, b.t, n.first, n.second)
                                }
                                null -> { }
                            }
                        },
                        onDragEnd = {
                            if (dragMode == DragMode.PAINT) {
                                val pts = ptsBuf.toList()
                                if (pts.size >= 2) onStrokeDone(InpaintEngine.Stroke(pts, brushHalf))
                                ptsBuf.clear()
                            } else if (dragMode != null) {
                                // 规整化：保证 l<r、t<b，且太小则丢弃
                                val b = WatermarkState.box
                                if (b != null) {
                                    val l = minOf(b.l, b.r); val r = maxOf(b.l, b.r)
                                    val t = minOf(b.t, b.b); val bb = maxOf(b.t, b.b)
                                    WatermarkState.box = if (r - l > 0.008f && bb - t > 0.008f)
                                        InpaintEngine.BoxRect(l, t, r, bb) else null
                                }
                            }
                            dragMode = null
                            moveBase = null
                        },
                        onDragCancel = {
                            ptsBuf.clear()
                            dragMode = null
                            moveBase = null
                        }
                    )
                }
        ) {
            clipRect(offX, offY, offX + drawW, offY + drawH) {
                val stW = WatermarkState
                // 已确认的涂抹笔画（白色半透明，像真实涂抹遮罩）
                val tick = paintTick   // 读取以触发涂抹中实时重绘
                for (s in stW.strokes) drawStrokePath(s, offX, offY, drawW, drawH, Color(0x8CFFFFFF))
                if (dragMode == DragMode.PAINT && ptsBuf.isNotEmpty()) {
                    drawStrokePath(InpaintEngine.Stroke(ptsBuf.toList(), brushHalf), offX, offY, drawW, drawH, Color(0xB3FFFFFF))
                }

                // 框选：亮黄色描边 + 浅色填充 + 四角白色圆点手柄
                val active = stW.box
                if (active != null) {
                    val l = offX + active.l * drawW
                    val t = offY + active.t * drawH
                    val r = offX + active.r * drawW
                    val b = offY + active.b * drawH
                    drawRect(
                        color = Color(0x2EFFF176),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t)
                    )
                    drawRect(
                        color = Color(0xFFFFEB3B),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 3f)
                    )
                    for (i in 0..3) {
                        drawCircle(
                            color = Color(0xFFFFFFFF),
                            radius = handleR,
                            center = cornerPx(active, i)
                        )
                        drawCircle(
                            color = Color(0xFFFBC02D),
                            radius = handleR,
                            center = cornerPx(active, i),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    s: InpaintEngine.Stroke, offX: Float, offY: Float, drawW: Float, drawH: Float, color: Color
) {
    val halfPx = s.halfWidthFrac * drawW
    for (p in s.pts) {
        drawCircle(
            color = color,
            radius = halfPx,
            center = Offset(offX + p.first * drawW, offY + p.second * drawH)
        )
    }
}
