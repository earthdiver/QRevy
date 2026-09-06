package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.UiMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoverySessionStoreLimitTest {
    @Test
    fun savingTwentyFirstSessionDeletesTheOldestSessionAndItsFiles() {
        val context = RuntimeEnvironment.getApplication()
        val store = RecoverySessionStore(context)
        store.deleteAll()
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "limit test",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        val firstId = "limit-session-00"

        repeat(RecoverySessionStore.MAX_SAVED_SESSIONS + 1) { index ->
            val id = "limit-session-${index.toString().padStart(2, '0')}"
            store.save(id, UiMode.SIMPLE, listOf(entry), 0)
            if (index == 0) {
                File(context.noBackupFilesDir, "qr_sessions/$firstId/synthetic-source-marker").writeText("test")
            }
            Thread.sleep(2)
        }

        val recent = store.listRecent()
        assertEquals(RecoverySessionStore.MAX_SAVED_SESSIONS, recent.size)
        assertFalse(recent.any { it.id == firstId })
        assertFalse(File(context.noBackupFilesDir, "qr_sessions/$firstId").exists())
        assertTrue(recent.any { it.id == "limit-session-20" })
        store.deleteAll()
    }

    @Test
    fun deleteAllRemovesEverySavedSession() {
        val context = RuntimeEnvironment.getApplication()
        val store = RecoverySessionStore(context)
        store.deleteAll()
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "delete all test",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        store.save("delete-all-a", UiMode.SIMPLE, listOf(entry), 0)
        store.save("delete-all-b", UiMode.SIMPLE, listOf(entry), 0)

        store.deleteAll()

        assertTrue(store.listRecent().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "qr_sessions/delete-all-a").exists())
        assertFalse(File(context.noBackupFilesDir, "qr_sessions/delete-all-b").exists())
    }
}
