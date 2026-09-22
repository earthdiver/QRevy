package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Coalesces pending snapshots while sharing FIFO ordering with image writes and deletion. */
internal class SessionSaveQueue(
    private val executor: Executor,
    private val save: (String, List<RecoveryHistoryEntry>, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private data class Request(
        val id: String, val entries: List<RecoveryHistoryEntry>, val cursor: Int, val generation: Int,
    )
    private val generation = AtomicInteger()
    private val pending = AtomicReference<Request?>(null)

    fun enqueue(id: String, entries: List<RecoveryHistoryEntry>, cursor: Int) {
        val token = generation.incrementAndGet()
        pending.set(Request(id, entries, cursor, token))
        // Jobs capture only their position, so superseded jobs never pin old matrix snapshots.
        executor.execute {
            val request = pending.get() ?: return@execute
            if (token != generation.get() || request.generation != token ||
                !pending.compareAndSet(request, null)) return@execute
            try {
                save(request.id, request.entries, request.cursor)
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }
}
