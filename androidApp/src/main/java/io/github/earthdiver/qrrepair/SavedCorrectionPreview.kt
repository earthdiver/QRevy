package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PreprocessSettings

/** One in-memory correction result, owned by the active source-image workspace. */
internal data class SavedCorrectionPreview(
    val imageKey: ImageKey,
    val referenceQuad: ImageQuad?,
    val correction: DistortionPreviewState.Snapshot,
) {
    data class ImageKey(val adjustment: ImageAdjustment, val rotation: Float, val perspective: ImageQuad?,
        val version: Int, val width: Int, val height: Int, val legacyImageRotation: Float? = null) {
        companion object {
            fun from(settings: PreprocessSettings, width: Int, height: Int) = ImageKey(
                settings.imageAdjustment, settings.rotationDegrees, settings.perspectiveCorrectionQuad,
                settings.version, width, height, settings.legacyImageRotationDegrees)
        }
    }
}
