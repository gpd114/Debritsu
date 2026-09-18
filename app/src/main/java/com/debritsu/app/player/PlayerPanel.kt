package com.debritsu.app.player

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.debritsu.app.R
import com.debritsu.app.ui.Ink

/** One row of a panel: what it is, what it says underneath, and an optional tag. */
data class PanelRow(val title: String, val subtitle: String, val tag: String? = null)

/** Marks the subheading so a panel that is still working can rewrite it. */
const val PANEL_SUBHEADING = "panel_subheading"

/**
 * The app's panel: a sheet in the theme's colours holding a heading and a list
 * to choose from. Used by the source, subtitle, audio and cast pickers.
 *
 * Built in code rather than as a layout because the colours come from the
 * theme, which is Compose state — and because the player is the one screen in
 * the app not written in Compose.
 */
fun Activity.panelDialog(
    heading: String,
    subheading: String,
    rows: List<PanelRow>,
    onPick: (Int) -> Unit
): Dialog {
    val dp = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    val heavy = ResourcesCompat.getFont(this, R.font.mplus_rounded_extrabold)
    val bold = ResourcesCompat.getFont(this, R.font.mplus_rounded_bold)
    val medium = ResourcesCompat.getFont(this, R.font.mplus_rounded_medium)

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(18), px(20), px(24))
        background = GradientDrawable().apply {
            setColor(Ink.palette.sheet.toArgb())
            cornerRadius = px(24).toFloat()
            setStroke(px(1), Ink.palette.hairline.toArgb())
        }
    }
    content.addView(TextView(this).apply {
        text = heading
        setTextColor(Ink.palette.bone.toArgb())
        textSize = 18f
        typeface = heavy
    })
    content.addView(TextView(this).apply {
        text = subheading
        setTextColor(Ink.palette.mist.toArgb())
        textSize = 10.5f
        typeface = bold
        letterSpacing = 0.06f
        setPadding(0, px(2), 0, px(12))
        tag = PANEL_SUBHEADING
    })

    val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)

    rows.forEachIndexed { index, row ->
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
            isClickable = true
            // Clickable is not focusable, and a remote can only reach a row
            // that is. Without this the picker opens on a television and
            // nothing in it can be selected.
            isFocusable = true
            background = focusPlate(px(10).toFloat())
            setOnClickListener { dialog.dismiss(); onPick(index) }
        }
        item.addView(TextView(this).apply {
            text = row.title
            setTextColor(Ink.palette.bone.toArgb())
            textSize = 13f
            typeface = bold
            maxLines = 1
        })
        item.addView(TextView(this).apply {
            text = row.subtitle
            setTextColor(Ink.palette.mist.toArgb())
            textSize = 11.5f
            typeface = medium
            maxLines = 2
        })
        row.tag?.let { tag ->
            val quiet = Ink.palette.quiet.toArgb()
            val fill = when {
                // A tag can say more than one thing ("PLAYING · DIRECT"); what
                // it leads with is what it is coloured by.
                tag.startsWith("PLAYING") -> Ink.palette.tag.toArgb()
                tag == "DIRECT" || tag == "SELECTED" -> Ink.palette.selected.toArgb()
                else -> quiet
            }
            item.addView(TextView(this).apply {
                // Each word of its own, so "PLAYING · DIRECT" does not come out
                // as "Playing · direct".
                text = tag.split(" ").joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar { it.uppercase() }
                }
                setTextColor(if (fill == quiet) Ink.palette.quietText.toArgb() else 0xFFFFFFFF.toInt())
                textSize = 10f
                typeface = bold
                setPadding(px(9), px(2), px(9), px(3))
                background = GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = px(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(5) }
            })
        }
        content.addView(item)
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1))
            setBackgroundColor(Ink.palette.hairline.toArgb())
        })
    }

    dialog.setContentView(ScrollView(this).apply { addView(content) })
    dialog.window?.apply {
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
        setLayout(
            (resources.displayMetrics.widthPixels * 0.62).toInt(),
            (resources.displayMetrics.heightPixels * 0.80).toInt()
        )
        setGravity(Gravity.BOTTOM or Gravity.END)
    }
    // Start on the first row, so the first press of a remote moves the
    // selection rather than being spent creating one.
    dialog.setOnShowListener { (content.getChildAt(2) ?: content.getChildAt(0))?.requestFocus() }
    return dialog
}

/** A tinted plate behind whichever row the d-pad is on, and nothing otherwise. */
private fun Activity.focusPlate(corner: Float): android.graphics.drawable.Drawable {
    val on = GradientDrawable().apply {
        setColor(
            (if (Ink.palette.dark) Ink.palette.video else Ink.palette.iris)
                .copy(alpha = 0.18f).toArgb()
        )
        cornerRadius = corner
    }
    return android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), on)
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
    }
}

/** Rewrites a shown panel's subheading in place. */
fun setPanelSubheading(dialog: Dialog, text: String) {
    runCatching {
        dialog.window?.decorView?.findViewWithTag<TextView>(PANEL_SUBHEADING)?.text = text
    }
}
