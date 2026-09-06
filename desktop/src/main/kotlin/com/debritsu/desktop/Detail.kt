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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
    /** Opening a related or recommended show, which replaces this page. */
    onOpenOther: (Anime) -> Unit,
    onDownload: (Anime, Int) -> Unit,
    /** List the sources for an episode and choose one by hand. */
    onSources: (Anime, Int) -> Unit,
    onPlay: (Anime, Int) -> Unit,
    /** The list entry changed, so the shelves behind this page are stale. */
    onChanged: () -> Unit = {}
) {
    // Starts from what the list already knew, so the page draws immediately and
    // fills in rather than showing a spinner over nothing.
    var anime by remember(initial.id) { mutableStateOf(initial) }
    var loadFailed by remember(initial.id) { mutableStateOf(false) }
    /** Whether the list entry is being edited, rather than just read. */
    var editing by remember(initial.id) { mutableStateOf(false) }
    var extras by remember(initial.id) { mutableStateOf<AniList.Extras?>(null) }

    LaunchedEffect(initial.id) {
        val full = withContext(Dispatchers.IO) { runCatching { AniList.media(initial.id) }.getOrNull() }
        if (full != null) {
            // The list entry knows your progress; the media query does not
            // always carry it. Keep whichever is higher rather than losing it.
            anime = full.copy(progress = maxOf(full.progress, initial.progress))
        } else {
            loadFailed = true
        }
        // After the record rather than beside it. These two rows are what you
        // read once you have decided this is the right show, and asking for
        // them together with the record would make the page wait on the slower
        // of two requests before drawing either.
        extras = withContext(Dispatchers.IO) { runCatching { AniList.extras(initial.id) }.getOrNull() }
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
                    // Always here, not only when automatic selection is off.
                    // Picking a source for one episode — because the automatic
                    // choice was dubbed, or would not resolve — should not need
                    // a global setting changed first.
                    TextButton(onClick = { onSources(anime, next) }) {
                        Text("Sources", color = DetailMuted)
                    }
                    TextButton(onClick = { editing = !editing }) {
                        Text(if (editing) "Close" else "Edit list entry", color = DetailMuted)
                    }
                }

                if (editing) {
                    ListEntryEditor(
                        anime = anime,
                        onSaved = { updated ->
                            // Shown from what was sent rather than fetched
                            // again. AniList takes a moment to reflect a write,
                            // and re-reading immediately gives back the old
                            // value — which reads as the save having failed.
                            anime = updated
                            onChanged()
                        }
                    )
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
                // Subscribes these chips to download progress, so an episode
                // shows as downloading and then downloaded while you watch it
                // rather than on the next visit to this page.
                Downloader.revision.value

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

        val side = extras
        if (side != null) {
            // Grouped by how they relate — a sequel and a side story are
            // different answers to "what next", and one undifferentiated row
            // would make you open each to find out which is which.
            side.relations
                .groupBy { it.type }
                .forEach { (type, group) ->
                    Shelf(
                        title = type.replaceFirstChar { it.uppercase() },
                        list = group.map { it.anime },
                        onOpen = onOpenOther
                    )
                    Box(Modifier.height(18.dp))
                }

            if (side.recommended.isNotEmpty()) {
                Shelf("People who liked this", side.recommended, onOpenOther)
                Box(Modifier.height(28.dp))
            }
        }
    }
}

private val DetailPaper = androidx.compose.ui.graphics.Color(0xFFF1EEF8)
private val DetailMuted = androidx.compose.ui.graphics.Color(0xFF948CAB)
private val DetailViolet = androidx.compose.ui.graphics.Color(0xFF8B5CF6)
private val DetailEdge = androidx.compose.ui.graphics.Color(0xFF2A2340)

private val STATUSES = listOf(
    "CURRENT" to "Watching",
    "PLANNING" to "Plan to watch",
    "COMPLETED" to "Completed",
    "PAUSED" to "Paused",
    "DROPPED" to "Dropped",
    "REPEATING" to "Rewatching"
)

/**
 * The AniList list entry: where you are, and what the show is to you.
 *
 * Progress moves on its own as episodes are watched, but not everything is
 * watched here — a couple of episodes on a television, or a season finished
 * before this existed, and the number is wrong with no way to say so. Status
 * has never been settable from this build at all.
 *
 * Status applies immediately, because it is one press and one meaning. Progress
 * and score are collected and saved together: nudging a number ten times should
 * not be ten writes to somebody's account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListEntryEditor(anime: Anime, onSaved: (Anime) -> Unit) {
    val scope = rememberCoroutineScope()
    var progress by remember(anime.id, anime.progress) { mutableStateOf(anime.progress) }
    var score by remember(anime.id, anime.score) { mutableStateOf(anime.score) }
    var note by remember(anime.id) { mutableStateOf<String?>(null) }
    var saving by remember(anime.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(DetailEdge, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Status", color = DetailMuted, style = MaterialTheme.typography.bodySmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            STATUSES.forEach { (value, label) ->
                val on = anime.listStatus == value
                TextButton(
                    onClick = {
                        scope.launch {
                            saving = true
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { AniList.saveEntry(anime.id, status = value) }.isSuccess
                            }
                            saving = false
                            note = if (ok) "Set to $label." else "AniList would not take that."
                            if (ok) onSaved(anime.copy(listStatus = value))
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (on) DetailViolet else androidx.compose.ui.graphics.Color.Transparent
                    )
                ) {
                    Text(
                        label,
                        color = if (on) DetailPaper else DetailMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text(
            "Episodes watched" + (anime.episodes?.let { " — of $it" } ?: ""),
            color = DetailMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (progress > 0) progress-- }) {
                Text("−", color = DetailPaper, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                progress.toString(),
                color = DetailPaper,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(40.dp)
            )
            TextButton(onClick = {
                // The show's own count is the ceiling where AniList knows it,
                // since a progress past the end is refused anyway.
                if (progress < (anime.episodes ?: Int.MAX_VALUE)) progress++
            }) {
                Text("+", color = DetailPaper, style = MaterialTheme.typography.titleMedium)
            }

            Box(Modifier.width(20.dp))

            Text(
                "Score  ${if (score > 0) score.toInt().toString() else "—"}",
                color = DetailMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = score.toFloat(),
                onValueChange = { score = it.toDouble() },
                valueRange = 0f..10f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = DetailViolet,
                    activeTrackColor = DetailViolet
                ),
                modifier = Modifier.width(180.dp).padding(start = 10.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                AniList.saveEntry(anime.id, progress = progress, score = score)
                            }.isSuccess
                        }
                        saving = false
                        note = if (ok) "Saved." else "AniList would not take that."
                        if (ok) onSaved(anime.copy(progress = progress, score = score))
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }

            // Only where there is an entry to remove. A show never added has no
            // entry id, and asking AniList to delete nothing is an error rather
            // than a no-op.
            anime.entryId?.let { id ->
                TextButton(onClick = {
                    scope.launch {
                        saving = true
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { AniList.deleteEntry(id) }.isSuccess
                        }
                        saving = false
                        note = if (ok) "Removed from your list." else "Could not remove it."
                        if (ok) onSaved(anime.copy(listStatus = null, entryId = null, progress = 0))
                    }
                }) { Text("Remove from list", color = DetailMuted) }
            }

            note?.let {
                Text(
                    it,
                    color = DetailMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}
