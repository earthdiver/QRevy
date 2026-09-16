package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Keep related actions together without squeezing their labels at large font sizes. */
class ResultActionRow(context: Context) : LinearLayout(context) {
    init { isBaselineAligned = false }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val required = visible.maxOfOrNull { child ->
            if (child is TextView) {
                val label = child.transformationMethod?.getTransformation(child.text, child) ?: child.text
                maxOf(child.minimumWidth, kotlin.math.ceil(child.paint.measureText(label.toString())).toInt() +
                    child.compoundPaddingLeft + child.compoundPaddingRight + (16 * resources.displayMetrics.density).toInt())
            } else child.minimumWidth
        } ?: 0
        val horizontal = available >= required * visible.size
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        visible.forEach { child ->
            val params = child.layoutParams as LayoutParams
            params.width = if (horizontal) 0 else LayoutParams.MATCH_PARENT
            params.height = LayoutParams.WRAP_CONTENT
            params.weight = if (horizontal) 1f else 0f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
