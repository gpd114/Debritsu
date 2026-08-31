package com.debritsu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
            launch { watching = runCatching { AniList.watching() }.getOrDefault(emptyList()) }
            launch { planning = runCatching { AniList.planning() }.getOrDefault(emptyList()) }
            launch { trending = runCatching { AniList.trending().items }.getOrDefault(emptyList()) }
            launch { recommended = runCatching { AniList.recommended().items }.getOrDefault(emptyList()) }
            launch { listed = runCatching { AniList.listedIds() }.getOrDefault(emptySet()) }
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

    Scaffold { pad ->
        Column(Modifier.padding(pad)) {

            // Search sits where the wordmark used to, alongside the actions.
            // Two rows of chrome before any content was a row too many, and the
            // app's name is not something anyone needs reminding of while
            // they're using it.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search anime") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Ink.Edge,
                        focusedBorderColor = Ink.Iris,
                        unfocusedContainerColor = Ink.Veil,
                        focusedContainerColor = Ink.Veil
                    ),
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                )
                IconButton(onClick = onDownloads) {
                    Icon(Icons.Default.Download, contentDescription = "Downloads")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }

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
                Surface(
                    onClick = onSettings,
                    color = Ink.Veil,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Add a source", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Debritsu plays what your Stremio addons return. Paste an addon URL to start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Mist
                        )
                    }
                }
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    onCollapse = { expanded = null }
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

/** One side-scrolling row of posters. */
@Composable
private fun Shelf(
    title: String,
    list: List<Anime>,
    onOpen: (Int) -> Unit,
    onExpand: () -> Unit
) {
    Column(Modifier.padding(top = 14.dp)) {
        SectionHeader(title, list.size, Icons.Default.OpenInFull, "Expand $title", onExpand)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { anime ->
                Box(Modifier.width(112.dp)) { PosterCard(anime, onOpen) }
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
    onCollapse: () -> Unit
) {
    Column {
        SectionHeader(title, list.size, Icons.Default.CloseFullscreen, "Collapse $title", onCollapse)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(list) { PosterCard(it, onOpen) }
        }
    }
}

@Composable
private fun SectionHeader(
    text: String,
    count: Int,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Starts level with the posters, which are inset by the same amount.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Mist
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAction) {
            Icon(actionIcon, contentDescription = actionLabel, tint = Ink.Mist)
        }
    }
}

@Composable
private fun PosterCard(anime: Anime, onOpen: (Int) -> Unit) {
    Column(Modifier.clickable { onOpen(anime.id) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.Veil)
        ) {
            AsyncImage(
                model = anime.cover,
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // In-progress shows get a violet spine and an episode readout.
            if (anime.progress > 0) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(Ink.Iris)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC08070D))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        "EP ${anime.progress.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Bone
                    )
                }
            }
            // Opposite corner to the episode badge, and only when AniList has a
            // score — a new or obscure title often has none, and an empty pill
            // reads worse than no pill.
            anime.averageScore?.let { score ->
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC08070D))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        "$score%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (score >= 75) Ink.Iris else Ink.Bone
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
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

/**
 * Everything being watched that has an episode still to come, soonest first.
 *
 * A row that scrolls rather than a single line: following three simulcasts at
 * once is normal, and showing only the nearest would quietly hide the rest.
 */
@Composable
private fun AiringStrip(airing: List<Anime>, onOpen: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.Veil)
                    .clickable { onOpen(anime.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    anime.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "EP ${anime.nextEpisode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Orchid
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    countdown(anime.airingInSeconds ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Mist,
                    maxLines = 1
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
