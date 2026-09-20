package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** A module's central mean and its range under small sampling displacements. */
internal data class ModuleObservation(val mean: Double, val low: Double, val high: Double) {
    val allowance: Double get() = max(.75, max(mean - low, high - mean))
}

data class ThresholdPair(val blackMax: Int, val whiteMin: Int) {
    init { require(blackMax in 0..254 && whiteMin in 1..255 && blackMax < whiteMin) }
}

/** No regional thresholds: regions only reject unsupported function-pattern evidence. */
internal object GridThresholdEstimator {
    private val patterns = Array(40) { knownPattern(it + 1) }
    private val offsets = doubleArrayOf(-.16, 0.0, .16)
    private val shifts = doubleArrayOf(-.1, 0.0, .1)

    fun observe(image: LumaImage, geometry: GridGeometry, inverted: Boolean): Array<ModuleObservation?> =
        Array(geometry.dimension * geometry.dimension) { index ->
            val x = index % geometry.dimension
            val y = index / geometry.dimension
            var central = 0.0
            var low = Double.POSITIVE_INFINITY
            var high = Double.NEGATIVE_INFINITY
            var valid = true
            for (sy in shifts) for (sx in shifts) {
                var sum = 0.0
                for (dy in offsets) for (dx in offsets) {
                    val p = geometry.moduleToImage(x + sx + dx, y + sy + dy)
                    val value = bilinear(image, p.x, p.y)
                    if (value == null) valid = false else sum += if (inverted) 255.0 - value else value
                }
                val mean = sum / 9.0
                if (sx == 0.0 && sy == 0.0) central = mean
                low = minOf(low, mean)
                high = maxOf(high, mean)
            }
            if (valid) ModuleObservation(central, low, high) else null
        }

    private fun bilinear(image: LumaImage, x: Double, y: Double): Double? {
        // An observation outside the image has no evidence; do not invent edge pixels.
        if (!x.isFinite() || !y.isFinite() || !image.inBounds(x, y)) return null
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val x1 = minOf(x0 + 1, image.width - 1); val y1 = minOf(y0 + 1, image.height - 1)
        val fx = x - x0; val fy = y - y0
        return (image.luma(x0, y0) * (1 - fx) + image.luma(x1, y0) * fx) * (1 - fy) +
            (image.luma(x0, y1) * (1 - fx) + image.luma(x1, y1) * fx) * fy
    }

    fun estimate(version: Int, observations: Array<ModuleObservation?>): ThresholdPair? {
        val n = 17 + 4 * version
        require(observations.size == n * n)
        val labels = patterns[version - 1]
        fun region(i: Int) = ((i / n) * 3 / n) * 3 + (i % n) * 3 / n
        val black = mutableListOf<Int>(); val white = mutableListOf<Int>()
        for (r in 0..8) {
            val b = labels.indices.filter { labels[it] == 1 && observations[it] != null && region(it) == r }
            val w = labels.indices.filter { labels[it] == 0 && observations[it] != null && region(it) == r }
            if (b.size < 4 || w.size < 4) continue
            val gap = median(w.map { observations[it]!!.mean }) - median(b.map { observations[it]!!.mean })
            val allowance = median((b + w).map { observations[it]!!.allowance })
            val ordered = b.sumOf { bi -> w.count { wi -> observations[bi]!!.mean < observations[wi]!!.mean } }
            if (gap <= 2 * allowance || ordered.toDouble() / (b.size * w.size) < .8) continue
            black += b; white += w
        }
        if (black.size < 8 || white.size < 8) return null
        val blackUpper = black.maxOf { observations[it]!!.let { o -> o.mean + o.allowance } }
        val whiteLower = white.minOf { observations[it]!!.let { o -> o.mean - o.allowance } }
        var low = if (blackUpper < whiteLower) (blackUpper + whiteLower) / 2 else whiteLower
        var high = if (blackUpper < whiteLower) low else blackUpper
        middleInterval(observations)?.let { (left, right) ->
            fun inside(i: Int) = observations[i]!!.mean > left && observations[i]!!.mean < right
            val b = black.filter(::inside); val w = white.filter(::inside)
            val supportedBlack = b.size >= 8 && w.isEmpty() && b.map(::region).distinct().size >= 2
            val supportedWhite = w.size >= 8 && b.isEmpty() && w.map(::region).distinct().size >= 2
            if (!supportedBlack && !supportedWhite) { low = minOf(low, left.toDouble()); high = maxOf(high, right.toDouble()) }
        }
        // Classification rounds luminance once. Quantize both boundaries conservatively;
        // a separated pair has adjacent integers and therefore no artificial unknown bin.
        if (low == high) {
            val boundary = (low - .5).roundToInt().coerceIn(0, 254)
            return ThresholdPair(boundary, boundary + 1)
        }
        val blackMax = floor(low - .5).toInt().coerceIn(0, 254)
        return ThresholdPair(blackMax, ceil(high + .5).toInt().coerceIn(blackMax + 1, 255))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    private fun middleInterval(observations: Array<ModuleObservation?>): Pair<Int, Int>? {
        val histogram = DoubleArray(256)
        observations.forEach { it?.let { o -> histogram[o.mean.roundToInt().coerceIn(0, 255)]++ } }
        val counts = DoubleArray(257); val sums = DoubleArray(257); val squares = DoubleArray(257)
        for (i in 0..255) {
            counts[i + 1] = counts[i] + histogram[i]
            sums[i + 1] = sums[i] + histogram[i] * i
            squares[i + 1] = squares[i] + histogram[i] * i * i
        }
        fun cost(start: Int, end: Int): Double {
            val count = counts[end] - counts[start]
            if (count <= 0) return Double.POSITIVE_INFINITY
            val sum = sums[end] - sums[start]
            return squares[end] - squares[start] - sum * sum / count
        }
        var best = Double.POSITIVE_INFINITY; var a = 0; var b = 0
        for (first in 0..253) for (second in first + 1..254) {
            val score = cost(0, first + 1) + cost(first + 1, second + 1) + cost(second + 1, 256)
            if (score < best) { best = score; a = first; b = second }
        }
        // All cuts inside an empty histogram gap have identical SSE. Center the cuts so
        // smoothing tails from a stronger adjacent peak cannot masquerade as the middle peak.
        fun centeredCut(cut: Int): Int {
            var next = cut + 1
            while (next < 256 && histogram[next] == 0.0) next++
            return if (next < 256) (cut + next - 1) / 2 else cut
        }
        a = centeredCut(a); b = centeredCut(b)
        val minimum = max(6.0, .02 * counts[256])
        if (!best.isFinite() || counts[a + 1] < minimum || counts[b + 1] - counts[a + 1] < minimum ||
            counts[256] - counts[b + 1] < minimum) return null
        var left = 255; var right = 0; var minPeak = 255; var maxPeak = 0
        for (sigma in listOf(1, 2, 4)) {
            val sm = smooth(histogram, sigma)
            val p0 = (0..a).maxBy { sm[it] }; val p1 = (a + 1..b).maxBy { sm[it] }
            val p2 = (b + 1..255).maxBy { sm[it] }
            if (p0 + 2 >= p1 || p1 + 2 >= p2) return null
            val l = (p0..p1).minBy { sm[it] }; val r = (p1..p2).minBy { sm[it] }
            if (l >= p1 || r <= p1 || sm[l] > .5 * minOf(sm[p0], sm[p1]) || sm[r] > .5 * minOf(sm[p1], sm[p2])) return null
            left = minOf(left, l); right = maxOf(right, r)
            minPeak = minOf(minPeak, p1); maxPeak = maxOf(maxPeak, p1)
        }
        return if (maxPeak - minPeak <= 6) left to right else null
    }

    private fun smooth(histogram: DoubleArray, sigma: Int): DoubleArray {
        val radius = 4 * sigma
        val kernel = DoubleArray(radius * 2 + 1) { exp(-((it - radius) * (it - radius)).toDouble() / (2 * sigma * sigma)) }
        val sum = kernel.sum()
        return DoubleArray(256) { i ->
            kernel.indices.sumOf { k ->
                val j = i + k - radius
                val reflected = when { j < 0 -> -j; j > 255 -> 510 - j; else -> j }
                histogram[reflected] * kernel[k] / sum
            }
        }
    }

    /** Only fixed function cells; no format/version information, payload or user edits. */
    private fun knownPattern(version: Int): IntArray {
        val n = 17 + 4 * version
        val labels = IntArray(n * n) { -1 }
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (dy in -1..7) for (dx in -1..7) {
                val x = ox + dx; val y = oy + dy
                if (x !in 0 until n || y !in 0 until n) continue
                labels[y * n + x] = if ((dx in 0..6 && (dy == 0 || dy == 6)) ||
                    (dy in 0..6 && (dx == 0 || dx == 6)) || (dx in 2..4 && dy in 2..4)) 1 else 0
            }
        }
        val centers = Version.getVersionForNumber(version).alignmentPatternCenters
        for (cy in centers) for (cx in centers) {
            if (labels[cy * n + cx] != -1) continue
            for (dy in -2..2) for (dx in -2..2) labels[(cy + dy) * n + cx + dx] =
                if (abs(dx) == 2 || abs(dy) == 2 || (dx == 0 && dy == 0)) 1 else 0
        }
        for (i in 8 until n - 8) {
            if (labels[6 * n + i] == -1) labels[6 * n + i] = if (i % 2 == 0) 1 else 0
            if (labels[i * n + 6] == -1) labels[i * n + 6] = if (i % 2 == 0) 1 else 0
        }
        return labels
    }
}
