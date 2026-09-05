package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Violet = Color(0xFF8B5CF6)
private val Ink = Color(0xFF16121F)
private val Paper = Color(0xFFF1EEF8)
private val Muted = Color(0xFF948CAB)

/**
 * Playback, inside this window.
 *
 * mpv draws into a surface we own rather than opening beside us, and the
 * transport controls below are ours. They sit under the video rather than over
 * it because the surface is a native one: Compose cannot draw on top of it at
 * any price, which is the trade this approach makes. Overlaid controls would
 * mean decoding frames ourselves and painting them, which is a different and
 * far larger job.
 */
@Composable
fun PlayerScreen(
    target: Watch.Target,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onState: (Watch.State) -> Unit
) {
    val handle = remember(target.url) { VideoSurface.makeCanvas() }
    var session by remember(target.url) { mutableStateOf<Mpv.Session?>(null) }
    var paused by remember(target.url) { mutableStateOf(false) }
    var positionMs by remember(target.url) { mutableStateOf(0L) }
    var durationMs by remember(target.url) { mutableStateOf(0L) }
    var scrubbing by remember(target.url) { mutableStateOf<Float?>(null) }
    var failure by remember(target.url) { mutableStateOf<String?>(null) }

    // Started once the canvas is on screen: the native handle does not exist
    // before it is displayable, and mpv is given that handle at launch.
    LaunchedEffect(target.url) {
        var wid: Long? = null
        repeat(50) {
            wid = handle.wid()
            if (wid != null) return@repeat
            delay(50)
        }
        if (wid == null) {
            BuildInfo.log("DebritsuMpv", "no window handle; falling back to mpv's own window")
        }

        val started = Watch.start(target, wid)
        if (started == null) {
            failure = "mpv would not start, or its pipe never appeared."
            onState(Watch.State.Failed(failure!!))
            return@LaunchedEffect
        }
        session = started
        onState(Watch.State.Playing(target.title))

        // Attached once mpv is up, so the keys have something to act on.
        VideoSurface.onKeys(
            handle,
            onEscape = { if (fullscreen) onFullscreen(false) },
            onSpace = {
                val next = !paused
                started.setPaused(next)
                paused = next
            },
            onSeek = { started.seekBy(it) }
        )

        // The progress rule runs in its own coroutine so the controls below
        // stay responsive while it polls.
        launch { Watch.follow(started, target, onState) }

        while (started.alive) {
            delay(500)
            if (scrubbing == null) {
                positionMs = started.positionMs() ?: positionMs
            }
            if (durationMs <= 0L) durationMs = started.durationMs() ?: 0L
            paused = started.paused() ?: paused
        }
    }

    // Leaving the screen stops playback. Without this mpv keeps running with no
    // window to draw into, which is a process nobody can see or stop.
    DisposableEffect(target.url) {
        onDispose {
            val s = session
            if (s != null) {
                // The position is saved on the way out as well as every five
                // seconds, so closing mid-episode keeps the exact spot rather
                // than the last multiple of five.
                runCatching {
                    val pos = s.positionMs()
                    val dur = s.durationMs()
                    if (pos != null && dur != null && dur > 0) {
                        Progress.save(target.anilistId, target.episode, pos, dur)
                    }
                }
                s.stop()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VideoPanel(handle, Modifier.fillMaxSize())
        }

        // Kept in fullscreen rather than hidden. mpv receives no input when it
        // draws into somebody else's window, so its own controller can never
        // appear and this strip is the only transport there is — hiding it
        // left fullscreen with no controls and no way back out.
        //
        // So fullscreen here means the window filling the screen with the video
        // taking all of it bar this strip, rather than the video covering
        // everything. That is the honest limit of embedding a native surface.
        Column(
            Modifier.fillMaxWidth().background(Ink).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            failure?.let {
                Text(it, color = Color(0xFFE29075), style = MaterialTheme.typography.bodySmall)
            }

            val shown = scrubbing?.let { (it * durationMs).toLong() } ?: positionMs
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clock(shown),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = scrubbing ?: fraction(positionMs, durationMs),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = {
                        val to = ((scrubbing ?: 0f) * durationMs).toLong()
                        session?.seekTo(to)
                        positionMs = to
                        scrubbing = null
                    },
                    enabled = durationMs > 0,
                    colors = SliderDefaults.colors(
                        thumbColor = Violet,
                        activeTrackColor = Violet,
                        inactiveTrackColor = Color(0x552A2140)
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    clock(durationMs),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp).width(56.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onBack() }) { Text("← Back", color = Muted) }

                TextButton(onClick = { session?.seekBy(-10) }) { Text("−10s", color = Paper) }
                TextButton(onClick = {
                    val s = session ?: return@TextButton
                    val next = !paused
                    s.setPaused(next)
                    paused = next
                }) { Text(if (paused) "Play" else "Pause", color = Paper) }
                TextButton(onClick = { session?.seekBy(30) }) { Text("+30s", color = Paper) }

                Box(Modifier.weight(1f))

                Text(
                    "${target.title} · ep ${target.episode}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )

                Box(Modifier.width(12.dp))

                // mpv cycles through every track it loaded, subtitles off
                // included. Naming which one is now selected would mean reading
                // the track list back on each press; the picture says it faster.
                TextButton(onClick = { session?.cycleSubtitles() }) {
                    Text("Subtitles", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { session?.cycleAudio() }) {
                    Text("Audio", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onFullscreen(!fullscreen) }) {
                    Text(
                        if (fullscreen) "Exit fullscreen" else "Fullscreen",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onSources) {
                    Text("Sources", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun fraction(position: Long, duration: Long): Float =
    if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

/** h:mm:ss, or m:ss for anything under an hour — most episodes. */
private fun clock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
