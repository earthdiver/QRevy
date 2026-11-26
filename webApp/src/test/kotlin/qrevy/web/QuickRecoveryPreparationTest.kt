package qrevy.web

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Test
import kotlin.test.*

class QuickRecoveryPreparationTest {
    private val blank = LumaImage(1, 1, intArrayOf(255))
    private val noGrid = PreprocessSettings(photographicSource = true)

    @Test fun preparationTimeoutSurvivesTheFreshRepairAllowanceWithOrWithoutTracing() {
        for (firstRun in listOf(false, true)) for (diagnostics in listOf(false, true)) {
            var now = 0L
            val trace = if (diagnostics) AutomaticScanTrace { now } else null
            val budget = AutomaticScanBudget.forImport(firstRun, clockMillis = { now }, trace = trace)
            now = if (firstRun) 4500 else 3000
            assertTrue(budget.stopped())
            assertFalse(budget.repairAfterPreparation().stopped())

            val result = QuickRecovery.runPreparedImage(blank, noGrid, budget, separateRepairBudget = true)
            assertEquals(QuickAutoFailureReason.TIME_LIMIT, result.failureReason)
            assertNull(result.selected)
            if (diagnostics) assertContains(result.diagnosticReport!!, "IMPORT_FINISH: failure=TIME_LIMIT stage=INITIAL_GRID")
            else assertNull(result.diagnosticReport)
        }
    }

    @Test fun tenSecondPreparationTimeoutRemainsATimeLimit() {
        var now = 0L
        val budget = AutomaticScanBudget(limitMillis = 10_000, clockMillis = { now })
        now = 10_000
        val result = QuickRecovery.runPreparedImage(blank, noGrid, budget, separateRepairBudget = false)
        assertEquals(QuickAutoFailureReason.TIME_LIMIT, result.failureReason)
        assertNull(result.selected)
    }

    @Test fun completedPreparationWithoutAGridIsNotATimeout() {
        var now = 0L
        val budget = AutomaticScanBudget.forImport(false, clockMillis = { now })
        now = 2999
        val result = QuickRecovery.runPreparedImage(blank, noGrid, budget, separateRepairBudget = true)
        assertEquals(QuickAutoFailureReason.NO_INITIAL_GRID, result.failureReason)
    }

    @Test fun unlimitedPreparationDoesNotBecomeATimeout() {
        var now = 0L
        val budget = AutomaticScanBudget(clockMillis = { now }, timeLimitsEnabled = false)
        now = 100_000
        val result = QuickRecovery.runPreparedImage(blank, noGrid, budget, separateRepairBudget = false)
        assertEquals(QuickAutoFailureReason.NO_INITIAL_GRID, result.failureReason)
    }

    @Test fun userStopTakesPrecedenceOverPreparationTimeout() {
        for (separate in listOf(false, true)) {
            var now = 0L
            var stop = false
            val budget = AutomaticScanBudget(clockMillis = { now }, stopRequested = { stop })
            now = 10_000
            stop = true
            val result = QuickRecovery.runPreparedImage(blank, noGrid, budget, separateRepairBudget = separate)
            assertEquals(QuickAutoFailureReason.INTERRUPTED, result.failureReason)
            assertNull(result.selected)
        }
    }

    @Test fun anAvailableGridCanStillRecoverWithTheFreshRepairAllowance() {
        val code = Encoder.encode("prepared grid", ErrorCorrectionLevel.L).matrix
        val size = (code.width + 8) * 4
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val x = i % size / 4 - 4
            val y = i / size / 4 - 4
            if (x in 0 until code.width && y in 0 until code.height && code[x, y].toInt() == 1) 0 else 255
        })
        val end = 17.5 + (code.width - 1) * 4
        val settings = noGrid.copy(version = (code.width - 17) / 4,
            manualQuad = ImageQuad(PointD(17.5,17.5), PointD(end,17.5), PointD(end,end), PointD(17.5,end)),
            automaticThresholds = true, lmCorrectionEnabled = false)
        var now = 0L
        val budget = AutomaticScanBudget.forImport(false, clockMillis = { now })
        now = 3000
        val result = QuickRecovery.runPreparedImage(image, settings, budget, separateRepairBudget = true)
        assertEquals("prepared grid", result.selected?.text)
        assertNull(result.failureReason)
    }
}
