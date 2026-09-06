package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.earthdiver.qrrepair.core.FinderRole

internal data class BasicSettingsPageActions(
    val openDetails: () -> Unit,
    val openCellEditor: () -> Unit,
    val nudgeCorner: (dx: Int, dy: Int, moduleStep: Double) -> Boolean,
    val applyPerspectiveCorrection: () -> Unit,
    val beginFinderPlacement: (FinderRole) -> Unit,
    val beginFinderRemoval: () -> Boolean,
)

/** Builds the basic pre-recovery settings page; image analysis and state remain with the host. */
internal class PreRecoveryBasicSettingsPage(
    private val context: Context,
    buttonFactory: (String, () -> Unit) -> Button,
    compactRow: (Array<out View>) -> LinearLayout,
    versionButton: Button,
    blackLabel: TextView,
    blackSeekBar: SeekBar,
    whiteLabel: TextView,
    whiteSeekBar: SeekBar,
    useUnknownColors: CheckBox,
    scrollContentFooter: View? = null,
    private val actions: BasicSettingsPageActions,
) {
    private val nudgeSteps = listOf(.05, .10, .25, .50, 1.0)
    private val nudgeStepLabels = arrayOf(
        "0.05 module", "0.10 module", "0.25 module", "0.50 module", "1.00 module",
    )
    private var nudgeStepIndex = 1

    val view: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            addView(buttonFactory(context.getString(R.string.details), actions.openDetails))
            lateinit var nudgeStepButton: Button
            nudgeStepButton = buttonFactory(context.getString(R.string.nudge_step, nudgeStepLabels[nudgeStepIndex])) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.select_nudge_step)
                    .setSingleChoiceItems(nudgeStepLabels, nudgeStepIndex) { dialog, which ->
                        nudgeStepIndex = which
                        nudgeStepButton.text = context.getString(R.string.nudge_step, nudgeStepLabels[which])
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
            addView(compactRow(arrayOf(
                nudgeStepButton,
                buttonFactory(context.getString(R.string.apply_perspective), actions.applyPerspectiveCorrection),
            )))
            fun nudgeButton(label: String, dx: Int, dy: Int) = buttonFactory(label) {
                if (!actions.nudgeCorner(dx, dy, nudgeSteps[nudgeStepIndex])) {
                    toast(context.getString(R.string.tap_orange_corner))
                }
            }
            addView(compactRow(arrayOf(
                nudgeButton("←", -1, 0), nudgeButton("↑", 0, -1),
                nudgeButton("↓", 0, 1), nudgeButton("→", 1, 0),
            )))
            addView(versionButton)
            addView(useUnknownColors)
            addView(blackLabel)
            addView(blackSeekBar)
            addView(whiteLabel)
            addView(whiteSeekBar)
            if (!AppStylePreferences.isModern(context)) {
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)))
                addView(buttonFactory(context.getString(R.string.cell_edit)) {}.apply {
                    PrecisionStyle.button(this, primary = true)
                    setOnLongClickListener {
                        actions.openCellEditor()
                        true
                    }
                })
            }
            addView(TextView(context).apply { setText(R.string.finder_manual) })
            addView(compactRow(arrayOf(
                finderPlacementButton(buttonFactory, context.getString(R.string.top_left), FinderRole.TOP_LEFT),
                finderPlacementButton(buttonFactory, context.getString(R.string.top_right), FinderRole.TOP_RIGHT),
                finderPlacementButton(buttonFactory, context.getString(R.string.bottom_left), FinderRole.BOTTOM_LEFT),
                buttonFactory(context.getString(R.string.clear)) {
                    if (actions.beginFinderRemoval()) toast(context.getString(R.string.tap_finder_to_remove), Toast.LENGTH_LONG)
                    else toast(context.getString(R.string.no_confirmed_finder))
                },
            )))
            addView(TextView(context).apply {
                setText(R.string.fixed_geometry_summary)
                textSize = 12f
            })
            scrollContentFooter?.let { addView(it) }
        })
    }

    private fun finderPlacementButton(
        buttonFactory: (String, () -> Unit) -> Button,
        label: String,
        role: FinderRole,
    ) = buttonFactory(label) {
        actions.beginFinderPlacement(role)
        toast(context.getString(R.string.finder_placement_help, label), Toast.LENGTH_LONG)
    }

    private fun toast(message: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(context, message, duration).show()

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
