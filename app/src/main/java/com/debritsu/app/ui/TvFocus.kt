package com.debritsu.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
    val mode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return mode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
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
 * Touching a control does not focus it, so this is invisible on a phone.
 */
@Composable
fun Modifier.tvClickable(
    shape: Shape,
    enabled: Boolean = true,
    /** Fraction to grow by when focused. Worth it on posters, wrong on rows. */
    lift: Float = 0f,
    onClick: () -> Unit
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && lift > 0f) 1f + lift else 1f,
        label = "tvFocusScale"
    )
    val ring by animateDpAsState(
        targetValue = if (focused) 3.dp else 0.dp,
        label = "tvFocusRing"
    )
    return this
        .scale(scale)
        .border(ring, if (focused) Ink.Iris else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(enabled = enabled, onClick = onClick)
}
