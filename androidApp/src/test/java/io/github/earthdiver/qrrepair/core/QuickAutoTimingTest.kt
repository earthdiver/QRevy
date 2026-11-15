package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class QuickAutoTimingTest {
    @Test fun preparationAndRepairShareOnlyOneStartupBonus() {
        for ((preparationMs, repairMs) in listOf(0L to 4500L, 2900L to 4500L,
            3000L to 4500L, 4000L to 3500L, 4500L to 3000L, 7600L to 0L)) {
            var now = 10_000L
            val preparation = AutomaticScanBudget.forImport(true, clockMillis = { now })
            assertEquals(4500L, preparation.remainingMillis)
            now += preparationMs
            val repair = preparation.repairAfterPreparation()
            assertTrue(repair.firstRun)
            assertEquals(repairMs, repair.remainingMillis)
            if (repairMs > 0) {
                now += repairMs - 1
                assertFalse(repair.stopped())
                now++
            }
            assertEquals("TOTAL_TIME_LIMIT", repair.stopReason())
        }
    }

    @Test fun subsequentImportsKeepSeparateThreeSecondAllowances() {
        var now = 0L
        val preparation = AutomaticScanBudget.forImport(false, clockMillis = { now })
        now = 3000
        assertTrue(preparation.stopped())
        val repair = preparation.repairAfterPreparation()
        assertFalse(repair.firstRun)
        assertEquals(3000L, repair.remainingMillis)
    }

    @Test fun initialSlicesDoubleButCannotOutliveTheirOverallDeadline() {
        for (first in listOf(false, true)) for (sliceMs in listOf(200L, 250L, 450L, 600L)) {
            var now = 0L
            val budget = AutomaticScanBudget.forImport(first, clockMillis = { now })
            val stop = budget.slice(sliceMs)
            now = sliceMs * if (first) 2 else 1
            now--
            assertFalse(stop())
            now++
            assertTrue(stop())
            now = if (first) 4400 else 2900
            val late = budget.slice(sliceMs)
            now += 100
            assertTrue(late())
        }
    }

    @Test fun cumulativeListTimeDoublesOnlyForTheInitialOperation() {
        for (first in listOf(false, true)) {
            var now = 0L
            val budget = AutomaticScanBudget.forImport(first, clockMillis = { now })
            val lists = budget.listDecodingBudget()
            val limit = if (first) 1400L else 700L
            assertThrows(ListDecodingTimeLimitException::class.java) {
                lists.measure {
                    now = limit - 1
                    lists.check()
                    now++
                    lists.check()
                }
            }
            assertEquals(limit, lists.elapsedMillis)
            assertTrue(lists.exhausted)
            assertFalse(budget.stopped())
        }
    }

    @Test fun curveGenerationLeavesNineHundredMillisecondsForDecoding() {
        for (first in listOf(false, true)) {
            var now = 0L
            val budget = AutomaticScanBudget.forImport(first, clockMillis = { now })
            now = (if (first) 4500L else 3000L) - 901
            assertFalse(budget.curveGenerationStopped())
            now++
            assertTrue(budget.curveGenerationStopped())
            assertFalse(budget.stopped())
            assertEquals(900L, budget.remainingMillis)
            assertFalse(budget.slice(200)())
        }
    }

    @Test fun userCancellationAndUnlimitedRetriesKeepTheirMeaning() {
        var now = 0L
        var stopped = false
        val preparation = AutomaticScanBudget.forImport(true, stopRequested = { stopped }, clockMillis = { now })
        val repair = preparation.repairAfterPreparation()
        val unlimited = AutomaticScanBudget(timeLimitsEnabled = false, clockMillis = { now }, stopRequested = { stopped })
        val slice = unlimited.slice(200)
        now = 100_000
        assertFalse(unlimited.stopped())
        assertFalse(unlimited.curveGenerationStopped())
        assertFalse(slice())
        stopped = true
        for (budget in listOf(preparation, repair, unlimited)) {
            assertEquals("STOP_REQUESTED", budget.stopReason())
            assertTrue(budget.curveGenerationStopped())
        }
    }
}
