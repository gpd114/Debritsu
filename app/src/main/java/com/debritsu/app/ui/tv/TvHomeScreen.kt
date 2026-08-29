package com.debritsu.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.debritsu.app.data.AniList
import com.debritsu.app.data.Anime
import com.debritsu.app.ui.Ink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Televisions cut a slice off every edge, varying by set. Content is kept
 * inside this margin.
 */
private val OVERSCAN = 27.dp

private val POSTER_WIDTH = 140.dp

/**
 * The browse screen, as rows of shows.
 *
 * Focus is left entirely to tv-material's [Card], which scales and outlines
 * itself when selected and moves correctly under a d-pad. The equivalent was
 * hand-written on the phone build and went wrong repeatedly; none of that is
 * repeated here.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onOpen: (Int) -> Unit,
    authFlash: Int
) {
    var watching by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var planning by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Anime>>(emptyList()) }

    // Three independent queries, run together rather than one after another.
    // Each row is assigned on its own so it appears as its own query lands
    // instead of every row waiting for the slowest.
    LaunchedEffect(authFlash) {
        coroutineScope {
            launch { watching = runCatching { AniList.watching() }.getOrDefault(emptyList()) }
            launch { planning = runCatching { AniList.planning() }.getOrDefault(emptyList()) }
            launch { trending = runCatching { AniList.trending().items }.getOrDefault(emptyList()) }
        }
    }

    val shelves = buildList {
        if (watching.isNotEmpty()) add("Continue watching" to watching)
        if (planning.isNotEmpty()) add("Plan to watch" to planning)
        if (trending.isNotEmpty()) add("Trending" to trending)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Base)
            .padding(OVERSCAN)
    ) {
        Text(
            "Debritsu",
            style = androidx.tv.material3.MaterialTheme.typography.headlineMedium,
            color = Ink.Bone,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(shelves) { (title, list) ->
                TvShelf(title, list, onOpen)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvShelf(title: String, list: List<Anime>, onOpen: (Int) -> Unit) {
    Column {
        Text(
            title,
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            color = Ink.Bone,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Room for a focused card to grow into without being clipped by the
            // row's own bounds.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            items(list) { anime -> TvPoster(anime, onOpen) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPoster(anime: Anime, onOpen: (Int) -> Unit) {
    Column(Modifier.width(POSTER_WIDTH)) {
        Card(onClick = { onOpen(anime.id) }) {
            AsyncImage(
                model = anime.cover,
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Ink.Veil)
            )
        }
        // Both lines reserved whether the title needs them or not. Cards of
        // differing height in one row let a taller one hang below its
        // neighbours, and a downward press then finds that neighbour rather
        // than the row beneath — which is the bug that cost the phone build an
        // evening.
        Text(
            anime.title,
            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
            color = Ink.Mist,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
    }
}
