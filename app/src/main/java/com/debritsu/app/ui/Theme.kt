package com.debritsu.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Night-violet palette. The background carries a violet cast rather than being
 * neutral grey, so posters sit in the same colour world as the chrome.
 */
object Ink {
    val Base = Color(0xFF08070D)      // page
    val Veil = Color(0xFF171226)      // cards, sheets
    val Edge = Color(0xFF2A2140)      // dividers, inactive chips
    val Iris = Color(0xFF8B5CF6)      // primary action
    val Orchid = Color(0xFFE879C7)    // secondary / status
    val Mist = Color(0xFFB9B3CC)      // muted text
    val Bone = Color(0xFFF1EEF8)      // primary text

    /** Used behind the detail banner and the app bar. */
    val Dusk = Brush.verticalGradient(listOf(Color(0x0008070D), Color(0xFF08070D)))
}

private val Scheme = darkColorScheme(
    primary = Ink.Iris,
    onPrimary = Color(0xFF12091F),
    primaryContainer = Color(0xFF3B2A63),
    onPrimaryContainer = Ink.Bone,
    secondary = Ink.Orchid,
    onSecondary = Color(0xFF2A0E22),
    background = Ink.Base,
    onBackground = Ink.Bone,
    surface = Ink.Veil,
    onSurface = Ink.Bone,
    surfaceVariant = Ink.Edge,
    onSurfaceVariant = Ink.Mist,
    outline = Ink.Edge,
    outlineVariant = Color(0xFF221A36)
)

/**
 * Two roles: a tightly-tracked sans for everything the eye reads, and monospace
 * for anything that is really data — episode numbers, sizes, quality tags. The
 * source list should look like the torrent metadata it is.
 */
private val Type = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        letterSpacing = (-1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = (-0.2).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.5.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    // The data voice.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 1.sp
    )
)

@Composable
fun DebritsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
