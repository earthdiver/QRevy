package io.github.earthdiver.qrrepair

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

internal data class DetailedSettingsPageActions(
    val rotateBy: (Float) -> Unit,
    val resetRotation: () -> Unit,
    val saveDiagnosticInput: () -> Unit,
)

/** Builds the detailed pre-recovery settings page; setting state and image operations stay outside. */
internal class PreRecoveryDetailedSettingsPage(
    context: Context,
    buttonFactory: (String, () -> Unit) -> Button,
    scrollRow: (Array<out View>) -> View,
    polarity: RadioGroup,
    searchQuadNeighborhood: CheckBox,
    trustKnownPayloadCount: CheckBox,
    allowFixedCorrections: CheckBox,
    formatMismatchLimitControl: View,
    dataEccMismatchLimitControl: View,
    actions: DetailedSettingsPageActions,
) {
    val view: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            addView(TextView(context).apply { setText(R.string.image_rotation) })
            addView(scrollRow(arrayOf(
                buttonFactory(context.getString(R.string.rotate_left_90)) { actions.rotateBy(-90f) },
                buttonFactory("-1°") { actions.rotateBy(-1f) },
                buttonFactory("-0.1°") { actions.rotateBy(-.1f) },
                buttonFactory("+0.1°") { actions.rotateBy(.1f) },
                buttonFactory("+1°") { actions.rotateBy(1f) },
                buttonFactory(context.getString(R.string.rotate_right_90)) { actions.rotateBy(90f) },
                buttonFactory(context.getString(R.string.reset_rotation), actions.resetRotation),
            )))
            addView(TextView(context).apply { setText(R.string.thresholding) })
            addView(polarity)
            addView(searchQuadNeighborhood)
            addView(TextView(context).apply {
                setText(R.string.geometry_search_note)
                textSize = 12f
            })
            addView(trustKnownPayloadCount)
            addView(TextView(context).apply {
                setText(R.string.payload_header_trust_note)
                textSize = 12f
            })
            addView(allowFixedCorrections)
            addView(formatMismatchLimitControl)
            addView(dataEccMismatchLimitControl)
            addView(buttonFactory(context.getString(R.string.save_debug_input), actions.saveDiagnosticInput))
        })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
