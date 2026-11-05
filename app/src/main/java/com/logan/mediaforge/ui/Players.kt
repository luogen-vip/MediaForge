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
import androidx.compose.runtime.mutableLongStateOf
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** 音频播放条：支持本地文件路径或 content Uri，真实播放；autoPlay=true 时准备好后自动开始。
 *  系统播放器不支持的格式（AC3/WMA/AIFF/APE 等）自动用 FFmpeg 离线解码成 WAV 后预览。 */
@Composable
fun AudioPlayer(mediaPath: String? = null, mediaUri: Uri? = null, autoPlay: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var pos by remember(mediaPath, mediaUri) { mutableIntStateOf(0) }
    var dur by remember(mediaPath, mediaUri) { mutableIntStateOf(0) }
    var ready by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var failed by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var decoding by remember(mediaPath, mediaUri) { mutableStateOf(false) }  // FFmpeg 解码预览中
    // 拖动进度条期间暂停播放与轮询，松手后一次性 seek（拖动期间不再出声，也消除音频抖动）
    var dragging by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    var resumeOnDragEnd by remember(mediaPath, mediaUri) { mutableStateOf(false) }
    // seek 异步生效期间的“挂起目标位”：播放器没追上目标前进度条保持目标位，彻底消除前后抖动
    var pendingSeek by remember(mediaPath, mediaUri) { mutableIntStateOf(-1) }
    var seekAt by remember(mediaPath, mediaUri) { mutableLongStateOf(0L) }
    val player = remember(mediaPath, mediaUri) {
        MediaPlayer().apply {
            // 关键修复：播放自然结束后，按钮要从「暂停(两条杠)」复位为「播放(三角)」，
            // 进度条停在结尾；再次点播放会从头开始
            setOnCompletionListener {
                playing = false
                pos = dur
            }
            // seek 真正完成后立即落到播放器实际位置（关键帧），避免拖住目标位再突跳
            setOnSeekCompleteListener { mp ->
                val ps = pendingSeek
                if (ps >= 0) {
                    pendingSeek = -1
                    pos = try { mp.currentPosition } catch (_: Exception) { ps }
                }
            }
        }
    }

    LaunchedEffect(mediaPath, mediaUri) {
        val path = mediaPath
        if (mediaUri != null) {
            try {
                player.setDataSource(context, mediaUri)
                player.prepare()
                dur = player.duration
                ready = true
            } catch (e: Exception) {
                failed = true
            }
        } else if (path != null) {
            var ok = try {
                player.setDataSource(path)
                player.prepare()
                true
            } catch (e: Exception) {
                try { player.reset() } catch (_: Exception) { }
                false
            }
            // 系统播放器不支持（AC3/WMA/AIFF 等编码）→ FFmpeg 解码成 WAV 再预览，不额外损失音质
            if (!ok) {
                decoding = true
                try {
                    val wav = File(context.cacheDir, "mf_preview_" + kotlin.math.abs(path.hashCode().toLong()) + ".wav")
                    val session = withContext(Dispatchers.IO) {
                        FFmpegKit.executeWithArguments(
                            arrayOf("-y", "-i", path, "-vn", "-c:a", "pcm_s16le", "-f", "wav", wav.absolutePath)
                        )
                    }
                    if (ReturnCode.isSuccess(session.returnCode) && wav.exists() && wav.length() > 0) {
                        ok = try {
                            player.reset()
                            player.setDataSource(wav.absolutePath)
                            player.prepare()
                            true
                        } catch (e: Exception) { false }
                    }
                } catch (_: Exception) { }
                decoding = false
            }
            if (ok) {
                dur = player.duration
                ready = true
            } else {
                failed = true
            }
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
            if (!dragging) {
                val ps = pendingSeek
                if (ps >= 0) {
                    val cur = try { player.currentPosition } catch (_: Exception) { ps }
                    // 播放器已报告到目标附近（或超时兜底）才恢复跟随，期间显示保持目标位不回跳
                    if (abs(cur - ps) <= 700 || System.currentTimeMillis() - seekAt > 2500) {
                        pendingSeek = -1
                        pos = cur
                    } else {
                        pos = ps
                    }
                } else {
                    try { pos = player.currentPosition } catch (_: Exception) { }
                }
            }
            delay(200)
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
            Text("该格式系统播放器不支持且预览解码失败（不影响已转换的文件本身，可下载后用 VLC 等播放器播放）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f))
        } else if (decoding) {
            Text("正在解码预览（该格式系统播放器不支持，正在用内置引擎离线转换）…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f))
        } else {
            Text(fmtMs(pos.toLong()), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = if (dur > 0) pos.toFloat() else 0f,
                onValueChange = {
                    if (!dragging) {
                        dragging = true
                        resumeOnDragEnd = playing
                        if (playing) try { player.pause() } catch (_: Exception) { }
                    }
                    pos = it.toInt()
                },
                onValueChangeFinished = {
                    try { player.seekTo(pos) } catch (_: Exception) { }
                    pendingSeek = pos
                    seekAt = System.currentTimeMillis()
                    if (resumeOnDragEnd) {
                        try { player.start() } catch (_: Exception) { }
                        resumeOnDragEnd = false
                    }
                    dragging = false
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var aspect by remember(uri) { mutableFloatStateOf(16f / 9f) } // 视频宽/高，onPrepared 后更新
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var pos by remember(uri) { mutableIntStateOf(0) }
    var dur by remember(uri) { mutableIntStateOf(0) }
    var failed by remember(uri) { mutableStateOf(false) }
    var remuxing by remember(uri) { mutableStateOf(false) }      // 重封装预览中
    var remuxTried by remember(uri) { mutableStateOf(false) }
    var remuxPath by remember(uri) { mutableStateOf<String?>(null) }
    // 拖动进度条期间暂停播放与轮询，松手后一次性 seek（拖动期间画面不再连播，也不出声）
    var dragging by remember(uri) { mutableStateOf(false) }
    var resumeOnDragEnd by remember(uri) { mutableStateOf(false) }
    // seek 异步生效期间的“挂起目标位”：播放器没追上目标前进度条保持目标位，彻底消除前后抖动
    var pendingSeek by remember(uri) { mutableIntStateOf(-1) }
    var seekAt by remember(uri) { mutableLongStateOf(0L) }

    // 系统播放器报错时：先尝试 FFmpeg 无重编码重封装（换封装不换画质，秒级完成），
    // 仍不行才提示不支持（不影响已转换的文件本身）
    fun tryRemux() {
        if (remuxTried) { failed = true; return }
        remuxTried = true
        remuxing = true
        scope.launch(Dispatchers.IO) {
            var ok = false
            var out: File? = null
            try {
                val input = if (uri.scheme == "file") uri.path
                    else FFmpegKitConfig.getSafParameterForRead(context, uri)
                if (input != null) {
                    val f = File(context.cacheDir, "mf_vremux_" + kotlin.math.abs(uri.toString().hashCode().toLong()) + ".mp4")
                    f.delete()
                    val s = FFmpegKit.executeWithArguments(
                        arrayOf("-y", "-i", input, "-c", "copy", "-movflags", "+faststart", f.absolutePath)
                    )
                    ok = ReturnCode.isSuccess(s.returnCode) && f.exists() && f.length() > 0
                    if (ok) out = f
                }
            } catch (_: Exception) { }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                remuxing = false
                if (ok && out != null) remuxPath = out!!.absolutePath else failed = true
            }
        }
    }

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
            setOnSeekCompleteListener { mp ->
                val ps = pendingSeek
                if (ps >= 0) {
                    pendingSeek = -1
                    pos = try { mp.currentPosition } catch (_: Exception) { ps }
                }
            }
            setOnErrorListener { _, _, _ ->
                tryRemux()
                true
            }
        }
    }

    // 重封装完成后切换到重封装后的文件播放
    LaunchedEffect(remuxPath) {
        val p = remuxPath ?: return@LaunchedEffect
        try {
            vv.setVideoPath(p)
            vv.start()
            playing = true
        } catch (_: Exception) {
            failed = true
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
                "该视频无法预览播放（编码可能不受系统播放器支持，重封装后仍不支持），不影响转换，可下载后用 VLC 等播放器播放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (remuxing) {
            Text(
                "正在重封装预览（换封装不换画质，很快）…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
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
                        if (!dragging) {
                            dragging = true
                            resumeOnDragEnd = playing
                            if (playing) try { vv.pause() } catch (_: Exception) { }
                        }
                        pos = it.toInt()
                    },
                    onValueChangeFinished = {
                        try { vv?.seekTo(pos) } catch (_: Exception) { }
                        pendingSeek = pos
                        seekAt = System.currentTimeMillis()
                        if (resumeOnDragEnd) {
                            try { vv.start() } catch (_: Exception) { }
                            resumeOnDragEnd = false
                        }
                        dragging = false
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
            if (!dragging) {
                val ps = pendingSeek
                if (ps >= 0) {
                    val cur = try { vv.currentPosition } catch (_: Exception) { ps }
                    if (abs(cur - ps) <= 700 || System.currentTimeMillis() - seekAt > 2500) {
                        pendingSeek = -1
                        pos = cur
                    } else {
                        pos = ps
                    }
                } else {
                    try { pos = vv.currentPosition } catch (_: Exception) { }
                }
            }
            delay(200)
        }
    }
    DisposableEffect(uri) {
        onDispose {
            try { vv.stopPlayback() } catch (_: Exception) { }
        }
    }
}
