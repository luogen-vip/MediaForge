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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.core.FileSniffer
import com.logan.mediaforge.core.ImageEngine
import com.logan.mediaforge.core.TaskRepository
import com.logan.mediaforge.core.fmtBytes
import com.logan.mediaforge.ui.ImageState
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
            val bmp = withContext(Dispatchers.IO) { ImageEngine.decodePreview(context, u) }
            st.uri.value = u
            st.name.value = n
            st.size.value = FileSniffer.querySize(resolver, u)
            st.det.value = d
            st.preview.value = bmp
            st.origW.value = wh?.first ?: 0
            st.origH.value = wh?.second ?: 0
            st.wText.value = wh?.first?.toString() ?: ""
            st.hText.value = wh?.second?.toString() ?: ""
            if (wh != null && wh.first > 0 && wh.second > 0) {
                st.lastAspect.value = wh.first.toFloat() / wh.second.toFloat()
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) onPicked(u)
    }

    val targetW = st.wText.value.toIntOrNull() ?: 0
    val targetH = st.hText.value.toIntOrNull() ?: 0
    // 宽高均有效时用目标比例；输入过程中（清空/半输入）沿用最近有效比例，避免画幅跳动
    val aspect = if (targetW > 0 && targetH > 0) targetW.toFloat() / targetH.toFloat() else st.lastAspect.value

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("图片格式转换", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("像素级真实转换（非改后缀），可同时调整分辨率，实时预览。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (st.uri.value == null) {
            SectionCard("选择图片", "支持 JPG/PNG/WebP/BMP/TIFF/HEIC/GIF 等常见图片") {
                Button(onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择图片")
                }
            }
        } else {
            // 实时预览：容器比例随目标分辨率变化
            SectionCard("实时预览", "下方预览的画幅比例 = 你设置的目标分辨率") {
                val bmp = st.preview.value
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "预览",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
                    )
                }
                if (st.det.value?.mismatch == true) {
                    Text("⚠ 后缀被改过：声称 .${st.det.value?.declaredExt}，真实格式是 ${st.det.value?.realName}",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                if (st.det.value?.corrupted != null) {
                    Text("⚠ ${st.det.value?.corrupted}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                InfoRow("文件名", st.name.value)
                InfoRow("真实格式", st.det.value?.realName ?: "未知")
                InfoRow("原始分辨率", if (st.origW.value > 0) "${st.origW.value}×${st.origH.value}" else "未知")
                InfoRow("文件大小", if (st.size.value > 0) fmtBytes(st.size.value) else "未知")
                OutlinedButton(onClick = { st.resetSource() }) {
                    Text("移除 / 重新选择")
                }
            }

            SectionCard("目标分辨率", "修改后下方预览实时变化；留空或 0 表示保持原始分辨率") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = st.wText.value,
                        onValueChange = {
                            st.wText.value = it.filter { c -> c.isDigit() }
                            if (st.keepRatio.value && st.origW.value > 0) {
                                val w = st.wText.value.toIntOrNull()
                                if (w != null) st.hText.value = (w * st.origH.value / st.origW.value).toString()
                            }
                        },
                        label = { Text("宽") }, singleLine = true, modifier = Modifier.weight(1f))
                    Text("×", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = st.hText.value,
                        onValueChange = {
                            st.hText.value = it.filter { c -> c.isDigit() }
                            if (st.keepRatio.value && st.origH.value > 0) {
                                val h = st.hText.value.toIntOrNull()
                                if (h != null) st.wText.value = (h * st.origW.value / st.origH.value).toString()
                            }
                        },
                        label = { Text("高") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("保持宽高比", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = st.keepRatio.value, onCheckedChange = { st.keepRatio.value = it })
                }
                OutlinedButton(onClick = {
                    st.wText.value = st.origW.value.toString()
                    st.hText.value = st.origH.value.toString()
                }) { Text("重置为原始分辨率") }
            }

            SectionCard("目标格式与质量") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IMAGE_TARGETS.forEach { e ->
                        FilterChip(
                            selected = st.extSel.value == e,
                            onClick = { st.extSel.value = e },
                            label = { Text(e.uppercase()) }
                        )
                    }
                }
                if (st.extSel.value == "jpg" || st.extSel.value == "webp") {
                    Text("质量：${st.quality.value.toInt()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = st.quality.value, onValueChange = { st.quality.value = it }, valueRange = 30f..100f)
                }
            }

            Button(
                onClick = {
                    val u = st.uri.value ?: return@Button
                    TaskRepository.startImage(u, st.name.value, st.extSel.value, targetW, targetH, st.quality.value.toInt())
                    Toast.makeText(context, "已加入任务，可在「任务」页查看与下载", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始转换（加入任务）") }
        }
    }
}
