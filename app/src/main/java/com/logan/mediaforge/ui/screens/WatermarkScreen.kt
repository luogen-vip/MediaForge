package com.logan.mediaforge.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
    var realExt by mutableStateOf("")                 // 真实格式扩展名（jpg/png/webp/heic…）
    var hasAlpha by mutableStateOf(false)             // 检测到透明通道（透明图片）
    var transparentMode by mutableStateOf(false)      // 透明模式：水印区域恢复完全透明
    var preview by mutableStateOf<Bitmap?>(null)
    var origW by mutableStateOf(0)
    var origH by mutableStateOf(0)
    var brushMode by mutableStateOf(false)      // false=框选 true=涂抹
    var brushPct by mutableStateOf(3f)          // 笔刷直径占图宽百分比（1..10）
    // 导出格式：follow=跟随原图；jpg/png/webp=强制指定（真实重新编码，非改后缀）
    var outFmt by mutableStateOf("follow")
    var box by mutableStateOf<InpaintEngine.BoxRect?>(null)   // 只保留一个框，可移动/缩放
    val strokes = mutableStateListOf<InpaintEngine.Stroke>()

    fun resetSource() {
        uri = null; name = ""; size = 0; detRealName = ""; realExt = ""; hasAlpha = false
        transparentMode = false
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
            val ext = withContext(Dispatchers.IO) {
                try { FileSniffer.detect(resolver, u, n.substringAfterLast('.', ""))?.realExt ?: "" } catch (_: Exception) { "" }
            }
            val wh = withContext(Dispatchers.IO) { ImageEngine.decodeSize(context, u) }
            val bmp = withContext(Dispatchers.IO) { ImageEngine.decodePreview(context, u, 1440) }
            // 透明通道检测：预览图逐像素扫描是否存在半透明/全透明像素
            val alpha = withContext(Dispatchers.IO) {
                if (bmp == null || !bmp.hasAlpha()) false else try {
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    px.any { (it ushr 24) < 250 }
                } catch (_: Exception) { false }
            }
            st.uri = u
            st.name = n
            st.size = try { FileSniffer.querySize(resolver, u) } catch (_: Exception) { 0L }
            st.detRealName = d ?: "未知"
            st.realExt = ext
            st.hasAlpha = alpha
            st.transparentMode = false
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
                Text("框选：拖框内部移动位置 · 拖四角圆点缩放大小。已有框时点其它位置不会误建新框；要重画请先点「清除框选」再拖动",
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
                    TaskRepository.startInpaint(
                        u, st.name,
                        st.box?.let { listOf(it) } ?: emptyList(), st.strokes.toList(),
                        st.outFmt, st.transparentMode
                    )
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

            // ---- 透明模式（透明图片专用）----
            // JPG 无透明通道：隐藏开关并说明；PNG/WebP 等可开启，开启后输出锁 PNG/WebP，
            // 被涂抹/框选的水印区域不再补画面，而是直接恢复为完全透明
            val isJpgSource = st.realExt == "jpg"
            if (isJpgSource) {
                Text(
                    "检测到原图为 JPG：JPG 不支持透明通道，透明模式不可用（透明图片请使用 PNG/WebP/HEIC 等格式）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("水印区域恢复透明（透明图片专用）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (st.hasAlpha) "已检测到透明通道：开启后水印区域直接变完全透明，不再用画面填补"
                            else "透明 LOGO / 免抠图去水印：开启后涂抹处直接变完全透明（不补画面）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = st.transparentMode, onCheckedChange = {
                        st.transparentMode = it
                        // 透明模式锁定支持透明通道的格式：JPG 自动切回 PNG
                        if (it && st.outFmt == "jpg") st.outFmt = "png"
                    })
                }
            }

            // 导出格式下拉：默认跟随原图，只去水印不改格式；手动指定则真实重新编码。
            // 透明模式下只提供 PNG/WebP（保留透明通道），JPG 会把透明区域变成白底/黑底
            val fmtOptions = if (st.transparentMode) listOf(
                "follow" to "跟随原图格式（仅 PNG/WebP 可保持）",
                "png" to "PNG（推荐）",
                "webp" to "WebP（无损）"
            ) else listOf(
                "follow" to "跟随原图格式",
                "png" to "PNG",
                "jpg" to "JPG",
                "webp" to "WebP"
            )
            var fmtExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { fmtExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("导出格式：${fmtOptions.first { it.first == st.outFmt }.second}")
                }
                DropdownMenu(expanded = fmtExpanded, onDismissRequest = { fmtExpanded = false }) {
                    fmtOptions.forEach { (v, label) ->
                        DropdownMenuItem(
                            text = { Text(if (v == st.outFmt) "✓ $label" else label) },
                            onClick = { st.outFmt = v; fmtExpanded = false }
                        )
                    }
                }
            }
            Text(
                when {
                    st.transparentMode ->
                        "透明模式：水印区域直接完全透明（其余部分透明状态原样保留），输出真实 PNG/无损 WebP；原图非 PNG/WebP 时自动导出 PNG。"
                    st.outFmt == "follow" ->
                        "原图是 JPG/PNG/WebP 时按原格式导出（分辨率不变，只去水印）；HEIC/GIF/BMP/TIFF 等系统无法原样回写的格式会自动导出 PNG，并在任务里提示。"
                    else ->
                        "以所选格式真实重新编码导出（非改后缀，导出后会校验真实格式），分辨率与原图一致。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
 *
 * 笔迹渲染用二次贝塞尔平滑连续路径 + 圆头描边：拖动快慢都不会出现“点点点”的断点，
 * 显示出来的涂抹区域与实际送去修复的蒙版完全一致。
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
        // 注意：这里不再缓存 brushHalf 局部变量！pointerInput(bmp) 闭包只在换图时重建，
        // 缓存会导致调滑块后闭包里还是旧值（画着是粗的、松手保存却是上一次的细的）。
        // 改为在回调里直接读 WatermarkState.brushPct，永远取当前值。

        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "去水印编辑",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(bmp, WatermarkState.brushMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val stW = WatermarkState
                        val insideImage = start.x >= offX && start.x <= offX + drawW &&
                            start.y >= offY && start.y <= offY + drawH

                        // ---- 手势归属判定：与页面滚动不打架 ----
                        // 只有「落在图片内容上的单指手势」才属于编辑（框选/涂抹）；
                        // 图片外的空白、已有框时的框外区域，全部放行给外层 verticalScroll，
                        // 从图片四周/框外滑动就能滚动页面，不再必须从图片下面滑。
                        fun hitBoxMode(): DragMode? {
                            val b = stW.box ?: return DragMode.NEW_BOX
                            val idx = (0..3).firstOrNull { i ->
                                val c = cornerPx(b, i)
                                abs(start.x - c.x) <= handleR * 1.6f && abs(start.y - c.y) <= handleR * 1.6f
                            }
                            return when (idx) {
                                0 -> DragMode.RESIZE_TL
                                1 -> DragMode.RESIZE_TR
                                2 -> DragMode.RESIZE_BL
                                3 -> DragMode.RESIZE_BR
                                else -> {
                                    val insideBox = start.x >= offX + b.l * drawW && start.x <= offX + b.r * drawW &&
                                        start.y >= offY + b.t * drawH && start.y <= offY + b.b * drawH
                                    if (insideBox) DragMode.MOVE_BOX else null
                                }
                            }
                        }
                        val mode: DragMode? = when {
                            !insideImage -> null
                            stW.brushMode -> DragMode.PAINT
                            else -> hitBoxMode()
                        }

                        if (mode == null) {
                            // 不消费任何事件：页面滚动接管，同时点按也不会误建框
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.all { it.changedToUp() }) break
                            }
                            return@awaitEachGesture
                        }

                        // ---- 编辑手势：手动拖动循环（等价 detectDragGestures，可精确控制消费时机）----
                        fun beginEdit(pos: Offset) {
                            dragMode = mode
                            when (mode) {
                                DragMode.PAINT -> {
                                    ptsBuf.clear()
                                    ptsBuf.add(toNorm(pos))
                                }
                                DragMode.NEW_BOX -> {
                                    val n = toNorm(pos)
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, n.second, n.first, n.second)
                                    anchor = Offset(n.first, n.second)
                                }
                                DragMode.MOVE_BOX -> {
                                    moveBase = WatermarkState.box
                                    anchor = pos
                                }
                                else -> { }
                            }
                        }
                        fun moveEdit(pos: Offset) {
                            when (mode) {
                                DragMode.PAINT -> {
                                    ptsBuf.add(toNorm(pos))
                                    paintTick++
                                }
                                DragMode.NEW_BOX -> {
                                    val n = toNorm(pos)
                                    WatermarkState.box = InpaintEngine.BoxRect(anchor.x, anchor.y, n.first, n.second)
                                }
                                DragMode.MOVE_BOX -> {
                                    val base = moveBase ?: return
                                    val dx = ((pos.x - anchor.x) / drawW).coerceIn(-base.l, 1f - base.r)
                                    val dy = ((pos.y - anchor.y) / drawH).coerceIn(-base.t, 1f - base.b)
                                    WatermarkState.box = InpaintEngine.BoxRect(base.l + dx, base.t + dy, base.r + dx, base.b + dy)
                                }
                                DragMode.RESIZE_TL -> {
                                    val n = toNorm(pos)
                                    val b = WatermarkState.box ?: return
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, n.second, b.r, b.b)
                                }
                                DragMode.RESIZE_TR -> {
                                    val n = toNorm(pos)
                                    val b = WatermarkState.box ?: return
                                    WatermarkState.box = InpaintEngine.BoxRect(b.l, n.second, n.first, b.b)
                                }
                                DragMode.RESIZE_BL -> {
                                    val n = toNorm(pos)
                                    val b = WatermarkState.box ?: return
                                    WatermarkState.box = InpaintEngine.BoxRect(n.first, b.t, b.r, n.second)
                                }
                                DragMode.RESIZE_BR -> {
                                    val n = toNorm(pos)
                                    val b = WatermarkState.box ?: return
                                    WatermarkState.box = InpaintEngine.BoxRect(b.l, b.t, n.first, n.second)
                                }
                                null -> { }
                            }
                        }
                        fun finishEdit() {
                            if (mode == DragMode.PAINT) {
                                val pts = ptsBuf.toList()
                                if (pts.size >= 2)
                                    onStrokeDone(InpaintEngine.Stroke(pts, WatermarkState.brushPct / 100f / 2f))
                                ptsBuf.clear()
                            } else if (mode != null) {
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
                                // 第二根手指按下 = 用户想滚动页面：放弃本次编辑，放行给滚动
                                if (started && mode == DragMode.PAINT) ptsBuf.clear()
                                if (started && mode != DragMode.PAINT) {
                                    dragMode = null; moveBase = null
                                } else {
                                    dragMode = null
                                }
                                break
                            }
                            if (!started) {
                                val d = ch.position - start
                                if (kotlin.math.hypot(d.x.toDouble(), d.y.toDouble()) > slop) {
                                    started = true
                                    beginEdit(ch.position)
                                } else if (ch.changedToUp()) {
                                    break
                                }
                            }
                            if (started) {
                                if (ch.positionChange() != Offset.Zero) {
                                    moveEdit(ch.position)
                                    ch.consume()
                                }
                            }
                            if (ch.changedToUp()) {
                                if (started) finishEdit()
                                break
                            }
                        }
                        if (!started) { dragMode = null; moveBase = null; ptsBuf.clear() }
                    }
                }
        ) {
            clipRect(offX, offY, offX + drawW, offY + drawH) {
                val stW = WatermarkState
                // 已确认的涂抹笔画（白色半透明，像真实涂抹遮罩）
                val tick = paintTick   // 读取以触发涂抹中实时重绘
                for (s in stW.strokes) drawStrokePath(s, offX, offY, drawW, drawH, Color(0x8CFFFFFF))
                if (dragMode == DragMode.PAINT && ptsBuf.isNotEmpty()) {
                    drawStrokePath(
                        InpaintEngine.Stroke(ptsBuf.toList(), WatermarkState.brushPct / 100f / 2f),
                        offX, offY, drawW, drawH, Color(0xB3FFFFFF)
                    )
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
    if (s.pts.isEmpty()) return
    val halfPx = s.halfWidthFrac * drawW
    // 单点：画实心圆；多点：沿点列生成平滑连续路径，圆头圆角描边，不再有断开的“点点点”
    if (s.pts.size == 1) {
        drawCircle(
            color = color,
            radius = halfPx,
            center = Offset(offX + s.pts[0].first * drawW, offY + s.pts[0].second * drawH)
        )
        return
    }
    val path = Path()
    var px = offX + s.pts[0].first * drawW
    var py = offY + s.pts[0].second * drawH
    path.moveTo(px, py)
    for (i in 1 until s.pts.size) {
        val x = offX + s.pts[i].first * drawW
        val y = offY + s.pts[i].second * drawH
        // 二次贝塞尔经过相邻两点中点，折线变圆滑，快速拖动也不会断开
        path.quadraticBezierTo(px, py, (px + x) / 2f, (py + y) / 2f)
        px = x; py = y
    }
    path.lineTo(px, py)
    drawPath(
        path, color,
        style = Stroke(width = halfPx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
