package com.debritsu.app.ui.tv

import android.content.Intent
import kotlin.math.abs
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
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
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var recommended by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var listed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var epMeta by remember { mutableStateOf<Map<Int, Jikan.EpisodeMeta>>(emptyMap()) }

    LaunchedEffect(anilistId, progressTick) {
        anime = runCatching { AniList.media(anilistId) }.getOrNull()
        selectedEpisode = ((anime?.progress ?: 0) + 1).coerceAtLeast(1)
    }
    LaunchedEffect(anilistId) {
        // Both rows come out of one request rather than two, and this is
        // deliberately not keyed on progressTick: a show's relations and
        // recommendations do not change while you watch an episode of it, and
        // refetching them on every return would cost a request for nothing.
        val extras = runCatching { AniList.extras(anilistId) }.getOrNull()
        relations = extras?.relations.orEmpty()
        recommended = extras?.recommended.orEmpty()
    }
    // What is on the list does change — finishing something adds it — so this
    // half refreshes on return. It is answered from the session's cache unless
    // a write dropped it, so it is usually free.
    LaunchedEffect(anilistId, progressTick) {
        listed = runCatching { AniList.listedIds() }.getOrDefault(emptySet())
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
                .putExtra(PlayerActivity.EXTRA_EPISODE_MINUTES, anime?.durationMins ?: 0)
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
                filter = Settings.sourceFilter,
                episodeMinutes = anime?.durationMins ?: 0
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

    // Leave the list alone when the focused control is already on screen.
    //
    // This is what was carrying the title away. The lazy list positions a
    // focused item at a preferred spot rather than merely making sure it is
    // visible, so landing on the play button scrolled about 500px — past a hero
    // that was already fully in view. And because nothing in the hero takes
    // focus, there was no way to scroll back: the title, score and synopsis
    // were simply gone.
    //
    // Measured on the box: focus sat at y=316 with an 800px hero above it and
    // nothing else in the tree, so the list had moved with everything visible
    // and no reason to.
    val onlyScrollWhenNeeded = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val top = offset
                val bottom = offset + size
                return when {
                    // Already on screen, or too tall to ever fit: don't move.
                    top >= 0f && bottom <= containerSize -> 0f
                    top < 0f && bottom > containerSize -> 0f
                    // Otherwise travel the shorter of the two distances that
                    // would bring it in — the least movement that does the job.
                    abs(top) < abs(bottom - containerSize) -> top
                    else -> bottom - containerSize
                }
            }
        }
    }

    // A LazyColumn, not a Column with verticalScroll. Android's guidance for
    // television is explicit: the lazy layouts carry focus positioning of their
    // own, and nesting same-direction scrollables — rows inside a scrolling
    // column — is named as the thing not to do.
    CompositionLocalProvider(LocalBringIntoViewSpec provides onlyScrollWhenNeeded) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink.Base),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
        // A hero across the full width, the way a television expects, rather
        // than a poster beside a column of text. Laid out as a phone screen it
        // wasted two thirds of a 1920 panel and squeezed the synopsis into one
        // truncated line.
        // A constant height, not a minimum, and this is the whole reason the
        // top of the page kept disappearing.
        //
        // The show arrives about a second after the screen does. Until then the
        // hero holds almost nothing and is short; when the title, score, genres
        // and synopsis land it grows, everything below it moves down, and the
        // scroll container chases the control that already had focus — taking
        // the top of the page with it. A minimum height cannot prevent that,
        // because growing is precisely what a minimum allows.
        //
        // Fixed, the box is the same size empty or full, so nothing below it
        // moves and there is nothing to chase. 400dp: the panel is 1920x1080 at
        // 2x, so the viewport is 540dp, and the hero plus its spacing plus the
        // control row has to fit inside that with room to spare.
        Box(
            Modifier
                .fillMaxWidth()
                .height(460.dp)
                .clipToBounds()
                .background(Ink.Base)
        ) {
            // Two different shapes of picture doing the same job, so they are
            // not scaled the same way.
            //
            // A banner is about 1900x400 — nearly five times as wide as it is
            // tall — and this box is 1920x800. Cropping to cover took the
            // larger of the two scales, which is the vertical one, so the
            // banner was drawn 3800px wide at twice its own size and you saw
            // the middle half of it, blown up past its own resolution. That is
            // the zoom, and the softness with it.
            //
            // Filling the width instead scales it 1.01x: the whole banner, at
            // very nearly one pixel to one. It stands at the top of the box and
            // the page colour carries on beneath it.
            val banner = anime?.banner
            val hasBanner = banner != null
            if (banner != null) {
                AsyncImage(
                    model = banner,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopStart)
                )
            } else {
                // A cover is portrait, so there is no framing of it that is not
                // a crop. The phone has cropped one to this same wide ratio all
                // along and it reads as soft artwork rather than an absence.
                AsyncImage(
                    model = anime?.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Sideways, so the text has something to be read against. Light
            // enough to leave the artwork visible through it, and reaching far
            // enough right to cover the text column, which is wider than it was.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Ink.Base.copy(alpha = 0.88f),
                        0.5f to Ink.Base.copy(alpha = 0.6f),
                        0.78f to androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            )
            // Downward, and it has to end where the picture does or it draws a
            // line across it. A banner stops at 202dp of this 460dp box, so the
            // fade is finished by then and its bottom edge dissolves. A cover
            // fills the whole box, so the fade takes the full height instead —
            // finishing early there left a visible edge with bright artwork
            // still going on underneath it.
            Box(
                Modifier.fillMaxSize().background(
                    if (hasBanner) {
                        Brush.verticalGradient(
                            0.24f to androidx.compose.ui.graphics.Color.Transparent,
                            0.44f to Ink.Base
                        )
                    } else {
                        Brush.verticalGradient(
                            0.45f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to Ink.Base
                        )
                    }
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
                    // Two thirds rather than half. The poster starts at 685dp
                    // of the 960dp width, so there was 185dp of empty middle
                    // that the synopsis can have — and a wider line holds more
                    // of it for the same height.
                    .fillMaxWidth(0.66f)
                    .padding(start = OVERSCAN, end = 24.dp, top = OVERSCAN, bottom = 20.dp)
            ) {
                Text(
                    anime?.title ?: "…",
                    // A step down from displaySmall. At 36sp a long title ate
                    // the space the rest of the block needed and still did not
                    // finish; smaller, three lines fit in less room than two
                    // used to take.
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink.Bone,
                    maxLines = 3,
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
                        synopsis(it),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.Mist,
                        // Whatever room is left, rather than a fixed count of
                        // lines. A fixed six cut a synopsis short on a show
                        // whose title took one line and left the space below it
                        // empty; weighted, a short title hands its spare height
                        // to the description and a three-line one takes it back.
                        //
                        // It is still not unlimited. The block and the buttons
                        // below it have to stay inside the 540dp the screen has,
                        // or focusing play scrolls the title off the top — the
                        // very problem this screen has just come out of.
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

            }
        }
        }

        // Controls sit below the hero at full width. Inside the text
        // column they were constrained to just over half the screen,
        // which four buttons and a five-way status row overflowed.
        item {
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
        }

        // Setting the list status, which the phone screen has and this
        // did not: no way to mark something watching, completed or
        // dropped without reaching for another device.
        item {
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
        }

        // The hero runs edge to edge, so everything below it carries the
        // overscan margin itself rather than inheriting one from the column.
        item {
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
            color = Ink.Bone,
            modifier = Modifier.padding(start = OVERSCAN)
        )
        // Nothing at all until the metadata arrives, rather than one invented
        // episode. Falling back to a count of 1 when the show had not loaded
        // drew a single episode button under an otherwise blank page, which
        // reads as a title with one episode — a manga entry, say — instead of
        // a title that failed to load.
        if (anime == null) {
            Text(
                "Still loading — reopen if this stays empty.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Mist,
                modifier = Modifier.padding(start = OVERSCAN, top = 6.dp)
            )
        } else {
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
        }
        }

        item {
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
        }

        // What people who liked this went on to like. Distinct from Related,
        // which is the same story — sequels and side stories — where this is
        // somewhere else to go next.
        //
        // Anything already on the list is dropped, the same as on the home
        // shelf: being told to watch what you have already finished is no more
        // use here than there. The row simply goes away if that leaves
        // nothing, which is the honest outcome.
        item {
        val unseen = recommended.filter { it.id !in listed }
        if (unseen.isNotEmpty()) {
            Text(
                "Recommended",
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Bone,
                modifier = Modifier.padding(start = OVERSCAN)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp)
            ) {
                items(unseen) { rec ->
                    Column(Modifier.width(140.dp)) {
                        Card(onClick = { onOpen(rec.id) }) {
                            AsyncImage(
                                model = rec.cover,
                                contentDescription = rec.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .background(Ink.Veil)
                            )
                        }
                        // Where Related names the kind of relation, the useful
                        // thing here is whether it is any good.
                        Text(
                            rec.averageScore?.let { "$it%" } ?: " ",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.Iris,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            rec.title,
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
        }

        item {
        autoStep?.let { step ->
            Text(
                stepLabel(step),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Orchid,
                modifier = Modifier.padding(horizontal = OVERSCAN)
            )
        }
        }
        item {
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Orchid,
                modifier = Modifier.padding(horizontal = OVERSCAN)
            )
        }
        }

        item {
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
    }
}

/**
 * An AniList description, made fit to read on a television.
 *
 * They arrive as HTML: `<br>` between paragraphs, escaped entities, and very
 * often a "(Source: Crunchyroll News)" credit on the end. Stripping the tags
 * and nothing else left the blank lines behind, and on a block six lines tall
 * that cost half the space to say nothing — one show spent three of its six
 * lines on an empty gap and a credit.
 *
 * So it all becomes a single paragraph, and the credit goes.
 */
private fun synopsis(raw: String): String =
    raw.replace(Regex("<[^>]*>"), " ")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\(Source:[^)]*\\)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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
