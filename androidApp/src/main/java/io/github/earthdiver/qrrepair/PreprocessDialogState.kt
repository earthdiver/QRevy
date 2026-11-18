package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings

internal data class PreprocessControlSnapshot(
    val polarity: PolarityMode,
    val automaticThresholds: Boolean,
    val blackProgress: Int,
    val whiteProgress: Int,
    val useUnknownColors: Boolean,
    val version: Int,
    val lockCorners: Boolean = true,
    val lockVersion: Boolean = true,
    val searchQuadNeighborhood: Boolean = false,
    val trustKnownPayloadCount: Boolean = false,
    val lockManualBits: Boolean = false,
    val prioritySearchLimit: Int = 4,
    val assumeUtf8ByteSegments: Boolean = false,
)

internal data class PreprocessGeometrySnapshot(
    val logicalGridAssigned: Boolean,
    val quad: ImageQuad,
    val finders: Map<FinderRole, PointD>,
    val confirmedFinderPolarity: FinderPolarity? = null,
    val visibleFinderRole: FinderRole? = null,
)

/** Owns non-View state for one pre-repair dialog and assembles reproducible settings. */
internal class PreprocessDialogState(private val initial: PreprocessSettings) {
    val search = PreprocessSearchSettingsState(initial)

    var provisionalBlackMax: Int = initial.provisionalBlackMax
        private set
    var provisionalWhiteMin: Int = initial.provisionalWhiteMin
        private set

    fun updateProvisionalThresholds(estimate: io.github.earthdiver.qrrepair.core.InitialThresholdEstimate?) {
        provisionalBlackMax = estimate?.blackMax ?: 96
        provisionalWhiteMin = estimate?.whiteMin ?: 160
    }

    var distortionCorrection = initial.distortionCorrection
    var mirroredInput = initial.mirroredInput
    var imageAdjustment = initial.imageAdjustment

    var rotationDegrees: Float = initial.rotationDegrees
        private set
    var perspectiveCorrectionQuad: ImageQuad? = initial.perspectiveCorrectionQuad
        private set
    var legacyImageRotationDegrees: Float? = initial.legacyImageRotationDegrees
        private set
    fun rotateBy(delta: Float) {
        rotationDegrees = rotationAfter(delta)
    }

    fun rotationAfter(delta: Float): Float = normalizeRotation(rotationDegrees + delta)

    fun resetRotation() {
        if (rotationDegrees == 0f) return
        rotationDegrees = 0f
    }

    fun applyPerspectiveCorrection(sourceQuad: ImageQuad) {
        perspectiveCorrectionQuad = sourceQuad
        legacyImageRotationDegrees = null
    }

    fun clearPerspectiveCorrection() {
        perspectiveCorrectionQuad = null
        legacyImageRotationDegrees = null
    }

    fun imageSettings(version: Int): PreprocessSettings = initial.copy(
        rotationDegrees = rotationDegrees, perspectiveCorrectionQuad = perspectiveCorrectionQuad,
        legacyImageRotationDegrees = legacyImageRotationDegrees,
        imageAdjustment = imageAdjustment, version = version,
    )

    fun previewSettings(controls: PreprocessControlSnapshot): PreprocessSettings {
        val (blackMax, whiteMin) = normalizedThresholds(controls)
        return PreprocessSettings(
            photographicSource = initial.photographicSource,
            imageAdjustment = imageAdjustment,
            distortionCorrection = distortionCorrection,
            lmCorrectionEnabled = initial.lmCorrectionEnabled,
            mirroredInput = mirroredInput,
            polarity = controls.polarity,
            automaticThresholds = controls.automaticThresholds,
            provisionalBlackMax = provisionalBlackMax,
            provisionalWhiteMin = provisionalWhiteMin,
            blackMax = blackMax,
            whiteMin = whiteMin,
            unknownColorClusters = initial.unknownColorClusters,
            useUnknownColorClusters = controls.useUnknownColors,
            quietZoneCorrection = initial.quietZoneCorrection,
            version = controls.version.coerceIn(1, 40),
        )
    }

    fun captureSettings(
        controls: PreprocessControlSnapshot,
        geometry: PreprocessGeometrySnapshot,
        edits: Map<Coord, ModuleValue>,
        prioritySearch: Set<Coord>,
        softEdits: Set<Coord> = emptySet(),
    ): PreprocessSettings {
        val preview = previewSettings(controls)
        return preview.copy(
            rotationDegrees = rotationDegrees,
            lockCorners = true,
            lockVersion = true,
            searchQuadNeighborhood = controls.searchQuadNeighborhood,
            trustKnownPayloadCount = controls.trustKnownPayloadCount,
            assumeUtf8ByteSegments = controls.assumeUtf8ByteSegments,
            formatConstraint = search.formatConstraint,
            formatMismatchLimit = search.formatMismatchLimit,
            dataEccMismatchLimit = search.dataEccMismatchLimit,
            formatMismatchLimitMode = search.formatMismatchLimitMode,
            dataEccMismatchLimitMode = search.dataEccMismatchLimitMode,
            lockManualBits = controls.lockManualBits,
            prioritySearchLimit = controls.prioritySearchLimit,
            partialBitSearchLimit = search.partialBitSearchLimit,
            listDecodingAdditionalErrors = search.listDecodingAdditionalErrors,
            knownBitErrorLimit = search.knownBitErrorLimit,
            perspectiveCorrectionQuad = perspectiveCorrectionQuad,
            legacyImageRotationDegrees = legacyImageRotationDegrees,
            manualQuad = geometry.quad,
            manualFinders = geometry.finders,
            visibleFinderRole = geometry.visibleFinderRole,
            logicalGridAssigned = geometry.logicalGridAssigned,
            detectedFinders = emptyList(),
            confirmedFinderPolarity = geometry.confirmedFinderPolarity ?: FinderPolarity.UNKNOWN,
            preRecoveryEdits = edits,
            preRecoverySoftEdits = softEdits,
            preRecoveryPrioritySearch = prioritySearch,
        )
    }

    private fun normalizedThresholds(controls: PreprocessControlSnapshot): Pair<Int, Int> {
        val blackMax = controls.blackProgress.coerceIn(0, 254)
        val whiteMin = (controls.whiteProgress + 1).coerceIn(blackMax + 1, 255)
        return blackMax to whiteMin
    }

    private fun normalizeRotation(value: Float): Float {
        val rounded = kotlin.math.round(value.toDouble() * 10_000.0) / 10_000.0
        return (((rounded + 180.0) % 360.0 + 360.0) % 360.0 - 180.0).toFloat()
    }
}
