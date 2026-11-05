package com.logan.mediaforge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI 抠图引擎（完全离线，不依赖服务器）：
 *
 * 模型链式回退（任一环节失败自动降级并如实报告原因，绝不静默输出原图）：
 * 1. matting_model.onnx      —— U2Net 全量模型（Conv 权重 int8 + DequantizeLinear 的
 *    QDQ 权重量化，计算图仍是标准 float Conv，全平台兼容），效果最好，约 42MB；
 * 2. matting_model_lite.onnx —— U2NetP 轻量模型（官方 fp32 原版，零量化、纯 Conv/MaxPool
 *    基础算子，任何 ONNX Runtime 都能跑），约 4.6MB，前一个模型异常时兜底；
 * 3. 内置背景识别兜底        —— 边界取色 + 泛洪连通（降采样加速），模型全部不可用时使用。
 *
 * 每次模型推理后都做「蒙版合理性校验」：输出含 NaN、恒定值（min==max）等一律视为
 * 该模型在当前设备不可用，自动换下一个引擎——从机制上杜绝“显示已完成但输出=原图”。
 *
 * 附加能力：
 * - 智能排除水印/LOGO：对蒙版做连通域分析，凡是「面积小 + 紧贴边缘/角落」的孤立区域
 *   （AI 生成水印文字、平台角标 LOGO、作者署名等典型位置）自动剔除为完全透明。
 * - 手动精修：「涂抹保留」（强制不透明）与「涂抹擦除」（强制透明，涂抹水印即消失）
 *   优先级最高，模型没抠准的地方可以手动圈回来或抹掉。
 * - 透明 PNG 输入：先合成到白底再送模型（避免透明像素的垃圾 RGB 干扰识别），
 *   最终 alpha = 原alpha × 主体蒙版，原本透明的部分依然完全透明。
 *
 * 坐标均为归一化 0..1（相对原图宽高），与去水印页共用同一笔刷数据结构。
 */

/** 笔刷直接复用去水印的结构（点列 + 半宽占图宽比例） */
typealias Stroke = InpaintEngine.Stroke

object MattingEngine {

    const val MODEL_ASSET = "matting_model.onnx"
    const val MODEL_LITE_ASSET = "matting_model_lite.onnx"

    data class MattingResult(val bitmap: Bitmap, val removedMarks: Int, val engine: String)

    fun modelAvailable(ctx: Context): Boolean = try {
        val l = ctx.assets.list("")?.toSet() ?: emptySet()
        MODEL_ASSET in l || MODEL_LITE_ASSET in l
    } catch (_: Exception) { false }

    /**
     * 执行抠图，输出与原图同分辨率、带透明通道的 ARGB_8888 位图。
     * @param keep 涂抹保留笔刷（强制不透明）
     * @param erase 涂抹擦除笔刷（强制透明）
     * @param excludeMarks 智能排除水印文字/LOGO（边缘小连通域剔除）
     */
    fun matting(
        ctx: Context,
        src: Bitmap,
        keep: List<Stroke>,
        erase: List<Stroke>,
        excludeMarks: Boolean,
        onProgress: (Float, String) -> Unit
    ): MattingResult? {
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return null

        // 1. 手动笔刷蒙版（全分辨率）
        onProgress(0.05f, "解析笔刷区域")
        val keepF = strokeToFloat(keep, w, h)
        val eraseF = strokeToFloat(erase, w, h)

        // 2. 主体蒙版：模型链式回退（U2Net QDQ → U2NetP fp32 → 内置背景识别）
        var engine = "内置背景识别"
        var failReason = ""
        var matte: FloatArray? = null
        onProgress(0.12f, "AI 模型加载中")
        for (cand in modelCandidates()) {
            try {
                matte = runU2Net(ctx, cand.asset, src) { p, ph ->
                    onProgress(0.12f + p * 0.43f, ph)
                }
                if (matte != null) { engine = cand.label; break }
                failReason += "${cand.label} 输出无效；"
            } catch (e: OutOfMemoryError) {
                failReason += "${cand.label} 内存不足；"
            } catch (t: Throwable) {
                // 必须捕 Throwable：某些设备/引擎版本的不兼容算子抛的是 Error 或 NOT_IMPLEMENTED
                failReason += "${cand.label} 不可用(${t.javaClass.simpleName})；"
            }
        }
        if (matte == null) {
            onProgress(0.3f, "识别背景中")
            matte = backgroundHeuristic(src) { p ->
                onProgress(0.3f + p * 0.25f, "识别背景中（复杂图可能需要数十秒）")
            }
            if (matte != null && failReason.isNotEmpty()) engine = "内置背景识别（$failReason）"
        }
        if (matte == null) return null
        onProgress(0.6f, "精修透明边缘")

        // 3. 智能排除水印文字 / LOGO（小而贴边的孤立连通域）
        var removed = 0
        if (excludeMarks) {
            removed = removeMarkComponents(matte, w, h)
        }

        // 4. 手动笔刷优先级最高：保留强制不透明，擦除强制透明（涂抹水印 → 完全透明）
        for (i in matte.indices) {
            val m = matte[i]
            val kf = if (keepF != null) keepF[i] else 0f
            val ef = if (eraseF != null) eraseF[i] else 0f
            var v = max(m, kf) * (1f - ef)
            if (v < 0f) v = 0f else if (v > 1f) v = 1f
            matte[i] = v
        }

        // 5. 合成输出：保留原 RGB，alpha = 原alpha × 主体蒙版（非预乘色直接写回）
        onProgress(0.85f, "生成透明图")
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = (px[i] ushr 24) * matte[i] + 0.5f
            val na = a.toInt().coerceIn(0, 255)
            px[i] = (na shl 24) or (px[i] and 0x00FFFFFF)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        onProgress(0.95f, "完成")
        return MattingResult(out, removed, engine)
    }

    private class ModelCandidate(val asset: String, val label: String)

    private fun modelCandidates(): List<ModelCandidate> = listOf(
        ModelCandidate(MODEL_ASSET, "U2Net AI 模型"),
        ModelCandidate(MODEL_LITE_ASSET, "U2Net 轻量模型（兼容模式）")
    )

    // ==================== U2Net / U2NetP ONNX 推理 ====================

    private val sessionCache = HashMap<String, OrtSession>()

    private fun getSession(ctx: Context, asset: String): OrtSession {
        sessionCache[asset]?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        val bytes = ctx.assets.open(asset).use { inp ->
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
        sessionCache[asset] = s
        return s
    }

    /**
     * 运行 U2Net 系模型：输入 1x3x320x320（ImageNet 归一化），输出首个为 320x320 的
     * 主体概率图。做严格的合理性校验，任何异常/退化都抛出，由上层换下一个模型。
     * 透明像素先合成到白底，避免垃圾 RGB 干扰识别。
     */
    private fun runU2Net(ctx: Context, asset: String, src: Bitmap, onProgress: (Float, String) -> Unit): FloatArray {
        val session = getSession(ctx, asset)
        val env = OrtEnvironment.getEnvironment()
        val S = 320
        val area = S * S

        onProgress(0.15f, "AI 识别主体中")
        val inBmp = Bitmap.createScaledBitmap(src, S, S, true)
        val px = IntArray(area)
        inBmp.getPixels(px, 0, S, 0, 0, S, S)
        if (inBmp !== src) inBmp.recycle()

        // ImageNet mean/std 归一化；透明像素合成到白底（a=255 时等价于原值）
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val img = FloatArray(3 * area)
        for (i in 0 until area) {
            val c = px[i]
            val a = c ushr 24
            val r: Float; val g: Float; val b: Float
            if (a >= 255) {
                r = (c shr 16 and 0xFF).toFloat()
                g = (c shr 8 and 0xFF).toFloat()
                b = (c and 0xFF).toFloat()
            } else {
                // 合成到白底：out = src*a + 255*(1-a)，避免透明像素垃圾 RGB 干扰识别
                val af = a / 255f
                r = (c shr 16 and 0xFF) * af + 255f * (1f - af)
                g = (c shr 8 and 0xFF) * af + 255f * (1f - af)
                b = (c and 0xFF) * af + 255f * (1f - af)
            }
            img[i] = (r / 255f - mean[0]) / std[0]
            img[area + i] = (g / 255f - mean[1]) / std[1]
            img[2 * area + i] = (b / 255f - mean[2]) / std[2]
        }

        val inName = session.inputNames.firstOrNull() ?: "input.1"
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), longArrayOf(1, 3, S.toLong(), S.toLong()))
        val matteSmall = FloatArray(area)
        try {
            session.run(mapOf(inName to tensor)).use { res ->
                val t = res.get(0) as OnnxTensor
                val shape = t.info.shape
                val cap = t.floatBuffer.capacity()
                // 输出布局允许 [1,1,320,320] / [1,320,320] / [320,320] 等
                if (cap < area / 4) throw IllegalStateException("模型输出尺寸异常: shape=$shape cap=$cap")
                val fb = t.floatBuffer
                val n = min(cap, area)
                // 合理性校验 + min-max 归一化（rembg 同款处理）
                var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
                for (i in 0 until n) {
                    val v = fb.get(i)
                    if (v.isNaN() || v.isInfinite()) throw IllegalStateException("模型输出含 NaN/Inf")
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                val range = mx - mn
                if (range < 1e-3f) throw IllegalStateException("模型输出为常量值（退化）")
                for (i in 0 until n) {
                    val v = (fb.get(i) - mn) / range
                    // 软阈值：弱响应压暗，主体边缘保持过渡
                    val s = ((v - 0.1f) / 0.8f).coerceIn(0f, 1f)
                    matteSmall[i] = s * s * (3f - 2f * s)
                }
            }
        } finally {
            tensor.close()
        }

        // 上采样到原图分辨率（双线性）：经 ARGB 位图插值完成
        val small = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val spx = IntArray(area) { i ->
            ((matteSmall[i] * 255).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        small.setPixels(spx, 0, S, 0, 0, S, S)
        val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (up !== small) small.recycle()
        val upx = IntArray(src.width * src.height)
        up.getPixels(upx, 0, src.width, 0, 0, src.width, src.height)
        up.recycle()
        return FloatArray(upx.size) { (upx[it] ushr 24) / 255f }
    }

    // ==================== 兜底：边界取色 + 泛洪背景识别 ====================

    /**
     * 从四条边界采样背景色 → 每像素与最近背景色的距离 →
     * 与边界连通的相似区域判定为背景（完全透明），其余按距离给出软过渡透明度。
     * 白衣服+白背景这类「背景色同色但被主体隔断」的情况不会被误删。
     *
     * 性能关键：距离计算在降采样图（长边 ≤ 900）上做，再双线性上采样回原尺寸，
     * 千万级像素也不会卡几十秒。
     */
    private fun backgroundHeuristic(src: Bitmap, onProgress: (Float) -> Unit = {}): FloatArray? {
        val w = src.width; val h = src.height
        if (w < 2 || h < 2) return null

        // 降采样工作图（长边 ≤ 900）
        val longSide = max(w, h)
        val ds = if (longSide > 900) 900f / longSide else 1f
        val dw = max(2, (w * ds).toInt())
        val dh = max(2, (h * ds).toInt())
        val work = if (dw == w && dh == h) src
        else Bitmap.createScaledBitmap(src, dw, dh, true)
        onProgress(0.15f)
        val matteSmall = heuristicOnBitmap(work, dw, dh, onProgress)
        if (work !== src) work.recycle()
        if (matteSmall == null) return null
        onProgress(0.85f)

        // 上采样回原分辨率（经 ARGB 位图双线性插值，蒙版边缘自然过渡）
        val small = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val spx = IntArray(dw * dh) { i ->
            ((matteSmall[i] * 255).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        small.setPixels(spx, 0, dw, 0, 0, dw, dh)
        val up = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        val upx = IntArray(w * h)
        up.getPixels(upx, 0, w, 0, 0, w, h)
        up.recycle()
        return FloatArray(upx.size) { (upx[it] ushr 24) / 255f }
    }

    /** 在给定尺寸的位图上执行边界取色+泛洪（matte 尺寸 = bmp 尺寸） */
    private fun heuristicOnBitmap(bmp: Bitmap, w: Int, h: Int, onProgress: (Float) -> Unit): FloatArray? {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 边界采样（跳过完全透明像素：透明本身也算背景信号）
        val samples = ArrayList<Int>(512)
        val step = max(1, max(w, h) / 128)
        fun addSample(x: Int, y: Int) {
            samples.add(px[y * w + x])
        }
        for (x in 0 until w step step) { addSample(x, 0); addSample(x, h - 1) }
        for (y in 0 until h step step) { addSample(0, y); addSample(w - 1, y) }

        // 距离图（与最近边界采样的欧氏距离，透明采样距离恒为 0）
        onProgress(0.3f)
        val dist = FloatArray(w * h)
        val transparentBg = BooleanArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a < 32) { transparentBg[i] = true; dist[i] = 0f; continue }
            var best = Float.MAX_VALUE
            val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            for (s in samples) {
                val sa = s ushr 24
                if (sa < 32) continue   // 透明采样与不透明像素不算同色
                val dr = (s shr 16 and 0xFF) - r; val dg = (s shr 8 and 0xFF) - g; val db = (s and 0xFF) - b
                val d = (dr * dr + dg * dg + db * db).toFloat()
                if (d < best) best = d
            }
            if (best == Float.MAX_VALUE) best = 255f * 255f
            dist[i] = sqrt(best)
        }
        onProgress(0.6f)

        // 泛洪：与边界连通的近背景区域 = 确定背景
        val bg = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun trySeed(x: Int, y: Int) {
            val i = y * w + x
            if (!bg[i] && (transparentBg[i] || dist[i] < 30f)) { bg[i] = true; queue.add(i) }
        }
        for (x in 0 until w) { trySeed(x, 0); trySeed(x, h - 1) }
        for (y in 0 until h) { trySeed(0, y); trySeed(w - 1, y) }
        while (queue.isNotEmpty()) {
            val i = queue.poll()
            val x = i % w; val y = i / w
            if (x > 0) trySeed(x - 1, y)
            if (x < w - 1) trySeed(x + 1, y)
            if (y > 0) trySeed(x, y - 1)
            if (y < h - 1) trySeed(x, y + 1)
        }
        onProgress(0.75f)

        val matte = FloatArray(w * h)
        for (i in px.indices) {
            when {
                bg[i] -> matte[i] = 0f
                transparentBg[i] -> matte[i] = 0f
                else -> {
                    // 距离软阈值：30 以下背景、70 以上前景，之间线性过渡
                    val v = ((dist[i] - 30f) / 40f).coerceIn(0f, 1f)
                    matte[i] = v * v * (3f - 2f * v)
                }
            }
        }
        // 蒙版轻度平滑，边缘更自然
        return boxBlurF(matte, w, h, 2, 1)
    }

    // ==================== 水印 / LOGO 排除（连通域分析） ====================

    /**
     * 剔除「面积小 + 紧贴图像边缘/角落」的孤立前景连通域：
     * AI 生成水印文字、平台 LOGO、作者署名几乎都落在这个特征里；
     * 最大连通域（主体）永远保留，较大的次要主体也不受影响。
     * @return 剔除的区域数量
     */
    private fun removeMarkComponents(matte: FloatArray, w: Int, h: Int): Int {
        val total = w * h
        val visited = BooleanArray(total)
        var bestId = -1
        var bestArea = 0L
        data class Comp(val id: Int, val area: Long, val touchBorder: Boolean)
        val comps = ArrayList<Comp>()
        val compId = IntArray(total)
        var cid = 0

        // 先找最大连通域（迭代 DFS 防栈溢出）
        val stack = ArrayDeque<Int>()
        for (start in 0 until total) {
            if (visited[start] || matte[start] <= 0.35f) continue
            cid++
            var area = 0L
            var border = false
            stack.clear(); stack.add(start); visited[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.poll()
                compId[i] = cid; area++
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0 && !visited[i - 1] && matte[i - 1] > 0.35f) { visited[i - 1] = true; stack.add(i - 1) }
                if (x < w - 1 && !visited[i + 1] && matte[i + 1] > 0.35f) { visited[i + 1] = true; stack.add(i + 1) }
                if (y > 0 && !visited[i - w] && matte[i - w] > 0.35f) { visited[i - w] = true; stack.add(i - w) }
                if (y < h - 1 && !visited[i + w] && matte[i + w] > 0.35f) { visited[i + w] = true; stack.add(i + w) }
            }
            if (area > bestArea) { bestArea = area; bestId = cid }
            comps.add(Comp(cid, area, border))
        }

        var removed = 0
        for (c in comps) {
            if (c.id == bestId) continue
            val small = c.area < total * 0.04
            if (!small) continue
            if (c.touchBorder) {
                clearComp(matte, compId, w, h, c.id)
                removed++
            } else {
                // 不贴边的孤立小块：只有非常小（<0.5%）才当作漂浮水印剔除
                if (c.area < total * 0.005) {
                    clearComp(matte, compId, w, h, c.id)
                    removed++
                }
            }
        }
        return removed
    }

    private fun clearComp(matte: FloatArray, compId: IntArray, w: Int, h: Int, id: Int) {
        for (i in compId.indices) if (compId[i] == id) matte[i] = 0f
    }

    // ==================== 工具函数 ====================

    /** 涂抹笔刷 → 羽化浮点蒙版（0..1），无笔画返回 null。
     *  在长边 ≤ 2048 的工作分辨率上光栅化再上采样：
     *  全分辨率浮点运算在千万像素图上要吃 200MB+ 临时内存（低内存设备必 OOM），且慢。 */
    private fun strokeToFloat(strokes: List<Stroke>, w: Int, h: Int): FloatArray? {
        if (strokes.isEmpty() || w <= 1 || h <= 1) return null

        // 工作分辨率（长边 ≤ 2048）
        val longSide = max(w, h)
        val ds = if (longSide > 2048) 2048f / longSide else 1f
        val dw = max(2, (w * ds).toInt())
        val dh = max(2, (h * ds).toInt())

        val bmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        var any = false
        for (s in strokes) {
            if (s.pts.isEmpty()) continue
            val half = s.halfWidthFrac.coerceIn(0.001f, 0.2f) * dw
            if (s.pts.size == 1) {
                c.drawCircle(s.pts[0].first * dw, s.pts[0].second * dh, half, p)
                any = true
            } else {
                sp.strokeWidth = half * 2f
                val path = Path()
                path.moveTo(s.pts[0].first * dw, s.pts[0].second * dh)
                for (i in 1 until s.pts.size) {
                    val x = s.pts[i].first * dw; val y = s.pts[i].second * dh
                    val mx = (s.pts[i - 1].first * dw + x) / 2f
                    val my = (s.pts[i - 1].second * dh + y) / 2f
                    path.quadTo(s.pts[i - 1].first * dw, s.pts[i - 1].second * dh, mx, my)
                }
                path.lineTo(s.pts.last().first * dw, s.pts.last().second * dh)
                c.drawPath(path, sp)
                any = true
            }
        }
        if (!any) { bmp.recycle(); return null }
        val px = IntArray(dw * dh)
        bmp.getPixels(px, 0, dw, 0, 0, dw, dh)
        bmp.recycle()
        val f = FloatArray(dw * dh) { (px[it] ushr 24) / 255f }
        val small = boxBlurF(f, dw, dh, 4, 2)

        // 上采样回原分辨率
        val smallBmp = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val spx = IntArray(dw * dh) { i ->
            ((small[i] * 255).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        smallBmp.setPixels(spx, 0, dw, 0, 0, dw, dh)
        val up = Bitmap.createScaledBitmap(smallBmp, w, h, true)
        smallBmp.recycle()
        val upx = IntArray(w * h)
        up.getPixels(upx, 0, w, 0, 0, w, h)
        up.recycle()
        return FloatArray(upx.size) { (upx[it] ushr 24) / 255f }
    }

    /** 盒滤波模糊（水平+垂直分离，多轮） */
    private fun boxBlurF(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int): FloatArray {
        var cur = src.copyOf()
        val tmp = FloatArray(w * h)
        val span = radius * 2 + 1
        repeat(passes) {
            for (y in 0 until h) {
                val row = y * w
                var sum = 0f
                for (x in 0 until span.coerceAtMost(w)) sum += cur[row + x.coerceIn(0, w - 1)]
                val cnt = span.coerceAtMost(w).toFloat()
                for (x in 0 until w) {
                    tmp[row + x] = sum / cnt
                    val xa = (x + radius + 1).coerceIn(0, w - 1)
                    val xs = (x - radius).coerceIn(0, w - 1)
                    sum += cur[row + xa] - cur[row + xs]
                }
            }
            for (x in 0 until w) {
                var sum = 0f
                for (y in 0 until span.coerceAtMost(h)) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                val cnt = span.coerceAtMost(h).toFloat()
                for (y in 0 until h) {
                    cur[y * w + x] = sum / cnt
                    val ya = (y + radius + 1).coerceIn(0, h - 1)
                    val ys = (y - radius).coerceIn(0, h - 1)
                    sum += tmp[ya * w + x] - tmp[ys * w + x]
                }
            }
        }
        return cur
    }
}
