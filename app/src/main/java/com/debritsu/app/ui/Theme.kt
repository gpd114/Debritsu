package com.debritsu.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.debritsu.app.R

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
    val Candy = Color(0xFFFF8CCB)     // watched dots, filler tags, ticks
    val Gold = Color(0xFFFFD86B)      // scores

    /** Used behind the detail banner and the app bar. */
    val Dusk = Brush.verticalGradient(listOf(Color(0x00180E36), Color(0xFF180E36)))
}

/**
 * M PLUS Rounded 1c — the face the launcher icon's wordmark is set in, so the
 * app and its icon read as one thing. Latin-only cuts, about 50KB a weight:
 * the full font carries every kanji and would add ten megabytes to the APK.
 * Anything outside the cut falls back to the system font on its own.
 */
val Rounded = FontFamily(
    Font(R.font.mplus_rounded_medium, FontWeight.Medium),
    Font(R.font.mplus_rounded_bold, FontWeight.Bold),
    Font(R.font.mplus_rounded_extrabold, FontWeight.ExtraBold)
)

private val Sheet = Color(0xFF1A1133)

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
    // Sheets, dialogs and menus draw from these. Left unset they are Material's
    // baseline greys, which is why the source sheet never looked like it
    // belonged to the rest of the app.
    surfaceContainerLowest = Color(0xFF0E0A1C),
    surfaceContainerLow = Sheet,
    surfaceContainer = Sheet,
    surfaceContainerHigh = Color(0xFF221840),
    surfaceContainerHighest = Color(0xFF2A1F4C),
    outline = Ink.Edge,
    outlineVariant = Color(0xFF221A36)
)

/**
 * One rounded face throughout, in three weights. The monospace "data voice"
 * this replaced suited a torrent client more than something glossy; the
 * numbers still read as numbers without it.
 */
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.2.sp)
)

@Composable
fun DebritsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
