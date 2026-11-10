package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** Fits the preview and scrollable settings to the actual dialog bounds, including resized windows. */
internal class PreprocessDialogScaffold(
    private val context: Context,
    overlay: View,
    private val pageHost: View,
    private val stepNavigation: View? = null,
) {
    private val modern = AppStylePreferences.isModern(context)
    val classificationLegend = TextView(context).apply {
        textSize = 10f
        if (modern) PrecisionStyle.numbers(this)
        setPadding(dp(12), dp(2), dp(12), dp(2))
    }
    private val helpText = TextView(context).apply {
        setPadding(dp(12), dp(8), dp(12), dp(4))
        setText(if (modern) R.string.preprocess_adjust_help else R.string.preprocess_title_help)
        if (modern) {
            setTextColor(PrecisionStyle.MUTED)
            textSize = 13f
        }
    }
    private val imageHost = FrameLayout(context).apply {
        addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    val view: ViewGroup = object : ViewGroup(context) {
        private var sideBySide = false
        private var previewColumnWidth = 0
        private var contentTop = 0
        private val gap = dp(12)

        init {
            if (modern) setBackgroundColor(PrecisionStyle.BACKGROUND)
            stepNavigation?.let { addView(it) }
            addView(helpText)
            addView(imageHost)
            addView(classificationLegend)
            addView(pageHost)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            fun measureHeader(child: View?): Int {
                if (child == null || child.visibility == GONE) return 0
                child.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.AT_MOST))
                return child.measuredHeight
            }
            contentTop = paddingTop + measureHeader(stepNavigation) + measureHeader(helpText)
            val bodyHeight = (height - paddingBottom - contentTop).coerceAtLeast(0)
            sideBySide = contentWidth >= dp(480) && contentWidth > contentHeight
            previewColumnWidth = if (sideBySide) (contentWidth - gap) / 2 else contentWidth
            classificationLegend.measure(MeasureSpec.makeMeasureSpec(previewColumnWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.AT_MOST))
            val legendHeight = classificationLegend.measuredHeight
            val previewAllowance = (bodyHeight - legendHeight - if (sideBySide) 0 else dp(160)).coerceAtLeast(0)
            val preferredHeight = if (modern || sideBySide) previewColumnWidth
                else (contentHeight * .42f).toInt().coerceIn(dp(260), dp(520))
            val previewHeight = minOf(preferredHeight, previewAllowance)
            val previewWidth = if (modern || sideBySide) minOf(previewColumnWidth, previewHeight) else previewColumnWidth
            imageHost.measure(MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY))
            val pageWidth = if (sideBySide) contentWidth - previewColumnWidth - gap else contentWidth
            val pageHeight = if (sideBySide) bodyHeight else bodyHeight - previewHeight - legendHeight
            pageHost.measure(MeasureSpec.makeMeasureSpec(pageWidth.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(pageHeight.coerceAtLeast(0), MeasureSpec.EXACTLY))
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            var y = paddingTop
            for (child in listOfNotNull(stepNavigation, helpText)) {
                if (child.visibility == GONE) continue
                child.layout(paddingLeft, y, width - paddingRight, y + child.measuredHeight)
                y += child.measuredHeight
            }
            val imageLeft = paddingLeft + (previewColumnWidth - imageHost.measuredWidth) / 2
            imageHost.layout(imageLeft, contentTop, imageLeft + imageHost.measuredWidth, contentTop + imageHost.measuredHeight)
            val legendTop = contentTop + imageHost.measuredHeight
            classificationLegend.layout(paddingLeft, legendTop, paddingLeft + previewColumnWidth,
                legendTop + classificationLegend.measuredHeight)
            val pageLeft = if (sideBySide) paddingLeft + previewColumnWidth + gap else paddingLeft
            val pageTop = if (sideBySide) contentTop else legendTop + classificationLegend.measuredHeight
            pageHost.layout(pageLeft, pageTop, pageLeft + pageHost.measuredWidth, pageTop + pageHost.measuredHeight)
        }
    }

    fun showPage(page: PreprocessPage) {
        if (!modern) return
        helpText.visibility = if (page == PreprocessPage.CELL_EDITOR) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
