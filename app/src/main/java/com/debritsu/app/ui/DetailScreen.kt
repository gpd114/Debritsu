package com.debritsu.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.debritsu.app.BuildConfig
import com.debritsu.app.data.*
import com.debritsu.app.player.PlayerActivity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AniList's API words are not the words people use. */
private val STATUS_LABELS = listOf(
    "CURRENT" to "Watching",
    "PLANNING" to "Plan to watch",
    "COMPLETED" to "Completed",
    "PAUSED" to "Paused",
    "DROPPED" to "Dropped",
    "REPEATING" to "Rewatching"
)

private fun statusLabel(raw: String?) =
    STATUS_LABELS.firstOrNull { it.first == raw }?.second ?: "Not on list"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(anilistId: Int, onBack: () -> Unit, onOpen: (Int) -> Unit = {}) {

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var anime by remember { mutableStateOf<Anime?>(null) }
    var selectedEpisode by remember { mutableStateOf(1) }
    var results by remember { mutableStateOf<List<Stremio.AddonResult>>(emptyList()) }
    // Whichever source automatic selection would have started, scored by the
    // very rules auto-play uses so the list and the automatic choice can never
    // disagree. Null when nothing clears the filters, which is also when
    // auto-play gives up and hands the list over.
    val bestStream = remember(results) {
        val filter = Settings.sourceFilter
        results.flatMap { it.streams }
            .map { it to StreamMeta.of(it) }
            .filter { (s, m) -> filter.accepts(s, m, minEpisodeSizeMb(anime?.durationMins ?: 0)) }
            .maxByOrNull { (s, m) -> filter.score(s, m) }
            ?.first
    }
    // That source floated to the top; everything else keeps the order its addon
    // returned it in, so the list is not reshuffled beyond the one promotion.
    val streams = remember(results, bestStream) {
        val flat = results.flatMap { it.streams }
        if (bestStream == null) flat
        else listOf(bestStream) + flat.filterNot { it === bestStream }
    }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    // Resolving a debrid link can take a few seconds with nothing on screen to
    // show for it, which reads as a dead tap.
    var resolving by remember { mutableStateOf(false) }
    // Non-null while auto-play is working, and names what it is doing.
    var autoStep by remember { mutableStateOf<AutoPlay.Step?>(null) }
    // Held so the whole run can be abandoned: a search plus several debrid
    // resolves is long enough that being unable to back out would be rude.
    var autoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var subtitles by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    // Bumped on return from the player so resume bars redraw.
    var progressTick by remember { mutableStateOf(0) }
    var relations by remember { mutableStateOf<List<Relation>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var listed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var epMeta by remember { mutableStateOf<Map<Int, Jikan.EpisodeMeta>>(emptyMap()) }
    var showListEditor by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                progressTick++
                // Auto-play leaves its card up while the player starts, so that
                // the hand-off isn't a gap with nothing on screen. Coming back
                // here is what retires it.
                autoStep = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(anilistId, progressTick) {
        anime = runCatching { AniList.media(anilistId) }.getOrNull()
        selectedEpisode = ((anime?.progress ?: 0) + 1).coerceAtLeast(1)
    }
    LaunchedEffect(anilistId) {
        // Both rows come out of one request rather than two, and this one is
        // deliberately not keyed on progressTick: the relations and
        // recommendations of a show do not change while you watch an episode
        // of it, and refetching them on every return would cost a request for
        // nothing.
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
    // Wait for the title: calling the mapper without it would cache a
    // kitsu-less result that findStreams() would then reuse.
    LaunchedEffect(anime?.id) {
        val a = anime ?: return@LaunchedEffect
        val mal = runCatching { Mappings.forAniList(a.id, a.title).mal?.toIntOrNull() }.getOrNull()
        epMeta = runCatching { Jikan.episodes(mal) }.getOrDefault(emptyMap())
    }

    /**
     * Sources are passed explicitly rather than read from state: auto-play sets
     * results and launches in the same breath, so the value captured at
     * composition would be a step behind.
     */
    fun startPlayer(
        url: String,
        sources: List<StreamOption>,
        subs: List<Subtitle>,
        episode: Int,
        sourceIndex: Int
    ) {
        // Handed over in memory rather than through the Intent. Serialised, a
        // few hundred sources runs to hundreds of kilobytes and overruns the
        // Binder transaction limit, killing the app mid-launch with nothing
        // logged. Measured here so the size is visible if it ever matters again.
        if (BuildConfig.DEBUG) {
            val bytes = runCatching {
                json.encodeToString(ListSerializer(StreamOption.serializer()), sources).length
            }.getOrDefault(-1)
            android.util.Log.d(
                "DebritsuFilter",
                "handing over ${sources.size} sources (${bytes} chars if serialised)"
            )
        }
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

    /** Ask the addons and show the list, leaving the choice to the user. */
    fun manualSearch(episode: Int) {
        scope.launch {
            searching = true
            status = null
            results = emptyList()
            showSheet = true
            val ids = Mappings.forAniList(anilistId, anime?.title)
            val movie = (anime?.episodes ?: 1) <= 1
            val target = Stremio.contentId(ids, episode, movie)
            if (target == null) {
                status = "Couldn't map this title to a Kitsu or IMDb ID — the addons " +
                    "index by those, so there is nothing to ask for. Very new shows " +
                    "often aren't in the mapping tables yet."
            } else {
                results = Stremio.streams(target.first, target.second)
                subtitles = Stremio.subtitles(Stremio.contentIds(ids, episode, movie))
                if (results.none { it.streams.isNotEmpty() }) {
                    // Show what each addon actually said rather than a blanket failure.
                    status = results.joinToString("\n") { r ->
                        "${r.addon}: ${r.error ?: "no streams"}"
                    }.ifEmpty { "No addons configured." }
                }
            }
            searching = false
        }
    }

    /** Find, filter and start the best match, narrating each step. */
    fun autoPlayEpisode(episode: Int) {
        autoJob = scope.launch {
            status = null
            results = emptyList()
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
                // The card stays up across the hand-off and is cleared on
                // resume instead. Dismissing it here dropped the user back on
                // the detail screen for however long the activity took to
                // start, with nothing to say anything was happening.
                startPlayer(
                    url, all, outcome.subtitles, episode,
                    all.indexOf(outcome.chosen)
                )
            } else {
                autoStep = null
                // Hand over rather than quietly playing something the filters
                // were set up to keep out.
                status = outcome.message
                showSheet = true
            }
        }
    }

    fun findStreams(episode: Int) {
        // Already on disk? Play it locally and never touch the network.
        val offline = Downloads.get(anilistId, episode)?.takeIf { Downloads.isComplete(it) }
        if (offline != null) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(
                        PlayerActivity.EXTRA_URL,
                        android.net.Uri.fromFile(Downloads.fileFor(offline)).toString()
                    )
                    .putExtra(PlayerActivity.EXTRA_TITLE, "${anime?.title} — EP $episode")
                    .putExtra(PlayerActivity.EXTRA_SERIES_TITLE, anime?.title.orEmpty())
                    .putExtra(PlayerActivity.EXTRA_EPISODE_COUNT, anime?.episodes ?: 0)
                .putExtra(PlayerActivity.EXTRA_EPISODE_MINUTES, anime?.durationMins ?: 0)
                    .putExtra(PlayerActivity.EXTRA_ANILIST_ID, anilistId)
                    .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
            )
            return
        }
        if (Settings.autoPlay) {
            autoPlayEpisode(episode)
        } else {
            manualSearch(episode)
        }
    }

    fun download(stream: StreamOption) {
        val a = anime ?: return
        scope.launch {
            status = "Preparing download…"
            runCatching { Debrid.resolve(stream) }
                .onSuccess { url ->
                    // Cache the poster too, or the library would be blank offline.
                    val cover = withContext(Dispatchers.IO) {
                        Downloads.cacheCover(a.id, a.cover)
                    }
                    withContext(Dispatchers.IO) {
                        Downloads.enqueue(url, a, selectedEpisode, stream.name, cover)
                    }
                    status = "Downloading episode $selectedEpisode — see Downloads"
                }
                .onFailure { status = it.message ?: "Could not start that download." }
        }
    }

    fun play(stream: StreamOption) {
        scope.launch {
            status = null
            resolving = true
            runCatching { Debrid.resolve(stream) }
                .also { resolving = false }
                .onSuccess { url ->
                    showSheet = false
                    status = null
                    // Subtitles carried on the stream itself take priority over
                    // whatever the subtitle addons returned.
                    val subs = (stream.subtitles + subtitles).distinctBy { it.url }
                    startPlayer(url, streams, subs, selectedEpisode, streams.indexOf(stream))
                }
                .onFailure { status = it.message ?: "Could not resolve that stream." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // No title up here. The banner carries it a few pixels below,
                // in full and at a size worth reading, while this one had a
                // single line and a back button's width taken out of it — so it
                // truncated the long titles that most need showing, to repeat
                // something already on screen.
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                // 150dp: cropped, but only a little, and drawn at its own size.
                //
                // The band's height decides how much of a banner is lost,
                // because cropping to cover scales by whichever side needs it
                // more, and for a 1900x400 banner in a band this wide that is
                // always the height. On a 411dp screen:
                //
                //   220dp  39% of the width shows, magnified 1.45x and soft
                //   150dp  58% shows, at 1.0x — the 400px artwork almost
                //          exactly fills 393px of band, so nothing is scaled
                //    86dp  all of it, but the band is a thin strip
                //
                // So this is not really a compromise between the first and the
                // last: it beats 220dp on both counts, showing half again as
                // much of the picture and sharply rather than blown up. It only
                // gives up height.
                //
                // Showing all of it inside a full 220dp band was tried — the
                // banner laid sharp over a blurred enlargement of itself to
                // fill the space around it — and looked muddy.
                Box(Modifier.fillMaxWidth().height(150.dp)) {
                    AsyncImage(
                        model = anime?.banner ?: anime?.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Fade the banner into the page so nothing has a hard edge.
                    Box(Modifier.fillMaxSize().background(Ink.Dusk))
                    Text(
                        anime?.title ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        listOfNotNull(
                            anime?.format,
                            anime?.episodes?.let { "${it.toString().padStart(2, '0')} EP" },
                            anime?.durationMins?.let { "${it}m" },
                            anime?.seasonLabel,
                            anime?.airingStatus
                        ).joinToString("  ·  ").uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Mist
                    )

                    // Score first — it is the thing that decides whether to bother.
                    anime?.averageScore?.let { avg ->
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$avg%",
                                style = MaterialTheme.typography.titleLarge,
                                color = when {
                                    avg >= 80 -> Ink.Iris
                                    avg >= 65 -> Ink.Bone
                                    else -> Ink.Mist
                                }
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "AVERAGE SCORE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Mist
                                )
                                Text(
                                    listOfNotNull(
                                        anime?.popularity?.let { "#$it BY POPULARITY" },
                                        anime?.favourites?.takeIf { it > 0 }?.let { "$it ♥" }
                                    ).joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Mist
                                )
                            }
                        }
                    }

                    if (!anime?.genres.isNullOrEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            anime?.genres?.take(5)?.forEach { g ->
                                Surface(color = Ink.Veil, shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        g,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Ink.Mist,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                    anime?.studio?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "STUDIO · ${it.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.Mist
                        )
                    }
                    if (Settings.aniListToken.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            onClick = { showListEditor = true },
                            color = Ink.Veil,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    statusLabel(anime?.listStatus),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (anime?.listStatus != null) Ink.Iris else Ink.Mist
                                )
                                if ((anime?.score ?: 0.0) > 0) {
                                    Text(
                                        "  ·  ${anime?.score?.toInt()}/10",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Ink.Mist
                                    )
                                }
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit list entry",
                                    tint = Ink.Mist,
                                    modifier = Modifier.padding(start = 8.dp).size(15.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { findStreams(selectedEpisode) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            val resumeFrac = remember(selectedEpisode, progressTick) {
                                Progress.fraction(anilistId, selectedEpisode)
                            }
                            Text(
                                if (resumeFrac > 0f)
                                    "Resume episode ${selectedEpisode.toString().padStart(2, '0')} · ${(resumeFrac * 100).toInt()}%"
                                else
                                    "Play episode ${selectedEpisode.toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        // The source list is the only place an episode can be
                        // downloaded, and automatic selection walks straight
                        // past it — which left downloading unreachable for
                        // anyone on the default settings. This always opens it,
                        // whatever auto-play is set to.
                        OutlinedButton(
                            onClick = { manualSearch(selectedEpisode) },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(50.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Choose a source or download"
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        anime?.description?.take(400) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist
                    )
                }
            }
            item {
                Text(
                    "Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp)
                )
            }
            // Nothing at all until the metadata arrives, rather than one
            // invented episode.
            //
            // Falling back to a count of 1 when the show had not loaded drew a
            // single episode button under an otherwise blank page, which reads
            // as a title that has one episode — a manga entry, say — rather
            // than a title that failed to load. Berserk's 25 episodes appeared
            // as exactly that, and were reported as manga, reasonably enough.
            if (anime == null) {
                item {
                    Text(
                        "Still loading — reopen if this stays empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            } else {
            item {
                val total = (anime?.episodes ?: 1).coerceAtLeast(1)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items((1..total).toList()) { ep ->
                        val selected = ep == selectedEpisode
                        val watched = ep <= (anime?.progress ?: 0)
                        val resume = remember(ep, progressTick) { Progress.fraction(anilistId, ep) }
                        val meta = epMeta[ep]
                        val skippable = meta?.filler == true || meta?.recap == true
                        Box(
                            Modifier
                                .width(58.dp)
                                .height(58.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) Ink.Iris else Ink.Veil)
                                .clickable { selectedEpisode = ep; findStreams(ep) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    ep.toString().padStart(2, '0'),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.onPrimary
                                        watched -> Ink.Mist
                                        else -> Ink.Bone
                                    }
                                )
                                if (skippable) {
                                    Text(
                                        if (meta?.filler == true) "FILLER" else "RECAP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else Ink.Orchid
                                    )
                                }
                            }
                            when {
                                // Part-watched wins over the watched dot: it is
                                // the more actionable state.
                                resume > 0f -> Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .fillMaxWidth()
                                        .height(3.dp)
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
                                        .padding(bottom = 7.dp)
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(Ink.Orchid)
                                )
                            }
                        }
                    }
                }
            }
            }
            epMeta[selectedEpisode]?.title?.let { epTitle ->
                item {
                    Text(
                        epTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    )
                }
            }
            if (relations.isNotEmpty()) {
                item {
                    Text(
                        "Related",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(relations) { rel ->
                            Column(
                                Modifier
                                    .width(104.dp)
                                    .clickable { onOpen(rel.anime.id) }
                            ) {
                                AsyncImage(
                                    model = rel.anime.cover,
                                    contentDescription = rel.anime.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Ink.Veil)
                                )
                                Text(
                                    rel.type.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Orchid,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                // Both lines reserved, so every card in this row
                                // is the same height. A shorter one lets a
                                // taller neighbour hang below it, and a
                                // downward press then finds that neighbour
                                // rather than whatever is under the row.
                                Text(
                                    rel.anime.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // What people who liked this went on to like. Distinct from
            // Related, which is the same story — sequels and side stories —
            // where this is somewhere else to go next.
            //
            // Anything already on the list is dropped, the same as on the home
            // shelf: being told to watch what you have already finished is no
            // more use here than there. The row simply goes away if that
            // leaves nothing, which is the honest outcome.
            val unseen = recommended.filter { it.id !in listed }
            if (unseen.isNotEmpty()) {
                item {
                    Text(
                        "Recommended",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(unseen) { rec ->
                            Column(
                                Modifier
                                    .width(104.dp)
                                    .clickable { onOpen(rec.id) }
                            ) {
                                AsyncImage(
                                    model = rec.cover,
                                    contentDescription = rec.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Ink.Veil)
                                )
                                // Where Related names the kind of relation, the
                                // useful thing here is whether it is any good.
                                Text(
                                    rec.averageScore?.let { "$it%" } ?: " ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Ink.Iris,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                // Both lines reserved, for the same reason as
                                // the row above: uneven cards break which one a
                                // downward press finds.
                                Text(
                                    rec.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    autoStep?.let { step ->
        Dialog(onDismissRequest = { autoJob?.cancel(); autoStep = null }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink.Veil)
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                AsyncImage(
                    model = anime?.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink.Edge)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    anime?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "EPISODE ${selectedEpisode.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    color = Ink.Iris,
                    trackColor = Ink.Edge,
                    modifier = Modifier.width(140.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stepLabel(step),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                stepDetail(step)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Mist,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { autoJob?.cancel(); autoStep = null }) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall, color = Ink.Mist)
                }
            }
        }
    }

    if (resolving) {
        // Not dismissable: the resolve is already in flight with the debrid
        // provider, and backing out here would leave it half-done.
        Dialog(onDismissRequest = { }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink.Veil)
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                AsyncImage(
                    model = anime?.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink.Edge)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    anime?.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "EPISODE ${selectedEpisode.toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    color = Ink.Iris,
                    trackColor = Ink.Edge,
                    modifier = Modifier.width(120.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Resolving link…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Ink.Veil
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Sources",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "EPISODE ${selectedEpisode.toString().padStart(2, '0')}" +
                        if (streams.isNotEmpty()) "  ·  ${streams.size} FOUND" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(10.dp))

                if (searching) {
                    LinearProgressIndicator(
                        color = Ink.Iris,
                        trackColor = Ink.Edge,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Orchid,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                streams.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { play(s) }
                            .padding(vertical = 12.dp)
                    ) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (s.isDirect) Ink.Iris else Ink.Edge)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                s.description.replace("\n", " ").take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Mist,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                s === bestStream -> "BEST"
                                s.isDirect -> "DIRECT"
                                else -> "DEBRID"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.isDirect || s === bestStream) Ink.Iris else Ink.Mist
                        )
                        IconButton(onClick = { download(s) }) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download for offline",
                                tint = Ink.Mist,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF221A36))
                }
            }
        }
    }

    if (showListEditor) {
        val statuses = STATUS_LABELS
        var pendingProgress by remember(anime?.progress) { mutableStateOf(anime?.progress ?: 0) }
        var pendingScore by remember(anime?.score) { mutableStateOf(anime?.score ?: 0.0) }

        ModalBottomSheet(
            onDismissRequest = { showListEditor = false },
            containerColor = Ink.Veil
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("List entry", style = MaterialTheme.typography.titleMedium)
                Text(
                    anime?.title?.uppercase()?.take(40) ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(14.dp))

                Text("Status", style = MaterialTheme.typography.bodySmall, color = Ink.Mist)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statuses.forEach { (value, label) ->
                        val on = anime?.listStatus == value
                        Surface(
                            onClick = {
                                scope.launch {
                                    runCatching { AniList.saveEntry(anilistId, status = value) }
                                    progressTick++
                                }
                            },
                            color = if (on) Ink.Iris else Ink.Edge,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else Ink.Bone,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Episodes watched — ${pendingProgress}${anime?.episodes?.let { " / $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (pendingProgress > 0) pendingProgress-- }) {
                        Icon(Icons.Default.Remove, contentDescription = "One fewer", tint = Ink.Bone)
                    }
                    Text(
                        pendingProgress.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = {
                        val max = anime?.episodes ?: Int.MAX_VALUE
                        if (pendingProgress < max) pendingProgress++
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "One more", tint = Ink.Bone)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Score — ${if (pendingScore > 0) pendingScore.toInt().toString() else "none"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist
                )
                Slider(
                    value = pendingScore.toFloat(),
                    onValueChange = { pendingScore = it.toDouble() },
                    valueRange = 0f..10f,
                    steps = 9
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    AniList.saveEntry(
                                        anilistId,
                                        progress = pendingProgress,
                                        score = pendingScore
                                    )
                                }
                                progressTick++
                                showListEditor = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save", style = MaterialTheme.typography.labelLarge) }

                    anime?.entryId?.let { id ->
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { AniList.deleteEntry(id) }
                                    progressTick++
                                    showListEditor = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Remove", color = Ink.Orchid) }
                    }
                }
            }
        }
    }
}

/** What auto-play is doing right now, in words. */
private fun stepLabel(step: AutoPlay.Step): String = when (step) {
    AutoPlay.Step.Locating -> "Finding this episode"
    AutoPlay.Step.Searching -> "Searching your addons"
    is AutoPlay.Step.Filtering ->
        if (step.kept == 0) "Nothing matched your filters"
        else "${step.kept} of ${step.found} sources match"
    is AutoPlay.Step.Resolving -> "Checking source ${step.attempt} of ${step.of}"
    AutoPlay.Step.Ready -> "Starting playback"
}

/** The source being tried, so a slow resolve names what it is waiting on. */
private fun stepDetail(step: AutoPlay.Step): String? = when (step) {
    is AutoPlay.Step.Resolving -> step.name.replace("\n", " ").take(70)
    else -> null
}
