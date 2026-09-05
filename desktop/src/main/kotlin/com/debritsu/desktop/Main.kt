package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.BuildInfo
import com.debritsu.app.data.DebridProvider
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import com.debritsu.app.data.SyncQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

private val Violet = Color(0xFF8B5CF6)
private val Ink = Color(0xFF16121F)
private val Panel = Color(0xFF1E1830)
private val Paper = Color(0xFFF1EEF8)
private val Muted = Color(0xFF948CAB)

/** For a download that is on disk. Semantic, and deliberately not the accent. */
private val Keep = Color(0xFF6FC79B)

fun main() {
    Settings.store = FileStore.default()
    Progress.store = FileStore.progress()
    DownloadIndex.store = FileStore.named("downloads")
    SyncQueue.store = FileStore.named("sync-queue")
    BuildInfo.anilistClientId = ANILIST_CLIENT_ID

    // On by default, to a file. The standalone build has no console to print
    // to, and its stdout goes nowhere anybody can read — which meant the first
    // real playback failure could only be described, not diagnosed. A few
    // kilobytes a session is a fair price for being able to answer "why did it
    // not do that" without asking someone to reproduce it under a debugger.
    BuildInfo.debug = System.getenv("DEBRITSU_QUIET") == null
    BuildInfo.log = Logging.install()

    // An exception thrown inside a JNA callback cannot propagate: the native
    // caller gets a zero and the exception vanishes. That is exactly how the
    // video output failed — libVLC read the zero as "no pictures" and rebuilt
    // its output forever, while nothing anywhere named a cause. This makes
    // those visible.
    com.sun.jna.Native.setCallbackExceptionHandler { callback, e ->
        BuildInfo.log("DebritsuJna", "callback ${callback.javaClass.simpleName} threw: $e")
        e.stackTrace.take(6).forEach { BuildInfo.log("DebritsuJna", "  at $it") }
    }

    // Said at startup rather than discovered when something is played. libVLC
    // is loaded as a native library, so a missing or mismatched one is a link
    // error rather than a program that fails to start — worth knowing before it
    // matters.
    BuildInfo.log(
        "DebritsuVlc",
        Vlc.directory()?.let { dir ->
            "found at $dir; libvlc reports ${Vlc.version() ?: "nothing — it did not load"}"
        } ?: "not found"
    )

    application {
        // Held here rather than inside the player, because the window is what
        // goes fullscreen and Escape has to reach it wherever focus happens to
        // be — which, while mpv is drawing, is often the native surface.
        val windowState = rememberWindowState(width = 1100.dp, height = 760.dp)
        var fullscreen by remember { mutableStateOf(false) }

        /**
         * The player's key handling, registered while something is playing.
         *
         * Held at the window rather than on a focusable box inside it. Focus
         * was the problem: when the controls faded, the button holding focus
         * left the composition and focus went nowhere, so the keys stopped
         * working a few seconds after they started. A window sees them however
         * that goes.
         */
        var playerKeys by remember { mutableStateOf<((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?>(null) }

        LaunchedEffect(fullscreen) {
            windowState.placement =
                if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Debritsu",
            // Preview, so a control button that has been clicked and kept focus
            // cannot swallow Space and press itself instead of pausing.
            onPreviewKeyEvent = { event -> playerKeys?.invoke(event) ?: false },
            onKeyEvent = { event ->
                if (fullscreen && event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape
                ) {
                    fullscreen = false
                    true
                } else {
                    false
                }
            },
            // The window's icon is separate from the executable's: jpackage
            // stamps the .ico onto the .exe, and this is what the title bar and
            // the running taskbar entry show.
            icon = painterResource("icon.png"),
            state = windowState
        ) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet,
                    background = Ink,
                    surface = Panel,
                    onBackground = Paper,
                    onSurface = Paper
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    App(
                        fullscreen = fullscreen,
                        onFullscreen = { fullscreen = it },
                        onPlayerKeys = { playerKeys = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun App(
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onPlayerKeys: (((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?) -> Unit
) {
    var showSettings by remember { mutableStateOf(Settings.aniListToken.isEmpty()) }
    var watching by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var planning by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var listed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var status by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Anime>>(emptyList()) }
    val searching = query.trim().length >= 3

    /** The show being looked at in full, or null for the shelves. */
    var detailOf by remember { mutableStateOf<Anime?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    /** Sources to choose from, when automatic selection is off. */
    var choosing by remember { mutableStateOf<Watch.State.Choose?>(null) }
    /** What is playing, in this window. Null when nothing is. */
    var playing by remember { mutableStateOf<Watch.Target?>(null) }
    /** Remembered so the Sources button inside the player knows what to list. */
    var playingShow by remember { mutableStateOf<Anime?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (Settings.aniListToken.isEmpty()) return@LaunchedEffect

        // Anything watched offline goes first, so the shelves below already
        // reflect it rather than showing a stale position and correcting
        // themselves a moment later.
        val queued = SyncQueue.count
        if (queued > 0) {
            status = "Syncing $queued watched offline..."
            withContext(Dispatchers.IO) { runCatching { SyncQueue.flush() } }
        }

        status = "Loading..."
        // Each of these keeps what it had when a request fails rather than
        // emptying, and an empty shelf is hidden — so on the phone a single
        // failed request made Continue watching disappear at random. It was not
        // random: this is five requests against AniList's thirty a minute, and
        // a failure means "no answer", which is not "nothing there".
        coroutineScope {
            launch { runCatching { AniList.watching() }.onSuccess { watching = it } }
            launch { runCatching { AniList.planning() }.onSuccess { planning = it } }
            launch { runCatching { AniList.listedIds() }.onSuccess { listed = it } }
            // These two do not change while you watch something, so they are
            // asked for only while missing — which also gives a shelf that
            // failed on the way in another go on the next reload.
            if (trending.isEmpty()) {
                launch { runCatching { AniList.trending().items }.onSuccess { trending = it } }
            }
            if (recommended.isEmpty()) {
                launch { runCatching { AniList.recommended().items }.onSuccess { recommended = it } }
            }
        }
        status = ""
    }

    // Three characters before asking, and a pause after the last keystroke, so
    // typing a title does not fire a query per letter.
    LaunchedEffect(query) {
        if (!searching) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        found = withContext(Dispatchers.IO) {
            runCatching { AniList.search(query.trim()).items }.getOrDefault(emptyList())
        }
    }

    // Retries whatever was watched offline, without being asked. The flush
    // above only runs when the shelves reload, so coming back online did
    // nothing until Refresh was pressed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (Settings.aniListToken.isEmpty()) continue
            val before = SyncQueue.count
            if (before == 0) continue
            withContext(Dispatchers.IO) { runCatching { SyncQueue.flush() } }
            val after = SyncQueue.count
            if (after < before) {
                BuildInfo.log("DebritsuSync", "flushed ${before - after} of $before queued")
                reload++
            }
        }
    }

    // Defined once and handed to every screen, so an episode started from a
    // shelf, a search result or the detail page goes through one path.
    val play: (Anime, Int) -> Unit = { anime, ep ->
        playingShow = anime
        scope.launch {
            Watch.episode(
                anilistId = anime.id,
                title = anime.title,
                episode = ep,
                // AniList's own minutes-per-episode where it has it, which is
                // what makes the plausible-size floor meaningful.
                episodeMinutes = anime.durationMins ?: 0,
                isMovie = (anime.episodes ?: 0) == 1
            ) { state ->
                status = when (state) {
                    is Watch.State.Preparing -> state.what
                    is Watch.State.Playing -> state.title
                    is Watch.State.Pushed -> {
                        reload++
                        "Episode ${state.episode} marked watched on AniList."
                    }
                    is Watch.State.Finished ->
                        if (state.pushed) "Marked watched on AniList." else "Playback ended."
                    is Watch.State.Failed -> state.why
                    is Watch.State.Choose -> {
                        choosing = state
                        "${state.outcome.results.sumOf { r -> r.streams.size }} sources found."
                    }
                    // Hands over to the player screen, which owns the surface
                    // mpv draws into and so has to be what starts it.
                    is Watch.State.Ready -> {
                        playing = state.target
                        ""
                    }
                }
            }
            reload++
        }
    }

    val sources: (Anime, Int) -> Unit = { anime, ep ->
        scope.launch {
            Watch.sources(
                anilistId = anime.id,
                title = anime.title,
                episode = ep,
                episodeMinutes = anime.durationMins ?: 0,
                isMovie = (anime.episodes ?: 0) == 1
            ) { state ->
                status = when (state) {
                    is Watch.State.Preparing -> state.what
                    is Watch.State.Choose -> {
                        choosing = state
                        ""
                    }
                    is Watch.State.Failed -> state.why
                    else -> status
                }
            }
        }
    }

    val download: (Anime, Int) -> Unit = { anime, ep ->
        scope.launch {
            val result = Downloader.episode(anime, ep) { step -> status = "Episode $ep — $step" }
            status = when (result) {
                is Downloader.Result.Done -> "Episode $ep downloaded. It will play from disk."
                is Downloader.Result.Failed -> result.why
            }
            reload++
        }
    }

    // Before every other screen: while something is playing, this window is the
    // player.
    val nowPlaying = playing
    if (nowPlaying != null) {
        PlayerScreen(
            target = nowPlaying,
            fullscreen = fullscreen,
            onFullscreen = onFullscreen,
            onKeys = onPlayerKeys,
            onBack = { onFullscreen(false); playing = null; reload++ },
            onSources = {
                val show = playingShow
                playing = null
                if (show != null) sources(show, nowPlaying.episode)
            },
            onState = { state ->
                status = when (state) {
                    is Watch.State.Playing -> state.title
                    is Watch.State.Pushed -> {
                        reload++
                        "Episode ${state.episode} marked watched on AniList."
                    }
                    is Watch.State.Finished -> "Playback ended."
                    is Watch.State.Failed -> state.why
                    else -> status
                }
            }
        )
        return
    }

    val pick = choosing
    if (pick != null) {
        SourcesScreen(
            state = pick,
            onBack = { choosing = null },
            onPick = { stream ->
                choosing = null
                scope.launch {
                    Watch.chosen(
                        stream = stream,
                        subtitles = pick.outcome.subtitles,
                        anilistId = pick.anilistId,
                        title = pick.title,
                        episode = pick.episode,
                        episodeMinutes = pick.episodeMinutes
                    ) { state ->
                        status = when (state) {
                            is Watch.State.Preparing -> state.what
                            is Watch.State.Playing -> state.title
                            is Watch.State.Pushed -> {
                                reload++
                                "Episode ${state.episode} marked watched on AniList."
                            }
                            is Watch.State.Finished -> "Playback ended."
                            is Watch.State.Failed -> state.why
                            is Watch.State.Choose -> ""
                            is Watch.State.Ready -> {
                                playing = state.target
                                ""
                            }
                        }
                    }
                    reload++
                }
            }
        )
        return
    }

    // Before the detail page and before the shelves, because this is the one
    // screen that works with no network — and on a plane the shelves will be
    // empty, which is exactly when the downloads have to be reachable.
    if (showDownloads) {
        DownloadsScreen(
            onBack = { showDownloads = false },
            onPlay = { item ->
                // Everything needed is in the index; nothing is looked up. The
                // watch path finds the local file and never touches debrid.
                scope.launch {
                    Watch.episode(
                        anilistId = item.anilistId,
                        title = item.title,
                        episode = item.episode,
                        episodeMinutes = 0,
                        isMovie = false
                    ) { state ->
                        status = when (state) {
                            is Watch.State.Preparing -> state.what
                            is Watch.State.Playing -> state.title
                            is Watch.State.Pushed -> "Episode ${state.episode} marked watched."
                            is Watch.State.Finished -> "Playback ended."
                            is Watch.State.Failed -> state.why
                            // Cannot happen here: a downloaded episode is
                            // played from disk and never looks for sources.
                            is Watch.State.Choose -> ""
                            is Watch.State.Ready -> {
                                playing = state.target
                                ""
                            }
                        }
                    }
                }
            },
            onChanged = { reload++ }
        )
        return
    }

    val showing = detailOf
    if (showing != null) {
        DetailScreen(
            initial = showing,
            onBack = { detailOf = null; reload++ },
            onOpenOther = { detailOf = it },
            onDownload = download,
            onSources = sources,
            onPlay = play
        )
        return
    }

    // Anything already on a list is not a discovery, so recommendations drop
    // it — and that means the whole list, not just the two shelves above.
    // Filtering on those alone let through shows finished years ago, which is
    // the one thing this shelf should never suggest.
    val onMyList = listed + (watching + planning).map { it.id }

    val shelves = buildList {
        if (watching.isNotEmpty()) add("Continue watching" to watching)
        if (planning.isNotEmpty()) add("Plan to watch" to planning)
        if (trending.isNotEmpty()) add("Trending" to trending)
        val fresh = recommended.filter { it.id !in onMyList }
        if (fresh.isNotEmpty()) add("Recommended" to fresh)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showDownloads = true }) {
                    val kept = DownloadIndex.all().size
                    Text(if (kept > 0) "Downloads ($kept)" else "Downloads")
                }
                TextButton(onClick = { reload++ }) { Text("Refresh") }
                TextButton(onClick = { showSettings = !showSettings }) { Text("Settings") }
            }

            // Above everything, because without VLC nothing on this screen can
            // be played and finding that out by pressing play is worse.
            VlcBanner(onInstalled = { reload++ })

            if (status.isNotEmpty()) {
                Text(
                    status,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
                )
            }

            if (searching) {
                Column(Modifier.padding(top = 12.dp)) {
                    Shelf(
                        title = if (found.isEmpty()) "Searching for \"${query.trim()}\"" else "Results",
                        list = found,
                        onOpen = { detailOf = it }
                    )
                }
            } else if (shelves.isEmpty()) {
                Text(
                    if (Settings.aniListToken.isEmpty()) "Sign in from Settings to see your list."
                    else "Nothing to show yet.",
                    color = Muted,
                    modifier = Modifier.padding(28.dp)
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    items(shelves) { shelf ->
                        Shelf(shelf.first, shelf.second) { detailOf = it }
                    }
                }
            }
        }

        if (showSettings) {
            Box(Modifier.width(1.dp).fillMaxSize().background(Muted.copy(alpha = 0.2f)))
            // Wide enough for a row of quality options without clipping them.
            SettingsPane(Modifier.width(430.dp)) { reload++ }
        }
    }
}

/** Shared with the detail screen, which lays out the same grid. */
@Composable
internal fun EpisodeChip(
    episode: Int,
    watched: Boolean,
    isNext: Boolean,
    partWatched: Float,
    downloaded: Boolean,
    downloading: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    val background = when {
        downloaded -> Keep.copy(alpha = 0.22f)
        isNext -> Violet.copy(alpha = 0.25f)
        watched -> Color.Transparent
        else -> Muted.copy(alpha = 0.12f)
    }
    val ink = when {
        isNext || downloaded -> Paper
        watched -> Muted
        else -> Paper.copy(alpha = 0.85f)
    }
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(background),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.clickable(onClick = onPlay).padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp)
        ) {
            Text(
                if (partWatched > 0f) "$episode · ${(partWatched * 100).toInt()}%" else "$episode",
                color = ink,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // The number plays, the arrow keeps it. Two targets in one chip rather
        // than a mode switch, so neither action is hidden behind the other.
        if (!downloaded) {
            Box(
                Modifier.clickable(onClick = onDownload).padding(end = 9.dp, top = 7.dp, bottom = 7.dp)
            ) {
                Text(
                    if (downloading) "…" else "↓",
                    color = if (downloading) Violet else Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Text(
                "✓",
                color = Keep,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 9.dp)
            )
        }
    }
}
