package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedWorkGuidanceTest {
    @Test fun savedWorkExplainsRetentionAndIndividualDeletionInEitherStyleOrLanguage() {
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val context = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(context, style)
            AppLanguageManager.save(context, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val store = RecoverySessionStore(activity)
            try {
                store.deleteAll()
                store.save("guidance", UiMode.SIMPLE, listOf(RecoveryHistoryEntry(
                    kind = HistoryEventKind.IMPORT, label = "Synthetic work",
                    snapshot = RecoverySnapshot(null, null, null, -1))), 0)
                store.save("keep-other-work", UiMode.SIMPLE, listOf(RecoveryHistoryEntry(
                    kind = HistoryEventKind.IMPORT, label = "Other synthetic work",
                    snapshot = RecoverySnapshot(null, null, null, -1))), 0)
                MainActivity::class.java.getDeclaredMethod("showRecentSessions").apply { isAccessible = true }.invoke(activity)
                val io = MainActivity::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
                    .get(activity) as ExecutorService
                val dialog = awaitSessionList(io, expectedCount = 2)
                assertTrue(dialog.listView.isShown)
                assertEquals(2, dialog.listView.adapter.count)
                val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains(activity.getString(R.string.saved_work_retention, RecoverySessionStore.MAX_SAVED_SESSIONS)))
                assertTrue(labels.contains(activity.getString(R.string.saved_work_actions_hint)))
                val list = dialog.listView
                val selectedIndex = (0 until list.adapter.count).single {
                    list.adapter.getItem(it).toString().contains("Synthetic work")
                }
                assertTrue(list.onItemLongClickListener!!.onItemLongClick(list, list, selectedIndex, selectedIndex.toLong()))
                shadowOf(Looper.getMainLooper()).idle()
                val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
                assertNotSame(dialog, confirmation)
                assertNotNull(store.load("guidance"))
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val remaining = awaitSessionList(io, expectedCount = 1)
                assertNull(store.load("guidance"))
                assertNotNull(store.load("keep-other-work"))
                assertEquals(1, remaining.listView.adapter.count)
                remaining.dismiss()
            } finally {
                controller.pause().stop().destroy()
                store.deleteAll()
            }
        }
    }

    private fun awaitSessionList(io: ExecutorService, expectedCount: Int): AlertDialog {
        repeat(10) {
            io.submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getShownDialogs()
                .filterIsInstance<AlertDialog>()
                .lastOrNull { it.listView?.adapter?.count == expectedCount }
                ?.let { return it }
        }
        fail("Saved-work dialog with $expectedCount entries was not shown")
        throw AssertionError("unreachable")
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
