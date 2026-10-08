package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class ListDecodingBudgetTest {
    @Test fun aDeferredFirstPassLeavesTheCumulativeBudgetAvailable() {
        var clock = 0L
        var idleCount = 0
        val budget = ListDecodingBudget(300, onIdle = { idleCount++ }) { clock }
        assertFailsWith<ListDecodingSliceLimitException> {
            budget.measureSlice(30) { clock += 30; budget.check() }
        }
        assertFalse(budget.exhausted)
        assertEquals(1, idleCount)
        clock += 10_000
        budget.measure { clock += 20; budget.check() }
        assertEquals(50, budget.elapsedMillis)
        assertEquals(2, idleCount)
    }

    @Test fun sharesElapsedTimeAcrossPassesWithoutChargingOrdinaryWork() {
        var clock = 100L
        var idleCount = 0
        val budget = ListDecodingBudget(300, onIdle = { idleCount++ }) { clock }
        budget.measure { clock += 80; budget.check() }
        clock += 10_000
        assertEquals(80, budget.elapsedMillis)
        budget.measure {
            clock += 100
            budget.measure { clock += 100; budget.check() }
        }
        assertEquals(280, budget.elapsedMillis)
        assertEquals(2, idleCount)
        assertFailsWith<ListDecodingTimeLimitException> { budget.measure { clock += 20; budget.check() } }
        assertEquals(300, budget.elapsedMillis)
        assertEquals(3, idleCount)
        assertFailsWith<ListDecodingTimeLimitException> { budget.measure { fail("Exhausted budget must not restart") } }
        assertEquals(3, idleCount)
    }

    @Test fun completionDoesNotWaitForDeadlineAndCancellationEscapes() {
        var clock = 0L
        val budget = ListDecodingBudget { clock }
        assertEquals(42, budget.measure { clock += 1; 42 })
        assertFalse(budget.exhausted)
        assertFailsWith<RecoveryCancelledException> { budget.measure { clock += 2; throw RecoveryCancelledException() } }
        clock += 10_000
        assertEquals(3, budget.elapsedMillis)
    }

    @Test fun aProgressCallbackCannotLeaveTheClockRunningAfterCancellation() {
        var clock = 0L
        val budget = ListDecodingBudget(onProgress = { _, _ ->
            clock += 3
            throw RecoveryCancelledException()
        }, clockMillis = { clock })
        assertFailsWith<RecoveryCancelledException> { budget.measure { fail("Already cancelled") } }
        clock += 1000
        assertEquals(3, budget.elapsedMillis)
    }
}
