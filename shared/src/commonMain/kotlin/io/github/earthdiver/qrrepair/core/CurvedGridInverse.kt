package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private const val MAX_INVERSE_EVALUATIONS = 256
private const val MAX_LM_STEPS = 48
private const val RESIDUAL_TOLERANCE_SQUARED = 1e-10

/** Invert the fixed sampling map with bounded Levenberg-Marquardt steps. */
internal fun invertCurvedGrid(
    base: Homography, warp: ThinPlateWarp, dimension: Int, initial: PointD, target: PointD,
): PointD {
    val failure = PointD(Double.NaN, Double.NaN)
    if (!target.x.isFinite() || !target.y.isFinite()) return failure
    val last = (dimension - 1).toDouble()
    var evaluations = 0

    // Residual x/y followed by the two rows of the full source-pixel Jacobian.
    fun evaluate(point: PointD, values: DoubleArray): Double {
        if (evaluations >= MAX_INVERSE_EVALUATIONS) return Double.POSITIVE_INFINITY
        evaluations++
        if (!point.x.isFinite() || !point.y.isFinite()) return Double.POSITIVE_INFINITY
        val divisor = base.h31 * point.x + base.h32 * point.y + 1.0
        if (!divisor.isFinite() || abs(divisor) <= 1e-12) return Double.POSITIVE_INFINITY
        val x = (base.h11 * point.x + base.h12 * point.y + base.h13) / divisor
        val y = (base.h21 * point.x + base.h22 * point.y + base.h23) / divisor
        warp.offsetAndJacobian(point.x / last, point.y / last, values)
        values[0] = (x + values[0]) - target.x
        values[1] = (y + values[1]) - target.y
        values[2] = (base.h11 - x * base.h31) / divisor + values[2] / last
        values[3] = (base.h12 - x * base.h32) / divisor + values[3] / last
        values[4] = (base.h21 - y * base.h31) / divisor + values[4] / last
        values[5] = (base.h22 - y * base.h32) / divisor + values[5] / last
        if (values.any { !it.isFinite() }) return Double.POSITIVE_INFINITY
        return values[0] * values[0] + values[1] * values[1]
    }

    fun minimize(start: PointD): PointD? {
        var point = start
        var current = DoubleArray(6)
        var trial = DoubleArray(6)
        var cost = evaluate(point, current)
        if (!cost.isFinite()) return null
        var damping = .001
        repeat(MAX_LM_STEPS) {
            if (cost < RESIDUAL_TOLERANCE_SQUARED) return point
            if (evaluations >= MAX_INVERSE_EVALUATIONS) return null
            // Column scaling gives (J'J + damping * diag(J'J)) without squaring
            // source-pixel scales or rejecting a valid but compressed direction.
            val sx = hypot(current[2], current[4])
            val sy = hypot(current[3], current[5])
            if (sx <= 0.0 || sy <= 0.0 || !sx.isFinite() || !sy.isFinite()) return null
            val ax = current[2] / sx; val ay = current[4] / sx
            val bx = current[3] / sy; val by = current[5] / sy
            val cross = (ax * bx + ay * by).coerceIn(-1.0, 1.0)
            val diagonal = 1.0 + damping
            val determinant = (diagonal - cross) * (diagonal + cross)
            val rx = -(ax * current[0] + ay * current[1])
            val ry = -(bx * current[0] + by * current[1])
            val step = PointD((diagonal * rx - cross * ry) / determinant / sx,
                (diagonal * ry - cross * rx) / determinant / sy)
            val candidate = point + step
            val next = evaluate(candidate, trial)
            if (next < cost) {
                point = candidate
                cost = next
                val swap = current; current = trial; trial = swap
                damping = max(1e-7, .4 * damping)
            } else {
                damping *= 5.0
                if (damping > 1e6) return null
            }
        }
        return point.takeIf { cost < RESIDUAL_TOLERANCE_SQUARED }
    }

    minimize(initial)?.let { return it }
    // Only failed inversions pay for alternate starts. Seeds, iterations and rejected
    // trials share one evaluation allowance, including for taps outside the symbol.
    val scratch = DoubleArray(6)
    val seeds = mutableListOf<Pair<Double, PointD>>()
    for (row in 0..8) for (col in 0..8) {
        val point = PointD(-.5 + dimension * col / 8.0, -.5 + dimension * row / 8.0)
        val cost = evaluate(point, scratch)
        if (cost.isFinite()) seeds += cost to point
    }
    for ((_, seed) in seeds.sortedBy { it.first }.take(3)) minimize(seed)?.let { return it }
    return failure
}
