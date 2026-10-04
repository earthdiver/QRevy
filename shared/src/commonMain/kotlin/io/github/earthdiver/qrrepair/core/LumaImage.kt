package io.github.earthdiver.qrrepair.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class LumaImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Optional packed 0xRRGGBB pixels retained for color-marker classification. */
    val rgbPixels: IntArray? = null,
) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(rgbPixels == null || rgbPixels.size == pixels.size)
    }

    val threshold: Int by lazy { otsuThreshold() }

    fun inBounds(x: Double, y: Double, margin: Double = 0.0): Boolean =
        x >= margin && y >= margin && x < width - margin && y < height - margin

    fun luma(x: Int, y: Int): Int = pixels[y * width + x]

    fun colorFraction(cx: Double, cy: Double, radius: Double, matches: (Int) -> Boolean): Double {
        val rgb = rgbPixels ?: return 0.0
        if (!inBounds(cx, cy)) return 0.0
        val r = max(0, radius.roundToInt())
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(width - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(height - 1, cy.roundToInt() + r)
        var marked = 0
        var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (matches(rgb[y * width + x])) marked++
            total++
        }
        return if (total == 0) 0.0 else marked.toDouble() / total
    }

    fun sampleMean(cx: Double, cy: Double, radius: Double): Double? {
        if (!inBounds(cx, cy)) return null
        val r = max(0, radius.roundToInt())
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(width - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(height - 1, cy.roundToInt() + r)
        var sum = 0L
        var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            sum += luma(x, y)
            n++
        }
        return if (n == 0) null else sum.toDouble() / n
    }

    fun isBlack(x: Int, y: Int): Boolean = luma(x, y) <= threshold

    /** One threshold inside a clockwise convex boundary; pixels outside it provide no evidence. */
    internal fun thresholdInside(boundary: ImageQuad): Int? {
        val points = boundary.asList()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val x0 = ceil(points.minOf { it.x }).coerceAtLeast(0.0).toInt()
        val x1 = floor(points.maxOf { it.x }).coerceAtMost((width - 1).toDouble()).toInt()
        val y0 = ceil(points.minOf { it.y }).coerceAtLeast(0.0).toInt()
        val y1 = floor(points.maxOf { it.y }).coerceAtMost((height - 1).toDouble()).toInt()
        val hist = IntArray(256)
        for (y in y0..y1) for (x in x0..x1) {
            if ((0..3).all { i ->
                    val a = points[i]; val b = points[(i + 1) % 4]
                    (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0.0
                }) hist[luma(x, y).coerceIn(0, 255)]++
        }
        return if (hist.count { it > 0 } < 2) null else otsuThreshold(hist)
    }

    private fun otsuThreshold(): Int {
        val hist = IntArray(256)
        pixels.forEach { hist[it.coerceIn(0, 255)]++ }
        return otsuThreshold(hist)
    }

    private fun otsuThreshold(hist: IntArray): Int {
        val total = hist.sum().toDouble()
        var sum = 0.0
        for (i in hist.indices) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var maxVariance = -1.0
        var best = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val d = mB - mF
            val between = wB * wF * d * d
            if (between > maxVariance) {
                maxVariance = between
                best = t
            }
        }
        return best
    }
}
