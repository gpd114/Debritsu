package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
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

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Debritsu",
            state = rememberWindowState(width = 1100.dp, height = 760.dp)
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
                Surface(Modifier.fillMaxSize(), color = Ink) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    var showSettings by remember { mutableStateOf(Settings.aniListToken.isEmpty()) }
    var watching by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    /** Which show has its episode list open. One at a time, or the list becomes unreadable. */
    var expandedId by remember { mutableStateOf<Int?>(null) }

    /** The show being looked at in full, or null for the list. */
    var detailOf by remember { mutableStateOf<Anime?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (Settings.aniListToken.isEmpty()) return@LaunchedEffect

        // Anything watched offline goes first, so the list that loads below
        // already reflects it rather than showing a stale position and
        // correcting itself a moment later.
        val queued = SyncQueue.count
        if (queued > 0) {
            status = "Syncing $queued watched offline…"
            withContext(Dispatchers.IO) { runCatching { SyncQueue.flush() } }
        }

        status = "Loading your list…"
        val list = withContext(Dispatchers.IO) {
            runCatching { AniList.watching() }.getOrNull()
        }
        watching = list ?: emptyList()
        status = when {
            list == null -> "AniList did not answer. It stalls on about one request in five; try again."
            list.isEmpty() -> "Nothing on your Watching list."
            else -> ""
        }
    }

    // Retries whatever was watched offline, without being asked.
    //
    // The flush above only runs when the list is (re)loaded, so coming back
    // online did nothing until Refresh was pressed — the queue was right and
    // simply had nothing to prompt it. Half a minute is well inside the time
    // between closing a laptop lid on a plane and opening it somewhere with
    // wifi, and costs one AniList request per attempt only while something is
    // actually queued.
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

    // Defined once and handed to both the list and the detail screen, so an
    // episode started from either goes through exactly the same path.
    val play: (Anime, Int) -> Unit = { anime, ep ->
        scope.launch {
            Watch.episode(
                anilistId = anime.id,
                title = anime.title,
                episode = ep,
                // AniList's own minutes-per-episode where it has it, which is
                // what makes the plausible-size floor meaningful. Zero falls
                // back to four minutes, which is far weaker.
                episodeMinutes = anime.durationMins ?: 0,
                isMovie = (anime.episodes ?: 0) == 1
            ) { state ->
                status = when (state) {
                    is Watch.State.Preparing -> state.what
                    is Watch.State.Playing -> state.title
                    is Watch.State.Pushed -> {
                        // Refresh now rather than when mpv is closed: the list is
                        // the only place this is visible, and waiting for the
                        // player to be shut reads as nothing having happened.
                        reload++
                        "Episode ${state.episode} marked watched on AniList."
                    }
                    is Watch.State.Finished ->
                        if (state.pushed) "Marked watched on AniList." else "Playback ended."
                    is Watch.State.Failed -> state.why
                }
            }
            reload++
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

    val showing = detailOf
    if (showing != null) {
        DetailScreen(
            initial = showing,
            onBack = { detailOf = null; reload++ },
            onDownload = download,
            onPlay = play
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(28.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Continue watching", style = MaterialTheme.typography.headlineSmall)
                Row {
                    TextButton(onClick = { reload++ }) { Text("Refresh") }
                    TextButton(onClick = { showSettings = !showSettings }) { Text("Settings") }
                }
            }

            if (status.isNotEmpty()) {
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }

            LazyColumn(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(watching) { anime ->
                    ShowRow(
                        anime = anime,
                        expanded = expandedId == anime.id,
                        onToggle = { expandedId = if (expandedId == anime.id) null else anime.id },
                        onOpen = { detailOf = anime },
                        onDownload = { ep -> download(anime, ep) },
                        onPlay = { ep -> play(anime, ep) }
                    )
                }
            }
        }

        if (showSettings) {
            Box(Modifier.width(1.dp).fillMaxSize().background(Muted.copy(alpha = 0.2f)))
            SettingsPane(Modifier.width(380.dp)) { reload++ }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowRow(
    anime: Anime,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDownload: (Int) -> Unit,
    onPlay: (Int) -> Unit
) {
    val next = anime.progress + 1

    /**
     * How many episodes there are to offer.
     *
     * AniList leaves `episodes` null while a show is still airing, so the count
     * comes from what has aired instead — the next airing episode minus one.
     * Failing both, only the next one is offered, which is no worse than the
     * button that was here before.
     */
    val total = (anime.episodes
        ?: anime.nextEpisode?.let { it - 1 }
        ?: next).coerceAtLeast(next)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteImage(
                url = anime.cover,
                modifier = Modifier.width(46.dp).height(64.dp).clickable(onClick = onOpen),
                fallback = anime.title
            )

            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                // The title opens the show. A whole-row click would fight the
                // buttons sitting in it.
                Text(
                    anime.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(onClick = onOpen)
                )
                val partWatched = Progress.fraction(anime.id, next)
                Text(
                    buildString {
                        append("Watched ${anime.progress}")
                        anime.episodes?.let { append(" of $it") }
                        // Only worth saying when there is something to come back
                        // to; Progress clears itself once an episode is finished.
                        if (partWatched > 0f) {
                            append("  ·  episode $next ${(partWatched * 100).toInt()}% in")
                        }
                    },
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onToggle) {
                Text(if (expanded) "Hide episodes" else "All episodes", color = Muted)
            }
            Button(onClick = { onPlay(next) }) {
                Text(
                    if (Progress.fraction(anime.id, next) > 0f) "Resume $next" else "Play $next"
                )
            }
        }

        if (expanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (ep in 1..total) {
                    val held = DownloadIndex.get(anime.id, ep)
                    EpisodeChip(
                        episode = ep,
                        watched = ep <= anime.progress,
                        isNext = ep == next,
                        partWatched = Progress.fraction(anime.id, ep),
                        downloaded = held != null && Downloader.isComplete(held),
                        downloading = held != null && Downloader.isRunning(held),
                        onPlay = { onPlay(ep) },
                        onDownload = { onDownload(ep) }
                    )
                }
            }
        }
    }
}

/**
 * One episode. Watched ones recede, the next one is picked out, and a
 * part-watched one carries how far in it is — which is the only state where the
 * number alone would not say enough.
 */
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

@Composable
private fun SettingsPane(modifier: Modifier = Modifier, onChanged: () -> Unit) {
    var token by remember { mutableStateOf(Settings.aniListToken) }
    var addon by remember { mutableStateOf(Settings.addons.firstOrNull() ?: "") }
    var provider by remember { mutableStateOf(Settings.debridProvider) }
    var debrid by remember { mutableStateOf(Settings.debridToken) }
    var mpvPath by remember { mutableStateOf(Settings.store.getString("mpv_path", "")) }
    val found = remember(mpvPath) { Mpv.locate(mpvPath) }

    Column(
        modifier.fillMaxSize().background(Panel).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text(
            "Sign in opens AniList in your browser. It shows a token — paste it here. " +
                "This build needs its own AniList client, with the redirect URL set to " +
                "https://anilist.co/api/v2/oauth/pin",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            enabled = BuildInfo.anilistClientId.isNotBlank(),
            onClick = {
                runCatching {
                    Desktop.getDesktop().browse(URI(AniList.authUrl(Settings.aniListClientId)))
                }
            }
        ) { Text(if (BuildInfo.anilistClientId.isBlank()) "No client id configured" else "Sign in") }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it; Settings.aniListToken = it.trim(); onChanged() },
            label = { Text("AniList token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = addon,
            onValueChange = {
                addon = it
                Settings.addons = listOfNotNull(Settings.normaliseAddon(it).ifBlank { null })
            },
            label = { Text("Stremio addon URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Tokens are stored per provider, so picking the wrong one does not
        // merely mislabel the field — it files the key under another service's
        // name and then calls that service's API with it.
        Text("Debrid provider", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DebridProvider.entries.forEach { p ->
                val selected = p == provider
                TextButton(
                    onClick = {
                        provider = p
                        Settings.debridProvider = p
                        // Re-read: the getter is keyed by provider, so this is
                        // whatever was last saved for the one just chosen.
                        debrid = Settings.debridToken
                    }
                ) {
                    Text(
                        p.label,
                        color = if (selected) Violet else Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedTextField(
            value = debrid,
            onValueChange = { debrid = it; Settings.debridToken = it.trim() },
            label = { Text("${provider.label} API token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Get it from ${provider.tokenHint}",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = mpvPath,
            onValueChange = { mpvPath = it; Settings.store.putString("mpv_path", it.trim()) },
            label = { Text("mpv.exe (blank to search)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            found?.let { "Found mpv: ${it.absolutePath}" }
                ?: "mpv not found — winget install shinchiro.mpv",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
