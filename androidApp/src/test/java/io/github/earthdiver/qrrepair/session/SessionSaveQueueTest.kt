package io.github.earthdiver.qrrepair.session

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SessionSaveQueueTest {
    @Test fun supersededSnapshotsDoNotMoveTheLatestSaveAheadOfSourceWritesOrDeletion() {
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
        assertEquals(listOf("delete:old", "source:new", "save:new:-1"), events)
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
        jobs.removeFirst().run()
        queue.enqueue("next", emptyList(), -1)
        jobs.removeFirst().run()
        assertEquals(listOf(failure), errors)
        assertEquals(listOf("next"), saved)
    }
}
