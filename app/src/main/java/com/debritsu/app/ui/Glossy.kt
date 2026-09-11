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
import androidx.compose.ui.draw.shadow
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
 * The launcher icon's finish, carried into the app: things sit on a slightly
 * darker edge, catch light along their top, and the one action a screen is for
 * is jelly — a periwinkle sweep with a sheen across it.
 *
 * Every brush here is built from the current [Palette], so the same pieces
 * serve both themes. Kept to a handful of modifiers and components so every
 * screen draws the same edge, highlight and jelly rather than approximating.
 */
object Gloss {
    private val p get() = Ink.palette

    /** The primary action. */
    val Jelly get() = Brush.verticalGradient(0f to p.jelly[0], 0.55f to p.jelly[1], 1f to p.jelly[2])
    val JellyEdge get() = p.jellyEdge

    /** Selected things: the lit episode, the chosen option, the list status. */
    val Selected get() = Brush.verticalGradient(p.selected)
    val SelectedEdge get() = p.selectedEdge

    /** Tags: "Episode N", airing status, the best source. */
    val Tag get() = Brush.verticalGradient(p.tag)

    /** Raised chips, tiles and cards. */
    val Chip get() = Brush.verticalGradient(p.chip)

    /** Frosted: icon buttons and secondary buttons. */
    val Glass get() = Brush.verticalGradient(p.glass)

    /** Progress bars, left to right. */
    val Progress get() = Brush.horizontalGradient(p.progress)

    /** What everything raised stands on. */
    val Edge get() = p.raise

    /** Light catching the top rim, fading out a third of the way down. */
    val TopLight get() = Brush.verticalGradient(0f to p.topLight, 0.4f to p.topLight.copy(alpha = 0f))

    /** The page, top to bottom. */
    val Backdrop get() = Brush.verticalGradient(0f to p.backdrop[0], 0.45f to p.backdrop[1], 1f to p.backdrop[2])
}

/** The page backdrop, with a soft glow off the top-left corner. */
fun Modifier.glossyBackdrop(): Modifier {
    val glow = Ink.palette.glow
    return background(Gloss.Backdrop).drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(glow, glow.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = size.width * 1.1f
            )
        )
    }
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
 * A soft coloured shadow under a card, for the pastel theme, where an edge
 * alone reads flat against a light page. Nothing on Night, where it would
 * not show. Put it before `clip`.
 */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp): Modifier =
    if (Ink.palette.dark) this
    else shadow(elevation, shape, clip = false, ambientColor = Color(0x335E69EA), spotColor = Color(0x405E69EA))

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
    height: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .height(height)
            .softShadow(shape, 12.dp)
            .raised(shape, edge, 4.dp)
            .clip(shape)
            .background(brush)
            .drawBehind {
                val inset = 16.dp.toPx()
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)),
                        startY = 4.dp.toPx(),
                        endY = 15.dp.toPx()
                    ),
                    topLeft = Offset(inset, 4.dp.toPx()),
                    size = Size(size.width - inset * 2, 11.dp.toPx()),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
            }
            .border(1.5.dp, Brush.verticalGradient(0f to Color(0x66FFFFFF), 0.4f to Color(0x00FFFFFF)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

/**
 * A frosted secondary button with a label — Details, Sources, Sign out. Sits
 * beside a [JellyButton] without competing with it.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    color: Color = Ink.Link
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .height(height)
            .softShadow(shape, 6.dp)
            .raised(shape)
            .clip(shape)
            .background(Gloss.Glass)
            .border(1.dp, Gloss.TopLight, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = color)
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
    tint: Color = Ink.GlassIcon
) {
    val shape = RoundedCornerShape(size * 0.34f)
    Box(
        modifier
            .size(size)
            .softShadow(shape, 6.dp)
            .raised(shape)
            .clip(shape)
            .background(Gloss.Glass)
            .border(1.dp, Gloss.TopLight, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * A round icon button for laying over artwork — the hero's search, downloads
 * and settings, a show's back arrow. On a frosted wash so it reads on any
 * picture: pale with a periwinkle icon on Pastel, dark with a white one on
 * Night, matching the haze along the top of the art.
 */
@Composable
fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wash: Boolean = true,
    tint: Color = if (Ink.palette.dark) Color.White else Ink.GlassIcon
) {
    val washColour = if (Ink.palette.dark) Color(0x4D0B0A14) else Color(0xB3FFFFFF)
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(if (wash) Modifier.background(washColour) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * A small rounded label. Quiet by default; given a [brush] it becomes one of
 * the glossy tags.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    color: Color = if (brush != null) Color.White else Ink.QuietText,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(brush ?: SolidColor(Ink.Quiet))
            .then(if (brush != null) Modifier.border(1.dp, Brush.verticalGradient(0f to Color(0x59FFFFFF), 0.5f to Color(0x00FFFFFF)), shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/**
 * Poster art on its edge, with the sheen across it — a white border and a soft
 * shadow on Pastel. Everything that shows a cover draws it with this, so they
 * are all the same object. [overlay] is for badges on top.
 */
@Composable
fun GlossyPoster(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    aspect: Float = 2f / 3f,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(corner)
    val dark = Ink.palette.dark
    Box(
        modifier
            .aspectRatio(aspect)
            .softShadow(shape, 10.dp)
            .raised(shape, if (dark) Color(0xFF07060E) else Gloss.Edge, 4.dp)
            .clip(shape)
            .background(Ink.Veil)
            .gloss(if (dark) 0.22f else 0.3f)
            .border(if (dark) 1.dp else 2.dp, if (dark) Color(0x1FFFFFFF) else Color.White, shape)
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

/** A progress bar: a track and a periwinkle fill. [fraction] is 0..1. */
@Composable
fun JellyBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = Ink.Edge
) {
    val fill = Gloss.Progress
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(track)
            .drawBehind {
                drawRoundRect(
                    fill,
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
            .softShadow(shape, 8.dp)
            .raised(shape, depth = 3.dp)
            .clip(shape)
            .background(Gloss.Chip)
            .border(1.dp, Gloss.TopLight, shape)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/**
 * One choice out of a few, as a row of chips of equal width — the chosen one
 * periwinkle and raised, the rest flat. In place of Material's segmented
 * buttons, whose outlined look belonged to a different app.
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
                    .then(if (on) Modifier.raised(shape, Gloss.SelectedEdge) else Modifier)
                    .clip(shape)
                    .background(if (on) Gloss.Selected else SolidColor(Ink.Quiet))
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
                uncheckedTrackColor = Ink.Edge,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** Sliders in the app's colours. */
@Composable
fun glossySliderColors() = SliderDefaults.colors(
    thumbColor = Ink.Iris,
    activeTrackColor = Ink.Iris,
    inactiveTrackColor = Ink.Edge,
    activeTickColor = Color.White.copy(alpha = 0.5f),
    inactiveTickColor = Ink.Mist.copy(alpha = 0.35f)
)

/** Text fields: a well with a faint rim that turns periwinkle when typing. */
@Composable
fun glossyFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Ink.Edge,
    focusedBorderColor = Ink.Iris,
    unfocusedContainerColor = Ink.Field,
    focusedContainerColor = Ink.Field,
    cursorColor = Ink.Iris,
    focusedLabelColor = Ink.Link,
    unfocusedLabelColor = Ink.Mist,
    focusedTextColor = Ink.Bone,
    unfocusedTextColor = Ink.Bone
)

/** Small print under a control: what it does, in a sentence or two. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Mist)
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
        Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp), color = Ink.Bone)
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
 * A row's heading: the title, an optional count, and a quiet link on the
 * right for whatever the row offers — See all, Close.
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
        modifier.padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = Ink.Bone)
        count?.let {
            Text("  $it", style = MaterialTheme.typography.labelMedium, color = Ink.Dim)
        }
        Box(Modifier.weight(1f))
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = Ink.Mist,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onTrailing)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
