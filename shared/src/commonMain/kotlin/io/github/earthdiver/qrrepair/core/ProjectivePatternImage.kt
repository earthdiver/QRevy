package io.github.earthdiver.qrrepair.core

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A bounded luminance crop. All levels use source-image coordinates, including the derivatives. */
internal class ProjectivePatternImage private constructor(
    private val values: FloatArray,
    private val width: Int,
    private val height: Int,
    private val left: Int,
    private val top: Int,
    private val step: Int,
) {
    fun sample(x: Double, y: Double, valueAndGradient: DoubleArray): Boolean {
        val u = (x - left) / step
        val v = (y - top) / step
        if (!(u >= 0 && u < width - 1 && v >= 0 && v < height - 1)) return false
        val ix = u.toInt()
        val iy = v.toInt()
        val fx = u - ix
        val fy = v - iy
        val i = iy * width + ix
        val a = values[i].toDouble()
        val b = values[i + 1].toDouble()
        val c = values[i + width].toDouble()
        val d = values[i + width + 1].toDouble()
        valueAndGradient[0] = (1 - fy) * (a + fx * (b - a)) + fy * (c + fx * (d - c))
        valueAndGradient[1] = ((1 - fy) * (b - a) + fy * (d - c)) / step
        valueAndGradient[2] = ((1 - fx) * (c - a) + fx * (d - b)) / step
        return true
    }

    fun blurred(sigmaPixels: Double, stopped: () -> Boolean): ProjectivePatternImage? {
        val sigma = sigmaPixels / step
        if (sigma < .25) return this
        val radius = ceil(3 * sigma).toInt()
        val kernel = DoubleArray(2 * radius + 1) { exp(-.5 * ((it - radius) / sigma) * ((it - radius) / sigma)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val horizontal = FloatArray(values.size)
        val result = FloatArray(values.size)
        for (y in 0 until height) {
            if (stopped()) return null
            for (x in 0 until width) {
                var mean = 0.0
                for (k in kernel.indices) mean += kernel[k] * values[y * width + (x + k - radius).coerceIn(0, width - 1)]
                horizontal[y * width + x] = mean.toFloat()
            }
        }
        for (y in 0 until height) {
            if (stopped()) return null
            for (x in 0 until width) {
                var mean = 0.0
                for (k in kernel.indices) mean += kernel[k] * horizontal[(y + k - radius).coerceIn(0, height - 1) * width + x]
                result[y * width + x] = mean.toFloat()
            }
        }
        return ProjectivePatternImage(result, width, height, left, top, step)
    }

    companion object {
        fun crop(image: LumaImage, quad: ImageQuad, pitch: Double, inverted: Boolean, stopped: () -> Boolean): ProjectivePatternImage? {
            val points = quad.asList()
            val margin = max(6.0, 5 * pitch)
            val left = max(0, floor(points.minOf { it.x } - margin).toInt())
            val top = max(0, floor(points.minOf { it.y } - margin).toInt())
            val right = min(image.width - 1, ceil(points.maxOf { it.x } + margin).toInt())
            val bottom = min(image.height - 1, ceil(points.maxOf { it.y } + margin).toInt())
            if (left >= right || top >= bottom) return null
            // Retain about eight samples per module on large inputs and bound temporary storage.
            val step = maxOf(1, (pitch / 8).toInt(), ceil(max(right - left, bottom - top) / 1400.0).toInt())
            val width = (right - left) / step + 1
            val height = (bottom - top) / step + 1
            if (width < 3 || height < 3) return null
            val histogram = IntArray(256)
            val pixels = FloatArray(width * height)
            for (y in 0 until height) {
                if (stopped()) return null
                for (x in 0 until width) {
                    val raw = image.luma(left + x * step, top + y * step).coerceIn(0, 255)
                    val value = if (inverted) 255 - raw else raw
                    histogram[value]++
                    pixels[y * width + x] = value.toFloat()
                }
            }
            fun percentile(fraction: Double): Int {
                var count = 0
                for (value in histogram.indices) {
                    count += histogram[value]
                    if (count > (pixels.size - 1) * fraction) return value
                }
                return histogram.lastIndex
            }
            val low = percentile(.03)
            val range = max(40, percentile(.97) - low)
            for (i in pixels.indices) pixels[i] = ((pixels[i] - low) / range).coerceIn(0f, 1f)
            return ProjectivePatternImage(pixels, width, height, left, top, step)
        }
    }
}
