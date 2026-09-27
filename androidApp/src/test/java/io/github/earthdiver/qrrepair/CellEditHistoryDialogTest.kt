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
import org.junit.Assert.*
import org.junit.After
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
class CellEditHistoryDialogTest {
    @Test fun savingSettingsInvalidatesQueuedRecoveryAndDoesNotPublishOldResults() {
        val activity = activity()
        val worker = field("executor").get(activity) as ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        worker.submit { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        lateinit var progress: AlertDialog
        try {
            MainActivity::class.java.getDeclaredMethod("startAutoRecovery").apply { isAccessible = true }.invoke(activity)
            progress = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(progress.isShowing)
            val generation = field("workGeneration").get(activity) as java.util.concurrent.atomic.AtomicInteger
            val oldToken = generation.get()
            val dialog = open(activity)
            edit(dialog, a)
            click(dialog, "保存")
            assertTrue(generation.get() > oldToken)
            dialog.dismiss()
        } finally {
            release.countDown()
        }
        worker.submit {}.get(30, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val state = field("recoveryState").get(activity) as RecoveryUiState
        assertNull(state.autoResult)
        assertTrue(state.recoveries.isEmpty())
        assertFalse(progress.isShowing)
        assertNull(field("recoveryProgressDialog").get(activity))
    }

    private val controllers = mutableListOf<org.robolectric.android.controller.ActivityController<MainActivity>>()
    @After fun destroyActivities() {
        controllers.forEach { it.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private val a = Coord(10, 10)
    private val b = Coord(11, 10)
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(dialog: AlertDialog, label: String) {
        views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick()
    }
    private fun open(activity: MainActivity): AlertDialog {
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun edit(dialog: AlertDialog, coord: Coord) {
        views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            .onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
    }
    private fun count(dialog: AlertDialog, undo: Int, redo: Int) {
        val label = views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.startsWith("変更操作:") }.text.toString()
        assertTrue(label, label.contains("変更操作: ${undo}回"))
        assertTrue(label, label.contains("やり直し: ${redo}回"))
    }
    private fun activity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        field("prepared").set(activity, BitmapUtils.prepare(bitmap))
        field("preprocessSettings").set(activity, PreprocessSettings(version = 1, lockCorners = true, lockVersion = true,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))))
        return activity
    }

    @Test fun repairRetainsIndividualUndoAndRedoAcrossReopening() {
        val activity = activity()
        val dialog = open(activity)
        edit(dialog, a)
        edit(dialog, b)
        click(dialog, "Undo")
        count(dialog, 1, 1)
        click(dialog, "設定を保存して修復")
        assertFalse(dialog.isShowing)
        (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val reopened = open(activity)
        count(reopened, 1, 1)
        click(reopened, "Redo")
        count(reopened, 2, 0)
        click(reopened, "Undo")
        click(reopened, "Undo")
        count(reopened, 0, 2)
        reopened.dismiss()
    }

    @Test fun discardRestoresSavedHistoryIncludingRedo() {
        val activity = activity()
        val dialog = open(activity)
        edit(dialog, a)
        edit(dialog, b)
        click(dialog, "Undo")
        click(dialog, "保存")
        click(dialog, "Redo")
        click(dialog, "キャンセル")
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        val reopened = open(activity)
        count(reopened, 1, 1)
        click(reopened, "Undo")
        count(reopened, 0, 2)
        reopened.dismiss()
    }

    @Test fun historyJumpDropsPreviousCellTransactionsEvenForIdenticalSettings() {
        val activity = activity()
        val dialog = open(activity)
        edit(dialog, a)
        edit(dialog, b)
        click(dialog, "保存")
        dialog.dismiss()
        val settings = field("preprocessSettings").get(activity) as PreprocessSettings
        val entry = RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "checkpoint",
            snapshot = RecoverySnapshot(null, null, null, -1, settings))
        MainActivity::class.java.declaredMethods.single { it.name == "restoreSnapshot" }.apply { isAccessible = true }
            .invoke(activity, entry, "test", null)
        val reopened = open(activity)
        count(reopened, 1, 0)
        reopened.dismiss()
    }

    @Test fun versionChangeClearsRedoWhenAllEditsWereUndone() {
        val activity = activity()
        val dialog = open(activity)
        edit(dialog, a)
        click(dialog, "Undo")
        count(dialog, 0, 1)
        views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.startsWith("QRバージョン:") }.performClick()
        val picker = ShadowDialog.getLatestDialog() as AlertDialog
        picker.listView.performItemClick(null, 1, 1L)
        count(dialog, 0, 0)
        click(dialog, "保存")
        dialog.dismiss()
        val reopened = open(activity)
        count(reopened, 0, 0)
        reopened.dismiss()
    }

    @Test fun switchingWorkDoesNotReuseSavedTransactions() {
        val activity = activity()
        val dialog = open(activity)
        edit(dialog, a)
        edit(dialog, b)
        click(dialog, "保存")
        dialog.dismiss()
        val settings = (field("preprocessSettings").get(activity) as PreprocessSettings)
            .copy(preRecoveryEdits = mapOf(a to ModuleValue.ONE))
        val entry = RecoveryHistoryEntry(kind = HistoryEventKind.SETTINGS, label = "other work",
            snapshot = RecoverySnapshot(null, null, null, -1, settings))
        val source = field("prepared").get(activity) as BitmapUtils.Prepared
        val stored = io.github.earthdiver.qrrepair.session.StoredSession("other", 0L, UiMode.SIMPLE, listOf(entry), 0, source.bitmap)
        MainActivity::class.java.declaredMethods.single { it.name == "applyStoredSession" }.apply { isAccessible = true }
            .invoke(activity, stored, source)
        shadowOf(Looper.getMainLooper()).idle()
        val reopened = ShadowDialog.getLatestDialog() as AlertDialog
        count(reopened, 1, 0)
        click(reopened, "Undo")
        count(reopened, 0, 1)
        reopened.dismiss()
    }

    @Test fun configurationRecreationRetainsSavedUndoAndRedoInMemory() {
        val activity = activity()
        val store = field("sessionStore").get(activity) as io.github.earthdiver.qrrepair.session.RecoverySessionStore
        val id = store.newId()
        field("sessionId").set(activity, id)
        val source = field("prepared").get(activity) as BitmapUtils.Prepared
        store.saveSourceBitmap(id, source.bitmap)
        val dialog = open(activity)
        edit(dialog, a)
        edit(dialog, b)
        click(dialog, "Undo")
        click(dialog, "保存")
        (field("ioExecutor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
        dialog.dismiss()
        val recreated = controllers.single().recreate().get()
        (field("ioExecutor").get(recreated) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val reopened = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(reopened.isShowing)
        count(reopened, 1, 1)
        click(reopened, "Redo")
        count(reopened, 2, 0)
        reopened.dismiss()
    }

    @Test fun recreationRetainsUnsavedCellsSettingsAndUndoWithoutSavingThem() = checkUnsavedRecreation(saveFirst = false)

    @Test fun recreationKeepsUnsavedChangesSeparateFromTheSavedCheckpoint() = checkUnsavedRecreation(saveFirst = true)

    private fun checkUnsavedRecreation(saveFirst: Boolean) {
        for (style in AppStyle.entries) {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            AppStylePreferences.save(context, style)
            val activity = activity()
            val dialog = open(activity)
            edit(dialog, a)
            if (saveFirst) click(dialog, "保存")
            edit(dialog, b)
            click(dialog, "Undo")
            val automatic = views(dialog.window!!.decorView).filterIsInstance<android.widget.CheckBox>()
                .single { it.text.toString() == "閾値を自動設定" }
            automatic.isChecked = false
            val threshold = views(dialog.window!!.decorView).filterIsInstance<android.widget.SeekBar>()
                .first { it.max == 254 }
            threshold.progress = 73
            // Navigate away from the basic page to check that the editor page is retained too.
            val session = field("preprocessEditor").get(activity) as PreprocessEditorSession
            session.open(PreprocessPage.CELL_EDITOR)
            shadowOf(Looper.getMainLooper()).idle()
            val captured = session.captureDraft()
            val baseline = field("preprocessSettings").get(activity) as PreprocessSettings
            (field("ioExecutor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            val recreated = controllers.last().recreate().get()
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            val restoredSession = field("preprocessEditor").get(recreated) as PreprocessEditorSession
            assertEquals(captured.settings, restoredSession.captureDraft().settings)
            assertEquals(PreprocessPage.CELL_EDITOR, restoredSession.captureDraft().page)
            assertEquals(baseline, field("preprocessSettings").get(recreated))
            if (!saveFirst) assertNull(field("sessionId").get(recreated))
            count(reopened, 1, 1)
            click(reopened, "Redo")
            count(reopened, 2, 0)
            // Discard still returns to the saved checkpoint rather than treating the draft as saved.
            restoredSession.open(PreprocessPage.BASIC)
            click(reopened, if (style == AppStyle.MODERN) "ホーム" else "キャンセル")
            val confirm = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(reopened, confirm)
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(reopened.isShowing)
            val checkpoint = open(recreated)
            count(checkpoint, if (saveFirst) 1 else 0, 0)
            checkpoint.dismiss()
        }
    }

}
