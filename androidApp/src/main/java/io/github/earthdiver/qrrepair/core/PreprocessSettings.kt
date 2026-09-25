package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.roundToInt

enum class PolarityMode { NORMAL, INVERTED, AUTO }

/** Version 40 has 3,706 total data/ECC codewords. */
const val MAX_QR_DATA_ECC_BITS = 3_706 * 8

/** User-selected parts of QR format information. Null fields remain automatic. */
data class QrFormatConstraint(
    val ecLevel: ErrorCorrectionLevel? = null,
    val dataMask: Int? = null,
) {
    init {
        require(ecLevel != null || dataMask != null)
        require(dataMask == null || dataMask in 0..7)
    }

    fun permits(ecLevel: ErrorCorrectionLevel, dataMask: Int): Boolean =
        (this.ecLevel == null || this.ecLevel == ecLevel) &&
            (this.dataMask == null || this.dataMask == dataMask)

    val candidateCount: Int
        get() = (if (ecLevel == null) 4 else 1) * (if (dataMask == null) 8 else 1)
}

data class PreprocessSettings(
    val imageAdjustment: ImageAdjustment = ImageAdjustment(),
    /** Rotation applied reproducibly to the persisted source image before sampling. */
    val rotationDegrees: Float = 0f,
    val polarity: PolarityMode = PolarityMode.AUTO,
    val blackMax: Int = 96,
    val whiteMin: Int = 160,
    /** Estimate one pair per grid; slider edits keep the whole pair manual. */
    val automaticThresholds: Boolean = false,
    /** Image-wide fallback, independent of candidate order and manual slider values. */
    val provisionalBlackMax: Int = blackMax,
    val provisionalWhiteMin: Int = whiteMin,
    /** Finder-independent Lab clusters sampled as unknown before luminance classification. */
    val unknownColorClusters: List<UnknownColorCluster> = emptyList(),
    val useUnknownColorClusters: Boolean = false,
    val quietZoneCorrection: Boolean = true,
    val version: Int = 1,
    /** Whether the displayed image quad is used instead of deriving corners from finders. */
    val lockCorners: Boolean = true,
    /** Whether geometry candidates are restricted to [version]. */
    val lockVersion: Boolean = true,
    /** Explore the legacy 19-quad neighborhood; valid only when corners and version are fixed. */
    val searchQuadNeighborhood: Boolean = false,
    /** Trust a fully observed mode/count header and force its terminator/alignment/pad suffix. */
    val trustKnownPayloadCount: Boolean = false,
    /** Restrict recovery by ECC level, data mask, or both; null leaves both automatic. */
    val formatConstraint: QrFormatConstraint? = null,
    /** Remembered manual cap across both format copies; null is legacy manual Unlimited. */
    val formatMismatchLimit: Int? = null,
    /** Remembered manual cap against known data/ECC bits after correction; presets resolve separately. */
    val dataEccMismatchLimit: Int? = null,
    /** MANUAL with a null stored value preserves legacy Unlimited settings. */
    val formatMismatchLimitMode: ErrorLimitMode = ErrorLimitMode.MANUAL,
    val dataEccMismatchLimitMode: ErrorLimitMode = ErrorLimitMode.MANUAL,
    /** Protect fixed 0/1 edits unless the user enables correction of fixed cells. */
    val lockManualBits: Boolean = true,
    /** Source-image quadrilateral rectified before all sampling; null leaves the rotated image intact. */
    val perspectiveCorrectionQuad: ImageQuad? = null,
    val manualQuad: ImageQuad? = null,
    /** Assigned centers, set manually or by verified initialization; removable in the Finder UI. */
    val manualFinders: Map<FinderRole, PointD> = emptyMap(),
    /** False while [manualQuad] is only a physical boundary without QR corner roles. */
    val logicalGridAssigned: Boolean = true,
    /** Candidates already measured in the preprocessing screen; avoids an identical second scan. */
    val detectedFinders: List<FinderCandidate> = emptyList(),
    /** Polarity established by an image-verified candidate that the user confirmed. */
    val confirmedFinderPolarity: FinderPolarity = FinderPolarity.UNKNOWN,
    /** User 0/1/? overrides made before recovery, keyed by stable QR module coordinate. */
    val preRecoveryEdits: Map<Coord, ModuleValue> = emptyMap(),
    /** Editable observations, never hard pins; values and user provenance are retained. */
    val preRecoverySoftEdits: Set<Coord> = emptySet(),
    /** Erased data/ECC modules explicitly selected for the bounded priority 0/1 search. */
    val preRecoveryPrioritySearch: Set<Coord> = emptySet(),
    val prioritySearchLimit: Int = 4,
) {
    val effectiveFormatMismatchLimit: Int?
        get() = ErrorLimitPolicy.format(formatMismatchLimitMode, formatMismatchLimit)
    val effectiveDataEccMismatchLimit: Int?
        get() = ErrorLimitPolicy.data(version, dataEccMismatchLimitMode, dataEccMismatchLimit)

    /** New imports opt in; legacy sessions retain their stored manual/unlimited values. */
    fun withInitialErrorLimits() = copy(
        formatMismatchLimitMode = ErrorLimitMode.AUTOMATIC,
        dataEccMismatchLimitMode = ErrorLimitMode.AUTOMATIC,
    )

    val logicalQuad: ImageQuad? get() = manualQuad.takeIf { logicalGridAssigned }

    /** Detach collection inputs before submitting work to a background executor. */
    fun recoverySnapshot(): PreprocessSettings = copy(
        unknownColorClusters = unknownColorClusters.toList(),
        manualFinders = manualFinders.toMap(),
        detectedFinders = detectedFinders.toList(),
        preRecoveryEdits = preRecoveryEdits.toMap(),
        preRecoverySoftEdits = preRecoverySoftEdits.toSet(),
        preRecoveryPrioritySearch = preRecoveryPrioritySearch.toSet(),
    )

    init {
        require(rotationDegrees.isFinite() && rotationDegrees in -360f..360f)
        require(blackMax in 0..254 && whiteMin in 1..255 && blackMax < whiteMin)
        require(provisionalBlackMax in 0..254 && provisionalWhiteMin in 1..255 && provisionalBlackMax < provisionalWhiteMin)
        require(unknownColorClusters.size <= 4)
        require(version in 1..40)
        require(preRecoverySoftEdits.all { it in preRecoveryEdits })
        require(prioritySearchLimit in 4..14)
        require(preRecoveryPrioritySearch.size <= 14)
        require(preRecoveryPrioritySearch.all { preRecoveryEdits[it] == ModuleValue.ERASED })
        require(formatMismatchLimit == null || formatMismatchLimit in 0..30)
        require(dataEccMismatchLimit == null || dataEccMismatchLimit in 0..MAX_QR_DATA_ECC_BITS)
        require(!searchQuadNeighborhood || (lockCorners && lockVersion))
        require(preRecoveryPrioritySearch.isEmpty() || (lockCorners && lockVersion))
    }

    fun variants(): List<SamplingProfile> = when {
        confirmedFinderPolarity == FinderPolarity.NORMAL -> listOf(SamplingProfile(this, false))
        confirmedFinderPolarity == FinderPolarity.INVERTED -> listOf(SamplingProfile(this, true))
        else -> when (polarity) {
        PolarityMode.NORMAL -> listOf(SamplingProfile(this, false))
        PolarityMode.INVERTED -> listOf(SamplingProfile(this, true))
        PolarityMode.AUTO -> listOf(SamplingProfile(this, false), SamplingProfile(this, true))
        }
    }

    /** Unknown edits, including priority-search erasures, are always tentative. */
    val effectiveSoftEdits: Set<Coord> get() = preRecoverySoftEdits +
        preRecoveryEdits.filterValues { it == ModuleValue.ERASED }.keys

    // Explicit ECC/mask consistency remains enforced even when fixed-cell correction is allowed.
    val fixedPreRecoveryEdits: Map<Coord, ModuleValue> get() = preRecoveryEdits - effectiveSoftEdits

    fun sourceForPreRecoveryEdit(value: ModuleValue, coord: Coord? = null): ModuleSource = when {
        value == ModuleValue.ERASED || coord in preRecoverySoftEdits -> ModuleSource.USER_SOFT
        lockManualBits -> ModuleSource.USER
        else -> ModuleSource.USER_UNLOCKED
    }

}

data class SamplingProfile(val settings: PreprocessSettings, val inverted: Boolean) {
    val label: String get() = if (inverted) localizedText("反転", "inverted") else localizedText("通常", "normal")

    fun normalize(luma: Double): Double = if (inverted) 255.0 - luma else luma

    /** Import estimates use source-image luminance, unlike fitted/manual logical thresholds. */
    val provisionalThresholds: ThresholdPair get() = if (inverted)
        ThresholdPair(255 - settings.provisionalWhiteMin, 255 - settings.provisionalBlackMax)
    else ThresholdPair(settings.provisionalBlackMax, settings.provisionalWhiteMin)

    val threshold: Double get() = (settings.blackMax + settings.whiteMin) / 2.0

    fun classifyLuminance(normalized: Double): ModuleValue {
        val value = normalized.roundToInt().coerceIn(0, 255)
        return when {
            value <= settings.blackMax -> ModuleValue.ONE
            value >= settings.whiteMin -> ModuleValue.ZERO
            else -> ModuleValue.ERASED
        }
    }

    fun classify(image: LumaImage, luma: Double, x: Double, y: Double, radius: Double): ModuleValue {
        if (settings.useUnknownColorClusters && settings.unknownColorClusters.isNotEmpty() &&
            image.colorFraction(x, y, maxOf(1.0, radius)) { rgb -> settings.unknownColorClusters.any { it.contains(rgb) } } >= 0.50
        ) {
            return ModuleValue.ERASED
        }
        return classifyLuminance(normalize(luma))
    }
}

internal fun PreprocessSettings.searchSummary(): String {
    val polarityLabel = when (confirmedFinderPolarity) {
        FinderPolarity.NORMAL -> localizedText("通常極性（確定Finder）", "normal polarity (confirmed Finder)")
        FinderPolarity.INVERTED -> localizedText("反転極性（確定Finder）", "inverted polarity (confirmed Finder)")
        FinderPolarity.UNKNOWN -> when (polarity) {
            PolarityMode.NORMAL -> localizedText("通常極性", "normal polarity")
            PolarityMode.INVERTED -> localizedText("反転極性", "inverted polarity")
            PolarityMode.AUTO -> localizedText("通常・反転の両方を比較", "compare normal and inverted")
        }
    }
    return "$polarityLabel / " +
        localizedText("表示中のQR格子 / V$version", "displayed QR grid / Version $version") +
        formatConstraint?.let {
            val ec = it.ecLevel?.toString() ?: localizedText("自動", "auto")
            val mask = it.dataMask?.toString() ?: localizedText("自動", "auto")
            localizedText(" / 形式指定 ECC=$ec, mask=$mask", " / format constraint ECC=$ec, mask=$mask")
        }.orEmpty() +
        effectiveFormatMismatchLimit?.takeIf { formatConstraint?.candidateCount != 1 }?.let {
            localizedText(" / 形式情報の誤り上限=$it", " / format-information error limit=$it")
        }.orEmpty() +
        effectiveDataEccMismatchLimit?.let {
            localizedText(" / データ・ECCの誤り上限=${it}bit", " / data/ECC error limit=$it bits")
        }.orEmpty() +
        if (searchQuadNeighborhood) localizedText(" / サンプリング微調整あり", " / sampling fine-tuning") else ""
}
