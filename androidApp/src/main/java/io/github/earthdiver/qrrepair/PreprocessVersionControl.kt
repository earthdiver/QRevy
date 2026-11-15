package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog

/** Selects a QR version and confirms changes that discard existing cell edits. */
internal class PreprocessVersionControl(
    private val activity: Activity,
    private val currentVersion: () -> Int,
    private val hasEdits: () -> Boolean,
    private val onInteraction: () -> Unit,
    private val onSameVersion: () -> Unit,
    private val onCommit: (Int) -> Unit,
) {
    private var confirmationShowing = false
    val button = DialogViewFactory(activity).button(ui("QRバージョン: ${currentVersion()} / ${17 + 4 * currentVersion()}×${17 + 4 * currentVersion()}セル", "QR Version: ${currentVersion()} / ${17 + 4 * currentVersion()} modules")) {
        onInteraction()
        val labels = (1..40).map { value -> ui("V$value / ${17 + 4 * value}×${17 + 4 * value}セル", "Version $value / ${17 + 4 * value} modules") }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(ui("QRバージョンを選択", "Select QR Version"))
            .setSingleChoiceItems(labels, currentVersion() - 1) { selectionDialog, which ->
                selectionDialog.dismiss()
                requestVersion(which + 1)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun refresh() {
        val version = currentVersion()
        val dimension = 17 + 4 * version
        button.text = ui("QRバージョン: $version / $dimension×${dimension}セル", "QR Version: $version / $dimension modules")
    }

    private fun requestVersion(requested: Int) {
        onInteraction()
        if (confirmationShowing) return
        if (requested == currentVersion()) {
            onSameVersion()
            return
        }
        if (hasEdits()) {
            confirmationShowing = true
            AlertDialog.Builder(activity)
                .setTitle(ui("バージョンを変更しますか？", "Change Version?"))
                .setMessage(ui("セル編集済みです。バージョンを変更するとセル座標が変わるため、現在のセル編集はすべて解除されます。", "Cells have been edited. Changing Version changes their coordinates and clears all current cell edits."))
                .setNegativeButton(ui("変更しない", "Keep current")) { _, _ -> confirmationShowing = false }
                .setPositiveButton(ui("編集を解除して変更", "Clear edits and change")) { _, _ ->
                    confirmationShowing = false
                    onCommit(requested)
                }
                .setOnCancelListener { confirmationShowing = false }
                .show()
        } else onCommit(requested)
    }

    private fun ui(japanese: String, english: String): String = localizedText(japanese, english)
}
