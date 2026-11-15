package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageImportControllerTest {
    @Test fun gridPreparationTimeoutIsReportedWithoutDependingOnDebugTracing() {
        var now = 0L
        var imported: PreparedImageImport? = null
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { ZxingCppGridDetector.Scan(emptyList()) }, standardScan = { null },
            estimateGrid = { _, _, cancellation -> now = 3000; cancellation.check(); null },
            automaticScan = { _, saved, budget ->
                assertNull(saved.manualQuad)
                assertFalse(budget.stopped())
                QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()),
                    failureReason = QuickAutoFailureReason.NO_INITIAL_GRID)
            }, clockMillis = { now }, firstAutomaticRun = { false })
        controller.prepare(1, bitmap(), null, ImageAdjustment(), diagnostics = false) { imported = it }
        val result = requireNotNull(imported)
        assertEquals(QuickAutoFailureReason.TIME_LIMIT, result.automaticResult!!.failureReason)
        assertNull(result.automaticResult.diagnosticReport)
        assertNull(result.initialGrid)
        result.source.bitmap.recycle()
    }

    @Test fun initialImportSharesItsExtraTimeWithRepairAndPreservesTheGrid() {
        var now = 0L
        var imported: PreparedImageImport? = null
        val startup = QuickAutoStartupState()
        val grid = InitialGridEstimate(1, ImageQuad(PointD(1.0, 1.0), PointD(30.0, 1.0),
            PointD(30.0, 30.0), PointD(1.0, 30.0)), InitialGridSource.PERIODICITY)
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { now += 2000; ZxingCppGridDetector.Scan(emptyList()) }, standardScan = { null },
            estimateGrid = { _, _, cancellation -> now += 2000; cancellation.check(); grid },
            automaticScan = { _, saved, budget ->
                assertEquals(grid.moduleCenterQuad, saved.manualQuad)
                assertTrue(budget.firstRun)
                assertEquals(3500L, budget.remainingMillis)
                now += 3500
                assertEquals("TOTAL_TIME_LIMIT", budget.stopReason())
                QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()))
            }, clockMillis = { now }, firstAutomaticRun = startup::claim)
        controller.prepare(1, bitmap(), null, ImageAdjustment()) { imported = it }
        assertSame(grid, requireNotNull(imported).initialGrid)
        assertFalse(startup.claim())
        imported.source.bitmap.recycle()
    }

    @Test fun queuedImportAndSlowPreparationLeaveAFullBoundedRepairAllowance() {
        var now = 0L
        var work: Runnable? = null
        var delivered: PreparedImageImport? = null
        var repairCalls = 0
        val grid = InitialGridEstimate(4, ImageQuad(PointD(1.0, 1.0), PointD(30.0, 1.0),
            PointD(30.0, 30.0), PointD(1.0, 30.0)), InitialGridSource.PERIODICITY)
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { work = it }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { now += 1_000; ZxingCppGridDetector.Scan(emptyList()) },
            standardScan = { now += 1_000; null },
            estimateGrid = { _, _, cancellation -> now += 900; cancellation.check(); grid },
            automaticScan = { _, settings, budget ->
                repairCalls++
                assertEquals(4, settings.version)
                assertEquals(grid.moduleCenterQuad, settings.manualQuad)
                assertFalse(budget.stopped())
                now += 2_999
                assertFalse(budget.stopped())
                now++
                assertTrue(budget.stopped())
                QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()))
            }, clockMillis = { now }, firstAutomaticRun = { false })
        controller.prepare(1, bitmap(), null, ImageAdjustment()) { delivered = it }
        now += 10_000 // Waiting for the worker is outside both processing allowances.
        requireNotNull(work).run()
        assertEquals(1, repairCalls)
        val result = requireNotNull(delivered)
        assertSame(grid, result.initialGrid)
        result.initialization.initialize()
        assertSame(grid, result.initialization.initialGrid)
        result.source.bitmap.recycle()
    }

    @Test fun requestingDetailsAfterGridEstimationDoesNotStartANewRepairAllowance() {
        var stop = false
        var delivered: PreparedImageImport? = null
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { ZxingCppGridDetector.Scan(emptyList()) }, standardScan = { null },
            estimateGrid = { _, _, _ -> stop = true; null },
            automaticScan = { _, _, _ -> error("A stop request must survive the stage transition") })
        controller.prepare(1, bitmap(), null, ImageAdjustment(), stopRequested = { stop }) { delivered = it }
        val result = requireNotNull(delivered)
        assertNull(result.automaticResult)
        result.source.bitmap.recycle()
    }

    @Test fun disabledAutomaticRepairStillReadsNormallyAndInitializesDetailsWithoutAutomaticBudget() {
        var delivered: PreparedImageImport? = null
        var reads = 0
        var estimates = 0
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { reads++; ZxingCppGridDetector.Scan(emptyList()) },
            standardScan = { reads++; null },
            estimateGrid = { _, _, cancellation -> cancellation.check(); estimates++; null },
            automaticScan = { _, _, _ -> error("Disabled automatic repair must not run") })
        controller.prepare(1, bitmap(), null, ImageAdjustment(), stopRequested = { true }, automaticRepair = false) { delivered = it }
        val result = requireNotNull(delivered)
        assertEquals(2, reads)
        assertEquals(1, estimates)
        assertNull(result.automaticResult)
        assertTrue(result.initialization.initialized)
        assertFalse(result.source.bitmap.isRecycled)
        result.source.bitmap.recycle()
    }

    @Test fun nativeSuccessSkipsGridAndRecoveryUntilDetailsAreOpened() {
        val callbacks = ArrayDeque<() -> Unit>()
        var imported: PreparedImageImport? = null
        var estimates = 0
        val nativeResult = Result("native result", null, emptyArray(), BarcodeFormat.QR_CODE)
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { callbacks.addLast(it) }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { ZxingCppGridDetector.Scan(emptyList(), nativeResult) },
            standardScan = { error("Native success must bypass the compatibility reader") },
            estimateGrid = { _, _, _ -> estimates++; null },
            automaticScan = { _, _, _ -> error("Native success must bypass recovery") })
        controller.prepare(1, bitmap(), null, ImageAdjustment()) { imported = it }
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        val result = requireNotNull(imported)
        assertSame(nativeResult, result.standardResult)
        assertEquals(0, estimates)
        assertFalse(result.initialization.initialized)
        result.initialization.initialize()
        result.initialization.initialize()
        assertEquals(1, estimates)
        assertNull(result.initialization.initialGrid)
        result.source.bitmap.recycle()
    }

    @Test fun failedScanReusesTheInitialGridAndDoesNotSaveAutomaticOverrides() {
        val callbacks = ArrayDeque<() -> Unit>()
        var imported: PreparedImageImport? = null
        var estimates = 0
        var calls = 0
        val grid = InitialGridEstimate(2, ImageQuad(PointD(1.0, 1.0), PointD(30.0, 1.0),
            PointD(30.0, 30.0), PointD(1.0, 30.0)), InitialGridSource.PERIODICITY)
        val controller = ImageImportController(RuntimeEnvironment.getApplication().contentResolver,
            Executor { it.run() }, { callbacks.addLast(it) }, { 1 }, { false }, {}, { throw AssertionError(it) },
            nativeScan = { ZxingCppGridDetector.Scan(emptyList()) }, standardScan = { null },
            estimateGrid = { _, _, _ -> estimates++; grid },
            automaticScan = { _, saved, _ ->
                calls++
                assertEquals(grid.moduleCenterQuad, saved.manualQuad)
                assertEquals(18, saved.effectiveDataEccMismatchLimit)
                QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()))
            })
        controller.prepare(1, bitmap(), null, ImageAdjustment()) { imported = it }
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        val result = requireNotNull(imported)
        result.initialization.initialize()
        assertEquals(1, estimates)
        assertEquals(1, calls)
        assertSame(grid, result.initialGrid)
        assertSame(result.settings, result.initialization.settings)
        assertFalse(result.settings.distortionCorrection)
        assertEquals(0, result.settings.knownBitErrorLimit)
        result.source.bitmap.recycle()
    }

    @Test fun stoppedInitializationStillHandsOffLiveSourceWithoutRepeatingEstimation() {
        val source = BitmapUtils.prepare(bitmap())
        var calls = 0
        val initialization = InitialImageAnalysis(source, source, PreprocessSettings(), emptyList()) { _, _, cancellation ->
            calls++
            cancellation.check()
            null
        }
        initialization.initialize(RecoveryCancellation { true })
        initialization.initialize()
        assertEquals(0, calls)
        assertTrue(initialization.initialized)
        assertNull(initialization.initialGrid)
        assertFalse(source.bitmap.isRecycled)
        source.bitmap.recycle()
    }

    @Test fun preparationTransfersLiveImagesAndAdjustmentSettingsToRecipient() {
        val callbacks = ArrayDeque<() -> Unit>()
        var result: PreparedImageImport? = null
        val adjustment = ImageAdjustment(contrast = 20, brightness = 10)
        val input = bitmap()
        val controller = controller(callbacks, { 1 })
        controller.prepare(1, input, null, adjustment, distortionCorrection = true) { result = it }
        assertNull(result)
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        val delivered = requireNotNull(result)
        assertFalse(delivered.source.bitmap.isRecycled)
        assertFalse(delivered.adjusted.bitmap.isRecycled)
        assertEquals(adjustment, delivered.settings.imageAdjustment)
        assertTrue(delivered.settings.distortionCorrection)
        assertTrue(delivered.settings.automaticThresholds)
        delivered.source.bitmap.recycle()
        if (delivered.adjusted.bitmap !== delivered.source.bitmap) delivered.adjusted.bitmap.recycle()
    }

    @Test fun preparationSupersededWhileAwaitingUiReleasesSourceAndDoesNotPublish() {
        val callbacks = ArrayDeque<() -> Unit>()
        var generation = 1
        val input = bitmap()
        var delivered = false
        val controller = controller(callbacks, { generation })
        controller.prepare(1, input, null, ImageAdjustment()) { delivered = true }
        generation++
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        assertFalse(delivered)
        assertTrue(input.isRecycled)
    }

    @Test fun obsoletePreparationStillDeletesCameraHandoff() {
        val callbacks = ArrayDeque<() -> Unit>()
        val input = bitmap()
        val capture = File.createTempFile("qr-capture-import-", ".png", RuntimeEnvironment.getApplication().cacheDir)
        try {
            controller(callbacks, { 2 }).prepare(1, input, capture, ImageAdjustment()) {
                fail("An obsolete import must not be delivered")
            }
            while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
            assertTrue(input.isRecycled)
            assertFalse(capture.exists())
        } finally { capture.delete() }
    }

    private fun controller(callbacks: ArrayDeque<() -> Unit>, generation: () -> Int) = ImageImportController(
        RuntimeEnvironment.getApplication().contentResolver,
        Executor { it.run() },
        { callbacks.addLast(it) },
        generation,
        { false },
        {},
        { throw AssertionError("Unexpected import failure", it) },
    )

    private fun bitmap() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }
}
