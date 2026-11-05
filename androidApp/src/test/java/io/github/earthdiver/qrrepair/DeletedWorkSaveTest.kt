package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class DeletedWorkSaveTest {
    @Test fun explicitSaveAfterDeletingCurrentWorkCreatesANewRestorableSession() = checkResave(deleteAll = false)
    @Test fun explicitSaveAfterDeletingAllWorkCreatesANewRestorableSession() = checkResave(deleteAll = true)

    private fun checkResave(deleteAll: Boolean) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        fun drain() {
            (field("ioExecutor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val store = RecoverySessionStore(activity)
        val workspaceSession = activity.workspaceSessionForTest()
        val source = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        try {
            store.deleteAll()
            field("prepared").set(activity, source)
            val importedId = store.newId()
            workspaceSession.restoreIdentity(importedId, workspaceSession.history.entries.toList(), workspaceSession.history.cursor)
            store.saveSourceBitmap(importedId, source.bitmap)
            field("preprocessSettings").set(activity, PreprocessSettings(manualQuad = ImageQuad(
                PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))))
            invoke("showPreprocessDialog")
            shadowOf(Looper.getMainLooper()).idle()
            val first = ShadowDialog.getLatestDialog() as AlertDialog
            descendants(first.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                .onCellBrushApplied!!.invoke(setOf(Coord(10, 10)), ModuleValue.ONE)
            click(first, "保存")
            first.dismiss()
            drain()
            val oldId = requireNotNull(workspaceSession.id)
            assertNotNull(store.load(oldId))
            if (deleteAll) invoke("deleteAllRecentSessions") else {
                MainActivity::class.java.getDeclaredMethod("deleteRecentSession", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, oldId)
            }
            drain()
            assertNull(workspaceSession.id)
            assertNull(store.load(oldId))
            assertTrue(store.listRecent().isEmpty())
            invoke("showPreprocessDialog")
            shadowOf(Looper.getMainLooper()).idle()
            val second = ShadowDialog.getLatestDialog() as AlertDialog
            // Saving unchanged work must also recreate both the image and its timeline.
            click(second, "保存")
            second.dismiss()
            drain()
            val newId = requireNotNull(workspaceSession.id)
            assertNotEquals(oldId, newId)
            assertNull(store.load(oldId))
            val restored = requireNotNull(store.load(newId))
            assertNotNull(restored.sourceBitmap)
            assertEquals(ModuleValue.ONE, restored.historyEntries[restored.historyCursor]
                .snapshot.preprocessSettings!!.preRecoveryEdits[Coord(10, 10)])
            restored.sourceBitmap?.recycle()
        } finally {
            controller.pause().stop().destroy()
            store.deleteAll()
            if (!source.bitmap.isRecycled) source.bitmap.recycle()
        }
    }

    private fun click(dialog: AlertDialog, text: String) {
        descendants(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
