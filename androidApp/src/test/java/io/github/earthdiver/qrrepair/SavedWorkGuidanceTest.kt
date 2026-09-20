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
    @Test fun retentionNoticeDoesNotHideSavedWorkInEitherStyleOrLanguage() {
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
                MainActivity::class.java.getDeclaredMethod("showRecentSessions").apply { isAccessible = true }.invoke(activity)
                val io = MainActivity::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
                    .get(activity) as ExecutorService
                io.submit {}.get(30, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.listView.isShown)
                assertEquals(1, dialog.listView.adapter.count)
                val labels = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.contains(activity.getString(R.string.saved_work_retention, RecoverySessionStore.MAX_SAVED_SESSIONS)))
                dialog.dismiss()
            } finally {
                controller.pause().stop().destroy()
                store.deleteAll()
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
