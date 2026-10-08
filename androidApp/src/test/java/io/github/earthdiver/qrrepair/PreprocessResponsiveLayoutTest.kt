package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w640dp-h360dp-land-mdpi")
class PreprocessResponsiveLayoutTest {
    @Test fun resizingKeepsPreviewAndPrimaryActionsVisibleInBothStyles() {
        val app = RuntimeEnvironment.getApplication()
        AppLanguageManager.save(app, AppLanguage.ENGLISH)
        for (style in AppStyle.entries) {
            AppStylePreferences.save(app, style)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            try {
                val activity = controller.get()
                MainActivity::class.java.getDeclaredField("prepared").apply {
                    isAccessible = true; set(activity, BitmapUtils.prepare(bitmap))
                }
                MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply {
                    isAccessible = true; invoke(activity)
                }
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val decor = dialog.window!!.decorView
                val overlay = views(decor).filterIsInstance<PreprocessOverlayView>().single()
                val actions = views(decor).filterIsInstance<Button>().filter { it.text in listOf("Save", "Repair", "Save & repair") }
                assertEquals(2, actions.size)
                for ((width, height) in listOf(640 to 360, 360 to 640, 800 to 480, 600 to 300)) {
                    decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    decor.layout(0, 0, width, height)
                    for (view in actions + overlay) {
                        val rect = Rect(0, 0, view.width, view.height)
                        (decor as ViewGroup).offsetDescendantRectToMyCoords(view, rect)
                        assertTrue("$style $width x $height: clipped ${view.javaClass.simpleName} $rect",
                            Rect(0, 0, width, height).contains(rect))
                        assertTrue(view.isShown)
                        assertTrue(view.height > 0 && view.width > 0)
                        if (view is Button) assertTrue("$style $width x $height: ${view.text} height=${view.height}",
                            view.height >= view.minimumHeight)
                    }
                    if (style == AppStyle.MODERN) assertEquals(overlay.width, overlay.height)
                }
                dialog.dismiss()
            } finally {
                controller.pause().stop().destroy()
                bitmap.recycle()
            }
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
