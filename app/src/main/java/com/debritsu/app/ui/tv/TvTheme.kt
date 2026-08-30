package com.debritsu.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.debritsu.app.ui.Ink

/**
 * The app's own colours, given to tv-material.
 *
 * Without this its components fall back to their defaults, which is why every
 * button came out a pale lavender belonging to no part of this app. The
 * library draws focus states from the scheme too, so the selection highlight
 * follows from here rather than being painted on at each call site.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DebritsuTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Iris,
            onPrimary = Ink.Bone,
            primaryContainer = Ink.Iris,
            onPrimaryContainer = Ink.Bone,
            secondary = Ink.Orchid,
            onSecondary = Ink.Base,
            background = Ink.Base,
            onBackground = Ink.Bone,
            surface = Ink.Veil,
            onSurface = Ink.Bone,
            surfaceVariant = Ink.Edge,
            onSurfaceVariant = Ink.Mist,
            border = Ink.Edge,
            borderVariant = Ink.Edge
        ),
        content = content
    )
}
