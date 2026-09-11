package com.debritsu.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.debritsu.app.R

/**
 * Everything one theme colours, by role. There are two — [Pastel], the
 * default, and [Night] — both with periwinkle as the accent.
 */
data class Palette(
    val dark: Boolean,
    val base: Color,          // page
    val veil: Color,          // cards, poster placeholders
    val edge: Color,          // dividers, inactive tracks
    val iris: Color,          // the accent, solid
    val orchid: Color,        // secondary text in the accent's family
    val mist: Color,          // muted text
    val bone: Color,          // primary text
    val candy: Color,         // watched dots, ticks, filler tags, kickers
    val gold: Color,          // scores
    val dim: Color,           // watched episode numbers, quiet icons
    val sheet: Color,         // bottom sheets and dialogs
    val field: Color,         // text-field wells
    val hairline: Color,      // faint rims and row dividers
    val quiet: Color,         // quiet pill ground
    val quietText: Color,     // quiet pill text
    val link: Color,          // "See all" and other small actions
    val glassIcon: Color,     // icons on glass buttons
    val jelly: List<Color>,   // the main action, top to bottom
    val jellyEdge: Color,
    val selected: List<Color>,
    val selectedEdge: Color,
    val tag: List<Color>,     // episode and airing tags
    val chip: List<Color>,    // raised chips and cards
    val raise: Color,         // what raised things stand on
    val topLight: Color,      // the light along a raised thing's top rim
    val glass: List<Color>,
    val progress: List<Color>,
    val backdrop: List<Color>,
    val glow: Color,          // off the top-left corner of the page
    val score: List<Color>,   // the score badge
    val scoreText: Color,
    val scoreSub: Color
)

val Pastel = Palette(
    dark = false,
    base = Color(0xFFF3F3FF), veil = Color(0xFFFFFFFF), edge = Color(0xFFE4E4F7),
    iris = Color(0xFF5E69EA), orchid = Color(0xFF5B67E8), mist = Color(0xFF7C7A9C),
    bone = Color(0xFF262243), candy = Color(0xFF6B76EC), gold = Color(0xFFB7791F),
    dim = Color(0xFFA9A8C6), sheet = Color(0xFFF7F7FF), field = Color(0xFFFFFFFF),
    hairline = Color(0xFFE9E9F8), quiet = Color(0xFFECEDFF), quietText = Color(0xFF4F4C7A),
    link = Color(0xFF5E69EA), glassIcon = Color(0xFF5E69EA),
    jelly = listOf(Color(0xFFC3C9FF), Color(0xFF8A94FA), Color(0xFF5E69EA)), jellyEdge = Color(0xFFBCC2F4),
    selected = listOf(Color(0xFF9AA3FF), Color(0xFF525EE0)), selectedEdge = Color(0xFFAEB4EE),
    tag = listOf(Color(0xFFAEB6FF), Color(0xFF6B76EC)),
    chip = listOf(Color(0xFFFFFFFF), Color(0xFFF6F6FF)), raise = Color(0xFFDFE0F6),
    topLight = Color(0xFFFFFFFF),
    glass = listOf(Color(0xFFFFFFFF), Color(0xFFF1F2FF)),
    progress = listOf(Color(0xFFAEB6FF), Color(0xFF5E69EA)),
    backdrop = listOf(Color(0xFFECEDFF), Color(0xFFF4F4FF), Color(0xFFFAFAFF)), glow = Color(0xFFDDE1FF),
    score = listOf(Color(0xFFFFF6DD), Color(0xFFFFE9B8)), scoreText = Color(0xFFB7731A), scoreSub = Color(0xFFC28A3C)
)

val Night = Palette(
    dark = true,
    base = Color(0xFF0B0A14), veil = Color(0xFF17162B), edge = Color(0xFF2A2946),
    iris = Color(0xFF8A94FA), orchid = Color(0xFFB9C0FF), mist = Color(0xFFA7A6C4),
    bone = Color(0xFFF1F1FA), candy = Color(0xFF9AA3FF), gold = Color(0xFFFFD86B),
    dim = Color(0xFF7A7899), sheet = Color(0xFF16152B), field = Color(0xFF12112A),
    hairline = Color(0x14FFFFFF), quiet = Color(0x17FFFFFF), quietText = Color(0xFFD6D6F2),
    link = Color(0xFFB9C0FF), glassIcon = Color(0xFFE4E6FF),
    jelly = listOf(Color(0xFFC3C9FF), Color(0xFF8A94FA), Color(0xFF5E69EA)), jellyEdge = Color(0xFF2E3180),
    selected = listOf(Color(0xFF9AA3FF), Color(0xFF525EE0)), selectedEdge = Color(0xFF22246A),
    tag = listOf(Color(0xFFAEB6FF), Color(0xFF6B76EC)),
    chip = listOf(Color(0xFF211F40), Color(0xFF1A1934)), raise = Color(0xFF08070F),
    topLight = Color(0x40FFFFFF),
    glass = listOf(Color(0x29FFFFFF), Color(0x0FFFFFFF)),
    progress = listOf(Color(0xFFAEB6FF), Color(0xFF6B76EC)),
    backdrop = listOf(Color(0xFF15142E), Color(0xFF0E0D1C), Color(0xFF0A0913)), glow = Color(0xCC2A2C6A),
    score = listOf(Color(0xFF3A2F1A), Color(0xFF2A2212)), scoreText = Color(0xFFFFD86B), scoreSub = Color(0xFFE6CF96)
)

/**
 * The colours in use. Each is read from the current [Palette], which is held
 * as state: switching theme recolours everything that has read one, including
 * drawing code, without any screen having to pass a palette about.
 *
 * The names are roles and date from the first, dark palette — Bone is the
 * primary text colour whether that is near-white or near-black.
 */
object Ink {
    var palette by mutableStateOf(Pastel)

    val Base get() = palette.base
    val Veil get() = palette.veil
    val Edge get() = palette.edge
    val Iris get() = palette.iris
    val Orchid get() = palette.orchid
    val Mist get() = palette.mist
    val Bone get() = palette.bone
    val Candy get() = palette.candy
    val Gold get() = palette.gold
    val Dim get() = palette.dim
    val Sheet get() = palette.sheet
    val Field get() = palette.field
    val Hairline get() = palette.hairline
    val Quiet get() = palette.quiet
    val QuietText get() = palette.quietText
    val Link get() = palette.link
    val GlassIcon get() = palette.glassIcon

    /** Fades an image into the page. */
    val Dusk get() = Brush.verticalGradient(listOf(palette.base.copy(alpha = 0f), palette.base))
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

/** Material's colours, from the palette, for the components still drawn by Material. */
private fun scheme(p: Palette) = if (p.dark) darkColorScheme(
    primary = p.iris, onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2E6E), onPrimaryContainer = p.bone,
    secondary = p.orchid, onSecondary = Color(0xFF14163A),
    background = p.base, onBackground = p.bone,
    surface = p.veil, onSurface = p.bone,
    surfaceVariant = p.edge, onSurfaceVariant = p.mist,
    // Sheets, dialogs and menus draw from these. Left unset they are Material's
    // baseline greys, which never looked like they belonged to the app.
    surfaceContainerLowest = p.base, surfaceContainerLow = p.sheet, surfaceContainer = p.sheet,
    surfaceContainerHigh = Color(0xFF201F3A), surfaceContainerHighest = Color(0xFF28274A),
    outline = p.edge, outlineVariant = p.hairline
) else lightColorScheme(
    primary = p.iris, onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E4FF), onPrimaryContainer = p.bone,
    secondary = p.orchid, onSecondary = Color.White,
    background = p.base, onBackground = p.bone,
    surface = p.veil, onSurface = p.bone,
    surfaceVariant = p.edge, onSurfaceVariant = p.mist,
    surfaceContainerLowest = Color.White, surfaceContainerLow = p.sheet, surfaceContainer = p.sheet,
    surfaceContainerHigh = Color(0xFFEFEFFD), surfaceContainerHighest = Color(0xFFE9E9FB),
    outline = p.edge, outlineVariant = p.hairline
)

/** One rounded face throughout, in three weights. */
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
    MaterialTheme(colorScheme = scheme(Ink.palette), typography = Type, content = content)
}

/** Applies a theme by its settings name, "pastel" or "night". */
fun applyTheme(name: String) {
    Ink.palette = if (name == "night") Night else Pastel
}
