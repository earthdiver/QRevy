package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import io.github.earthdiver.qrrepair.ui.QrEditorView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class CellSourceDialogTest {
    private class Host : PreprocessDialogHost {
        var saved: PreprocessSave? = null
        override fun retains(image: BitmapUtils.Prepared) = saved?.image === image
        override fun save(draft: PreprocessSave) { saved = draft }
        override fun showSavedStatus() = Unit
        override fun startRecovery() = error("Source changes must not start recovery")
        override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) = Unit
    }
    private class Fixture(val dialog: AlertDialog, val host: Host) {
        val root get() = dialog.window!!.decorView
        val overlay get() = views(root).filterIsInstance<PreprocessOverlayView>().single()
        fun click(label: String) { text(root, label).performClick() }
        fun openEditor() { assertTrue(text(root, "セル編集（長押し）").performLongClick()) }
        fun select(coord: Coord) { overlay.onEditCellSelected!!.invoke(coord.x, coord.y) }
        fun snapshot(): PreprocessSave {
            click("戻る")
            click("保存")
            openEditor()
            return host.saved!!
        }
    }

    private fun withFixture(
        value: ModuleValue = ModuleValue.ZERO,
        settings: PreprocessSettings = PreprocessSettings(),
        block: (Fixture) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val prepared = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (value == ModuleValue.ONE) Color.BLACK else Color.WHITE)
        })
        val host = Host()
        PreprocessDialogController(controller.get(), settings.copy(version = 1, polarity = PolarityMode.NORMAL,
            thresholdMode = ThresholdMode.GLOBAL, manualQuad = ImageQuad(
                PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))),
            prepared, prepared, null, Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try { Fixture(dialog, host).also { it.openEditor(); block(it) } } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
            if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

    @Test fun compactEditorKeepsChangedCellCountAboveUndoAndReportsPriorityInClassification() = withFixture { f ->
        val coord = Coord(10, 10)
        f.select(coord)
        f.click("*")
        val operations = text(f.root, "変更操作: 1回・変更セル: 1セル　やり直し: 0回")
        val undoRow = text(f.root, "Undo").parent as ViewGroup
        val column = operations.parent as ViewGroup
        assertSame(column, undoRow.parent)
        assertEquals(column.indexOfChild(operations) + 1, column.indexOfChild(undoRow))
        assertFalse(views(f.root).filterIsInstance<TextView>().any { it.text.contains("優先探索:") })
        fun classification(): String {
            val overlay = f.overlay
            overlay.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            overlay.layout(0, 0, 360, 300)
            val bitmap = Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)
            overlay.draw(Canvas(bitmap))
            bitmap.recycle()
            shadowOf(Looper.getMainLooper()).idle()
            return views(f.root).filterIsInstance<TextView>().first { it.text.startsWith("判定:") }.text.toString()
        }
        assertEquals("判定: 1=0  0=440  ?=0  *=1", classification())
        f.click("Undo")
        assertEquals("判定: 1=0  0=441  ?=0  *=0", classification())
        text(f.root, "変更操作: 0回・変更セル: 0セル　やり直し: 1回")
        f.click("Redo")
        assertEquals("判定: 1=0  0=440  ?=0  *=1", classification())
    }

    @Test fun allFourBinaryInputsUseTheSameRulesForDataAndFormatCells() {
        for (baseline in listOf(ModuleValue.ZERO, ModuleValue.ONE)) withFixture(baseline) { f ->
            for (coord in listOf(Coord(8, 0), Coord(10, 10))) {
                for (input in listOf(ModuleValue.ZERO, ModuleValue.ONE)) {
                    f.select(coord)
                    f.click(if (input == ModuleValue.ONE) "1" else "0")
                    val saved = f.snapshot()
                    if (input == baseline) assertTrue(saved.settings.preRecoveryEdits.isEmpty())
                    else assertEquals(mapOf(coord to input), saved.settings.preRecoveryEdits)
                    f.select(coord)
                    if (input != baseline) f.click("画像に戻す")
                }
            }
        }
    }

    @Test fun explicitSameValuePinAndUndoRedoPreserveSourceThroughSave() = withFixture { f ->
        val coord = Coord(8, 0)
        assertFalse(text(f.root, "手動指定にする").isEnabled)
        f.select(coord)
        f.click("手動指定にする")
        assertEquals(coord, f.overlay.selectedEditCell())
        assertTrue(text(f.root, "画像に戻す").isEnabled)
        assertTrue(views(f.root).filterIsInstance<TextView>().any { it.text.contains("由来: 手動 / 現在: 0 / 画像に戻す値: 0") })
        f.click("0")
        assertEquals(mapOf(coord to ModuleValue.ZERO), f.snapshot().settings.preRecoveryEdits)
        f.select(coord)
        f.click("画像に戻す")
        assertEquals(coord, f.overlay.selectedEditCell())
        assertEquals("手動指定にする", text(f.root, "手動指定にする").text.toString())
        f.click("Undo")
        assertEquals(mapOf(coord to ModuleValue.ZERO), f.snapshot().cellEdits.edits)
        f.click("Redo")
        assertTrue(f.snapshot().settings.preRecoveryEdits.isEmpty())
    }

    @Test fun explicitPinsRejectFormatConflictsButRestoringImageKeepsEccMaskSettings() {
        val constraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)
        val coord = FormatEditPolicy.fixedValues(1, constraint).entries.first { it.value == ModuleValue.ONE }.key
        for (lock in listOf(true, false)) withFixture(settings = PreprocessSettings(formatConstraint = constraint, lockManualBits = lock)) { f ->
            f.select(coord)
            f.click("0") // Same sampled value: no hidden format pin.
            f.click("手動指定にする")
            val error = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(f.dialog, error)
            assertTrue(error.findViewById<TextView>(android.R.id.message).text.contains("(${coord.x},${coord.y})"))
            error.dismiss()
            assertTrue(f.snapshot().settings.preRecoveryEdits.isEmpty())
            f.select(coord)
            f.click("1") // A real change that agrees with the selected format.
            f.click("画像に戻す")
            val saved = f.snapshot()
            assertTrue(saved.settings.preRecoveryEdits.isEmpty())
            assertEquals(constraint, saved.settings.formatConstraint)
            assertEquals(lock, saved.settings.lockManualBits)
        }
    }

    @Test fun mixedSelectionRestoresImageAndPriorityStateIsUndoable() = withFixture { f ->
        val a = Coord(10, 10)
        val b = Coord(11, 10)
        f.select(a)
        f.click("*")
        f.overlay.onCellRangeSelected!!.invoke(setOf(a, b))
        assertTrue(views(f.root).filterIsInstance<TextView>().any { it.text.toString() == "選択: 2セル / 画像: 1 / 手動: 1" })
        f.click("画像に戻す")
        val restored = f.snapshot()
        assertTrue(restored.settings.preRecoveryEdits.isEmpty())
        assertTrue(restored.settings.preRecoveryPrioritySearch.isEmpty())
        f.click("Undo")
        val undone = f.snapshot()
        assertEquals(mapOf(a to ModuleValue.ERASED), undone.settings.preRecoveryEdits)
        assertEquals(setOf(a), undone.settings.preRecoveryPrioritySearch)
    }

    @Test fun largeEditorSourceButtonUsesSameStateAndFitsSmallScreen() = withFixture { f ->
        f.click("大きな編集画面を開く")
        val large = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val root = large.window!!.decorView
            val editor = views(root).filterIsInstance<QrEditorView>().single()
            editor.onCellSelected!!.invoke(10, 10)
            text(root, "手動指定にする").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 640)
            val button = text(root, "画像に戻す") as Button
            val visible = android.graphics.Rect()
            assertTrue(views(root).joinToString("\n") { "${it.javaClass.simpleName} ${it.width}x${it.height} at ${it.top} visibility=${it.visibility}" }, button.getGlobalVisibleRect(visible))
            assertEquals(button.height, visible.height())
            assertTrue(editor.height > 0)
            large.dismiss()
            assertEquals(mapOf(Coord(10, 10) to ModuleValue.ZERO), f.snapshot().settings.preRecoveryEdits)
        } finally { large.dismiss() }
    }

    companion object {
        private fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        private fun text(root: View, label: String): TextView = views(root).filterIsInstance<TextView>().first { it.text.toString() == label }
    }
}
