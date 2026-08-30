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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.AutoPlay
import com.debritsu.app.data.Debrid
import com.debritsu.app.data.Jikan
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Relation
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
    onBack: () -> Unit,
    onOpen: (Int) -> Unit
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
    var showStatus by remember { mutableStateOf(false) }

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

    var relations by remember { mutableStateOf<List<Relation>>(emptyList()) }
    var epMeta by remember { mutableStateOf<Map<Int, Jikan.EpisodeMeta>>(emptyMap()) }

    LaunchedEffect(anilistId, progressTick) {
        anime = runCatching { AniList.media(anilistId) }.getOrNull()
        selectedEpisode = ((anime?.progress ?: 0) + 1).coerceAtLeast(1)
    }
    LaunchedEffect(anilistId) {
        relations = runCatching { AniList.relations(anilistId) }.getOrDefault(emptyList())
    }
    // Waits for the title: calling the mapper without one caches a kitsu-less
    // result that the stream lookup would then reuse.
    LaunchedEffect(anime?.id) {
        val a = anime ?: return@LaunchedEffect
        val mal = runCatching { Mappings.forAniList(a.id, a.title).mal?.toIntOrNull() }.getOrNull()
        epMeta = runCatching { Jikan.episodes(mal) }.getOrDefault(emptyMap())
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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // A hero across the full width, the way a television expects, rather
        // than a poster beside a column of text. Laid out as a phone screen it
        // wasted two thirds of a 1920 panel and squeezed the synopsis into one
        // truncated line.
        // Sized so the hero and the controls beneath it both fit on screen at
        // once. The panel is 1920x1080 at 2x, so the viewport is 540dp; at 470
        // the hero plus its spacing plus the control row came to exactly that,
        // and focusing a control scrolled the page just enough to carry the
        // title off the top. 400 leaves about 70dp of headroom, so nothing has
        // to move.
        //
        // The title going missing was never clipping — it was this scroll.
        Box(Modifier.fillMaxWidth().heightIn(min = 400.dp).background(Ink.Veil)) {
            // Banner only. Falling back to the cover put a portrait image in a
            // box four times as wide, so the crop threw away everything but a
            // meaningless middle strip. Where there is no banner the artwork is
            // the poster on the right instead, at its own shape.
            anime?.banner?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Two scrims: sideways so the text has something solid behind it,
            // and downward so the artwork meets the rows below without an edge.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Ink.Base,
                        0.45f to Ink.Base.copy(alpha = 0.92f),
                        1f to Ink.Base.copy(alpha = 0.15f)
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.55f to androidx.compose.ui.graphics.Color.Transparent,
                        1f to Ink.Base
                    )
                )
            )

            // The poster, at its own two-to-three shape. Always present, so a
            // show without a banner still has artwork on screen.
            AsyncImage(
                model = anime?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = OVERSCAN)
                    .height(340.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink.Edge)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                // Anchored to the top, not the bottom. Bottom-anchored, a show
                // with more to say pushes its own title upward and out of the
                // box; top-anchored, the overflow goes downward where the box
                // simply grows to take it.
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.52f)
                    .padding(start = OVERSCAN, end = 24.dp, top = OVERSCAN, bottom = 20.dp)
            ) {
                Text(
                    anime?.title ?: "…",
                    style = MaterialTheme.typography.displaySmall,
                    color = Ink.Bone,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    listOfNotNull(
                        anime?.format,
                        anime?.episodes?.let { "${it.toString().padStart(2, '0')} EP" },
                        anime?.durationMins?.let { "${it}m" },
                        anime?.seasonLabel,
                        anime?.airingStatus
                    ).joinToString("  ·  ").uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink.Mist
                )

                // Score first — it is the thing that decides whether to bother.
                anime?.averageScore?.let { avg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$avg%",
                            style = MaterialTheme.typography.headlineMedium,
                            color = when {
                                avg >= 80 -> Ink.Iris
                                avg >= 65 -> Ink.Bone
                                else -> Ink.Mist
                            }
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "AVERAGE SCORE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Mist
                            )
                            Text(
                                listOfNotNull(
                                    anime?.popularity?.let { "#$it BY POPULARITY" },
                                    anime?.favourites?.takeIf { it > 0 }?.let { "$it FAVOURITES" }
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = Ink.Mist
                            )
                        }
                    }
                }

                anime?.genres?.take(4)?.takeIf { it.isNotEmpty() }?.let { genres ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.forEach { g ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Ink.Veil)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    g,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Mist
                                )
                            }
                        }
                    }
                }

                anime?.description?.let {
                    Text(
                        it.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.Mist,
                        // Two lines keeps the hero inside its minimum for most
                        // shows, so the height stays predictable.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            }
        }

        // Controls sit below the hero at full width. Inside the text
        // column they were constrained to just over half the screen,
        // which four buttons and a five-way status row overflowed.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = OVERSCAN)
        ) {
            // The primary action carries the app's colour rather than
            // the library's default, so it reads as the thing to press.
            Button(
                onClick = { play(selectedEpisode) },
                colors = ButtonDefaults.colors(
                    containerColor = Ink.Iris,
                    contentColor = Ink.Bone
                )
            ) {
                Text(
                    if (resumeFrac > 0f)
                        "Resume episode ${selectedEpisode.toString().padStart(2, '0')}  ·  " +
                            "${(resumeFrac * 100).toInt()}%"
                    else "Play episode ${selectedEpisode.toString().padStart(2, '0')}"
                )
            }
            Button(onClick = { manualSearch(selectedEpisode) }) { Text("Choose source") }
            if (Settings.aniListToken.isNotEmpty()) {
                Button(onClick = { showStatus = true }) {
                    Text(statusLabel(anime?.listStatus))
                }
            }
            Button(onClick = onBack) { Text("Back") }
        }

        // Setting the list status, which the phone screen has and this
        // did not: no way to mark something watching, completed or
        // dropped without reaching for another device.
        if (showStatus) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = OVERSCAN)
            ) {
                STATUS_CHOICES.forEach { (value, label) ->
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    AniList.saveEntry(anilistId, status = value)
                                }
                                showStatus = false
                                progressTick++
                            }
                        },
                        colors = if (anime?.listStatus == value)
                            ButtonDefaults.colors(
                                containerColor = Ink.Iris,
                                contentColor = Ink.Bone
                            )
                        else ButtonDefaults.colors()
                    ) { Text(label) }
                }
            }
        }

        // The hero runs edge to edge, so everything below it carries the
        // overscan margin itself rather than inheriting one from the column.
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Bone,
            modifier = Modifier.padding(start = OVERSCAN)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp)
        ) {
            items((1..total).toList()) { ep ->
                val watched = ep <= (anime?.progress ?: 0)
                val resume = remember(ep, progressTick) { Progress.fraction(anilistId, ep) }
                val meta = epMeta[ep]
                val skippable = meta?.filler == true || meta?.recap == true

                // Fixed size, so no episode ever hangs below its neighbours and
                // a downward press cannot find one along the row instead of
                // leaving it.
                val selected = ep == selectedEpisode
                Card(onClick = { selectedEpisode = ep; play(ep) }) {
                    Box(
                        Modifier.size(96.dp)
                            .background(if (selected) Ink.Iris else Ink.Veil),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                ep.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.titleMedium,
                                // Watched ones recede rather than shout.
                                color = when {
                                    selected -> Ink.Bone
                                    watched -> Ink.Mist
                                    else -> Ink.Bone
                                }
                            )
                            // Marked from MyAnimeList data, so you know what is
                            // safe to skip before starting it.
                            if (skippable) {
                                Text(
                                    if (meta?.filler == true) "FILLER" else "RECAP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Ink.Bone else Ink.Orchid
                                )
                            }
                        }
                        when {
                            // Part-watched wins over the watched dot: it is the
                            // more actionable state.
                            resume > 0f -> Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Ink.Edge)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(resume)
                                        .fillMaxHeight()
                                        .background(Ink.Orchid)
                                )
                            }
                            watched && !selected -> Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 11.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Ink.Orchid)
                            )
                        }
                    }
                }
            }
        }

        if (relations.isNotEmpty()) {
            Text(
                "Related",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Bone,
                modifier = Modifier.padding(start = OVERSCAN)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp)
            ) {
                items(relations) { rel ->
                    Column(Modifier.width(140.dp)) {
                        Card(onClick = { onOpen(rel.anime.id) }) {
                            AsyncImage(
                                model = rel.anime.cover,
                                contentDescription = rel.anime.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .background(Ink.Veil)
                            )
                        }
                        Text(
                            rel.type.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.Orchid,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // Both lines reserved, so every card in the row is the
                        // same height and a downward press leaves the row.
                        Text(
                            rel.anime.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Mist,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        autoStep?.let { step ->
            Text(
                stepLabel(step),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Orchid,
                modifier = Modifier.padding(horizontal = OVERSCAN)
            )
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Orchid,
                modifier = Modifier.padding(horizontal = OVERSCAN)
            )
        }

        if (showSources) {
            val streams = results.flatMap { it.streams }
            Text(
                "Sources · ${streams.size} found",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Bone,
                modifier = Modifier.padding(horizontal = OVERSCAN)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = OVERSCAN)
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

/** The AniList statuses worth setting from a remote, with their labels. */
private val STATUS_CHOICES = listOf(
    "CURRENT" to "Watching",
    "COMPLETED" to "Completed",
    "PAUSED" to "Paused",
    "DROPPED" to "Dropped",
    "PLANNING" to "Plan to watch"
)

private fun statusLabel(raw: String?): String =
    STATUS_CHOICES.firstOrNull { it.first == raw }?.second ?: "Not on list"

private fun stepLabel(step: AutoPlay.Step): String = when (step) {
    AutoPlay.Step.Locating -> "Finding this episode…"
    AutoPlay.Step.Searching -> "Searching your addons…"
    is AutoPlay.Step.Filtering ->
        if (step.kept == 0) "Nothing matched your filters"
        else "${step.kept} of ${step.found} sources match"
    is AutoPlay.Step.Resolving -> "Checking source ${step.attempt} of ${step.of}…"
    AutoPlay.Step.Ready -> "Starting playback"
}
