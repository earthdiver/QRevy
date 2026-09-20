package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class InitialThresholdEstimate(
    val blackMax: Int,
    val whiteMin: Int,
    val unknownColorClusters: List<UnknownColorCluster>,
    val usedThreeClassModel: Boolean,
)

/** Deterministic, Finder/geometry-independent initial classification derived once at import. */
object InitialThresholdEstimator {
    private const val MAX_SAMPLES = 500_000
    private const val MIN_CLASS_FRACTION = 0.02
    private const val MIN_CONTRAST = 24.0
    private const val MIN_ADJACENT_MEAN_GAP = 12.0
    private const val MIN_THREE_CLASS_SSE_IMPROVEMENT = 0.18
    private const val MIN_COLOR_CLUSTER_FRACTION = 0.005
    private const val COLOR_BIN_SIZE = 8.0
    private const val COLOR_MERGE_DELTA_E = 16.0

    fun estimate(bitmap: Bitmap): InitialThresholdEstimate? {
        val step = max(1, ceil(sqrt(bitmap.width.toDouble() * bitmap.height / MAX_SAMPLES)).toInt())
        val samples = ArrayList<Int>(minOf(MAX_SAMPLES, bitmap.width * bitmap.height))
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step step) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step step) samples += row[x] and 0xFFFFFF
        }
        if (samples.size < 64) return null

        val initialHistogram = histogram(samples)
        val initialTwo = bestTwoClass(initialHistogram) ?: return null
        if (!validOuterClasses(initialTwo, initialHistogram.sum())) return null
        val colorClusters = detectColorClusters(samples, initialTwo.means[0], initialTwo.means[1])
        val luminanceHistogram = histogram(samples.filterNot { rgb -> colorClusters.any { it.contains(rgb) } })
        val total = luminanceHistogram.sum()
        if (total < 64) return null
        val two = bestTwoClass(luminanceHistogram) ?: return null
        if (!validOuterClasses(two, total)) return null

        val three = bestThreeClass(luminanceHistogram)?.takeIf { candidate ->
            candidate.counts.all { it >= total * MIN_CLASS_FRACTION } &&
                candidate.means[1] - candidate.means[0] >= MIN_ADJACENT_MEAN_GAP &&
                candidate.means[2] - candidate.means[1] >= MIN_ADJACENT_MEAN_GAP &&
                two.sse > 0.0 && (two.sse - candidate.sse) / two.sse >= MIN_THREE_CLASS_SSE_IMPROVEMENT &&
                hasCentralPeakAndValleys(luminanceHistogram, candidate)
        }
        if (three != null) {
            val (darkBoundary, lightBoundary) = threeClassValleys(luminanceHistogram, three)
            return InitialThresholdEstimate(
                darkBoundary.coerceIn(0, 253),
                (lightBoundary + 1).coerceIn(1, 255),
                colorClusters,
                true,
            )
        }

        val smooth = smooth(luminanceHistogram)
        val darkCenter = two.means[0].roundToInt().coerceIn(0, 254)
        val lightCenter = two.means[1].roundToInt().coerceIn(darkCenter + 1, 255)
        val valley = valleyCenter(smooth, darkCenter, lightCenter)
        val darkPeak = (0..valley).maxOfOrNull { smooth[it] } ?: 0
        val lightPeak = (valley..255).maxOfOrNull { smooth[it] } ?: 0
        if (smooth[valley] <= minOf(darkPeak, lightPeak) * 0.02) {
            val boundary = valley.coerceIn(0, 254)
            return InitialThresholdEstimate(boundary, boundary + 1, colorClusters, false)
        }
        val shoulder = max(smooth[valley] * 3, (minOf(darkPeak, lightPeak) * 0.08).roundToInt())
        var black = valley
        while (black > darkCenter && smooth[black - 1] <= shoulder) black--
        var white = valley + 1
        while (white < lightCenter && smooth[white] <= shoulder) white++
        return InitialThresholdEstimate(black.coerceIn(0, 254), white.coerceIn(black + 1, 255), colorClusters, false)
    }

    private data class ColorBin(var count: Int, var lSum: Double, var aSum: Double, var bSum: Double) {
        val center get() = LabPoint(lSum / count, aSum / count, bSum / count)
    }

    private fun detectColorClusters(samples: List<Int>, darkMean: Double, lightMean: Double): List<UnknownColorCluster> {
        val bins = linkedMapOf<Triple<Int, Int, Int>, ColorBin>()
        samples.forEach { rgb ->
            val lum = luma(rgb)
            if (lum < darkMean + 12.0 || lum > lightMean - 12.0) return@forEach
            val lab = ColorLab.fromRgb(rgb)
            if (sqrt(lab.a * lab.a + lab.b * lab.b) < 12.0) return@forEach
            val key = Triple((lab.l / COLOR_BIN_SIZE).toInt(), (lab.a / COLOR_BIN_SIZE).toInt(), (lab.b / COLOR_BIN_SIZE).toInt())
            val bin = bins.getOrPut(key) { ColorBin(0, 0.0, 0.0, 0.0) }
            bin.count++; bin.lSum += lab.l; bin.aSum += lab.a; bin.bSum += lab.b
        }
        val remaining = bins.values.toMutableList()
        val minimum = max(16, (samples.size * MIN_COLOR_CLUSTER_FRACTION).roundToInt())
        val result = mutableListOf<UnknownColorCluster>()
        while (remaining.isNotEmpty() && result.size < 4) {
            val seed = remaining.maxByOrNull { it.count } ?: break
            var center = seed.center
            var members = remaining.filter { it.center.distanceTo(center) <= COLOR_MERGE_DELTA_E }
            val count = members.sumOf { it.count }
            if (count < minimum) break
            center = LabPoint(
                members.sumOf { it.lSum } / count,
                members.sumOf { it.aSum } / count,
                members.sumOf { it.bSum } / count,
            )
            members = remaining.filter { it.center.distanceTo(center) <= COLOR_MERGE_DELTA_E }
            val finalCount = members.sumOf { it.count }
            if (finalCount < minimum) { remaining.remove(seed); continue }
            val radius = members.maxOf { it.center.distanceTo(center) }.coerceIn(4.0, 24.0)
            result += UnknownColorCluster(
                (center.l * 100).roundToInt(),
                (center.a * 100).roundToInt(),
                (center.b * 100).roundToInt(),
                ((radius + 2.0).coerceAtMost(26.0) * 100).roundToInt(),
            )
            remaining.removeAll(members.toSet())
        }
        return result
    }

    private data class ClassModel(val thresholds: IntArray, val counts: IntArray, val means: DoubleArray, val sse: Double)

    private fun bestTwoClass(hist: IntArray): ClassModel? {
        val prefix = Prefix(hist)
        return (0..254).mapNotNull { model(prefix, intArrayOf(it)) }.minByOrNull { it.sse }
    }

    private fun bestThreeClass(hist: IntArray): ClassModel? {
        val prefix = Prefix(hist)
        var best: ClassModel? = null
        for (first in 0..253) for (second in first + 1..254) {
            val candidate = model(prefix, intArrayOf(first, second)) ?: continue
            if (best == null || candidate.sse < best.sse) best = candidate
        }
        return best
    }

    private class Prefix(hist: IntArray) {
        val count = LongArray(257)
        val sum = DoubleArray(257)
        val square = DoubleArray(257)
        init {
            for (i in hist.indices) {
                count[i + 1] = count[i] + hist[i]
                sum[i + 1] = sum[i] + i * hist[i]
                square[i + 1] = square[i] + i * i.toDouble() * hist[i]
            }
        }
    }

    private fun model(prefix: Prefix, thresholds: IntArray): ClassModel? {
        val ends = thresholds.map { it + 1 } + 256
        var start = 0
        val counts = IntArray(ends.size)
        val means = DoubleArray(ends.size)
        var sse = 0.0
        for (index in ends.indices) {
            val end = ends[index]
            val count = prefix.count[end] - prefix.count[start]
            if (count == 0L) return null
            val sum = prefix.sum[end] - prefix.sum[start]
            val square = prefix.square[end] - prefix.square[start]
            counts[index] = count.toInt()
            means[index] = sum / count
            sse += square - sum * sum / count
            start = end
        }
        return ClassModel(thresholds, counts, means, sse.coerceAtLeast(0.0))
    }

    private fun validOuterClasses(model: ClassModel, total: Int): Boolean =
        model.counts.all { it >= total * MIN_CLASS_FRACTION } && model.means.last() - model.means.first() >= MIN_CONTRAST

    private fun hasCentralPeakAndValleys(hist: IntArray, model: ClassModel): Boolean {
        val smooth = smooth(hist)
        val (left, right) = threeClassValleys(hist, model)
        if (right - left < 2) return false
        val centralPeak = (left + 1 until right).maxOf { smooth[it] }
        return centralPeak > max(smooth[left], smooth[right]) * 1.25
    }

    private fun threeClassValleys(hist: IntArray, model: ClassModel): Pair<Int, Int> {
        val smooth = smooth(hist)
        val dark = model.means[0].roundToInt().coerceIn(0, 253)
        val middle = model.means[1].roundToInt().coerceIn(dark + 1, 254)
        val light = model.means[2].roundToInt().coerceIn(middle + 1, 255)
        return valleyCenter(smooth, dark, middle) to valleyCenter(smooth, middle, light)
    }

    /** Keep both tones away from the boundary when an empty valley has many equal minima. */
    private fun valleyCenter(hist: IntArray, start: Int, end: Int): Int {
        val midpoint = (start + end) / 2.0
        val minimum = (start..end).minWithOrNull(
            compareBy<Int> { hist[it] }.thenBy { abs(it - midpoint) },
        )!!
        // Center one contiguous plateau, never average across an intervening peak.
        var left = minimum
        var right = minimum
        while (left > start && hist[left - 1] == hist[minimum]) left--
        while (right < end && hist[right + 1] == hist[minimum]) right++
        return (left + right) / 2
    }

    private fun histogram(samples: List<Int>): IntArray = IntArray(256).also { hist -> samples.forEach { hist[luma(it)]++ } }
    private fun smooth(hist: IntArray): IntArray = IntArray(256) { index ->
        val range = max(0, index - 2)..minOf(255, index + 2)
        range.sumOf { hist[it] } / range.count()
    }
    private fun luma(rgb: Int): Int {
        val r = rgb ushr 16 and 0xFF
        val g = rgb ushr 8 and 0xFF
        val b = rgb and 0xFF
        return (299 * r + 587 * g + 114 * b + 500) / 1000
    }
}
