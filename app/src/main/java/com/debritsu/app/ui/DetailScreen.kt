package com.debritsu.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
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
fun DetailScreen(
    anilistId: Int,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit = {},
    // Start the next episode as soon as the show has loaded — how Resume on
    // the home screen gets from a card to playing.
    autoPlay: Boolean = false,
    // A show to draw instead of asking AniList for one. Only the debug-build
    // preview passes it, so the look can be checked with AniList unreachable;
    // the app itself never does.
    preview: Anime? = null
) {

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var anime by remember { mutableStateOf<Anime?>(null) }
    var selectedEpisode by remember { mutableStateOf(1) }

    /**
     * Whether the selection came from an episode's download half rather than
     * its number.
     *
     * Either half has to select the episode — the download is filed under
     * [selectedEpisode] — but selection used to colour the whole chip, so
     * pressing the arrow lit both halves and its own press was lost under it.
     * This says which half did it, and only that half lights.
     */
    var pickedByArrow by remember { mutableStateOf(false) }
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
    // Everything that meets the filters first, best of them at the top, then
    // everything else — also ranked, so the nearest misses come before the
    // hopeless ones.
    //
    // Only the single best was promoted before, and the rest kept the order
    // their addon returned them in. That reads as one good source followed by
    // noise: the second and third that also qualify are somewhere down the
    // list, and finding them means reading every row. Ordering the whole list
    // costs nothing and puts the choices worth making together.
    val streams = remember(results, anime) {
        val filter = Settings.sourceFilter
        val minSize = minEpisodeSizeMb(anime?.durationMins ?: 0)
        results.flatMap { it.streams }
            .map { it to StreamMeta.of(it) }
            .sortedWith(
                compareByDescending<Pair<StreamOption, StreamMeta>> { (s, m) ->
                    filter.accepts(s, m, minSize)
                }.thenByDescending { (s, m) -> filter.score(s, m) }
            )
            .map { it.first }
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

    /**
     * The episode grid's own scroll, so it can be put where you are.
     *
     * It always opened at episode one, which is fine for a season and useless
     * for a long-running show: episode 900 of One Piece was a very long scroll
     * away, every single time the page was opened. Jumped once when the record
     * lands, and never again — moving it under somebody who has started
     * scrolling would be worse than not moving it at all.
     */
    val episodeGrid = rememberLazyGridState()
    var jumpedToEpisode by remember(anilistId) { mutableStateOf(false) }
    LaunchedEffect(anilistId, anime, selectedEpisode) {
        if (jumpedToEpisode || anime == null) return@LaunchedEffect
        jumpedToEpisode = true
        // A row above where you are, so what is next has context above it
        // rather than sitting jammed against the top edge.
        runCatching { episodeGrid.scrollToItem((selectedEpisode - 5).coerceAtLeast(0)) }
    }
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
        anime = preview ?: runCatching { AniList.media(anilistId) }.getOrNull()
        // Where you left off, and never past the end.
        //
        // Progress plus one is the next episode to watch, which is right until
        // the show is finished: 25 of 25 watched made this 26, an episode that
        // does not exist. The grid only draws 1..25 so nothing was highlighted,
        // but the play button offered it and the addons were asked for it.
        // Finished, it settles on the last episode, which is what somebody
        // returning to a completed show is most likely reaching for.
        val known = anime?.episodes ?: 0
        selectedEpisode = ((anime?.progress ?: 0) + 1)
            .coerceAtLeast(1)
            .let { if (known > 0) it.coerceAtMost(known) else it }
        // Chosen here as the next to play, so it is the number that lights.
        pickedByArrow = false
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

    /**
     * Plays the copy on disk, if there is one, and says whether it did.
     *
     * Shared by the episode's number, which tries this before anything else,
     * and the source sheet, which offers it by name — so both start the local
     * file exactly the same way and cannot drift apart.
     */
    fun playDownloaded(episode: Int): Boolean {
        val offline = Downloads.get(anilistId, episode)
            ?.takeIf { Downloads.isComplete(it) }
            ?: return false
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
        return true
    }

    fun findStreams(episode: Int) {
        // Already on disk? Play it locally and never touch the network.
        if (playDownloaded(episode)) return
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

    // Opened by Resume on the home screen: play once the show has loaded, as
    // though its play button had been pressed. Once only — saved, so coming
    // back from the player does not set it off again.
    var autoPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(anime) {
        if (autoPlay && !autoPlayed && anime != null) {
            autoPlayed = true
            findStreams(selectedEpisode)
        }
    }

    // No app bar. The show's art runs across the top under the status bar with
    // a back button over it, and the title sits below it on the fade — in full,
    // at a size worth reading. An app bar title had a single line and a back
    // button's width taken out of it, so it truncated the long titles that
    // most need showing, to repeat something already on screen.
    Scaffold(containerColor = Color.Transparent) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())
        ) {
            item {
                // The art at close to its own shape rather than a band cut
                // across it. TVDB's fanart where ani.zip has it — 1920x1080, so
                // it shows nearly whole — else AniList's banner or cover.
                //
                // A band was the right call for the banner alone: cropping to
                // cover scales by whichever side needs it more, and for a
                // 1900x400 banner that is always the height, so every extra dp
                // of band cost width and sharpness (220dp showed 39% of it at
                // 1.45x). Fanart is the shape of a screen, and a banner at this
                // height is still only enlarged by about half — while the words
                // no longer sit on the picture at all but below it.
                Box {
                    WideArt(anime?.let { rememberArt(it) }, Modifier.fillMaxWidth().aspectRatio(1.6f))
                    OverlayIconButton(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "Back",
                        onBack,
                        Modifier.statusBarsPadding().padding(start = 12.dp, top = 6.dp)
                    )
                }
            }
            item {
                Column(Modifier.pullUp(56.dp).padding(horizontal = 20.dp)) {
                    val kicker = when {
                        (anime?.progress ?: 0) > 0 -> "CONTINUE WATCHING"
                        anime?.airingStatus == "Releasing" -> "AIRING NOW"
                        else -> null
                    }
                    kicker?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
                            color = Ink.Candy,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Text(
                        anime?.title ?: "",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 31.sp),
                        color = Ink.Bone,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Score first — it is the thing that decides whether to
                    // bother — then the facts, on one line.
                    Text(
                        buildAnnotatedString {
                            anime?.averageScore?.let { avg ->
                                withStyle(SpanStyle(color = Ink.Gold, fontWeight = FontWeight.ExtraBold)) { append("★ $avg%") }
                                append("  ·  ")
                            }
                            append(
                                listOfNotNull(
                                    anime?.format?.let { if (it.length <= 3) it else it.titleCase() },
                                    anime?.episodes?.let { if (it == 1) "1 episode" else "$it episodes" },
                                    anime?.durationMins?.let { "${it}m" },
                                    anime?.seasonLabel?.titleCase(),
                                    anime?.airingStatus
                                ).joinToString("  ·  ")
                            )
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Ink.Mist,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (Settings.aniListToken.isNotEmpty()) {
                        // Your own place on the list, and the way to change it.
                        val onList = anime?.listStatus != null
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .then(if (onList) Modifier.raised(shape, Gloss.SelectedEdge) else Modifier)
                                .clip(shape)
                                .background(if (onList) Gloss.Selected else SolidColor(Ink.Quiet))
                                .clickable { showListEditor = true }
                                .padding(horizontal = 13.dp, vertical = 7.dp)
                        ) {
                            Text(
                                statusLabel(anime?.listStatus) +
                                    if ((anime?.score ?: 0.0) > 0) " · ${anime?.score?.toInt()}/10" else "",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                color = if (onList) Color.White else Ink.QuietText
                            )
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit list entry",
                                tint = if (onList) Color.White else Ink.QuietText,
                                modifier = Modifier.padding(start = 7.dp).size(14.dp)
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        JellyButton(
                            onClick = { findStreams(selectedEpisode) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            val resumeFrac = remember(selectedEpisode, progressTick) {
                                Progress.fraction(anilistId, selectedEpisode)
                            }
                            Text(
                                if (resumeFrac > 0f)
                                    "Resume episode $selectedEpisode · ${(resumeFrac * 100).toInt()}%"
                                else
                                    "Play episode $selectedEpisode",
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
                        GlassButton("Sources", { manualSearch(selectedEpisode) })
                    }
                    Text(
                        anime?.description?.take(400) ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                        color = Ink.Mist,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    if (!anime?.genres.isNullOrEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            anime?.genres?.take(5)?.forEach { Pill(it) }
                        }
                    }
                    val small = listOfNotNull(
                        anime?.studio,
                        anime?.popularity?.let { "#$it by popularity" },
                        anime?.favourites?.takeIf { it > 0 }?.let { "%,d ♥".format(it) }
                    )
                    if (small.isNotEmpty()) {
                        Text(
                            small.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Mist,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
            item {
                GlossyHeader("Episodes", count = anime?.episodes)
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

                // Rows rather than one long row, four of them at most.
                //
                // A single scrolling row is fine for a season and hopeless for
                // One Piece: a thousand chips in a line is a very long swipe
                // and shows eight at a time. Four rows show thirty-odd at a
                // glance, and the cap keeps the grid from pushing the
                // description and the related shows off the screen entirely.
                //
                // heightIn rather than height, so a twelve episode show draws
                // three rows and stops rather than leaving a hole. It must be
                // bounded either way: a vertically scrolling grid inside a
                // vertically scrolling column has no height to measure against
                // and throws.
                //
                // 80dp cells rather than 93: a 411dp phone fits three of the
                // larger and four of these, and the extra column is a third
                // more episodes on screen, which is the grid's whole point. The
                // cap is four rows of 52dp chips and their gaps; left at the
                // old figure it would show a sliver of a fifth.
                LazyVerticalGrid(
                    state = episodeGrid,
                    columns = GridCells.Adaptive(80.dp),
                    // The bottom padding is room for the raised edge under the
                    // last row, which the grid would otherwise clip.
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.heightIn(max = 240.dp)
                ) {
                    items((1..total).toList()) { ep ->
                        val selected = ep == selectedEpisode
                        val watched = ep <= (anime?.progress ?: 0)
                        val resume = remember(ep, progressTick) { Progress.fraction(anilistId, ep) }
                        val meta = epMeta[ep]
                        val skippable = meta?.filler == true || meta?.recap == true
                        // Already on the device? Then the second half has
                        // nothing left to offer and says so instead.
                        val held = Downloads.get(anilistId, ep)
                            ?.takeIf { Downloads.isComplete(it) } != null

                        // Two halves, two jobs: the number plays, the arrow
                        // opens that episode's sources, where every row has its
                        // own download.
                        //
                        // Downloading one particular episode used to mean
                        // tapping it, waiting for auto-play to find a source,
                        // cancelling that, and only then pressing the download
                        // button at the top — because that button acts on
                        // whatever is selected, and selecting an episode is
                        // exactly what starts the search. A long press was
                        // tried first and is worse: invisible until somebody
                        // guesses at it, on a grid that already does something
                        // when tapped.
                        //
                        // Split seventy-thirty by weight rather than in fixed
                        // widths. The left half carries the number, the filler
                        // tag and the progress bar; the right only ever holds
                        // one small icon. Fixed widths also left the chip short
                        // of its cell — the grid stretches cells to fill the
                        // row, so a 93dp chip sat in a 121dp cell with a gap
                        // beside it. Filling the cell and dividing it removes
                        // both at once.
                        // Whichever half made the selection is the one that
                        // lights. The chip's own ground stays neutral, so the
                        // lit half reads as the thing that was pressed.
                        val playLit = selected && !pickedByArrow
                        val arrowLit = selected && pickedByArrow
                        val chip = RoundedCornerShape(15.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .raised(chip)
                                .clip(chip)
                                .background(Gloss.Chip)
                                .border(1.dp, Gloss.TopLight, chip)
                        ) {
                        Box(
                            Modifier
                                .weight(7f)
                                .fillMaxHeight()
                                .then(if (playLit) Modifier.background(Gloss.Selected) else Modifier)
                                .clickable {
                                    selectedEpisode = ep
                                    pickedByArrow = false
                                    findStreams(ep)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    ep.toString().padStart(2, '0'),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = when {
                                        playLit -> Color.White
                                        watched -> Ink.Dim
                                        else -> Ink.Bone
                                    }
                                )
                                if (skippable) {
                                    Text(
                                        if (meta?.filler == true) "FILLER" else "RECAP",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = if (playLit) Color.White else Ink.Candy
                                    )
                                }
                            }
                            when {
                                // Part-watched wins over the watched dot: it is
                                // the more actionable state.
                                resume > 0f -> JellyBar(
                                    resume,
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 9.dp, vertical = 6.dp)
                                        .fillMaxWidth(),
                                    height = 4.dp
                                )
                                watched && !playLit -> Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 7.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(Ink.Candy)
                                )
                            }
                        }

                        // A hairline rather than a gap: two targets that
                        // clearly belong to one episode, not two chips.
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(
                                    if (selected) Color.White.copy(alpha = 0.25f)
                                    else Ink.Hairline
                                )
                        )

                        Box(
                            Modifier
                                .weight(3f)
                                .fillMaxHeight()
                                .then(if (arrowLit) Modifier.background(Gloss.Selected) else Modifier)
                                .clickable {
                                    selectedEpisode = ep
                                    pickedByArrow = true
                                    manualSearch(ep)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (held) Icons.Default.Check else Icons.Default.Download,
                                contentDescription =
                                    if (held) "Downloaded — choose another source"
                                    else "Choose a source or download",
                                modifier = Modifier.size(17.dp),
                                tint = when {
                                    // Lit first: the tick's own colour on the
                                    // violet ground would all but vanish.
                                    arrowLit -> Color.White
                                    held -> Ink.Candy
                                    else -> Ink.Dim
                                }
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
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Ink.Bone, fontWeight = FontWeight.Bold)) {
                                append("Episode $selectedEpisode")
                            }
                            append("  ·  $epTitle")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp)
                    )
                }
            }
            if (relations.isNotEmpty()) {
                item { GlossyHeader("Related") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(relations) { rel ->
                            Column(
                                Modifier
                                    .width(112.dp)
                                    .clickable { onOpen(rel.anime.id) }
                            ) {
                                GlossyPoster(rel.anime.cover, rel.anime.title, Modifier.fillMaxWidth())
                                Text(
                                    rel.type.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.6.sp
                                    ),
                                    color = Ink.Candy,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                // Both lines reserved, so every card in this row
                                // is the same height. A shorter one lets a
                                // taller neighbour hang below it, and a
                                // downward press then finds that neighbour
                                // rather than whatever is under the row.
                                Text(
                                    rel.anime.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                                    color = Ink.Bone,
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
                item { GlossyHeader("Recommended") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(unseen) { rec ->
                            Column(
                                Modifier
                                    .width(112.dp)
                                    .clickable { onOpen(rec.id) }
                            ) {
                                GlossyPoster(rec.cover, rec.title, Modifier.fillMaxWidth())
                                // Where Related names the kind of relation, the
                                // useful thing here is whether it is any good.
                                Text(
                                    rec.averageScore?.let { "★ $it%" } ?: " ",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = Ink.Gold,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                                // Both lines reserved, for the same reason as
                                // the row above: uneven cards break which one a
                                // downward press finds.
                                Text(
                                    rec.title,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                                    color = Ink.Bone,
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
            WaitCard(
                cover = anime?.cover,
                title = anime?.title.orEmpty(),
                episode = selectedEpisode,
                label = stepLabel(step),
                detail = stepDetail(step),
                onCancel = { autoJob?.cancel(); autoStep = null }
            )
        }
    }

    if (resolving) {
        // Not dismissable: the resolve is already in flight with the debrid
        // provider, and backing out here would leave it half-done.
        Dialog(onDismissRequest = { }) {
            WaitCard(
                cover = anime?.cover,
                title = anime?.title.orEmpty(),
                episode = selectedEpisode,
                label = "Resolving link…"
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Ink.Sheet
        ) {
            Column(
                Modifier
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sources", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Pill("Episode $selectedEpisode", brush = Gloss.Tag)
                    if (streams.isNotEmpty()) {
                        Text(
                            "  ${streams.size} found",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Mist
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                // The copy already on the phone, above everything the addons
                // offer.
                //
                // The tick opens this sheet, and that is mostly done to look at
                // what else there is — but the one thing certain to play, with
                // no network and no debrid, was reachable only by closing the
                // sheet and pressing the number instead. Named by the release
                // it came from, since the reason to be here is comparing it.
                val onDisk = Downloads.get(anilistId, selectedEpisode)
                    ?.takeIf { Downloads.isComplete(it) }
                if (onDisk != null) {
                    val card = RoundedCornerShape(18.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .raised(card, Gloss.SelectedEdge, 4.dp)
                            .clip(card)
                            .background(Gloss.Selected)
                            .border(1.dp, Gloss.TopLight, card)
                            .clickable {
                                showSheet = false
                                playDownloaded(selectedEpisode)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Play the downloaded copy",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                            Text(
                                buildString {
                                    append(onDisk.sourceName.lineSequence().first().ifBlank { "On this phone" })
                                    append("  ·  ${Downloads.fileFor(onDisk).length() / 1_048_576} MB")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (searching) {
                    LinearProgressIndicator(
                        color = Ink.Candy,
                        trackColor = Ink.Edge,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp))
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
                                .width(4.dp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        s === bestStream -> Gloss.Tag
                                        s.isDirect -> Gloss.Selected
                                        else -> SolidColor(Ink.Edge)
                                    }
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = Ink.Bone,
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
                        when {
                            s === bestStream -> Pill("Best", brush = Gloss.Tag)
                            s.isDirect -> Pill("Direct", brush = Gloss.Selected)
                            else -> Pill("Debrid", color = Ink.Mist)
                        }
                        IconButton(onClick = { download(s) }) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download for offline",
                                tint = Ink.Mist,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = Ink.Hairline)
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
            containerColor = Ink.Sheet
        ) {
            Column(
                Modifier
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("List entry", style = MaterialTheme.typography.titleLarge)
                Text(
                    anime?.title ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))

                Text("Status", style = MaterialTheme.typography.labelMedium, color = Ink.Mist)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    statuses.forEach { (value, label) ->
                        val on = anime?.listStatus == value
                        val chip = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .raised(chip, if (on) Gloss.SelectedEdge else Gloss.Edge)
                                .clip(chip)
                                .background(if (on) Gloss.Selected else Gloss.Chip)
                                .border(1.dp, Gloss.TopLight, chip)
                                .clickable {
                                    scope.launch {
                                        runCatching { AniList.saveEntry(anilistId, status = value) }
                                        progressTick++
                                    }
                                }
                                .padding(horizontal = 13.dp, vertical = 9.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                color = if (on) Color.White else Ink.Bone
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "Episodes watched",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Mist
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        Icons.Default.Remove,
                        "One fewer",
                        { if (pendingProgress > 0) pendingProgress-- }
                    )
                    Text(
                        "$pendingProgress${anime?.episodes?.let { " / $it" } ?: ""}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )
                    GlassIconButton(
                        Icons.Default.Add,
                        "One more",
                        {
                            val max = anime?.episodes ?: Int.MAX_VALUE
                            if (pendingProgress < max) pendingProgress++
                        }
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Score — ${if (pendingScore > 0) pendingScore.toInt().toString() else "none"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.Mist
                )
                Slider(
                    value = pendingScore.toFloat(),
                    onValueChange = { pendingScore = it.toDouble() },
                    valueRange = 0f..10f,
                    steps = 9,
                    colors = glossySliderColors()
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    JellyButton(
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
                        height = 48.dp,
                        shape = RoundedCornerShape(17.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Save", style = MaterialTheme.typography.labelLarge) }

                    anime?.entryId?.let { id ->
                        val glass = RoundedCornerShape(17.dp)
                        Box(
                            Modifier
                                .height(48.dp)
                                .raised(glass)
                                .clip(glass)
                                .background(Gloss.Glass)
                                .border(1.dp, Gloss.TopLight, glass)
                                .clickable {
                                    scope.launch {
                                        runCatching { AniList.deleteEntry(id) }
                                        progressTick++
                                        showListEditor = false
                                    }
                                }
                                .padding(horizontal = 22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Remove", style = MaterialTheme.typography.labelLarge, color = Ink.Orchid)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card shown while an episode is being found or its link resolved: the
 * poster, which episode, and what is happening. [onCancel] adds a way out,
 * for the stages where backing out leaves nothing half-done.
 */
@Composable
private fun WaitCard(
    cover: String?,
    title: String,
    episode: Int,
    label: String,
    detail: String? = null,
    onCancel: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .raised(shape, depth = 5.dp)
            .clip(shape)
            .background(Gloss.Chip)
            .border(1.dp, Gloss.TopLight, shape)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        GlossyPoster(cover, null, Modifier.width(104.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Pill("Episode $episode", brush = Gloss.Tag)
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            color = Ink.Candy,
            trackColor = Ink.Edge,
            modifier = Modifier.width(140.dp).clip(RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        detail?.let {
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
        onCancel?.let {
            Spacer(Modifier.height(14.dp))
            Pill("Cancel", color = Ink.Mist, onClick = it)
        }
    }
}

/** "SPRING 2013" → "Spring 2013"; short words such as TV keep their capitals. */
private fun String.titleCase() = split(' ').joinToString(" ") { w ->
    if (w.length <= 2 || w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
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
