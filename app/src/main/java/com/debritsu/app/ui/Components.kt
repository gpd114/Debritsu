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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The pieces every screen is built from, flat: solid fills on rounded shapes,
 * a faint rim where something needs an edge against the page, and the accent
 * kept for the one action a screen is for.
 *
 * There was a glossy finish here once — raised edges, light along the top
 * rims, a sheen across posters — taken from the launcher icon. It came out in
 * favour of this, so nothing below should grow a highlight back.
 */
object Fills {
    private val p get() = Ink.palette

    /** Selected things: the lit episode, the chosen option, the list status. */
    val Selected get() = SolidColor(p.selected)

    /** Tags: "Episode N", airing status, the best source. */
    val Tag get() = SolidColor(p.tag)

    /** Chips, tiles and cards. */
    val Chip get() = SolidColor(p.chip)

    /** Secondary and icon buttons. */
    val Glass get() = SolidColor(p.glass)
}

/** The page. */
fun Modifier.pageBackground(): Modifier = background(Ink.Base)

/** The main button — Resume, Play — solid in the accent. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brush: Brush = SolidColor(Ink.palette.action),
    height: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(brush)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

/**
 * A secondary button with a label — Details, Sources, Sign out. Sits beside a
 * [PrimaryButton] without competing with it.
 */
@Composable
fun SecondaryButton(
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
            .clip(shape)
            .background(Fills.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = color)
    }
}

/** A square button for an icon: downloads, settings, back. */
@Composable
fun SquareIconButton(
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
            .clip(shape)
            .background(Fills.Glass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/**
 * A round icon button for laying over artwork — the hero's search, downloads
 * and settings, a show's back arrow. On a wash of the page's colour so it
 * reads on any picture: pale with an accent icon on Pastel, dark with a white
 * one on the dark themes, matching the haze along the top of the art.
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
    val washColour = if (Ink.palette.dark) Ink.Base.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.7f)
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
 * A small rounded label. Quiet by default; given a [brush] it is filled with
 * it and the text turns white.
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/**
 * Poster art, rounded, with a faint rim so a dark cover still has an edge
 * against the page. Everything that shows a cover draws it with this, so they
 * are all the same object. [overlay] is for badges on top.
 */
@Composable
fun PosterArt(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    aspect: Float = 2f / 3f,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(aspect)
            .clip(shape)
            .background(Ink.Veil)
            .border(1.dp, Ink.Hairline, shape)
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

/** A progress bar: a track and a fill in the accent. [fraction] is 0..1. */
@Composable
fun ProgressLine(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = Ink.Edge
) {
    val fill = Ink.palette.progress
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
 * A panel that groups related things — a settings section, a download.
 * Content is laid out in a column with room between rows.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Fills.Chip)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/**
 * One choice out of a few, as a row of chips of equal width — the chosen one
 * filled in the accent, the rest quiet. In place of Material's segmented
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
                    .clip(shape)
                    .background(if (on) Fills.Selected else SolidColor(Ink.Quiet))
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
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
fun sliderColors() = SliderDefaults.colors(
    thumbColor = Ink.Iris,
    activeTrackColor = Ink.Iris,
    inactiveTrackColor = Ink.Edge,
    activeTickColor = Color.White.copy(alpha = 0.5f),
    inactiveTickColor = Ink.Mist.copy(alpha = 0.35f)
)

/** Text fields: a well with a faint rim that turns to the accent when typing. */
@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
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
 * The top of a secondary screen: a back button and the screen's name. Stands
 * in for Material's app bar, which drew an opaque strip of its own across the
 * page.
 */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        SquareIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Spacer(Modifier.width(14.dp))
        Text(title, style = MaterialTheme.typography.displaySmall.copy(fontSize = 26.sp), color = Ink.Bone)
    }
}

/**
 * A row's heading: the title, an optional count, and a quiet link on the
 * right for whatever the row offers — See all, Close.
 */
@Composable
fun RowHeader(
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
