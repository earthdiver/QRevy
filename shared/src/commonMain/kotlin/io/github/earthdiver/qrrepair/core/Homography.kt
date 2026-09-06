package io.github.earthdiver.qrrepair.core

import kotlin.math.abs

/** 2D projective transform with h33 normalized to 1. */
data class Homography(
    val h11: Double, val h12: Double, val h13: Double,
    val h21: Double, val h22: Double, val h23: Double,
    val h31: Double, val h32: Double,
) {
    fun map(p: PointD): PointD = map(p.x, p.y)

    fun map(x: Double, y: Double): PointD {
        val w = h31 * x + h32 * y + 1.0
        require(abs(w) > 1e-12) { "Homography maps point to infinity" }
        return PointD(
            (h11 * x + h12 * y + h13) / w,
            (h21 * x + h22 * y + h23) / w,
        )
    }

    fun inverse(): Homography {
        val a = h11; val b = h12; val c = h13
        val d = h21; val e = h22; val f = h23
        val g = h31; val h = h32; val i = 1.0
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val D = -(b * i - c * h)
        val E = a * i - c * g
        val F = -(a * h - b * g)
        val G = b * f - c * e
        val H = -(a * f - c * d)
        val I = a * e - b * d
        val det = a * A + b * B + c * C
        require(abs(det) > 1e-12) { "Singular homography" }
        val scale = 1.0 / I.coerceUnlessNearZero()
        // Normalize so h33 == 1. Cofactor transpose / determinant shares a common scale;
        // divide all elements by the resulting bottom-right element instead.
        return Homography(
            A * scale, D * scale, G * scale,
            B * scale, E * scale, H * scale,
            C * scale, F * scale,
        )
    }

    private fun Double.coerceUnlessNearZero(): Double {
        require(abs(this) > 1e-12) { "Inverse homography cannot be normalized" }
        return this
    }

    companion object {
        /** Solve four arbitrary point correspondences using Gaussian elimination. */
        fun fromCorrespondences(source: List<PointD>, destination: List<PointD>): Homography {
            require(source.size == 4 && destination.size == 4)
            val a = Array(8) { DoubleArray(9) }
            for (k in 0 until 4) {
                val x = source[k].x
                val y = source[k].y
                val u = destination[k].x
                val v = destination[k].y
                val r = 2 * k
                a[r][0] = x; a[r][1] = y; a[r][2] = 1.0
                a[r][6] = -u * x; a[r][7] = -u * y; a[r][8] = u
                a[r + 1][3] = x; a[r + 1][4] = y; a[r + 1][5] = 1.0
                a[r + 1][6] = -v * x; a[r + 1][7] = -v * y; a[r + 1][8] = v
            }
            for (col in 0 until 8) {
                var pivot = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                require(abs(a[pivot][col]) > 1e-10) { "Degenerate projective correspondences" }
                if (pivot != col) {
                    val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
                }
                val div = a[col][col]
                for (j in col until 9) a[col][j] /= div
                for (r in 0 until 8) {
                    if (r == col) continue
                    val factor = a[r][col]
                    if (factor == 0.0) continue
                    for (j in col until 9) a[r][j] -= factor * a[col][j]
                }
            }
            val x = DoubleArray(8) { a[it][8] }
            return Homography(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7])
        }
    }
}

/** Affine transform fit from three non-collinear correspondences. */
data class Affine2D(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double,
) {
    fun map(p: PointD): PointD = PointD(a * p.x + b * p.y + c, d * p.x + e * p.y + f)

    companion object {
        fun fromThree(source: List<PointD>, destination: List<PointD>): Affine2D {
            require(source.size == 3 && destination.size == 3)
            val m = Array(3) { r -> doubleArrayOf(source[r].x, source[r].y, 1.0) }
            fun solve(values: DoubleArray): DoubleArray {
                val a = Array(3) { r -> doubleArrayOf(m[r][0], m[r][1], m[r][2], values[r]) }
                for (col in 0..2) {
                    var pivot = col
                    for (r in col + 1..2) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                    require(abs(a[pivot][col]) > 1e-10) { "Degenerate affine correspondences" }
                    val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
                    val div = a[col][col]
                    for (j in col..3) a[col][j] /= div
                    for (r in 0..2) if (r != col) {
                        val factor = a[r][col]
                        for (j in col..3) a[r][j] -= factor * a[col][j]
                    }
                }
                return DoubleArray(3) { a[it][3] }
            }
            val sx = solve(DoubleArray(3) { destination[it].x })
            val sy = solve(DoubleArray(3) { destination[it].y })
            return Affine2D(sx[0], sx[1], sx[2], sy[0], sy[1], sy[2])
        }
    }
}
