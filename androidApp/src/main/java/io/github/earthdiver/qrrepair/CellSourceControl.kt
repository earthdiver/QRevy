package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix

internal class CellSourceControl(
    private val context: Context,
    buttonFactory: (String, () -> Unit) -> Button,
    private val changeSource: (CellEditSource) -> Unit,
) {
    private val modernStyle = AppStylePreferences.isModern(context)
    var onManualFocusChanged: (Boolean) -> Unit = {}
    private val manualFocus = CheckBox(context).apply {
        setText(R.string.highlight_manual_only)
        textSize = 12f
        setOnCheckedChangeListener { _, checked -> onManualFocusChanged(checked) }
    }
    fun setManualFocus(enabled: Boolean) { if (manualFocus.isChecked != enabled) manualFocus.isChecked = enabled }

    private var selectedSource: CellEditSource? = null
    private var canFixSelection = false
    private var allowFixedCorrections = false
    val button = buttonFactory(context.getString(R.string.cell_source_image)) { showSourceMenu() }.apply {
        isEnabled = false
    }

    private fun labelFor(source: CellEditSource): Int = when (source) {
        CellEditSource.IMAGE -> R.string.cell_source_image
        CellEditSource.TENTATIVE -> R.string.cell_source_tentative
        CellEditSource.FIXED -> if (allowFixedCorrections) R.string.cell_source_fixed_correctable else R.string.cell_source_fixed
    }

    private fun showSourceMenu() {
        PopupMenu(context, button).apply {
            CellEditSource.entries.forEach { source ->
                menu.add(0, source.ordinal, source.ordinal, labelFor(source)).apply {
                    isEnabled = source != CellEditSource.FIXED || canFixSelection
                    isCheckable = true
                    isChecked = source == selectedSource
                }
            }
            setOnMenuItemClickListener { item ->
                changeSource(CellEditSource.entries[item.itemId])
                true
            }
            show()
        }
    }
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
        addView(manualFocus)
    }

    fun update(state: PreRecoveryCellEditState, baseline: QrMatrix?, allowFixedCorrections: Boolean = false) {
        this.allowFixedCorrections = allowFixedCorrections
        val selected = state.selectedCells
        val manualCount = selected.count { it in state.edits }
        val softCount = selected.count { it in state.softEdits }
        selectedSource = selected.map(state::sourceAt).distinct().singleOrNull()
        val modeLabel = selectedSource?.let { context.getString(labelFor(it)) }
            ?: context.getString(R.string.cell_source_mixed)
        button.text = context.getString(R.string.cell_source_menu, modeLabel)
        button.isEnabled = selected.isNotEmpty() && baseline != null && selected.all {
            it.x in 0 until baseline.dimension && it.y in 0 until baseline.dimension
        }
        canFixSelection = button.isEnabled && selected.all {
            (state.edits[it] ?: baseline!![it.x, it.y].value) != ModuleValue.ERASED
        }
        summary.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        if (selected.size == 1 && button.isEnabled) {
            val coord = selected.single()
            val imageValue = label(baseline!![coord.x, coord.y].value)
            val current = if (coord in state.prioritySearch) "*" else label(state.edits[coord] ?: baseline[coord.x, coord.y].value)
            summary.text = context.getString(
                R.string.cell_source_preview,
                current,
                imageValue,
            )
            if (modernStyle) summary.text = "(${coord.x},${coord.y})  " + summary.text
        } else {
            summary.text = context.getString(R.string.cell_source_selection, selected.size, selected.size - manualCount, softCount, manualCount - softCount)
        }
    }

    private fun label(value: ModuleValue): String = when (value) {
        ModuleValue.ZERO -> "0"
        ModuleValue.ONE -> "1"
        ModuleValue.ERASED -> "?"
    }
}
