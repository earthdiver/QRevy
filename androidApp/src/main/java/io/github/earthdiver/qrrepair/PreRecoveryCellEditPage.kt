package io.github.earthdiver.qrrepair

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.ui.EditorTool

internal data class CellEditPageActions(
    val move: (Int, Int) -> Unit,
    val setValue: (ModuleValue) -> Unit,
    val setPriority: () -> Unit,
    val toggleSource: () -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val selectMode: (EditorTool) -> Unit,
    val openLargeEditor: () -> Unit,
)

/** Builds the compact cell-edit page; edit state and QR operations remain outside this class. */
internal class PreRecoveryCellEditPage(
    context: Context,
    buttonFactory: (String, () -> Unit) -> Button,
    compactRow: (Array<out View>) -> LinearLayout,
    actions: CellEditPageActions,
) {
    val status = TextView(context).apply {
        setText(R.string.tap_cell)
        textSize = 12f
    }
    val countStatus = TextView(context).apply {
        textSize = 12f
        if (AppStylePreferences.isModern(context)) {
            setTextColor(PrecisionStyle.MUTED)
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            PrecisionStyle.numbers(this)
        }
    }
    val sourceControl = CellSourceControl(context, buttonFactory, actions.toggleSource)
    val view: View = ScrollView(context).apply {
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6))
            addView(TextView(context).apply {
                setText(R.string.cell_edit_help)
                textSize = 12f
            })
            if (AppStylePreferences.isModern(context)) {
                PrecisionStyle.numbers(status)
                sourceControl.view.addView(status, 0)
            } else addView(status)
            addView(compactRow(arrayOf(
                buttonFactory("←") { actions.move(-1, 0) },
                buttonFactory("↑") { actions.move(0, -1) },
                buttonFactory("↓") { actions.move(0, 1) },
                buttonFactory("→") { actions.move(1, 0) },
                buttonFactory("▱") { actions.selectMode(EditorTool.RECT_SELECT) }.apply {
                    contentDescription = context.getString(R.string.rectangle_select)
                },
            )))
            addView(compactRow(arrayOf(
                buttonFactory("0") { actions.setValue(ModuleValue.ZERO) },
                buttonFactory("1") { actions.setValue(ModuleValue.ONE) },
                buttonFactory("?") { actions.setValue(ModuleValue.ERASED) },
                buttonFactory("*") { actions.setPriority() },
            )))
            if (!AppStylePreferences.isModern(context)) addView(sourceControl.view)
            addView(View(context).apply {
                setBackgroundColor(if (AppStylePreferences.isModern(context)) PrecisionStyle.OUTLINE else 0x55333333)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            })
            addView(compactRow(arrayOf(
                buttonFactory(context.getString(R.string.one_cell)) { actions.selectMode(EditorTool.NAVIGATE) },
                buttonFactory(context.getString(R.string.zero_brush)) { actions.selectMode(EditorTool.BRUSH_ZERO) },
                buttonFactory(context.getString(R.string.one_brush)) { actions.selectMode(EditorTool.BRUSH_ONE) },
                buttonFactory(context.getString(R.string.unknown_brush)) { actions.selectMode(EditorTool.BRUSH_ERASURE) },
                buttonFactory(context.getString(R.string.unknown_stamp_medium)) { actions.selectMode(EditorTool.STAMP_ERASURE_MEDIUM) },
            )))
            if (AppStylePreferences.isModern(context)) addView(sourceControl.view)
            val historyButtons = compactRow(arrayOf(
                buttonFactory(context.getString(R.string.undo)) { actions.undo() },
                buttonFactory(context.getString(R.string.redo)) { actions.redo() },
            ))
            if (AppStylePreferences.isModern(context)) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                    PrecisionStyle.panel(this)
                    addView(countStatus)
                    addView(historyButtons)
                })
            } else {
                addView(countStatus)
                addView(historyButtons)
            }
            addView(buttonFactory(context.getString(R.string.open_large_editor)) { actions.openLargeEditor() })
        })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

internal data class LargeCellEditPageActions(
    val setTool: (EditorTool) -> Unit,
    val setValue: (ModuleValue) -> Unit,
    val setPriority: () -> Unit,
    val toggleSource: () -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val showCodewords: (Boolean) -> Unit,
    val close: () -> Unit,
)

/** Builds the large editor's controls around the supplied editor View. */
internal class PreRecoveryLargeCellEditPage(
    context: Context,
    editor: View,
    buttonFactory: (String, () -> Unit) -> Button,
    compactRow: (Array<out View>) -> LinearLayout,
    scrollRow: (Array<out View>) -> View,
    actions: LargeCellEditPageActions,
) {
    val status = TextView(context).apply {
        setText(R.string.pan_zoom_status)
        textSize = 12f
        setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
    }
    val countStatus = TextView(context).apply {
        textSize = 12f
        setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 4))
    }
    val sourceControl = CellSourceControl(context, buttonFactory, actions.toggleSource)
    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 2), dp(context, 6), dp(context, 2))
            addView(TextView(context).apply {
                setText(R.string.large_cell_editor_title)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(CheckBox(context).apply {
                setText(R.string.show_codeword_overlay)
                textSize = 12f
                includeFontPadding = false
                minimumHeight = dp(context, 40)
                isChecked = false
                setOnCheckedChangeListener { _, checked -> actions.showCodewords(checked) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        })
        addView(scrollRow(arrayOf(
            buttonFactory(context.getString(R.string.pan_zoom)) { actions.setTool(EditorTool.NAVIGATE) },
            buttonFactory(context.getString(R.string.zero_brush)) { actions.setTool(EditorTool.BRUSH_ZERO) },
            buttonFactory(context.getString(R.string.one_brush)) { actions.setTool(EditorTool.BRUSH_ONE) },
            buttonFactory(context.getString(R.string.unknown_brush_narrow)) { actions.setTool(EditorTool.BRUSH_ERASURE) },
            buttonFactory(context.getString(R.string.unknown_brush_wide)) { actions.setTool(EditorTool.BRUSH_ERASURE_WIDE) },
        )))
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        var stampMode = true
        fun showMode() {
            modeRow.removeAllViews()
            val toggleButton: Button
            val toolButtons: Array<Button>
            if (stampMode) {
                toggleButton = buttonFactory(context.getString(R.string.switch_to_rectangle)) {
                    stampMode = false
                    actions.setTool(EditorTool.RECT_SELECT)
                    showMode()
                }
                toolButtons = arrayOf(
                buttonFactory(context.getString(R.string.unknown_stamp_large)) { actions.setTool(EditorTool.STAMP_ERASURE_LARGE) },
                buttonFactory(context.getString(R.string.unknown_stamp_medium)) { actions.setTool(EditorTool.STAMP_ERASURE_MEDIUM) },
                buttonFactory(context.getString(R.string.unknown_stamp_small)) { actions.setTool(EditorTool.STAMP_ERASURE_SMALL) },
                )
            } else {
                toggleButton = buttonFactory(context.getString(R.string.switch_to_stamp)) {
                    stampMode = true
                    actions.setTool(EditorTool.NAVIGATE)
                    showMode()
                }
                toolButtons = arrayOf(
                buttonFactory("0") { actions.setValue(ModuleValue.ZERO) },
                buttonFactory("1") { actions.setValue(ModuleValue.ONE) },
                buttonFactory("?") { actions.setValue(ModuleValue.ERASED) },
                buttonFactory("*") { actions.setPriority() },
                )
            }
            modeRow.addView(toggleButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            toolButtons.forEach {
                modeRow.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        showMode()
        addView(modeRow)
        if (AppStylePreferences.isModern(context)) {
            PrecisionStyle.numbers(status)
            PrecisionStyle.numbers(countStatus)
            sourceControl.view.addView(status, 0)
            addView(sourceControl.view)
        } else {
            addView(status)
            addView(sourceControl.view)
        }
        if (AppStylePreferences.isModern(context)) {
            val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            while (childCount > 0) {
                val child = getChildAt(0)
                removeView(child)
                controls.addView(child)
            }
            addView(ScrollView(context).apply { addView(controls) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 2f,
            ))
        }
        addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0,
            if (AppStylePreferences.isModern(context)) 3f else 1f))
        addView(countStatus)
        addView(compactRow(arrayOf(
            buttonFactory(context.getString(R.string.undo)) { actions.undo() },
            buttonFactory(context.getString(R.string.redo)) { actions.redo() },
            buttonFactory(context.getString(R.string.close)) { actions.close() },
        )))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
