package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup

/** Shared native dialog chrome for draft and repair-result data. */
internal fun showDataDialog(activity: Activity, content: View): AlertDialog {
    return AlertDialog.Builder(activity)
        .setTitle(R.string.data)
        .setView(content)
        .setPositiveButton(R.string.close, null)
        .show().also { dialog ->
            // AlertDialog's panel container otherwise wraps short text inside the
            // fixed window, moving its buttons and leaving unused space below.
            val host = dialog.window?.findViewById<ViewGroup>(android.R.id.content)
            if (host != null) for (index in 0 until host.childCount) {
                val panel = host.getChildAt(index)
                panel.layoutParams = panel.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            }
            dialog.findViewById<View>(android.R.id.custom)?.let { custom ->
                custom.layoutParams = custom.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            }
            fitDialogToViewport(activity, dialog, heightFraction = .85f)
        }
}
