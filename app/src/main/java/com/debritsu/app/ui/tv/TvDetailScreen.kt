package com.debritsu.app.ui.tv

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Progress
import com.debritsu.app.data.SourceHandoff
import com.debritsu.app.data.Settings
import com.debritsu.app.data.Stremio
import com.debritsu.app.data.StreamOption
import com.debritsu.app.data.Subtitle
import com.debritsu.app.player.PlayerActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.debritsu.app.ui.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One show: what it is, which episode, and getting it playing.
 *
 * The work is the phone screen's, unchanged — the same mapping, the same
 * automatic selection, the same hand-off through [SourceHandoff] rather than an
 * Intent. Only the arrangement is different, and the controls are ones a remote
 * can reach.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDetailScreen(
    anilistId: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var anime by remember { mutableStateOf<Anime?>(null) }
    var selectedEpisode by remember { mutableStateOf(1) }
    var results by remember { mutableStateOf<List<Stremio.AddonResult>>(emptyList()) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    var autoStep by remember { mutableStateOf<AutoPlay.Step?>(null) }
    var autoJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showSources by remember { mutableStateOf(false) }

    // Bumped on every return to this screen. Without it nothing is re-read
    // after watching: the resume percentage stays as it was at first
    // composition and the next episode never advances, which looks exactly
    // like progress not being saved when in fact it is.
    var progressTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) progressTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(anilistId, progressTick) {
        anime = runCatching { AniList.media(anilistId) }.getOrNull()
        selectedEpisode = ((anime?.progress ?: 0) + 1).coerceAtLeast(1)
    }

    fun startPlayer(
        url: String,
        sources: List<StreamOption>,
        subs: List<Subtitle>,
        episode: Int,
        sourceIndex: Int
    ) {
        // In memory rather than through the Intent: a few hundred sources with
        // long URLs overruns the Binder transaction limit and tears the app down
        // mid-launch with nothing logged.
        SourceHandoff.offer(sources)
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SUB_URLS, subs.map { it.url }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_SUB_LANGS, subs.map { it.lang }.toTypedArray())
                .putExtra(
                    PlayerActivity.EXTRA_SUB_ADDONS,
                    subs.map { it.addon.orEmpty() }.toTypedArray()
                )
                .putExtra(PlayerActivity.EXTRA_URL, url)
                .putExtra(PlayerActivity.EXTRA_TITLE, "${anime?.title} — EP $episode")
                .putExtra(PlayerActivity.EXTRA_SERIES_TITLE, anime?.title.orEmpty())
                .putExtra(PlayerActivity.EXTRA_EPISODE_COUNT, anime?.episodes ?: 0)
                .putExtra(PlayerActivity.EXTRA_ANILIST_ID, anilistId)
                .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                .putExtra(PlayerActivity.EXTRA_SOURCE_INDEX, sourceIndex)
        )
    }

    fun manualSearch(episode: Int) {
        scope.launch {
            status = "Searching your addons…"
            results = emptyList()
            showSources = true
            val ids = Mappings.forAniList(anilistId, anime?.title)
            val movie = (anime?.episodes ?: 1) <= 1
            val target = Stremio.contentId(ids, episode, movie)
            if (target == null) {
                status = "Couldn't map this title to a Kitsu or IMDb ID — the addons " +
                    "index by those, so there is nothing to ask for."
            } else {
                results = Stremio.streams(target.first, target.second)
                subtitles = Stremio.subtitles(Stremio.contentIds(ids, episode, movie))
                status = if (results.none { it.streams.isNotEmpty() }) {
                    results.joinToString("\n") { r -> "${r.addon}: ${r.error ?: "no streams"}" }
                        .ifEmpty { "No addons configured." }
                } else null
            }
        }
    }

    fun play(episode: Int) {
        // No offline check: this build has no way to make a download, so it
        // could never find one. Downloads are for a phone away from the house,
        // and these boxes have barely any storage to spend on them.
        if (!Settings.autoPlay) {
            manualSearch(episode)
            return
        }
        autoJob = scope.launch {
            status = null
            autoStep = AutoPlay.Step.Locating
            val outcome = AutoPlay.run(
                anilistId = anilistId,
                title = anime?.title,
                episode = episode,
                isMovie = (anime?.episodes ?: 1) <= 1,
                filter = Settings.sourceFilter
            ) { autoStep = it }

            results = outcome.results
            subtitles = outcome.subtitles
            val url = outcome.url
            if (url != null) {
                val all = outcome.results.flatMap { it.streams }
                startPlayer(url, all, outcome.subtitles, episode, all.indexOf(outcome.chosen))
                autoStep = null
            } else {
                // Hand over rather than quietly playing something the filters
                // were set up to keep out.
                autoStep = null
                status = outcome.message
                showSources = true
            }
        }
    }

    val total = (anime?.episodes ?: 1).coerceAtLeast(1)
    val resumeFrac = remember(selectedEpisode, progressTick) {
        Progress.fraction(anilistId, selectedEpisode)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
            .verticalScroll(rememberScrollState())
            .padding(OVERSCAN),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Artwork beside the text rather than behind it: a banner washed under
        // a title is the usual television treatment and it makes both harder to
        // read across a room.
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AsyncImage(
                model = anime?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(220.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink.Veil)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(
                    anime?.title ?: "…",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink.Bone,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // The line of facts worth knowing before starting something.
                val facts = listOfNotNull(
                    anime?.averageScore?.let { "$it%" },
                    anime?.format,
                    anime?.seasonLabel,
                    anime?.episodes?.let { "$it episodes" },
                    anime?.studio
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Orchid
                    )
                }
                anime?.genres?.take(5)?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist
                    )
                }
                anime?.description?.let {
                    Text(
                        it.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").take(400),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Mist,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { play(selectedEpisode) }) {
                Text(
                    if (resumeFrac > 0f)
                        "Resume episode ${selectedEpisode.toString().padStart(2, '0')} · " +
                            "${(resumeFrac * 100).toInt()}%"
                    else "Play episode ${selectedEpisode.toString().padStart(2, '0')}"
                )
            }
            Button(onClick = { manualSearch(selectedEpisode) }) { Text("Choose source") }
            Button(onClick = onBack) { Text("Back") }
        }

        Text("Episodes", style = MaterialTheme.typography.titleMedium, color = Ink.Bone)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items((1..total).toList()) { ep ->
                // Fixed size, so no episode ever hangs below its neighbours and
                // a downward press cannot find one along the row instead of
                // leaving it.
                Card(onClick = { selectedEpisode = ep; play(ep) }) {
                    Box(
                        Modifier.size(96.dp).background(
                            if (ep == selectedEpisode) Ink.Iris else Ink.Veil
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            ep.toString().padStart(2, '0'),
                            style = MaterialTheme.typography.titleMedium,
                            color = Ink.Bone
                        )
                    }
                }
            }
        }

        autoStep?.let { step ->
            Text(
                stepLabel(step),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Orchid
            )
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Orchid)
        }

        if (showSources) {
            val streams = results.flatMap { it.streams }
            Text(
                "Sources · ${streams.size} found",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Bone
            )
            streams.take(40).forEach { s ->
                Button(
                    onClick = {
                        scope.launch {
                            status = "Resolving…"
                            val url = runCatching { Debrid.resolve(s) }.getOrNull()
                            status = if (url == null) "That source wouldn't resolve." else null
                            if (url != null) {
                                startPlayer(
                                    url, streams, subtitles, selectedEpisode, streams.indexOf(s)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        s.name.replace("\n", " ").take(90),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun stepLabel(step: AutoPlay.Step): String = when (step) {
    AutoPlay.Step.Locating -> "Finding this episode…"
    AutoPlay.Step.Searching -> "Searching your addons…"
    is AutoPlay.Step.Filtering ->
        if (step.kept == 0) "Nothing matched your filters"
        else "${step.kept} of ${step.found} sources match"
    is AutoPlay.Step.Resolving -> "Checking source ${step.attempt} of ${step.of}…"
    AutoPlay.Step.Ready -> "Starting playback"
}
