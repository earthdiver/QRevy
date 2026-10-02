package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Coalesces snapshots per session on the caller's FIFO executor for image writes and deletion. */
internal class SessionSaveQueue(
    private val executor: Executor,
    private val save: (String, List<RecoveryHistoryEntry>, Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private class Request(
        val id: String, val entries: List<RecoveryHistoryEntry>, val cursor: Int, val generation: Long,
    )
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<String, Request>()

    fun enqueue(id: String, entries: List<RecoveryHistoryEntry>, cursor: Int) {
        val token = generation.incrementAndGet()
        pending[id] = Request(id, entries, cursor, token)
        // Jobs capture only their position, so superseded jobs never pin old matrix snapshots.
        executor.execute {
            val request = pending[id] ?: return@execute
            if (request.generation != token || !pending.remove(id, request)) return@execute
            try {
                save(request.id, request.entries, request.cursor)
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }
}
