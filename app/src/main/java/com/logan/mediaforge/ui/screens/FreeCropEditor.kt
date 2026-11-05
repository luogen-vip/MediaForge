package com.logan.mediaforge.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.logan.mediaforge.core.aspectRatioText
import kotlin.math.roundToInt

/**
 * 全屏自由裁剪编辑器（用户要求的新交互）：
 * - 点击「自由裁剪」立刻弹出，而不是要往上滑才能找到裁剪框；
 * - 单指拖动 = 平移图片，双指捏合 = 缩放图片，裁剪框固定在屏幕上，
 *   框里显示的就是最终裁剪出来的画面（所见即所得）；
 * - 也可以选固定比例（1:1 / 16:9 等），或「自由」模式拖四角圆点改变框的比例。
 * 确认后把框映射回原图坐标（cropL/T/R/B），转换时真实裁剪。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeCropEditor(
    bmp: Bitmap,
    initCrop: FloatArray,           // [l, t, r, b] 归一化（相对原图）
    origW: Int,
    origH: Int,
    onDismiss: () -> Unit,
    onConfirm: (FloatArray) -> Unit
) {
    val density = LocalDensity.current
    val img = bmp.asImageBitmap()

    // 全屏对话框：点击「自由裁剪」立刻弹出，编辑完回到转换页
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color(0xFF141414), modifier = Modifier.fillMaxSize()) {
            EditorContent(bmp, initCrop, origW, origH, onDismiss, onConfirm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    bmp: Bitmap,
    initCrop: FloatArray,
    origW: Int,
    origH: Int,
    onDismiss: () -> Unit,
    onConfirm: (FloatArray) -> Unit
) {
    val density = LocalDensity.current
    val img = bmp.asImageBitmap()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }

        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val fit = minOf(vw / iw, vh / ih)
        val baseW = iw * fit
        val baseH = ih * fit

        var scale by remember { mutableFloatStateOf(1f) }   // 相对 fit 的缩放
        var offset by remember { mutableStateOf(Offset.Zero) }
        // 裁剪框（相对视口归一化）。初始：把当前裁剪框按适配位置映射进来
        var frame by remember {
            mutableStateOf(
                floatArrayOf(
                    ((vw - baseW) / 2f + initCrop[0] * baseW) / vw,
                    ((vh - baseH) / 2f + initCrop[1] * baseH) / vh,
                    ((vw - baseW) / 2f + initCrop[2] * baseW) / vw,
                    ((vh - baseH) / 2f + initCrop[3] * baseH) / vh
                )
            )
        }
        var freeMode by remember { mutableStateOf(true) }

        fun framePx(): FloatArray = floatArrayOf(
            frame[0] * vw, frame[1] * vh, frame[2] * vw, frame[3] * vh
        )

        fun minScale(): Float {
            val fp = framePx()
            val fw = fp[2] - fp[0]; val fh = fp[3] - fp[1]
            return maxOf(fw / baseW, fh / baseH).coerceAtLeast(1f * maxOf(fw / baseW, fh / baseH))
        }

        // 把 offset 夹紧到「图片完全盖住裁剪框」的范围内
        fun clampOffset(s: Float, o: Offset): Offset {
            val dispW = baseW * s; val dispH = baseH * s
            val fp = framePx()
            val minX = fp[2] - (vw / 2f + dispW / 2f)   // 图片右缘 >= 框右缘
            val maxX = fp[0] - (vw / 2f - dispW / 2f)   // 图片左缘 <= 框左缘
            val minY = fp[3] - (vh / 2f + dispH / 2f)
            val maxY = fp[1] - (vh / 2f - dispH / 2f)
            return Offset(
                if (minX > maxX) (minX + maxX) / 2f else o.x.coerceIn(minX, maxX),
                if (minY > maxY) (minY + maxY) / 2f else o.y.coerceIn(minY, maxY)
            )
        }

        fun imgLeft(s: Float, o: Offset) = vw / 2f - baseW * s / 2f + o.x
        fun imgTop(s: Float, o: Offset) = vh / 2f - baseH * s / 2f + o.y

        // ---- 图片：单指平移 / 双指缩放（以手势中心为锚点） ----
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(minScale(), 16f)
                        val c = Offset(vw / 2f, vh / 2f)
                        var o = centroid - c - ((centroid - c) - offset) * (s / scale)
                        o += pan
                        offset = clampOffset(s, o)
                        scale = s
                    }
                }
        ) {
            val l = imgLeft(scale, offset)
            val t = imgTop(scale, offset)
            drawImage(
                image = img,
                dstOffset = IntOffset(l.roundToInt(), t.roundToInt()),
                dstSize = IntSize((baseW * scale).roundToInt(), (baseH * scale).roundToInt()),
                filterQuality = FilterQuality.Medium
            )
        }

        // ---- 裁剪框：暗化框外 + 黄框 ----
        Canvas(Modifier.fillMaxSize()) {
            val fp = framePx()
            val dim = Color(0x99000000)
            drawRect(dim, Offset(0f, 0f), Size(vw, fp[1]))
            drawRect(dim, Offset(0f, fp[3]), Size(vw, vh - fp[3]))
            drawRect(dim, Offset(0f, fp[1]), Size(fp[0], fp[3] - fp[1]))
            drawRect(dim, Offset(fp[2], fp[1]), Size(vw - fp[2], fp[3] - fp[1]))
            drawRect(
                Color(0xFFFFEB3B), Offset(fp[0], fp[1]),
                Size(fp[2] - fp[0], fp[3] - fp[1]), style = Stroke(width = 4f)
            )
        }

        // ---- 自由模式：四角圆点可拖，改变框大小/比例 ----
        if (freeMode) {
            val fp = framePx()
            val corners = listOf(
                0 to Offset(fp[0], fp[1]), 1 to Offset(fp[2], fp[1]),
                2 to Offset(fp[0], fp[3]), 3 to Offset(fp[2], fp[3])
            )
            corners.forEach { (idx, pos) ->
                var dragTotal by remember(idx) { mutableStateOf(Offset.Zero) }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (pos.x - with(density) { 22.dp.toPx() }).roundToInt(),
                                (pos.y - with(density) { 22.dp.toPx() }).roundToInt()
                            )
                        }
                        .size(44.dp)
                        .pointerInput(idx) {
                            detectDragGestures(
                                onDragStart = {
                                    dragTotal = Offset.Zero
                                },
                                onDrag = { _, amt ->
                                    dragTotal += amt
                                    val fp2 = framePx()
                                    var nx = when (idx) {
                                        0, 2 -> fp2[0] + dragTotal.x
                                        else -> fp2[2] + dragTotal.x
                                    }
                                    var ny = when (idx) {
                                        0, 1 -> fp2[1] + dragTotal.y
                                        else -> fp2[3] + dragTotal.y
                                    }
                                    nx = nx.coerceIn(0f, vw); ny = ny.coerceIn(0f, vh)
                                    val nf = frame.copyOf()
                                    when (idx) {
                                        0 -> { nf[0] = nx; nf[1] = ny }
                                        1 -> { nf[2] = nx; nf[1] = ny }
                                        2 -> { nf[0] = nx; nf[3] = ny }
                                        3 -> { nf[2] = nx; nf[3] = ny }
                                    }
                                    // 保证不翻转
                                    if (nf[2] - nf[0] > 40f && nf[3] - nf[1] > 40f) frame = nf
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, 10f, center)
                        drawCircle(Color(0xFFFBC02D), 10f, center, style = Stroke(3f))
                    }
                }
            }
        }

        // ---- 顶部比例选择 + 底部按钮 ----
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xDD1E1E1E)) {
                Column(Modifier.padding(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = freeMode,
                            onClick = { freeMode = true },
                            label = { Text("自由", color = Color.White) })
                        listOf("1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f,
                            "16:9" to 16f / 9f, "9:16" to 9f / 16f).forEach { (name, a) ->
                            FilterChip(
                                selected = !freeMode && isFrameAspect(frame, a, vw, vh),
                                onClick = {
                                    freeMode = false
                                    // 视口内最大居中矩形
                                    val fw: Float; val fh: Float
                                    if (vw / vh > a) { fh = vh; fw = vh * a } else { fw = vw; fh = vw / a }
                                    frame = floatArrayOf(
                                        (vw - fw) / 2f / vw, (vh - fh) / 2f / vh,
                                        (vw + fw) / 2f / vw, (vh + fh) / 2f / vh
                                    )
                                    // 缩放复位并保证图片盖住新框
                                    val s = maxOf(fw / baseW, fh / baseH).coerceAtLeast(1f)
                                    scale = s
                                    offset = clampOffset(s, Offset.Zero)
                                },
                                label = { Text(name, color = Color.White) })
                        }
                        FilterChip(
                            selected = false,
                            onClick = {
                                scale = 1f; offset = Offset.Zero
                            },
                            label = { Text("重置视图", color = Color.White) })
                    }
                    val fp = framePx()
                    val cl = ((fp[0] - imgLeft(scale, offset)) / (baseW * scale)).coerceIn(0f, 1f)
                    val cr = ((fp[2] - imgLeft(scale, offset)) / (baseW * scale)).coerceIn(0f, 1f)
                    val ct = ((fp[1] - imgTop(scale, offset)) / (baseH * scale)).coerceIn(0f, 1f)
                    val cb = ((fp[3] - imgTop(scale, offset)) / (baseH * scale)).coerceIn(0f, 1f)
                    val pw = ((cr - cl) * origW).roundToInt().coerceAtLeast(1)
                    val ph = ((cb - ct) * origH).roundToInt().coerceAtLeast(1)
                    Text(
                        "框内 = 裁剪结果：约 ${pw}×${ph}（比例 ${aspectRatioText(pw, ph)}）；单指拖图 / 双指缩放，框里就是最终画面",
                        color = Color(0xFFCCCCCC),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) {
                    Text("取消", color = Color.White)
                }
                Button(
                    onClick = {
                        val fp = framePx()
                        val dispW = baseW * scale; val dispH = baseH * scale
                        val l = ((fp[0] - imgLeft(scale, offset)) / dispW).coerceIn(0f, 1f)
                        val r = ((fp[2] - imgLeft(scale, offset)) / dispW).coerceIn(0f, 1f)
                        val t = ((fp[1] - imgTop(scale, offset)) / dispH).coerceIn(0f, 1f)
                        val b = ((fp[3] - imgTop(scale, offset)) / dispH).coerceIn(0f, 1f)
                        if (r - l > 0.01f && b - t > 0.01f) {
                            onConfirm(floatArrayOf(l, t, r, b))
                        } else {
                            onDismiss()
                        }
                    },
                    Modifier.weight(1f)
                ) { Text("确定裁剪") }
            }
        }
    }
}

/** 框当前比例是否等于 a（容差 3%） */
private fun isFrameAspect(frame: FloatArray, a: Float, vw: Float, vh: Float): Boolean {
    val fw = (frame[2] - frame[0]) * vw
    val fh = (frame[3] - frame[1]) * vh
    if (fh <= 0f) return false
    return kotlin.math.abs(fw / fh - a) / a < 0.03f
}