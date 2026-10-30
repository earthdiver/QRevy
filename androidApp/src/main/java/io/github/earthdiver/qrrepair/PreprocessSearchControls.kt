package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.ErrorLimitPolicy

/** Binds search-limit controls to the editor's existing draft state. */
internal class PreprocessSearchControls(
    private val activity: Activity,
    private val searchSettings: PreprocessSearchSettingsState,
    private val cellEditState: PreRecoveryCellEditState,
    private val version: () -> Int,
    private val refreshLabels: () -> Unit,
    private val updatePreEditCountStatus: () -> Unit,
    private val noteDraftChange: (String, String, String) -> Unit,
) {
    private val dialogViews = DialogViewFactory(activity)
    private val modernStyle = AppStylePreferences.isModern(activity)
    private val resources get() = activity.resources
    private val formatMismatchLimitNote = TextView(activity).apply { textSize = 12f }
    private val formatMismatchLimitButton = button("", {})
    val formatMismatchLimitControl = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply { setText(R.string.format_mismatch_limit) })
        addView(formatMismatchLimitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(formatMismatchLimitNote)
    }
    private val dataEccMismatchLimitButton = button("", {})
    val dataEccMismatchLimitControl = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply { setText(R.string.data_ecc_mismatch_limit) })
        addView(dataEccMismatchLimitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(TextView(activity).apply {
            setText(R.string.data_ecc_mismatch_limit_note)
            textSize = 12f
        })
    }
    private val partialBitLimitButton = button("", {}).apply {
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
    }
    val partialBitSearchControl = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(partialBitLimitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(activity).apply {
            setText(R.string.partial_bit_search_limit_note)
            textSize = 12f
        })
    }
    private val listLimitButton = button("", {}).apply {
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
    }
    private val knownBitButton = button("", {}).apply {
        setSingleLine(false)
        setOnClickListener {
            val choices = arrayOf(activity.getString(R.string.known_bit_off), activity.getString(R.string.known_bit_one), activity.getString(R.string.known_bit_two))
            android.app.AlertDialog.Builder(activity).setTitle(R.string.known_bit_title)
                .setSingleChoiceItems(choices, searchSettings.knownBitErrorLimit) { dialog, which ->
                    searchSettings.knownBitErrorLimit = which
                    text = choices[which]
                    noteDraftChange("known-bit-error-limit", activity.getString(R.string.known_bit_title), choices[which])
                    dialog.dismiss()
                }.show()
        }
        text = activity.getString(when (searchSettings.knownBitErrorLimit) {
            1 -> R.string.known_bit_one; 2 -> R.string.known_bit_two; else -> R.string.known_bit_off
        })
    }
    val listDecodingControl = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(knownBitButton)
        addView(TextView(activity).apply { setText(R.string.known_bit_note); textSize = 12f })
        addView(listLimitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(activity).apply {
            setText(R.string.list_decoding_limit_note)
            textSize = 12f
        })
    }
    val priorityLimitButton = button("", {}).apply {
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    init {
        formatMismatchLimitButton.setOnClickListener {
            showBitErrorLimitDialog(activity, getString(R.string.format_mismatch_limit),
                currentMode = searchSettings.formatMismatchLimitMode,
                currentManualValue = searchSettings.formatMismatchLimit,
                maximum = 30,
                automaticLimit = ErrorLimitPolicy.format(ErrorLimitMode.AUTOMATIC, null),
                strictLimit = ErrorLimitPolicy.STRICT_FORMAT,
                relaxedLimit = ErrorLimitPolicy.RELAXED_FORMAT,
            ) { mode, value ->
                searchSettings.setFormatMismatchLimit(mode, value)
                refreshFormatMismatchLimitControl()
                refreshLabels()
                noteDraftChange(
                    "formatMismatchLimit",
                    ui("形式情報の誤り上限を変更", "Changed format-information error limit"),
                    formatMismatchLimitButton.text.toString(),
                )
            }
        }

        refreshFormatMismatchLimitControl()
        dataEccMismatchLimitButton.setOnClickListener { editDataEccLimit() }

        refreshDataEccMismatchLimitControl()
        refreshPartialBitLimit()
        partialBitLimitButton.setOnClickListener {
            val choices = io.github.earthdiver.qrrepair.core.PartialBitSearchLimit.supported.toList()
            AlertDialog.Builder(activity).setTitle(R.string.partial_bit_search_limit_title)
                .setSingleChoiceItems(choices.map { getString(R.string.partial_bit_search_limit_choice, it) }.toTypedArray(),
                    choices.indexOf(searchSettings.partialBitSearchLimit)) { dialog, which ->
                    searchSettings.partialBitSearchLimit = choices[which]
                    refreshPartialBitLimit()
                    noteDraftChange("partial-bit-limit", getString(R.string.partial_bit_search_limit_title),
                        getString(R.string.partial_bit_search_limit_choice, searchSettings.partialBitSearchLimit))
                    dialog.dismiss()
                }.setNegativeButton(R.string.close, null).show()
        }

        refreshListLimit()
        listLimitButton.setOnClickListener {
            val choices = io.github.earthdiver.qrrepair.core.ListDecodingLimit.supported.toList()
            AlertDialog.Builder(activity).setTitle(R.string.list_decoding_limit_title)
                .setSingleChoiceItems(choices.map { listLimitChoice(it) }.toTypedArray(),
                    choices.indexOf(searchSettings.listDecodingAdditionalErrors)) { dialog, which ->
                    searchSettings.listDecodingAdditionalErrors = choices[which]
                    refreshListLimit()
                    noteDraftChange("list-decoding-limit", getString(R.string.list_decoding_limit_title),
                        listLimitChoice(searchSettings.listDecodingAdditionalErrors))
                    dialog.dismiss()
                }.setNegativeButton(R.string.close, null).show()
        }

        refreshPriorityLimit()
        priorityLimitButton.setOnClickListener {
            val choices = (4..14).toList()
            AlertDialog.Builder(activity).setTitle(ui("優先探索のセル数上限", "Priority search cell limit"))
                .setSingleChoiceItems(choices.map { "$it" }.toTypedArray(), cellEditState.priorityLimit - 4) { dialog, which ->
                    cellEditState.priorityLimit = choices[which]
                    refreshPriorityLimit()
                    updatePreEditCountStatus()
                    noteDraftChange("priority-limit", ui("優先探索の上限を変更", "Changed priority search limit"), "${choices[which]}")
                    dialog.dismiss()
                    if (cellEditState.prioritySearch.size > choices[which]) {
                        AlertDialog.Builder(activity).setMessage(ui(
                            "既存の*は保持しました。修復前に上限を戻すか、*の指定数を減らしてください。",
                            "Existing * marks were preserved. Raise the limit or remove some marks before repair."))
                            .setPositiveButton(R.string.close, null).show()
                    }
                }.setNegativeButton(R.string.close, null).show()
        }
    }

    private fun button(label: String, onClick: () -> Unit) = dialogViews.button(label, onClick)

    private fun getString(id: Int) = activity.getString(id)

    private fun getString(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun ui(japanese: String, english: String) = localizedText(japanese, english)

    private fun errorLimitModeLabel(mode: ErrorLimitMode, value: Int?, valueLabel: String): String = when (mode) {
        ErrorLimitMode.AUTOMATIC -> getString(R.string.error_limit_automatic_value, valueLabel)
        ErrorLimitMode.STRICT -> getString(R.string.error_limit_strict_value, valueLabel)
        ErrorLimitMode.RELAXED -> getString(R.string.error_limit_relaxed_value, valueLabel)
        ErrorLimitMode.MANUAL -> if (value == null) valueLabel else getString(R.string.error_limit_manual_value, valueLabel)
        ErrorLimitMode.EXACT -> getString(R.string.error_limit_exact)
        ErrorLimitMode.UNLIMITED -> valueLabel
    }

    private fun effectiveFormatMismatchLimit() = searchSettings.effectiveFormatMismatchLimit()

    private fun formatMismatchLimitValueLabel(value: Int?): String = when (value) {
        null -> getString(R.string.format_mismatch_limit_unlimited)
        0 -> getString(R.string.format_mismatch_limit_exact)
        else -> resources.getQuantityString(R.plurals.format_mismatch_limit_bits, value, value)
    }

    fun refreshFormatMismatchLimitControl() {
        val effective = effectiveFormatMismatchLimit()
        formatMismatchLimitButton.text = errorLimitModeLabel(searchSettings.formatMismatchLimitMode, effective,
            formatMismatchLimitValueLabel(effective))
        formatMismatchLimitButton.isEnabled = true
        formatMismatchLimitNote.setText(R.string.format_mismatch_limit_note)
    }

    private fun effectiveDataEccMismatchLimit() = searchSettings.effectiveDataEccMismatchLimit(version())

    private fun dataEccMismatchLimitValueLabel(value: Int?): String = when (value) {
        null -> getString(R.string.data_ecc_mismatch_limit_unlimited)
        0 -> getString(R.string.data_ecc_mismatch_limit_exact)
        else -> resources.getQuantityString(R.plurals.data_ecc_mismatch_limit_bits, value, value)
    }

    private fun refreshDataEccMismatchLimitControl() {
        val effective = effectiveDataEccMismatchLimit()
        dataEccMismatchLimitButton.text = errorLimitModeLabel(searchSettings.dataEccMismatchLimitMode, effective,
            dataEccMismatchLimitValueLabel(effective))
    }

    private fun dataEccMaximum() = searchSettings.dataEccMaximum(version())

    private fun editDataEccLimit(notice: String? = null) {
        showBitErrorLimitDialog(activity, getString(R.string.data_ecc_mismatch_limit),
            currentMode = searchSettings.dataEccMismatchLimitMode,
            currentManualValue = searchSettings.dataEccMismatchLimit,
            maximum = dataEccMaximum(),
            automaticLimit = ErrorLimitPolicy.data(version(), ErrorLimitMode.AUTOMATIC, null),
            strictLimit = ErrorLimitPolicy.strictData(version()),
            relaxedLimit = ErrorLimitPolicy.relaxedData(version()),
            notice = notice,
        ) { mode, value ->
            searchSettings.setDataEccMismatchLimit(mode, value)
            refreshDataEccMismatchLimitControl()
            noteDraftChange(
                "dataEccMismatchLimit",
                ui("データ・ECCの誤り上限を変更", "Changed data/ECC error limit"),
                dataEccMismatchLimitButton.text.toString(),
            )
        }
    }

    fun requestValidDataEccLimit(): Boolean {
        if (searchSettings.dataEccLimitIsValid(version())) return true
        editDataEccLimit(ui(
            "現在の上限はVersion ${version()}のデータ・ECC総bit数を超えています。上限を再指定するか「制限なし」を選択してください。",
            "The current limit exceeds the total data/ECC bits for Version ${version()}. Enter a new limit or select Unlimited.",
        ))
        return false
    }

    fun refreshForVersionChange() {
        refreshDataEccMismatchLimitControl()
        requestValidDataEccLimit()
    }

    private fun refreshPartialBitLimit() {
        partialBitLimitButton.text = getString(R.string.partial_bit_search_limit, searchSettings.partialBitSearchLimit)
    }

    private fun listLimitChoice(limit: Int): String = if (limit == 0)
        getString(R.string.list_decoding_off_choice) else getString(R.string.list_decoding_limit_choice, limit)

    private fun refreshListLimit() {
        listLimitButton.text = if (searchSettings.listDecodingAdditionalErrors == 0)
            getString(R.string.list_decoding_off) else
            getString(R.string.list_decoding_limit, searchSettings.listDecodingAdditionalErrors)
    }

    fun refreshPriorityLimit() {
        val count = cellEditState.prioritySearch.size
        priorityLimitButton.text = SpannableString(ui(
            " * （優先探索）の上限: ${cellEditState.priorityLimit}個\n現在${count}個・${1 shl count}通り",
            " *  (priority search) limit: ${cellEditState.priorityLimit} cells\n$count selected · ${1 shl count} combinations",
        )).apply {
            setSpan(BackgroundColorSpan(if (modernStyle) PrecisionStyle.OUTLINE else 0xFFE0E8E3.toInt()),
                0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
