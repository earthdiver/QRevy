package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecoveryCancellationIntegrationTest {
    @Test fun geometryFallbackDoesNotSwallowCancellation() {
        var cancelled = false
        val settings = PreprocessSettings(searchQuadNeighborhood = true, manualQuad = ImageQuad(
            PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 20.0), PointD(0.0, 20.0)))
        var progressCalls = 0
        assertThrows(RecoveryCancelledException::class.java) {
            AutoRecoveryEngine().run(LumaImage(21, 21, IntArray(441) { 255 }),
                profile = SamplingProfile(settings, false), cancellation = RecoveryCancellation { cancelled },
                onProgress = { _, _, _ -> progressCalls++; cancelled = true })
        }
        assertEquals(1, progressCalls)
    }

    @Test fun cancelledRecoveryReleasesWorkerForNextRequest() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val engine = RecoveryEngine()
        try {
            val old = worker.submit<Boolean> {
                var checks = 0
                try {
                    engine.recover(QrMatrix(177), cancellation = RecoveryCancellation {
                        if (++checks == 10) {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        cancelled.get()
                    })
                    false
                } catch (_: RecoveryCancelledException) { true }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            cancelled.set(true)
            release.countDown()
            assertTrue(old.get(10, TimeUnit.SECONDS))
            assertEquals(42, worker.submit<Int> { 42 }.get(10, TimeUnit.SECONDS))
            // Cancellation belongs to one invocation, not the reused engine.
            val outcome = engine.recoverWithDiagnostics(QrMatrix(21), maxFormatCandidates = 1, maxKnownDataEccMismatches = 0)
            assertEquals(SearchCompleteness.COMPLETE, outcome.searchDiagnostic.completeness)
            assertFalse(SearchIncompleteReason.USER_INTERRUPTED in outcome.searchDiagnostic.incompleteReasons)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
