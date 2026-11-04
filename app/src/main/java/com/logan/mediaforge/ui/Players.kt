package com.logan.mediaforge.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.logan.mediaforge.core.fmtMs
import kotlinx.coroutines.delay

/** 音频播放条：支持本地文件路径或 content Uri，真实播放；autoPlay=true 时准备好后自动开始 */
@Composable
fun AudioPlayer(mediaPath: String? = null, mediaUri: Uri? = null, autoPlay: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var pos by remember(mediaPath, mediaUri) { mutableIntStateOf(0) }
    var dur by remember(mediaPath, mediaUri) { mutableIntStateOf(0) }
    var ready by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var failed by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    val player = remember(mediaPath, mediaUri) {
        MediaPlayer().apply {
            // 关键修复：播放自然结束后，按钮要从「暂停(两条杠)」复位为「播放(三角)」，
            // 进度条停在结尾；再次点播放会从头开始
            setOnCompletionListener {
                playing = false
                pos = dur
            }
        }
    }

    LaunchedEffect(mediaPath, mediaUri) {
        try {
            if (mediaUri != null) player.setDataSource(context, mediaUri)
            else if (mediaPath != null) player.setDataSource(mediaPath)
            else return@LaunchedEffect
            player.prepare()
            dur = player.duration
            ready = true
        } catch (e: Exception) {
            ready = false
            failed = true
        }
    }
    DisposableEffect(mediaPath, mediaUri) {
        onDispose { try { player.release() } catch (_: Exception) { } }
    }
    LaunchedEffect(ready, autoPlay) {
        if (ready && autoPlay) {
            try {
                player.start()
                playing = true
            } catch (_: Exception) { }
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            try { pos = player.currentPosition } catch (_: Exception) { }
            delay(300)
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = {
            if (!ready) return@IconButton
            if (playing) {
                try { player.pause() } catch (_: Exception) { }
                playing = false
            } else {
                try {
                    if (dur > 0 && pos >= dur - 200) player.seekTo(0)
                    player.start()
                    playing = true
                } catch (_: Exception) { }
            }
        }) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (failed) {
            Text("该文件无法播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f))
        } else {
            Text(fmtMs(pos.toLong()), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = if (dur > 0) pos.toFloat() else 0f,
                onValueChange = {
                    pos = it.toInt()
                    try { player.seekTo(it.toInt()) } catch (_: Exception) { }
                },
                valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
                modifier = Modifier.weight(1f)
            )
            Text(fmtMs(dur.toLong()), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 视频预览（VideoView + 系统控制器） */
@Composable
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val vv = VideoView(ctx)
            val mc = MediaController(ctx)
            mc.setAnchorView(vv)
            vv.setMediaController(mc)
            vv.setVideoURI(uri)
            vv.start()
            vv
        }
    )
}
