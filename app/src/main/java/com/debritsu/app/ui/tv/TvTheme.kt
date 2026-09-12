package com.debritsu.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.debritsu.app.ui.Ink
import com.debritsu.app.ui.Rounded

/**
 * The app's own colours and type, given to tv-material.
 *
 * Without this its components fall back to their defaults, which is why every
 * button once came out a pale lavender belonging to no part of this app. The
 * library draws focus states from the scheme too, so the selection highlight
 * follows from here rather than being painted on at each call site — the
 * border in particular is what outlines a focused card, and it is the theme's
 * accent so the remote's position is never a guess.
 *
 * Read from the current palette, so switching theme in Settings recolours the
 * library's components along with everything else.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DebritsuTvTheme(content: @Composable () -> Unit) {
    val p = Ink.palette
    val scheme = if (p.dark) darkColorScheme(
        primary = p.iris, onPrimary = Color.White,
        primaryContainer = p.iris, onPrimaryContainer = Color.White,
        secondary = p.orchid, onSecondary = p.base,
        background = p.base, onBackground = p.bone,
        surface = p.veil, onSurface = p.bone,
        surfaceVariant = p.edge, onSurfaceVariant = p.mist,
        border = p.iris, borderVariant = p.edge
    ) else lightColorScheme(
        primary = p.iris, onPrimary = Color.White,
        primaryContainer = p.iris, onPrimaryContainer = Color.White,
        secondary = p.orchid, onSecondary = Color.White,
        background = p.base, onBackground = p.bone,
        surface = p.veil, onSurface = p.bone,
        surfaceVariant = p.edge, onSurfaceVariant = p.mist,
        border = p.iris, borderVariant = p.edge
    )
    MaterialTheme(colorScheme = scheme, typography = TvType, content = content)
}

/** tv-material's own sizes, in the rounded face the icon's wordmark is set in. */
@OptIn(ExperimentalTvMaterial3Api::class)
private val TvType: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Rounded),
        displayMedium = t.displayMedium.copy(fontFamily = Rounded),
        displaySmall = t.displaySmall.copy(fontFamily = Rounded),
        headlineLarge = t.headlineLarge.copy(fontFamily = Rounded),
        headlineMedium = t.headlineMedium.copy(fontFamily = Rounded),
        headlineSmall = t.headlineSmall.copy(fontFamily = Rounded),
        titleLarge = t.titleLarge.copy(fontFamily = Rounded),
        titleMedium = t.titleMedium.copy(fontFamily = Rounded),
        titleSmall = t.titleSmall.copy(fontFamily = Rounded),
        bodyLarge = t.bodyLarge.copy(fontFamily = Rounded),
        bodyMedium = t.bodyMedium.copy(fontFamily = Rounded),
        bodySmall = t.bodySmall.copy(fontFamily = Rounded),
        labelLarge = t.labelLarge.copy(fontFamily = Rounded),
        labelMedium = t.labelMedium.copy(fontFamily = Rounded),
        labelSmall = t.labelSmall.copy(fontFamily = Rounded)
    )
}
