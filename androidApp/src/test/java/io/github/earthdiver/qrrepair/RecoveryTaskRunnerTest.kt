package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.RecoveryCancellation
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class RecoveryTaskRunnerTest {
    private class Harness {
        var generation = 1
        val worker = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val runner = RecoveryTaskRunner(Executor { worker.addLast(it) }, { ui.addLast(it) }, { generation })

        fun submit(task: (RecoveryCancellation) -> String = { "candidate" }) {
            runner.execute(generation, task,
                onResult = { events += "result:$it" },
                onError = { events += "error:${it.message}" },
                onFinished = { events += "finished" })
        }

        fun runWorker() = worker.removeFirst().run()
        fun runUi() = ui.removeFirst().invoke()
    }

    @Test fun successfulWorkCleansUpOnUiBeforePublishingItsResult() {
        val h = Harness()
        h.submit { cancellation ->
            assertFalse(cancellation.isCancelled())
            "retained candidates"
        }
        assertTrue(h.events.isEmpty())
        h.runWorker()
        assertTrue(h.events.isEmpty())
        h.runUi()
        assertEquals(listOf("finished", "result:retained candidates"), h.events)
        assertTrue(h.ui.isEmpty())
    }

    @Test fun supersededQueuedWorkSkipsCalculationButStillCleansUp() {
        val h = Harness()
        h.submit { throw AssertionError("Obsolete work must not start") }
        h.generation++
        h.runWorker()
        assertTrue(h.events.isEmpty())
        h.runUi()
        assertEquals(listOf("finished"), h.events)
    }

    @Test fun runningWorkCanCancelCooperativelyWithoutReportingFailure() {
        val h = Harness()
        h.submit { cancellation ->
            h.generation++
            cancellation.check()
            throw AssertionError("Cancelled calculation continued")
        }
        h.runWorker()
        h.runUi()
        assertEquals(listOf("finished"), h.events)
        h.submit()
        h.runWorker()
        h.runUi()
        assertEquals(listOf("finished", "finished", "result:candidate"), h.events)
    }

    @Test fun resultQueuedBeforeWorkChangesCannotReplaceTheNewResult() {
        val h = Harness()
        h.submit { "old" }
        h.runWorker()
        h.generation++
        h.submit { "new" }
        h.runWorker()
        h.runUi()
        h.runUi()
        assertEquals(listOf("finished", "finished", "result:new"), h.events)
    }

    @Test fun failureQueuedBeforeWorkChangesCannotReplaceTheNewStatus() {
        val h = Harness()
        h.submit { throw IllegalStateException("old failure") }
        h.runWorker()
        h.generation++
        h.runUi()
        assertEquals(listOf("finished"), h.events)
    }

    @Test fun currentFailureIsReportedOnUiAfterCleanup() {
        val h = Harness()
        h.submit { throw IllegalStateException("failed") }
        h.runWorker()
        assertTrue(h.events.isEmpty())
        h.runUi()
        assertEquals(listOf("finished", "error:failed"), h.events)
    }

    @Test fun unhandledWorkerFailureStillSchedulesCleanup() {
        val h = Harness()
        val error = IllegalStateException("failed")
        h.runner.execute<String>(h.generation, { throw error },
            onResult = { fail("Unexpected result") }, onFinished = { h.events += "finished" })
        assertSame(error, assertThrows(IllegalStateException::class.java) { h.runWorker() })
        assertTrue(h.events.isEmpty())
        h.runUi()
        assertEquals(listOf("finished"), h.events)
    }

    @Test fun interruptedWorkerSkipsCalculationWithoutConsumingInterruptFlag() {
        val h = Harness()
        h.submit { throw AssertionError("Interrupted work must not start") }
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            h.runWorker()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        h.runUi()
        assertEquals(listOf("finished"), h.events)
    }
}
