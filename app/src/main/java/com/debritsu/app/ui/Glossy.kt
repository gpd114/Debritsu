package com.debritsu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The launcher icon's finish, carried into the app: things sit on a thicker
 * edge of a darker colour, catch light along their top, and the important ones
 * are jelly — a pink-to-violet sweep with a sheen across it.
 *
 * Kept to a handful of modifiers and components so every screen draws the same
 * edge, the same highlight and the same jelly rather than approximating them.
 */
object Gloss {
    /** Pink into violet: the primary action, and progress. */
    val Jelly = Brush.verticalGradient(
        0f to Color(0xFFFFA6DA), 0.55f to Color(0xFFC45CF2), 1f to Color(0xFF8B3FE0)
    )
    val JellyEdge = Color(0xFF4A1896)

    /** Violet: selected things, badges, the list-status control. */
    val Violet = Brush.verticalGradient(listOf(Color(0xFFA47BFF), Color(0xFF6D3FE0)))
    val VioletEdge = Color(0xFF2A1466)

    /** Pink: "episode N" tags, airing status. */
    val Pink = Brush.verticalGradient(listOf(Color(0xFFFF9BD5), Color(0xFFD9468F)))

    /** Raised chips and tiles. */
    val Chip = Brush.verticalGradient(listOf(Color(0xFF2F1F5E), Color(0xFF241749)))

    /** Frosted: icon buttons, the search field, quiet pills. */
    val Glass = Brush.verticalGradient(listOf(Color(0x29FFFFFF), Color(0x0FFFFFFF)))

    /** Progress bars, left to right. */
    val Progress = Brush.horizontalGradient(listOf(Color(0xFFFF8CCB), Color(0xFF9B6BFF)))

    /** What everything raised stands on. */
    val Edge = Color(0xFF0C0720)

    /** Light catching the top rim, fading out a third of the way down. */
    val TopLight = Brush.verticalGradient(0f to Color(0x40FFFFFF), 0.4f to Color(0x00FFFFFF))

    /**
     * The page: deep violet at the top, a glow off the top-left corner, near
     * black by the bottom — the icon's background, stretched to a screen.
     */
    val Backdrop = Brush.verticalGradient(
        0f to Color(0xFF1C1040), 0.45f to Color(0xFF110B24), 1f to Color(0xFF0B0816)
    )
}

/** The page backdrop, glow included. */
fun Modifier.glossyBackdrop() = background(Gloss.Backdrop).drawBehind {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xCC34197A), Color(0x0034197A)),
            center = Offset.Zero,
            radius = size.width * 1.1f
        )
    )
}

/**
 * A darker copy of [shape] drawn [depth] below, so the element stands on an
 * edge the way the icon's rice ball does. Put it before `clip` — it draws
 * outside the element's bounds, which clipping would cut off.
 */
fun Modifier.raised(shape: Shape, edge: Color = Gloss.Edge, depth: Dp = 3.dp) = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    translate(top = depth.toPx()) { drawOutline(outline, edge) }
}

/**
 * A diagonal sheen from the top-left corner, over whatever is underneath —
 * poster art included. Put it after `clip` so it keeps to the shape.
 */
fun Modifier.gloss(strength: Float = 0.26f) = drawWithContent {
    drawContent()
    drawRect(
        Brush.linearGradient(
            0f to Color.White.copy(alpha = strength),
            0.34f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(size.width * 0.6f, size.height)
        )
    )
}

/**
 * The jelly button: raised, glossy, with a soft band of light across its top.
 * Used for the one thing a screen is for — Resume, Play.
 */
@Composable
fun JellyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brush: Brush = Gloss.Jelly,
    edge: Color = Gloss.JellyEdge,
    height: Dp = 54.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .height(height)
            .raised(shape, edge, 5.dp)
            .clip(shape)
            .background(brush)
            .drawBehind {
                val inset = 14.dp.toPx()
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0x4DFFFFFF), Color(0x00FFFFFF)),
                        startY = 4.dp.toPx(),
                        endY = 16.dp.toPx()
                    ),
                    topLeft = Offset(inset, 4.dp.toPx()),
                    size = Size(size.width - inset * 2, 12.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
            }
            .border(1.5.dp, Gloss.TopLight, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

/** A square frosted button for an icon: downloads, settings, back. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = Color(0xFFE9E2FF)
) {
    val shape = RoundedCornerShape(size * 0.34f)
    Box(
        modifier
            .size(size)
            .raised(shape)
            .clip(shape)
            .background(Gloss.Glass)
            .background(Color(0x33140C2E))
            .border(1.dp, Gloss.TopLight, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * A small rounded label. Quiet by default; given a [brush] it becomes one of
 * the glossy tags — pink for an episode or airing, violet for a badge.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    color: Color = if (brush != null) Color.White else Color(0xFFDCD4F5),
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(brush ?: SolidColor(Color(0x17FFFFFF)))
            .border(1.dp, Gloss.TopLight, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/**
 * Poster art standing on its edge, with the sheen across it. Everything that
 * shows a cover — shelves, related shows, the detail header — draws it with
 * this, so they are all the same object. [overlay] is for badges on top.
 */
@Composable
fun GlossyPoster(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .raised(shape, Color(0xFF0A0619), 5.dp)
            .clip(shape)
            .background(Ink.Veil)
            .gloss()
            .border(1.5.dp, Color(0x24FFFFFF), shape)
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        overlay()
    }
}

/** A progress bar: dark track, jelly fill. [fraction] is 0..1. */
@Composable
fun JellyBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0xB30A0619))
            .drawBehind {
                drawRoundRect(
                    Gloss.Progress,
                    size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                    cornerRadius = CornerRadius(size.height / 2)
                )
            }
    )
}

/**
 * A raised panel that groups related things — a settings section, a download.
 * Content is laid out in a column with room between rows.
 */
@Composable
fun GlossyCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .raised(shape, depth = 4.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF231652), Color(0xFF1B1140))))
            .border(1.dp, Gloss.TopLight, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/**
 * One choice out of a few, as a row of chips of equal width — the chosen one
 * violet and raised, the rest flat. In place of Material's segmented buttons,
 * whose outlined look belonged to a different app.
 */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .then(if (on) Modifier.raised(shape, Gloss.VioletEdge) else Modifier)
                    .clip(shape)
                    .background(if (on) Gloss.Violet else SolidColor(Color(0x14FFFFFF)))
                    .border(1.dp, Gloss.TopLight, shape)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = if (on) Color.White else Ink.Mist,
                    maxLines = 1
                )
            }
        }
    }
}

/** A labelled switch on one line, the label taking the room. */
@Composable
fun GlossySwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Bone, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ink.Iris,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Ink.Mist,
                uncheckedTrackColor = Color(0xFF2A1F4C),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** Sliders in the app's colours: candy thumb, violet run. */
@Composable
fun glossySliderColors() = SliderDefaults.colors(
    thumbColor = Ink.Candy,
    activeTrackColor = Ink.Iris,
    inactiveTrackColor = Color(0x26FFFFFF),
    activeTickColor = Color(0x59FFFFFF),
    inactiveTickColor = Color(0x33FFFFFF)
)

/** Text fields: a dark well with a faint rim that turns violet when typing. */
@Composable
fun glossyFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Color(0x1FFFFFFF),
    focusedBorderColor = Ink.Iris,
    unfocusedContainerColor = Color(0xFF150E30),
    focusedContainerColor = Color(0xFF181033),
    cursorColor = Ink.Candy,
    focusedLabelColor = Color(0xFFCDBBFF),
    unfocusedLabelColor = Ink.Mist
)

/** Small print under a control: what it does, in a sentence or two. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9C94B8))
}

/**
 * The top of a secondary screen: a glass back button and the screen's name.
 * Stands in for Material's app bar, which drew an opaque strip of its own
 * across the backdrop.
 */
@Composable
fun GlossyTopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp))
    }
}

/** The four-pointed sparkle from the icon's background. */
val Sparkle: ImageVector by lazy {
    ImageVector.Builder("Sparkle", 10.dp, 10.dp, 10f, 10f).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(5f, 1f)
            quadTo(5.6f, 4.4f, 9f, 5f)
            quadTo(5.6f, 5.6f, 5f, 9f)
            quadTo(4.4f, 5.6f, 1f, 5f)
            quadTo(4.4f, 4.4f, 5f, 1f)
            close()
        }
    }.build()
}

/**
 * A shelf's heading: the title, a gold sparkle, and a quiet pill on the right
 * for whatever the shelf offers — See all, Close.
 */
@Composable
fun GlossyHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: () -> Unit = {},
    count: Int? = null
) {
    Row(
        modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink.Bone)
        count?.let {
            Text(
                "  $it",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF8C84A8)
            )
        }
        Icon(
            Sparkle,
            contentDescription = null,
            tint = Ink.Gold,
            modifier = Modifier.padding(start = 7.dp).size(14.dp)
        )
        Box(Modifier.weight(1f))
        trailing?.let {
            Pill(it, color = Color(0xFFCDBBFF), onClick = onTrailing)
        }
    }
}
