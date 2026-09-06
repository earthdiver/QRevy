package io.github.earthdiver.qrrepair

import android.app.Activity
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
class PreprocessDialogControllerTest {
    private class Host : PreprocessDialogHost {
        val saves = mutableListOf<PreprocessSave>()
        val effects = mutableListOf<String>()
        var diagnostic: Bitmap? = null
        override fun retains(image: BitmapUtils.Prepared) =
            saves.lastOrNull()?.image === image || diagnostic === image.bitmap
        override fun save(draft: PreprocessSave) { saves += draft; effects += "save" }
        override fun showSavedStatus() { effects += "saved status" }
        override fun startRecovery() { effects += "repair" }
        override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) { diagnostic = bitmap }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun click(dialog: AlertDialog, label: String) {
        views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == label }.performClick()
    }

    private fun withDialog(block: (AlertDialog, Host, BitmapUtils.Prepared, BitmapUtils.Prepared) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val source = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        })
        val working = BitmapUtils.rotate(source.bitmap, 15f)
        val host = Host()
        PreprocessDialogController(activity.get(), PreprocessSettings(version = 1, rotationDegrees = 15f,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
                PointD(220.0, 220.0), PointD(20.0, 220.0))),
            source, working, null, Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try { block(dialog, host, source, working) } finally {
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            activity.pause().stop().destroy()
            if (!working.bitmap.isRecycled) working.bitmap.recycle()
            if (!source.bitmap.isRecycled) source.bitmap.recycle()
        }
    }

    @Test fun dismissDiscardsTemporaryImageWithoutSaving() = withDialog { dialog, host, source, working ->
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(host.saves.isEmpty())
        assertTrue(working.bitmap.isRecycled)
        assertFalse(source.bitmap.isRecycled)
    }

    @Test fun saveHandsOffImageAndCopiesEditsBeforeFurtherChanges() = withDialog { dialog, host, _, working ->
        val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
        // Explicitly edit the value; later draft edits must not mutate the saved snapshot.
        val coord = Coord(10, 10)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
        click(dialog, "保存")
        val saved = host.saves.single()
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ZERO)
        assertEquals(ModuleValue.ONE, saved.settings.preRecoveryEdits[coord])
        assertEquals(ModuleValue.ONE, saved.cellEdits.edits[coord])
        assertTrue(saved.events.isNotEmpty())
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertEquals(listOf("save", "saved status"), host.effects)
    }

    @Test fun repairStartsAfterSavingAndRetainsTheImage() = withDialog { dialog, host, _, working ->
        click(dialog, "設定を保存して修復")
        assertEquals(listOf("save", "repair"), host.effects)
        assertFalse(dialog.isShowing)
        assertFalse(working.bitmap.isRecycled)
    }

    @Test fun dismissKeepsImageOwnedByDiagnosticExport() = withDialog { dialog, host, _, working ->
        host.diagnostic = working.bitmap
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertTrue(host.saves.isEmpty())
    }
}
