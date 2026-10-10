package io.github.earthdiver.qrrepair

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

/** Shared visual tokens; image and module colours remain independent of the app theme. */
internal object PrecisionStyle {
    const val ACTION_TOP_MARGIN_DP = 8
    const val ACTION_BOTTOM_MARGIN_DP = 4
    const val BACKGROUND = 0xFF101312.toInt()
    const val SURFACE = 0xFF1C211E.toInt()
    const val OUTLINE = 0xFF39423B.toInt()
    const val TEXT = 0xFFF4F5EF.toInt()
    const val MUTED = 0xFFB9C0B8.toInt()
    const val PINK = 0xFFFF9EB5.toInt()
    const val ACCENT = 0xFFD2ED48.toInt()

    fun panel(view: View, fill: Int = SURFACE, radius: Int = 16) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * density
            setStroke((density).toInt().coerceAtLeast(1), OUTLINE)
        }
    }

    fun numbers(view: TextView) {
        view.fontFeatureSettings = "tnum"
    }

    fun brand(view: TextView) {
        view.setTextColor(TEXT)
        view.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        view.textSize = 32f
        view.letterSpacing = .025f
        view.includeFontPadding = false
    }

    fun heading(view: TextView) {
        view.setTextColor(TEXT)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.textSize = 22f
    }

    fun button(view: Button, primary: Boolean = false, home: Boolean = false, exit: Boolean = false) {
        if (!AppStylePreferences.isModern(view.context)) return
        val density = view.resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            setColor(if (exit) PINK else if (primary) ACCENT else SURFACE)
            cornerRadius = 12 * density
            if (!primary && !exit) setStroke(((if (home) 3 else 1) * density).toInt().coerceAtLeast(1), if (home) TEXT else OUTLINE)
        }
        view.backgroundTintList = null
        view.background = RippleDrawable(ColorStateList.valueOf(0x337B8C64), shape, null)
        view.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(MUTED, if (primary || exit) BACKGROUND else TEXT),
        ))
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.stateListAnimator = null
    }
}
