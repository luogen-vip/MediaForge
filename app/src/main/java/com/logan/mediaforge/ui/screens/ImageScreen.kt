package com.logan.mediaforge.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.ImageEngine
import com.logan.mediaforge.core.Detection
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.ui.InfoRow
import com.logan.mediaforge.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMAGE_TARGETS = listOf("jpg", "png", "webp", "bmp", "tif")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(0L) }
    var det by remember { mutableStateOf<Detection?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var origW by remember { mutableStateOf(0) }
    var origH by remember { mutableStateOf(0) }
    var wText by remember { mutableStateOf("") }
    var hText by remember { mutableStateOf("") }
    var keepRatio by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(92f) }
    var extSel by remember { mutableStateOf("jpg") }

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
            val bmp = withContext(Dispatchers.IO) { ImageEngine.decodePreview(context, u) }
            uri = u
            name = n
            size = FileSniffer.querySize(resolver, u)
            det = d
            preview = bmp
            origW = wh?.first ?: 0
            origH = wh?.second ?: 0
            wText = wh?.first?.toString() ?: ""
            hText = wh?.second?.toString() ?: ""
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPicked(u)
    }

    val targetW = wText.toIntOrNull() ?: 0
    val targetH = hText.toIntOrNull() ?: 0
    val aspect = if (targetW > 0 && targetH > 0) targetW.toFloat() / targetH.toFloat() else 1f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("图片格式转换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("像素级真实转换（非改后缀），可同时调整分辨率，实时预览。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (uri == null) {
            SectionCard("选择图片", "支持 JPG/PNG/WebP/BMP/TIFF/HEIC/GIF 等常见图片") {
                Button(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择图片")
                }
            }
        } else {
            // 实时预览：容器比例随目标分辨率变化
            SectionCard("实时预览", "下方预览的画幅比例 = 你设置的目标分辨率") {
                val bmp = preview
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "预览",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
                    )
                }
                if (det?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${det?.declaredExt}，真实格式是 ${det?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (det?.corrupted != null) {
                    Text("⚠ ${det?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("文件名", name)
                InfoRow("真实格式", det?.realName ?: "未知")
                InfoRow("原始分辨率", if (origW > 0) "${origW}×${origH}" else "未知")
                InfoRow("文件大小", if (size > 0) fmtBytes(size) else "未知")
                OutlinedButton(onClick = { uri = null; det = null; preview = null; name = "" }) {
                    Text("移除 / 重新选择")
                }
            }

            SectionCard("目标分辨率", "修改后下方预览实时变化；留空或 0 表示保持原始分辨率") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = wText,
                        onValueChange = {
                            wText = it.filter { c -> c.isDigit() }
                            if (keepRatio && origW > 0) {
                                val w = wText.toIntOrNull()
                                if (w != null) hText = (w * origH / origW).toString()
                            }
                        },
                        label = { Text("宽") }, singleLine = true, modifier = Modifier.weight(1f))
                    Text("×", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = hText,
                        onValueChange = {
                            hText = it.filter { c -> c.isDigit() }
                            if (keepRatio && origH > 0) {
                                val h = hText.toIntOrNull()
                                if (h != null) wText = (h * origW / origH).toString()
                            }
                        },
                        label = { Text("高") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保持宽高比", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = keepRatio, onCheckedChange = { keepRatio = it })
                }
                OutlinedButton(onClick = {
                    wText = origW.toString()
                    hText = origH.toString()
                }) { Text("重置为原始分辨率") }
            }

            SectionCard("目标格式与质量") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IMAGE_TARGETS.forEach { e ->
                        FilterChip(
                            selected = extSel == e,
                            onClick = { extSel = e },
                            label = { Text(e.uppercase()) }
                        )
                    }
                }
                if (extSel == "jpg" || extSel == "webp") {
                    Text("质量：${quality.toInt()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..100f)
                }
            }

            Button(
                onClick = {
                    val u = uri ?: return@Button
                    TaskRepository.startImage(u, name, extSel, targetW, targetH, quality.toInt())
                    Toast.makeText(context, "已加入任务，可在「任务」页查看与下载", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始转换（加入任务）") }
        }
    }
}
