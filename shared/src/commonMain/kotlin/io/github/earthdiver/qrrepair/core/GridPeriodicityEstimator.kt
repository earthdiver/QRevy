package io.github.earthdiver.qrrepair.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class GridPeriodicityEstimate(
    val version: Int,
    /** Centers of the four outermost QR modules, in source-image coordinates. */
    val moduleCenterQuad: ImageQuad,
    val modulePitch: Double,
    /** Explanatory agreement score, not a probability. */
    val score: Double,
)

/**
 * Finds the repeated module grid before attempting to recognize finder patterns.
 *
 * QR contents are not periodic, but every transition is constrained to one of two orthogonal,
 * equally spaced sets of module boundaries.  The circular phase agreement below measures that
 * property directly and therefore still works when all three finders are incomplete.
 */
object GridPeriodicityEstimator {
    private data class Edge(val x: Double, val y: Double, val gx: Double, val gy: Double, val weight: Double)
    internal data class Period(val pitch: Double, val agreement: Double, val boundaryPhase: Double)
    private data class DarkProjection(
        val u: List<Double>,
        val v: List<Double>,
        val minU: Double,
        val maxU: Double,
        val minV: Double,
        val maxV: Double,
    )

    fun estimate(image: LumaImage, cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        trace: GridEstimationTrace? = null): GridPeriodicityEstimate? {
        cancellation.check()
        if (minOf(image.width, image.height) < 42) return null
        val stride = max(1, max(image.width, image.height) / 700)
        val edges = ArrayList<Edge>()
        val orientation = DoubleArray(90)
        trace.measure("EDGES") {
            for (y in 1 until image.height - 1 step stride) for (x in 1 until image.width - 1 step stride) {
                if (x == 1) cancellation.check()
                val gx = (image.luma(x + 1, y) - image.luma(x - 1, y)).toDouble()
                val gy = (image.luma(x, y + 1) - image.luma(x, y - 1)).toDouble()
                val magnitude = hypot(gx, gy)
                if (magnitude < 36.0) continue
                val angle = ((atan2(gy, gx) * 180.0 / PI) % 180.0 + 180.0) % 180.0
                val bin = (angle / 2.0).roundToInt() % 90
                orientation[bin] += magnitude
                edges += Edge(x.toDouble(), y.toDouble(), gx, gy, magnitude)
            }
        }
        if (edges.size < 80) return null

        // A QR contributes two perpendicular edge-normal families. Requiring both suppresses
        // strong single-direction structures such as text lines and fences.
        val axisBin = orientation.indices.maxByOrNull { orientation[it] + orientation[(it + 45) % 90] } ?: return null
        val axisAngle = axisBin * 2.0 * PI / 180.0
        val ux = if (axisBin == 45) 0.0 else cos(axisAngle)
        val uy = if (axisBin == 45) 1.0 else sin(axisAngle)
        val vx = -uy; val vy = ux
        val uCoordinates = ArrayList<Pair<Double, Double>>()
        val vCoordinates = ArrayList<Pair<Double, Double>>()
        for (edge in edges) {
            val magnitude = edge.weight
            val alongU = abs(edge.gx * ux + edge.gy * uy) / magnitude
            val alongV = abs(edge.gx * vx + edge.gy * vy) / magnitude
            if (alongU >= .82) uCoordinates += (edge.x * ux + edge.y * uy) to (magnitude * alongU)
            if (alongV >= .82) vCoordinates += (edge.x * vx + edge.y * vy) to (magnitude * alongV)
        }
        if (uCoordinates.size < 30 || vCoordinates.size < 30) return null

        val maxPitch = minOf(image.width, image.height).toDouble() / 20.0
        val uPeriods = trace.measure("U_PERIODS") { periods(uCoordinates, maxPitch, cancellation) }
        val vPeriods = trace.measure("V_PERIODS") { periods(vCoordinates, maxPitch, cancellation) }
        if (uPeriods.isEmpty() || vPeriods.isEmpty()) return null

        val dark = trace.measure("DARK_SUPPORT") {
            projectedDark(image, ux, uy, vx, vy, stride, cancellation)
        } ?: return null
        val spanU = dark.maxU - dark.minU + 1.0
        val spanV = dark.maxV - dark.minV + 1.0
        data class Choice(val u: Period, val v: Period, val version: Int, val cost: Double, val agreement: Double)
        var best: Choice? = null
        for (up in uPeriods) for (vp in vPeriods) {
            if (abs(up.pitch - vp.pitch) / max(up.pitch, vp.pitch) > .14) continue
            val pitch = (up.pitch + vp.pitch) / 2.0
            for (version in 1..40) {
                val dimension = 17 + 4 * version
                val fit = (abs(spanU / pitch - dimension) + abs(spanV / pitch - dimension)) / 2.0
                val square = abs(spanU - spanV) / max(spanU, spanV) * 4.0
                val agreement = (up.agreement + vp.agreement) / 2.0
                val cost = fit + square + (1.0 - agreement) * 3.0
                if (best == null || cost < best.cost) best = Choice(up, vp, version, cost, agreement)
            }
        }
        val choice = best ?: return null
        val dimension = 17 + 4 * choice.version
        val dimensionError = (abs(spanU / choice.u.pitch - dimension) + abs(spanV / choice.v.pitch - dimension)) / 2.0
        if (choice.agreement < .48 || dimensionError > 2.2 || abs(spanU - spanV) / max(spanU, spanV) > .18) return null

        // Select a dimension-sized, phase-aligned window. This discards spreadsheet headers and
        // other narrow periodic margins instead of mistaking the entire photograph for the QR.
        val ordinaryU = bestWindow(dark.u, dark.minU, dark.maxU, choice.u, dimension)
        val ordinaryV = bestWindow(dark.v, dark.minV, dark.maxV, choice.v, dimension)
        val anchored = if (ordinaryU == null || ordinaryV == null)
            trace.measure("FINDER_ANCHOR") {
                finderAnchoredWindow(image, dimension, choice.u, choice.v, ux, uy, dark, cancellation, trace)
            } ?: return null
        else null
        val uEdge = anchored?.first ?: ordinaryU ?: return null
        val vEdge = anchored?.second ?: ordinaryV ?: return null
        val u0 = uEdge + choice.u.pitch * .5
        val u1 = uEdge + choice.u.pitch * (dimension - .5)
        val v0 = vEdge + choice.v.pitch * .5
        val v1 = vEdge + choice.v.pitch * (dimension - .5)
        fun point(u: Double, v: Double) = PointD(ux * u + vx * v, uy * u + vy * v)
        val score = (choice.agreement * (1.0 - dimensionError / 4.0)).coerceIn(0.0, 1.0)
        return GridPeriodicityEstimate(
            choice.version,
            ImageQuad(point(u0, v0), point(u1, v0), point(u1, v1), point(u0, v1)),
            (choice.u.pitch + choice.v.pitch) / 2.0,
            score,
        )
    }

    /** Extends missing dark support only when a surviving Finder and timing agree with the phase. */
    private fun finderAnchoredWindow(
        image: LumaImage, dimension: Int, u: Period, v: Period, ux: Double, uy: Double,
        dark: DarkProjection, cancellation: RecoveryCancellation, trace: GridEstimationTrace?,
    ): Pair<Double, Double>? {
        // One Finder cannot constrain perspective. Keep this fallback to nearly square modules.
        if (dimension < 25 || abs(u.pitch / v.pitch - 1.0) > .08) return null
        // Period, phase and dark support already bound the possible windows. Verify their corner
        // templates directly instead of repeating a whole-image Finder scale/angle search.
        fun starts(minimum: Double, maximum: Double, period: Period): List<Double> {
            val first = ceil((maximum - (dimension + .5) * period.pitch - period.boundaryPhase) / period.pitch).toInt()
            val last = floor((minimum + .5 * period.pitch - period.boundaryPhase) / period.pitch).toInt()
            return (first..last).map { period.boundaryPhase + it * period.pitch }
        }
        val uStarts = starts(dark.minU, dark.maxU, u)
        val vStarts = starts(dark.minV, dark.maxV, v)
        trace?.record("FINDER_WINDOWS", "windows=${uStarts.size * vStarts.size}")
        val accepted = mutableListOf<Pair<Double, Double>>()
        val offsets = listOf(0.0 to 0.0, -.2 to 0.0, .2 to 0.0, 0.0 to -.2, 0.0 to .2)
        fun point(a: Double, b: Double) = PointD(ux * a - uy * b, uy * a + ux * b)
        for (startU in uStarts) for (startV in vStarts)
            for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            cancellation.check()
            val fx = if (right) dimension - 4 else 3
            val fy = if (bottom) dimension - 4 else 3
            // The proposed window must contain the observed dark extent, not just the anchor.
            if (dark.minU < startU - u.pitch * .5 || dark.maxU > startU + (dimension + .5) * u.pitch ||
                dark.minV < startV - v.pitch * .5 || dark.maxV > startV + (dimension + .5) * v.pitch) continue
            fun sample(x: Double, y: Double): Double? {
                val p = point(startU + (x + .5) * u.pitch, startV + (y + .5) * v.pitch)
                return image.sampleMean(p.x, p.y, 0.0)
            }
            if (listOf(0 to 0, dimension - 1 to 0, dimension - 1 to dimension - 1, 0 to dimension - 1)
                    .any { (x, y) -> sample(x.toDouble(), y.toDouble()) == null }) continue
            fun finderBlack(x: Int, y: Int) = abs(x) == 3 || abs(y) == 3 || (abs(x) <= 1 && abs(y) <= 1)
            val blacks = mutableListOf<Double>(); val whites = mutableListOf<Double>()
            for (y in -3..3) for (x in -3..3) {
                val value = sample((fx + x).toDouble(), (fy + y).toDouble()) ?: continue
                if (finderBlack(x, y)) blacks += value else whites += value
            }
            if (blacks.size != 33 || whites.size != 16) continue
            val black = blacks.sorted()[blacks.size / 2]; val white = whites.sorted()[whites.size / 2]
            if (white - black < 64) continue
            // This evidence-only unknown band does not change module sampling or saved thresholds.
            fun observed(x: Double, y: Double): Boolean? {
                val value = sample(x, y) ?: return null
                return when {
                    value <= black + (white - black) * .3 -> true
                    value >= black + (white - black) * .7 -> false
                    else -> null
                }
            }
            if (offsets.any { (dx, dy) ->
                    var errors = 0
                    for (y in -3..3) for (x in -3..3)
                        if (observed(fx + x + dx, fy + y + dy) != finderBlack(x, y)) errors++
                    errors > 2
                }) continue
            val sx = if (right) -4 else 4; val sy = if (bottom) -4 else 4
            if (offsets.any { (dx, dy) ->
                    var whitesKnown = 0; var errors = 0
                    val cells = (-3..3).flatMap { listOf(fx + sx to fy + it, fx + it to fy + sy) } +
                        (fx + sx to fy + sy)
                    for ((x, y) in cells) when (observed(x + dx, y + dy)) {
                        false -> whitesKnown++
                        true -> errors++
                        null -> Unit
                    }
                    whitesKnown < 12 || errors > 1
                }) continue
            // Try all logical rotations; mirror swaps the two timing axes and gives the same test.
            // No logical Finder role is assigned from this provisional geometry.
            val supported = (0..3).any { rotation ->
                fun rotated(x: Double, y: Double): Pair<Double, Double> = when (rotation) {
                    0 -> x to y
                    1 -> dimension - 1 - y to x
                    2 -> dimension - 1 - x to dimension - 1 - y
                    else -> y to dimension - 1 - x
                }
                val missingCorner = rotated((dimension - 4).toDouble(), (dimension - 4).toDouble())
                if (missingCorner.first == fx.toDouble() && missingCorner.second == fy.toDouble()) return@any false
                offsets.all { (dx, dy) ->
                    var strongAxes = 0; var contradicts = false
                    for (horizontal in listOf(true, false)) {
                        var known = 0; var errors = 0; var blackCount = 0
                        for (i in 8..dimension - 9) {
                            val (x, y) = if (horizontal) rotated(i + dx, 6.0 + dy) else rotated(6.0 + dx, i + dy)
                            val bit = observed(x, y) ?: continue
                            known++
                            if (bit) blackCount++
                            if (bit != (i % 2 == 0)) errors++
                        }
                        if (errors > known * .05) contradicts = true
                        if (known >= 8 && known >= (dimension - 16) * .6 && blackCount >= 3 && known - blackCount >= 3)
                            strongAxes++
                    }
                    !contradicts && strongAxes >= 1
                }
            }
            if (supported && accepted.none { abs(it.first - startU) < .01 && abs(it.second - startV) < .01 })
                accepted += startU to startV
        }
        // Multiple anchors may support the same grid, but distinct supported extents remain ambiguous.
        return accepted.singleOrNull()
    }

    internal fun periods(coordinates: List<Pair<Double, Double>>, maxPitch: Double,
        cancellation: RecoveryCancellation): List<Period> {
        // The phase statistic converges with far fewer samples than a camera frame contains.
        // Deterministic even sampling keeps import latency bounded without changing provenance.
        val sampleStep = max(1, coordinates.size / 12_000)
        val weights = linkedMapOf<Double, Double>()
        var count = 0
        for (index in coordinates.indices step sampleStep) {
            if (count++ >= 12_000) break
            if (index % 256 == 0) cancellation.check()
            val (coordinate, rawWeight) = coordinates[index]
            weights[coordinate] = (weights[coordinate] ?: 0.0) + rawWeight.coerceAtMost(510.0)
        }
        // Identical projections have identical phases at every pitch. Summing their weights
        // removes repeated trigonometry without quantizing coordinates or dropping evidence.
        val positions = weights.keys.toDoubleArray()
        val summedWeights = weights.values.toDoubleArray()
        val total = summedWeights.sum()
        val candidates = ArrayList<Period>()
        var pitch = 2.0
        while (pitch <= maxPitch) {
            cancellation.check()
            var real = 0.0; var imaginary = 0.0
            for (index in positions.indices) {
                val phase = positions[index] / pitch * 2.0 * PI
                real += summedWeights[index] * cos(phase)
                imaginary += summedWeights[index] * sin(phase)
            }
            val agreement = if (total == 0.0) 0.0 else hypot(real, imaginary) / total
            var phase = atan2(imaginary, real) / (2.0 * PI) * pitch
            if (phase < 0.0) phase += pitch
            candidates += Period(pitch, agreement, phase)
            pitch += .25
        }
        val fundamental = candidates.filter { candidate ->
            cancellation.check()
            (2..4).none { harmonic ->
                candidates.any { larger ->
                    abs(larger.pitch - candidate.pitch * harmonic) <= .26 &&
                        larger.agreement >= candidate.agreement * .82
                }
            }
        }
        return fundamental.sortedByDescending { it.agreement }.take(10)
    }

    private fun projectedDark(
        image: LumaImage,
        ux: Double,
        uy: Double,
        vx: Double,
        vy: Double,
        stride: Int,
        cancellation: RecoveryCancellation,
    ): DarkProjection? {
        var minU = Double.POSITIVE_INFINITY; var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY; var maxV = Double.NEGATIVE_INFINITY
        val uValues = ArrayList<Double>(); val vValues = ArrayList<Double>()
        // Geometry bounds need definite black evidence. Otsu may classify a large gray erasure
        // or shaded paper background as dark and enlarge the symbol by several modules.
        val threshold = minOf(image.threshold, 96)
        for (y in 0 until image.height step stride) for (x in 0 until image.width step stride) {
            if (x == 0) cancellation.check()
            if (image.luma(x, y) > threshold) continue
            val u = x * ux + y * uy; val v = x * vx + y * vy
            minU = minOf(minU, u); maxU = maxOf(maxU, u)
            minV = minOf(minV, v); maxV = maxOf(maxV, v)
            uValues += u; vValues += v
        }
        if (uValues.size < 40) return null
        // Isolated dark pixels in a photographed background must not enlarge the QR by several
        // modules. Keep the bulk extent; 0.1% still retains thousands of edge pixels in a normal
        // camera frame and leaves clean, tightly cropped symbols effectively unchanged.
        val sortedU = uValues.sorted()
        val sortedV = vValues.sorted()
        val trim = (uValues.size * .001).toInt().coerceAtMost((uValues.size - 1) / 4)
        minU = sortedU[trim]; maxU = sortedU[sortedU.lastIndex - trim]
        minV = sortedV[trim]; maxV = sortedV[sortedV.lastIndex - trim]
        return DarkProjection(uValues, vValues, minU, maxU, minV, maxV)
    }

    private fun bestWindow(
        values: List<Double>,
        minimum: Double,
        maximum: Double,
        period: Period,
        dimension: Int,
    ): Double? {
        val length = dimension * period.pitch
        val first = kotlin.math.floor((minimum - period.boundaryPhase) / period.pitch).toInt() - 1
        val last = kotlin.math.ceil((maximum - period.boundaryPhase) / period.pitch).toInt() + 1
        var bestStart: Double? = null
        var bestCount = -1
        for (index in first..last) {
            val start = period.boundaryPhase + index * period.pitch
            val end = start + length
            // Permit half a module beyond the observed dark support for tightly cropped photographs.
            if (start < minimum - period.pitch * .5 || end > maximum + period.pitch * .5) continue
            val count = values.count { it in start..end }
            if (count > bestCount) { bestCount = count; bestStart = start }
        }
        return bestStart
    }

}
