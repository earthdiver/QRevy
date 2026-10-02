package io.github.earthdiver.qrrepair.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import io.github.earthdiver.qrrepair.R
import io.github.earthdiver.qrrepair.PrecisionStyle
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.createIndependentCrop

/** One shared crop/tone editor for camera imports, file imports, and later tone changes.
 * The caller owns [original]; this view owns composites and temporary rotations. */
class ImageAdjustmentEditor(
    context: Context,
    private val original: Bitmap,
    initial: ImageAdjustment = ImageAdjustment(),
    private val allowCrop: Boolean = true,
    cancelLabel: Int = R.string.cancel,
    private val applyLabel: Int = R.string.crop_and_analyze,
    private val onCancel: () -> Unit,
    private val onApply: (ImageAdjustment) -> Unit,
) : LinearLayout(context) {
    private val whiteSource = BitmapUtils.onWhiteBackground(original)
    val preview = CropOverlayView(context).apply { cropEnabled = allowCrop; setBitmap(whiteSource) }
    var adjustment: ImageAdjustment = initial
        private set
    var showingBefore: Boolean = false
        private set
    private var rotated: Bitmap = whiteSource
    private var rotation = 0f
    private var editing = false
    private var contrastSelected = true
    private var busy = false
    private var wideControls = false
    private val actions = LinearLayout(context)
    private val tools = LinearLayout(context).apply { isBaselineAligned = false }
    private val compare = action(R.string.image_before) { showingBefore = !showingBefore; refreshPreview() }
    private val applyButton = action(applyLabel) { onApply(adjustment) }
    private val status = TextView(context).apply { visibility = View.GONE; setPadding(dp(12), 0, dp(12), 0) }
    private var itemButton: Button? = null
    private var slider: SeekBar? = null

    init {
        orientation = VERTICAL
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        io.github.earthdiver.qrrepair.PrecisionStyle.button(applyButton, primary = true)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setText(if (allowCrop) R.string.crop_editor_title else R.string.image_adjustment)
                textSize = 17f
                setPadding(dp(12), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(compare, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(action(R.string.image_help) {
                AlertDialog.Builder(context).setTitle(R.string.image_adjustment)
                    .setMessage(context.getString(R.string.crop_help) + "\n\n" + context.getString(R.string.image_adjustment_help))
                    .setPositiveButton(R.string.close, null).show()
            }.apply { text = "?" }, LayoutParams(dp(48), LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(status)
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(actions.apply {
            gravity = Gravity.TOP
            isBaselineAligned = false
            setPadding(dp(8), 0, dp(8), dp(4))
            addView(action(cancelLabel, onCancel), buttonParams(0, 1f))
            addView(applyButton, buttonParams(0, 2f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuildTools()
        refreshPreview()
    }

    private fun action(label: Int, block: () -> Unit) = Button(context).apply {
        setText(label)
        contentDescription = context.getString(label)
        isAllCaps = false
        textSize = if (resources.configuration.locales[0]?.language == "ja") 12f else 11f
        minWidth = dp(48); minimumWidth = dp(48)
        minHeight = dp(36); minimumHeight = dp(36)
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        if (io.github.earthdiver.qrrepair.AppStylePreferences.isModern(context)) includeFontPadding = false
        setPadding(dp(4), dp(2), dp(4), dp(2))
        io.github.earthdiver.qrrepair.PrecisionStyle.button(this)
        setOnClickListener { if (!busy) block() }
    }

    private fun buttonParams(width: Int, weight: Float = 0f) =
        LayoutParams(width, LayoutParams.WRAP_CONTENT, weight).apply {
            if (io.github.earthdiver.qrrepair.AppStylePreferences.isModern(context)) {
                setMargins(dp(2), dp(PrecisionStyle.ACTION_TOP_MARGIN_DP), dp(2), dp(PrecisionStyle.ACTION_BOTTOM_MARGIN_DP))
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val next = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight < dp(350) || resources.configuration.fontScale > 1.2f
        if (next != wideControls) {
            wideControls = next
            rebuildTools()
        }
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        val footerButtons = buttons(tools) + buttons(actions)
        // First measure complete labels at their actual widths, then give every action
        // the tallest required height. Recompute so wider layouts can shrink again.
        footerButtons.forEach { it.layoutParams.height = LayoutParams.WRAP_CONTENT }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val height = footerButtons.maxOf { it.measuredHeight }
        footerButtons.forEach { it.layoutParams.height = height }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rebuildTools() {
        tools.removeAllViews()
        tools.orientation = if (wideControls && editing) VERTICAL else HORIZONTAL
        tools.gravity = if (editing) Gravity.CENTER_VERTICAL else Gravity.BOTTOM
        tools.setPadding(dp(8), 0, dp(8), 0)
        // Reserve only the visible controls; a second row exists only while editing on a
        // narrow screen. Empty slider space must not separate the tools from the actions.
        tools.minimumHeight = 0
        itemButton = null
        slider = null
        if (!editing) {
            if (allowCrop) tools.addView(action(R.string.image_rotate) { rotationMenu(tools.getChildAt(0)) },
                buttonParams(0, 1f))
            tools.addView(action(R.string.image_invert) {
                setAdjustment(adjustment.copy(inverted = !adjustment.inverted)); rebuildTools()
            }.apply { isSelected = adjustment.inverted; text = context.getString(R.string.image_invert) + if (adjustment.inverted) " ✓" else "" },
                buttonParams(0, 1f))
            tools.addView(action(R.string.image_adjustment) { editing = true; rebuildTools() },
                buttonParams(0, 1f))
            return
        }
        val name = if (contrastSelected) R.string.image_contrast else R.string.image_brightness
        val item = action(name) { itemButton?.let(::adjustmentMenu) }.apply { textSize = 12f }
        itemButton = item
        val seek = SeekBar(context).apply {
            max = 200
            progress = selectedValue() + 100
            minimumHeight = dp(36)
            contentDescription = context.getString(name)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateValue(progress - 100)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        slider = seek
        val done = action(R.string.image_done) { editing = false; rebuildTools() }.apply { textSize = 12f }
        if (wideControls) {
            tools.addView(LinearLayout(context).apply {
                isBaselineAligned = false
                addView(item, buttonParams(0, 1f))
                addView(done, buttonParams(dp(64)))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            tools.addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
        } else {
            tools.addView(item, buttonParams(dp(112)))
            tools.addView(seek, LayoutParams(0, dp(36), 1f).apply {
                if (io.github.earthdiver.qrrepair.AppStylePreferences.isModern(context)) {
                    topMargin = dp(PrecisionStyle.ACTION_TOP_MARGIN_DP)
                    bottomMargin = dp(PrecisionStyle.ACTION_BOTTOM_MARGIN_DP)
                }
            })
            tools.addView(done, buttonParams(dp(48)))
        }
        updateValueLabel()
    }

    private fun selectedValue() = if (contrastSelected) adjustment.contrast else adjustment.brightness
    private fun updateValue(value: Int) {
        setAdjustment(if (contrastSelected) adjustment.copy(contrast = value) else adjustment.copy(brightness = value))
    }

    fun setAdjustment(value: ImageAdjustment) {
        adjustment = value
        // Editing always returns to the result that will be analyzed.
        showingBefore = false
        slider?.progress = selectedValue() + 100
        updateValueLabel()
        refreshPreview()
    }

    private fun updateValueLabel() {
        val name = context.getString(if (contrastSelected) R.string.image_contrast else R.string.image_brightness)
        val value = selectedValue().let { if (it > 0) "+$it" else "$it" }
        itemButton?.text = "$name ▾\n$value"
        itemButton?.contentDescription = "$name $value"
    }

    private fun adjustmentMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            listOf(R.string.image_contrast, R.string.image_brightness, R.string.image_enter_value,
                R.string.image_reset_current, R.string.image_reset_all).forEachIndexed { index, label ->
                menu.add(0, index, index, label)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0, 1 -> { contrastSelected = it.itemId == 0; rebuildTools() }
                    2 -> enterValue()
                    3 -> updateValue(0)
                    4 -> setAdjustment(ImageAdjustment())
                }
                true
            }
            show()
        }
    }

    private fun enterValue() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(selectedValue().toString()); selectAll()
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.image_enter_value).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.image_apply, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in -100..100) input.error = context.getString(R.string.image_value_error)
                else { updateValue(value); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun rotationMenu(anchor: View) {
        val degrees = listOf(-90f, -1f, -.1f, .1f, 1f, 90f)
        PopupMenu(context, anchor).apply {
            degrees.forEachIndexed { index, value ->
                val label = when (index) {
                    0 -> context.getString(R.string.rotate_left_90)
                    5 -> context.getString(R.string.rotate_right_90)
                    else -> "${if (value > 0) "+" else ""}$value°"
                }
                menu.add(0, index, index, label)
            }
            setOnMenuItemClickListener { item ->
                runCatching { rotateTo(rotation + degrees[item.itemId]) }.onFailure(::showError)
                true
            }
            show()
        }
    }

    private fun rotateTo(degrees: Float) {
        val normalized = ((degrees + 180f) % 360f + 360f) % 360f - 180f
        val next = if (normalized == 0f) whiteSource else BitmapUtils.rotate(whiteSource, normalized).bitmap
        if (rotated !== whiteSource && rotated !== next) rotated.recycle()
        rotated = next
        rotation = normalized
        preview.setBitmap(next)
        refreshPreview()
    }

    private fun refreshPreview() {
        preview.setAdjustment(if (showingBefore) ImageAdjustment() else adjustment)
        compare.setText(if (showingBefore) R.string.image_before_showing else R.string.image_before)
        compare.contentDescription = compare.text
        compare.isSelected = showingBefore
        applyButton.setText(if (!showingBefore) applyLabel else if (allowCrop) R.string.image_analyze_after else R.string.image_apply_after)
        applyButton.contentDescription = applyButton.text
    }

    fun createCrop(): Bitmap = createIndependentCrop(rotated, preview.cropRect()).getOrThrow()

    fun setBusy(value: Boolean) {
        busy = value
        fun enable(view: View) {
            view.isEnabled = !value
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        enable(this)
    }

    fun showError(error: Throwable) {
        status.text = context.getString(R.string.image_adjustment_failed, error.message ?: error.javaClass.simpleName)
        status.visibility = View.VISIBLE
    }

    fun saveState() = Bundle().apply {
        putInt("contrast", adjustment.contrast); putInt("brightness", adjustment.brightness)
        putBoolean("inverted", adjustment.inverted); putFloat("rotation", rotation)
        putFloatArray("crop", preview.cropRect().let { floatArrayOf(it.left, it.top, it.right, it.bottom) })
        putBoolean("editing", editing); putBoolean("contrastSelected", contrastSelected)
        putBoolean("before", showingBefore)
    }

    fun restoreState(state: Bundle) {
        rotateTo(state.getFloat("rotation"))
        state.getFloatArray("crop")?.takeIf { it.size == 4 }?.let { preview.restoreCrop(RectF(it[0], it[1], it[2], it[3])) }
        setAdjustment(ImageAdjustment(state.getInt("contrast"), state.getInt("brightness"), state.getBoolean("inverted")))
        editing = state.getBoolean("editing"); contrastSelected = state.getBoolean("contrastSelected", true)
        showingBefore = state.getBoolean("before")
        rebuildTools(); refreshPreview()
    }

    fun dispose() {
        if (rotated !== whiteSource && !rotated.isRecycled) rotated.recycle()
        if (whiteSource !== original && !whiteSource.isRecycled) whiteSource.recycle()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
