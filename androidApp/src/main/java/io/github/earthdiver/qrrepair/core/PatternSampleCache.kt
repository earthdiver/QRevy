package io.github.earthdiver.qrrepair.core

/** Exact-coordinate reuse within one pattern search; never shared between geometries or images. */
internal class PatternSampleCache private constructor(
    private val x: Axis,
    private val y: Axis,
    private val sampleCount: Int,
) {
    private class Axis(val coordinates: List<Double>, val indices: IntArray)
    // NaN caches an unavailable observation; infinity denotes a cell not evaluated yet.
    private val values = DoubleArray(x.coordinates.size * y.coordinates.size) { Double.POSITIVE_INFINITY }

    fun value(sample: Int, shiftX: Int, shiftY: Int, observe: (Double, Double) -> Double?): Double {
        val xi = x.indices[shiftX * sampleCount + sample]
        val yi = y.indices[shiftY * sampleCount + sample]
        val index = yi * x.coordinates.size + xi
        var value = values[index]
        if (value == Double.POSITIVE_INFINITY) {
            value = observe(x.coordinates[xi], y.coordinates[yi]) ?: Double.NaN
            values[index] = value
        }
        return value
    }

    companion object {
        // Bound transient memory even if coordinates do not overlap as expected.
        private const val MAX_CELLS = 65_536
        private const val MAX_AXIS_INDICES = 16_384

        fun create(xs: DoubleArray, ys: DoubleArray, nx: Int, ny: Int, step: Double): PatternSampleCache? {
            require(xs.size == ys.size && nx >= 0 && ny >= 0)
            if (xs.size.toLong() * (2L * nx + 1) > MAX_AXIS_INDICES ||
                ys.size.toLong() * (2L * ny + 1) > MAX_AXIS_INDICES) return null
            fun axis(samples: DoubleArray, radius: Int): Axis {
                val coordinates = ArrayList<Double>()
                val lookup = HashMap<Double, Int>()
                val indices = IntArray(samples.size * (2 * radius + 1))
                for (shift in -radius..radius) {
                    val delta = shift * step
                    for (i in samples.indices) {
                        // Keep the original addition and exact Double key: no rounding to a lattice.
                        val coordinate = samples[i] + delta
                        indices[(shift + radius) * samples.size + i] = lookup.getOrPut(coordinate) {
                            coordinates.add(coordinate)
                            coordinates.lastIndex
                        }
                    }
                }
                return Axis(coordinates, indices)
            }
            val x = axis(xs, nx)
            val y = axis(ys, ny)
            if (x.coordinates.size.toLong() * y.coordinates.size > MAX_CELLS) return null
            return PatternSampleCache(x, y, xs.size)
        }
    }
}
