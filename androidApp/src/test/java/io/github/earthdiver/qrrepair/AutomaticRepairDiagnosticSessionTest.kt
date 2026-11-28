package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticRepairDiagnosticSessionTest {
    @Test fun retryObtainsMissingJavaBoundariesAndCachesEvenAnEmptyCompletedResult() {
        for (detected in listOf(emptyList(), listOf(quad))) {
            var calls = 0
            val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings(), "baseline",
                detectFallback = { _, cancellation -> calls++; cancellation.check(); detected })
            repeat(2) {
                session.settingsForRetry(AutomaticScanTrace(), RecoveryCancellation.NONE) { _, boundaries, _, _ ->
                    assertEquals(detected, boundaries)
                    null
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun interruptedJavaFallbackIsRetriedWithoutCachingPartialGeometry() {
        var calls = 0
        var stop = false
        val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings(), "baseline",
            detectFallback = { _, cancellation ->
                calls++
                if (calls == 1) stop = true
                cancellation.check()
                listOf(quad)
            })
        assertThrows(RecoveryCancelledException::class.java) {
            session.settingsForRetry(AutomaticScanTrace(), RecoveryCancellation { stop })
        }
        stop = false
        session.settingsForRetry(AutomaticScanTrace(), RecoveryCancellation { stop }) { _, boundaries, _, _ ->
            assertEquals(listOf(quad), boundaries)
            estimate
        }
        assertEquals(2, calls)
    }

    @Test fun retriesProgressFromTenSecondsToUnlimitedAndReuseTheCompletedGrid() {
        var now = 0L
        var estimates = 0
        val original = PreprocessSettings(lmCorrectionEnabled = false, blackMax = 20, whiteMin = 220)
        val session = AutomaticRepairDiagnosticSession(image, original, "baseline", listOf(quad))
        repeat(2) { pass ->
            val extended = session.extend(AutomaticScanTrace { now }, { false }, { now },
                estimateGrid = { _, boundaries, _, _ ->
                    estimates++
                    assertEquals(listOf(quad), boundaries)
                    now += 4000
                    estimate
                }, recover = { input, saved, budget ->
                    assertSame(image, input)
                    assertEquals(quad, saved.manualQuad)
                    assertFalse(saved.lmCorrectionEnabled)
                    assertEquals(20, saved.blackMax)
                    if (pass == 0) {
                        assertEquals(6000L, budget.remainingMillis)
                        now += 6000
                        assertTrue(budget.stopped())
                    } else {
                        assertEquals(Long.MAX_VALUE, budget.remainingMillis)
                        val slice = budget.slice(200)
                        now += 100_000
                        assertFalse(budget.stopped())
                        assertFalse(slice())
                        assertFalse(budget.curveGenerationStopped())
                        assertEquals(Long.MAX_VALUE, budget.listDecodingBudget().limitMillis)
                    }
                    QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()),
                        failureReason = if (pass == 0) QuickAutoFailureReason.TIME_LIMIT else QuickAutoFailureReason.SEARCH_LIMIT)
                })
            assertEquals(if (pass == 0) QuickAutoFailureReason.TIME_LIMIT else QuickAutoFailureReason.SEARCH_LIMIT,
                extended.result.failureReason)
            assertEquals(if (pass == 0) QuickAutoRetryMode.UNLIMITED else null, session.nextRetry)
            assertEquals(quad, extended.settings.manualQuad)
        }
        assertEquals(1, estimates)
        assertEquals(original, session.settings)
        assertTrue(session.report().contains("EXTENDED RETRY (10 seconds)"))
        assertTrue(session.report().contains("AUTOMATIC RETRY (no time limit)"))
        assertThrows(IllegalStateException::class.java) { session.extend(AutomaticScanTrace(), { false }) }
    }

    @Test fun unlimitedRetryStillHonorsCancellationDuringGridEstimation() {
        val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings(), "baseline")
        session.extend(AutomaticScanTrace(), { false }, estimateGrid = { _, _, _, _ -> null },
            recover = { _, _, _ -> QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()),
                failureReason = QuickAutoFailureReason.TIME_LIMIT) })
        var stop = false
        val result = session.extend(AutomaticScanTrace(), { stop }, estimateGrid = { _, _, cancellation, _ ->
            stop = true
            cancellation.check()
            error("Cancelled estimate must stop")
        }, recover = { _, _, _ -> error("Do not decode after cancellation") })
        assertEquals(QuickAutoFailureReason.INTERRUPTED, result.result.failureReason)
        assertNull(session.nextRetry)
        assertNull(result.settings.manualQuad)
    }

    @Test fun extensionDeadlineAndUserStopDuringGridEstimationAreDistinguished() {
        for (userStop in listOf(false, true)) {
            var now = 0L
            var stopped = false
            val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings(), "baseline")
            val result = session.extend(AutomaticScanTrace { now }, { stopped }, { now },
                estimateGrid = { _, _, cancellation, _ ->
                    now = 10000
                    stopped = userStop
                    cancellation.check()
                    error("The bounded estimate must stop")
                }, recover = { _, _, _ -> error("No decoding after the deadline") })
            assertEquals(if (userStop) QuickAutoFailureReason.INTERRUPTED else QuickAutoFailureReason.TIME_LIMIT,
                result.result.failureReason)
            assertNull(result.settings.manualQuad)
        }
    }

    private val image = LumaImage(200, 200, IntArray(40000) { 255 })
    private val quad = ImageQuad(PointD(10.0, 10.0), PointD(190.0, 10.0),
        PointD(190.0, 190.0), PointD(10.0, 190.0))
    private val estimate = InitialGridEstimate(3, quad, InitialGridSource.PERIODICITY)

    @Test fun missingGridRetriesOriginalBoundariesWithoutReplacingBaselineSettings() {
        val original = PreprocessSettings(lmCorrectionEnabled = false, blackMax = 20, whiteMin = 220)
        val boundaries = mutableListOf(quad)
        val session = AutomaticRepairDiagnosticSession(image, original, "baseline", boundaries)
        boundaries.clear()
        var calls = 0
        val trace = AutomaticScanTrace()
        val retry = session.settingsForRetry(trace, RecoveryCancellation.NONE) { input, observed, cancellation, observer ->
            calls++
            assertSame(image, input)
            assertEquals(listOf(quad), observed)
            assertFalse(cancellation.isCancelled())
            assertNotNull(observer)
            estimate
        }
        assertEquals(1, calls)
        assertEquals(3, retry.version)
        assertEquals(quad, retry.manualQuad)
        assertEquals(20, retry.blackMax)
        assertEquals(220, retry.whiteMin)
        assertFalse(retry.lmCorrectionEnabled)
        assertEquals(original, session.settings)
        assertNull(session.settings.manualQuad)
        assertTrue(trace.report().contains("timeLimits=false"))
        assertTrue(trace.report().contains("INITIAL_GRID:"))
    }

    @Test fun existingGridIsReusedWithoutEstimation() {
        val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings().withInitialGrid(estimate), "baseline")
        val retry = session.settingsForRetry(AutomaticScanTrace(), RecoveryCancellation.NONE) { _, _, _, _ ->
            error("Completed initial geometry must be reused")
        }
        assertSame(session.settings, retry)
    }

    @Test fun cancellationDuringGridRetryPropagatesAndLeavesBaselineUntouched() {
        val session = AutomaticRepairDiagnosticSession(image, PreprocessSettings(), "baseline")
        val trace = AutomaticScanTrace()
        var stop = false
        assertThrows(RecoveryCancelledException::class.java) {
            session.settingsForRetry(trace, RecoveryCancellation { stop }) { _, _, cancellation, _ ->
                stop = true
                cancellation.check()
                estimate
            }
        }
        assertNull(session.settings.manualQuad)
        assertTrue(trace.report().contains("stop=CANCELLED"))
        assertTrue(trace.report().endsWith("PREPARATION: END"))
    }
}
