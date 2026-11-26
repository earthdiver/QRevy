package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class AutomaticScanDiagnosticTest {
    @Test fun timedAndUserStopsKeepTheSameCandidatesButDistinctReasons() {
        val matrix = QrTextMatrixParser.parse(java.io.File("tests/fixtures/priority-before.txt").readText()).matrix
        for (elapsed in listOf(201L, 3_001L)) for (userStop in listOf(false, true)) {
            var now = 0L
            var requested = false
            val budget = AutomaticScanBudget(clockMillis = { now }, stopRequested = { requested })
            val slice = budget.slice(200)
            val outcome = RecoveryEngine().recoverWithDiagnostics(matrix.copy(),
                executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXTENDED, allowAutomaticPrioritySearch = true,
                trustKnownPayloadCount = true, maxFormatMismatches = 6, maxKnownDataEccMismatches = 2,
                listDecodingAdditionalErrors = 1, knownBitErrorLimit = 0,
                listDecodingBudget = ListDecodingBudget(clockMillis = { 0L }),
                stopRequested = slice, stopIsTimeLimit = budget::stopIsTimeLimit,
                discoveryCounter = RecoveryDiscoveryCounter(onChanged = { now = elapsed; requested = userStop }),
                onPriorityProgress = { _, _ -> if (slice()) throw RecoveryCancelledException() })
            assertTrue(outcome.candidates.any { it.text == "QRevy repaired." })
            val reason = if (userStop) SearchIncompleteReason.USER_INTERRUPTED else SearchIncompleteReason.TIME_LIMIT
            val other = if (userStop) SearchIncompleteReason.TIME_LIMIT else SearchIncompleteReason.USER_INTERRUPTED
            assertTrue(outcome.searchDiagnostic.incompleteReasons.contains(reason))
            assertFalse(outcome.searchDiagnostic.incompleteReasons.contains(other))
            assertEquals(SearchCompleteness.UNSEARCHED, outcome.searchDiagnostic.completeness)
            assertFalse(QuickAutoRecovery.canReport(outcome))
            assertTrue(outcome.candidates.all { reason in it.combinationSearch.incompleteReasons })
            val cumulative = RecoverySearchDiagnostic.combine(listOf(outcome.searchDiagnostic,
                RecoverySearchDiagnostic(acceptedCandidate = true, solverCompleteness = SearchCompleteness.COMPLETE)))!!
            assertTrue(cumulative.incompleteReasons.contains(reason))
            assertFalse(cumulative.incompleteReasons.contains(other))
            val listStatuses = outcome.searchDiagnostic.listDecodingExecution!!.stages.values.flatten()
            assertTrue(listStatuses.contains(if (userStop) ListDecodingStatus.INTERRUPTED else ListDecodingStatus.REPAIR_TIME_LIMIT))
            assertFalse(listStatuses.contains(if (userStop) ListDecodingStatus.REPAIR_TIME_LIMIT else ListDecodingStatus.INTERRUPTED))
        }
    }

    @Test fun unlimitedComparisonRecoversTheSameInputWhileNormalDeadlineAndUserStopRemainDistinct() {
        val code = Encoder.encode("diagnostic comparison", ErrorCorrectionLevel.L).matrix
        val size = (code.width + 8) * 4
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val x = i % size / 4 - 4; val y = i / size / 4 - 4
            if (x in 0 until code.width && y in 0 until code.height && code[x, y].toInt() == 1) 0 else 255
        })
        val end = 17.5 + (code.width - 1) * 4
        val saved = PreprocessSettings(version = (code.width - 17) / 4,
            manualQuad = ImageQuad(PointD(17.5,17.5), PointD(end,17.5), PointD(end,end), PointD(17.5,end)),
            automaticThresholds = true, photographicSource = true, lmCorrectionEnabled = false)
        for (unlimited in listOf(false, true)) {
            var now = 0L
            val trace = AutomaticScanTrace { now }
            val budget = AutomaticScanBudget(clockMillis = { now }, timeLimitsEnabled = !unlimited, trace = trace)
            now = 10_000
            val result = QuickAutoRecovery().run(image, saved, budget)
            if (unlimited) {
                assertEquals("diagnostic comparison", result.selected?.text)
                assertTrue(result.diagnosticReport!!.contains("reason=UNIQUE_VERIFIED_CANDIDATE"))
                assertFalse(result.diagnosticReport.contains("diagnostic comparison"))
                assertTrue(result.diagnosticReport.contains("curved=false"))
            } else {
                assertNull(result.selected)
                assertTrue(result.diagnosticReport!!.contains("reason=TOTAL_TIME_LIMIT"))
            }
        }
        val trace = AutomaticScanTrace()
        val stopped = QuickAutoRecovery().run(image, saved,
            AutomaticScanBudget(stopRequested = { true }, timeLimitsEnabled = false, trace = trace))
        assertNull(stopped.selected)
        assertTrue(stopped.diagnosticReport!!.contains("reason=STOP_REQUESTED"))
        assertFalse(saved.lmCorrectionEnabled)
        assertFalse(saved.distortionCorrection)
    }

    @Test fun disablingTimeLimitsAlsoDisablesSlicesButKeepsCancellation() {
        var now = 0L
        var stop = false
        val budget = AutomaticScanBudget(clockMillis = { now }, stopRequested = { stop }, timeLimitsEnabled = false)
        val slice = budget.slice(200)
        now = 1_000_000
        assertFalse(budget.stopped())
        assertFalse(slice())
        stop = true
        assertEquals("STOP_REQUESTED", budget.stopReason())
        assertTrue(slice())
    }

    @Test fun missingGridIsNotReportedAsADecoderFailure() {
        val trace = AutomaticScanTrace()
        val result = QuickAutoRecovery().run(LumaImage(1, 1, intArrayOf(255)), PreprocessSettings(),
            AutomaticScanBudget(trace = trace))
        assertTrue(result.diagnosticReport!!.contains("reason=NO_INITIAL_GRID"))
        assertFalse(result.diagnosticReport.contains("DECODE: START"))
    }
}
