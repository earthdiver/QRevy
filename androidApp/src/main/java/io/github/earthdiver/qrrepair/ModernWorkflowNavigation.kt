package io.github.earthdiver.qrrepair

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout

/** Navigation changes only the visible page; draft saving stays with the controller. */
internal class ModernWorkflowNavigation(
    context: Context,
    onAdjust: () -> Unit,
    onEdit: () -> Unit,
    onResult: () -> Unit,
) {
    private val factory = DialogViewFactory(context)
    private val buttons = listOf(
        factory.button(context.getString(R.string.step_adjust), onAdjust),
        factory.button(context.getString(R.string.step_edit), onEdit),
        factory.button(context.getString(R.string.step_result), onResult),
    )
    val view: LinearLayout = factory.compactRow(*buttons.toTypedArray())

    init { update(active = 0) }

    fun update(active: Int, canEdit: Boolean = true, canShowResult: Boolean = true) {
        buttons.forEachIndexed { index, button ->
            button.isSelected = index == active
            button.isEnabled = if (index == 2) canShowResult else canEdit
            val selected = index == active
            val density = button.resources.displayMetrics.density
            val underline = GradientDrawable().apply {
                setColor(if (selected) PrecisionStyle.ACCENT else PrecisionStyle.OUTLINE)
            }
            val surface = GradientDrawable().apply { setColor(PrecisionStyle.BACKGROUND) }
            val background = LayerDrawable(arrayOf(surface, underline)).apply {
                setLayerHeight(1, ((if (selected) 3 else 1) * density).toInt().coerceAtLeast(1))
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            }
            button.background = RippleDrawable(ColorStateList.valueOf(0x337B8C64), background, null)
            button.setTextColor(if (!button.isEnabled) PrecisionStyle.MUTED
                else if (selected) PrecisionStyle.ACCENT else PrecisionStyle.TEXT)
            button.alpha = if (button.isEnabled) 1f else .5f
        }
    }
}
