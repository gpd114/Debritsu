package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.DownloadIndex
import com.debritsu.app.data.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One show: its artwork, what it is, and every episode.
 *
 * The list is for continuing; this is for deciding. It asks AniList for the
 * full record rather than reusing the list entry, because description, genres,
 * studio and running time are not on a list query — the phone screen makes the
 * same second request for the same reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    initial: Anime,
    onBack: () -> Unit,
    onDownload: (Anime, Int) -> Unit,
    onPlay: (Anime, Int) -> Unit
) {
    // Starts from what the list already knew, so the page draws immediately and
    // fills in rather than showing a spinner over nothing.
    var anime by remember(initial.id) { mutableStateOf(initial) }
    var loadFailed by remember(initial.id) { mutableStateOf(false) }

    LaunchedEffect(initial.id) {
        val full = withContext(Dispatchers.IO) { runCatching { AniList.media(initial.id) }.getOrNull() }
        if (full != null) {
            // The list entry knows your progress; the media query does not
            // always carry it. Keep whichever is higher rather than losing it.
            anime = full.copy(progress = maxOf(full.progress, initial.progress))
        } else {
            loadFailed = true
        }
    }

    val next = anime.progress + 1
    val total = (anime.episodes ?: anime.nextEpisode?.let { it - 1 } ?: next).coerceAtLeast(next)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            RemoteImage(
                url = anime.banner ?: anime.cover,
                modifier = Modifier.fillMaxSize(),
                corner = 0,
                contentScale = ContentScale.Crop
            )
            TextButton(onClick = onBack, modifier = Modifier.padding(12.dp)) {
                Text("← Back", color = DetailPaper)
            }
        }

        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RemoteImage(
                url = anime.cover,
                modifier = Modifier.width(150.dp).aspectRatio(0.7f),
                fallback = anime.title
            )

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(anime.title, style = MaterialTheme.typography.headlineSmall)

                Text(
                    listOfNotNull(
                        anime.format,
                        anime.seasonLabel,
                        anime.episodes?.let { "$it episodes" },
                        anime.durationMins?.let { "${it}m" },
                        anime.averageScore?.let { "$it%" },
                        anime.airingStatus
                    ).joinToString("  ·  "),
                    color = DetailMuted,
                    style = MaterialTheme.typography.bodySmall
                )

                if (anime.studio != null || anime.genres.isNotEmpty()) {
                    Text(
                        listOfNotNull(anime.studio, anime.genres.take(4).joinToString(", ").ifBlank { null })
                            .joinToString("  ·  "),
                        color = DetailMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPlay(anime, next) }) {
                        Text(
                            if (Progress.fraction(anime.id, next) > 0f) "Resume episode $next"
                            else "Play episode $next"
                        )
                    }
                    TextButton(onClick = { onDownload(anime, next) }) {
                        Text("Download $next", color = DetailMuted)
                    }
                }

                anime.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = DetailPaper)
                }

                if (loadFailed) {
                    Text(
                        "Couldn't load the full details — AniList stalls on about one request " +
                            "in five. Episodes below still work.",
                        color = DetailMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Episodes", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
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
                        onPlay = { onPlay(anime, ep) },
                        onDownload = { onDownload(anime, ep) }
                    )
                }
            }
        }
    }
}

private val DetailPaper = androidx.compose.ui.graphics.Color(0xFFF1EEF8)
private val DetailMuted = androidx.compose.ui.graphics.Color(0xFF948CAB)
