package com.debritsu.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debritsu.app.data.Anime
import com.debritsu.app.data.Mappings
import com.debritsu.app.data.Progress
import kotlinx.coroutines.delay

/**
 * The phone's hero, laid out for a wide window: the show you are most likely
 * to carry on with, its artwork, and Resume.
 *
 * The phone stacks the words under a full-width picture. A window is wide and
 * short, so here the picture keeps its own 16:9 shape against the right edge
 * and fades out leftwards, and the words sit beside it. Cropping fanart to a
 * full-width strip would show a slice from its middle, magnified.
 *
 * Moves on to the next show every eight seconds, as the phone's does, but not
 * while the pointer is over it: nothing should change under a hand reaching
 * for Resume. It does not follow the pointer across the shelves either.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeHero(
    shows: List<Anime>,
    /** Shows under way, rather than trending ones for an empty list. */
    underway: Boolean,
    onOpen: (Anime) -> Unit,
    onResume: (Anime, Int) -> Unit
) {
    if (shows.isEmpty()) return
    var index by remember(shows.map { it.id }) { mutableStateOf(0) }
    var hovered by remember { mutableStateOf(false) }
    LaunchedEffect(index, shows.size, hovered) {
        if (shows.size < 2 || hovered) return@LaunchedEffect
        delay(8_000)
        index = (index + 1) % shows.size
    }
    val anime = shows[index.coerceIn(0, shows.lastIndex)]

    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
    ) {
        Crossfade(anime, animationSpec = tween(600), modifier = Modifier.fillMaxSize()) { shown ->
            Box(Modifier.fillMaxSize()) {
                HeroArt(shown, Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f))
                HeroWords(
                    shown,
                    underway,
                    onOpen,
                    onResume,
                    Modifier.align(Alignment.CenterStart).padding(start = 28.dp).widthIn(max = 540.dp)
                )
            }
        }
        if (shows.size > 1) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                shows.indices.forEach { i ->
                    val on = i == index
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(if (on) 18.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (on) Ink.Candy else Ink.Edge)
                            .clickable { index = i }
                    )
                }
            }
        }
    }
}

/**
 * TVDB's fanart where ani.zip has it, else AniList's banner, else the cover,
 * faded out along its left and bottom edges so it melts into the page.
 */
@Composable
private fun HeroArt(anime: Anime, modifier: Modifier) {
    var art by remember(anime.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(anime.id) {
        art = runCatching { Mappings.fanart(anime.id) }.getOrNull() ?: anime.banner ?: anime.cover
    }
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.4f to Color.Black),
                    blendMode = BlendMode.DstIn
                )
                drawRect(
                    Brush.verticalGradient(0.6f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        // Nothing until the lookup answers, so a banner is not drawn and then
        // swapped a moment later for the fanart.
        art?.let { RemoteImage(it, Modifier.fillMaxSize(), corner = 0) }
    }
}

@Composable
private fun HeroWords(
    anime: Anime,
    underway: Boolean,
    onOpen: (Anime) -> Unit,
    onResume: (Anime, Int) -> Unit,
    modifier: Modifier
) {
    val total = anime.episodes ?: 0
    // The episode Resume plays: the one after the last watched, never past the end.
    val next = (anime.progress + 1).let { if (total > 0) it.coerceAtMost(total) else it }
    val partWatched = remember(anime.id, next) { Progress.fraction(anime.id, next) }

    Column(modifier) {
        Text(
            if (underway) "CONTINUE WATCHING" else "TRENDING NOW",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
            color = Ink.Candy
        )
        Text(
            anime.title,
            style = MaterialTheme.typography.displaySmall,
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
                anime.averageScore?.let { "$it%" },
                anime.episodes?.let { if (it == 1) "1 episode" else "$it episodes" }
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = Ink.Mist
        )
        if (underway && total > 0) {
            Box(
                Modifier.padding(top = 10.dp).width(320.dp).height(5.dp)
                    .clip(CircleShape).background(Ink.Edge)
            ) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth((anime.progress.toFloat() / total).coerceIn(0f, 1f))
                        .clip(CircleShape).background(Ink.palette.progress)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 18.dp)
        ) {
            PrimaryButton(onClick = { onResume(anime, next) }) {
                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (underway) "Resume" else "Play", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(10.dp))
            TextButton(
                onClick = { onOpen(anime) },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Ink.palette.glass,
                    contentColor = Ink.GlassIcon
                )
            ) {
                Text("Details", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
