package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
class FormatEditDialogTest {
    private fun set(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(root: View, prefix: String) = views(root).filterIsInstance<TextView>().first { it.text.startsWith(prefix) }
    private fun open(settings: PreprocessSettings): Pair<MainActivity, AlertDialog> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        set(activity, "prepared", BitmapUtils.prepare(bitmap))
        set(activity, "preprocessSettings", settings.copy(manualQuad = ImageQuad(
            PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))))
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        return activity to (ShadowDialog.getLatestDialog() as AlertDialog)
    }
    private fun pick(root: View, prefix: String, index: Int) {
        text(root, prefix).performClick()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        picker.listView.performItemClick(null, index, index.toLong())
    }
    private fun assertError(coord: Coord): AlertDialog {
        val error = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(error.isShowing)
        val message = error.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message, message.contains("(${coord.x},${coord.y})"))
        assertTrue(message, message.contains("変更は適用されません"))
        return error
    }

    @Test fun fixedEditsAndEccChangesRejectConflictsRegardlessOfCorrectionOption() {
        for (lock in listOf(true, false)) {
            val constraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
            val (coord, expected) = FormatEditPolicy.fixedValues(1, constraint).entries.first { it.value == ModuleValue.ZERO }
            val wrong = if (expected == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
            val (_, dialog) = open(PreprocessSettings(version = 1, formatConstraint = constraint, lockManualBits = lock))
            val root = dialog.window!!.decorView
            val before = text(root, "変更操作:").text.toString()
            views(root).filterIsInstance<PreprocessOverlayView>().single().onCellBrushApplied!!.invoke(setOf(coord), wrong)
            assertError(coord).dismiss()
            assertEquals(before, text(root, "変更操作:").text.toString())
            assertEquals("ECC: L", text(root, "ECC:").text.toString())
            dialog.dismiss()

            // Explicit ECC/mask consistency also applies when fixed-cell correction is allowed.
            val pins = FormatEditPolicy.fixedValues(1, constraint)
            val (_, second) = open(PreprocessSettings(version = 1, preRecoveryEdits = pins, lockManualBits = lock))
            val secondRoot = second.window!!.decorView
            val history = text(secondRoot, "変更操作:").text.toString()
            pick(secondRoot, "ECC:", 2)
            val mFixed = FormatEditPolicy.fixedValues(1, QrFormatConstraint(ErrorCorrectionLevel.M, null))
            val conflictCoord = mFixed.keys.first { pins[it] != mFixed[it] }
            assertError(conflictCoord).dismiss()
            assertEquals("ECC: 自動", text(secondRoot, "ECC:").text.toString())
            assertEquals(history, text(secondRoot, "変更操作:").text.toString())
            // A compatible ECC selection is accepted, but a contradictory mask change is rejected.
            pick(secondRoot, "ECC:", 1)
            assertEquals("ECC: L", text(secondRoot, "ECC:").text.toString())
            pick(secondRoot, "mask:", 2) // mask 1
            val maskFixed = FormatEditPolicy.fixedValues(1, QrFormatConstraint(ErrorCorrectionLevel.L, 1))
            assertError(maskFixed.keys.first { pins[it] != maskFixed[it] }).dismiss()
            assertEquals("mask: 自動", text(secondRoot, "mask:").text.toString())
            second.dismiss()
        }
    }

    @Test fun savedConflictLoadsUnchangedWithoutStartingRepair() {
        val constraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val (coord, expected) = FormatEditPolicy.fixedValues(1, constraint).entries.first()
        val wrong = if (expected == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        val settings = PreprocessSettings(version = 1, formatConstraint = constraint, preRecoveryEdits = mapOf(coord to wrong))
        val (activity, dialog) = open(PreprocessSettings())
        dialog.dismiss()
        val matrix = QrMatrix(21).apply { set(coord.x, coord.y, wrong, 1f, ModuleSource.USER) }
        val entry = RecoveryHistoryEntry(kind = HistoryEventKind.USER_EDIT, label = "synthetic saved conflict",
            snapshot = RecoverySnapshot(GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0), matrix.copy(), matrix, 0, settings))
        MainActivity::class.java.getDeclaredMethod("restoreSnapshot", RecoveryHistoryEntry::class.java,
            String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }.invoke(activity, entry, "test", null)
        val restored = MainActivity::class.java.getDeclaredField("preprocessSettings").apply { isAccessible = true }.get(activity)
        assertEquals(settings, restored)
        val error = ShadowDialog.getLatestDialog() as AlertDialog
        val message = error.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("(${coord.x},${coord.y})"))
        assertFalse(message.contains("変更は適用されません"))
        assertEquals(wrong, entry.snapshot.workingMatrix!![coord.x, coord.y].value)
        val state = MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }.get(activity) as RecoveryUiState
        assertTrue(state.recoveries.isEmpty())
        assertEquals(wrong, state.workingMatrix!![coord.x, coord.y].value)
        // A repair request must also preserve the loaded matrix when its settings are rejected.
        error.dismiss()
        MainActivity::class.java.getDeclaredMethod("startAutoRecovery").apply { isAccessible = true }.invoke(activity)
        assertTrue((ShadowDialog.getLatestDialog() as AlertDialog).isShowing)
        assertEquals(wrong, state.workingMatrix!![coord.x, coord.y].value)
        assertTrue(state.recoveries.isEmpty())
    }
}
