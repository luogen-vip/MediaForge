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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.ImageEngine
import com.logan.mediaforge.core.ImageMeta
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.aspectRatioText
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.ui.ImageState
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val IMAGE_TARGETS = listOf("jpg", "png", "webp", "bmp", "tif")

private val ASPECT_PRESETS = linkedMapOf(
    "1:1" to (1f to 1f),
    "4:3" to (4f to 3f),
    "3:4" to (3f to 4f),
    "16:9" to (16f to 9f),
    "9:16" to (9f to 16f),
    "3:2" to (3f to 2f),
    "2:3" to (2f to 3f),
    "21:9" to (21f to 9f)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = ImageState

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
            val d = withContext(Dispatchers.IO) { FileSniffer.detect(resolver, u, n.substringAfterLast('.', "")) }
            val wh = withContext(Dispatchers.IO) { ImageEngine.decodeSize(context, u) }
            val meta = withContext(Dispatchers.IO) { ImageMeta.parse(resolver, u) }
            val bmp = withContext(Dispatchers.IO) { ImageEngine.decodePreview(context, u) }
            st.uri = u
            st.name = n
            st.size = FileSniffer.querySize(resolver, u)
            st.det = d
            st.imgMeta = meta
            st.preview = bmp
            st.origW = wh?.first ?: 0
            st.origH = wh?.second ?: 0
            st.wText = wh?.first?.toString() ?: ""
            st.hText = wh?.second?.toString() ?: ""
            if (wh != null && wh.first > 0 && wh.second > 0) {
                st.lastAspect = wh.first.toFloat() / wh.second.toFloat()
            }
            st.aspectSel = "原比例"
            st.cropL = 0f; st.cropT = 0f; st.cropR = 1f; st.cropB = 1f
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPicked(u)
    }

    val targetW = (st.wText.toIntOrNull() ?: 0).coerceIn(0, 32767)
    val targetH = (st.hText.toIntOrNull() ?: 0).coerceIn(0, 32767)

    // 裁剪区域对应的源像素尺寸（用于显示与默认输出分辨率）
    val cropSrcW = ((st.cropR - st.cropL) * st.origW).roundToInt().coerceAtLeast(1)
    val cropSrcH = ((st.cropB - st.cropT) * st.origH).roundToInt().coerceAtLeast(1)
    val cropAspect = if (st.origW > 0 && st.origH > 0)
        ((st.cropR - st.cropL) * st.origW) / ((st.cropB - st.cropT) * st.origH) else 1f

    // 选预设比例：在原图内取最大居中裁剪框，并把目标分辨率同步为裁剪尺寸
    fun applyAspect(name: String) {
        val (rw, rh) = ASPECT_PRESETS[name] ?: return
        val ow = st.origW; val oh = st.origH
        if (ow <= 0 || oh <= 0) return
        val a = rw / rh
        val srcA = ow.toFloat() / oh.toFloat()
        val cw: Int; val ch: Int
        if (srcA > a) { ch = oh; cw = (oh * a).roundToInt() } else { cw = ow; ch = (ow / a).roundToInt() }
        st.cropL = ((ow - cw) / 2f) / ow
        st.cropR = st.cropL + cw.toFloat() / ow
        st.cropT = ((oh - ch) / 2f) / oh
        st.cropB = st.cropT + ch.toFloat() / oh
        st.wText = cw.toString()
        st.hText = ch.toString()
        st.keepRatio = true
    }

    // 宽高均有效时用目标比例；输入过程中（清空/半输入）沿用最近有效比例，避免画幅跳动
    val aspect = if (targetW > 0 && targetH > 0) {
        targetW.toFloat() / targetH.toFloat()
    } else if (st.isCropped) cropAspect else st.lastAspect

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("图片格式转换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("像素级真实转换（非改后缀），支持比例裁剪与分辨率调整，实时预览。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (st.uri == null) {
            SectionCard("选择图片", "支持 JPG/PNG/WebP/BMP/TIFF/HEIC/GIF 等常见图片") {
                Button(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择图片")
                }
            }
        } else {
            // 实时预览：显示裁剪区域内容（所见即所得），固定高度容器防极端比例撑爆布局
            SectionCard("实时预览", "预览显示的就是裁剪后要输出的画面；自由裁剪模式下显示全图与裁剪框") {
                val bmp = st.preview
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null && st.aspectSel == "自由裁剪") {
                        // 自由裁剪：显示全图 + 可拖动裁剪框
                        val af = (if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f)
                            .coerceIn(0.05f, 20f)
                        var w = maxWidth
                        var h = w / af
                        if (h > maxHeight) { h = maxHeight; w = h * af }
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "自由裁剪",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.size(w, h).clip(RoundedCornerShape(10.dp))
                        )
                        FreeCropOverlay(Modifier.size(w, h))
                    } else if (bmp != null) {
                        val a = aspect.coerceIn(0.05f, 20f)
                        var w = maxWidth
                        var h = if (a > 0f) w / a else maxHeight
                        if (h > maxHeight) { h = maxHeight; w = h * a }
                        CropPreviewImage(
                            bmp = bmp,
                            cropL = st.cropL, cropT = st.cropT, cropR = st.cropR, cropB = st.cropB,
                            modifier = Modifier.size(w, h).clip(RoundedCornerShape(10.dp))
                        )
                    }
                }
                if (st.det?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${st.det?.declaredExt}，真实格式是 ${st.det?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (st.det?.corrupted != null) {
                    Text("⚠ ${st.det?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("文件名", st.name)
                InfoRow("真实格式", st.det?.realName ?: "未知")
                if (st.origW > 0 && st.origH > 0) {
                    InfoRow("原始分辨率", "${st.origW}×${st.origH}（比例 ${aspectRatioText(st.origW, st.origH)}）")
                } else {
                    InfoRow("原始分辨率", "未知")
                }
                // 位深 / 色彩模式 / 色彩空间（ICC）真实解析
                st.imgMeta?.let { m ->
                    InfoRow("位深与色彩模式", m.describe())
                    InfoRow("色彩空间", m.colorSpace ?: "未内嵌 ICC（Android 解码时按原始 ICC 校色到 sRGB）")
                }
                if (st.origW > 0 && st.origH > 0) {
                    InfoRow("输出像素格式", "sRGB（转换时自动应用原 ICC 校色后重新编码，非简单改后缀）")
                }
                InfoRow("文件大小", if (st.size > 0) fmtBytes(st.size) else "未知")
                OutlinedButton(onClick = { st.resetSource() }) {
                    Text("移除 / 重新选择")
                }
            }

            // ---- 画面比例 / 裁剪 ----
            SectionCard("画面比例（裁剪）", "选预设比例自动居中裁剪，或选「自由裁剪」拖动预览图上的框；输出宽高比严格等于所选比例") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = st.aspectSel == "原比例",
                        onClick = {
                            st.aspectSel = "原比例"
                            st.cropL = 0f; st.cropT = 0f; st.cropR = 1f; st.cropB = 1f
                            st.wText = st.origW.toString(); st.hText = st.origH.toString()
                        },
                        label = { Text("原比例") }
                    )
                    ASPECT_PRESETS.forEach { (name, _) ->
                        FilterChip(
                            selected = st.aspectSel == name,
                            onClick = {
                                st.aspectSel = name
                                applyAspect(name)
                            },
                            label = { Text(name) }
                        )
                    }
                    FilterChip(
                        selected = st.aspectSel == "自由裁剪",
                        onClick = { st.aspectSel = "自由裁剪" },
                        label = { Text("自由裁剪") }
                    )
                }
                if (st.aspectSel == "自由裁剪") {
                    Text("拖动裁剪框内部移动位置，拖四角圆点调整大小；框外的画面会被裁掉",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.isCropped) {
                    Text(
                        "当前裁剪：${cropSrcW}×${cropSrcH}（比例 ${aspectRatioText(cropSrcW, cropSrcH)}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                OutlinedButton(onClick = {
                    st.aspectSel = "原比例"
                    st.cropL = 0f; st.cropT = 0f; st.cropR = 1f; st.cropB = 1f
                    st.wText = st.origW.toString(); st.hText = st.origH.toString()
                }) { Text("重置为原比例（取消裁剪）") }
            }

            SectionCard("目标分辨率", "留空或 0 表示保持裁剪区域原始像素；修改后预览实时变化") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = st.wText,
                        onValueChange = {
                            st.wText = it.filter { c -> c.isDigit() }.take(5)
                            if (st.keepRatio && st.origW > 0) {
                                val w = st.wText.toIntOrNull()
                                if (w != null) {
                                    st.hText = ((w / cropAspect).roundToInt().coerceIn(0, 32767)).toString().take(5)
                                }
                            }
                        },
                        label = { Text("宽") }, singleLine = true, modifier = Modifier.weight(1f))
                    Text("×", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = st.hText,
                        onValueChange = {
                            st.hText = it.filter { c -> c.isDigit() }.take(5)
                            if (st.keepRatio && st.origW > 0) {
                                val h = st.hText.toIntOrNull()
                                if (h != null) {
                                    st.wText = ((h * cropAspect).roundToInt().coerceIn(0, 32767)).toString().take(5)
                                }
                            }
                        },
                        label = { Text("高") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (st.isCropped) {
                    Text("裁剪区域源尺寸 ${cropSrcW}×${cropSrcH}；目标分辨率留空/0 即按此尺寸输出",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保持宽高比", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = st.keepRatio, onCheckedChange = { st.keepRatio = it })
                }
                OutlinedButton(onClick = {
                    st.wText = cropSrcW.toString()
                    st.hText = cropSrcH.toString()
                }) { Text("重置为${if (st.isCropped) "裁剪区域" else "原始"}分辨率") }
                Text("单边最大支持 32767 像素；超大分辨率会明显变慢并占用更多内存",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("目标格式与质量") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IMAGE_TARGETS.forEach { e ->
                        FilterChip(
                            selected = st.extSel == e,
                            onClick = { st.extSel = e },
                            label = { Text(e.uppercase()) }
                        )
                    }
                }
                if (st.extSel == "jpg" || st.extSel == "webp") {
                    Text("质量：${st.quality.toInt()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = st.quality, onValueChange = { st.quality = it }, valueRange = 30f..100f)
                }
            }

            Button(
                onClick = {
                    val u = st.uri ?: return@Button
                    if ((st.wText.toIntOrNull() ?: 0) > 32767 || (st.hText.toIntOrNull() ?: 0) > 32767) {
                        Toast.makeText(context, "分辨率超出限制：单边最大 32767 像素", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (targetW > 0 && targetH > 0 && targetW.toLong() * targetH > 80_000_000L) {
                        Toast.makeText(context, "分辨率过大（总像素超过 8000 万），请调小一些", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    TaskRepository.startImage(
                        u, st.name, st.extSel, targetW, targetH, st.quality.toInt(),
                        ImageEngine.CropRect(st.cropL, st.cropT, st.cropR, st.cropB)
                    )
                    Toast.makeText(context, "已加入任务，可在「任务」页查看与下载", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始转换（加入任务）") }
        }
    }
}

/** 预览图：只绘制裁剪区域（src → dst 真实映射，所见即所得） */
@Composable
private fun CropPreviewImage(
    bmp: Bitmap,
    cropL: Float, cropT: Float, cropR: Float, cropB: Float,
    modifier: Modifier = Modifier
) {
    val img = bmp.asImageBitmap()
    Canvas(modifier) {
        val sx = (bmp.width * cropL).toInt().coerceIn(0, bmp.width - 1)
        val sy = (bmp.height * cropT).toInt().coerceIn(0, bmp.height - 1)
        val sw = (bmp.width * (cropR - cropL)).toInt().coerceIn(1, bmp.width - sx)
        val sh = (bmp.height * (cropB - cropT)).toInt().coerceIn(1, bmp.height - sy)
        drawImage(
            image = img,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium
        )
    }
}

/**
 * 自由裁剪覆盖层：全图上拖动裁剪框 —— 框内拖动移动位置，四角圆点缩放，
 * 拖空白处画新框；结束后把目标分辨率同步为裁剪区域的像素尺寸。
 */
@Composable
private fun FreeCropOverlay(modifier: Modifier = Modifier) {
    val st = ImageState
    val density = LocalDensity.current
    var mode by remember { mutableStateOf<Int?>(null) } // null 无 / 0-3 四角 / 4 移动 / 5 画新框
    val base = remember { floatArrayOf(0f, 0f, 1f, 1f) }
    var anchor by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier) {
        val wpx = with(density) { maxWidth.toPx() }
        val hpx = with(density) { maxHeight.toPx() }

        fun rectPx(): FloatArray = floatArrayOf(
            minOf(st.cropL, st.cropR) * wpx, minOf(st.cropT, st.cropB) * hpx,
            maxOf(st.cropL, st.cropR) * wpx, maxOf(st.cropT, st.cropB) * hpx
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val rc = rectPx()
                            val th = 48f
                            mode = when {
                                abs(pos.x - rc[0]) <= th && abs(pos.y - rc[1]) <= th -> 0
                                abs(pos.x - rc[2]) <= th && abs(pos.y - rc[1]) <= th -> 1
                                abs(pos.x - rc[0]) <= th && abs(pos.y - rc[3]) <= th -> 2
                                abs(pos.x - rc[2]) <= th && abs(pos.y - rc[3]) <= th -> 3
                                pos.x >= rc[0] && pos.x <= rc[2] && pos.y >= rc[1] && pos.y <= rc[3] -> 4
                                else -> 5
                            }
                            base[0] = st.cropL; base[1] = st.cropT; base[2] = st.cropR; base[3] = st.cropB
                            anchor = pos
                        },
                        onDrag = { change, _ ->
                            val p = change.position
                            val nx = (p.x / wpx).coerceIn(0f, 1f)
                            val ny = (p.y / hpx).coerceIn(0f, 1f)
                            when (mode) {
                                4 -> {
                                    val dx = (p.x - anchor.x) / wpx
                                    val dy = (p.y - anchor.y) / hpx
                                    val bw = base[2] - base[0]; val bh = base[3] - base[1]
                                    val nl = (base[0] + dx).coerceIn(0f, 1f - bw)
                                    val nt = (base[1] + dy).coerceIn(0f, 1f - bh)
                                    st.cropL = nl; st.cropT = nt; st.cropR = nl + bw; st.cropB = nt + bh
                                }
                                5 -> {
                                    val ax = (anchor.x / wpx).coerceIn(0f, 1f)
                                    val ay = (anchor.y / hpx).coerceIn(0f, 1f)
                                    st.cropL = minOf(ax, nx); st.cropR = maxOf(ax, nx)
                                    st.cropT = minOf(ay, ny); st.cropB = maxOf(ay, ny)
                                }
                                0 -> { st.cropL = nx; st.cropT = ny }
                                1 -> { st.cropR = nx; st.cropT = ny }
                                2 -> { st.cropL = nx; st.cropB = ny }
                                3 -> { st.cropR = nx; st.cropB = ny }
                            }
                        },
                        onDragEnd = {
                            val l = minOf(st.cropL, st.cropR); val r = maxOf(st.cropL, st.cropR)
                            val t = minOf(st.cropT, st.cropB); val b = maxOf(st.cropT, st.cropB)
                            if (r - l > 0.02f && b - t > 0.02f) {
                                st.cropL = l; st.cropR = r; st.cropT = t; st.cropB = b
                                if (st.origW > 0 && st.origH > 0) {
                                    st.wText = ((r - l) * st.origW).roundToInt().toString()
                                    st.hText = ((b - t) * st.origH).roundToInt().toString()
                                }
                            } else {
                                st.cropL = base[0]; st.cropT = base[1]; st.cropR = base[2]; st.cropB = base[3]
                            }
                            mode = null
                        },
                        onDragCancel = { mode = null }
                    )
                }
        ) {
            val rc = rectPx()
            val dim = Color(0x99000000)
            drawRect(dim, Offset(0f, 0f), Size(wpx, rc[1]))
            drawRect(dim, Offset(0f, rc[3]), Size(wpx, hpx - rc[3]))
            drawRect(dim, Offset(0f, rc[1]), Size(rc[0], rc[3] - rc[1]))
            drawRect(dim, Offset(rc[2], rc[1]), Size(wpx - rc[2], rc[3] - rc[1]))
            drawRect(
                Color(0xFFFFEB3B), Offset(rc[0], rc[1]),
                Size(rc[2] - rc[0], rc[3] - rc[1]), style = Stroke(width = 3f)
            )
            for (c in listOf(
                Offset(rc[0], rc[1]), Offset(rc[2], rc[1]),
                Offset(rc[0], rc[3]), Offset(rc[2], rc[3])
            )) {
                drawCircle(Color.White, 9f, c)
                drawCircle(Color(0xFFFBC02D), 9f, c, style = Stroke(3f))
            }
        }
    }
}
