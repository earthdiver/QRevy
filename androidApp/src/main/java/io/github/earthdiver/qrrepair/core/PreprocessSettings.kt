package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.roundToInt

enum class PolarityMode { NORMAL, INVERTED, AUTO }
enum class ThresholdMode { GLOBAL, LOCAL_ADAPTIVE }

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
    /** Rotation applied reproducibly to the persisted source image before sampling. */
    val rotationDegrees: Float = 0f,
    val polarity: PolarityMode = PolarityMode.AUTO,
    val thresholdMode: ThresholdMode = ThresholdMode.LOCAL_ADAPTIVE,
    val blackMax: Int = 96,
    val whiteMin: Int = 160,
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
    /** Maximum known-bit mismatches across both format-information copies; null means unlimited. */
    val formatMismatchLimit: Int? = null,
    /** Maximum mismatches against known data/ECC modules after RS correction; null means unlimited. */
    val dataEccMismatchLimit: Int? = null,
    /** Treat preprocessing 0/1 edits as hard pins. '?' and '*' remain recoverable erasures. */
    val lockManualBits: Boolean = true,
    /** Source-image quadrilateral rectified before all sampling; null leaves the rotated image intact. */
    val perspectiveCorrectionQuad: ImageQuad? = null,
    val manualQuad: ImageQuad? = null,
    val manualFinders: Map<FinderRole, PointD> = emptyMap(),
    /** False while [manualQuad] is only a physical boundary without QR corner roles. */
    val logicalGridAssigned: Boolean = true,
    /** Candidates already measured in the preprocessing screen; avoids an identical second scan. */
    val detectedFinders: List<FinderCandidate> = emptyList(),
    /** Polarity established by an image-verified candidate that the user confirmed. */
    val confirmedFinderPolarity: FinderPolarity = FinderPolarity.UNKNOWN,
    /** Hard 0/1/? overrides made before recovery, keyed by stable QR module coordinate. */
    val preRecoveryEdits: Map<Coord, ModuleValue> = emptyMap(),
    /** Erased data/ECC modules explicitly selected for the bounded priority 0/1 search. */
    val preRecoveryPrioritySearch: Set<Coord> = emptySet(),
) {
    val logicalQuad: ImageQuad? get() = manualQuad.takeIf { logicalGridAssigned }

    /** Detach collection inputs before submitting work to a background executor. */
    fun recoverySnapshot(): PreprocessSettings = copy(
        unknownColorClusters = unknownColorClusters.toList(),
        manualFinders = manualFinders.toMap(),
        detectedFinders = detectedFinders.toList(),
        preRecoveryEdits = preRecoveryEdits.toMap(),
        preRecoveryPrioritySearch = preRecoveryPrioritySearch.toSet(),
    )

    init {
        require(rotationDegrees.isFinite() && rotationDegrees in -360f..360f)
        require(blackMax in 0..254 && whiteMin in 1..255 && blackMax < whiteMin)
        require(unknownColorClusters.size <= 4)
        require(version in 1..40)
        require(preRecoveryPrioritySearch.size <= 4)
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

    fun sourceForPreRecoveryEdit(value: ModuleValue): ModuleSource =
        if (value == ModuleValue.ERASED || lockManualBits) ModuleSource.USER else ModuleSource.IMAGE
}

data class SamplingProfile(val settings: PreprocessSettings, val inverted: Boolean) {
    val label: String get() = if (inverted) localizedText("反転", "inverted") else localizedText("通常", "normal")

    fun normalize(luma: Double): Double = if (inverted) 255.0 - luma else luma

    fun thresholdAt(image: LumaImage, x: Double, y: Double, radius: Double): Double {
        val midpoint = (settings.blackMax + settings.whiteMin) / 2.0
        if (settings.thresholdMode == ThresholdMode.GLOBAL) return midpoint
        val local = image.sampleMean(x, y, maxOf(4.0, radius * 3.5))?.let(::normalize) ?: midpoint
        return (midpoint + (local - 127.5) * 0.65).coerceIn(20.0, 235.0)
    }

    fun classify(image: LumaImage, luma: Double, x: Double, y: Double, radius: Double): ModuleValue {
        if (settings.useUnknownColorClusters && settings.unknownColorClusters.isNotEmpty() &&
            image.colorFraction(x, y, maxOf(1.0, radius)) { rgb -> settings.unknownColorClusters.any { it.contains(rgb) } } >= 0.50
        ) {
            return ModuleValue.ERASED
        }
        val midpoint = (settings.blackMax + settings.whiteMin) / 2.0
        val shift = thresholdAt(image, x, y, radius) - midpoint
        // Module sampling produces a fractional mean even for an integer-luminance image.
        // Quantize after undoing the local shift so adjacent integer thresholds truly have no gap.
        val value = (normalize(luma) - shift).roundToInt().coerceIn(0, 255)
        return when {
            value <= settings.blackMax -> ModuleValue.ONE
            value >= settings.whiteMin -> ModuleValue.ZERO
            else -> ModuleValue.ERASED
        }
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
        formatMismatchLimit?.takeIf { formatConstraint?.candidateCount != 1 }?.let {
            localizedText(" / 形式情報の誤り上限=$it", " / format-information error limit=$it")
        }.orEmpty() +
        dataEccMismatchLimit?.let {
            localizedText(" / データ・ECCの誤り上限=${it}bit", " / data/ECC error limit=$it bits")
        }.orEmpty() +
        if (searchQuadNeighborhood) localizedText(" / サンプリング微調整あり", " / sampling fine-tuning") else ""
}
