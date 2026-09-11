package com.debritsu.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.debritsu.app.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Int) -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    authFlash: Int
) {

    var query by remember { mutableStateOf("") }
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
    val searching = query.length >= 3
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

    Scaffold(containerColor = Color.Transparent) { pad ->
        Column(Modifier.padding(pad)) {

            HomeTopBar(query, { query = it }, onDownloads, onSettings)

            // Only for shows still airing, and only while searching isn't in
            // the way. Someone who watches finished series will never see it,
            // which is the point — an empty strip is worse than no strip.
            if (!searching) {
                val airing = watching
                    .filter { it.nextEpisode != null && (it.airingInSeconds ?: 0) > 0 }
                    .sortedBy { it.airingInSeconds ?: Int.MAX_VALUE }
                if (airing.isNotEmpty()) AiringStrip(airing, onOpen)
            }

            if (Settings.addons.isEmpty()) {
                val cardShape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .raised(cardShape)
                        .clip(cardShape)
                        .background(Gloss.Chip)
                        .border(1.dp, Gloss.TopLight, cardShape)
                        .clickable(onClick = onSettings)
                        .padding(16.dp)
                ) {
                    Text("Add a source", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everything here plays from your Stremio addons. Paste an addon URL in Settings to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Mist,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            if (loading) {
                LinearProgressIndicator(
                    color = Ink.Candy,
                    trackColor = Color(0x1AFFFFFF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            // Anything already on a list is not a discovery, so the
            // recommendations drop it — and that means the whole list, not
            // just the two shelves above. Filtering on those alone let shows
            // through that were finished and marked completed years ago, which
            // is the one thing this shelf should never suggest.
            val onMyList = listed + (watching + planning).map { it.id }

            val shelves = buildList {
                if (watching.isNotEmpty()) add("Continue watching" to watching)
                if (planning.isNotEmpty()) add("Plan to watch" to planning)
                add("Trending" to trending)
                val fresh = recommended.filter { it.id !in onMyList }
                if (fresh.isNotEmpty()) add("Recommended" to fresh)
            }
            val openShelf = shelves.firstOrNull { it.first == expanded }

            // Back should close an expanded shelf rather than leaving Home.
            BackHandler(enabled = openShelf != null) { expanded = null }

            when {
                searching -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(browse) { PosterCard(it, onOpen) }
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

                openShelf != null -> ExpandedShelf(
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

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                    shelves.forEach { (title, list) ->
                        item { Shelf(title, list, onOpen) { expanded = title } }
                    }
                }
            }
        }
    }
}

/**
 * Search, with Downloads and Settings beside it.
 *
 * Search sits where the wordmark used to, alongside the actions. Two rows of
 * chrome before any content was a row too many, and the app's name is not
 * something anyone needs reminding of while they're using it.
 */
@Composable
internal fun HomeTopBar(
    query: String,
    onQuery: (String) -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)
    ) {
        val searchShape = RoundedCornerShape(24.dp)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search anime", color = Ink.Mist) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = Ink.Mist)
            },
            shape = searchShape,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color(0x1FFFFFFF),
                focusedBorderColor = Ink.Iris,
                unfocusedContainerColor = Color(0xFF1B1238),
                focusedContainerColor = Color(0xFF1F1541),
                cursorColor = Ink.Candy
            ),
            modifier = Modifier.weight(1f).raised(searchShape)
        )
        GlassIconButton(Icons.Default.Download, "Downloads", onDownloads)
        GlassIconButton(Icons.Default.Settings, "Settings", onSettings)
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
    // Only the shelf of shows under way carries "8 of 25" and a bar. Every
    // card in it has progress, so the extra line never makes one card in a
    // row taller than its neighbours.
    val underway = title == "Continue watching"
    Column {
        GlossyHeader(title, trailing = "See all", onTrailing = onExpand)
        LazyRow(
            // Bottom padding leaves room for the raised edge under each poster.
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { anime ->
                Box(Modifier.width(118.dp)) { PosterCard(anime, onOpen, showProgress = underway) }
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
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(list) { PosterCard(it, onOpen, showProgress = title == "Continue watching") }
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
 * A poster standing on its edge, with the icon's sheen across it. Shows under
 * way carry their episode as a violet badge; [showProgress] adds a bar through
 * the series and an "8 of 25" line, for the shelf where that is the point.
 */
@Composable
internal fun PosterCard(anime: Anime, onOpen: (Int) -> Unit, showProgress: Boolean = false) {
    Column(Modifier.clickable { onOpen(anime.id) }) {
        GlossyPoster(anime.cover, anime.title, Modifier.fillMaxWidth()) {
            if (anime.progress > 0) {
                Pill(
                    "EP ${anime.progress}",
                    brush = Gloss.Violet,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
            // Only when AniList has a score — a new or obscure title often has
            // none, and an empty pill reads worse than no pill — and only when
            // there is no episode badge: side by side on a poster this narrow
            // the two ran into each other, and for a show already under way
            // the episode is the more useful of the two.
            if (anime.progress == 0) anime.averageScore?.let { score ->
                Pill(
                    "★ $score%",
                    brush = SolidColor(Color(0xC70C081C)),
                    color = Ink.Gold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
            val total = anime.episodes ?: 0
            if (showProgress && anime.progress > 0 && total > 0) {
                JellyBar(
                    anime.progress.toFloat() / total,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .fillMaxWidth()
                )
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
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = Ink.Bone,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 11.dp)
        )
        if (showProgress) {
            Text(
                if (anime.progress > 0) "${anime.progress} of ${anime.episodes ?: "?"}" else " ",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = Ink.Mist
            )
        }
    }
}

/**
 * Everything being watched that has an episode still to come, soonest first.
 *
 * A row that scrolls rather than a single line: following three simulcasts at
 * once is normal, and showing only the nearest would quietly hide the rest.
 */
@Composable
internal fun AiringStrip(airing: List<Anime>, onOpen: (Int) -> Unit) {
    LazyRow(
        // The bottom padding keeps each chip's raised edge inside the layer
        // below, which is offscreen and so cuts off anything past its bounds.
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(bottom = 2.dp)
            // Fades the last chip into the edge rather than cutting it off, so
            // a row with more in it than fits says so without a scrollbar or a
            // count to read. Drawn with the layer so it fades the content
            // itself rather than painting a band over the top of it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // DstIn keeps the content where this brush is opaque and erases
                // it where the brush is clear, so the alpha runs 1 to 0.
                drawRect(
                    brush = Brush.horizontalGradient(
                        0.90f to Color.Black,
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = size.width
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        items(airing) { anime ->
            val chip = RoundedCornerShape(19.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(38.dp)
                    .raised(chip)
                    .clip(chip)
                    .background(Gloss.Chip)
                    .border(1.dp, Gloss.TopLight, chip)
                    .clickable { onOpen(anime.id) }
                    .padding(start = 14.dp, end = 6.dp)
            ) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = Ink.Bone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp)
                )
                Spacer(Modifier.width(8.dp))
                Pill("EP ${anime.nextEpisode}", brush = Gloss.Pink)
                Spacer(Modifier.width(6.dp))
                Text(
                    countdown(anime.airingInSeconds ?: 0),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = Ink.Mist,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
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
