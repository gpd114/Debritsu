package com.debritsu.app.ui

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.debritsu.app.BuildConfig
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True when the app is running on a television.
 *
 * Read from the UI mode rather than from the presence of a touchscreen: an
 * Android box plugged into a monitor has no touchscreen either, and should get
 * the same treatment.
 */
fun isTelevision(context: Context): Boolean {
    // The configuration's ui mode, not UiModeManager.getCurrentModeType(). The
    // latter reports dock state — car, desk, and so on — and boxes are perfectly
    // capable of answering NORMAL to it while the configuration says TELEVISION.
    // Reading the wrong one made every television-only behaviour inert on the
    // very device they were written for.
    val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true

    // The other tell, for anything that reports a normal ui mode regardless: a
    // television launcher is the only thing that declares leanback.
    return context.packageManager.hasSystemFeature("android.software.leanback")
}

/**
 * Televisions overscan: a slice of every edge is cut off by the panel, varying
 * by set, so content flush to the edge is partly invisible. This is the margin
 * to keep clear, and it is zero everywhere else.
 */
@Composable
fun overscan(): Dp {
    val context = LocalContext.current
    val tv = remember(context) { isTelevision(context) }
    return if (tv) 27.dp else 0.dp
}

/**
 * Lets the d-pad out of a text field.
 *
 * Once a text field has focus, up and down do nothing and left and right move
 * the caret, so on a television the remote appears to stop working entirely —
 * the selection is stuck in a box that quietly accepts typing. Up and down are
 * given back to the screen; left and right stay with the caret, which is what
 * makes editing possible at all.
 *
 * Every text field needs this. Fixing one and not the rest just moves where the
 * app freezes.
 */
@Composable
fun Modifier.tvEscape(): Modifier {
    val context = LocalContext.current
    val tv = remember(context) { isTelevision(context) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (!tv) return this
    // Preview rather than the ordinary key handler. Compose runs a preview pass
    // down the tree before the bubble pass back up, and onKeyEvent is the
    // bubble one — so a text field that consumes up and down for its own
    // cursor handling means the handler never runs at all. Which is precisely
    // what an earlier attempt at this did, and why it changed nothing.
    return this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (e.key) {
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionUp -> FocusDirection.Up
            else -> return@onPreviewKeyEvent false
        }
        // The keyboard has to go before the selection moves, not after. A
        // television puts it over the whole screen, so leaving it up means
        // focus travels away underneath something the viewer cannot see past —
        // which looks exactly like being stuck, whether or not it moved.
        keyboard?.hide()
        val moved = focusManager.moveFocus(direction)
        if (BuildConfig.DEBUG) {
            Log.d("DebritsuTv", "escape ${e.key} moved=$moved")
        }
        // Consumed either way: letting an unmoved press through would put the
        // caret back where it started and look like nothing happened.
        true
    }
}

/**
 * A clickable that shows where the d-pad is.
 *
 * Everything interactive in this app already used [clickable], which in Compose
 * is focusable and moves with a remote — so the app was navigable on a
 * television and simply never said what was selected, which is the difference
 * between usable and not.
 *
 * The two modifiers have to be applied together and in this order, because
 * [onFocusChanged] only observes focus targets that come after it in the chain
 * and [clickable] is the one creating that target. Bundling them removes the
 * chance of wiring it up backwards at each call site and seeing nothing.
 *
 * Touching a control does not focus it, so this is invisible on a phone — and
 * on a phone it is skipped outright rather than merely never drawn.
 */
@Composable
fun Modifier.tvClickable(
    shape: Shape,
    enabled: Boolean = true,
    /** Fraction to grow by when focused. Worth it on posters, wrong on rows. */
    lift: Float = 0f,
    onClick: () -> Unit
): Modifier {
    val context = LocalContext.current
    val tv = remember(context) { isTelevision(context) }
    // Nothing below can ever show on a phone, since touching a control does not
    // focus it — so a phone should not be paying for any of it. Two animated
    // values, a border and a graphics layer on every poster, episode chip and
    // list row is real work in a scrolling list, and it buys exactly nothing
    // where there is no d-pad.
    if (!tv) return this.clickable(enabled = enabled, onClick = onClick)

    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && lift > 0f) 1f + lift else 1f,
        label = "tvFocusScale"
    )
    val ring by animateDpAsState(
        targetValue = if (focused) 3.dp else 0.dp,
        label = "tvFocusRing"
    )
    // A border draws its stroke inside the bounds it is given, so on its own the
    // ring lands on top of the poster rather than around it. Insetting the
    // content by a constant leaves the stroke somewhere of its own to sit — and
    // it has to be constant rather than appearing with focus, or every poster
    // would resize as the selection passed over it.
    val gap = if (tv) 4.dp else 0.dp
    return this
        .scale(scale)
        .border(ring, if (focused) Ink.Iris else Color.Transparent, shape)
        .padding(gap)
        .onFocusChanged { focused = it.isFocused }
        .clickable(enabled = enabled, onClick = onClick)
}
