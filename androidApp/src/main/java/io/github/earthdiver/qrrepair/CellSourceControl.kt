package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix

internal class CellSourceControl(
    private val context: Context,
    buttonFactory: (String, () -> Unit) -> Button,
    toggle: () -> Unit,
) {
    private val modernStyle = AppStylePreferences.isModern(context)
    var onManualFocusChanged: (Boolean) -> Unit = {}
    private val manualFocus = CheckBox(context).apply {
        setText(R.string.highlight_manual_only)
        textSize = 12f
        setOnCheckedChangeListener { _, checked -> onManualFocusChanged(checked) }
    }
    fun setManualFocus(enabled: Boolean) { if (manualFocus.isChecked != enabled) manualFocus.isChecked = enabled }

    val button = buttonFactory(context.getString(R.string.cell_make_manual), toggle).apply { isEnabled = false }
    val summary = TextView(context).apply {
        textSize = 12f
        if (modernStyle) PrecisionStyle.numbers(this)
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (modernStyle) {
            PrecisionStyle.panel(this)
            val inset = (8 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        addView(TextView(context).apply {
            setText(R.string.cell_source_legend)
            textSize = 12f
        })
        addView(summary)
        addView(button, LinearLayout.LayoutParams(-1, -2))
        if (modernStyle) addView(manualFocus)
    }

    fun update(state: PreRecoveryCellEditState, baseline: QrMatrix?) {
        val selected = state.selectedCells
        val manualCount = selected.count { it in state.edits }
        button.setText(if (manualCount > 0) R.string.cell_restore_image else R.string.cell_make_manual)
        button.isEnabled = selected.isNotEmpty() && baseline != null && selected.all {
            it.x in 0 until baseline.dimension && it.y in 0 until baseline.dimension
        }
        summary.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        if (selected.size == 1 && button.isEnabled) {
            val coord = selected.single()
            val imageValue = label(baseline!![coord.x, coord.y].value)
            val current = if (coord in state.prioritySearch) "*" else label(state.edits[coord] ?: baseline[coord.x, coord.y].value)
            summary.text = context.getString(
                R.string.cell_source_preview,
                context.getString(if (manualCount > 0) R.string.cell_source_manual else R.string.cell_source_image),
                current,
                imageValue,
            )
            if (modernStyle) summary.text = "(${coord.x},${coord.y})  " + summary.text
        } else {
            summary.text = context.getString(R.string.cell_source_selection, selected.size, selected.size - manualCount, manualCount)
        }
    }

    private fun label(value: ModuleValue): String = when (value) {
        ModuleValue.ZERO -> "0"
        ModuleValue.ONE -> "1"
        ModuleValue.ERASED -> "?"
    }
}
