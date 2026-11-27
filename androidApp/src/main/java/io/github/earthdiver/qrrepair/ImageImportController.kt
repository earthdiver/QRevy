package io.github.earthdiver.qrrepair

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.Result
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.core.InitialGridEstimate
import io.github.earthdiver.qrrepair.core.InitialGridEstimator
import io.github.earthdiver.qrrepair.core.ZxingCppGridDetector
import io.github.earthdiver.qrrepair.core.InitialThresholdEstimator
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.StandardQrDecoder
import io.github.earthdiver.qrrepair.core.AutomaticScanBudget
import io.github.earthdiver.qrrepair.core.AutomaticScanTrace
import io.github.earthdiver.qrrepair.core.QuickAutoRecovery
import io.github.earthdiver.qrrepair.core.QuickAutoRecoveryResult
import io.github.earthdiver.qrrepair.core.QuickAutoFailureReason
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.RecoveryCancellation
import io.github.earthdiver.qrrepair.core.RecoveryCancelledException
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.io.File
import java.util.concurrent.Executor

/** Images are owned by the import until delivered; the recipient then owns both prepared images. */
internal data class PreparedImageImport(
    val source: BitmapUtils.Prepared,
    val adjusted: BitmapUtils.Prepared,
    val settings: PreprocessSettings,
    val initialGrid: InitialGridEstimate?,
    val standardResult: Result?,
    val initialization: InitialImageAnalysis,
    val automaticResult: QuickAutoRecoveryResult? = null,
)

/** A null estimate is cached too. Ordinary scans defer this work until detailed analysis is opened. */
internal class InitialImageAnalysis(
    private val source: BitmapUtils.Prepared,
    private val adjusted: BitmapUtils.Prepared,
    initialSettings: PreprocessSettings,
    val boundaries: List<ImageQuad>,
    private val trace: AutomaticScanTrace? = null,
    private val estimateGrid: ((LumaImage, List<ImageQuad>, RecoveryCancellation) -> InitialGridEstimate?)? = null,
) {
    var initialized = false
        private set
    var settings = initialSettings
        private set
    var initialGrid: InitialGridEstimate? = null
        private set

    fun initialize(cancellation: RecoveryCancellation = RecoveryCancellation.NONE) {
        if (initialized) return
        try {
            cancellation.check()
            fun estimateThresholds(bitmap: Bitmap) = if (trace == null) InitialThresholdEstimator.estimate(bitmap)
                else trace.measure("INITIAL_THRESHOLDS") { InitialThresholdEstimator.estimate(bitmap) }
            val thresholds = estimateThresholds(adjusted.bitmap)
            val original = if (adjusted === source) thresholds else estimateThresholds(source.bitmap)
            settings = settings.copy(blackMax = thresholds?.blackMax ?: 96, whiteMin = thresholds?.whiteMin ?: 160,
                provisionalBlackMax = thresholds?.blackMax ?: 96, provisionalWhiteMin = thresholds?.whiteMin ?: 160,
                unknownColorClusters = original?.unknownColorClusters.orEmpty())
            initialGrid = if (estimateGrid != null) estimateGrid.invoke(adjusted.luma, boundaries, cancellation)
                else InitialGridEstimator.estimate(adjusted.luma,
                    boundaries.ifEmpty { io.github.earthdiver.qrrepair.core.ZxingJavaGridDetector.detect(adjusted.luma) },
                    cancellation, trace?.gridObserver())
        } catch (_: RecoveryCancelledException) {
            // No completed grid is available; the detailed editor remains usable without it.
        }
        settings = settings.withInitialGrid(initialGrid).copy(lmCorrectionEnabled = settings.lmCorrectionEnabled)
            .withInitialErrorLimits()
        initialized = true
    }
}

/** Owns decoding, preparation, and cleanup of obsolete image imports. UI/session changes stay with the host. */
internal class ImageImportController(
    private val contentResolver: ContentResolver,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Int,
    private val isDestroyed: () -> Boolean,
    private val onFinished: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val nativeScan: (Bitmap) -> ZxingCppGridDetector.Scan = ZxingCppGridDetector::scan,
    private val standardScan: (Bitmap) -> Result? = ::decodeStandardQr,
    private val estimateGrid: ((LumaImage, List<ImageQuad>, RecoveryCancellation) -> InitialGridEstimate?)? = null,
    private val automaticScan: (LumaImage, PreprocessSettings, AutomaticScanBudget) -> QuickAutoRecoveryResult = QuickAutoRecovery()::run,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val firstAutomaticRun: () -> Boolean = QuickAutoStartupState.process::claim,
) {
    fun load(token: Int, uri: Uri, cleanupFile: File?, onDecoded: (Bitmap) -> Unit) {
        executor.execute {
            try {
                val bitmap = decode(uri)
                    ?: throw IllegalArgumentException(localizedText("画像をデコードできません", "Could not decode the image"))
                if (token != currentGeneration()) {
                    bitmap.recycle()
                    cleanupFile?.let { runCatching { it.delete() } }
                    return@execute
                }
                dispatchToUi {
                    if (token != currentGeneration()) {
                        bitmap.recycle()
                        cleanupFile?.let { runCatching { it.delete() } }
                    } else {
                        onFinished(token)
                        onDecoded(bitmap)
                    }
                }
            } catch (t: Throwable) {
                dispatchToUi {
                    onFinished(token)
                    if (token != currentGeneration() || isDestroyed()) return@dispatchToUi
                    onError(t)
                }
                cleanupFile?.let { runCatching { it.delete() } }
            } finally {
                dispatchToUi { onFinished(token) }
            }
        }
    }

    fun prepare(
        token: Int,
        bitmap: Bitmap,
        cleanupFile: File?,
        imageAdjustment: ImageAdjustment,
        distortionCorrection: Boolean = false,
        stopRequested: () -> Boolean = { false },
        automaticRepair: Boolean = true,
        diagnostics: Boolean = false,
        onPrepared: (PreparedImageImport) -> Unit,
    ) {
        val stopped = {
            stopRequested() || token != currentGeneration() || Thread.currentThread().isInterrupted
        }
        executor.execute {
            val trace = if (diagnostics) AutomaticScanTrace(clockMillis) else null
            trace?.event("DEVICE", "model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "heapMaxBytes=${Runtime.getRuntime().maxMemory()}")
            trace?.event("PREPARATION", "START")
            // Queueing and image preparation must not spend the separate repair allowance.
            val budget = AutomaticScanBudget.forImport(automaticRepair && firstAutomaticRun(),
                stopRequested = stopped, clockMillis = clockMillis, trace = trace)
            var candidate: BitmapUtils.Prepared? = null
            var adjustedCandidate: BitmapUtils.Prepared? = null
            var handedToUi = false
            try {
                val p = BitmapUtils.prepare(bitmap)
                candidate = p
                if (p.bitmap !== bitmap) bitmap.recycle()
                val adjusted = BitmapUtils.adjust(p, imageAdjustment)
                adjustedCandidate = adjusted
                trace?.event("IMAGE", "size=${adjusted.luma.width}x${adjusted.luma.height} adjustment=$imageAdjustment")
                val nativeStarted = trace?.elapsedMillis ?: 0L
                val native = nativeScan(adjusted.bitmap)
                trace?.event("NATIVE_SCAN", "durationMs=${trace.elapsedMillis - nativeStarted} decoded=${native.decoded != null} boundaries=${native.boundaries}")
                val standardStarted = trace?.elapsedMillis ?: 0L
                val standardResult = native.decoded ?: if (!automaticRepair || !budget.stopped()) standardScan(adjusted.bitmap) else null
                trace?.event("STANDARD_SCAN", "durationMs=${trace.elapsedMillis - standardStarted} decoded=${standardResult != null}")
                val initialization = InitialImageAnalysis(p, adjusted, PreprocessSettings(
                    photographicSource = true,
                    imageAdjustment = imageAdjustment,
                    distortionCorrection = distortionCorrection,
                    automaticThresholds = true,
                    lockCorners = true,
                    logicalGridAssigned = false,
                ).withInitialErrorLimits(), native.boundaries, trace, estimateGrid)
                val automatic = if (standardResult == null) {
                    val gridStarted = trace?.elapsedMillis ?: 0L
                    initialization.initialize(RecoveryCancellation {
                        token != currentGeneration() || Thread.currentThread().isInterrupted ||
                            (automaticRepair && budget.stopped())
                    })
                    trace?.event("INITIAL_GRID", "durationMs=${trace.elapsedMillis - gridStarted} result=${initialization.initialGrid} stop=${budget.stopReason() ?: "NONE"}")
                    trace?.event("PREPARATION", "END")
                    if (automaticRepair && !stopped()) automaticScan(adjusted.luma, initialization.settings,
                        budget.repairAfterPreparation()).let {
                        if (it.selected == null && initialization.initialGrid == null && budget.stopReason() == "TOTAL_TIME_LIMIT" &&
                            it.failureReason in setOf(null, QuickAutoFailureReason.NO_INITIAL_GRID, QuickAutoFailureReason.TIME_LIMIT)) {
                            trace?.event("IMPORT_FINISH", "failure=TIME_LIMIT stage=INITIAL_GRID")
                            it.copy(failureReason = QuickAutoFailureReason.TIME_LIMIT, diagnosticReport = trace?.report())
                        } else it
                    } else null
                } else null
                val result = PreparedImageImport(p, adjusted, initialization.settings, initialization.initialGrid,
                    standardResult, initialization, automatic)
                if (token != currentGeneration()) {
                    p.bitmap.recycle()
                    if (adjusted !== p) adjusted.bitmap.recycle()
                    return@execute
                }
                dispatchToUi {
                    if (token != currentGeneration()) {
                        p.bitmap.recycle()
                        if (adjusted !== p) adjusted.bitmap.recycle()
                        return@dispatchToUi
                    }
                    onFinished(token)
                    onPrepared(result)
                }
                handedToUi = true
            } catch (t: Throwable) {
                dispatchToUi {
                    onFinished(token)
                    if (token != currentGeneration() || isDestroyed()) return@dispatchToUi
                    onError(t)
                }
            } finally {
                dispatchToUi { onFinished(token) }
                if (!handedToUi) {
                    adjustedCandidate?.bitmap?.takeIf { it !== candidate?.bitmap && !it.isRecycled }?.recycle()
                    candidate?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap.takeIf { it !== candidate?.bitmap && !it.isRecycled }?.recycle()
                }
                // CameraActivity hands us a private lossless cache image. It is only a hand-off artifact;
                // the durable session copy is lossless PNG under noBackupFilesDir.
                cleanupFile?.let { runCatching { it.delete() } }
            }
        }
    }

    private fun decode(uri: Uri): Bitmap? {
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode before resampling: decoder-specific subsampling changes recovery evidence.
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val spec = imageOrientationTransform(orientation)
        val transform = Matrix().apply {
            setRotate(spec.rotationDegrees)
            if (spec.scaleX != 1f || spec.scaleY != 1f) postScale(spec.scaleX, spec.scaleY)
        }
        val upright = try {
            if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, false).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (t: Throwable) {
            decoded.recycle()
            throw t
        }
        return try { BitmapUtils.boundImportedSource(upright) } finally { upright.recycle() }
    }
}

internal fun decodeStandardQr(bitmap: Bitmap): Result? = runCatching {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    StandardQrDecoder.decode(bitmap.width, bitmap.height, pixels)
}.getOrNull()
