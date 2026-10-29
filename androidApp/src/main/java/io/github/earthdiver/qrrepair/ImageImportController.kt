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
)

/** Owns decoding, preparation, and cleanup of obsolete image imports. UI/session changes stay with the host. */
internal class ImageImportController(
    private val contentResolver: ContentResolver,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Int,
    private val isDestroyed: () -> Boolean,
    private val onFinished: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
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
        onPrepared: (PreparedImageImport) -> Unit,
    ) {
        executor.execute {
            var candidate: BitmapUtils.Prepared? = null
            var adjustedCandidate: BitmapUtils.Prepared? = null
            var handedToUi = false
            try {
                val p = BitmapUtils.prepare(bitmap)
                candidate = p
                if (p.bitmap !== bitmap) bitmap.recycle()
                val adjusted = BitmapUtils.adjust(p, imageAdjustment)
                adjustedCandidate = adjusted
                val standardResult = decodeStandardQr(adjusted.bitmap)
                val initialEstimate = InitialThresholdEstimator.estimate(adjusted.bitmap)
                val originalEstimate = if (adjusted === p) initialEstimate else InitialThresholdEstimator.estimate(p.bitmap)
                val initialGrid = InitialGridEstimator.estimate(
                    adjusted.luma, ZxingCppGridDetector.detect(adjusted.bitmap),
                )
                val settings = PreprocessSettings(
                    imageAdjustment = imageAdjustment,
                    automaticThresholds = true,
                    blackMax = initialEstimate?.blackMax ?: 96,
                    whiteMin = initialEstimate?.whiteMin ?: 160,
                    unknownColorClusters = originalEstimate?.unknownColorClusters.orEmpty(),
                    lockCorners = true,
                ).withInitialGrid(initialGrid).withInitialErrorLimits()
                val result = PreparedImageImport(p, adjusted, settings, initialGrid, standardResult)
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
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 2600) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val spec = imageOrientationTransform(orientation)
        val transform = Matrix().apply {
            setRotate(spec.rotationDegrees)
            if (spec.scaleX != 1f || spec.scaleY != 1f) postScale(spec.scaleX, spec.scaleY)
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return decoded
        }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (t: Throwable) {
            decoded.recycle()
            throw t
        }
    }
}

internal fun decodeStandardQr(bitmap: Bitmap): Result? = runCatching {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    StandardQrDecoder.decode(bitmap.width, bitmap.height, pixels)
}.getOrNull()
