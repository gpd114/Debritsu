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
 * Everything a theme colours, by role. Flat colours throughout — no
 * gradients, rims or sheen — so each role is one colour.
 *
 * Three themes: [Pastel], the default, light with a dark purple accent;
 * [Night], near-black with the same; and [Plum], a dark purple page with a
 * periwinkle accent.
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
    val gold: Color,          // warnings
    val dim: Color,           // watched episode numbers, quiet icons
    val sheet: Color,         // bottom sheets and dialogs
    val field: Color,         // text-field wells
    val hairline: Color,      // faint rims and row dividers
    val quiet: Color,         // quiet pill ground
    val quietText: Color,     // quiet pill text
    val link: Color,          // "See all" and other small actions
    val glassIcon: Color,     // icons on secondary buttons
    val action: Color,        // the main button — Play, Resume
    val selected: Color,      // the lit episode, the chosen option
    val tag: Color,           // episode and airing tags
    val chip: Color,          // chips, tiles and cards
    val glass: Color,         // secondary and icon buttons
    val progress: Color,      // progress fills
    val raisedHigh: Color,    // Material's higher surfaces: menus, raised sheets
    val raisedHighest: Color,
    val badge: Color,         // the translucent score pill over posters
    val video: Color,         // the accent over video: played bar, spinner
    val videoKnob: Color      // the seek bar's knob
)

/** Light lavender, with a dark purple accent. */
val Pastel = Palette(
    dark = false,
    base = Color(0xFFF6F3FB), veil = Color(0xFFFFFFFF), edge = Color(0xFFE6E0F1),
    iris = Color(0xFF5A2E9E), orchid = Color(0xFF6B3FAE), mist = Color(0xFF7B7394),
    bone = Color(0xFF251D38), candy = Color(0xFF6B3AB5), gold = Color(0xFFB7791F),
    dim = Color(0xFFAAA2C0), sheet = Color(0xFFF9F7FD), field = Color(0xFFFFFFFF),
    hairline = Color(0xFFECE6F6), quiet = Color(0xFFEEE8F8), quietText = Color(0xFF4E3F70),
    link = Color(0xFF5A2E9E), glassIcon = Color(0xFF5A2E9E),
    action = Color(0xFF4B2386), selected = Color(0xFF5A2E9E), tag = Color(0xFF6B3AB5),
    chip = Color(0xFFFFFFFF), glass = Color(0xFFEDE6F8), progress = Color(0xFF6B3AB5),
    raisedHigh = Color(0xFFF0ECF8), raisedHighest = Color(0xFFE9E4F5),
    badge = Color(0x8C3E1C78), video = Color(0xFFA884FF), videoKnob = Color(0xFFD6C6FF)
)

/**
 * Near-black, with the dark purple accent where it is a fill — the main
 * button, the lit episode — and a lighter purple for text and small marks,
 * which in dark purple would be unreadable on a page this dark.
 */
val Night = Palette(
    dark = true,
    base = Color(0xFF0B0A14), veil = Color(0xFF17152A), edge = Color(0xFF2A2744),
    iris = Color(0xFFB79CFF), orchid = Color(0xFFCFC0FF), mist = Color(0xFFA7A3C2),
    bone = Color(0xFFF2F0FA), candy = Color(0xFFB08CFF), gold = Color(0xFFFFD86B),
    dim = Color(0xFF7A7599), sheet = Color(0xFF16142A), field = Color(0xFF12112A),
    hairline = Color(0x14FFFFFF), quiet = Color(0x17FFFFFF), quietText = Color(0xFFDAD4F2),
    link = Color(0xFFCFC0FF), glassIcon = Color(0xFFE8E0FF),
    action = Color(0xFF5A2E9E), selected = Color(0xFF5A2E9E), tag = Color(0xFF6B3AB5),
    chip = Color(0xFF1D1A33), glass = Color(0x1AFFFFFF), progress = Color(0xFF9C7BFF),
    raisedHigh = Color(0xFF201D38), raisedHighest = Color(0xFF29254A),
    badge = Color(0x8C3E1C78), video = Color(0xFFA884FF), videoKnob = Color(0xFFD6C6FF)
)

/** Dark purple, with the periwinkle accent. */
val Plum = Palette(
    dark = true,
    base = Color(0xFF1A1029), veil = Color(0xFF261A3A), edge = Color(0xFF3A2B52),
    iris = Color(0xFF8A94FA), orchid = Color(0xFFB9C0FF), mist = Color(0xFFADA3C6),
    bone = Color(0xFFF4F0FF), candy = Color(0xFF9AA3FF), gold = Color(0xFFFFD86B),
    dim = Color(0xFF7E7299), sheet = Color(0xFF22173A), field = Color(0xFF211634),
    hairline = Color(0x17FFFFFF), quiet = Color(0x17FFFFFF), quietText = Color(0xFFDDD6F2),
    link = Color(0xFFB9C0FF), glassIcon = Color(0xFFE8E4FF),
    action = Color(0xFF6C76EE), selected = Color(0xFF5E69EA), tag = Color(0xFF6C76EE),
    chip = Color(0xFF261A3A), glass = Color(0x1AFFFFFF), progress = Color(0xFF8A94FA),
    raisedHigh = Color(0xFF2C1F42), raisedHighest = Color(0xFF35284D),
    badge = Color(0x8C3A42C8), video = Color(0xFF8A94FA), videoKnob = Color(0xFFC3C9FF)
)

/**
 * The colours in use. Each is read from the current [Palette], which is held
 * as state: switching theme recolours everything that has read one, including
 * drawing code, without any screen having to pass a palette about.
 *
 * The names are roles and date from the first palette — Bone is the primary
 * text colour, whatever colour that happens to be.
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
    primaryContainer = p.selected, onPrimaryContainer = Color.White,
    secondary = p.orchid, onSecondary = Color(0xFF14163A),
    background = p.base, onBackground = p.bone,
    surface = p.veil, onSurface = p.bone,
    surfaceVariant = p.edge, onSurfaceVariant = p.mist,
    // Sheets, dialogs and menus draw from these. Left unset they are Material's
    // baseline greys, which never looked like they belonged to the app.
    surfaceContainerLowest = p.base, surfaceContainerLow = p.sheet, surfaceContainer = p.sheet,
    surfaceContainerHigh = p.raisedHigh, surfaceContainerHighest = p.raisedHighest,
    outline = p.edge, outlineVariant = p.hairline
) else lightColorScheme(
    primary = p.iris, onPrimary = Color.White,
    primaryContainer = p.quiet, onPrimaryContainer = p.bone,
    secondary = p.orchid, onSecondary = Color.White,
    background = p.base, onBackground = p.bone,
    surface = p.veil, onSurface = p.bone,
    surfaceVariant = p.edge, onSurfaceVariant = p.mist,
    surfaceContainerLowest = p.base, surfaceContainerLow = p.sheet, surfaceContainer = p.sheet,
    surfaceContainerHigh = p.raisedHigh, surfaceContainerHighest = p.raisedHighest,
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

/** Applies a theme by its settings name: "pastel", "night" or "plum". */
fun applyTheme(name: String) {
    Ink.palette = when (name) {
        "night" -> Night
        "plum" -> Plum
        else -> Pastel
    }
}
