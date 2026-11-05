package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/** Normalized module-center position and residual source-pixel displacement. */
data class WarpLandmark(val position: PointD, val displacement: PointD, val smoothing: Double = 1e-5) {
    init {
        require(position.x.isFinite() && position.x in 0.0..1.0 && position.y.isFinite() && position.y in 0.0..1.0)
        require(displacement.x.isFinite() && abs(displacement.x) <= 1e6 && displacement.y.isFinite() && abs(displacement.y) <= 1e6)
        require(smoothing.isFinite() && smoothing in 1e-6..1.0)
    }
}

/** Bounded, reproducible TPS residual over a projective map. No source pixels are replaced. */
class ThinPlateWarp(landmarks: List<WarpLandmark>) {
    val landmarks: List<WarpLandmark> = landmarks.toList()
    init {
        require(this.landmarks.size in 3..64)
        require(this.landmarks.map { it.position }.distinct().size == this.landmarks.size)
    }
    private val coefficients: Array<DoubleArray> by lazy { fit() }

    fun offset(x: Double, y: Double): PointD {
        val n = landmarks.size
        val c = coefficients
        var dx = c[n][0] + c[n + 1][0] * x + c[n + 2][0] * y
        var dy = c[n][1] + c[n + 1][1] * x + c[n + 2][1] * y
        for (i in 0 until n) {
            val p = landmarks[i].position
            val k = kernel(x - p.x, y - p.y)
            dx += c[i][0] * k; dy += c[i][1] * k
        }
        return PointD(dx, dy)
    }

    /** Offset followed by its x/y derivatives, evaluated together without repeating kernels. */
    internal fun offsetAndJacobian(x: Double, y: Double, result: DoubleArray) {
        val n = landmarks.size
        val c = coefficients
        result[0] = c[n][0] + c[n + 1][0] * x + c[n + 2][0] * y
        result[1] = c[n][1] + c[n + 1][1] * x + c[n + 2][1] * y
        result[2] = c[n + 1][0]; result[3] = c[n + 2][0]
        result[4] = c[n + 1][1]; result[5] = c[n + 2][1]
        for (i in 0 until n) {
            val dx = x - landmarks[i].position.x; val dy = y - landmarks[i].position.y
            val r2 = dx * dx + dy * dy
            if (r2 < 1e-24) continue
            val log = ln(r2)
            val k = .5 * r2 * log
            val gx = dx * (log + 1.0); val gy = dy * (log + 1.0)
            result[0] += c[i][0] * k; result[1] += c[i][1] * k
            result[2] += c[i][0] * gx; result[3] += c[i][0] * gy
            result[4] += c[i][1] * gx; result[5] += c[i][1] * gy
        }
    }

    private fun fit(): Array<DoubleArray> {
        val n = landmarks.size; val size = n + 3
        val a = Array(size) { DoubleArray(size + 2) }
        for (i in 0 until n) {
            val p = landmarks[i]
            for (j in 0 until n) {
                val q = landmarks[j].position
                a[i][j] = kernel(p.position.x - q.x, p.position.y - q.y)
            }
            a[i][i] += p.smoothing
            for ((j, v) in listOf(1.0, p.position.x, p.position.y).withIndex()) {
                a[i][n + j] = v; a[n + j][i] = v
            }
            a[i][size] = p.displacement.x; a[i][size + 1] = p.displacement.y
        }
        for (col in 0 until size) {
            val pivot = (col until size).maxBy { abs(a[it][col]) }
            require(abs(a[pivot][col]) > 1e-12) { "Degenerate warp landmarks" }
            val row = a[pivot]; a[pivot] = a[col]; a[col] = row
            val divisor = row[col]
            for (j in col until size + 2) row[j] /= divisor
            for (r in 0 until size) if (r != col) {
                val factor = a[r][col]
                for (j in col until size + 2) a[r][j] -= factor * row[j]
            }
        }
        return Array(size) { doubleArrayOf(a[it][size], a[it][size + 1]) }
    }

    override fun equals(other: Any?): Boolean = other is ThinPlateWarp && landmarks == other.landmarks
    override fun hashCode(): Int = landmarks.hashCode()

    private fun kernel(x: Double, y: Double): Double {
        val r2 = x * x + y * y
        return if (r2 < 1e-24) 0.0 else .5 * r2 * ln(r2)
    }
}

/** Check the mapping once per immutable geometry, including the full area of edge cells. */
fun GridGeometry.hasUsableWarp(): Boolean = usableWarp

internal fun GridGeometry.validateWarp(): Boolean {
    val deformation = warp ?: return true
    val last = (dimension - 1).toDouble()
    return try {
        val base = Homography.fromCorrespondences(
            listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
            requireNotNull(imageQuad).asList(),
        )
        val values = DoubleArray(6)
        fun usable(x: Double, y: Double): Boolean {
            val divisor = base.h31 * x + base.h32 * y + 1.0
            if (!divisor.isFinite() || abs(divisor) < 1e-12) return false
            val point = base.map(x, y)
            val ax = (base.h11 - point.x * base.h31) / divisor
            val ay = (base.h12 - point.x * base.h32) / divisor
            val bx = (base.h21 - point.y * base.h31) / divisor
            val by = (base.h22 - point.y * base.h32) / divisor
            deformation.offsetAndJacobian(x / last, y / last, values)
            val det = (ax + values[2] / last) * (by + values[5] / last) -
                (ay + values[3] / last) * (bx + values[4] / last)
            val ratio = det / (ax * by - ay * bx)
            val pitch = (point.distanceTo(base.map(x + 1.0, y)) + point.distanceTo(base.map(x, y + 1.0))) / 2.0
            return ratio.isFinite() && ratio in .15..6.0 && hypot(values[0], values[1]) <= pitch * 4.0
        }
        // Resolve the neighborhoods of closely spaced knots as well as the module grid.
        // At most 64 landmarks add 81 checks each, independent of how close the knots are.
        val knots = deformation.landmarks.map { it.position * last }
        for ((index, knot) in knots.withIndex()) {
            var radius = .5
            for (other in knots.indices) if (other != index) {
                radius = minOf(radius, knot.distanceTo(knots[other]))
            }
            val step = radius / 4.0
            // An unresolved neighborhood must not be accepted as a flat sampled region.
            if (step <= 0.0 || knot.x + step == knot.x || knot.y + step == knot.y) return false
            for (row in -4..4) for (col in -4..4) {
                if (!usable(knot.x + col * step, knot.y + row * step)) return false
            }
        }
        // Half-module spacing covers centers, edges and corners at every QR version.
        for (row in -1..2 * dimension - 1) for (col in -1..2 * dimension - 1) {
            if (!usable(col * .5, row * .5)) return false
        }
        true
    } catch (_: IllegalArgumentException) { false }
}
