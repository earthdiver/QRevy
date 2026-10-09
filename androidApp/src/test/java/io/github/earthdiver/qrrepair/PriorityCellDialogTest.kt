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
class PriorityCellDialogTest {
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
        fun choose(source: CellEditSource) = chooseSource(root, source)
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
        settings: PreprocessSettings = PreprocessSettings(version = 2),
        block: (Fixture) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val previousLanguage = AppLanguageManager.current
        AppLanguageManager.applyTo(controller.get(), AppLanguage.JAPANESE)
        val prepared = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (value == ModuleValue.ONE) Color.BLACK else Color.WHITE)
        })
        val host = Host()
        PreprocessDialogController(controller.get(), settings.copy(polarity = PolarityMode.NORMAL,
            automaticThresholds = false, manualQuad = ImageQuad(
                PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))),
            prepared, prepared, null, Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try { Fixture(dialog, host).also { it.openEditor(); block(it) } } finally {
            dialog.dismiss()
            AppLanguageManager.applyTo(controller.get(), previousLanguage)
            controller.pause().stop().destroy()
            if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

    @Test fun unassignedRolesExplainPrerequisiteInBothEditors() {
        for (large in listOf(false, true)) withFixture(settings = PreprocessSettings(version = 2, logicalGridAssigned = false)) { f ->
            val root = if (large) {
                text(f.root, "大きな編集画面を開く").performClick()
                (ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView
            } else f.root
            if (large) text(root, root.context.getString(R.string.switch_to_rectangle)).performClick()
            if (large) views(root).filterIsInstance<QrEditorView>().single().onCellSelected!!.invoke(17, 19)
            else f.select(Coord(17, 19))
            org.robolectric.shadows.ShadowToast.reset()
            text(root, "*").performClick()
            assertEquals("*を指定するには、先に四隅の位置と役割を指定してください。",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            if (large) (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            assertTrue(f.snapshot().settings.preRecoveryPrioritySearch.isEmpty())
        }
    }

    @Test fun excludedPositionsHaveSpecificNamesInBothEditors() {
        val cases = mapOf(Coord(17, 19) to "位置合せパターン", Coord(0, 13) to "余りビット",
            Coord(0, 0) to "位置検出パターン", Coord(8, 0) to "形式情報")
        for (large in listOf(false, true)) withFixture { f ->
            val dialog = if (large) {
                text(f.root, "大きな編集画面を開く").performClick()
                ShadowDialog.getLatestDialog() as AlertDialog
            } else f.dialog
            val root = dialog.window!!.decorView
            if (large) text(root, root.context.getString(R.string.switch_to_rectangle)).performClick()
            for ((coord, label) in cases) {
                if (large) views(root).filterIsInstance<QrEditorView>().single().onCellSelected!!.invoke(coord.x, coord.y)
                else f.select(coord)
                assertTrue(views(root).filterIsInstance<TextView>().any { it.text.startsWith("選択:") && it.text.contains(label) })
                text(root, "*").performClick()
                val error = ShadowDialog.getLatestDialog() as AlertDialog
                val message = error.findViewById<TextView>(android.R.id.message).text.toString()
                assertTrue(message, message.contains("指定位置は${label}です。*はデータ・ECCセルにのみ指定できます。"))
                assertTrue(message.contains("(${coord.x},${coord.y})"))
                error.dismiss()
            }
            if (large) dialog.dismiss()
            assertTrue(f.snapshot().settings.preRecoveryPrioritySearch.isEmpty())
        }
    }

    @Test fun mixedSelectionIsRejectedWithoutPartiallyAddingPriorityCells() = withFixture { f ->
        f.overlay.onCellRangeSelected!!.invoke(setOf(Coord(24, 24), Coord(17, 19)))
        f.click("*")
        val error = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(error.findViewById<TextView>(android.R.id.message).text.contains("位置合せパターン"))
        error.dismiss()
        assertTrue(f.snapshot().settings.preRecoveryPrioritySearch.isEmpty())
    }

    @Test fun codewordCellsAcceptPriorityAndVersionChangeClearsIt() = withFixture { f ->
        val coord = Coord(24, 24)
        f.select(coord)
        f.click("*")
        assertEquals(setOf(coord), f.snapshot().settings.preRecoveryPrioritySearch)
        f.click("戻る")
        views(f.root).filterIsInstance<TextView>().first { it.text.startsWith("QRバージョン:") }.performClick()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        picker.listView.performItemClick(null, 0, 0)
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        f.click("保存")
        assertEquals(1, f.host.saved!!.settings.version)
        assertTrue(f.host.saved!!.settings.preRecoveryPrioritySearch.isEmpty())
        assertTrue(f.host.saved!!.cellEdits.prioritySearch.isEmpty())
        assertTrue(f.host.saved!!.cellEdits.undo().isEmpty())
    }

    @Test fun orientationChangeRetainsValidPriorityAndExplainsRemovedMarks() {
        val a = Coord(24, 24)
        val b = Coord(10, 10)
        withFixture(settings = PreprocessSettings(version = 2, logicalGridAssigned = false,
            preRecoveryEdits = mapOf(a to ModuleValue.ERASED, b to ModuleValue.ERASED),
            preRecoveryPrioritySearch = setOf(a, b))) { f ->
            f.overlay.setImageQuad(f.overlay.imageQuad())
            f.overlay.onLogicalGridAssigned!!.invoke(1)
            val notice = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(notice.findViewById<TextView>(android.R.id.message).text.contains("(24,0)"))
            notice.dismiss()
            val saved = f.snapshot()
            assertEquals(setOf(Coord(10, 14)), saved.settings.preRecoveryPrioritySearch)
            assertEquals(ModuleValue.ERASED, saved.settings.preRecoveryEdits[Coord(24, 0)])
            saved.cellEdits.undo()
            saved.cellEdits.redo()
            assertEquals(setOf(Coord(10, 14)), saved.cellEdits.prioritySearch)
        }
    }

    companion object {
        private fun sourceButton(root: View): Button = views(root).filterIsInstance<Button>().first {
            it.text.toString() in listOf("画像から判定 ▾", "仮指定（訂正可） ▾", "固定（訂正不可） ▾", "固定（修正可） ▾", "複数の状態 ▾")
        }
        private fun chooseSource(root: View, source: CellEditSource) {
            sourceButton(root).performClick()
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            assertTrue(popup.menu.performIdentifierAction(source.ordinal, 0))
            popup.dismiss()
        }

        private fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        private fun text(root: View, label: String): TextView = views(root).filterIsInstance<TextView>().first { it.text.toString() == label }
    }
}
