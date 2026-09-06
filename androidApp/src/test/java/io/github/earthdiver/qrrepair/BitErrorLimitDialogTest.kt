package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class BitErrorLimitDialogTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun input(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
    private fun step(dialog: AlertDialog, label: String) = views(dialog.window!!.decorView)
        .filterIsInstance<Button>().first { it.text.toString() == label }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
    private fun activity() = Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun invalidInputStaysOpenAndValidInputAppliesOnlyOnConfirmation() {
        val activity = activity()
        var applied: Int? = 5
        var calls = 0
        val dialog = showBitErrorLimitDialog(activity, "test", 5, 30) { applied = it; calls++ }
        val input = input(dialog)
        for (invalid in listOf("", "-1", "31", "1.5", "999999999999999999999999")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
            assertTrue(dialog.isShowing)
            assertNotNull(input.error)
            assertEquals(0, calls)
        }
        input.setText("29")
        step(dialog, "＋")
        step(dialog, "＋")
        assertEquals("30", input.text.toString())
        step(dialog, "−")
        assertEquals("29", input.text.toString())
        assertEquals(5, applied)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(29, applied)
        assertEquals(1, calls)
        assertFalse(dialog.isShowing)
    }

    @Test fun cancelPreservesValueAndUnlimitedIsExplicitEvenForInvalidLegacyValue() {
        val activity = activity()
        var applied: Int? = 29648
        val dialog = showBitErrorLimitDialog(activity, "test", applied, 208) { applied = it }
        assertEquals("29648", input(dialog).text.toString())
        assertNotNull(input(dialog).error)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(29648, applied)
        val next = showBitErrorLimitDialog(activity, "test", applied, 208) { applied = it }
        next.getButton(AlertDialog.BUTTON_NEUTRAL).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertNull(applied)
        val zero = showBitErrorLimitDialog(activity, "test", 0, 30) { applied = it }
        step(zero, "−")
        assertEquals("0", input(zero).text.toString())
        zero.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertEquals(0, applied)
    }

    @Test fun decreasingVersionRequestsNewLimitWithoutClampingAndRechecksBeforeSave() {
        val activity = activity()
        fun set(name: String, value: Any) = MainActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(activity, value)
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        set("prepared", BitmapUtils.prepare(bitmap))
        set("preprocessSettings", PreprocessSettings(version = 2, dataEccMismatchLimit = 300))
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        val editor = ShadowDialog.getLatestDialog() as AlertDialog
        val root = editor.window!!.decorView
        views(root).filterIsInstance<Button>().first { it.text.startsWith("QRバージョン:") }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0).also { shadowOf(Looper.getMainLooper()).idle() }
        val limit = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("300", input(limit).text.toString())
        assertTrue(limit.findViewById<TextView>(android.R.id.message).text.contains("208"))
        input(limit).setText("209")
        limit.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(limit.isShowing)
        limit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        // Save is intercepted while the invalid draft remains, without changing persisted settings.
        views(root).filterIsInstance<Button>().first { it.text.toString() == "保存" }.performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("300", input(again).text.toString())
        val saved = MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }.get(activity) as PreprocessSettings
        assertEquals(2, saved.version)
        assertEquals(300, saved.dataEccMismatchLimit)
        input(again).setText("208")
        again.getButton(AlertDialog.BUTTON_POSITIVE).performClick().also { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(views(root).filterIsInstance<Button>().any { it.text.toString() == "208bit" })
    }
}
