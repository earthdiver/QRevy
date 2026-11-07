package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import io.github.earthdiver.qrrepair.session.SessionSummary
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceSessionStartupTest {
    private class Jobs : Executor {
        private val pending = ArrayDeque<Runnable>()
        var running = false
            private set

        override fun execute(command: Runnable) { pending.addLast(command) }
        fun dispatch(action: () -> Unit) = execute(Runnable { action() })
        fun runNext() {
            running = true
            try { pending.removeFirst().run() } finally { running = false }
        }
        fun drain() { while (pending.isNotEmpty()) runNext() }
    }

    @Test fun workStartedBeforeStorageIsReadyQueuesItsImageAndHistoryWithoutInitializingOnTheCaller() {
        for (attachExistingHistory in listOf(false, true)) withSessionRoot { root ->
            val worker = Jobs()
            val ui = Jobs()
            val errors = mutableListOf<Throwable>()
            var creations = 0
            val session = WorkspaceSessionCoordinator(
                createStore = {
                    assertTrue(worker.running)
                    creations++
                    RecoverySessionStore(RuntimeEnvironment.getApplication())
                },
                executor = worker,
                dispatchToUi = ui::dispatch,
                onSaveError = errors::add,
            )
            val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val checkpoint = checkpoint()
            var recent: Result<List<SessionSummary>>? = null
            var loaded: WorkspaceSessionCoordinator.LoadedSession? = null
            try {
                assertEquals(0, creations)
                val id = if (attachExistingHistory) {
                    session.restoreIdentity(null, listOf(checkpoint), 0)
                    session.ensureAttached(source, errors::add)
                } else {
                    session.beginNew(source, errors::add).also { session.history.record(checkpoint) }
                }
                assertTrue(RecoverySessionCodec.isSafeSessionId(id))
                assertEquals(id, session.ensureAttached(source, errors::add))
                session.persist(32L * 1024 * 1024)
                session.listRecent { recent = it }
                session.load(id, { true }) { loaded = it }

                assertEquals(0, creations)
                assertFalse(File(root, id).exists())
                assertNull(recent)
                assertNull(loaded)

                worker.drain()
                assertEquals(1, creations)
                assertNull(recent)
                assertNull(loaded)
                ui.drain()

                assertTrue(errors.isEmpty())
                val summary = requireNotNull(recent).getOrThrow().single()
                assertEquals(id, summary.id)
                assertEquals(1, summary.historyCount)
                val result = requireNotNull(loaded)
                assertEquals(id, result.stored.id)
                assertEquals(0, result.stored.historyCursor)
                assertCheckpointPreserved(checkpoint, result.stored.historyEntries.single())
                val prepared = requireNotNull(result.source)
                assertEquals(2, prepared.bitmap.width)
                assertEquals(2, prepared.bitmap.height)
                assertTrue(File(root, "$id/source.ref").isFile)
                assertFalse(source.isRecycled)
            } finally {
                loaded?.source?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                loaded?.stored?.sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
                source.recycle()
            }
        }
    }

    @Test fun queuedStartupStillEnforcesRetentionAndQuarantinesInvalidSessionsBeforeReadingSavedWork() {
        withSessionRoot { root ->
            val checkpoint = checkpoint()
            val ids = (0..RecoverySessionStore.MAX_SAVED_SESSIONS).map { "startup-session-$it" }
            ids.forEachIndexed { index, id -> writeSession(root, id, id, index + 1L, checkpoint) }
            val oldest = File(root, ids.first())
            File(oldest, "synthetic-source-marker").writeText("synthetic source")
            val mismatch = writeSession(root, "mismatched-folder", "different-payload-id", 100L, checkpoint)
            val corrupt = File(root, "corrupt-session/session.qrs").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val worker = Jobs()
            val ui = Jobs()
            val errors = mutableListOf<Throwable>()
            var creations = 0
            val session = WorkspaceSessionCoordinator(
                createStore = {
                    assertTrue(worker.running)
                    creations++
                    RecoverySessionStore(RuntimeEnvironment.getApplication())
                },
                executor = worker,
                dispatchToUi = ui::dispatch,
                onSaveError = errors::add,
            )
            var recent: Result<List<SessionSummary>>? = null
            var loaded: WorkspaceSessionCoordinator.LoadedSession? = null
            session.listRecent { recent = it }
            session.load(ids.last(), { true }) { loaded = it }

            assertEquals(0, creations)
            assertTrue(oldest.exists())
            assertTrue(mismatch.isFile)
            assertTrue(corrupt.isFile)
            assertEquals(ids.size + 2, root.listFiles().orEmpty().count { File(it, "session.qrs").isFile })

            // Run startup alone so later list/load requests cannot account for cleanup.
            worker.runNext()

            assertEquals(1, creations)
            assertFalse(oldest.exists())
            assertQuarantined(mismatch)
            assertQuarantined(corrupt)
            assertEquals(RecoverySessionStore.MAX_SAVED_SESSIONS,
                root.listFiles().orEmpty().count { File(it, "session.qrs").isFile })
            assertNull(recent)
            assertNull(loaded)

            worker.drain()
            ui.drain()

            assertEquals(1, creations)
            assertTrue(errors.isEmpty())
            assertEquals(ids.drop(1).reversed(), requireNotNull(recent).getOrThrow().map { it.id })
            val restored = requireNotNull(loaded).stored
            assertEquals(ids.last(), restored.id)
            assertEquals(0, restored.historyCursor)
            assertCheckpointPreserved(checkpoint, restored.historyEntries.single())
            assertNull(restored.sourceBitmap)
        }
    }

    @Test fun failedInitializationReportsItsOwnErrorOnTheWorkerAndLaterOperationsCanRetry() {
        withSessionRoot {
            val worker = Jobs()
            val ui = Jobs()
            val saveErrors = mutableListOf<Throwable>()
            val initializationErrors = mutableListOf<Throwable>()
            val failure = IOException("Synthetic unavailable session storage")
            var attempts = 0
            val session = WorkspaceSessionCoordinator(
                createStore = {
                    assertTrue(worker.running)
                    attempts++
                    if (attempts == 1) throw failure
                    RecoverySessionStore(RuntimeEnvironment.getApplication())
                },
                executor = worker,
                dispatchToUi = ui::dispatch,
                onSaveError = saveErrors::add,
                onInitializationError = {
                    assertTrue(worker.running)
                    initializationErrors += it
                },
            )
            assertEquals(0, attempts)
            assertTrue(initializationErrors.isEmpty())

            worker.runNext()

            assertEquals(1, attempts)
            assertEquals(listOf(failure), initializationErrors)
            assertTrue(saveErrors.isEmpty())
            var recent: Result<List<SessionSummary>>? = null
            session.listRecent { recent = it }
            assertEquals(1, attempts)
            assertNull(recent)

            worker.drain()
            assertEquals(2, attempts)
            assertNull(recent)
            ui.drain()

            assertTrue(requireNotNull(recent).getOrThrow().isEmpty())
            assertEquals(listOf(failure), initializationErrors)
            assertTrue(saveErrors.isEmpty())
        }
    }

    private fun checkpoint() = RecoveryHistoryEntry(
        kind = HistoryEventKind.USER_EDIT,
        label = "Synthetic fixed cells",
        snapshot = RecoverySnapshot(null, null, QrMatrix(21).apply {
            set(10, 10, ModuleValue.ONE, 1f, ModuleSource.USER)
            set(11, 10, ModuleValue.ZERO, 1f, ModuleSource.USER)
            set(12, 10, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }, -1),
    )

    private fun assertCheckpointPreserved(expected: RecoveryHistoryEntry, actual: RecoveryHistoryEntry) {
        assertEquals(expected.id, actual.id)
        assertEquals(expected.label, actual.label)
        assertEquals(expected.kind, actual.kind)
        val matrix = requireNotNull(actual.snapshot.workingMatrix)
        for (x in 10..12) assertEquals(expected.snapshot.workingMatrix!![x, 10], matrix[x, 10])
    }

    private fun writeSession(root: File, folderId: String, payloadId: String, updatedAt: Long,
        checkpoint: RecoveryHistoryEntry): File = File(root, "$folderId/session.qrs").apply {
        parentFile!!.mkdirs()
        writeBytes(RecoverySessionCodec.encode(
            RecoverySessionCodec.Payload(payloadId, updatedAt, UiMode.SIMPLE, listOf(checkpoint), 0),
        ))
    }

    private fun assertQuarantined(file: File) {
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("session.qrs.corrupt-") })
    }

    private fun withSessionRoot(test: (File) -> Unit) {
        val root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "qr_sessions")
        root.deleteRecursively()
        root.mkdirs()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
