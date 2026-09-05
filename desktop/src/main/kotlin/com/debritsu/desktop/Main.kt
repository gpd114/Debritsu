package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.debritsu.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

private val Violet = Color(0xFF8B5CF6)
private val Ink = Color(0xFF16121F)
private val Panel = Color(0xFF1E1830)
private val Paper = Color(0xFFF1EEF8)
private val Muted = Color(0xFF948CAB)

fun main() {
    Settings.store = FileStore.default()
    BuildInfo.anilistClientId = ANILIST_CLIENT_ID
    BuildInfo.debug = System.getenv("DEBRITSU_DEBUG") != null
    BuildInfo.log = { tag, message -> println("$tag  $message") }

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
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        if (Settings.aniListToken.isEmpty()) return@LaunchedEffect
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
                    ShowRow(anime) { ep ->
                        scope.launch {
                            Watch.episode(
                                anilistId = anime.id,
                                title = anime.title,
                                episode = ep,
                                episodeMinutes = 0,
                                isMovie = (anime.episodes ?: 0) == 1
                            ) { state ->
                                status = when (state) {
                                    is Watch.State.Preparing -> state.what
                                    is Watch.State.Playing -> state.title
                                    is Watch.State.Finished ->
                                        if (state.pushed) "Marked watched on AniList." else "Playback ended."
                                    is Watch.State.Failed -> state.why
                                }
                            }
                            reload++
                        }
                    }
                }
            }
        }

        if (showSettings) {
            Box(Modifier.width(1.dp).fillMaxSize().background(Muted.copy(alpha = 0.2f)))
            SettingsPane(Modifier.width(380.dp)) { reload++ }
        }
    }
}

@Composable
private fun ShowRow(anime: Anime, onPlay: (Int) -> Unit) {
    val next = (anime.progress) + 1
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(anime.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Watched ${anime.progress}" + (anime.episodes?.let { " of $it" } ?: ""),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = { onPlay(next) }) { Text("Play episode $next") }
        }
    }
}

@Composable
private fun SettingsPane(modifier: Modifier = Modifier, onChanged: () -> Unit) {
    var token by remember { mutableStateOf(Settings.aniListToken) }
    var addon by remember { mutableStateOf(Settings.addons.firstOrNull() ?: "") }
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

        OutlinedTextField(
            value = debrid,
            onValueChange = { debrid = it; Settings.debridToken = it.trim() },
            label = { Text("${Settings.debridProvider.label} API token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
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
