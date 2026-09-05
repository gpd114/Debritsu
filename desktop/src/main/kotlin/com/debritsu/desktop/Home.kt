package com.debritsu.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Anime

private val ShelfViolet = Color(0xFF8B5CF6)
private val ShelfPaper = Color(0xFFF1EEF8)

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

/** One horizontal row of shows, as on the phone and the television. */
@Composable
fun Shelf(title: String, list: List<Anime>, onOpen: (Anime) -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 28.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(list) { anime -> PosterCard(anime) { onOpen(anime) } }
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
