package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Samples the saved mapping and applies edits exactly as the selected repair attempt does. */
internal fun sampleSavedPreRecoveryData(image: LumaImage, settings: PreprocessSettings,
    stopRequested: () -> Boolean = { false }): PreRecoveryDataInput? {
    if (stopRequested()) throw RecoveryCancelledException()
    val quad = settings.manualQuad ?: return null
    val version = settings.version
    val dimension = 17 + 4 * version
    val pitch = ((quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
        (2.0 * (dimension - 1))).coerceAtLeast(.25)
    val geometry = settings.previewGeometry?.takeIf {
        settings.distortionCorrection && settings.logicalQuad != null && it.version == version
    } ?: runCatching {
        GridGeometry(version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
            pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
    }.getOrNull() ?: return null
    val matrix = PreRecoveryPreviewSampler().sample(image, geometry, settings.variants().first(),
        settings.logicalGridAssigned, stopRequested).first
    settings.preRecoveryEdits.forEach { (coord, value) ->
        if (coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension) {
            matrix.set(coord.x, coord.y, value, 1f, settings.sourceForPreRecoveryEdit(value, coord))
            matrix.setPrioritySearch(coord.x, coord.y, coord in settings.preRecoveryPrioritySearch)
        }
    }
    return PreRecoveryDataInput(matrix, version)
}
