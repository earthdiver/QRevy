package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Consistent compact controls shared by the main screen and its dialogs. */
internal class DialogViewFactory(private val context: Context) {
    fun button(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = if (AppLanguageManager.isJapanese) 12f else 11f
        minHeight = dp(if (AppStylePreferences.isModern(context)) 36 else 40)
        minimumHeight = dp(if (AppStylePreferences.isModern(context)) 36 else 40)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        isAllCaps = false
        if (AppStylePreferences.isModern(context)) includeFontPadding = false
        PrecisionStyle.button(this)
        setOnClickListener { onClick() }
    }

    fun compactRow(vararg children: View): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        children.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (AppStylePreferences.isModern(context)) setMargins(dp(2), dp(2), dp(2), dp(2))
        }) }
    }

    fun scrollRow(vararg children: View): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        children.forEach { row.addView(it) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
