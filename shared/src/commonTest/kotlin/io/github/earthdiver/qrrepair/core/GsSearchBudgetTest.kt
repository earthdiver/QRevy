package io.github.earthdiver.qrrepair.core

import kotlin.test.*

class GsSearchBudgetTest {
    @Test fun sharesElapsedTimeAcrossPassesWithoutChargingOrdinaryWork() {
        var clock = 100L
        val budget = GsSearchBudget(300) { clock }
        budget.measure { clock += 80; budget.check() }
        clock += 10_000
        assertEquals(80, budget.elapsedMillis)
        budget.measure {
            clock += 100
            budget.measure { clock += 100; budget.check() }
        }
        assertEquals(280, budget.elapsedMillis)
        assertFailsWith<GsTimeLimitException> { budget.measure { clock += 20; budget.check() } }
        assertEquals(300, budget.elapsedMillis)
        assertFailsWith<GsTimeLimitException> { budget.measure { fail("Exhausted budget must not restart") } }
    }

    @Test fun completionDoesNotWaitForDeadlineAndCancellationEscapes() {
        var clock = 0L
        val budget = GsSearchBudget { clock }
        assertEquals(42, budget.measure { clock += 1; 42 })
        assertFalse(budget.exhausted)
        assertFailsWith<RecoveryCancelledException> { budget.measure { clock += 2; throw RecoveryCancelledException() } }
        clock += 10_000
        assertEquals(3, budget.elapsedMillis)
    }

    @Test fun aProgressCallbackCannotLeaveTheClockRunningAfterCancellation() {
        var clock = 0L
        val budget = GsSearchBudget(onProgress = { _, _ ->
            clock += 3
            throw RecoveryCancelledException()
        }, clockMillis = { clock })
        assertFailsWith<RecoveryCancelledException> { budget.measure { fail("Already cancelled") } }
        clock += 1000
        assertEquals(3, budget.elapsedMillis)
    }
}
