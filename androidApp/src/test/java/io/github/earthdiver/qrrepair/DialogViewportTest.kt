package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialogViewportTest {
    class ViewportActivity : Activity() {
        var metrics: WindowMetrics? = null
        override fun getWindowManager(): WindowManager {
            val original = super.getWindowManager()
            return object : WindowManager by original {
                override fun getCurrentWindowMetrics(): WindowMetrics = metrics ?: original.currentWindowMetrics
            }
        }
    }

    @Test fun dialogsFitSystemBarsCutoutsAndResizedWindows() {
        val controller = Robolectric.buildActivity(ViewportActivity::class.java).setup()
        val activity = controller.get().apply { setTheme(R.style.AppTheme) }
        fun viewport(bounds: Rect, bars: Insets, cutout: Insets = Insets.NONE) {
            activity.metrics = WindowMetrics(bounds, WindowInsets.Builder()
                .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), bars)
                .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), cutout).build(), 1f)
        }
        viewport(Rect(0, 0, 720, 1280), Insets.of(0, 36, 0, 72))
        val dialog = AlertDialog.Builder(activity).setMessage("Content").show()
        try {
            fitDialogToViewport(activity, dialog, 1f, 1f)
            assertEquals(Rect(0, 36, 720, 1208), safeWindowBounds(activity))
            assertEquals(1172, dialog.window!!.attributes.height)
            viewport(Rect(100, 0, 740, 480), Insets.of(0, 24, 48, 0), Insets.of(30, 0, 0, 0))
            activity.window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(Rect(130, 24, 692, 480), safeWindowBounds(activity))
            assertEquals(562, dialog.window!!.attributes.width)
            assertEquals(456, dialog.window!!.attributes.height)
            // Repeated layout must not accumulate another set of system-bar margins.
            activity.window.decorView.viewTreeObserver.dispatchOnGlobalLayout()
            assertEquals(456, dialog.window!!.attributes.height)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }
}
