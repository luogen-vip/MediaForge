package com.logan.mediaforge.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 视频预览：播放器 + 自绘控制条（播放/暂停 + 进度 + 时间）。
 * 旧版用系统 MediaController，竖屏（9:16）视频时上一曲/下一曲按钮被遮住只露一点；
 * 新版控制条固定在视频下方，与画面比例无关，永远完整可见；
 * 画面按真实宽高比在固定高度容器内等比缩放，不拉伸不变形。
 * 播放结束自动复位为播放按钮（与音频播放器一致）。
 */
@Composable
fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var aspect by remember(uri) { mutableFloatStateOf(16f / 9f) } // 视频宽/高，onPrepared 后更新
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var pos by remember(uri) { mutableIntStateOf(0) }
    var dur by remember(uri) { mutableIntStateOf(0) }
    var failed by remember(uri) { mutableStateOf(false) }
    val vv = remember(uri) {
        VideoView(context).apply {
            setVideoURI(uri)
            setOnPreparedListener { mp ->
                dur = try { mp.duration } catch (_: Exception) { 0 }
                if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                    aspect = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
                }
                prepared = true
                start()
                playing = true
            }
            setOnCompletionListener {
                // 播放结束：按钮复位为播放，进度停在结尾
                playing = false
                pos = dur
            }
            setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
        }
    }

    Column(modifier) {
        // ---- 画面：固定高度容器内等比缩放，任何比例都不变形、不撑爆布局 ----
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center
        ) {
            val a = aspect.coerceIn(0.2f, 5f)
            var w = maxWidth
            var h = w / a
            if (h > maxHeight) { h = maxHeight; w = h * a }
            AndroidView(
                factory = { vv },
                modifier = Modifier.size(w, h).clip(RoundedCornerShape(10.dp))
            )
        }

        if (failed) {
            Text(
                "该视频无法预览播放（编码可能不受预览支持），不影响转换，可直接转换后播放结果",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            // ---- 控制条：在视频下方，任何画面比例都完整可见 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (!prepared) return@IconButton
                    if (playing) {
                        try { vv.pause() } catch (_: Exception) { }
                        playing = false
                    } else {
                        try {
                            if (dur > 0 && pos >= dur - 200) vv.seekTo(0)
                            vv.start()
                            playing = true
                        } catch (_: Exception) { }
                    }
                }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "播放/暂停",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = if (dur > 0) pos.toFloat() else 0f,
                    onValueChange = {
                        pos = it.toInt()
                        try { vv?.seekTo(it.toInt()) } catch (_: Exception) { }
                    },
                    valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${fmtMs(pos.toLong())} / ${fmtMs(dur.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 播放中轮询更新进度
    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            try { pos = vv.currentPosition } catch (_: Exception) { }
            delay(300)
        }
    }
    DisposableEffect(uri) {
        onDispose {
            try { vv.stopPlayback() } catch (_: Exception) { }
        }
    }
}
