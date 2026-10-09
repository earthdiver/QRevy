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
    prioritySearchControl: View? = null,
    partialBitSearchControl: View? = null,
    mirroredInput: CheckBox? = null,
    listDecodingControl: View? = null,
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
            mirroredInput?.let {
                addSettingsItem(it, 6)
                addView(TextView(context).apply { setText(R.string.mirrored_input_note); textSize = 12f })
            }
            addSettingsItem(TextView(context).apply { setText(R.string.thresholding) }, 6)
            addView(polarity)
            partialBitSearchControl?.let { addSettingsItem(it, 6) }
            listDecodingControl?.let { addSettingsItem(it, 6) }
            prioritySearchControl?.let { addSettingsItem(it, 6) }
            addSettingsItem(searchQuadNeighborhood, 6)
            addView(TextView(context).apply {
                setText(R.string.geometry_search_note)
                textSize = 12f
            })
            addSettingsItem(trustKnownPayloadCount, 6)
            addView(TextView(context).apply {
                setText(R.string.payload_header_trust_note)
                textSize = 12f
            })
            addSettingsItem(allowFixedCorrections, 6)
            addSettingsItem(formatMismatchLimitControl, 6)
            addSettingsItem(dataEccMismatchLimitControl, 6)
            addSettingsItem(buttonFactory(context.getString(R.string.save_debug_input), actions.saveDiagnosticInput), 6)
        })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
