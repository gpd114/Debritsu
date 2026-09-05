package com.debritsu.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debritsu.app.data.Anime

private val ShelfViolet = Color(0xFF8B5CF6)

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
        RemoteImage(
            url = anime.cover,
            modifier = Modifier.width(132.dp).height(186.dp),
            fallback = anime.title
        )
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
