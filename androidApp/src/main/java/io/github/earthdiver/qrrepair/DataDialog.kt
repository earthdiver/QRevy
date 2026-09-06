package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.view.View

/** Shared native dialog chrome for draft and repair-result data. */
internal fun showDataDialog(activity: Activity, content: View): AlertDialog {
    if (AppStylePreferences.isModern(activity)) {
        content.minimumHeight = (activity.resources.displayMetrics.heightPixels * .65f).toInt()
    }
    return AlertDialog.Builder(activity)
        .setTitle(R.string.data)
        .setView(content)
        .setPositiveButton(R.string.close, null)
        .show()
}
