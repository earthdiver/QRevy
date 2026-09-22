package io.github.earthdiver.qrrepair

import android.view.View
import android.widget.LinearLayout

/** Keeps spacing between settings separate from the label, control, and help within a setting. */
internal fun LinearLayout.addSettingsItem(view: View, topSpacingDp: Int) {
    addView(view, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (topSpacingDp * resources.displayMetrics.density).toInt()
    })
}
