package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.QrFormatConstraint

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
    val formatConstraint: QrFormatConstraint? = null,
    val formatMismatchLimit: Int? = null,
    val dataEccMismatchLimit: Int? = null,
    val formatMismatchLimitMode: ErrorLimitMode = ErrorLimitMode.MANUAL,
    val dataEccMismatchLimitMode: ErrorLimitMode = ErrorLimitMode.MANUAL,
    val lockManualBits: Boolean = false,
    val prioritySearchLimit: Int = 4,
)

internal data class PreprocessGeometrySnapshot(
    val logicalGridAssigned: Boolean,
    val quad: ImageQuad,
    val finders: Map<FinderRole, PointD>,
    val confirmedFinderPolarity: FinderPolarity? = null,
)

/** Owns non-View state for one pre-repair dialog and assembles reproducible settings. */
internal class PreprocessDialogState(private val initial: PreprocessSettings) {
    var provisionalBlackMax: Int = initial.provisionalBlackMax
        private set
    var provisionalWhiteMin: Int = initial.provisionalWhiteMin
        private set

    fun updateProvisionalThresholds(estimate: io.github.earthdiver.qrrepair.core.InitialThresholdEstimate?) {
        provisionalBlackMax = estimate?.blackMax ?: 96
        provisionalWhiteMin = estimate?.whiteMin ?: 160
    }

    var imageAdjustment = initial.imageAdjustment

    var rotationDegrees: Float = initial.rotationDegrees
        private set
    var perspectiveCorrectionQuad: ImageQuad? = initial.perspectiveCorrectionQuad
        private set
    fun rotateBy(delta: Float) {
        perspectiveCorrectionQuad = null
        rotationDegrees = normalizeRotation(rotationDegrees + delta)
    }

    fun resetRotation() {
        rotationDegrees = 0f
        perspectiveCorrectionQuad = null
    }

    fun applyPerspectiveCorrection(sourceQuad: ImageQuad) {
        check(perspectiveCorrectionQuad == null) { "Perspective correction is already applied" }
        perspectiveCorrectionQuad = sourceQuad
    }

    fun previewSettings(controls: PreprocessControlSnapshot): PreprocessSettings {
        val (blackMax, whiteMin) = normalizedThresholds(controls)
        return PreprocessSettings(
            imageAdjustment = imageAdjustment,
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
            formatConstraint = controls.formatConstraint,
            formatMismatchLimit = controls.formatMismatchLimit,
            dataEccMismatchLimit = controls.dataEccMismatchLimit,
            formatMismatchLimitMode = controls.formatMismatchLimitMode,
            dataEccMismatchLimitMode = controls.dataEccMismatchLimitMode,
            lockManualBits = controls.lockManualBits,
            prioritySearchLimit = controls.prioritySearchLimit,
            perspectiveCorrectionQuad = perspectiveCorrectionQuad,
            manualQuad = geometry.quad,
            manualFinders = geometry.finders,
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

    private fun normalizeRotation(value: Float): Float =
        ((value + 180f) % 360f + 360f) % 360f - 180f
}
