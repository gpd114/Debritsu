package com.debritsu.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Anime

private val ShelfViolet = Color(0xFF8B5CF6)
private val ShelfPaper = Color(0xFFF1EEF8)
private val ShelfMuted = Color(0xFF948CAB)

/**
 * "in 2d 4h", "in 5h 12m", "in 48m" — as much as is worth reading.
 *
 * Minutes stop mattering once it is days away and seconds never mattered, so
 * each unit only shows the one below it.
 */
fun airsIn(seconds: Int): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "in ${days}d ${hours}h"
        hours > 0 -> "in ${hours}h ${minutes}m"
        minutes > 0 -> "in ${minutes}m"
        else -> "any moment"
    }
}

/**
 * Green, amber or rust by how well regarded a show is.
 *
 * AniList scores cluster hard in the seventies and eighties, so the boundaries
 * are set where the distribution actually is rather than at halfway — a 70%
 * anime is unremarkable, not good, and colouring it green would say nothing.
 * Semantic, and deliberately not the app's violet, which means "yours" here.
 */
private fun ScoreColour(score: Int): Color = when {
    score >= 80 -> Color(0xCC2F7D5B)
    score >= 70 -> Color(0xCC8A6A2F)
    else -> Color(0xCC8A4433)
}

/**
 * What is airing soon, soonest first, along the top bar.
 *
 * A row rather than the single soonest, because "when is there something new"
 * is rarely a question about one show — and a strip that only ever names one
 * of six looks like it has nothing more to say.
 *
 * The scrollbar is drawn rather than left to be discovered. A row that runs off
 * the edge is only obviously scrollable once you have already tried, and on a
 * desktop a wheel does not scroll sideways, so without it the rest of the list
 * may as well not exist. Chevrons move it a card at a time for the same reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiringStrip(airing: List<Anime>, modifier: Modifier = Modifier, onOpen: (Anime) -> Unit) {
    val row = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // A thumb that fills its track says "nothing hidden" as clearly as an empty
    // one says the opposite, so the controls only appear when they mean
    // something.
    val scrollable by remember {
        derivedStateOf {
            val info = row.layoutInfo
            info.totalItemsCount > info.visibleItemsInfo.size
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Airing next",
                color = ShelfViolet,
                style = MaterialTheme.typography.labelSmall
            )

            if (scrollable) {
                Text(
                    " ‹ ",
                    color = ShelfMuted,
                    modifier = Modifier.clickable {
                        scope.launch {
                            row.animateScrollToItem((row.firstVisibleItemIndex - 2).coerceAtLeast(0))
                        }
                    }
                )
            }

            LazyRow(
                state = row,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(airing, key = { it.id }) { show ->
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(ShelfMuted.copy(alpha = 0.12f))
                            .clickable { onOpen(show) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            show.title,
                            color = ShelfPaper,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                        Text(
                            "  ep ${show.nextEpisode}  ${airsIn(show.airingInSeconds ?: 0)}",
                            color = ShelfMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            if (scrollable) {
                Text(
                    " › ",
                    color = ShelfMuted,
                    modifier = Modifier.clickable {
                        scope.launch {
                            row.animateScrollToItem(row.firstVisibleItemIndex + 2)
                        }
                    }
                )
            }
        }

        if (scrollable) {
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(row),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 14.dp)
            )
        }
    }
}

/** One horizontal row of shows, as on the phone and the television. */
@Composable
fun Shelf(
    title: String,
    list: List<Anime>,
    /** Offered where there is more than a row's worth to be had. */
    onExpand: (() -> Unit)? = null,
    onOpen: (Anime) -> Unit
) {
    Column {
        Row(
            Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            onExpand?.let {
                TextButton(onClick = it, modifier = Modifier.padding(start = 6.dp)) {
                    Text(
                        "Expand",
                        color = ShelfMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { anime -> PosterCard(anime) { onOpen(anime) } }
        }
    }
}

/**
 * One shelf opened out into a page of posters, fetching more as it is read.
 *
 * A row shows about eight of the forty a page brings back, and the rest were
 * reachable only by dragging sideways. This is the same list without that
 * limit: as many columns as the window fits, and the next page asked for
 * shortly before the end rather than at it, so the scroll does not stop dead
 * while it waits.
 */
@Composable
fun ExpandedShelf(
    title: String,
    list: List<Anime>,
    loading: Boolean,
    hasMore: Boolean,
    onNearEnd: () -> Unit,
    onCollapse: () -> Unit,
    onOpen: (Anime) -> Unit
) {
    val grid = rememberLazyGridState()

    // Watched rather than polled. snapshotFlow reports only when the value
    // changes, so this runs on crossing the threshold instead of on every
    // frame of a scroll.
    LaunchedEffect(grid, list.size, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= list.size - 12) onNearEnd() }
    }

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(
            Modifier.padding(start = 28.dp, end = 28.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onCollapse, modifier = Modifier.padding(start = 6.dp)) {
                Text("Collapse", color = ShelfMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                when {
                    loading -> "${list.size} shown  ·  fetching more…"
                    hasMore -> "${list.size} shown"
                    else -> "${list.size} shown  ·  that is all of them"
                },
                color = ShelfMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyVerticalGrid(
            state = grid,
            // Sized to the poster rather than to a column count, so a wider
            // window means more per row instead of bigger gaps.
            columns = GridCells.Adaptive(144.dp),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(list, key = { it.id }) { anime -> PosterCard(anime) { onOpen(anime) } }
        }
    }
}

/**
 * A show as a poster.
 *
 * The next episode is written on the card rather than only inside it, because
 * the point of the first shelf is knowing what to play without opening
 * anything first.
 */
@Composable
private fun PosterCard(anime: Anime, onOpen: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onOpen)) {
        Box {
            RemoteImage(
                url = anime.cover,
                modifier = Modifier.width(132.dp).height(186.dp),
                fallback = anime.title
            )
            // On the artwork rather than beneath it: the score is the reason to
            // look twice at a poster you do not recognise, and under the title
            // it competes with the title instead of qualifying the picture.
            anime.averageScore?.takeIf { it > 0 }?.let { score ->
                Text(
                    "$score%",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfPaper,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ScoreColour(score))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            anime.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (anime.progress > 0) {
            Text(
                "Next: episode ${anime.progress + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = ShelfViolet
            )
        }
    }
}
