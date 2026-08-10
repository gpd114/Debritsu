package com.debritsu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
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
    var browse by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    suspend fun fetch(p: Int) =
        if (query.length >= 3) AniList.search(query, p) else AniList.trending(p)

    LaunchedEffect(authFlash) {
        watching = runCatching { AniList.watching() }.getOrDefault(emptyList())
    }

    // Reset to page one whenever the query changes.
    LaunchedEffect(query, authFlash) {
        if (query.isNotEmpty() && query.length < 3) return@LaunchedEffect
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("debritsu", style = MaterialTheme.typography.displaySmall)
                        Box(
                            Modifier
                                .padding(start = 6.dp, top = 10.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Ink.Orchid)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDownloads) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

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

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (watching.isNotEmpty() && query.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Continue watching", watching.size) }
                    items(watching) { PosterCard(it, onOpen) }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Trending", browse.size) }
                }
                items(browse) { PosterCard(it, onOpen) }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Ink.Iris, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Mist
        )
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
        }
        Text(
            anime.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}
