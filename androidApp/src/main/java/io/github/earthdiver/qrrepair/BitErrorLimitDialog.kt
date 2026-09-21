package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import io.github.earthdiver.qrrepair.core.ErrorLimitMode

/** Preset and manual edits remain local until Apply; Cancel preserves the caller's draft. */
internal fun showBitErrorLimitDialog(
    context: Context,
    title: String,
    currentMode: ErrorLimitMode,
    currentManualValue: Int?,
    maximum: Int,
    automaticLimit: Int?,
    strictLimit: Int,
    relaxedLimit: Int,
    notice: String? = null,
    onApply: (ErrorLimitMode, Int?) -> Unit,
): AlertDialog {
    require(maximum >= 0)
    require(automaticLimit == null || automaticLimit in 0..maximum)
    require(strictLimit in 0..maximum && relaxedLimit in 0..maximum)
    val range = localizedText("0〜${maximum}bitの整数を入力してください。", "Enter a whole number from 0 to $maximum bits.")
    val initialMode = if (currentMode == ErrorLimitMode.MANUAL && currentManualValue == null)
        ErrorLimitMode.UNLIMITED else currentMode
    var selectedMode = initialMode
    var manualValue = currentManualValue
    var updatingControls = false
    fun bits(value: Int?): String = value?.let {
        context.resources.getQuantityString(R.plurals.data_ecc_mismatch_limit_bits, it, it)
    } ?: context.getString(R.string.data_ecc_mismatch_limit_unlimited)
    fun displayedValue(mode: ErrorLimitMode): Int = when (mode) {
        ErrorLimitMode.AUTOMATIC -> automaticLimit ?: manualValue ?: 0
        ErrorLimitMode.EXACT -> 0
        ErrorLimitMode.STRICT -> strictLimit
        ErrorLimitMode.RELAXED -> relaxedLimit
        ErrorLimitMode.MANUAL, ErrorLimitMode.UNLIMITED -> manualValue ?: 0
    }
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText(displayedValue(initialMode).toString())
        contentDescription = title
    }
    val choices = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
    val buttons = linkedMapOf<ErrorLimitMode, RadioButton>()
    val labels = linkedMapOf(
        ErrorLimitMode.AUTOMATIC to context.getString(R.string.error_limit_automatic_value, bits(automaticLimit)),
        ErrorLimitMode.EXACT to context.getString(R.string.error_limit_exact),
        ErrorLimitMode.STRICT to context.getString(R.string.error_limit_strict_value, bits(strictLimit)),
        ErrorLimitMode.RELAXED to context.getString(R.string.error_limit_relaxed_value, bits(relaxedLimit)),
        ErrorLimitMode.MANUAL to context.getString(R.string.error_limit_manual),
        ErrorLimitMode.UNLIMITED to context.getString(R.string.data_ecc_mismatch_limit_unlimited),
    )
    labels.forEach { (mode, label) ->
        val button = RadioButton(context).apply {
            id = View.generateViewId()
            text = label
        }
        buttons[mode] = button
        choices.addView(button)
    }
    choices.check(buttons.getValue(initialMode).id)
    choices.setOnCheckedChangeListener { _, checkedId ->
        if (!updatingControls) {
            selectedMode = buttons.entries.first { it.value.id == checkedId }.key
            updatingControls = true
            input.setText(displayedValue(selectedMode).toString())
            input.setSelection(input.text.length)
            input.error = null
            updatingControls = false
        }
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(text: Editable?) {
            if (updatingControls) return
            selectedMode = ErrorLimitMode.MANUAL
            updatingControls = true
            choices.check(buttons.getValue(ErrorLimitMode.MANUAL).id)
            updatingControls = false
            text?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 0..maximum }?.let { manualValue = it }
            input.error = null
        }
    })
    fun readValue(): Int? {
        val value = input.text.toString().trim().toIntOrNull()
        if (value == null || value !in 0..maximum) {
            input.error = range
            return null
        }
        input.error = null
        return value
    }
    fun stepButton(label: String, delta: Int) = Button(context).apply {
        text = label
        contentDescription = localizedText(if (delta < 0) "1bit減らす" else "1bit増やす",
            if (delta < 0) "Decrease by 1 bit" else "Increase by 1 bit")
        setOnClickListener {
            val value = readValue() ?: return@setOnClickListener
            input.setText((value.toLong() + delta).coerceIn(0L, maximum.toLong()).toString())
            input.setSelection(input.text.length)
        }
    }
    val row = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(stepButton("−", -1))
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(stepButton("＋", 1))
    }
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(choices)
        addView(row)
    }
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(listOfNotNull(notice, range, context.getString(R.string.error_limit_preset_help)).joinToString("\n"))
        .setView(ScrollView(context).apply { addView(content) })
        .setPositiveButton(R.string.apply_limit, null)
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (selectedMode == ErrorLimitMode.MANUAL) manualValue = readValue() ?: return@setOnClickListener
        onApply(selectedMode, manualValue)
        dialog.dismiss()
    }
    if (initialMode == ErrorLimitMode.MANUAL && (currentManualValue == null || currentManualValue !in 0..maximum)) input.error = range
    return dialog
}
