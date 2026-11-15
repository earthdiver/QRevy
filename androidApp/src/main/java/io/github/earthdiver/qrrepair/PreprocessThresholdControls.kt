package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.PreprocessSettings

/** Owns threshold controls, paired values, and numeric entry for one pre-repair editor. */
internal class PreprocessThresholdControls(
    private val activity: Activity,
    initial: PreprocessSettings,
) {
    val automaticThresholds = CheckBox(activity).apply {
        text = ui("閾値を自動設定", "Set thresholds automatically")
        isChecked = initial.automaticThresholds
    }
    val blackLabel = TextView(activity)
    val black = SeekBar(activity).apply { max = 254; progress = initial.blackMax }
    val whiteLabel = TextView(activity)
    val white = SeekBar(activity).apply { max = 254; progress = initial.whiteMin - 1 }
    val automatic: Boolean get() = automaticThresholds.isChecked
    val blackMax: Int get() = black.progress
    val whiteMin: Int get() = white.progress + 1
    private var updatingThresholdControls = false

    fun refreshLabels(inverted: Boolean) {
        val (blackText, whiteText) = polarityThresholdLabels(inverted, blackMax, whiteMin, AppLanguageManager.isJapanese)
        blackLabel.text = blackText
        whiteLabel.text = whiteText
    }

    /** Sampling updates the displayed pair without creating manual edits or another preview request. */
    fun showSampledPair(blackMax: Int, whiteMin: Int, inverted: Boolean) {
        updatingThresholdControls = true
        black.progress = blackMax
        white.progress = whiteMin - 1
        updatingThresholdControls = false
        refreshLabels(inverted)
    }

    /** Bind after the host has created its preview and settings snapshot functions. */
    fun bind(
        previewInverted: () -> Boolean,
        onInvalidate: () -> Unit,
        onChanged: () -> Unit,
        onInteraction: () -> Unit,
        noteDraftChange: (String, String, String) -> Unit,
    ) {
        fun updateThreshold(slider: SeekBar, progress: Int, manual: Boolean) {
            if (updatingThresholdControls) return
            updatingThresholdControls = true
            if (manual) automaticThresholds.isChecked = false
            slider.progress = progress
            if (black.progress > white.progress) {
                if (slider === black) white.progress = black.progress
                else black.progress = white.progress
            }
            updatingThresholdControls = false
            if (manual) onInvalidate()
            onChanged()
        }
        fun recordThresholdChange(slider: SeekBar) {
            if (slider === black) {
                noteDraftChange("black", ui("QRの黒の閾値を変更", "Changed QR black threshold"), black.progress.toString())
            } else {
                noteDraftChange("white", ui("QRの白の閾値を変更", "Changed QR white threshold"), (white.progress + 1).toString())
            }
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateThreshold(seekBar, progress, fromUser)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                onInteraction()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                recordThresholdChange(seekBar)
            }
        }
        black.setOnSeekBarChangeListener(listener)
        white.setOnSeekBarChangeListener(listener)
        fun bindThresholdInput(label: TextView, slider: SeekBar, offset: Int) {
            label.setOnLongClickListener {
                val inverted = previewInverted()
                fun imageValue(logical: Int) = if (inverted) 255 - logical else logical
                val first = imageValue(offset)
                val last = imageValue(slider.max + offset)
                showThresholdInput(label.text, imageValue(slider.progress + offset), minOf(first, last)..maxOf(first, last)) { value ->
                    val logical = if (inverted) 255 - value else value
                    updateThreshold(slider, logical - offset, manual = true)
                    recordThresholdChange(slider)
                }
                true
            }
        }
        bindThresholdInput(blackLabel, black, offset = 0)
        bindThresholdInput(whiteLabel, white, offset = 1)
        automaticThresholds.setOnCheckedChangeListener { _, checked ->
            if (updatingThresholdControls) return@setOnCheckedChangeListener
            onInvalidate(); onChanged()
            noteDraftChange("threshold-auto", ui("閾値を自動設定", "Set thresholds automatically"), if (checked) "ON" else "OFF")
        }
    }

    private fun showThresholdInput(title: CharSequence, current: Int, range: IntRange, applyValue: (Int) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(current.toString())
            selectAll()
        }
        val rangeHint = activity.getString(R.string.threshold_input_range, range.first, range.last)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(rangeHint)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.image_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in range) {
                    input.error = rangeHint
                } else {
                    applyValue(value)
                    dialog.dismiss()
                }
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun ui(japanese: String, english: String): String = localizedText(japanese, english)
}
