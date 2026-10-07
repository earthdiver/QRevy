package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrCellRegions
import io.github.earthdiver.qrrepair.core.QrMatrix

/** Applies cell commands and reports their effects to both editor views through one host. */
internal class PreprocessCellEditController(
    private val context: Context,
    private val cells: PreRecoveryCellEditState,
    private val host: Host,
) {
    interface Host {
        fun observedMatrix(): QrMatrix?
        fun hasLogicalGrid(): Boolean
        fun regions(): QrCellRegions
        fun regionLabel(coord: Coord): String
        fun refreshClassification()
        fun refreshEditedCells(changed: Set<Coord>, preserveFocus: Boolean)
        fun showStatus(text: String)
        fun recordChange(key: String, label: String, detail: String)
    }

    fun setValues(coords: Collection<Coord>, value: ModuleValue) {
        val original = host.observedMatrix() ?: return
        val changed = cells.applyValues(coords, value) { original.observedValue(it) }
        if (changed.isEmpty()) return
        host.refreshEditedCells(changed, preserveFocus = false)
        host.showStatus(ui("${changed.size}セルを変更しました。", "Changed ${changed.size} cell(s)."))
        record("cell-edit", ui("修復前セルを編集", "Edited pre-repair cells"), changed)
    }

    fun changeSource(source: CellEditSource) {
        val original = host.observedMatrix() ?: return
        val selected = cells.selectedCells
        if (selected.isEmpty()) return
        val changed = cells.setSource(selected, source) { original.observedValue(it) }
        if (changed.isEmpty()) return
        // Classification clears selection, so restore the edited focus afterwards.
        host.refreshClassification()
        host.refreshEditedCells(changed, preserveFocus = false)
        val label = context.getString(when (source) {
            CellEditSource.IMAGE -> R.string.cell_source_image
            CellEditSource.TENTATIVE -> R.string.cell_source_tentative
            CellEditSource.FIXED -> R.string.cell_source_fixed
        })
        host.showStatus(ui("${changed.size}セル: $label", "${changed.size} cell(s): $label"))
        record("cell-source", label, changed)
    }

    fun setPriority(coords: Collection<Coord>) {
        val distinct = coords.distinct()
        if (distinct.isEmpty()) return
        if (!host.hasLogicalGrid()) {
            Toast.makeText(context, R.string.priority_requires_geometry, Toast.LENGTH_LONG).show()
            return
        }
        val invalid = distinct.filter { it !in host.regions().codewordCoordinates }
        if (invalid.isNotEmpty()) {
            val kinds = invalid.map(host::regionLabel).distinct().joinToString(" / ")
            val message = context.getString(R.string.priority_invalid_position, kinds) + "\n" +
                invalid.joinToString(" ") { "(${it.x},${it.y})" }
            AlertDialog.Builder(context).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        when (cells.setPrioritySearch(distinct)) {
            PriorityEditResult.LIMIT_EXCEEDED -> {
                Toast.makeText(context, ui("*は最大${cells.priorityLimit}セルです。詳細設定で変更できます。",
                    "Up to ${cells.priorityLimit} cells can be marked *. Change this in advanced settings."), Toast.LENGTH_SHORT).show()
                return
            }
            PriorityEditResult.UNCHANGED -> return
            PriorityEditResult.CHANGED -> Unit
        }
        val changed = cells.selectedCells
        host.refreshEditedCells(changed, preserveFocus = true)
        host.showStatus(ui("${changed.size}セルを*に指定しました。", "Marked ${changed.size} cell(s) as *."))
        record("cell-edit-priority", ui("優先探索セルを指定", "Marked priority-search cells"), changed)
    }

    fun undo() {
        val changed = cells.undo()
        if (changed.isEmpty()) return
        host.refreshEditedCells(changed, preserveFocus = false)
        host.showStatus(ui("${changed.size}セルの編集を戻しました。", "Undid edits to ${changed.size} cell(s)."))
        record("cell-edit", ui("修復前セル編集をUndo", "Undid pre-repair cell edits"), changed)
    }

    fun redo() {
        val changed = cells.redo()
        if (changed.isEmpty()) return
        host.refreshEditedCells(changed, preserveFocus = false)
        host.showStatus(ui("${changed.size}セルの編集をやり直しました。", "Redid edits to ${changed.size} cell(s)."))
        record("cell-edit", ui("修復前セル編集をRedo", "Redid pre-repair cell edits"), changed)
    }

    private fun record(key: String, label: String, changed: Set<Coord>) =
        host.recordChange(key, label, ui("${changed.size}セル", "${changed.size} cell(s)"))

    private fun QrMatrix.observedValue(coord: Coord): ModuleValue? =
        takeIf { coord.x in 0 until dimension && coord.y in 0 until dimension }?.get(coord.x, coord.y)?.value

    private fun ui(japanese: String, english: String): String =
        localizedText(japanese, english)
}
