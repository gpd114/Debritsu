package com.debritsu.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn from path data rather than depended on.
 *
 * Compose ships exactly one of these in its core icon set — PlayArrow — and
 * everything else lives in material-icons-extended, which is tens of megabytes
 * of vector definitions. Android shrinks that away at build time; a desktop
 * build has no such pass, so the whole artifact would go into the installer to
 * fetch a dozen glyphs. These are the standard Material shapes on the usual
 * 24x24 grid, written out.
 */
private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
    }.build()

object AppIcons {

    val Play = icon("Play", "M8 5v14l11-7z")

    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")

    val PreviousEpisode = icon("PreviousEpisode", "M6 6h2v12H6zm3.5 6l8.5 6V6z")

    val NextEpisode = icon("NextEpisode", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")

    val Rewind = icon("Rewind", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")

    val Forward = icon("Forward", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")

    val Back = icon("Back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")

    val Subtitles = icon(
        "Subtitles",
        "M19 4H5c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 7H9.5" +
            "v-.5h-2v3h2V13H11v1c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 " +
            ".45 1 1v1zm7 0h-1.5v-.5h-2v3h2V13H18v1c0 .55-.45 1-1 1h-3c-.55 0-1-.45-1-1v-4c0-.55" +
            ".45-1 1-1h3c.55 0 1 .45 1 1v1z"
    )

    val Audio = icon(
        "Audio",
        "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
    )

    val Sources = icon(
        "Sources",
        "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"
    )

    val Fullscreen = icon(
        "Fullscreen",
        "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"
    )

    val Windowed = icon(
        "Windowed",
        "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"
    )

    val Refresh = icon(
        "Refresh",
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 " +
            "7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 " +
            "4.22 1.78L13 11h7V4l-2.35 2.35z"
    )

    val Download = icon("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")

    val Settings = icon(
        "Settings",
        "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61" +
            "l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-." +
            "04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94" +
            "l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.0" +
            "9.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59" +
            ".22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47" +
            "-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-." +
            "22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6" +
            " 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
    )
}

/**
 * One icon that does something, for the bar along the top.
 *
 * No tooltip. The player's had one and it swallowed roughly two presses in
 * three, and nothing here is worth risking that for — a gear, a down arrow and
 * a circling arrow are about as widely understood as icons get. The name is
 * still the content description, which is what a screen reader reads.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    name: String,
    onClick: () -> Unit,
    tint: Color = Color(0xFF948CAB),
    /** Written beside the glyph, for a count worth knowing without opening it. */
    badge: String? = null
) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
        badge?.let {
            Text(
                it,
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 3.dp)
            )
        }
    }
}
