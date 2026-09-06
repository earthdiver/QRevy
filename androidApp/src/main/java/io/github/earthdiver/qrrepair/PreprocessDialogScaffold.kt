package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the shared image/header/page host. */
internal class PreprocessDialogScaffold(
    private val context: Context,
    overlay: View,
    pageHost: View,
    stepNavigation: View? = null,
) {
    val classificationLegend = TextView(context).apply {
        textSize = 10f
        if (AppStylePreferences.isModern(context)) PrecisionStyle.numbers(this)
        setPadding(dp(12), dp(2), dp(12), dp(2))
    }

    private lateinit var imageHost: PreviewHost
    private lateinit var helpText: TextView

    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (AppStylePreferences.isModern(context)) setBackgroundColor(PrecisionStyle.BACKGROUND)
        helpText = TextView(context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setText(if (AppStylePreferences.isModern(context)) R.string.preprocess_adjust_help
                else R.string.preprocess_title_help)
            if (AppStylePreferences.isModern(context)) {
                setTextColor(PrecisionStyle.MUTED)
                textSize = 13f
            }
        }
        stepNavigation?.let { addView(it) }
        addView(helpText)
        imageHost = PreviewHost(context).apply {
            addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        val imageHeight = (context.resources.displayMetrics.heightPixels * .42f).toInt()
            .coerceIn(dp(260), dp(520))
        addView(imageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, imageHeight))
        addView(classificationLegend)
        addView(pageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun showPage(page: PreprocessPage) {
        if (!AppStylePreferences.isModern(context)) return
        val editing = page == PreprocessPage.CELL_EDITOR
        helpText.visibility = if (editing) View.GONE else View.VISIBLE
        imageHost.square = true
        imageHost.layoutParams = (imageHost.layoutParams as LinearLayout.LayoutParams).apply {
            height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
    }

    /** Use the measured content width, including after resizing, rather than the screen width. */
    private class PreviewHost(context: Context) : FrameLayout(context) {
        var square = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val heightSpec = if (square) MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY,
            ) else heightMeasureSpec
            super.onMeasure(widthMeasureSpec, heightSpec)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
