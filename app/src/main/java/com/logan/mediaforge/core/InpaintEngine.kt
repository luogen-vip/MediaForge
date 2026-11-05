package com.logan.mediaforge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer

/**
 * 图片去水印引擎（完全离线，不依赖服务器）：
 *
 * 1. AI 引擎（首选）：内置 LaMa 深度学习修复模型（ONNX，打包在 assets/inpaint_model.onnx）。
 *    LaMa 根据周围画面内容智能补全被抹掉的区域，补全结果与整张图自然融合。
 *    只对水印所在局部区域推理（保留原始分辨率，速度快、效果好）。
 *
 * 2. 内置算法兜底：模型缺失或设备内存不足时，自动退化为多尺度金字塔扩散填充，
 *    对细水印/文字/小 LOGO 同样自然，保证功能永不失败。
 *
 * 坐标均为归一化 0..1（相对原图宽高），框选与涂抹共用同一蒙版空间。
 */
object InpaintEngine {

    const val MODEL_ASSET = "inpaint_model.onnx"

    /** 框选矩形（归一化 left/top/right/bottom） */
    data class BoxRect(val l: Float, val t: Float, val r: Float, val b: Float)

    /** 涂抹笔画：归一化点序列 + 半宽（相对原图宽度的比例） */
    data class Stroke(val pts: List<Pair<Float, Float>>, val halfWidthFrac: Float)

    /** 蒙版包围盒（归一化，已扩边留足上下文） */
    data class BoundsN(val l: Float, val t: Float, val r: Float, val b: Float)

    fun modelAvailable(ctx: Context): Boolean = try {
        ctx.assets.list("")?.contains(MODEL_ASSET) == true
    } catch (_: Exception) { false }

    /** 根据框选+涂抹计算归一化包围盒（含扩边）；无有效区域返回 null */
    fun computeBounds(w: Int, h: Int, rects: List<BoxRect>, strokes: List<Stroke>): BoundsN? {
        if (w <= 0 || h <= 0) return null
        if (rects.isEmpty() && strokes.isEmpty()) return null
        var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
        var any = false
        for (rc in rects) {
            val l = rc.l.coerceIn(0f, 1f); val t = rc.t.coerceIn(0f, 1f)
            val r = rc.r.coerceIn(0f, 1f); val b = rc.b.coerceIn(0f, 1f)
            if (r - l < 0.002f || b - t < 0.002f) continue
            any = true
            minX = minOf(minX, l); minY = minOf(minY, t); maxX = maxOf(maxX, r); maxY = maxOf(maxY, b)
        }
        for (s in strokes) {
            if (s.pts.isEmpty()) continue
            any = true
            val half = s.halfWidthFrac.coerceIn(0.001f, 0.2f)
            for (p in s.pts) {
                val x = p.first.coerceIn(0f, 1f); val y = p.second.coerceIn(0f, 1f)
                minX = minOf(minX, x - half); minY = minOf(minY, y - half)
                maxX = maxOf(maxX, x + half); maxY = maxOf(maxY, y + half)
            }
        }
        if (!any) return null
        minX = minX.coerceIn(0f, 1f); minY = minY.coerceIn(0f, 1f)
        maxX = maxX.coerceIn(0f, 1f); maxY = maxY.coerceIn(0f, 1f)
        // 扩边：水印尺寸的 0.75 倍 + 固定余量，为修复提供上下文
        val bw = maxX - minX; val bh = maxY - minY
        val mx = (bw * 0.75f).coerceIn(0.04f, 0.3f) + 0.02f
        val my = (bh * 0.75f).coerceIn(0.04f, 0.3f) + 0.02f
        val l = (minX - mx).coerceIn(0f, 1f)
        val t = (minY - my).coerceIn(0f, 1f)
        val r = (maxX + mx).coerceIn(0f, 1f)
        val b = (maxY + my).coerceIn(0f, 1f)
        return BoundsN(l, t, r, b)
    }

    /**
     * 执行去水印。
     * @param onProgress (进度0..1, 阶段描述)
     * @return 与原图同分辨率的修复结果；失败返回 null
     */
    fun inpaint(
        ctx: Context,
        src: Bitmap,
        rects: List<BoxRect>,
        strokes: List<Stroke>,
        onProgress: (Float, String) -> Unit
    ): Bitmap? {
        val bounds = computeBounds(src.width, src.height, rects, strokes) ?: return null
        onProgress(0.08f, "定位水印区域")

        // ROI 像素区域；长边超过 2048 时按比例缩小（避免超大图内存爆炸）
        val rx = (bounds.l * src.width).toInt().coerceIn(0, src.width - 2)
        val ry = (bounds.t * src.height).toInt().coerceIn(0, src.height - 2)
        val rw0 = ((bounds.r - bounds.l) * src.width).toInt().coerceIn(2, src.width - rx)
        val rh0 = ((bounds.b - bounds.t) * src.height).toInt().coerceIn(2, src.height - ry)
        val scale = if (maxOf(rw0, rh0) > 2048) 2048f / maxOf(rw0, rh0) else 1f
        val rw = (rw0 * scale).toInt().coerceAtLeast(2)
        val rh = (rh0 * scale).toInt().coerceAtLeast(2)

        // 注意：当框覆盖整图时，createBitmap(src, 0,0, w, h) 对不可变位图会直接返回 src 本体，
        // 此时绝不能 recycle roiImg，否则后面的 src.copy(...) 会抛 "Can't copy a recycled bitmap"
        val roiImg = Bitmap.createBitmap(src, rx, ry, rw0, rh0)
        val roi = if (scale < 1f)
            Bitmap.createScaledBitmap(roiImg, rw, rh, true).also { if (roiImg !== src) roiImg.recycle() }
        else roiImg

        // 在 ROI 分辨率上生成蒙版（白色 = 去除区域）；scale 用于坐标同步缩放
        val mask = buildMask(roi.width, roi.height, src.width, src.height, rx, ry, scale, rects, strokes)
        if (mask == null) {
            roi.recycle()
            return null
        }

        onProgress(0.15f, "修复中")
        val patched = if (modelAvailable(ctx)) {
            try {
                inpaintWithModel(ctx, roi, mask, onProgress)
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        } else null
        val filled = patched ?: inpaintFallback(roi, mask, onProgress)
        if (filled == null) {
            mask.recycle(); roi.recycle()
            return null
        }
        onProgress(0.9f, "融合边缘")

        // 结果贴回全分辨率原图（copy 第二参数必须为 true：Canvas 构造要求可变 Bitmap）；
        // src 已被意外回收时降级为从 ROI 恢复，不再抛异常
        if (src.isRecycled) {
            mask.recycle(); roi.recycle(); filled.recycle()
            return null
        }
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val feather = featheredMask(mask) // ROI 尺寸的白色图（alpha=羽化后的蒙版）
        // 把填充结果与羽化蒙版做 DST_IN 得到只含修复区域的图，再贴回
        val blended = Bitmap.createBitmap(filled.width, filled.height, Bitmap.Config.ARGB_8888)
        val bc = Canvas(blended)
        bc.drawBitmap(filled, 0f, 0f, drawPaint)
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        bc.drawBitmap(feather, 0f, 0f, fp)
        feather.recycle()
        if (scale < 1f) {
            // ROI 被缩小过：先把修复区域放大回原尺寸再贴
            val up = Bitmap.createScaledBitmap(blended, rw0, rh0, true)
            blended.recycle()
            canvas.drawBitmap(up, rx.toFloat(), ry.toFloat(), drawPaint)
            up.recycle()
        } else {
            canvas.drawBitmap(blended, rx.toFloat(), ry.toFloat(), drawPaint)
        }
        blended.recycle()
        mask.recycle()
        roi.recycle()
        filled.recycle()
        return result
    }

    // ==================== 蒙版构建（ROI 分辨率） ====================

    private fun buildMask(
        roiW: Int, roiH: Int, srcW: Int, srcH: Int,
        rx: Int, ry: Int, scale: Float,
        rects: List<BoxRect>, strokes: List<Stroke>
    ): Bitmap? {
        if (roiW <= 1 || roiH <= 1) return null
        val bmp = Bitmap.createBitmap(roiW, roiH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        var any = false
        // 归一化原图坐标 → ROI 像素坐标（含 ROI 缩放）
        fun toRoiX(nx: Float) = (nx * srcW - rx) * scale
        fun toRoiY(ny: Float) = (ny * srcH - ry) * scale
        for (rc in rects) {
            val l = toRoiX(rc.l.coerceIn(0f, 1f)); val t = toRoiY(rc.t.coerceIn(0f, 1f))
            val r = toRoiX(rc.r.coerceIn(0f, 1f)); val b = toRoiY(rc.b.coerceIn(0f, 1f))
            val left = minOf(l, r); val right = maxOf(l, r)
            val top = minOf(t, b); val bottom = maxOf(t, b)
            if (right < 0 || bottom < 0 || left > roiW || top > roiH) continue
            if (right - left < 1 || bottom - top < 1) continue
            c.drawRect(left, top, right, bottom, p)
            any = true
        }
        // 涂抹笔画：与 UI 渲染一致 —— 用平滑连续路径 + 圆头描边光栅化，
        // 保证「屏幕上看到的涂抹区域」与「实际送去修复的蒙版」完全一致，且没有断开的点
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        for (s in strokes) {
            if (s.pts.isEmpty()) continue
            val half = s.halfWidthFrac.coerceIn(0.001f, 0.2f) * srcW * scale
            val mapped = s.pts.map { pt ->
                Pair(toRoiX(pt.first.coerceIn(0f, 1f)), toRoiY(pt.second.coerceIn(0f, 1f)))
            }
            // 整笔完全在 ROI 外才跳过
            if (mapped.all { p ->
                    p.first + half < 0 || p.second + half < 0 || p.first - half > roiW || p.second - half > roiH
                }) continue
            any = true
            if (mapped.size == 1) {
                c.drawCircle(mapped[0].first, mapped[0].second, half, p)
            } else {
                strokePaint.strokeWidth = half * 2f
                val path = android.graphics.Path()
                path.moveTo(mapped[0].first, mapped[0].second)
                for (i in 1 until mapped.size) {
                    val mx = (mapped[i - 1].first + mapped[i].first) / 2f
                    val my = (mapped[i - 1].second + mapped[i].second) / 2f
                    path.quadTo(mapped[i - 1].first, mapped[i - 1].second, mx, my)
                }
                path.lineTo(mapped.last().first, mapped.last().second)
                c.drawPath(path, strokePaint)
            }
        }
        return if (any) bmp else { bmp.recycle(); null }
    }

    // ==================== AI 引擎（LaMa ONNX） ====================

    private var cachedSession: OrtSession? = null

    private fun getSession(ctx: Context): OrtSession {
        cachedSession?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        val bytes = ctx.assets.open(MODEL_ASSET).use { inp ->
            val buf = ByteArray(1 shl 20)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val s = env.createSession(bytes, opts)
        cachedSession = s
        return s
    }

    private fun inpaintWithModel(
        ctx: Context, roi: Bitmap, mask: Bitmap, onProgress: (Float, String) -> Unit
    ): Bitmap? {
        onProgress(0.2f, "AI 模型加载中")
        val session = getSession(ctx)
        val env = OrtEnvironment.getEnvironment()
        val S = 512L
        val si = 512

        val in512 = Bitmap.createScaledBitmap(roi, si, si, true)
        val raw512 = Bitmap.createScaledBitmap(mask, si, si, true)
        val dilated = dilateMask(raw512, 3)
        // createScaledBitmap 在尺寸相同时会直接返回源 bitmap 本体，此时回收它等于回收 roi/mask
        if (raw512 !== mask) raw512.recycle()
        onProgress(0.35f, "AI 修复中（大图首次较慢）")

        val area = si * si
        val img = FloatArray(3 * area)
        val px = IntArray(area)
        in512.getPixels(px, 0, si, 0, 0, si, si)
        for (i in 0 until area) {
            val c = px[i]
            img[i] = (c shr 16 and 0xFF) / 255f
            img[area + i] = (c shr 8 and 0xFF) / 255f
            img[2 * area + i] = (c and 0xFF) / 255f
        }
        val mpx = IntArray(area)
        dilated.getPixels(mpx, 0, si, 0, 0, si, si)
        val msk = FloatArray(area) { if ((mpx[it] ushr 24) > 64) 1f else 0f }

        // 不同导出版本输入名可能不同：优先按常见名称匹配，否则按位置约定（第一个=image，第二个=mask）
        val names = session.inputNames.toList()
        val imageInputName = names.firstOrNull {
            it.equals("image", true) || it.equals("input_image", true) || it.equals("img", true)
        } ?: names.firstOrNull() ?: "image"
        val maskInputName = names.firstOrNull {
            it.equals("mask", true) || it.equals("input_mask", true)
        } ?: names.getOrNull(1) ?: "mask"

        val imgTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), longArrayOf(1, 3, S, S))
        val mskTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(msk), longArrayOf(1, 1, S, S))
        val out512: Bitmap = try {
            session.run(mapOf(imageInputName to imgTensor, maskInputName to mskTensor)).use { res ->
                val tensor = res.get(0) as OnnxTensor
                val fb = tensor.floatBuffer
                val ob = Bitmap.createBitmap(si, si, Bitmap.Config.ARGB_8888)
                // 输出可能为 0..1 或 0..255，动态判断（NCHW 布局，通道在前）
                var maxV = 0f
                for (ch in 0 until 3) for (i in 0 until area) {
                    val v = fb.get(ch * area + i); if (v > maxV) maxV = v
                }
                val scale = if (maxV <= 1.5f) 255f else 1f
                val opx = IntArray(area)
                for (i in 0 until area) {
                    val r = (fb.get(i) * scale).toInt().coerceIn(0, 255)
                    val g = (fb.get(area + i) * scale).toInt().coerceIn(0, 255)
                    val b = (fb.get(2 * area + i) * scale).toInt().coerceIn(0, 255)
                    opx[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
                ob.setPixels(opx, 0, si, 0, 0, si, si)
                ob
            }
        } finally {
            imgTensor.close(); mskTensor.close()
            dilated.recycle()
        }

        onProgress(0.8f, "融合到原图")
        val outRoi = Bitmap.createScaledBitmap(out512, roi.width, roi.height, true)
        out512.recycle()
        if (in512 !== roi) in512.recycle()
        return outRoi
    }

    /** 蒙版膨胀 n 像素（4 邻域迭代），保证水印边缘完全覆盖 */
    private fun dilateMask(src: Bitmap, n: Int): Bitmap {
        val w = src.width; val h = src.height
        var cur = IntArray(w * h)
        src.getPixels(cur, 0, w, 0, 0, w, h)
        val on = { v: Int -> (v ushr 24) > 64 }
        repeat(n) {
            val nxt = cur.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (on(cur[y * w + x])) continue
                    val l = if (x > 0) cur[y * w + x - 1] else 0
                    val r = if (x < w - 1) cur[y * w + x + 1] else 0
                    val u = if (y > 0) cur[(y - 1) * w + x] else 0
                    val d = if (y < h - 1) cur[(y + 1) * w + x] else 0
                    if (on(l) || on(r) || on(u) || on(d)) nxt[y * w + x] = -0x1000000
                }
            }
            cur = nxt
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(cur, 0, w, 0, 0, w, h)
        return out
    }

    /** 蒙版高斯羽化（盒滤波近似），修复区域与原图无缝过渡 */
    private fun featheredMask(mask: Bitmap): Bitmap {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        val src = FloatArray(w * h) { i -> if ((px[i] ushr 24) > 64) 255f else 0f }
        val blurred = boxBlur(src, w, h, radius = 6, passes = 3)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val opx = IntArray(w * h) { i ->
            val a = blurred[i].toInt().coerceIn(0, 255)
            (a shl 24) or 0x00FFFFFF
        }
        out.setPixels(opx, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        var cur = src.copyOf()
        val tmp = FloatArray(w * h)
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (x in 0 until (radius * 2 + 1).coerceAtMost(w)) sum += cur[row + x.coerceIn(0, w - 1)]
                val cnt = (radius * 2 + 1).coerceAtMost(w).toFloat()
                for (x in 0 until w) {
                    tmp[row + x] = sum / cnt
                    val xAdd = (x + radius + 1).coerceIn(0, w - 1)
                    val xSub = (x - radius).coerceIn(0, w - 1)
                    sum += cur[row + xAdd] - cur[row + xSub]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (y in 0 until (radius * 2 + 1).coerceAtMost(h)) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                val cnt = (radius * 2 + 1).coerceAtMost(h).toFloat()
                for (y in 0 until h) {
                    cur[y * w + x] = sum / cnt
                    val yAdd = (y + radius + 1).coerceIn(0, h - 1)
                    val ySub = (y - radius).coerceIn(0, h - 1)
                    sum += tmp[yAdd * w + x] - tmp[ySub * w + x]
                }
            }
        }
        return cur
    }

    // ==================== 兜底算法（多尺度金字塔扩散填充） ====================

    private fun inpaintFallback(roi: Bitmap, mask: Bitmap, onProgress: (Float, String) -> Unit): Bitmap? {
        onProgress(0.3f, "智能填充中")
        val w = roi.width; val h = roi.height
        if (w < 2 || h < 2) return null
        val px = IntArray(w * h)
        roi.getPixels(px, 0, w, 0, 0, w, h)
        val mpx = IntArray(w * h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)
        val hole = BooleanArray(w * h) { (mpx[it] ushr 24) > 64 }
        if (hole.none { it }) return null

        fillPyramid(px, hole, w, h)
        onProgress(0.85f, "融合边缘")

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 金字塔层级 */
    private class Level(
        val w: Int, val h: Int,
        val r: FloatArray, val g: FloatArray, val b: FloatArray,
        val cnt: FloatArray, val hole: BooleanArray
    )

    /**
     * pull-push 金字塔填充：逐级降采样取已知像素均值 → 最粗层兜底 →
     * 逐级上采样填回空洞 → 空洞内部轻度平滑。
     */
    private fun fillPyramid(px: IntArray, hole: BooleanArray, w: Int, h: Int) {
        val levels = mutableListOf<Level>()
        var lw = w; var lh = h
        var lr = FloatArray(w * h); var lg = FloatArray(w * h); var lb = FloatArray(w * h)
        var lc = FloatArray(w * h); var lhole = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            lr[i] = (c shr 16 and 0xFF).toFloat(); lg[i] = (c shr 8 and 0xFF).toFloat(); lb[i] = (c and 0xFF).toFloat()
            lc[i] = if (hole[i]) 0f else 1f
            lhole[i] = hole[i]
        }
        levels.add(Level(lw, lh, lr, lg, lb, lc, lhole))
        while (lw > 8 && lh > 8) {
            val nw = lw / 2; val nh = lh / 2
            val nr = FloatArray(nw * nh); val ng = FloatArray(nw * nh); val nb = FloatArray(nw * nh)
            val nc = FloatArray(nw * nh); val nhole = BooleanArray(nw * nh)
            val prev = levels.last()
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sr = 0f; var sg = 0f; var sb = 0f; var sc = 0f; var holes = 0; var tot = 0
                    for (dy in 0..1) for (dx in 0..1) {
                        val sx = (x * 2 + dx).coerceAtMost(prev.w - 1)
                        val sy = (y * 2 + dy).coerceAtMost(prev.h - 1)
                        val i = sy * prev.w + sx
                        tot++
                        sr += prev.r[i] * prev.cnt[i]; sg += prev.g[i] * prev.cnt[i]; sb += prev.b[i] * prev.cnt[i]
                        sc += prev.cnt[i]
                        if (prev.hole[i]) holes++
                    }
                    val j = y * nw + x
                    nr[j] = if (sc > 0) sr / sc else 0f
                    ng[j] = if (sc > 0) sg / sc else 0f
                    nb[j] = if (sc > 0) sb / sc else 0f
                    nc[j] = sc / 4f
                    nhole[j] = holes >= tot * 3 / 4 && sc < 0.5f
                }
            }
            lw = nw; lh = nh
            levels.add(Level(nw, nh, nr, ng, nb, nc, nhole))
        }
        // 最粗层兜底：全图已知均值
        val coarse = levels.last()
        var ar = 0f; var ag = 0f; var ab = 0f; var ac = 0f
        for (i in coarse.r.indices) {
            ar += coarse.r[i] * coarse.cnt[i]; ag += coarse.g[i] * coarse.cnt[i]; ab += coarse.b[i] * coarse.cnt[i]; ac += coarse.cnt[i]
        }
        if (ac > 0) { ar /= ac; ag /= ac; ab /= ac }
        for (i in coarse.r.indices) {
            if (coarse.cnt[i] <= 0f) {
                coarse.r[i] = ar; coarse.g[i] = ag; coarse.b[i] = ab
                coarse.cnt[i] = 1f; coarse.hole[i] = true
            }
        }
        // 逐级上采样填空洞
        for (li in levels.size - 2 downTo 0) {
            val up = levels[li]; val dn = levels[li + 1]
            for (y in 0 until up.h) {
                for (x in 0 until up.w) {
                    val i = y * up.w + x
                    if (up.cnt[i] > 0f) continue
                    val fx = (x - 0.5f) / 2f; val fy = (y - 0.5f) / 2f
                    val x0 = fx.toInt().coerceIn(0, dn.w - 1); val x1 = (x0 + 1).coerceIn(0, dn.w - 1)
                    val y0 = fy.toInt().coerceIn(0, dn.h - 1); val y1 = (y0 + 1).coerceIn(0, dn.h - 1)
                    val tx = (fx - x0).coerceIn(0f, 1f); val ty = (fy - y0).coerceIn(0f, 1f)
                    val w00 = dn.cnt[y0 * dn.w + x0]; val w10 = dn.cnt[y0 * dn.w + x1]
                    val w01 = dn.cnt[y1 * dn.w + x0]; val w11 = dn.cnt[y1 * dn.w + x1]
                    val wsum = w00 + w10 + w01 + w11
                    if (wsum > 0f) {
                        up.r[i] = (dn.r[y0 * dn.w + x0] * w00 + dn.r[y0 * dn.w + x1] * w10 +
                                dn.r[y1 * dn.w + x0] * w01 + dn.r[y1 * dn.w + x1] * w11) / wsum
                        up.g[i] = (dn.g[y0 * dn.w + x0] * w00 + dn.g[y0 * dn.w + x1] * w10 +
                                dn.g[y1 * dn.w + x0] * w01 + dn.g[y1 * dn.w + x1] * w11) / wsum
                        up.b[i] = (dn.b[y0 * dn.w + x0] * w00 + dn.b[y0 * dn.w + x1] * w10 +
                                dn.b[y1 * dn.w + x0] * w01 + dn.b[y1 * dn.w + x1] * w11) / wsum
                    } else {
                        val near = nearestKnown(dn, x0, y0)
                        up.r[i] = dn.r[near]; up.g[i] = dn.g[near]; up.b[i] = dn.b[near]
                    }
                    up.cnt[i] = 1f
                }
            }
        }
        // 写回最细层空洞像素
        val fine = levels[0]
        for (i in 0 until w * h) {
            if (!hole[i]) continue
            val r = fine.r[i].toInt().coerceIn(0, 255)
            val g = fine.g[i].toInt().coerceIn(0, 255)
            val b = fine.b[i].toInt().coerceIn(0, 255)
            px[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        // 空洞内部 3x3 轻度平滑两次（衔接过渡）
        val tmp = px.copyOf()
        repeat(2) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!hole[i]) continue
                    var sr = 0f; var sg = 0f; var sb = 0f; var cnt = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val sy = (y + dy).coerceIn(0, h - 1)
                        val j = sy * w + sx
                        val c = tmp[j]
                        val cc = if (hole[j]) 1f else 0.6f
                        sr += (c shr 16 and 0xFF) * cc; sg += (c shr 8 and 0xFF) * cc; sb += (c and 0xFF) * cc
                        cnt += cc
                    }
                    val r = (sr / cnt).toInt().coerceIn(0, 255)
                    val g = (sg / cnt).toInt().coerceIn(0, 255)
                    val b = (sb / cnt).toInt().coerceIn(0, 255)
                    px[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            }
            for (i in tmp.indices) if (hole[i]) tmp[i] = px[i]
        }
    }

    private fun nearestKnown(lv: Level, x: Int, y: Int): Int {
        for (rad in 1 until maxOf(lv.w, lv.h)) {
            for (dy in -rad..rad) for (dx in -rad..rad) {
                if (dx != rad && dx != -rad && dy != rad && dy != -rad) continue
                val nx = (x + dx).coerceIn(0, lv.w - 1)
                val ny = (y + dy).coerceIn(0, lv.h - 1)
                val j = ny * lv.w + nx
                if (lv.cnt[j] > 0f) return j
            }
        }
        return (y * lv.w + x).coerceIn(0, lv.cnt.size - 1)
    }
}
