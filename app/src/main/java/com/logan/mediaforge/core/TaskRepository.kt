package com.logan.mediaforge.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

enum class TaskStatus { IMPORTING, RUNNING, COMPLETED, FAILED, CANCELED }
enum class TaskKind { MEDIA, VIDEO, TRIM, IMAGE }

data class ConvertTask(
    val id: Long,
    val kind: TaskKind,
    val typeName: String,       // 视频 → 音频 / 音频 → 音频 / 音频裁剪 / 图片格式转换 ...
    val sourceName: String,
    val sourceReal: String,     // 源文件真实格式（魔数识别）
    val fromExt: String,
    val toExt: String,
    val detail: String,         // 参数明细
    val status: TaskStatus,
    val phase: String,          // 导入源文件 / 转码中 / 已完成 ...
    val progress: Float,        // 0..1 真实进度（FFmpeg 统计）
    val output: File? = null,
    val outputInfo: String = "",
    val durationMs: Long = 0,
    val error: String? = null,
    val sessionId: Long? = null,
    val downloadedName: String? = null,
    val downloadedUri: Uri? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 转换任务仓库：
 * - 多任务并发（无上限）
 * - 真实进度（FFmpeg Statistics 时间统计 / 总时长）
 * - 完成后用 FFprobe 校验输出容器真实格式（防止"假转换"）
 */
object TaskRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<ConvertTask>>(emptyList())
    val tasks: StateFlow<List<ConvertTask>> = _tasks.asStateFlow()

    private lateinit var appContext: Context
    private val counter = AtomicLong(1)
    private val persistPending = AtomicBoolean(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        restore()
    }

    // ==================== 任务持久化（杀掉 APP 后保留历史任务） ====================

    private fun prefs() = appContext.getSharedPreferences("mediaforge_tasks", Context.MODE_PRIVATE)

    private fun persist() {
        if (!persistPending.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try { Thread.sleep(500) } catch (_: InterruptedException) { }
            persistPending.set(false)
            try {
                val arr = JSONArray()
                _tasks.value.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("id", t.id)
                        put("kind", t.kind.name)
                        put("typeName", t.typeName)
                        put("sourceName", t.sourceName)
                        put("sourceReal", t.sourceReal)
                        put("fromExt", t.fromExt)
                        put("toExt", t.toExt)
                        put("detail", t.detail)
                        put("status", t.status.name)
                        put("phase", t.phase)
                        put("progress", t.progress.toDouble())
                        put("output", t.output?.absolutePath ?: "")
                        put("outputInfo", t.outputInfo)
                        put("durationMs", t.durationMs)
                        put("error", t.error ?: "")
                        put("downloadedName", t.downloadedName ?: "")
                        put("downloadedUri", t.downloadedUri?.toString() ?: "")
                        put("createdAt", t.createdAt)
                    })
                }
                prefs().edit().putString("tasks", arr.toString()).apply()
            } catch (_: Exception) { }
        }
    }

    private fun restore() {
        try {
            val s = prefs().getString("tasks", null) ?: return
            val arr = JSONArray(s)
            val list = mutableListOf<ConvertTask>()
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                maxId = maxOf(maxId, id)
                val savedStatus = try { TaskStatus.valueOf(o.optString("status", "FAILED")) } catch (_: Exception) { TaskStatus.FAILED }
                val kind = try { TaskKind.valueOf(o.optString("kind", "MEDIA")) } catch (_: Exception) { TaskKind.MEDIA }
                // 上次退出时还在进行的任务标记为中断失败，其余原样保留
                val interrupted = savedStatus == TaskStatus.RUNNING || savedStatus == TaskStatus.IMPORTING
                val status = if (interrupted) TaskStatus.FAILED else savedStatus
                val outPath = o.optString("output", "")
                val out = if (outPath.isNotEmpty()) File(outPath) else null
                list.add(
                    ConvertTask(
                        id = id,
                        kind = kind,
                        typeName = o.optString("typeName", "转换任务"),
                        sourceName = o.optString("sourceName", ""),
                        sourceReal = o.optString("sourceReal", ""),
                        fromExt = o.optString("fromExt", ""),
                        toExt = o.optString("toExt", ""),
                        detail = o.optString("detail", ""),
                        status = status,
                        phase = if (interrupted) "中断" else o.optString("phase", ""),
                        progress = if (interrupted) 0f else o.optDouble("progress", 0.0).toFloat(),
                        output = out,
                        outputInfo = o.optString("outputInfo", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        error = if (interrupted) "应用被关闭，任务中断" else o.optString("error", "").ifEmpty { null },
                        downloadedName = o.optString("downloadedName", "").ifEmpty { null },
                        downloadedUri = o.optString("downloadedUri", "").ifEmpty { null }?.let { Uri.parse(it) },
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            counter.set(maxId + 1)
            // 历史任务按创建时间倒序（最新在最顶部）
            _tasks.value = list.sortedByDescending { it.id }
        } catch (_: Exception) { }
    }

    val outputsDir: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "outputs").apply { mkdirs() }

    private val importsDir: File
        get() = File(appContext.cacheDir, "imports").apply { mkdirs() }

    fun update(id: Long, transform: (ConvertTask) -> ConvertTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persist()
    }

    private fun add(task: ConvertTask) {
        // 新任务插到列表最顶部（最新转换的排在最上面）
        _tasks.update { listOf(task) + it }
        persist()
    }

    fun uniqueOut(ext: String): File {
        var i = 0
        while (true) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val n = if (i == 0) "MediaForge_${ts}.$ext" else "MediaForge_${ts}_$i.$ext"
            val f = File(outputsDir, n)
            if (!f.exists()) return f
            i++
        }
    }

    // ==================== 音频任务（视频→音频 / 音频→音频 / 改参数） ====================

    fun startAudio(
        uri: Uri?, sourceFile: File?, displayName: String,
        params: AudioParams, trimStartMs: Long?, trimEndMs: Long?, isTrim: Boolean = false
    ) {
        val id = counter.getAndIncrement()
        val srcDesc = sourceFile?.name ?: displayName
        add(
            ConvertTask(
                id = id,
                kind = if (isTrim) TaskKind.TRIM else TaskKind.MEDIA,
                typeName = if (isTrim) "音频裁剪" else "准备中",
                sourceName = srcDesc,
                sourceReal = "",
                fromExt = srcDesc.substringAfterLast('.', "?"),
                toExt = params.ext,
                detail = params.describe(),
                status = TaskStatus.IMPORTING,
                phase = "导入源文件",
                progress = 0f
            )
        )
        scope.launch {
            try {
                var src = sourceFile
                if (src == null && uri != null) src = importUri(id, uri, displayName)
                if (isCanceled(id)) return@launch
                if (src == null || !src.exists() || src.length() == 0L) {
                    fail(id, "导入源文件失败（文件不可读或为空）")
                    return@launch
                }
                val det = FileSniffer.detect(src)
                val info = MediaProbe.probe(src.absolutePath)
                val total = info?.durationMs ?: 0L
                // 裁剪区间按源文件实际时长钳制：防止基于旧时长（如裁剪前的总时长）选择的
                // 区间超出文件实际范围，导致 FFmpeg 裁出空/无效文件而无法播放
                var ts = trimStartMs
                var te = trimEndMs
                if (ts != null || te != null) {
                    if (total <= 0L) {
                        fail(id, "无法读取源文件时长，无法执行裁剪；请重新选择文件后重试")
                        return@launch
                    }
                    val s = (ts ?: 0L).coerceIn(0L, (total - 100L).coerceAtLeast(0L))
                    val rawEnd = te
                    val e: Long = if (rawEnd == null) {
                        total
                    } else if (rawEnd > total) {
                        total
                    } else if (rawEnd <= s) {
                        if (s + 100L <= total) s + 100L else total
                    } else {
                        rawEnd
                    }
                    if (e - s < 100L) {
                        fail(id, "裁剪区间无效：文件实际时长 ${fmtMs(total)}，所选区间超出文件范围")
                        return@launch
                    }
                    ts = s; te = e
                }
                val fromExt = src.extension.ifBlank { det?.realExt ?: "bin" }
                val kindName = when {
                    isTrim -> "音频裁剪"
                    det?.category == "VIDEO" -> "视频 → 音频"
                    det?.category == "AUDIO" ->
                        if (fromExt.equals(params.ext, true)) "音频 → 音频（同格式改参数）" else "音频 → 音频"
                    else -> "媒体 → 音频"
                }
                update(id) {
                    it.copy(
                        typeName = kindName, sourceReal = det?.realName ?: "未知",
                        fromExt = fromExt, durationMs = total,
                        status = TaskStatus.RUNNING, phase = "转码中", progress = 0f
                    )
                }
                val out = uniqueOut(if (params.ext == "alac") "m4a" else params.ext)
                // ALAC 无独立封装器，必须装进 M4A 容器（spec.notes 已说明）；文件后缀用 m4a
                val args = buildAudioArgs(src.absolutePath, out.absolutePath, params, ts, te)
                // 进度分母用裁剪后的有效时长（无裁剪时为源总时长），进度更准确
                val progTotal = if (ts != null && te != null && te > ts) te - ts else total
                val session = runFfmpeg(id, args, progTotal)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    if (ReturnCode.isCancel(session.returnCode)) {
                        update(id) { it.copy(status = TaskStatus.CANCELED, phase = "已取消") }
                    } else {
                        fail(id, "FFmpeg 转换失败：" + tailLog(session))
                    }
                    return@launch
                }
                // MP2 特殊：ffprobe 对 MPEG 层2 文件可能报 mp3（两者共用同一 MPEG 音频解复用器），
                // 校验接受别名，否则 MP2 目标永远校验失败
                val verifyKey = when (params.ext) {
                    "mp2" -> "mp2|mp3"
                    else -> AudioFormats.byExt(params.ext)?.verifyKey ?: ""
                }
                verifyAndFinish(id, out, verifyKey)
            } catch (e: Exception) {
                fail(id, "异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ==================== 视频任务（视频 → 视频） ====================

    fun startVideo(uri: Uri?, sourceFile: File?, displayName: String, params: VideoParams) {
        val id = counter.getAndIncrement()
        val srcDesc = sourceFile?.name ?: displayName
        add(
            ConvertTask(
                id = id, kind = TaskKind.VIDEO, typeName = "视频 → 视频",
                sourceName = srcDesc, sourceReal = "",
                fromExt = srcDesc.substringAfterLast('.', "?"), toExt = params.ext,
                detail = "目标容器 ${params.ext.uppercase()} · ${params.vcodec} · CRF ${params.crf}" +
                    (if (params.scaleW > 0) " · ${params.scaleW}x${params.scaleH}" else " · 原始分辨率") +
                    (if (params.fps > 0) " · ${params.fps}fps" else "") +
                    (if (params.describeRatio().isNotEmpty()) " · ${params.describeRatio()}" else "") +
                    (if (params.describeColor().isNotEmpty()) " · ${params.describeColor()}" else ""),
                status = TaskStatus.IMPORTING, phase = "导入源文件", progress = 0f
            )
        )
        scope.launch {
            try {
                var src = sourceFile
                if (src == null && uri != null) src = importUri(id, uri, displayName)
                if (isCanceled(id)) return@launch
                if (src == null || !src.exists() || src.length() == 0L) {
                    fail(id, "导入源文件失败（文件不可读或为空）")
                    return@launch
                }
                val det = FileSniffer.detect(src)
                val info = MediaProbe.probe(src.absolutePath)
                val total = info?.durationMs ?: 0L
                update(id) {
                    it.copy(
                        sourceReal = det?.realName ?: "未知",
                        fromExt = src.extension.ifBlank { det?.realExt ?: "bin" },
                        durationMs = total,
                        status = TaskStatus.RUNNING, phase = "转码中", progress = 0f
                    )
                }
                val out = uniqueOut(params.ext)
                val args = buildVideoArgs(src.absolutePath, out.absolutePath, params)
                val session = runFfmpeg(id, args, total)
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    if (ReturnCode.isCancel(session.returnCode)) {
                        update(id) { it.copy(status = TaskStatus.CANCELED, phase = "已取消") }
                    } else {
                        fail(id, "FFmpeg 转换失败：" + tailLog(session))
                    }
                    return@launch
                }
                verifyAndFinish(id, out, VideoFormats.byExt(params.ext)?.verifyKey ?: "")
            } catch (e: Exception) {
                fail(id, "异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ==================== 图片任务 ====================

    fun startImage(
        uri: Uri, displayName: String, targetExt: String, w: Int, h: Int, quality: Int,
        crop: ImageEngine.CropRect = ImageEngine.CropRect()
    ) {
        val id = counter.getAndIncrement()
        val cropDesc = if (!crop.isFull)
            " · 已裁剪 ${((crop.r - crop.l) * 100).toInt()}%×${((crop.b - crop.t) * 100).toInt()}%" else ""
        add(
            ConvertTask(
                id = id, kind = TaskKind.IMAGE, typeName = "图片格式转换",
                sourceName = displayName, sourceReal = "",
                fromExt = displayName.substringAfterLast('.', "?"), toExt = targetExt,
                detail = (if (w > 0 && h > 0) "${w}×${h} · " else "") +
                    (if (!crop.isFull) "裁剪 ${aspectRatioText(
                        ((crop.r - crop.l) * 1000).toInt(), ((crop.b - crop.t) * 1000).toInt())} · " else "") +
                    "目标 ${targetExt.uppercase()} · 质量 $quality",
                status = TaskStatus.RUNNING, phase = "转换中", progress = 0.3f
            )
        )
        scope.launch {
            try {
                val out = uniqueOut(targetExt)
                val ok = ImageEngine.convert(appContext, uri, targetExt, w, h, quality, out, crop)
                if (!ok) {
                    fail(id, "图片转换失败（源文件可能无法解码或已损坏）")
                    return@launch
                }
                val det = FileSniffer.detect(out)
                if (det == null || det.realExt != targetExt) {
                    fail(id, "输出校验失败：实际格式为 ${det?.realName ?: "未知"}，与目标 ${targetExt.uppercase()} 不符")
                    return@launch
                }
                update(id) {
                    it.copy(
                        status = TaskStatus.COMPLETED, phase = "已完成", progress = 1f,
                        output = out, outputInfo = (if (w > 0 && h > 0) "${w}×${h} · " else "") +
                            (if (!crop.isFull) "已裁剪" + cropDesc.trimStart('·', ' ') + " · " else "") +
                            fmtBytes(out.length())
                    )
                }
            } catch (e: OutOfMemoryError) {
                fail(id, "内存不足：分辨率过大或图片太大，请调小分辨率后重试")
            } catch (e: Exception) {
                fail(id, "异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // ==================== 图片去水印（LaMa AI / 内置算法，完全离线） ====================

    fun startInpaint(
        uri: Uri, displayName: String,
        rects: List<InpaintEngine.BoxRect>, strokes: List<InpaintEngine.Stroke>,
        outFmt: String = "follow",                       // follow=跟随原图 / png / jpg / webp
        transparentMode: Boolean = false                 // 透明模式：水印区域恢复完全透明（仅 PNG/WebP 输出）
    ) {
        val id = counter.getAndIncrement()
        val hasModel = InpaintEngine.modelAvailable(appContext)
        val engineName = if (hasModel) "LaMa AI 模型" else "内置智能填充"
        val fmtLabel = if (outFmt == "follow") "跟随原图" else outFmt.uppercase()
        val modeDesc = if (transparentMode) " · 透明模式（水印区域恢复透明）" else ""
        add(
            ConvertTask(
                id = id, kind = TaskKind.IMAGE, typeName = "图片去水印",
                sourceName = displayName, sourceReal = "",
                fromExt = displayName.substringAfterLast('.', "?"), toExt = fmtLabel,
                detail = "框选×${rects.size} · 涂抹×${strokes.size} · 引擎：$engineName · 导出：$fmtLabel$modeDesc",
                status = TaskStatus.IMPORTING, phase = "导入源图片", progress = 0f
            )
        )
        scope.launch {
            var result: Bitmap? = null
            try {
                val src = importUri(id, uri, displayName)
                if (isCanceled(id)) return@launch
                if (src == null || !src.exists() || src.length() == 0L) {
                    fail(id, "导入源图片失败（文件不可读或为空）")
                    return@launch
                }
                update(id) { if (it.status == TaskStatus.IMPORTING) it.copy(status = TaskStatus.RUNNING, phase = "解码图片", progress = 0.03f) else it }
                val bmp = decodeBitmapCapped(src, 20_000_000L)
                if (bmp == null) {
                    fail(id, "图片解码失败（文件可能已损坏或不是可识别的图片）")
                    return@launch
                }
                val det = FileSniffer.detect(src)
                update(id) {
                    it.copy(sourceReal = det?.realName ?: "未知", phase = "修复中")
                }
                // 决定导出格式：跟随原图时 JPG/PNG/WebP 原格式回写（分辨率不变，只去水印）；
                // HEIC/GIF/BMP/TIFF 等系统无法原样回写 → 自动回退 PNG 并在任务里提示。
                // 透明模式下只允许 PNG/WebP（JPG 丢弃透明通道），跟随原图时同样按此限制
                val realExt = det?.realExt ?: ""
                val srcHasAlpha = bmp.hasAlpha()
                var target = if (outFmt == "follow") {
                    if (realExt == "jpg" || realExt == "png" || realExt == "webp") realExt else "png"
                } else outFmt
                val fallbackNotes = mutableListOf<String>()
                if (outFmt == "follow" && !(realExt == "jpg" || realExt == "png" || realExt == "webp"))
                    fallbackNotes.add("原格式 ${det?.realName ?: "未知"} 无法原样回写，已导出 PNG")
                if (transparentMode) {
                    if (target == "jpg") {
                        target = "png"
                        fallbackNotes.add("透明模式不支持 JPG，已导出 PNG（JPG 会丢失透明通道）")
                    } else if (outFmt == "follow" && realExt != "png" && realExt != "webp") {
                        fallbackNotes.add("透明模式仅支持 PNG/WebP，已导出 PNG")
                    }
                }
                update(id) {
                    it.copy(toExt = target, detail = it.detail + " → ${target.uppercase()}")
                }
                result = InpaintEngine.inpaint(appContext, bmp, rects, strokes, { p, ph ->
                    update(id) {
                        if (it.status == TaskStatus.RUNNING) it.copy(progress = p.coerceIn(0.05f, 0.95f), phase = ph) else it
                    }
                }, transparentOut = transparentMode)
                bmp.recycle()
                if (isCanceled(id)) return@launch
                if (result == null) {
                    fail(id, "去水印失败：未指定有效区域或引擎异常，请重新框选/涂抹后重试")
                    return@launch
                }
                update(id) { it.copy(phase = "保存结果", progress = 0.97f) }
                val out = uniqueOut(target)
                val ok = out.outputStream().use { fos ->
                    when (target) {
                        "jpg" -> result!!.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        "webp" -> result!!.compress(
                            // 透明模式下用无损 WebP，保证透明通道不劣化
                            if (transparentMode) {
                                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                                else Bitmap.CompressFormat.WEBP
                            } else if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                            else Bitmap.CompressFormat.WEBP,
                            if (transparentMode) 100 else 95, fos
                        )
                        else -> result!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                if (!ok || !out.exists() || out.length() == 0L) {
                    fail(id, "保存结果失败")
                    return@launch
                }
                // 真实格式校验：导出什么格式就是什么格式，不是改后缀
                val det2 = FileSniffer.detect(out)
                if (det2 == null || det2.realExt != target) {
                    fail(id, "输出校验失败：实际格式为 ${det2?.realName ?: "未知"}，与目标 ${target.uppercase()} 不符")
                    return@launch
                }
                update(id) {
                    it.copy(
                        status = TaskStatus.COMPLETED, phase = "已完成", progress = 1f,
                        output = out,
                        outputInfo = "${result!!.width}×${result!!.height} · ${target.uppercase()}" +
                            (if (fallbackNotes.isNotEmpty()) " · " + fallbackNotes.joinToString("；") else "") +
                            (if (transparentMode && srcHasAlpha) " · 水印区域已恢复完全透明" else "") +
                            " · " + fmtBytes(out.length())
                    )
                }
            } catch (e: OutOfMemoryError) {
                fail(id, "内存不足：图片过大或区域过大，请稍后重试")
            } catch (e: Exception) {
                fail(id, "异常：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                result?.recycle()
            }
        }
    }

    // ==================== AI 抠图（U2Net / 内置算法，完全离线，输出透明 PNG） ====================

    fun startMatting(
        uri: Uri, displayName: String,
        keep: List<InpaintEngine.Stroke>, erase: List<InpaintEngine.Stroke>,
        excludeMarks: Boolean
    ) {
        val id = counter.getAndIncrement()
        add(
            ConvertTask(
                id = id, kind = TaskKind.IMAGE, typeName = "AI 抠图",
                sourceName = displayName, sourceReal = "",
                fromExt = displayName.substringAfterLast('.', "?"), toExt = "PNG",
                detail = "保留涂抹×${keep.size} · 擦除涂抹×${erase.size} · " +
                    (if (excludeMarks) "智能排除水印/LOGO：开" else "智能排除水印/LOGO：关") + " · 导出：PNG（透明）",
                status = TaskStatus.IMPORTING, phase = "导入源图片", progress = 0f
            )
        )
        scope.launch {
            var result: Bitmap? = null
            try {
                val src = importUri(id, uri, displayName)
                if (isCanceled(id)) return@launch
                if (src == null || !src.exists() || src.length() == 0L) {
                    fail(id, "导入源图片失败（文件不可读或为空）")
                    return@launch
                }
                update(id) { if (it.status == TaskStatus.IMPORTING) it.copy(status = TaskStatus.RUNNING, phase = "解码图片", progress = 0.03f) else it }
                // 抠图蒙版全程浮点运算，像素上限更保守，防低内存设备 OOM
                val bmp = decodeBitmapCapped(src, 14_000_000L)
                if (bmp == null) {
                    fail(id, "图片解码失败（文件可能已损坏或不是可识别的图片）")
                    return@launch
                }
                val det = FileSniffer.detect(src)
                update(id) { it.copy(sourceReal = det?.realName ?: "未知", phase = "抠图中") }
                val r = MattingEngine.matting(appContext, bmp, keep, erase, excludeMarks) { p, ph ->
                    update(id) {
                        if (it.status == TaskStatus.RUNNING) it.copy(progress = p.coerceIn(0.05f, 0.95f), phase = ph) else it
                    }
                }
                bmp.recycle()
                if (isCanceled(id)) return@launch
                if (r == null) {
                    fail(id, "抠图失败：图片无法解码或引擎异常，请重试")
                    return@launch
                }
                result = r.bitmap
                update(id) { it.copy(phase = "保存结果", progress = 0.97f) }
                val out = uniqueOut("png")
                val ok = out.outputStream().use { fos ->
                    // 抠图结果必须 PNG：JPG 无透明通道，直接锁死
                    result!!.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                if (!ok || !out.exists() || out.length() == 0L) {
                    fail(id, "保存结果失败")
                    return@launch
                }
                val det2 = FileSniffer.detect(out)
                if (det2 == null || det2.realExt != "png") {
                    fail(id, "输出校验失败：实际格式为 ${det2?.realName ?: "未知"}，与目标 PNG 不符")
                    return@launch
                }
                update(id) {
                    it.copy(
                        status = TaskStatus.COMPLETED, phase = "已完成", progress = 1f,
                        output = out,
                        outputInfo = "${result!!.width}×${result!!.height} · PNG（透明） · 引擎：${r.engine}" +
                            (if (excludeMarks && r.removedMarks > 0) " · 已剔除 ${r.removedMarks} 处疑似水印/LOGO 区域" else "") +
                            " · " + fmtBytes(out.length())
                    )
                }
            } catch (e: OutOfMemoryError) {
                fail(id, "内存不足：图片过大，请稍后重试")
            } catch (e: Exception) {
                fail(id, "异常：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                result?.recycle()
            }
        }
    }

    /** 解码图片，超过 maxPixels 时按倍数降采样防 OOM */
    private fun decodeBitmapCapped(f: File, maxPixels: Long): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null
        else {
            var sample = 1
            while (opts.outWidth.toLong() * opts.outHeight / (sample.toLong() * sample) > maxPixels) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.absolutePath, o2)
        }
    } catch (e: Exception) {
        null
    }

    // ==================== 裁剪 ====================

    fun startTrim(sourceFile: File, startSec: Double, endSec: Double) {
        val ext = sourceFile.extension.ifBlank { "wav" }
        val params = AudioParams(ext = ext)
        startAudio(
            null, sourceFile, sourceFile.name, params,
            (startSec * 1000).toLong(), (endSec * 1000).toLong(), isTrim = true
        )
    }

    // ==================== 内部流程 ====================

    private suspend fun importUri(id: Long, uri: Uri, displayName: String): File? = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "").ifBlank { "bin" }
        val dst = File(importsDir, "src_$id.$ext")
        try {
            val size = FileSniffer.querySize(appContext.contentResolver, uri)
            val input = appContext.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { ins ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        if (isCanceled(id)) {
                            dst.delete()
                            return@withContext null
                        }
                        val r = ins.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        copied += r
                        if (size > 0) {
                            val p = ((copied.toDouble() / size) * 0.99).toFloat().coerceIn(0f, 0.99f)
                            update(id) { if (it.status == TaskStatus.IMPORTING) it.copy(progress = p) else it }
                        }
                    }
                }
            }
            dst
        } catch (e: Exception) {
            dst.delete()
            null
        }
    }

    private suspend fun runFfmpeg(id: Long, args: List<String>, totalMs: Long): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s -> if (cont.isActive) cont.resume(s) },
                { },
                { stats ->
                    val t = stats.time.toLong()
                    if (totalMs > 0 && t > 0) {
                        val p = (t.toDouble() / totalMs).toFloat().coerceIn(0f, 0.99f)
                        update(id) {
                            if (it.status == TaskStatus.RUNNING && it.progress < p - 0.004f)
                                it.copy(progress = p) else it
                        }
                    }
                }
            )
            val sid = session.sessionId
            update(id) { it.copy(sessionId = sid) }
            cont.invokeOnCancellation {
                try { FFmpegKit.cancel(sid) } catch (_: Exception) { }
            }
        }

    private fun verifyAndFinish(id: Long, out: File, verifyKey: String) {
        if (!out.exists() || out.length() == 0L) {
            fail(id, "输出文件无效")
            return
        }
        val info = MediaProbe.probe(out.absolutePath)
        if (info == null) {
            fail(id, "输出校验失败：无法解析输出文件（不是有效的目标格式）")
            return
        }
        if (verifyKey.isNotEmpty() && verifyKey.split('|').none { info.formatName.contains(it) }) {
            fail(id, "输出校验失败：实际容器为 ${info.formatName}，与目标格式不符")
            return
        }
        val durText = if (info.durationMs > 0) fmtMs(info.durationMs) else "未知"
        update(id) {
            it.copy(
                status = TaskStatus.COMPLETED, phase = "已完成", progress = 1f,
                output = out, outputInfo = "时长 $durText · ${fmtBytes(out.length())}",
                // 关键：durationMs 必须记录「输出文件」的真实时长。
                // 之前只记录源文件时长，导致裁剪任务在任务页点「裁剪」时
                // 显示的是裁剪前的总时长，用户按旧时长再裁会裁出无效文件
                durationMs = if (info.durationMs > 0) info.durationMs else it.durationMs
            )
        }
    }

    private fun tailLog(session: FFmpegSession): String = try {
        val logs = session.allLogsAsString
        if (logs.length > 500) logs.substring(logs.length - 500) else logs
    } catch (e: Exception) {
        "returnCode=${session.returnCode}"
    }

    private fun fail(id: Long, msg: String) {
        update(id) {
            if (it.status == TaskStatus.CANCELED) it
            else it.copy(status = TaskStatus.FAILED, phase = "失败", error = msg.take(600))
        }
    }

    private fun isCanceled(id: Long): Boolean =
        _tasks.value.firstOrNull { it.id == id }?.status == TaskStatus.CANCELED

    // ==================== 操作 ====================

    fun cancel(id: Long) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(status = TaskStatus.CANCELED, phase = "已取消") }
        t.sessionId?.let { sid -> try { FFmpegKit.cancel(sid) } catch (_: Exception) { } }
    }

    fun delete(id: Long, alsoDeleteDownloaded: Boolean = false) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        // 先把任务从列表移除（UI 立即消失，杜绝“点了删除还显示”的观感问题），
        // 取消转码与文件删除放到后台执行，不阻塞也不依赖列表状态
        _tasks.update { it.filterNot { x -> x.id == id } }
        persist()
        if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.IMPORTING) {
            t.sessionId?.let { sid -> try { FFmpegKit.cancel(sid) } catch (_: Exception) { } }
        }
        scope.launch(Dispatchers.IO) {
            try { t.output?.delete() } catch (_: Exception) { }
            if (alsoDeleteDownloaded && t.downloadedUri != null) {
                try {
                    val uri = t.downloadedUri!!
                    if (uri.scheme == "file") {
                        File(uri.path ?: "").delete()
                    } else if (uri.authority == "media") {
                        appContext.contentResolver.delete(uri, null, null)
                    } else {
                        DocumentsContract.deleteDocument(appContext.contentResolver, uri)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun download(id: Long, onResult: (String) -> Unit) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        val f = t.output ?: return
        val category = when (t.kind) {
            TaskKind.IMAGE -> SettingsRepo.CAT_IMAGE
            TaskKind.MEDIA, TaskKind.TRIM -> SettingsRepo.CAT_AUDIO
            else -> SettingsRepo.CAT_FILE
        }
        scope.launch {
            val res = SaveHelper.download(appContext, f, t.toExt, category)
            if (res.uri != null) {
                update(id) { it.copy(downloadedName = res.name, downloadedUri = res.uri) }
            }
            withContext(Dispatchers.Main) {
                onResult(res.error ?: "已保存：${res.name}（下载/MediaForge 或所选文件夹）")
            }
        }
    }

    fun rename(id: Long, newName: String, onResult: (String) -> Unit) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        scope.launch {
            val res = SaveHelper.rename(appContext, t.output, t.downloadedUri, newName, t.toExt)
            if (res.error == null) {
                update(id) {
                    it.copy(
                        output = res.file ?: it.output,
                        downloadedUri = res.uri ?: it.downloadedUri,
                        downloadedName = if (it.downloadedUri != null) res.name else it.downloadedName
                    )
                }
            }
            withContext(Dispatchers.Main) {
                onResult(res.error ?: "已重命名为：${res.name}")
            }
        }
    }
}
