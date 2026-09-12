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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.debritsu.app.ui.pageBackground
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
@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun TvDetailScreen(
    anilistId: Int,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    /** Start the next episode as soon as the show loads — Home's Resume. */
    autoPlay: Boolean = false,
    // A show to draw instead of asking AniList for one. Only the debug-build
    // preview passes it, so the look can be checked with AniList unreachable;
    // the app itself never does.
    preview: Anime? = null
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
    }

    /**
     * The episode row's own scroll, so it starts where you are.
     *
     * It always opened on episode one. On a phone that is a long swipe; here it
     * is worse, because the only way along the row is the d-pad — reaching
     * episode 900 of a long-running show would mean holding right until it
     * arrived, every time the page was opened.
     *
     * Moved once, when the record lands. Moving it again would drag the row out
     * from under a remote already travelling along it.
     */
    val episodeRow = rememberLazyListState()
    var placedEpisodeRow by remember(anilistId) { mutableStateOf(false) }
    // Where focus enters that row; see the row itself.
    val selectedTile = remember { FocusRequester() }
    LaunchedEffect(anilistId, anime, selectedEpisode) {
        if (placedEpisodeRow || anime == null) return@LaunchedEffect
        placedEpisodeRow = true
        // A couple back from the one you want, so it is not jammed against the
        // left edge with no sense of what came before it.
        runCatching { episodeRow.scrollToItem((selectedEpisode - 3).coerceAtLeast(0)) }
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

    // Once only, and remembered across the trip to the player and back: a
    // Resume from Home plays when the show first loads, and the return from
    // the episode lands on this page as normal rather than starting another.
    var autoPlayed by rememberSaveable(anilistId) { mutableStateOf(false) }
    LaunchedEffect(anime?.id) {
        if (!autoPlay || autoPlayed || anime == null) return@LaunchedEffect
        autoPlayed = true
        play(selectedEpisode)
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
        modifier = Modifier.fillMaxSize().pageBackground(),
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
        // moves and there is nothing to chase.
        //
        // 360dp, so the top of the episode row shows beneath the buttons. The
        // panel is 1920x1080 at 2x, a 540dp viewport; at 460 the row sat wholly
        // below it, and a lazy row that is built in the same moment focus
        // arrives, scrolled to somewhere past its first episode, does not take
        // the focus — it paged back towards episode one looking for it, which
        // on One Piece was a thousand episodes of scrolling, and then let focus
        // fall through to Related. On screen already, the press lands on a tile
        // that is there. It also means moving down to the episodes lifts the
        // page by only a couple of dozen dp, so the title stays in view.
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clipToBounds()
        ) {
            // The show's own wide art at the top right, at its natural shape —
            // TVDB's fanart is 1920x1080, the shape of the panel, so none of
            // it is thrown away and nothing is blown up past its resolution.
            // AniList's banner, which this used to stretch across the box, is
            // a strip four hundred pixels tall. It dissolves leftward into the
            // words and downward into the page by masking the picture itself,
            // so there is no seam where a guessed colour meets the backdrop.
            TvBackdrop(
                rememberTvArt(anime),
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.72f)
                    .aspectRatio(16f / 9f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Anchored to the top, not the bottom. Bottom-anchored, a show
                // with more to say pushes its own title upward and out of the
                // box; top-anchored, the overflow goes downward where the box
                // simply grows to take it.
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.56f)
                    .padding(start = OVERSCAN, end = 24.dp, top = OVERSCAN + 12.dp, bottom = 16.dp)
            ) {
                // What kind of thing it is, small and in the accent, above the
                // name — the phone's kicker.
                Text(
                    listOfNotNull(anime?.format, anime?.seasonLabel, anime?.airingStatus)
                        .joinToString("  ·  ").uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp
                    ),
                    color = Ink.Candy,
                    maxLines = 1
                )

                Text(
                    anime?.title ?: "…",
                    // A step down from displaySmall. At 36sp a long title ate
                    // the space the rest of the block needed and still did not
                    // finish; smaller, three lines fit in less room than two
                    // used to take.
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = Ink.Bone,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                // Score first — it is the thing that decides whether to
                // bother — then the facts, on one line.
                Text(
                    buildAnnotatedString {
                        anime?.averageScore?.let {
                            withStyle(SpanStyle(color = Ink.Candy, fontWeight = FontWeight.ExtraBold)) {
                                append("★ $it%")
                            }
                            append("    ")
                        }
                        append(
                            listOfNotNull(
                                anime?.episodes?.let { if (it == 1) "1 episode" else "$it episodes" },
                                anime?.durationMins?.let { "${it}m" },
                                anime?.popularity?.let { "#$it by popularity" }
                            ).joinToString("  ·  ")
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Ink.Mist,
                    maxLines = 1
                )

                anime?.genres?.take(4)?.takeIf { it.isNotEmpty() }?.let { genres ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.forEach { g ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    // Quiet, as on the phone: the accent is for
                                    // things to press, and genres are not.
                                    .background(Ink.Quiet)
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    g,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Ink.QuietText
                                )
                            }
                        }
                    }
                }

                anime?.description?.let {
                    Text(
                        synopsis(it),
                        style = MaterialTheme.typography.bodyMedium,
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
            // The primary action in the accent, so it reads as the thing to
            // press; everything else soft beside it.
            TvPrimaryButton(onClick = { play(selectedEpisode) }) {
                androidx.tv.material3.Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (resumeFrac > 0f)
                        "Resume episode $selectedEpisode  ·  ${(resumeFrac * 100).toInt()}%"
                    else "Play episode $selectedEpisode"
                )
            }
            TvSecondaryButton(onClick = { manualSearch(selectedEpisode) }) { Text("Sources") }
            if (Settings.aniListToken.isNotEmpty()) {
                TvSecondaryButton(onClick = { showStatus = true }) {
                    Text(statusLabel(anime?.listStatus))
                }
            }
            TvSecondaryButton(onClick = onBack) { Text("Back") }
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
                    TvChoice(label, anime?.listStatus == value) {
                        scope.launch {
                            runCatching {
                                AniList.saveEntry(anilistId, status = value)
                            }
                            showStatus = false
                            progressTick++
                        }
                    }
                }
            }
        }
        }

        // The hero runs edge to edge, so everything below it carries the
        // overscan margin itself rather than inheriting one from the column.
        item {
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
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
            state = episodeRow,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp),
            // Focus comes into the row on the episode the Play button names,
            // and afterwards back to whichever one the remote was last on.
            //
            // Left to itself the row enters at whichever tile is nearest the
            // button the remote came from, which is rarely the one you want.
            //
            // Only while that episode is actually laid out: a FocusRequester
            // nothing holds throws when focus is sent to it, which is how an
            // earlier attempt at aiming focus closed the app from Home.
            modifier = Modifier.focusRestorer {
                val shown = episodeRow.layoutInfo.visibleItemsInfo.any { it.index == selectedEpisode - 1 }
                if (shown) selectedTile else FocusRequester.Default
            }
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
                Card(
                    onClick = { selectedEpisode = ep; play(ep) },
                    shape = TvCardShape,
                    modifier = if (selected) Modifier.focusRequester(selectedTile) else Modifier
                ) {
                    Box(
                        Modifier.size(width = 104.dp, height = 84.dp)
                            .background(
                                if (selected) Ink.palette.selected else Ink.palette.chip
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                ep.toString(),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                // Watched ones recede rather than shout.
                                color = when {
                                    selected -> Color.White
                                    watched -> Ink.Dim
                                    else -> Ink.Bone
                                }
                            )
                            // Marked from MyAnimeList data, so you know what is
                            // safe to skip before starting it.
                            if (skippable) {
                                Text(
                                    if (meta?.filler == true) "FILLER" else "RECAP",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = if (selected) Color.White else Ink.Candy
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
                                        .background(Ink.palette.progress)
                                )
                            }
                            watched && !selected -> Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 9.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Ink.Candy)
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Ink.Bone,
                modifier = Modifier.padding(start = OVERSCAN)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp)
            ) {
                items(relations) { rel ->
                    Column(Modifier.width(124.dp)) {
                        Card(onClick = { onOpen(rel.anime.id) }, shape = TvCardShape) {
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
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = Ink.Candy,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // Both lines reserved, so every card in the row is the
                        // same height and a downward press leaves the row.
                        Text(
                            rel.anime.title,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Ink.Bone,
                modifier = Modifier.padding(start = OVERSCAN)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = OVERSCAN, vertical = 10.dp)
            ) {
                items(unseen) { rec ->
                    Column(Modifier.width(124.dp)) {
                        Card(onClick = { onOpen(rec.id) }, shape = TvCardShape) {
                            Box {
                                AsyncImage(
                                    model = rec.cover,
                                    contentDescription = rec.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(2f / 3f)
                                        .background(Ink.Veil)
                                )
                                // Where Related names the kind of relation, the
                                // useful thing here is whether it is any good —
                                // on the poster, as it is on Home.
                                rec.averageScore?.let {
                                    TvBadge("★ $it%", Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                        Text(
                            rec.title,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Ink.Bone,
                            modifier = Modifier.padding(top = 6.dp),
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
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = Ink.Bone,
                modifier = Modifier.padding(horizontal = OVERSCAN)
            )
            streams.take(40).forEach { s ->
                TvSecondaryButton(
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

    // Over everything while an episode is being found, as on the phone.
    //
    // It used to be a line of text at the foot of this page, and the page is
    // taller than the screen, so it was below the fold: Resume on Home opened
    // a show and then, as far as anyone watching could tell, did nothing until
    // the episode suddenly started. A dialog also gives Back a meaning here —
    // it stops the search — and holds nothing focusable, so stray presses of
    // the remote cannot start something else underneath it.
    autoStep?.let { step ->
        Dialog(onDismissRequest = { autoJob?.cancel(); autoStep = null }) {
            TvWaitCard(
                cover = anime?.cover,
                title = anime?.title.orEmpty(),
                episode = selectedEpisode,
                label = stepLabel(step)
            )
        }
    }
}

/** The card shown while an episode is found: the poster, which episode, and what is happening. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvWaitCard(cover: String?, title: String, episode: Int, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(340.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Ink.Sheet)
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.Veil)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = Ink.Bone,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp)
        )
        TvBadge("Episode $episode", Modifier.padding(top = 2.dp), solid = true)
        LinearProgressIndicator(
            color = Ink.Candy,
            trackColor = Ink.Edge,
            modifier = Modifier
                .padding(top = 12.dp)
                .width(160.dp)
                .clip(RoundedCornerShape(2.dp))
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = Ink.Bone,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "Press Back to cancel",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Mist,
            modifier = Modifier.padding(top = 6.dp)
        )
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
