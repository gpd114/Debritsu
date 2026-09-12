package com.debritsu.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.debritsu.app.data.Anime
import com.debritsu.app.data.Mappings
import com.debritsu.app.ui.Ink

/**
 * The phone's look, for a television: a show's own wide artwork behind the
 * words, flat fills, the theme's accent for the one thing a screen is for,
 * and rounded cards that tv-material outlines in the accent when the remote is
 * on them.
 *
 * Focus is the one place this departs from the phone. Whatever the remote is
 * on becomes a pill in the page's strongest contrast — white on the dark
 * themes, near-black on Pastel — because from across a room focus has to be
 * the most obvious thing on the screen, and an accent fill on a dark page is
 * not.
 */

/** Rounded corners for every tv-material card on these screens. */
@OptIn(ExperimentalTvMaterial3Api::class)
internal val TvCardShape @Composable get() = CardDefaults.shape(RoundedCornerShape(14.dp))

/**
 * Wide artwork for a show: TVDB's fanart where ani.zip has it — 1920x1080,
 * the shape of the screen — else AniList's banner, else the cover.
 *
 * The previous show's art stays until the next is known. Cleared on every
 * change, Home's backdrop blinked empty at each press of the remote while the
 * lookup was out; kept, it simply crossfades from one show to the next.
 */
@Composable
internal fun rememberTvArt(anime: Anime?): Any? {
    var art by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(anime?.id) {
        val a = anime ?: return@LaunchedEffect
        art = Mappings.fanart(a.id) ?: a.banner ?: a.cover
    }
    return art
}

/**
 * The artwork as a backdrop: filling the width it is given, faded to nothing
 * along the bottom and along the left so it dissolves into the page wherever
 * the words are. Faded by masking the picture itself rather than laying a
 * colour over it, so there is no seam where a guessed colour meets the page.
 */
@Composable
internal fun TvBackdrop(model: Any?, modifier: Modifier = Modifier, fadeLeft: Boolean = true) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(true).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
                if (fadeLeft) {
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Black),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    )
}

/**
 * Every button on these screens: [fill] when idle, the focus pill when the
 * remote is on it. One composable whose colours change rather than separate
 * ones swapped in and out — swapping would put a different node in the tree
 * the moment a choice is pressed, and the focus the remote was holding would
 * go with the old one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvButton(
    onClick: () -> Unit,
    fill: Color,
    content: Color,
    modifier: Modifier = Modifier,
    body: @Composable RowScope.() -> Unit
) {
    val p = Ink.palette
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(26.dp)),
        colors = ButtonDefaults.colors(
            containerColor = fill,
            contentColor = content,
            focusedContainerColor = p.bone,
            focusedContentColor = p.base,
            pressedContainerColor = p.bone,
            pressedContentColor = p.base
        ),
        content = body
    )
}

/** The one thing a screen is for — Play, Resume — filled in the accent. */
@Composable
internal fun TvPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    TvButton(onClick, Ink.palette.action, Color.White, modifier, content)

/** Everything else — Details, Sources, Search — on a quiet ground. */
@Composable
internal fun TvSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    TvButton(onClick, Ink.palette.glass, Ink.Link, modifier, content)

/**
 * One of a set of choices: the chosen one filled in the accent, the rest
 * quiet, so which is current reads from across a room without a marker in the
 * text.
 */
@Composable
internal fun TvChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Ink.palette
    TvButton(
        onClick,
        if (selected) p.selected else p.glass,
        if (selected) Color.White else Ink.Link
    ) { Text(label) }
}

/** A badge over artwork: tinted in the accent, white text, legible on any poster. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvBadge(text: String, modifier: Modifier = Modifier, solid: Boolean = false) {
    Box(
        modifier
            .padding(7.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (solid) Ink.palette.selected else Ink.palette.badge)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
