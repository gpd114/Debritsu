package com.debritsu.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.Progress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Violet = Color(0xFF8B5CF6)
private val Paper = Color(0xFFF1EEF8)
private val Muted = Color(0xFFC4BCD8)

/**
 * Playback, with the controls over the picture.
 *
 * libVLC decodes into a buffer this paints as an ordinary image, so everything
 * else is ordinary Compose drawn on top. That is the whole reason for the move
 * from mpv: mpv drew into a window it did not own, which meant nothing could be
 * laid over it and every control had to sit beside the video.
 *
 * The controls fade out while the pointer is still and come back when it moves,
 * which is what every other player does and is only possible now.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    target: Watch.Target,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onState: (Watch.State) -> Unit
) {
    val player = remember(target.url) { VlcPlayer(target.vlcDir) }
    var frames by remember(target.url) { mutableStateOf(0L) }
    var paused by remember(target.url) { mutableStateOf(false) }
    var positionMs by remember(target.url) { mutableStateOf(0L) }
    var durationMs by remember(target.url) { mutableStateOf(0L) }
    var scrubbing by remember(target.url) { mutableStateOf<Float?>(null) }
    var controlsShown by remember(target.url) { mutableStateOf(true) }
    var lastMoved by remember(target.url) { mutableStateOf(0L) }

    LaunchedEffect(target.url) {
        Watch.start(player, target)
        onState(Watch.State.Playing(target.title))

        val watched = object : Watch.Playing {
            override val alive: Boolean get() = !player.ended
            override fun positionMs() = player.positionMs().takeIf { it >= 0 }
            override fun durationMs() = player.durationMs().takeIf { it > 0 }
        }
        launch { Watch.follow(watched, target, onState) }

        // Redrawn from the frame counter rather than from the bitmap: the
        // bitmap is reused between frames, so its identity never changes and
        // Compose would have no reason to draw it again.
        var chosenSubtitles = false
        while (!player.ended) {
            delay(16)
            frames = player.frames

            // Once the picture is up, which is when VLC knows its tracks. The
            // mediaPlayerReady event was supposed to do this and never fired,
            // so the track list was never read and nothing was ever chosen
            // beyond the one attached file.
            if (!chosenSubtitles && frames > 0) {
                chosenSubtitles = true
                player.chooseSubtitles()
            }
            if (scrubbing == null) positionMs = player.positionMs()
            if (durationMs <= 0L) durationMs = player.durationMs()
            paused = !player.playing
        }
        onState(Watch.State.Finished(false))
    }

    // Hides itself while nothing moves. Not while paused: a paused picture with
    // no controls looks like a frozen program rather than a deliberate stop.
    LaunchedEffect(lastMoved, paused) {
        if (paused) {
            controlsShown = true
            return@LaunchedEffect
        }
        controlsShown = true
        delay(2500)
        controlsShown = false
    }

    DisposableEffect(target.url) {
        onDispose {
            runCatching {
                val pos = player.positionMs()
                val dur = player.durationMs()
                if (pos > 0 && dur > 0) {
                    // Written on the way out as well as every five seconds, so
                    // closing mid-episode keeps the exact spot.
                    Progress.save(target.anilistId, target.episode, pos, dur)
                }
            }
            player.release()
        }
    }

    val focus = remember(target.url) { FocusRequester() }
    LaunchedEffect(target.url) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { lastMoved = System.currentTimeMillis() }
            .focusRequester(focus)
            .focusable()
            // Preview rather than ordinary key handling: a control button that
            // has been clicked holds focus, and Space would otherwise press it
            // again instead of pausing. Taking the keys before the children see
            // them means the player answers to them wherever focus has landed.
            //
            // Deliberately does not wake the controls. These exist so playback
            // can be driven without anything appearing over the picture; a key
            // that summoned the controls would defeat the point of using it.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.K -> {
                        val next = !paused
                        player.setPaused(next)
                        paused = next
                        true
                    }
                    Key.DirectionLeft -> { player.seekBy(-10); true }
                    Key.DirectionRight -> { player.seekBy(30); true }
                    Key.DirectionUp -> { player.setVolume(player.volume() + 5); true }
                    Key.DirectionDown -> { player.setVolume(player.volume() - 5); true }
                    Key.M -> { player.setMuted(!player.muted()); true }
                    Key.F -> { onFullscreen(!fullscreen); true }
                    Key.Escape -> {
                        if (fullscreen) { onFullscreen(false); true } else false
                    }
                    else -> false
                }
            }
    ) {
        // Read so the frame counter is an input to this composition; without it
        // nothing here depends on it and the picture never updates.
        @Suppress("UNUSED_EXPRESSION") frames

        player.frame()?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Fit, not crop: a 4:3 episode in a 16:9 window should be
                // letterboxed rather than have its sides cut off.
                contentScale = ContentScale.Fit
            )
        } ?: Text(
            "Opening…",
            color = Muted,
            modifier = Modifier.align(Alignment.Center)
        )

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Controls(
                target = target,
                paused = paused,
                positionMs = scrubbing?.let { (it * durationMs).toLong() } ?: positionMs,
                durationMs = durationMs,
                fraction = scrubbing ?: fraction(positionMs, durationMs),
                fullscreen = fullscreen,
                onScrub = { scrubbing = it },
                onScrubbed = {
                    val to = ((scrubbing ?: 0f) * durationMs).toLong()
                    player.seekTo(to)
                    positionMs = to
                    scrubbing = null
                },
                onPlayPause = {
                    val next = !paused
                    player.setPaused(next)
                    paused = next
                },
                onSeek = { player.seekBy(it) },
                onSubtitles = { cycle(player.subtitleTracks(), player.subtitleTrack()) { id -> player.setSubtitleTrack(id) } },
                onAudio = { cycle(player.audioTracks(), player.audioTrack()) { id -> player.setAudioTrack(id) } },
                onFullscreen = { onFullscreen(!fullscreen) },
                onSources = onSources,
                onBack = onBack
            )
        }
    }
}

/** Steps to the next track in a list, wrapping. */
private fun cycle(tracks: List<Pair<Int, String>>, current: Int, set: (Int) -> Unit) {
    if (tracks.isEmpty()) return
    val index = tracks.indexOfFirst { it.first == current }
    val next = tracks[(index + 1).mod(tracks.size)]
    BuildInfo.log("DebritsuVlc", "track -> ${next.second}")
    set(next.first)
}

@Composable
private fun Controls(
    target: Watch.Target,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    fraction: Float,
    fullscreen: Boolean,
    onScrub: (Float) -> Unit,
    onScrubbed: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onFullscreen: () -> Unit,
    onSources: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            // A gradient rather than a panel: white text over a bright scene
            // needs something behind it, and a hard edge across the picture is
            // the thing the phone build spent four releases removing.
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xCC120E1C))
                )
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clock(positionMs), color = Paper, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp))
            Slider(
                value = fraction,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubbed,
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Violet,
                    activeTrackColor = Violet,
                    inactiveTrackColor = Color(0x66FFFFFF)
                ),
                modifier = Modifier.weight(1f)
            )
            Text(clock(durationMs), color = Paper, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp).width(56.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back", color = Muted) }
            TextButton(onClick = { onSeek(-10) }) { Text("−10s", color = Paper) }
            TextButton(onClick = onPlayPause) { Text(if (paused) "Play" else "Pause", color = Paper) }
            TextButton(onClick = { onSeek(30) }) { Text("+30s", color = Paper) }

            Box(Modifier.weight(1f))

            Text(
                "${target.title} · ep ${target.episode}",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Box(Modifier.width(12.dp))

            TextButton(onClick = onSubtitles) {
                Text("Subtitles", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAudio) {
                Text("Audio", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSources) {
                Text("Sources", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onFullscreen) {
                Text(
                    if (fullscreen) "Windowed" else "Fullscreen",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
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
