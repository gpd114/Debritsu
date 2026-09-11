package com.debritsu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Progress
import com.debritsu.app.data.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    authFlash: Int
) {

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var planning by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var listed by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Paging for the two shelves that have more to give. Expanding one used to
    // show the same items the row already held, just laid out as a grid, which
    // is a poor reward for tapping expand — the point of the bigger view is
    // more of them.
    val scope = rememberCoroutineScope()
    var trendingPage by remember { mutableStateOf(1) }
    var trendingMore by remember { mutableStateOf(true) }
    var recPage by remember { mutableStateOf(1) }
    var recMore by remember { mutableStateOf(true) }
    var shelfLoadingMore by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf<List<Anime>>(emptyList()) }
    val searching = searchOpen && query.length >= 3
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Title of the shelf currently taking over the screen, or null for the
    // normal stack of side-scrolling rows.
    var expanded by remember { mutableStateOf<String?>(null) }

    suspend fun fetch(p: Int) = AniList.search(query, p)

    // Four independent queries, so they go at once rather than one after
    // another — waiting on them in turn made opening the app cost the sum of
    // them, and AniList is regularly slow enough for that to run to several
    // seconds each. Each shelf is also assigned on its own, so it appears the
    // moment its own query lands instead of everything waiting for the slowest.
    LaunchedEffect(authFlash) {
        loading = true
        coroutineScope {
            // Each keeps what it had when its request fails, rather than being
            // emptied. A shelf is hidden when it is empty, so falling back to
            // an empty list made one refused request erase the shelf — which is
            // how Continue watching came to disappear at random on television.
            // A failure means "no answer", not "nothing there".
            launch { runCatching { AniList.watching() }.onSuccess { watching = it } }
            launch { runCatching { AniList.planning() }.onSuccess { planning = it } }
            launch { runCatching { AniList.trending().items }.onSuccess { trending = it } }
            launch { runCatching { AniList.recommended().items }.onSuccess { recommended = it } }
            launch { runCatching { AniList.listedIds() }.onSuccess { listed = it } }
        }
        loading = false
    }

    // Reset to page one whenever the query changes.
    LaunchedEffect(query, authFlash) {
        if (!searching) return@LaunchedEffect
        loading = true
        page = 1
        val res = runCatching { fetch(1) }.getOrNull()
        if (res != null) {
            browse = res.items
            hasMore = res.hasMore
        }
        loading = false
    }

    // Pull the next page as the grid nears its end.
    LaunchedEffect(gridState, browse.size, hasMore, query) {
        if (!searching) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (hasMore && !loadingMore && !loading && browse.isNotEmpty() &&
                    last >= browse.size - 8
                ) {
                    loadingMore = true
                    val next = page + 1
                    val res = runCatching { fetch(next) }.getOrNull()
                    if (res != null) {
                        browse = browse + res.items
                        hasMore = res.hasMore
                        page = next
                    } else {
                        hasMore = false
                    }
                    loadingMore = false
                }
            }
    }

    // Anything already on a list is not a discovery, so the recommendations
    // drop it — and that means the whole list, not just the two shelves
    // above. Filtering on those alone let shows through that were finished and
    // marked completed years ago, which is the one thing this shelf should
    // never suggest.
    val onMyList = listed + (watching + planning).map { it.id }
    val fresh = recommended.filter { it.id !in onMyList }
    val shelves = buildList {
        if (watching.isNotEmpty()) add("Continue watching" to watching)
        if (planning.isNotEmpty()) add("Plan to watch" to planning)
        add("Trending" to trending)
        if (fresh.isNotEmpty()) add("Recommended" to fresh)
    }
    val openShelf = shelves.firstOrNull { it.first == expanded }

    // Back closes search or an expanded shelf rather than leaving Home.
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    BackHandler(enabled = !searchOpen && openShelf != null) { expanded = null }

    Scaffold(containerColor = Color.Transparent) { pad ->
        when {
            searchOpen -> Column(Modifier.padding(bottom = pad.calculateBottomPadding())) {
                SearchBar(query, { query = it }) { searchOpen = false; query = "" }
                if (loading && searching) LoadingLine()
                if (!searching) {
                    Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                        Hint("Type at least three letters.")
                    }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (searching) items(browse) { PosterCard(it, onOpen) }
                    if (loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Ink.Iris, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            openShelf != null -> Column(
                Modifier.statusBarsPadding().padding(bottom = pad.calculateBottomPadding())
            ) {
                ExpandedShelf(
                    title = openShelf.first,
                    list = openShelf.second,
                    onOpen = onOpen,
                    onCollapse = { expanded = null },
                    loadingMore = shelfLoadingMore,
                    // The two list shelves are the whole of a list already and
                    // have no next page; only these two are worth asking for
                    // more of. Ids are checked as pages are joined, because a
                    // show can appear on two pages of a changing ranking.
                    onNearEnd = {
                        if (!shelfLoadingMore) when (openShelf.first) {
                            "Trending" -> if (trendingMore) scope.launch {
                                shelfLoadingMore = true
                                val next = trendingPage + 1
                                val res = runCatching { AniList.trending(next) }.getOrNull()
                                if (res != null) {
                                    val have = trending.mapTo(mutableSetOf()) { it.id }
                                    trending = trending + res.items.filter { have.add(it.id) }
                                    trendingMore = res.hasMore
                                    trendingPage = next
                                } else trendingMore = false
                                shelfLoadingMore = false
                            }
                            "Recommended" -> if (recMore) scope.launch {
                                shelfLoadingMore = true
                                val next = recPage + 1
                                val res = runCatching { AniList.recommended(next) }.getOrNull()
                                if (res != null) {
                                    val have = recommended.mapTo(mutableSetOf()) { it.id }
                                    recommended = recommended + res.items.filter { have.add(it.id) }
                                    recMore = res.hasMore
                                    recPage = next
                                } else recMore = false
                                shelfLoadingMore = false
                            }
                            else -> Unit
                        }
                    }
                )
            }

            else -> HomeFeed(
                watching = watching,
                planning = planning,
                trending = trending,
                recommended = fresh,
                loading = loading,
                needsSource = Settings.addons.isEmpty(),
                bottomPadding = pad.calculateBottomPadding(),
                onOpen = onOpen,
                onResume = onResume,
                onExpand = { expanded = it },
                onSearch = { searchOpen = true },
                onDownloads = onDownloads,
                onSettings = onSettings
            )
        }
    }
}

/**
 * The home screen proper, from lists already in hand — kept apart from the
 * fetching so the debug preview can draw it with sample shows.
 *
 * A streaming-app layout: the shows you are part-way through turn over across
 * the top with a Resume button, then what airs this week, wide cards for the
 * shows under way, and rows of posters. With nothing under way the top shows
 * what is trending instead.
 */
@Composable
internal fun HomeFeed(
    watching: List<Anime>,
    planning: List<Anime>,
    trending: List<Anime>,
    recommended: List<Anime>,
    loading: Boolean,
    needsSource: Boolean,
    bottomPadding: Dp,
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit,
    onExpand: (String) -> Unit,
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit
) {
    val underway = watching.isNotEmpty()
    val heroShows = (if (underway) watching else trending).take(5)
    // Only shows still airing. Someone who watches finished series will never
    // see this row, which is the point — an empty row is worse than no row.
    val airing = watching
        .filter { it.nextEpisode != null && (it.airingInSeconds ?: 0) > 0 }
        .sortedBy { it.airingInSeconds ?: Int.MAX_VALUE }

    val list = rememberLazyListState()
    val solidAfter = with(LocalDensity.current) { 300.dp.toPx() }
    // The icons float over the picture until it has scrolled away, then sit on
    // a bar of their own so they stay legible over the rows.
    val solid by remember(heroShows.isEmpty()) {
        derivedStateOf {
            heroShows.isEmpty() || list.firstVisibleItemIndex > 0 ||
                list.firstVisibleItemScrollOffset > solidAfter
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = bottomPadding + 28.dp)
        ) {
            item {
                if (heroShows.isNotEmpty()) Hero(heroShows, underway, onOpen, onResume)
                else Spacer(Modifier.statusBarsPadding().height(60.dp))
            }
            if (needsSource) item { AddSourceCard(onSettings) }
            if (loading && heroShows.isEmpty()) item { LoadingLine() }
            if (airing.isNotEmpty()) {
                item { GlossyHeader("Airing this week", trailing = "${airing.size} ${if (airing.size == 1) "show" else "shows"}") }
                item { AiringRow(airing, onOpen) }
            }
            if (watching.isNotEmpty()) {
                item { GlossyHeader("Continue watching", trailing = "See all", onTrailing = { onExpand("Continue watching") }) }
                item { ContinueRow(watching, onOpen) }
            }
            if (planning.isNotEmpty()) item { Shelf("Plan to watch", planning, onOpen) { onExpand("Plan to watch") } }
            if (trending.isNotEmpty()) item { Shelf("Trending", trending, onOpen) { onExpand("Trending") } }
            if (recommended.isNotEmpty()) item { Shelf("Recommended", recommended, onOpen) { onExpand("Recommended") } }
        }

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (solid) Ink.palette.backdrop[0].copy(alpha = 0.94f) else Color.Transparent)
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            val tint = if (solid) Ink.GlassIcon else if (Ink.palette.dark) Color.White else Ink.GlassIcon
            OverlayIconButton(Icons.Default.Search, "Search", onSearch, wash = !solid, tint = tint)
            Spacer(Modifier.width(6.dp))
            OverlayIconButton(Icons.Default.Download, "Downloads", onDownloads, wash = !solid, tint = tint)
            Spacer(Modifier.width(6.dp))
            OverlayIconButton(Icons.Default.Settings, "Settings", onSettings, wash = !solid, tint = tint)
        }
    }
}

/**
 * The shows you are part-way through, turning over on their own every few
 * seconds and swipeable — the one you are most likely to want is a single tap
 * from opening the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Hero(
    shows: List<Anime>,
    underway: Boolean,
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit
) {
    val pager = rememberPagerState { shows.size }
    // Moves on after six seconds on a page. Keyed on the page it has settled
    // on, so a swipe restarts the wait rather than being followed a moment
    // later by a jump. Not on currentPage: that changes halfway through the
    // slide, which cancelled this very animation and left the hero stuck
    // between two shows.
    LaunchedEffect(pager.settledPage, shows.size) {
        if (shows.size < 2) return@LaunchedEffect
        delay(6_000)
        if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.settledPage + 1) % shows.size)
    }
    Column {
        HorizontalPager(state = pager, beyondViewportPageCount = 1) { page ->
            HeroPage(shows[page], underway, onOpen, onResume)
        }
        if (shows.size > 1) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                repeat(shows.size) { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(if (on) 18.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (on) Ink.Candy else Ink.Edge)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(
    anime: Anime,
    underway: Boolean,
    onOpen: (Int) -> Unit,
    onResume: (Int) -> Unit
) {
    val total = anime.episodes ?: 0
    // The episode Resume will play: the one after the last watched, and never
    // past the end.
    val next = (anime.progress + 1).let { if (total > 0) it.coerceAtMost(total) else it }
    val partWatched = remember(anime.id, next) { Progress.fraction(anime.id, next) }

    Column(Modifier.fillMaxWidth()) {
        WideArt(rememberArt(anime), Modifier.fillMaxWidth().aspectRatio(1.6f))
        // A fixed height, laid out from the bottom: a long title and a short
        // one then leave the buttons in the same place on every page, and the
        // pager does not change height as it turns.
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .pullUp(56.dp)
                .height(218.dp)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                if (underway) "CONTINUE WATCHING" else "TRENDING NOW",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
                color = Ink.Candy
            )
            Text(
                anime.title,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 31.sp),
                color = Ink.Bone,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
            )
            Text(
                if (underway) buildString {
                    append("Episode $next")
                    if (total > 0) append(" of $total")
                    if (partWatched > 0f) append("  ·  ${(partWatched * 100).toInt()}% watched")
                } else listOfNotNull(
                    anime.averageScore?.let { "★ $it%" },
                    anime.episodes?.let { if (it == 1) "1 episode" else "$it episodes" }
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = Ink.Mist
            )
            if (underway && total > 0) {
                JellyBar(
                    anime.progress.toFloat() / total,
                    Modifier.padding(top = 10.dp).fillMaxWidth(),
                    height = 5.dp
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                JellyButton(onClick = { onResume(anime.id) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (underway) "Resume" else "Play", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(10.dp))
                GlassButton("Details", { onOpen(anime.id) })
            }
        }
    }
}

/**
 * Wide artwork for a show: TVDB's fanart where ani.zip has it, else the
 * AniList banner, else the cover. Null while the lookup is out, so nothing is
 * drawn and then swapped a moment later for something better.
 */
@Composable
internal fun rememberArt(anime: Anime): Any? {
    var art by remember(anime.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(anime.id) {
        art = Mappings.fanart(anime.id) ?: anime.banner ?: anime.cover
    }
    return art
}

/**
 * A picture across the full width at close to its own shape, dimmed along the
 * top so the clock and the icons read over it, and faded into the page along
 * the bottom so the words below sit on it without a hard edge.
 *
 * Deliberately not tall. Wide art cropped to a tall panel is a slice from the
 * middle of it, magnified — on a banner, next to nothing.
 */
@Composable
internal fun WideArt(model: Any?, modifier: Modifier) {
    val context = LocalContext.current
    val p = Ink.palette
    // A haze along the top for the status bar: the page's own pale colour on
    // Pastel, where the clock is drawn dark, and a dark one on Night, where it
    // is light. Either way the clock reads over any picture.
    val haze = if (p.dark) Color(0x6B0F0823) else p.backdrop[0].copy(alpha = 0.8f)
    Box(modifier) {
        AsyncImage(
            model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(true).build() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // The picture fades out to nothing along the bottom rather than
                // into a colour, so it melts into whatever the page is behind
                // it. Fading into one colour left a seam wherever the page's
                // gradient had moved on from that colour.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to haze, 0.28f to haze.copy(alpha = 0f))
            )
        )
    }
}

/** Lifts content up over whatever is above it, giving the space back below. */
internal fun Modifier.pullUp(by: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val px = by.roundToPx()
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) {
        placeable.place(0, -px)
    }
}

/** Search, taking over the top of the screen, with a way back. */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 18.dp, top = 8.dp, bottom = 10.dp)
    ) {
        GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Close search", onClose)
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search anime", color = Ink.Mist) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Ink.Mist) },
            shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = glossyFieldColors(),
            modifier = Modifier.weight(1f).focusRequester(focus)
        )
    }
}

@Composable
private fun LoadingLine() {
    LinearProgressIndicator(
        color = Ink.Iris,
        trackColor = Ink.Edge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}

@Composable
private fun AddSourceCard(onSettings: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .softShadow(shape, 8.dp)
            .raised(shape)
            .clip(shape)
            .background(Gloss.Chip)
            .border(1.dp, Gloss.TopLight, shape)
            .clickable(onClick = onSettings)
            .padding(16.dp)
    ) {
        Text("Add a source", style = MaterialTheme.typography.titleMedium, color = Ink.Bone)
        Text(
            "Everything here plays from your Stremio addons. Paste an addon URL in Settings to start.",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.Mist,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * What airs this week among the shows being watched, soonest first — a small
 * cover, the title, and which episode when.
 */
@Composable
internal fun AiringRow(airing: List<Anime>, onOpen: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(airing) { anime ->
            val shape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .width(232.dp)
                    .softShadow(shape, 8.dp)
                    .raised(shape)
                    .clip(shape)
                    .background(Gloss.Chip)
                    .border(1.dp, Gloss.TopLight, shape)
                    .clickable { onOpen(anime.id) }
                    .padding(8.dp)
            ) {
                AsyncImage(
                    model = anime.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(40.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Ink.Veil)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        anime.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                        color = Ink.Bone,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Ink.Candy, fontWeight = FontWeight.Bold)) {
                                append("Episode ${anime.nextEpisode}")
                            }
                            append("  ·  ${countdown(anime.airingInSeconds ?: 0)}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Ink.Mist,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * The shows under way as wide cards — their fanart, a play mark, the next
 * episode and a bar through the series.
 */
@Composable
internal fun ContinueRow(shows: List<Anime>, onOpen: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(shows) { anime ->
            val shape = RoundedCornerShape(16.dp)
            val total = anime.episodes ?: 0
            Column(
                Modifier
                    .width(236.dp)
                    .clickable { onOpen(anime.id) }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .softShadow(shape, 10.dp)
                        .raised(shape, depth = 4.dp)
                        .clip(shape)
                        .background(Ink.Veil)
                        .border(if (Ink.palette.dark) 1.dp else 2.dp, if (Ink.palette.dark) Color(0x1FFFFFFF) else Color.White, shape)
                ) {
                    AsyncImage(
                        model = rememberArt(anime),
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.45f to Color(0x00000000), 1f to Color(0xB3000000))
                        )
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xEBFFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF14123A), modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "EP ${(anime.progress + 1).let { if (total > 0) it.coerceAtMost(total) else it }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    )
                    if (total > 0) {
                        JellyBar(
                            anime.progress.toFloat() / total,
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            height = 4.dp,
                            track = Color(0x40FFFFFF)
                        )
                    }
                }
                Text(
                    anime.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = Ink.Bone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
        }
    }
}

/** One side-scrolling row of posters. */
@Composable
internal fun Shelf(
    title: String,
    list: List<Anime>,
    onOpen: (Int) -> Unit,
    onExpand: () -> Unit
) {
    Column {
        GlossyHeader(title, trailing = "See all", onTrailing = onExpand)
        LazyRow(
            // Bottom padding leaves room for the edge and shadow under each poster.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { anime ->
                Box(Modifier.width(110.dp)) { PosterCard(anime, onOpen) }
            }
        }
    }
}

/** The same shelf given the whole screen, as a scrolling grid. */
@Composable
private fun ExpandedShelf(
    title: String,
    list: List<Anime>,
    onOpen: (Int) -> Unit,
    onCollapse: () -> Unit,
    loadingMore: Boolean = false,
    onNearEnd: () -> Unit = {}
) {
    val grid = rememberLazyGridState()

    // Ask for the next page as the grid nears its end, the same way search
    // does. Eight from the bottom, so the next page is usually there before
    // the scrolling reaches it.
    LaunchedEffect(grid, list.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (list.isNotEmpty() && last >= list.size - 8) onNearEnd() }
    }

    Column {
        GlossyHeader(title, trailing = "Close", onTrailing = onCollapse, count = list.size)
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(list) { PosterCard(it, onOpen) }
            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Ink.Iris, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/**
 * A poster with its title under it. Shows under way carry their episode as a
 * badge; the rest carry AniList's score where it has one.
 */
@Composable
internal fun PosterCard(anime: Anime, onOpen: (Int) -> Unit) {
    Column(Modifier.clickable { onOpen(anime.id) }) {
        GlossyPoster(anime.cover, anime.title, Modifier.fillMaxWidth(), corner = 14.dp) {
            if (anime.progress > 0) {
                Pill(
                    "EP ${anime.progress}",
                    brush = Gloss.Selected,
                    modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                )
            } else {
                // Only when AniList has a score — a new or obscure title often
                // has none, and an empty pill reads worse than no pill — and
                // only when there is no episode badge: side by side on a poster
                // this narrow the two ran into each other.
                anime.averageScore?.let { score ->
                    Pill(
                        "★ $score%",
                        brush = SolidColor(Color(0xB30C0B1C)),
                        color = Color(0xFFFFD86B),
                        modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
                    )
                }
            }
        }
        // Both lines are reserved whether the title needs them or not, so every
        // card in a row is exactly as tall as its neighbours.
        //
        // Uneven heights are what made pressing down inside a shelf jump along
        // the row instead of leaving it: a taller card extends below its
        // shorter neighbours, and a downward focus search takes anything whose
        // bounds lie below the current item — including a sibling four places
        // to the right on the very same row.
        Text(
            anime.title,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
            color = Ink.Bone,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp)
        )
    }
}

/** Rounded to whatever unit reads naturally — nobody needs the seconds. */
private fun countdown(seconds: Int): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 1 -> "in $days days"
        days == 1 -> "in a day"
        hours > 1 -> "in $hours hours"
        hours == 1 -> "in an hour"
        minutes > 1 -> "in $minutes minutes"
        else -> "any moment"
    }
}
