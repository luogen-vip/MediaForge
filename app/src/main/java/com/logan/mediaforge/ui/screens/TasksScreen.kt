package com.logan.mediaforge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.logan.mediaforge.ui.VideoPreview
import java.io.File

private fun statusLabel(s: TaskStatus): String = when (s) {
    TaskStatus.IMPORTING -> "导入中"
    TaskStatus.RUNNING -> "进行中"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "已取消"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val tasks by TaskRepository.tasks.collectAsState()

    var showPlayerFor by remember { mutableStateOf<Long?>(null) }
    var renameFor by remember { mutableStateOf<ConvertTask?>(null) }
    var trimFor by remember { mutableStateOf<ConvertTask?>(null) }
    var confirmDelete by remember { mutableStateOf<ConvertTask?>(null) }
    var pendingPermDownload by remember { mutableStateOf<Long?>(null) }

    fun onDownloadResult(id: Long, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingPermDownload
        pendingPermDownload = null
        if (granted && id != null) {
            TaskRepository.download(id) { onDownloadResult(id, it) }
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
        TaskRepository.download(id) { onDownloadResult(id, it) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("转换任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("多任务并发 · 真实进度 · 点击播放预览 · 下载到「下载/MediaForge」",
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
                        playerVisible = showPlayerFor == t.id,
                        onTogglePlay = {
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
                    TaskRepository.rename(t.id, name) { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    renameFor = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } }
        )
    }

    // 裁剪对话框
    trimFor?.let { t ->
        var startText by remember(t.id) { mutableStateOf("") }
        var endText by remember(t.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { trimFor = null },
            title = { Text("裁剪音频") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("输入开始/结束时间（秒数如 12.5，或 mm:ss 如 1:05），将生成新的裁剪任务，不影响原文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = startText, onValueChange = { startText = it },
                        label = { Text("开始（秒 / mm:ss）") }, singleLine = true)
                    OutlinedTextField(value = endText, onValueChange = { endText = it },
                        label = { Text("结束（秒 / mm:ss）") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = parseTimeSec(startText)
                    val e = parseTimeSec(endText)
                    if (s == null || e == null || e <= s) {
                        Toast.makeText(context, "时间无效：结束必须大于开始", Toast.LENGTH_SHORT).show()
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

    // 删除确认
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除任务") },
            text = { Text("将删除任务记录与转换结果文件（已下载到系统文件夹的副本不受影响）。") },
            confirmButton = {
                TextButton(onClick = { TaskRepository.delete(t.id); confirmDelete = null }) { Text("删除") }
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
    playerVisible: Boolean,
    onTogglePlay: () -> Unit,
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
                    AssistChip(onClick = onTogglePlay, label = { Text(if (playerVisible) "收起播放" else "播放") })
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

            if (playerVisible && t.status == TaskStatus.COMPLETED && t.output != null) {
                if (t.kind == TaskKind.VIDEO) {
                    VideoPreview(Uri.fromFile(t.output), Modifier.fillMaxWidth().height(200.dp))
                } else {
                    AudioPlayer(mediaPath = t.output.absolutePath)
                }
            }
        }
    }
}
