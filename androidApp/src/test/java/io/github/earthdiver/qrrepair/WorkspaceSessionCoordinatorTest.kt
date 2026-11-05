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
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceSessionCoordinatorTest {
    private class Jobs : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        fun dispatch(action: () -> Unit) = execute(Runnable { action() })
    }

    @Test fun queuedSavesCannotResurrectDeletedWorkAndExplicitSavePreservesItsHistoryUnderANewId() {
        for (deleteAll in listOf(false, true)) withStore { store, source ->
            val worker = Jobs()
            val ui = Jobs()
            val errors = mutableListOf<Throwable>()
            val session = WorkspaceSessionCoordinator(store, worker, ui::dispatch, errors::add)
            val oldId = session.beginNew(source, errors::add)
            val checkpoint = checkpoint()
            session.history.record(checkpoint)
            session.persist(32L * 1024 * 1024)
            var deleted: Boolean? = null
            if (deleteAll) session.deleteAll { deleted = it } else session.delete(oldId) { deleted = it }
            assertNull(session.id)
            session.persist(32L * 1024 * 1024)
            worker.drain()
            ui.drain()
            assertEquals(true, deleted)
            assertNull(store.load(oldId))
            assertTrue(store.listRecent().isEmpty())
            assertEquals(checkpoint.id, session.history.current!!.id)

            val newId = session.ensureAttached(source, errors::add)
            assertNotEquals(oldId, newId)
            assertEquals(newId, session.ensureAttached(source, errors::add))
            session.persist(32L * 1024 * 1024)
            worker.drain()
            ui.drain()
            assertTrue(errors.isEmpty())
            assertNull(store.load(oldId))
            val saved = requireNotNull(store.load(newId))
            try {
                assertNotNull(saved.sourceBitmap)
                assertEquals(checkpoint.id, saved.historyEntries.single().id)
                assertEquals(0, saved.historyCursor)
                val cell = saved.historyEntries.single().snapshot.workingMatrix!![10, 10]
                assertEquals(ModuleValue.ONE, cell.value)
                assertEquals(ModuleSource.USER, cell.source)
            } finally { saved.sourceBitmap?.recycle() }
        }
    }

    @Test fun supersededLoadsReleaseOriginalAndPreparedImagesBeforeOrAfterUiDispatch() {
        for (supersededDuringPreparation in listOf(false, true)) withStore { store, source ->
            val id = "superseded-load"
            store.saveSourceBitmap(id, source)
            store.save(id, UiMode.SIMPLE, listOf(checkpoint()), 0)
            val worker = Jobs()
            val ui = Jobs()
            var current = true
            val originals = mutableListOf<Bitmap>()
            val prepared = mutableListOf<Bitmap>()
            var deliveries = 0
            val session = WorkspaceSessionCoordinator(store, worker, ui::dispatch,
                onSaveError = { throw AssertionError(it) },
                prepareSource = { original ->
                    originals += original
                    val image = BitmapUtils.prepare(Bitmap.createScaledBitmap(original, 4, 4, false))
                    prepared += image.bitmap
                    if (supersededDuringPreparation) current = false
                    image
                })
            try {
                session.load(id, { current }) { deliveries++ }
                worker.drain()
                current = false
                ui.drain()
                assertEquals(0, deliveries)
                assertTrue(originals.single().isRecycled)
                assertTrue(prepared.single().isRecycled)
                assertFalse(source.isRecycled)
            } finally {
                (originals + prepared).forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    @Test fun unavailableImagesKeepValidHistoryAndLoadingDoesNotReplaceTheActiveIdentity() {
        for (preparationFails in listOf(false, true)) withStore { store, source ->
            val id = "history-without-image"
            val checkpoint = checkpoint()
            store.save(id, UiMode.SIMPLE, listOf(checkpoint), 0)
            if (preparationFails) store.saveSourceBitmap(id, source)
            val worker = Jobs()
            val ui = Jobs()
            var decodedImage: Bitmap? = null
            var loaded: WorkspaceSessionCoordinator.LoadedSession? = null
            val session = WorkspaceSessionCoordinator(store, worker, ui::dispatch,
                onSaveError = { throw AssertionError(it) },
                prepareSource = { image ->
                    decodedImage = image
                    throw IllegalStateException("Synthetic image preparation failure")
                })
            session.restoreIdentity("active-work", emptyList(), -1)
            try {
                session.load(id, { true }) { loaded = it }
                worker.drain()
                ui.drain()
                val result = requireNotNull(loaded)
                assertNull(result.source)
                assertEquals(checkpoint.id, result.stored.historyEntries.single().id)
                assertEquals("active-work", session.id)
                if (preparationFails) assertTrue(requireNotNull(decodedImage).isRecycled)
                else assertNull(decodedImage)
                session.restoreIdentity(result.stored.id, result.stored.historyEntries, result.stored.historyCursor)
                assertEquals(id, session.id)
                assertEquals(checkpoint.id, session.history.current!!.id)
            } finally { decodedImage?.takeIf { !it.isRecycled }?.recycle() }
        }
    }

    private fun checkpoint() = RecoveryHistoryEntry(
        kind = HistoryEventKind.USER_EDIT,
        label = "Synthetic fixed cell",
        snapshot = RecoverySnapshot(null, null, QrMatrix(21).apply {
            set(10, 10, ModuleValue.ONE, 1f, ModuleSource.USER)
        }, -1),
    )

    private fun withStore(test: (RecoverySessionStore, Bitmap) -> Unit) {
        val store = RecoverySessionStore(RuntimeEnvironment.getApplication())
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        store.deleteAll()
        try { test(store, source) } finally {
            store.deleteAll()
            if (!source.isRecycled) source.recycle()
        }
    }
}
