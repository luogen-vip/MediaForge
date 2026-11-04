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
import androidx.compose.foundation.layout.padding
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
    val rects = mutableStateListOf<InpaintEngine.BoxRect>()
    val strokes = mutableStateListOf<InpaintEngine.Stroke>()

    fun resetSource() {
        uri = null; name = ""; size = 0; detRealName = ""
        preview = null; origW = 0; origH = 0
        rects.clear(); strokes.clear()
    }

    fun hasMask(): Boolean = rects.isNotEmpty() || strokes.isNotEmpty()
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
            st.rects.clear()
            st.strokes.clear()
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPicked(u)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("图片去水印", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "完全离线 · 框选或涂抹要去除的水印 → AI 根据周围画面智能补全，效果自然融入。" +
                "任务与进度在「任务」页查看。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (st.uri == null) {
            OutlinedButton(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Text("选择图片")
            }
        } else {
            MaskEditor(
                modifier = Modifier.fillMaxWidth(),
                onRectDone = { st.rects.add(it) },
                onStrokeDone = { st.strokes.add(it) }
            )

            // 模式选择
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = !st.brushMode,
                    onClick = { st.brushMode = false },
                    label = { Text("框选去水印") }
                )
                FilterChip(
                    selected = st.brushMode,
                    onClick = { st.brushMode = true },
                    label = { Text("涂抹去水印") }
                )
            }

            if (st.brushMode) {
                Text("笔刷大小：直径约为图片宽度的 ${st.brushPct.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = st.brushPct, onValueChange = { st.brushPct = it }, valueRange = 1f..10f)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    if (st.brushMode) {
                        if (st.strokes.isNotEmpty()) st.strokes.removeAt(st.strokes.size - 1)
                    } else {
                        if (st.rects.isNotEmpty()) st.rects.removeAt(st.rects.size - 1)
                    }
                }, modifier = Modifier.weight(1f)) { Text("撤销上一笔") }
                OutlinedButton(onClick = {
                    st.rects.clear(); st.strokes.clear()
                }, modifier = Modifier.weight(1f)) { Text("清空全部") }
            }

            Text(
                "文件：${st.name} · ${st.detRealName}" +
                    (if (st.origW > 0) " · ${st.origW}×${st.origH}" else "") +
                    (if (st.size > 0) " · ${fmtBytes(st.size)}" else "") +
                    " · 已标记：框选 ${st.rects.size} 处 / 涂抹 ${st.strokes.size} 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val hasMask = st.hasMask()
            Button(
                onClick = {
                    val u = st.uri ?: return@Button
                    if (!st.hasMask()) {
                        Toast.makeText(context, "请先框选或涂抹要去除的水印区域", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    TaskRepository.startInpaint(u, st.name, st.rects.toList(), st.strokes.toList())
                    Toast.makeText(context, "去水印任务已创建，可在「任务」页查看进度与结果", Toast.LENGTH_SHORT).show()
                },
                enabled = hasMask,
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始去水印（加入任务）") }

            OutlinedButton(onClick = { st.resetSource() }, modifier = Modifier.fillMaxWidth()) {
                Text("移除 / 重新选择")
            }
        }
    }
}

/**
 * 蒙版编辑器：固定高度容器内等比显示图片（Fit），
 * 框选模式拖出矩形；涂抹模式拖动画出笔刷轨迹。
 * 坐标在容器内与图片绘制区域精确映射，任何操作都不会撑爆布局。
 */
@Composable
private fun MaskEditor(
    modifier: Modifier,
    onRectDone: (InpaintEngine.BoxRect) -> Unit,
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

        fun toNorm(p: Offset): Pair<Float, Float> = Pair(
            ((p.x - offX) / drawW).coerceIn(0f, 1f),
            ((p.y - offY) / drawH).coerceIn(0f, 1f)
        )

        // 正在进行的框选 / 笔画
        var activeRect by remember(bmp) { mutableStateOf<InpaintEngine.BoxRect?>(null) }
        var activeStroke by remember(bmp) { mutableStateOf<InpaintEngine.Stroke?>(null) }
        val ptsBuf = remember(bmp) { mutableListOf<Pair<Float, Float>>() }
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
                            val n = toNorm(pos)
                            if (WatermarkState.brushMode) {
                                ptsBuf.clear()
                                ptsBuf.add(n)
                                activeStroke = InpaintEngine.Stroke(ptsBuf.toList(), brushHalf)
                            } else {
                                activeRect = InpaintEngine.BoxRect(n.first, n.second, n.first, n.second)
                            }
                        },
                        onDrag = { change, _ ->
                            val n = toNorm(change.position)
                            if (WatermarkState.brushMode) {
                                ptsBuf.add(n)
                                activeStroke = InpaintEngine.Stroke(ptsBuf.toList(), brushHalf)
                            } else {
                                val s = activeRect
                                if (s != null) {
                                    activeRect = InpaintEngine.BoxRect(s.l, s.t, n.first, n.second)
                                }
                            }
                        },
                        onDragEnd = {
                            if (WatermarkState.brushMode) {
                                activeStroke?.let {
                                    if (it.pts.size >= 2) onStrokeDone(it)
                                }
                                ptsBuf.clear()
                                activeStroke = null
                            } else {
                                activeRect?.let { r ->
                                    val l = minOf(r.l, r.r); val t = minOf(r.t, r.b)
                                    val rr = maxOf(r.l, r.r); val b = maxOf(r.t, r.b)
                                    if (rr - l > 0.005f && b - t > 0.005f) {
                                        onRectDone(InpaintEngine.BoxRect(l, t, rr, b))
                                    }
                                }
                                activeRect = null
                            }
                        },
                        onDragCancel = {
                            ptsBuf.clear()
                            activeStroke = null
                            activeRect = null
                        }
                    )
                }
        ) {
            clipRect(offX, offY, offX + drawW, offY + drawH) {
                val stW = WatermarkState
                // 已确认的框选
                for (r in stW.rects) {
                    val l = offX + r.l * drawW; val t = offY + r.t * drawH
                    val rr = offX + r.r * drawW; val b = offY + r.b * drawH
                    drawRect(
                        color = Color(0x40FFD400),
                        topLeft = Offset(l, t),
                        size = androidx.compose.ui.geometry.Size(rr - l, b - t)
                    )
                    drawRect(
                        color = Color(0xFFFFD400),
                        topLeft = Offset(l, t),
                        size = androidx.compose.ui.geometry.Size(rr - l, b - t),
                        style = Stroke(width = 2.5f)
                    )
                }
                // 正在拖动的框选
                activeRect?.let { r ->
                    val l = offX + minOf(r.l, r.r) * drawW
                    val t = offY + minOf(r.t, r.b) * drawH
                    val rr = offX + maxOf(r.l, r.r) * drawW
                    val b = offY + maxOf(r.t, r.b) * drawH
                    drawRect(
                        color = Color(0x55FFD400),
                        topLeft = Offset(l, t),
                        size = androidx.compose.ui.geometry.Size(rr - l, b - t)
                    )
                    drawRect(
                        color = Color(0xFFFFD400),
                        topLeft = Offset(l, t),
                        size = androidx.compose.ui.geometry.Size(rr - l, b - t),
                        style = Stroke(width = 2.5f)
                    )
                }
                // 已确认的笔画（沿路径画圆，与蒙版生成方式一致）
                for (s in stW.strokes) drawStrokePath(s, offX, offY, drawW, drawH, Color(0x59FF3D00))
                activeStroke?.let { drawStrokePath(it, offX, offY, drawW, drawH, Color(0x80FF3D00)) }
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
