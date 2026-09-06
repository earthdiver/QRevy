package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/** Edits a draft only after Apply or Unlimited; invalid input never dismisses the dialog. */
internal fun showBitErrorLimitDialog(
    context: Context,
    title: String,
    current: Int?,
    maximum: Int,
    notice: String? = null,
    onApply: (Int?) -> Unit,
): AlertDialog {
    val range = localizedText("0〜${maximum}bitの整数を入力してください。", "Enter a whole number from 0 to $maximum bits.")
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText((current ?: 0).toString())
        selectAll()
        contentDescription = title
    }
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
            input.setText((value + delta).coerceIn(0, maximum).toString())
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
    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(listOfNotNull(notice, range, context.getString(R.string.data_ecc_mismatch_limit_select)).joinToString("\n"))
        .setView(row)
        .setPositiveButton(R.string.apply_limit, null)
        .setNeutralButton(R.string.data_ecc_mismatch_limit_unlimited) { _, _ -> onApply(null) }
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = readValue() ?: return@setOnClickListener
        onApply(value)
        dialog.dismiss()
    }
    if (current != null && current !in 0..maximum) input.error = range
    return dialog
}
