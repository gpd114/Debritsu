package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.SourceFilter
import com.debritsu.app.data.StreamMeta
import com.debritsu.app.data.StreamOption

private val SrcPanel = Color(0xFF1E1830)
private val SrcMuted = Color(0xFF948CAB)
private val SrcWarn = Color(0xFFE29075)
private val SrcKeep = Color(0xFF6FC79B)
private val SrcAccent = Color(0xFFB79BF7)

/** The row for what is on screen: the panel colour lifted towards the accent. */
private val SrcPlaying = Color(0xFF2F2350)

/**
 * Sources in the order they should be read: everything that meets the filters
 * first, best of them at the top, then the rest — also ranked, so near misses
 * come before hopeless ones.
 *
 * Score alone was not enough and was actively wrong: a 4K source scores well
 * and is rejected by a 1080p ceiling, so rejected sources floated above
 * accepted ones and the list had to be read rather than glanced at.
 *
 * Shared with the player's own source list, so both orders agree — a list that
 * ranks differently depending on where it was opened from is worse than one
 * that does not rank at all.
 */
fun rankSources(
    streams: List<StreamOption>,
    episodeMinutes: Int
): List<Pair<StreamOption, StreamMeta>> {
    val filter = com.debritsu.app.data.Settings.sourceFilter
    val minSize = com.debritsu.app.data.minEpisodeSizeMb(episodeMinutes)
    return streams
        .map { it to StreamMeta.of(it) }
        .sortedWith(
            compareByDescending<Pair<StreamOption, StreamMeta>> { (s, m) ->
                filter.accepts(s, m, minSize)
            }.thenByDescending { (s, m) -> filter.score(s, m) }
        )
}

/**
 * Every source an addon offered, for choosing by hand.
 *
 * What each one actually is has to be read out of free text the addon author
 * wrote however they liked, so the parsed values are shown beside the raw name
 * rather than instead of it — when the parsing is wrong, which it sometimes is,
 * the name is the only way to see that.
 */
@Composable
fun SourcesScreen(
    state: Watch.State.Choose,
    onBack: () -> Unit,
    /** Absent where there is nothing to file a download under. */
    onDownload: ((StreamOption) -> Unit)? = null,
    onPick: (StreamOption) -> Unit
) {
    val streams = state.outcome.results.flatMap { it.streams }
    val filter = com.debritsu.app.data.Settings.sourceFilter
    val minSize = com.debritsu.app.data.minEpisodeSizeMb(state.episodeMinutes)
    val ranked = rankSources(streams, state.episodeMinutes)

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Sources", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${state.title} — episode ${state.episode}  ·  ${streams.size} found",
                    color = SrcMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onBack) { Text("← Back", color = SrcMuted) }
        }

        if (state.outcome.results.any { it.error != null }) {
            Text(
                state.outcome.results.filter { it.error != null }
                    .joinToString("  ·  ") { "${it.addon}: ${it.error}" },
                color = SrcWarn,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ranked) { (stream, meta) ->
                SourceRow(
                    stream = stream,
                    meta = meta,
                    filter = filter,
                    minSizeMb = minSize,
                    onDownload = onDownload?.let { { it(stream) } }
                ) { onPick(stream) }
            }
        }
    }
}

@Composable
internal fun SourceRow(
    stream: StreamOption,
    meta: StreamMeta,
    filter: SourceFilter,
    minSizeMb: Int,
    /** Marked when this is the release already on screen. */
    playing: Boolean = false,
    /** Offered where there is something to file the download under. */
    onDownload: (() -> Unit)? = null,
    onPick: () -> Unit
) {
    val accepted = filter.accepts(stream, meta, minSizeMb)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (playing) SrcPlaying else SrcPanel)
            .clickable(onClick = onPick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (playing) {
            Text(
                "▶  NOW PLAYING",
                color = SrcAccent,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stream.name.lineSequence().joinToString(" ").trim(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            // Beside the name rather than under the row, so it reads as a
            // second thing you can do with this source rather than as a
            // decision about the list.
            onDownload?.let {
                TextButton(onClick = it) {
                    Text("Download", color = SrcKeep, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text(
            buildString {
                meta.resolution?.let { append("${it}p") } ?: append("resolution unknown")
                meta.sizeMb?.let { append("  ·  ${it} MB") }
                meta.packSizeMb?.let { append(" of ${it} MB") }
                when (meta.cached) {
                    true -> append("  ·  cached")
                    false -> append("  ·  NOT cached")
                    null -> Unit
                }
                if (meta.isPack) append("  ·  pack")
                if (meta.declaresEnglish) append("  ·  English")
                meta.seeders?.let { append("  ·  $it seeders") }
            },
            color = when {
                meta.cached == false -> SrcWarn
                accepted -> SrcKeep
                else -> SrcMuted
            },
            style = MaterialTheme.typography.bodySmall
        )

        if (!accepted) {
            // Said plainly rather than by hiding the row. The filters exist to
            // choose automatically; overriding one by hand is a fair thing to
            // want, and an uncached source in particular is a decision about
            // somebody's debrid account rather than about quality.
            Text(
                if (meta.cached == false)
                    "Playing this starts a download on your ${
                        com.debritsu.app.data.Settings.debridProvider.label
                    } account."
                else "Outside your filters.",
                color = SrcWarn,
                style = MaterialTheme.typography.bodySmall
            )
        }

        stream.description.takeIf { it.isNotBlank() }?.let {
            Text(
                it.lineSequence().joinToString(" ").trim().take(160),
                color = SrcMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
