package io.github.earthdiver.qrrepair.core

import kotlin.math.floor

/** Platform-independent opaque RGB resampling. Rows are read on demand to bound working memory. */
object ImageResampling {
    fun importSample(width: Int, height: Int, limit: Int = 2600): Int {
        require(width > 0 && height > 0 && limit > 0)
        var sample = 1
        while (maxOf(width, height).toDouble() / sample > limit) sample *= 2
        return sample
    }

    fun sampledSize(size: Int, sample: Int): Int {
        require(size > 0 && sample > 0)
        return (size - 1) / sample + 1
    }

    fun onWhite(argb: Int): Int {
        val alpha = argb ushr 24
        fun channel(shift: Int) = (((argb ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Integer block means, half-up rounding, and available pixels only at odd edges. */
    fun downsample(width: Int, height: Int, sample: Int, readRow: (Int, IntArray) -> Unit): IntArray {
        require(width > 0 && height > 0 && sample > 0)
        val ow = sampledSize(width, sample)
        val oh = sampledSize(height, sample)
        require(ow.toLong() * oh <= Int.MAX_VALUE)
        val result = IntArray(ow * oh)
        val row = IntArray(width)
        val sums = LongArray(ow * 3)
        for (oy in 0 until oh) {
            sums.fill(0)
            val firstY = oy * sample
            val rows = minOf(sample, height - firstY)
            for (y in firstY until firstY + rows) {
                readRow(y, row)
                for (x in 0 until width) {
                    val p = onWhite(row[x])
                    val i = x / sample * 3
                    sums[i] += (p ushr 16) and 255
                    sums[i + 1] += (p ushr 8) and 255
                    sums[i + 2] += p and 255
                }
            }
            for (x in 0 until ow) {
                val count = rows.toLong() * minOf(sample, width - x * sample)
                fun channel(c: Int) = ((sums[x * 3 + c] + count / 2) / count).toInt()
                result[oy * ow + x] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
            }
        }
        return result
    }

    /** Center-aligned bilinear interpolation with clamped edges and half-up channel rounding. */
    fun resize(width: Int, height: Int, outWidth: Int, outHeight: Int,
               readRow: (Int, IntArray) -> Unit): IntArray {
        require(width > 0 && height > 0 && outWidth > 0 && outHeight > 0)
        require(outWidth.toLong() * outHeight <= Int.MAX_VALUE)
        val result = IntArray(outWidth * outHeight)
        val top = IntArray(width)
        val bottom = IntArray(width)
        for (y in 0 until outHeight) {
            val sy = ((y + .5) * height / outHeight - .5).coerceIn(0.0, height - 1.0)
            val y0 = floor(sy).toInt()
            val fy = sy - y0
            readRow(y0, top)
            readRow(minOf(y0 + 1, height - 1), bottom)
            for (x in 0 until width) { top[x] = onWhite(top[x]); bottom[x] = onWhite(bottom[x]) }
            for (x in 0 until outWidth) {
                val sx = ((x + .5) * width / outWidth - .5).coerceIn(0.0, width - 1.0)
                val x0 = floor(sx).toInt()
                val x1 = minOf(x0 + 1, width - 1)
                val fx = sx - x0
                fun channel(shift: Int): Int {
                    fun c(p: Int) = (p ushr shift) and 255
                    val a = c(top[x0]) * (1 - fx) + c(top[x1]) * fx
                    val b = c(bottom[x0]) * (1 - fx) + c(bottom[x1]) * fx
                    return floor(a * (1 - fy) + b * fy + .5).toInt().coerceIn(0, 255)
                }
                result[y * outWidth + x] = (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        return result
    }
}
