package com.logan.mediaforge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.ConvertTask
import com.logan.mediaforge.core.TaskKind
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.TaskStatus
import com.logan.mediaforge.core.fmtMs
import com.logan.mediaforge.ui.AudioPlayer
import com.logan.mediaforge.ui.StatusText
import com.logan.mediaforge.ui.TrimEditor
import com.logan.mediaforge.ui.VideoPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val taskTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun fmtTaskTime(ms: Long): String {
    if (ms <= 0L) return ""
    return taskTimeFmt.format(Date(ms))
}

@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val tasks by TaskRepository.tasks.collectAsState()

    var showPlayerFor by remember { mutableStateOf<Long?>(null) }
    var renameFor by remember { mutableStateOf<ConvertTask?>(null) }
    var trimFor by remember { mutableStateOf<ConvertTask?>(null) }
    var confirmDelete by remember { mutableStateOf<ConvertTask?>(null) }
    var pendingPermDownload by remember { mutableStateOf<Long?>(null) }

    fun onResult(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingPermDownload
        pendingPermDownload = null
        if (granted && id != null) {
            TaskRepository.download(id) { onResult(it) }
        } else if (id != null) {
            Toast.makeText(context, "未授予存储权限，无法保存", Toast.LENGTH_SHORT).show()
        }
    }

    fun startDownload(id: Long) {
        if (Build.VERSION.SDK_INT < 29 &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermDownload = id
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        TaskRepository.download(id) { onResult(it) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("转换任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("多任务并发 · 真实进度 · 转换完成的音频可直接播放 · 下载到「下载/MediaForge」",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = 60.dp)) {
                Text("还没有任务。去「转换」页选择视频或音频开始转换吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(tasks, key = { it.id }) { t ->
                    TaskCard(
                        t = t,
                        videoPlayerVisible = showPlayerFor == t.id,
                        onToggleVideo = {
                            showPlayerFor = if (showPlayerFor == t.id) null else t.id
                        },
                        onDownload = { startDownload(t.id) },
                        onRename = { renameFor = t },
                        onTrim = { trimFor = t },
                        onCancel = { TaskRepository.cancel(t.id) },
                        onDelete = { confirmDelete = t }
                    )
                }
            }
        }
    }

    // 重命名对话框
    renameFor?.let { t ->
        var name by remember(t.id) {
            mutableStateOf(t.downloadedName ?: t.output?.name ?: t.sourceName)
        }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已下载的文件会在系统文件管理器中即时生效；未下载的将重命名转换结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TaskRepository.rename(t.id, name) { msg -> onResult(msg) }
                    renameFor = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } }
        )
    }

    // 裁剪对话框（可视化时间轴 + 试听，所见即所听）
    trimFor?.let { t ->
        // durSec 先用任务记录的 durationMs 初始化；编辑器 prepare 后会用
        // 输出文件的实际时长回调修正（兼容旧版本记录的裁剪前源时长）
        var durSec by remember(t.id) { mutableStateOf((t.durationMs / 1000f).coerceAtLeast(0f)) }
        var range by remember(t.id) {
            mutableStateOf(if (durSec > 0f) 0f..durSec else 0f..1f)
        }
        var startText by remember(t.id) { mutableStateOf("") }
        var endText by remember(t.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { trimFor = null },
            title = { Text("裁剪音频") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (durSec > 0f) {
                        Text("拖动滑块选择保留区间 → 点「试听」确认起止点是否满意 → 满意后开始裁剪：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TrimEditor(
                            durationMs = t.durationMs,
                            mediaPath = t.output?.absolutePath,
                            range = range,
                            onRangeChange = { r ->
                                range = r
                                startText = String.format(java.util.Locale.US, "%.1f", r.start)
                                endText = String.format(java.util.Locale.US, "%.1f", r.endInclusive)
                            },
                            onActualDurationMs = { d ->
                                // 实际文件时长与记录不一致时：修正显示总时长并钳制已选区间
                                val ds = d / 1000f
                                if (ds > 0f && kotlin.math.abs(ds - durSec) > 0.05f) {
                                    durSec = ds
                                    val ns = range.start.coerceIn(0f, ds)
                                    val ne = range.endInclusive.coerceIn(minOf(ns + 0.1f, ds), ds)
                                    range = ns..ne
                                }
                            }
                        )
                        Text("完整时长：${fmtMs((durSec * 1000).toLong())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        OutlinedTextField(value = startText, onValueChange = { startText = it },
                            label = { Text("开始（秒 / mm:ss）") }, singleLine = true)
                        OutlinedTextField(value = endText, onValueChange = { endText = it },
                            label = { Text("结束（秒 / mm:ss）") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = if (durSec > 0f) range.start.toDouble() else parseTimeSec(startText)
                    val e = if (durSec > 0f) range.endInclusive.toDouble() else parseTimeSec(endText)
                    if (s == null || e == null || e <= s) {
                        Toast.makeText(context, "时间无效：结束必须大于开始", Toast.LENGTH_SHORT).show()
                    } else if (durSec > 0f && (s >= durSec.toDouble())) {
                        Toast.makeText(context, "开始时间超出文件实际时长（${fmtMs(durSec.toLong() * 1000L)}），请重新选择", Toast.LENGTH_SHORT).show()
                    } else {
                        val out = t.output
                        if (out != null) {
                            TaskRepository.startTrim(out, s, e)
                            Toast.makeText(context, "裁剪任务已创建", Toast.LENGTH_SHORT).show()
                        }
                    }
                    trimFor = null
                }) { Text("开始裁剪") }
            },
            dismissButton = { TextButton(onClick = { trimFor = null }) { Text("取消") } }
        )
    }

    // 删除确认（可选同时删除已下载副本）
    confirmDelete?.let { t ->
        var alsoDeleteDownloaded by remember(t.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将删除任务记录与转换结果文件。")
                    // 勾选项始终显示：勾选后连同本地文件（含已下载副本）一起删除
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = alsoDeleteDownloaded, onCheckedChange = { alsoDeleteDownloaded = it })
                        Text(
                            if (t.downloadedUri != null) "同时删除本地文件（含已下载到系统文件夹的副本）"
                            else "同时删除本地文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    TaskRepository.delete(t.id, alsoDeleteDownloaded)
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

private fun parseTimeSec(s: String): Double? {
    val t = s.trim()
    if (t.isEmpty()) return null
    return try {
        if (t.contains(':')) {
            var sec = 0.0
            for (p in t.split(':')) sec = sec * 60 + p.toDouble()
            sec
        } else t.toDouble()
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    t: ConvertTask,
    videoPlayerVisible: Boolean,
    onToggleVideo: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onTrim: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(t.typeName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${t.fromExt.uppercase()} → ${t.toExt.uppercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                StatusText(t.status, t.progress, t.phase)
            }
            Text(t.sourceName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fmtTaskTime(t.createdAt).isNotEmpty()) {
                Text("创建时间：${fmtTaskTime(t.createdAt)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(t.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.sourceReal.isNotEmpty()) {
                Text("源真实格式：${t.sourceReal}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (t.status == TaskStatus.IMPORTING || t.status == TaskStatus.RUNNING) {
                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                Text("${t.phase} ${(t.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.status == TaskStatus.COMPLETED) {
                // 图片类任务（格式转换 / 去水印）完成后直接展示结果预览图，
                // 用户先看到效果再决定要不要下载
                if (t.kind == TaskKind.IMAGE && t.output != null) {
                    ResultImageThumb(path = t.output!!.absolutePath)
                }
                Text(t.outputInfo, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
                if (t.downloadedName != null) {
                    Text("已下载：${t.downloadedName}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (t.status == TaskStatus.FAILED && t.error != null) {
                Text("⚠ ${t.error}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 5)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.status == TaskStatus.COMPLETED && t.output != null) {
                    // 只有视频转换才需要「播放」展开按钮；音频的播放条常显在下方；图片没有播放
                    if (t.kind == TaskKind.VIDEO) {
                        AssistChip(onClick = onToggleVideo,
                            label = { Text(if (videoPlayerVisible) "收起播放" else "播放") })
                    }
                    AssistChip(onClick = onDownload, label = { Text("下载") })
                    AssistChip(onClick = onRename, label = { Text("重命名") })
                    if (t.kind == TaskKind.MEDIA || t.kind == TaskKind.TRIM) {
                        AssistChip(onClick = onTrim, label = { Text("裁剪") })
                    }
                }
                if (t.status == TaskStatus.RUNNING || t.status == TaskStatus.IMPORTING) {
                    AssistChip(onClick = onCancel, label = { Text("取消") })
                }
                AssistChip(onClick = onDelete, label = { Text("删除") })
            }

            // 音频转换结果：播放条常显，点左侧播放键直接播放
            if (t.status == TaskStatus.COMPLETED && t.output != null &&
                (t.kind == TaskKind.MEDIA || t.kind == TaskKind.TRIM)
            ) {
                AudioPlayer(mediaPath = t.output.absolutePath)
            }
            // 视频转换结果：点击「播放」展开并自动播放
            if (videoPlayerVisible && t.status == TaskStatus.COMPLETED &&
                t.kind == TaskKind.VIDEO && t.output != null
            ) {
                VideoPreview(Uri.fromFile(t.output), Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 图片任务结果预览图：后台降采样解码（不卡列表、不吃内存），
 * 固定高度容器内等比展示，极端比例的图片也不会撑爆布局。
 */
@Composable
private fun ResultImageThumb(path: String) {
    var bmp by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        val b = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) return@withContext null
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 480) sample *= 2
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }
        bmp = b
    }
    val b = bmp
    if (b != null) {
        Box(
            Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "转换结果预览图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            )
        }
        Text(
            "转换结果预览（满意请点下方「下载」保存）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
