package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import kotlin.math.abs
import kotlin.math.max

object QrFunctionMask {
    fun isFunction(versionNumber: Int, x: Int, y: Int): Boolean {
        val version = Version.getVersionForNumber(versionNumber)
        val d = version.dimensionForVersion

        // Finder + separator + format information areas, exactly as ZXing Version.buildFunctionPattern().
        if (x in 0..8 && y in 0..8) return true
        if (x in (d - 8) until d && y in 0..8) return true
        if (x in 0..8 && y in (d - 8) until d) return true

        val centers = version.alignmentPatternCenters
        val maxIndex = centers.size
        for (ix in centers.indices) {
            for (iy in centers.indices) {
                val skippedNearFinder =
                    (ix == 0 && (iy == 0 || iy == maxIndex - 1)) ||
                    (ix == maxIndex - 1 && iy == 0)
                if (!skippedNearFinder) {
                    if (abs(x - centers[iy]) <= 2 && abs(y - centers[ix]) <= 2) return true
                }
            }
        }

        if (x == 6 && y in 9..(d - 9)) return true
        if (y == 6 && x in 9..(d - 9)) return true

        if (versionNumber > 6) {
            if (x in (d - 11)..(d - 9) && y in 0..5) return true
            if (x in 0..5 && y in (d - 11)..(d - 9)) return true
        }
        return false
    }
}

class MatrixSampler(
    private val image: LumaImage,
    private val profile: SamplingProfile? = null,
) {
    data class SamplingStats(
        val threshold: Double, val contrast: Double, val erasureBand: Double,
        val thresholds: ThresholdPair? = null,
    )

    fun sample(geometry: GridGeometry, erasureBandOverride: Double? = null): Pair<QrMatrix, SamplingStats> {
        if (profile != null) return sampleProfile(geometry, profile)
        val stats = estimateFinderThreshold(geometry, erasureBandOverride)
        val matrix = QrMatrix(geometry.dimension)
        for (y in 0 until geometry.dimension) for (x in 0 until geometry.dimension) {
            val p = geometry.moduleToImage(x.toDouble(), y.toDouble())
            val radius = geometry.localModulePitch(x.toDouble(), y.toDouble()) * 0.14
            val lum = image.sampleMean(p.x, p.y, radius)
            if (lum == null) {
                matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                continue
            }
            val distance = abs(lum - stats.threshold)
            val confidence = (distance / max(12.0, stats.contrast * 0.45)).coerceIn(0.0, 1.0).toFloat()
            val value = if (distance <= stats.erasureBand) {
                ModuleValue.ERASED
            } else if (lum < stats.threshold) {
                ModuleValue.ONE
            } else {
                ModuleValue.ZERO
            }
            matrix.set(x, y, value, confidence, ModuleSource.IMAGE)
        }
        return matrix to stats
    }

    private fun sampleProfile(geometry: GridGeometry, requested: SamplingProfile): Pair<QrMatrix, SamplingStats> {
        return sampleObserved(geometry, requested, GridThresholdEstimator.observe(image, geometry, requested.inverted))
    }

    internal fun sampleObserved(
        geometry: GridGeometry, requested: SamplingProfile, observations: Array<ModuleObservation?>,
    ): Pair<QrMatrix, SamplingStats> {
        val settings = requested.settings
        val pair = if (settings.automaticThresholds) {
            GridThresholdEstimator.estimate(geometry.version, observations)
                ?: requested.provisionalThresholds
        } else ThresholdPair(settings.blackMax, settings.whiteMin)
        val effective = requested.copy(settings = settings.copy(blackMax = pair.blackMax, whiteMin = pair.whiteMin))
        val contrast = (pair.whiteMin - pair.blackMax).toDouble()
        val stats = SamplingStats(effective.threshold, contrast, contrast / 2, pair)
        val matrix = QrMatrix(geometry.dimension)
        observations.forEachIndexed { index, observation ->
            val x = index % geometry.dimension; val y = index / geometry.dimension
            var value = ModuleValue.ERASED
            var confidence = 0f
            if (observation != null) {
                val low = effective.classifyLuminance(observation.low)
                val high = effective.classifyLuminance(observation.high)
                if (low == high) value = low
                if (value != ModuleValue.ERASED && settings.useUnknownColorClusters) {
                    val p = geometry.moduleToImage(x.toDouble(), y.toDouble())
                    val radius = geometry.localModulePitch(x.toDouble(), y.toDouble()) * .14
                    value = effective.classify(image, if (requested.inverted) 255 - observation.mean else observation.mean, p.x, p.y, radius)
                }
                if (value != ModuleValue.ERASED) confidence =
                    (abs(observation.mean - stats.threshold) / max(12.0, contrast * .45)).coerceIn(0.0, 1.0).toFloat()
            }
            matrix.set(x, y, value, confidence, ModuleSource.IMAGE)
        }
        return matrix to stats
    }

    private fun estimateFinderThreshold(geometry: GridGeometry, override: Double?): SamplingStats {
        val blacks = mutableListOf<Double>()
        val whites = mutableListOf<Double>()
        val center = geometry.moduleToImage(
            when (geometry.role) {
                FinderRole.TOP_LEFT, FinderRole.BOTTOM_LEFT -> 3.0
                FinderRole.TOP_RIGHT -> (geometry.dimension - 4).toDouble()
            },
            when (geometry.role) {
                FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT -> 3.0
                FinderRole.BOTTOM_LEFT -> (geometry.dimension - 4).toDouble()
            },
        )
        val c = kotlin.math.cos(geometry.angleRad)
        val s = kotlin.math.sin(geometry.angleRad)
        for (my in -3..3) for (mx in -3..3) {
            val px = center.x + c * mx * geometry.modulePitch - s * my * geometry.modulePitch
            val py = center.y + s * mx * geometry.modulePitch + c * my * geometry.modulePitch
            val lum = image.sampleMean(px, py, geometry.modulePitch * 0.13) ?: continue
            val black = mx == -3 || mx == 3 || my == -3 || my == 3 || (mx in -1..1 && my in -1..1)
            if (black) blacks += lum else whites += lum
        }
        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return Double.NaN
            val srt = xs.sorted()
            return srt[srt.size / 2]
        }
        val b = median(blacks)
        val w = median(whites)
        val goodLocal = b.isFinite() && w.isFinite() && w - b >= 18.0
        val threshold = if (goodLocal) (b + w) / 2.0 else image.threshold.toDouble()
        val contrast = if (goodLocal) w - b else 80.0
        val band = override ?: max(7.0, contrast * 0.10)
        return SamplingStats(threshold, contrast, band)
    }
}

data class FormatCandidate(
    val ecLevel: ErrorCorrectionLevel,
    val dataMask: Int,
    val maskedCodeword: Int,
    val matches: Int,
    val mismatches: Int,
    val knownBits: Int,
) {
    val rankingScore: Int get() = mismatches * 20 - matches
}

object FormatSolver {
    internal fun copies(dimension: Int): List<List<Pair<Int, Int>>> {
        val copy1 = listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8,
            7 to 8, 8 to 8, 8 to 7,
            8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        )
        val copy2 = buildList {
            for (y in dimension - 1 downTo dimension - 7) add(8 to y)
            for (x in dimension - 8 until dimension) add(x to 8)
        }
        return listOf(copy1, copy2)
    }

    fun ranked(matrix: QrMatrix, ignoredKnownCoords: Set<Coord> = emptySet()): List<FormatCandidate> {
        val formatCopies = copies(matrix.dimension)

        val levels = listOf(
            ErrorCorrectionLevel.M to 0,
            ErrorCorrectionLevel.L to 1,
            ErrorCorrectionLevel.H to 2,
            ErrorCorrectionLevel.Q to 3,
        )
        return buildList {
            for ((ec, ecBits) in levels) for (mask in 0..7) {
                val code = formatCodeword((ecBits shl 3) or mask)
                var matches = 0
                var mismatches = 0
                var known = 0
                for (coords in formatCopies) {
                    for (i in 0 until 15) {
                        if (Coord(coords[i].first, coords[i].second) in ignoredKnownCoords) continue
                        val observed = matrix[coords[i].first, coords[i].second].value.bitOrNull() ?: continue
                        val expected = (code shr (14 - i)) and 1
                        known++
                        if (observed == expected) matches++ else mismatches++
                    }
                }
                add(FormatCandidate(ec, mask, code, matches, mismatches, known))
            }
        }.sortedWith(compareBy<FormatCandidate> { it.rankingScore }.thenByDescending { it.knownBits })
    }

    /**
     * Returns a format candidate that is safe to try alone first.
     *
     * A complete copy must be within BCH's three-error correction radius. When only one copy is
     * complete, every known bit in the damaged copy must agree with the corrected codeword.
     */
    fun trustedCandidate(matrix: QrMatrix): FormatCandidate? {
        val observed = copies(matrix.dimension).map { coords ->
            coords.map { (x, y) -> matrix[x, y].value.bitOrNull() }
        }
        val complete = observed.map { bits -> bits.all { it != null } }
        if (complete.none { it }) return null

        fun decode(bits: List<Int?>): Int? {
            if (bits.any { it == null }) return null
            return (0..31)
                .map { fiveBits -> fiveBits to hammingDistance(bits, formatCodeword(fiveBits)) }
                .minByOrNull { it.second }
                ?.takeIf { it.second <= 3 }
                ?.first
        }

        val decoded = observed.map(::decode)
        val fiveBits = when {
            complete.all { it } -> decoded[0]?.takeIf { it == decoded[1] }
            complete[0] -> decoded[0]?.takeIf { candidate -> agreesWithKnown(observed[1], formatCodeword(candidate)) }
            else -> decoded[1]?.takeIf { candidate -> agreesWithKnown(observed[0], formatCodeword(candidate)) }
        } ?: return null

        val codeword = formatCodeword(fiveBits)
        return ranked(matrix).firstOrNull { it.maskedCodeword == codeword }
    }

    /**
     * Returns a provisional fast-path candidate when the five format-information bits can be read
     * consistently even though neither copy is BCH-trusted. Two damaged information fields may
     * complement one another, but only when every overlapping known bit agrees and every available
     * BCH parity bit also agrees with the resulting candidate. This is deliberately weaker than
     * [trustedCandidate]: callers must fall back to all format candidates unless final payload
     * validation succeeds.
     */
    fun matchingInformationCandidate(matrix: QrMatrix): FormatCandidate? {
        if (trustedCandidate(matrix) != null) return null
        val observed = copies(matrix.dimension).map { coords ->
            coords.map { (x, y) -> matrix[x, y].value.bitOrNull() }
        }
        val information = observed.map { bits -> bits.take(5) }
        val merged = information[0].indices.map { i ->
            val first = information[0][i]
            val second = information[1][i]
            if (first != null && second != null && first != second) return null
            first ?: second ?: return null
        }
        val candidate = ranked(matrix).firstOrNull { format ->
            merged.indices.all { i -> merged[i] == ((format.maskedCodeword shr (14 - i)) and 1) }
        } ?: return null
        val hasBchConflict = observed.any { bits ->
            (5 until 15).any { i ->
                bits[i]?.let { it != ((candidate.maskedCodeword shr (14 - i)) and 1) } == true
            }
        }
        val bothInformationFieldsComplete = information.all { bits -> bits.none { it == null } }
        val bothInformationFieldsDamaged = information.all { bits -> bits.any { it == null } }
        return candidate.takeIf {
            (bothInformationFieldsComplete && information[0] == information[1] && hasBchConflict) ||
                (bothInformationFieldsDamaged && !hasBchConflict)
        }
    }

    private fun hammingDistance(bits: List<Int?>, codeword: Int): Int =
        bits.indices.count { i -> bits[i] != ((codeword shr (14 - i)) and 1) }

    private fun agreesWithKnown(bits: List<Int?>, codeword: Int): Boolean =
        bits.indices.all { i -> bits[i] == null || bits[i] == ((codeword shr (14 - i)) and 1) }

    /** BCH(15,5) + fixed QR format mask 0x5412. */
    fun formatCodeword(fiveBits: Int): Int {
        require(fiveBits in 0..31)
        var value = fiveBits shl 10
        val generator = 0x537
        while (bitLength(value) >= bitLength(generator)) {
            value = value xor (generator shl (bitLength(value) - bitLength(generator)))
        }
        return ((fiveBits shl 10) or value) xor 0x5412
    }

    private fun bitLength(v: Int): Int = if (v == 0) 0 else 32 - Integer.numberOfLeadingZeros(v)
}

data class PartialCodeword(
    val value: Int,
    val unknownBits: Int,
    val coordinates: List<Coord>,
) {
    val erased: Boolean get() = unknownBits != 0
}

data class CodewordExtraction(val codewords: List<PartialCodeword>)

object CodewordExtractor {
    fun extract(matrix: QrMatrix, versionNumber: Int, dataMask: Int): CodewordExtraction {
        val version = Version.getVersionForNumber(versionNumber)
        require(matrix.dimension == version.dimensionForVersion)
        val expectedBytes = version.totalCodewords
        val result = ArrayList<PartialCodeword>(expectedBytes)
        var current = 0
        var unknownMask = 0
        var bitCount = 0
        val coords = ArrayList<Coord>(8)
        var readingUp = true
        var j = matrix.dimension - 1
        outer@ while (j > 0) {
            if (j == 6) j--
            for (count in 0 until matrix.dimension) {
                val y = if (readingUp) matrix.dimension - 1 - count else count
                for (col in 0..1) {
                    val x = j - col
                    if (QrFunctionMask.isFunction(versionNumber, x, y)) continue
                    val bitIndex = bitCount
                    current = current shl 1
                    unknownMask = unknownMask shl 1
                    val observed = matrix[x, y].value.bitOrNull()
                    if (observed == null) {
                        unknownMask = unknownMask or 1
                    } else {
                        val unmasked = observed xor if (maskApplies(dataMask, x, y)) 1 else 0
                        current = current or unmasked
                    }
                    coords += Coord(x, y)
                    bitCount = bitIndex + 1
                    if (bitCount == 8) {
                        result += PartialCodeword(current and 0xFF, unknownMask and 0xFF, coords.toList())
                        current = 0
                        unknownMask = 0
                        bitCount = 0
                        coords.clear()
                        if (result.size == expectedBytes) break@outer
                    }
                }
            }
            readingUp = !readingUp
            j -= 2
        }
        require(result.size == expectedBytes) {
            "Read ${result.size} codewords, expected $expectedBytes for version $versionNumber"
        }
        return CodewordExtraction(result)
    }

    fun maskApplies(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> ((y + x) and 1) == 0
        1 -> (y and 1) == 0
        2 -> x % 3 == 0
        3 -> (y + x) % 3 == 0
        4 -> ((y / 2) + (x / 3)) % 2 == 0
        5 -> (y * x) % 2 + (y * x) % 3 == 0
        6 -> (((y * x) % 2 + (y * x) % 3) and 1) == 0
        7 -> ((((y + x) % 2) + ((y * x) % 3)) and 1) == 0
        else -> error("Invalid QR data mask: $mask")
    }
}
