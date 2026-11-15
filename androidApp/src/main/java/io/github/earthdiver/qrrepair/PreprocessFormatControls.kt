package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.FormatEditConflict

/** Keeps format selection UI coupled to the existing manual-constraint validation. */
internal class PreprocessFormatControls(
    private val activity: Activity,
    private val searchSettings: PreprocessSearchSettingsState,
    private val version: () -> Int,
    private val fixedEdits: () -> Map<Coord, ModuleValue>,
    private val onConflict: (FormatEditConflict) -> Unit,
    private val onRefreshLimits: () -> Unit,
    private val onChanged: () -> Unit,
    private val noteDraftChange: (String, String, String) -> Unit,
) {
    private val formatSearchCount = TextView(activity).apply {
        textSize = 12f
        gravity = Gravity.CENTER_HORIZONTAL
    }
    fun refresh() {
        eccConstraintButton.text = searchSettings.formatConstraint?.ecLevel?.let { "ECC: $it" }
            ?: ui("ECC: 自動", "ECC: Auto")
        maskConstraintButton.text = searchSettings.formatConstraint?.dataMask?.let { "mask: $it" }
            ?: ui("mask: 自動", "Mask: Auto")
        val count = searchSettings.formatConstraint?.candidateCount ?: 32
        formatSearchCount.text = ui("探索対象: ${count}形式", "Formats to search: $count")
        onRefreshLimits()
    }
    private fun updateFormatConstraint(ecLevel: ErrorCorrectionLevel?, dataMask: Int?) {
        searchSettings.updateFormatConstraint(version(), ecLevel, dataMask, fixedEdits())?.let {
            onConflict(it)
            return
        }
        refresh()
        onChanged()
        noteDraftChange(
            "format",
            ui("形式情報の探索条件を変更", "Changed format search constraint"),
            "ECC=${ecLevel ?: ui("自動", "Auto")}, mask=${dataMask ?: ui("自動", "Auto")}",
        )
    }
    private val eccConstraintButton = DialogViewFactory(activity).button(ui("ECC: 自動", "ECC: Auto")) {
        val choices = listOf<ErrorCorrectionLevel?>(
            null,
            ErrorCorrectionLevel.L,
            ErrorCorrectionLevel.M,
            ErrorCorrectionLevel.Q,
            ErrorCorrectionLevel.H,
        )
        val labels = choices.map { it?.let { level -> "ECC: $level" } ?: ui("ECC: 自動", "ECC: Auto") }.toTypedArray()
        val selected = choices.indexOf(searchSettings.formatConstraint?.ecLevel).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(ui("誤り訂正レベルを指定", "Select ECC level"))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                updateFormatConstraint(choices[which], searchSettings.formatConstraint?.dataMask)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
    private val maskConstraintButton = DialogViewFactory(activity).button(ui("mask: 自動", "Mask: Auto")) {
        val choices = listOf<Int?>(null) + (0..7)
        val labels = choices.map { it?.let { mask -> "mask: $mask" } ?: ui("mask: 自動", "Mask: Auto") }.toTypedArray()
        val selected = choices.indexOf(searchSettings.formatConstraint?.dataMask).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(ui("マスクを指定", "Select mask"))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                updateFormatConstraint(searchSettings.formatConstraint?.ecLevel, choices[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        addView(TextView(activity).apply {
            text = ui("形式情報（修復探索）", "Format information (recovery search)")
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(eccConstraintButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(maskConstraintButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(formatSearchCount)
    }
    init { refresh() }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun ui(japanese: String, english: String): String = localizedText(japanese, english)
}
