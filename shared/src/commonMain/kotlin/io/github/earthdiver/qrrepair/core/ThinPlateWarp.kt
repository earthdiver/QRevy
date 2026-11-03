package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
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

/** Reject folds and extreme local stretching, preserving the base map's handedness. */
fun GridGeometry.hasUsableWarp(): Boolean {
    if (warp == null) return true
    val base = copy(warp = null, model = GeometryModel.PROJECTIVE)
    val last = (dimension - 1).toDouble()
    return try {
        (0..24).all { row -> (0..24).all { col ->
            val x = last * col / 24; val y = last * row / 24
            fun determinant(g: GridGeometry): Double {
                val p = g.moduleToImage(x, y)
                val dx = g.moduleToImage(x + .01, y) - p
                val dy = g.moduleToImage(x, y + .01) - p
                return dx.x * dy.y - dx.y * dy.x
            }
            val ratio = determinant(this) / determinant(base)
            val displacement = warp.offset(x / last, y / last)
            ratio.isFinite() && ratio in .15..6.0 &&
                displacement.distanceTo(PointD(0.0, 0.0)) <= base.localModulePitch(x, y) * 4.0
        } }
    } catch (_: IllegalArgumentException) { false }
}
