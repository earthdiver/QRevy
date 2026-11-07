package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySessionHistory
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import io.github.earthdiver.qrrepair.session.SessionSaveQueue
import io.github.earthdiver.qrrepair.session.SessionSummary
import io.github.earthdiver.qrrepair.session.StoredSession
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.UUID
import java.util.concurrent.Executor

/** Owns active session identity and history; storage operations share the caller's FIFO executor. */
internal class WorkspaceSessionCoordinator(
    createStore: () -> RecoverySessionStore,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    onSaveError: (Throwable) -> Unit,
    private val prepareSource: (Bitmap) -> BitmapUtils.Prepared = { BitmapUtils.prepare(it) },
    onInitializationError: (Throwable) -> Unit = onSaveError,
) {
    data class LoadedSession(val stored: StoredSession, val source: BitmapUtils.Prepared?)

    var id: String? = null
        private set
    val history = RecoverySessionHistory(maxEntries = 40)
    // Access only from the FIFO worker, including construction and startup retention checks.
    private val store by lazy(createStore)
    private val saves = SessionSaveQueue(executor,
        save = { id, entries, cursor -> store.save(id, UiMode.SIMPLE, entries, cursor) },
        onError = onSaveError)

    init {
        executor.execute { runCatching { store }.onFailure(onInitializationError) }
    }

    fun restoreIdentity(id: String?, entries: List<RecoveryHistoryEntry>, cursor: Int) {
        val restoredEntries = entries.toList()
        this.id = id
        history.replace(restoredEntries, cursor)
    }

    fun beginNew(source: Bitmap, onSourceError: (Throwable) -> Unit): String {
        val newId = UUID.randomUUID().toString()
        id = newId
        history.clear()
        saveSource(newId, source, onSourceError)
        return newId
    }

    /** Only an explicit save may attach detached work to a new stored session. */
    fun ensureAttached(source: Bitmap, onSourceError: (Throwable) -> Unit): String {
        id?.let { return it }
        val newId = UUID.randomUUID().toString()
        id = newId
        saveSource(newId, source, onSourceError)
        return newId
    }

    private fun saveSource(id: String, source: Bitmap, onError: (Throwable) -> Unit) {
        executor.execute {
            try {
                store.saveSourceBitmap(id, source)
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    fun persist(maxRetainedBytes: Long, onTrimmed: (Int) -> Unit = {}): Int {
        val currentId = id ?: return 0
        if (history.entries.isEmpty()) return 0
        val removed = RecoverySessionCodec.trimHistoryToPayloadLimit(history, currentId, maxRetainedBytes)
        if (removed > 0) onTrimmed(removed)
        saves.enqueue(currentId, history.entries.toList(), history.cursor)
        return removed
    }

    fun delete(id: String, onComplete: (Boolean) -> Unit) {
        // Detach synchronously so later autosaves cannot recreate the deleted session.
        if (this.id == id) this.id = null
        executor.execute {
            val deleted = runCatching { store.delete(id) }.isSuccess
            dispatchToUi { onComplete(deleted) }
        }
    }

    fun deleteAll(onComplete: (Boolean) -> Unit) {
        id = null
        executor.execute {
            val deleted = runCatching { store.deleteAll() }.isSuccess
            dispatchToUi { onComplete(deleted) }
        }
    }

    fun listRecent(onComplete: (Result<List<SessionSummary>>) -> Unit) {
        executor.execute {
            val recent = runCatching { store.listRecent() }
            dispatchToUi { onComplete(recent) }
        }
    }

    /** Transfers only current loaded images to the caller; stale results release every owned bitmap. */
    fun load(id: String, isCurrent: () -> Boolean, onComplete: (LoadedSession?) -> Unit) {
        executor.execute {
            val stored = runCatching { store.load(id) }.getOrNull()
            val bitmap = stored?.sourceBitmap
            val source = bitmap?.let { runCatching { prepareSource(it) }.getOrNull() }
            fun recycleLoadedBitmaps() {
                source?.bitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                bitmap?.takeIf { !it.isRecycled }?.recycle()
            }
            if (!isCurrent()) {
                recycleLoadedBitmaps()
                return@execute
            }
            dispatchToUi {
                if (!isCurrent()) {
                    recycleLoadedBitmaps()
                    return@dispatchToUi
                }
                if (stored == null) {
                    onComplete(null)
                } else {
                    bitmap?.takeIf { it !== source?.bitmap && !it.isRecycled }?.recycle()
                    onComplete(LoadedSession(stored, source))
                }
            }
        }
    }
}
