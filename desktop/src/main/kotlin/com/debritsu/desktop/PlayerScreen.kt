package com.debritsu.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
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
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    /** Registers this screen's key handling with the window. */
    onKeys: (((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?) -> Unit,
    onBack: () -> Unit,
    onState: (Watch.State) -> Unit
) {
    // Read from the player rather than taken as an argument, because switching
    // source has to be seen by both windows and the argument would come from
    // whichever composition happened to be frozen at the time.
    val target = ActivePlayer.playing.value ?: return

    // Was it already playing before the window was rebuilt? Going fullscreen
    // recreates the window, and everything below has to pick up where it was
    // rather than start again.
    val alreadyPlaying = remember(target.url) { ActivePlayer.holds(target.url) }
    val player = remember(target.url) { ActivePlayer.of(target.url, target.vlcDir) }
    var frames by remember(target.url) { mutableStateOf(0L) }
    var paused by remember(target.url) { mutableStateOf(false) }
    var positionMs by remember(target.url) { mutableStateOf(0L) }
    var durationMs by remember(target.url) { mutableStateOf(0L) }
    var scrubbing by remember(target.url) { mutableStateOf<Float?>(null) }
    var controlsShown by remember(target.url) { mutableStateOf(true) }
    var lastMoved by remember(target.url) { mutableStateOf(0L) }

    /** The video's own proportions, which the decoded buffer's are not. */
    var aspect by remember(target.url) { mutableStateOf(16f / 9f) }

    /** Opening and ending times for this episode, when AniSkip knows them. */
    var segments by remember(target.url) {
        mutableStateOf<List<com.debritsu.app.data.AniSkip.Segment>>(emptyList())
    }

    // The one on screen now, if any. Follows the position the frame loop is
    // already reading, so it needs no polling of its own — which is how the
    // phone does it, because it has no such loop to lean on.
    val skippable = segments.firstOrNull { positionMs in it.startMs..it.endMs }

    // The source list, over the picture rather than instead of it.
    //
    // Backing out to the app to change source stopped playback, lost the place
    // and made comparing two releases a matter of memory. Kept per episode
    // rather than per source, so switching and switching back does not go
    // asking the addons twice.
    var sourcesOpen by remember(target.episode) { mutableStateOf(false) }
    var sourceList by remember(target.episode) { mutableStateOf<Watch.State.Choose?>(null) }
    var sourceNote by remember(target.episode) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** What is happening while another episode is being found, if anything. */
    var switching by remember(target.url) { mutableStateOf<String?>(null) }

    /**
     * Which track list is open, if either.
     *
     * These used to cycle to the next track and say nothing at all. That is
     * indistinguishable from a broken button when a release has one audio
     * track and no subtitle tracks — which is most of them, since anime
     * commonly burns the subtitles into the picture. A list says what there is.
     */
    var trackMenu by remember(target.url) { mutableStateOf<String?>(null) }

    // Beyond the last episode there is nothing to go to. A show whose count
    // AniList does not know keeps the button, because refusing to move on a
    // missing number would be worse than trying and finding nothing.
    val lastEpisode = target.anime?.episodes ?: 0
    val hasPrevious = target.episode > 1
    val hasNext = lastEpisode <= 0 || target.episode < lastEpisode

    /**
     * Moves to another episode without leaving the player.
     *
     * The same path a shelf uses, ending in the same place a source switch
     * does: the target is swapped underneath and everything below rekeys on
     * the new URL. Backing out to the detail page did work, but it stopped
     * playback, lost the position for the episode being left, and made the
     * commonest thing anybody does at the end of an episode the longest.
     */
    val goToEpisode: (Int) -> Unit = { wanted ->
        val anime = target.anime
        // Logged on arrival, because the last time this failed the log could
        // not say whether the press had reached here at all — and "the button
        // does nothing" has at least three causes that look identical.
        BuildInfo.log(
            "DebritsuWatch",
            "episode button: want $wanted, from ${target.episode}, " +
                "show has ${anime?.episodes ?: "an unknown number of"} episodes"
        )
        switching = "Finding episode $wanted"
        scope.launch {
            // An exception escaping here cancels the scope that raised it, and
            // with it every later launch from this screen — which is exactly
            // the shape of "it worked once and never again". It is caught and
            // said out loud instead.
            runCatching {
            Watch.episode(
                anilistId = target.anilistId,
                title = target.title,
                episode = wanted,
                // From the show where it is known, since this is what stops a
                // ninety-second creditless opening marking the episode watched.
                episodeMinutes = anime?.durationMins ?: target.episodeMinutes,
                isMovie = target.isMovie,
                anime = anime
            ) { state ->
                when (state) {
                    is Watch.State.Preparing -> switching = state.what
                    is Watch.State.Failed -> switching = state.why
                    is Watch.State.Ready -> {
                        ActivePlayer.playing.value = state.target
                        switching = null
                    }
                    // Automatic selection is off. The list belongs to the app's
                    // own screen, and there is no way to that from in here, so
                    // say so rather than appearing to do nothing.
                    is Watch.State.Choose ->
                        switching = "Episode $wanted needs a source chosen — use Sources"
                    else -> Unit
                }
            }
            }.onFailure {
                BuildInfo.log("DebritsuWatch", "episode $wanted failed: $it")
                switching = "Could not open episode $wanted: ${it.message ?: it}"
            }
        }
    }

    LaunchedEffect(target.url) {
        // Only if this is not the same playback carried across a window rebuild.
        if (!alreadyPlaying) {
            Watch.start(player, target)
            onState(Watch.State.Playing(target.title))
        }

        val watched = object : Watch.Playing {
            override val alive: Boolean get() = !player.ended
            override fun positionMs() = player.positionMs().takeIf { it >= 0 }
            override fun durationMs() = player.durationMs().takeIf { it > 0 }
        }
        launch {
            // Same reason as below: this loop also ends when the player is
            // released, and that is Back rather than the episode finishing.
            Watch.follow(watched, target) { state ->
                if (state !is Watch.State.Finished || !player.isReleased) onState(state)
            }
        }

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
                // The video's shape, which the buffer's own does not give: the
                // callback output scales the picture into whatever buffer size
                // VLC settles on, and that is not the video's proportions. An
                // AV1 release lands in a 1920x1152 buffer and is still 16:9.
                player.sourceSize()?.let { (w, h) ->
                    BuildInfo.log("DebritsuVlc", "source is ${w}x$h")
                    if (w > 0 && h > 0) aspect = w.toFloat() / h
                }
            }
            if (scrubbing == null) positionMs = player.positionMs()
            if (durationMs <= 0L) durationMs = player.durationMs()
            paused = !player.playing
        }
        // Only if it ran out. Being released ends this loop too, and reporting
        // that as the episode finishing put "Playback ended." on the shelves
        // every time somebody pressed Back.
        if (!player.isReleased) onState(Watch.State.Finished(false))
    }

    // Fullscreen hides them while nothing moves; windowed keeps them.
    //
    // Which is what every other player does, and for a reason: fullscreen is
    // the picture and nothing else, so anything over it is in the way, while a
    // window that hides its own controls is a window with a title bar and no
    // way to press play — the frame is already telling you this is a program
    // rather than a picture, so hiding the controls only makes it a worse one.
    //
    // Pausing does not hold them open in fullscreen. That was tried on the
    // reasoning that a still picture with no controls reads as a frozen
    // program; it does not, because pausing is usually done to look at the
    // picture and the controls then cover the thing they were pressed to see.
    LaunchedEffect(lastMoved, fullscreen) {
        controlsShown = true
        if (!fullscreen) return@LaunchedEffect
        delay(2500)
        controlsShown = false
    }

    // Note what is deliberately absent: this does not stop the player. Leaving
    // the screen and the window being rebuilt for fullscreen are the same event
    // as far as composition is concerned, and stopping here would end playback
    // every time somebody pressed F. Stopping is the Back button's job, which
    // knows the difference.
    DisposableEffect(target.url) {
        // Frames stop being published the moment this screen goes, and start
        // again when the next one composes. Between those two points the window
        // is being rebuilt for fullscreen and there is a renderer disposing
        // itself; drawing into it is an access violation inside Skia that takes
        // the whole JVM down.
        player.publishing = true
        onDispose {
            player.publishing = false
            runCatching {
                val pos = player.positionMs()
                val dur = player.durationMs()
                if (pos > 0 && dur > 0) {
                    // Written on the way out as well as every five seconds, so
                    // closing mid-episode keeps the exact spot.
                    Progress.save(target.anilistId, target.episode, pos, dur)
                }
            }
        }
    }

    // Registered with the window while this screen is up, and taken away when
    // it goes. Keys are handled there rather than here because focus inside
    // this screen moves — when the controls fade, whichever button held it
    // leaves the composition and the keys stop arriving, which is exactly what
    // "they work then stop" was.
    //
    // Deliberately does not wake the controls. These exist so playback can be
    // driven without anything appearing over the picture.
    DisposableEffect(target.url, fullscreen, paused, sourcesOpen, skippable) {
        onKeys { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeys false
            when (event.key) {
                // Only while there is something to skip, so S is free to mean
                // nothing the rest of the time rather than seeking at random.
                Key.S -> skippable?.let {
                    player.seekTo(it.endMs)
                    positionMs = it.endMs
                    true
                } ?: false
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
                // The list first, then fullscreen. Escape closes the nearest
                // thing that is open, which is what it does everywhere else.
                Key.Escape -> when {
                    sourcesOpen -> { sourcesOpen = false; true }
                    fullscreen -> { onFullscreen(false); true }
                    else -> false
                }
                else -> false
            }
        }
        onDispose { onKeys(null) }
    }

    /**
     * Opening and ending times, the same way the phone and television get them.
     *
     * AniSkip indexes by MAL id, so the AniList id has to be mapped first — the
     * same three-hop chain the episode titles go through, and it fails the same
     * way, quietly and without consequence beyond no button appearing.
     */
    LaunchedEffect(target.url) {
        if (target.anilistId <= 0 || target.episode <= 0) return@LaunchedEffect

        // The service scales its timings to the particular encode, so it wants
        // the duration — which is not known the instant playback starts.
        var waited = 0
        while (waited < 5000 && player.durationMs() <= 0L) {
            delay(250)
            waited += 250
        }

        val mal = runCatching {
            com.debritsu.app.data.Mappings.forAniList(target.anilistId, target.title).mal?.toIntOrNull()
        }.getOrNull()

        val found = runCatching {
            com.debritsu.app.data.AniSkip.segments(
                mal, target.episode, player.durationMs().coerceAtLeast(0L)
            )
        }.getOrDefault(emptyList())

        BuildInfo.log(
            "DebritsuWatch",
            "aniskip: mal=$mal episode=${target.episode} -> " +
                found.joinToString { "${it.kind} ${it.startMs}..${it.endMs}" }.ifEmpty { "nothing" }
        )
        segments = found
    }

    // The search this playback came out of, which is normally already here.
    // Only a downloaded episode arrives without one, having come from no
    // search at all — that is the single case that has to go and ask.
    val outcome = target.outcome ?: sourceList?.outcome

    LaunchedEffect(sourcesOpen, target.episode) {
        if (!sourcesOpen || outcome != null) return@LaunchedEffect
        Watch.sources(
            anilistId = target.anilistId,
            title = target.title,
            episode = target.episode,
            episodeMinutes = target.episodeMinutes,
            isMovie = target.isMovie
        ) { state ->
            when (state) {
                is Watch.State.Preparing -> sourceNote = state.what
                is Watch.State.Choose -> { sourceList = state; sourceNote = null }
                is Watch.State.Failed -> sourceNote = state.why
                else -> Unit
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { lastMoved = System.currentTimeMillis() }
    ) {
        // Read so the frame counter is an input to this composition; without it
        // nothing here depends on it and the picture never updates.
        @Suppress("UNUSED_EXPRESSION") frames

        player.frame()?.let { bitmap ->
            // Drawn at the video's aspect rather than the buffer's, and made to
            // fill that: the buffer holds the whole picture, but stretched to
            // whatever height VLC settled on, so squashing it back into the
            // right shape is the correction. Fit against the buffer's own
            // dimensions would letterbox a 16:9 episode as though it were 5:3.
            //
            // aspectRatio sizes within the window rather than beyond it, so a
            // 4:3 episode is still letterboxed rather than cropped.
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center)
                    .aspectRatio(aspect, matchHeightConstraintsFirst = false),
                contentScale = ContentScale.FillBounds
            )
        } ?: Text(
            "Opening…",
            color = Muted,
            modifier = Modifier.align(Alignment.Center)
        )

        // Said over the picture while another episode is being found, because
        // resolving one takes seconds and the current episode carries on
        // playing throughout — so without this the button looks ignored.
        switching?.let {
            Text(
                it,
                color = Paper,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color(0xCC120E1C), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

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
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onEpisode = { step -> goToEpisode(target.episode + step) },
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
                onSubtitles = { trackMenu = if (trackMenu == "subtitles") null else "subtitles" },
                onAudio = { trackMenu = if (trackMenu == "audio") null else "audio" },
                onFullscreen = { onFullscreen(!fullscreen) },
                onSources = { sourcesOpen = true },
                onBack = onBack
            )
        }

        // Above the controls rather than beside them, and it moves up when they
        // are showing so it never sits on the scrubber. Hidden while the source
        // list is open, which covers that side of the screen anyway.
        if (skippable != null && !sourcesOpen) {
            Button(
                onClick = { player.seekTo(skippable.endMs); positionMs = skippable.endMs },
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Paper),
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = if (controlsShown) 132.dp else 40.dp)
            ) {
                // Just the label. It said "Skip intro · S" to advertise the
                // key, and a shortcut printed on a button reads as part of its
                // name rather than as a hint.
                Text(skippable.label)
            }
        }

        trackMenu?.let { which ->
            val subtitles = which == "subtitles"
            val tracks = if (subtitles) player.subtitleTracks() else player.audioTracks()
            val current = if (subtitles) player.subtitleTrack() else player.audioTrack()
            TrackMenu(
                title = if (subtitles) "Subtitles" else "Audio",
                tracks = tracks,
                current = current,
                // Said plainly rather than left as a button that appears dead.
                // A file with one audio track and its subtitles burned into the
                // picture is normal for anime, not a fault.
                empty = if (subtitles)
                    "No subtitle tracks in this file. Many releases burn them into the picture."
                else "One audio track in this file.",
                onPick = { id ->
                    BuildInfo.log("DebritsuVlc", "track -> $id")
                    if (subtitles) player.setSubtitleTrack(id) else player.setAudioTrack(id)
                    trackMenu = null
                },
                onClose = { trackMenu = null }
            )
        }

        if (sourcesOpen) {
            SourcesOverlay(
                target = target,
                outcome = outcome,
                note = sourceNote,
                onClose = { sourcesOpen = false },
                // On the background scope rather than this screen's: pressing
                // Download and then Back is an obvious thing to do, and it
                // would cancel the transfer the moment the player closed.
                onDownload = target.anime?.let { show ->
                    { stream ->
                        sourceNote = "Downloading — starting"
                        Downloader.background.launch {
                            val result = Downloader.source(show, target.episode, stream) { step ->
                                sourceNote = "Downloading — $step"
                            }
                            sourceNote = when (result) {
                                is Downloader.Result.Done -> "Downloaded. It will play from disk."
                                is Downloader.Result.Failed -> result.why
                            }
                        }
                        Unit
                    }
                },
                onPick = { stream ->
                    val from = outcome ?: return@SourcesOverlay
                    scope.launch {
                        Watch.chosen(
                            stream = stream,
                            outcome = from,
                            anilistId = target.anilistId,
                            title = target.title,
                            episode = target.episode,
                            episodeMinutes = target.episodeMinutes,
                            isMovie = target.isMovie
                        ) { state ->
                            when (state) {
                                is Watch.State.Preparing -> sourceNote = state.what
                                is Watch.State.Failed -> sourceNote = state.why
                                // Swapped underneath rather than handed back to
                                // the app. Everything below keys on the URL, so
                                // the new one starts and the old one is released
                                // without the screen going anywhere.
                                is Watch.State.Ready -> {
                                    ActivePlayer.playing.value = state.target
                                    sourceNote = null
                                    sourcesOpen = false
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            )
        }
    }
}

/**
 * The source list, over the picture.
 *
 * Down the side rather than across the whole window: what is playing stays
 * visible while a different release is being considered, which is most of the
 * reason for changing source in the first place.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SourcesOverlay(
    target: Watch.Target,
    outcome: com.debritsu.app.data.AutoPlay.Outcome?,
    note: String?,
    onClose: () -> Unit,
    onDownload: ((com.debritsu.app.data.StreamOption) -> Unit)?,
    onPick: (com.debritsu.app.data.StreamOption) -> Unit
) {
    val filter = com.debritsu.app.data.Settings.sourceFilter
    val minSize = com.debritsu.app.data.minEpisodeSizeMb(target.episodeMinutes)

    // The source playing is one of these, not something to be found among
    // them: this is the list it was picked out of. So it is compared as an
    // object and there is nothing left to get wrong.
    val nowPlaying = outcome?.chosen

    // And it goes to the top whatever it scores. Marking it where it happened
    // to rank was no use — 219 sources came back for one episode, and a mark a
    // dozen screens down is a mark nobody sees. The sort is stable, so
    // everything else keeps its order.
    val ranked = remember(outcome) {
        rankSources(outcome?.results?.flatMap { it.streams }.orEmpty(), target.episodeMinutes)
            .sortedByDescending { (stream, _) -> stream == nowPlaying }
    }

    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(560.dp)
                .background(Color(0xF2160F24)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sources", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(
                        "${target.title} — episode ${target.episode}" +
                            (ranked.size.takeIf { it > 0 }?.let { "  ·  $it found" } ?: ""),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onClose) { Text("Close", color = Muted) }
            }

            note?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(ranked) { (stream, meta) ->
                    SourceRow(
                        stream = stream,
                        meta = meta,
                        filter = filter,
                        minSizeMb = minSize,
                        playing = stream == nowPlaying,
                        onDownload = onDownload?.let { { it(stream) } }
                    ) { onPick(stream) }
                }
            }
        }
    }
}

/**
 * One control on the bar, with the name it would have had as a word.
 *
 * The name is not decoration: an icon-only bar is a guessing game the first
 * time, and this is what a hover says. It is also what a screen reader reads,
 * which is the same problem from the other side.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Paper,
    size: androidx.compose.ui.unit.Dp = 26.dp
) {
    TooltipArea(tooltip = {
        Surface(color = Color(0xF2231B38), shape = RoundedCornerShape(6.dp)) {
            Text(
                name,
                color = Paper,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                // Dimmed rather than hidden when there is nowhere to go. A
                // control that disappears at the last episode moves everything
                // beside it, and a bar whose buttons shift is worse than one
                // with a button that is plainly unavailable.
                tint = if (enabled) tint else tint.copy(alpha = 0.3f),
                modifier = Modifier.size(size)
            )
        }
    }
}

/**
 * The tracks in this file, to pick from.
 *
 * Small and beside the button that opened it, rather than the full-height
 * panel the sources use: a file has a handful of tracks and two hundred
 * sources, and the two want different shapes.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrackMenu(
    title: String,
    tracks: List<Pair<Int, String>>,
    current: Int,
    empty: String,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    // -1 is VLC's "disabled" entry. Worth offering for subtitles and never for
    // audio, but it is simplest to show what the file has and let the list
    // carry it — turning subtitles off is a thing people want.
    val real = tracks.filter { it.first != -1 }

    Column(
        Modifier.align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 96.dp)
            .width(340.dp)
            .background(Color(0xF2160F24), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Paper)
            TextButton(onClick = onClose) {
                Text("Close", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (real.isEmpty()) {
            Text(empty, color = Muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        tracks.forEach { (id, name) ->
            val label = if (id == -1) "Off" else name
            Text(
                (if (id == current) "●  " else "○  ") + label,
                color = if (id == current) Violet else Paper,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
                    .clickable { onPick(id) }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun Controls(
    target: Watch.Target,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    fraction: Float,
    fullscreen: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    /** −1 or +1, relative to what is playing. */
    onEpisode: (Int) -> Unit,
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
        // No source name here. It was tried and it did not answer the question:
        // the name is not unique — one episode came back with four rows all
        // called "[TB ⚡] Comet 1080p" — so reading it told you nothing you
        // could then find in the list. The list marks the row instead.
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
            ControlIcon(PlayerIcons.Back, "Back", onBack, tint = Muted)
            ControlIcon(
                PlayerIcons.PreviousEpisode, "Previous episode",
                { onEpisode(-1) }, enabled = hasPrevious
            )
            ControlIcon(PlayerIcons.Rewind, "Back 10 seconds", { onSeek(-10) })
            ControlIcon(
                if (paused) PlayerIcons.Play else PlayerIcons.Pause,
                if (paused) "Play" else "Pause",
                onPlayPause,
                size = 34.dp
            )
            ControlIcon(PlayerIcons.Forward, "Forward 30 seconds", { onSeek(30) })
            ControlIcon(
                PlayerIcons.NextEpisode, "Next episode",
                { onEpisode(1) }, enabled = hasNext
            )

            Box(Modifier.weight(1f))

            // Kept as words. Every other control here is a verb with a shape
            // everyone already knows; this is the only one whose content is the
            // point, and an icon cannot say which episode of what.
            Text(
                "${target.title} · ep ${target.episode}",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Box(Modifier.width(12.dp))

            ControlIcon(PlayerIcons.Subtitles, "Subtitle track", onSubtitles, tint = Muted)
            ControlIcon(PlayerIcons.Audio, "Audio track", onAudio, tint = Muted)
            ControlIcon(PlayerIcons.Sources, "Sources", onSources, tint = Muted)
            ControlIcon(
                if (fullscreen) PlayerIcons.Windowed else PlayerIcons.Fullscreen,
                if (fullscreen) "Leave fullscreen" else "Fullscreen",
                onFullscreen,
                tint = Muted
            )
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
