package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SessionSaveQueueTest {
    private class PendingExecutor : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun runAll() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }

    private fun entry(label: String, value: ModuleValue = ModuleValue.ZERO) = RecoveryHistoryEntry(
        kind = HistoryEventKind.USER_EDIT, label = label,
        snapshot = RecoverySnapshot(null, null, null, -1,
            PreprocessSettings(preRecoveryEdits = mapOf(Coord(10, 10) to value))),
    )

    @Test fun newSessionDoesNotDiscardPreviousSessionsLastEditsOrSelectedCheckpoint() {
        val executor = PendingExecutor()
        val saved = mutableMapOf<String, Pair<List<RecoveryHistoryEntry>, Int>>()
        val queue = SessionSaveQueue(executor,
            save = { id, entries, cursor -> saved[id] = entries to cursor },
            onError = { throw AssertionError(it) })
        val first = listOf(entry("First edit"), entry("Last fixed edit", ModuleValue.ONE))
        val second = listOf(entry("New work"))
        queue.enqueue("first", first, 1)
        queue.enqueue("second", second, 0)
        executor.runAll()
        assertEquals(first to 1, saved["first"])
        assertEquals(second to 0, saved["second"])
    }

    @Test fun onlyTheLatestPendingSnapshotOfTheSameSessionIsSaved() {
        val executor = PendingExecutor()
        val saved = mutableListOf<Pair<List<RecoveryHistoryEntry>, Int>>()
        val queue = SessionSaveQueue(executor,
            save = { _, entries, cursor -> saved += entries to cursor },
            onError = { throw AssertionError(it) })
        val first = entry("Initial edit")
        val latest = listOf(first, entry("Latest edit", ModuleValue.ONE))
        queue.enqueue("work", listOf(first), 0)
        queue.enqueue("work", latest, 0)
        queue.enqueue("work", latest, 1)
        executor.runAll()
        assertEquals(listOf(latest to 1), saved)
    }

    @Test fun switchingBackToAnEarlierSessionKeepsBothSessionsLatestSnapshots() {
        val executor = PendingExecutor()
        val saved = mutableListOf<Triple<String, List<RecoveryHistoryEntry>, Int>>()
        val queue = SessionSaveQueue(executor,
            save = { id, entries, cursor -> saved += Triple(id, entries, cursor) },
            onError = { throw AssertionError(it) })
        val first = entry("First edit")
        val second = listOf(entry("Second session"))
        val latest = listOf(first, entry("Resumed first session", ModuleValue.ONE))
        queue.enqueue("first", listOf(first), 0)
        queue.enqueue("second", second, 0)
        queue.enqueue("first", latest, 1)
        executor.runAll()
        assertEquals(listOf(Triple("second", second, 0), Triple("first", latest, 1)), saved)
    }

    @Test fun latestSnapshotWaitsForTheSourceWriteAtItsOwnQueuePosition() {
        val executor = PendingExecutor()
        val events = mutableListOf<String>()
        val queue = SessionSaveQueue(executor,
            save = { id, _, cursor -> events += "save:$id:$cursor" },
            onError = { throw AssertionError(it) })
        val initial = entry("Initial edit")
        queue.enqueue("first", listOf(initial), 0)
        executor.execute { events += "source:first" }
        queue.enqueue("first", listOf(initial, entry("Latest edit", ModuleValue.ONE)), 1)
        executor.execute { events += "source:second" }
        queue.enqueue("second", listOf(entry("New work")), 0)
        executor.runAll()
        assertEquals(listOf("source:first", "save:first:1", "source:second", "save:second:0"), events)
    }

    @Test fun savesAndDeletionFollowQueueOrderWithoutRecreatingDeletedWork() {
        val jobs = ArrayDeque<Runnable>()
        val executor = Executor { jobs.addLast(it) }
        val events = mutableListOf<String>()
        val queue = SessionSaveQueue(executor,
            save = { id, _, cursor -> events += "save:$id:$cursor" },
            onError = { throw AssertionError(it) })
        queue.enqueue("old", emptyList(), -1)
        executor.execute { events += "delete:old" }
        executor.execute { events += "source:new" }
        queue.enqueue("new", emptyList(), -1)
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        assertEquals(listOf("save:old:-1", "delete:old", "source:new", "save:new:-1"), events)
    }

    @Test fun failedSaveIsReportedAndDoesNotPreventTheNextSnapshotFromSaving() {
        val jobs = ArrayDeque<Runnable>()
        val failure = IllegalStateException("unavailable storage")
        val errors = mutableListOf<Throwable>()
        val saved = mutableListOf<String>()
        val queue = SessionSaveQueue(Executor { jobs.addLast(it) },
            save = { id, _, _ -> if (id == "failed") throw failure else saved.add(id) },
            onError = errors::add)
        queue.enqueue("failed", emptyList(), -1)
        queue.enqueue("next", emptyList(), -1)
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        assertEquals(listOf(failure), errors)
        assertEquals(listOf("next"), saved)
    }

    @Test fun deletingAllWorkStaysAfterPendingSavesAndBeforeNewWork() {
        val executor = PendingExecutor()
        val stored = mutableSetOf<String>()
        val queue = SessionSaveQueue(executor,
            save = { id, _, _ -> stored.add(id) },
            onError = { throw AssertionError(it) })
        queue.enqueue("first", emptyList(), -1)
        queue.enqueue("second", emptyList(), -1)
        executor.execute { stored.clear() }
        queue.enqueue("new", emptyList(), -1)
        executor.runAll()
        assertEquals(setOf("new"), stored)
    }

    @Test fun editQueuedDuringSavingIsSavedAfterTheRunningSnapshot() {
        val executor = PendingExecutor()
        val saved = mutableListOf<Int>()
        val history = listOf(entry("Initial edit"), entry("Next edit", ModuleValue.ONE), entry("Final edit"))
        lateinit var queue: SessionSaveQueue
        queue = SessionSaveQueue(executor,
            save = { id, _, cursor ->
                saved += cursor
                if (cursor == 0) queue.enqueue(id, history, 1)
            },
            onError = { throw AssertionError(it) })
        queue.enqueue("work", history, 0)
        executor.runAll()
        queue.enqueue("work", history, 2)
        executor.runAll()
        assertEquals(listOf(0, 1, 2), saved)
    }
}
