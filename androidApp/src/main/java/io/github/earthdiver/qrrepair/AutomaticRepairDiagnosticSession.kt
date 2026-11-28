package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.AutomaticScanTrace
import io.github.earthdiver.qrrepair.core.GridEstimationTrace
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.InitialGridEstimate
import io.github.earthdiver.qrrepair.core.InitialGridEstimator
import io.github.earthdiver.qrrepair.core.RecoveryCancellation
import io.github.earthdiver.qrrepair.core.RecoveryCancelledException
import io.github.earthdiver.qrrepair.core.AutomaticScanBudget
import io.github.earthdiver.qrrepair.core.QuickAutoRecovery
import io.github.earthdiver.qrrepair.core.QuickAutoRecoveryResult
import io.github.earthdiver.qrrepair.core.QuickAutoFailureReason
import io.github.earthdiver.qrrepair.core.AutoRecoveryResult

internal enum class QuickAutoRetryMode { TEN_SECONDS, UNLIMITED }

/** Keeps the original automatic input, independent of later detailed-editor changes. */
internal class AutomaticRepairDiagnosticSession(
    val image: LumaImage,
    settings: PreprocessSettings,
    val baselineReport: String,
    boundaries: List<ImageQuad> = emptyList(),
    val textMatrix: Boolean = false,
    boundaryDetectionComplete: Boolean = boundaries.isNotEmpty(),
    private val detectFallback: (LumaImage, RecoveryCancellation) -> List<ImageQuad> =
        io.github.earthdiver.qrrepair.core.ZxingJavaGridDetector::detect,
) {
    val settings = settings.recoverySnapshot()
    private var boundaries = boundaries.takeIf { boundaryDetectionComplete || it.isNotEmpty() }?.toList()
    var extendedReport: String? = null
    var unlimitedRepairReport: String? = null
    @Volatile var nextRetry: QuickAutoRetryMode? = QuickAutoRetryMode.TEN_SECONDS
        private set
    private var extendedSettings = this.settings

    data class Extension(val result: QuickAutoRecoveryResult, val settings: PreprocessSettings)

    /** One ten-second attempt, then one unlimited attempt; both reuse completed geometry. */
    fun extend(trace: AutomaticScanTrace, stopped: () -> Boolean,
        clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        estimateGrid: (LumaImage, List<ImageQuad>, RecoveryCancellation, GridEstimationTrace?) -> InitialGridEstimate? =
            InitialGridEstimator::estimate,
        recover: (LumaImage, PreprocessSettings, AutomaticScanBudget) -> QuickAutoRecoveryResult = { input, saved, budget ->
            if (textMatrix) QuickAutoRecovery().runTextMatrix(input, saved, budget)
            else QuickAutoRecovery().run(input, saved, budget)
        }): Extension {
        val mode = checkNotNull(nextRetry) { "Automatic retry sequence is complete" }
        nextRetry = if (mode == QuickAutoRetryMode.TEN_SECONDS) QuickAutoRetryMode.UNLIMITED else null
        val bounded = mode == QuickAutoRetryMode.TEN_SECONDS
        val budget = AutomaticScanBudget(limitMillis = 10_000, stopRequested = stopped,
            clockMillis = clockMillis, trace = trace, sliceMultiplier = 3, timeLimitsEnabled = bounded)
        trace.event("EXTENDED_RETRY", "START mode=$mode timeLimits=$bounded restart=true")
        val result = try {
            if (extendedSettings.manualQuad == null) extendedSettings = settingsForRetry(trace,
                RecoveryCancellation(budget::stopped), timeLimitsEnabled = bounded, estimateGrid = estimateGrid)
            else trace.event("INITIAL_GRID_RETRY", "REUSED")
            recover(image, extendedSettings, budget)
        } catch (_: RecoveryCancelledException) {
            QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()), diagnosticReport = trace.report(),
                failureReason = if (stopped()) QuickAutoFailureReason.INTERRUPTED else QuickAutoFailureReason.TIME_LIMIT)
        }
        trace.event("EXTENDED_RETRY", "END failure=${result.failureReason} selected=${result.selected != null}")
        val report = trace.report()
        if (bounded) extendedReport = report else unlimitedRepairReport = report
        return Extension(result.copy(diagnosticReport = report), extendedSettings)
    }

    /** A missing grid is retried on the original input, without altering either editor or baseline. */
    fun settingsForRetry(trace: AutomaticScanTrace, cancellation: RecoveryCancellation,
        timeLimitsEnabled: Boolean = false,
        estimateGrid: (LumaImage, List<ImageQuad>, RecoveryCancellation, GridEstimationTrace?) -> InitialGridEstimate? =
            InitialGridEstimator::estimate): PreprocessSettings {
        if (settings.manualQuad != null) {
            trace.event("INITIAL_GRID_RETRY", "REUSED")
            return settings
        }
        trace.event("PREPARATION", "START timeLimits=$timeLimitsEnabled")
        val start = trace.elapsedMillis
        return try {
            cancellation.check()
            val detected = boundaries ?: detectFallback(image, cancellation).also {
                cancellation.check()
                boundaries = it.toList()
            }
            val grid = estimateGrid(image, detected, cancellation, trace.gridObserver())
            cancellation.check()
            trace.event("INITIAL_GRID", "durationMs=${trace.elapsedMillis - start} result=$grid stop=NONE")
            if (grid == null) settings else settings.withInitialGrid(grid)
                .copy(lmCorrectionEnabled = settings.lmCorrectionEnabled).withInitialErrorLimits()
        } catch (e: RecoveryCancelledException) {
            trace.event("INITIAL_GRID", "durationMs=${trace.elapsedMillis - start} result=null stop=CANCELLED")
            throw e
        } finally {
            trace.event("PREPARATION", "END")
        }
    }

    fun report(): String = "QRevy automatic repair diagnostic v1\n\n=== NORMAL LIMITS ===\n" + baselineReport +
        (extendedReport?.let { "\n\n=== EXTENDED RETRY (10 seconds) ===\n$it" } ?: "") +
        (unlimitedRepairReport?.let { "\n\n=== AUTOMATIC RETRY (no time limit) ===\n$it" } ?: "")
}
