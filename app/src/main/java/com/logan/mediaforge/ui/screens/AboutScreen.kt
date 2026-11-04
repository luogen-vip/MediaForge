package com.logan.mediaforge.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logan.mediaforge.BuildConfig
import com.logan.mediaforge.core.SettingsRepo
import com.logan.mediaforge.ui.SectionCard

private const val REPO_URL = "https://github.com/luogen-vip/MediaForge"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var folderVersion by remember { mutableIntStateOf(0) }

    fun pickFolder(key: String): androidx.activity.compose.ManagedActivityResultLauncher<Uri?, Uri> =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    SettingsRepo.setFolder(context, key, uri.toString())
                    Toast.makeText(context, "保存位置已更新", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "设置失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
                folderVersion++
            }
        }

    val pickAudio = pickFolder(SettingsRepo.CAT_AUDIO)
    val pickImage = pickFolder(SettingsRepo.CAT_IMAGE)
    val pickFile = pickFolder(SettingsRepo.CAT_FILE)

    fun folderDisplay(key: String): String {
        folderVersion // 依赖以便刷新
        val v = SettingsRepo.folder(context, key)
        return if (v == null) "默认（下载/MediaForge）"
        else try {
            "自选：${DocumentsContract.getTreeDocumentId(Uri.parse(v))}"
        } catch (e: Exception) {
            "自选文件夹"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard("媒体工坊 MediaForge") {
            Text("版本 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("作者：罗根", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary)
            Text("开源仓库：$REPO_URL", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(REPO_URL))
                    Toast.makeText(context, "仓库地址已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制仓库地址") }
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    } catch (e: Exception) { }
                }) { Text("打开仓库") }
            }
        }

        SectionCard("保存位置设置", "转换结果的默认下载位置；也可为不同类别分别指定文件夹") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("音频（视频/音频转换）", style = MaterialTheme.typography.bodyMedium)
                    Text(folderDisplay(SettingsRepo.CAT_AUDIO), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { pickAudio.launch(null) }) { Text("选择") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("图片（格式转换）", style = MaterialTheme.typography.bodyMedium)
                    Text(folderDisplay(SettingsRepo.CAT_IMAGE), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { pickImage.launch(null) }) { Text("选择") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("其他", style = MaterialTheme.typography.bodyMedium)
                    Text(folderDisplay(SettingsRepo.CAT_FILE), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { pickFile.launch(null) }) { Text("选择") }
            }
            TextButton(onClick = {
                SettingsRepo.setFolder(context, SettingsRepo.CAT_AUDIO, null)
                SettingsRepo.setFolder(context, SettingsRepo.CAT_IMAGE, null)
                SettingsRepo.setFolder(context, SettingsRepo.CAT_FILE, null)
                folderVersion++
                Toast.makeText(context, "已恢复默认：下载/MediaForge", Toast.LENGTH_SHORT).show()
            }) { Text("全部恢复默认下载文件夹") }
        }

        SectionCard("功能说明") {
            Text(
                "• 视频 → 音频：真实 FFmpeg 转码，支持 WAV/MP3/M4A/AAC/FLAC/OGG/OPUS/WMA/AIFF/AMR/AC3/MP2/ALAC\n" +
                "• 音频 → 音频：含同格式改参数（比特率/采样率/声道/位深）\n" +
                "• 视频 → 视频：MP4/MOV/MKV/WEBM/AVI/TS，H.264/H.265/VP9\n" +
                "• 场景预设：发行歌曲审核标准等一键套参数，偏离标准自动提醒\n" +
                "• 任务中心：并发任务、真实进度、播放预览、下载、重命名（即时生效）、裁剪\n" +
                "• 图片：格式转换 + 分辨率调整，实时预览\n" +
                "• 识别：魔数嗅探真实格式，识破改后缀与损坏文件",
                style = MaterialTheme.typography.bodySmall
            )
        }

        SectionCard("开源与技术说明") {
            Text(
                "本应用基于 FFmpeg（ffmpegkit-maintained 社区维护版）实现真实转码，" +
                    "输出文件经过 FFprobe 容器校验，确保格式真实有效，绝非改后缀模拟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "因包含 GPL 编码器（x264/x265），本项目以 GPL-3.0 协议开源。源码：$REPO_URL",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
