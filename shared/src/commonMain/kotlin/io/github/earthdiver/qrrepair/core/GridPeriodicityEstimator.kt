package io.github.earthdiver.qrrepair.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
    private data class Period(val pitch: Double, val agreement: Double, val boundaryPhase: Double)
    private data class DarkProjection(
        val u: List<Double>,
        val v: List<Double>,
        val minU: Double,
        val maxU: Double,
        val minV: Double,
        val maxV: Double,
    )

    fun estimate(image: LumaImage): GridPeriodicityEstimate? {
        if (minOf(image.width, image.height) < 42) return null
        val stride = max(1, max(image.width, image.height) / 700)
        val edges = ArrayList<Edge>()
        val orientation = DoubleArray(90)
        for (y in 1 until image.height - 1 step stride) for (x in 1 until image.width - 1 step stride) {
            val gx = (image.luma(x + 1, y) - image.luma(x - 1, y)).toDouble()
            val gy = (image.luma(x, y + 1) - image.luma(x, y - 1)).toDouble()
            val magnitude = hypot(gx, gy)
            if (magnitude < 36.0) continue
            val angle = ((atan2(gy, gx) * 180.0 / PI) % 180.0 + 180.0) % 180.0
            val bin = (angle / 2.0).roundToInt() % 90
            orientation[bin] += magnitude
            edges += Edge(x.toDouble(), y.toDouble(), gx, gy, magnitude)
        }
        if (edges.size < 80) return null

        // A QR contributes two perpendicular edge-normal families. Requiring both suppresses
        // strong single-direction structures such as text lines and fences.
        val axisBin = orientation.indices.maxByOrNull { orientation[it] + orientation[(it + 45) % 90] } ?: return null
        val axisAngle = axisBin * 2.0 * PI / 180.0
        val ux = cos(axisAngle); val uy = sin(axisAngle)
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
        val uPeriods = periods(uCoordinates, maxPitch)
        val vPeriods = periods(vCoordinates, maxPitch)
        if (uPeriods.isEmpty() || vPeriods.isEmpty()) return null

        val dark = projectedDark(image, ux, uy, vx, vy, stride) ?: return null
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
        val uEdge = bestWindow(dark.u, dark.minU, dark.maxU, choice.u, dimension) ?: return null
        val vEdge = bestWindow(dark.v, dark.minV, dark.maxV, choice.v, dimension) ?: return null
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

    private fun periods(coordinates: List<Pair<Double, Double>>, maxPitch: Double): List<Period> {
        // The phase statistic converges with far fewer samples than a camera frame contains.
        // Deterministic even sampling keeps import latency bounded without changing provenance.
        val sampleStep = max(1, coordinates.size / 12_000)
        val samples = coordinates.filterIndexed { index, _ -> index % sampleStep == 0 }.take(12_000)
        val candidates = ArrayList<Period>()
        var pitch = 2.0
        while (pitch <= maxPitch) {
            var real = 0.0; var imaginary = 0.0; var total = 0.0
            for ((coordinate, rawWeight) in samples) {
                val weight = rawWeight.coerceAtMost(510.0)
                val phase = coordinate / pitch * 2.0 * PI
                real += weight * cos(phase); imaginary += weight * sin(phase); total += weight
            }
            val agreement = if (total == 0.0) 0.0 else hypot(real, imaginary) / total
            var phase = atan2(imaginary, real) / (2.0 * PI) * pitch
            if (phase < 0.0) phase += pitch
            candidates += Period(pitch, agreement, phase)
            pitch += .25
        }
        val fundamental = candidates.filter { candidate ->
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
    ): DarkProjection? {
        var minU = Double.POSITIVE_INFINITY; var maxU = Double.NEGATIVE_INFINITY
        var minV = Double.POSITIVE_INFINITY; var maxV = Double.NEGATIVE_INFINITY
        val uValues = ArrayList<Double>(); val vValues = ArrayList<Double>()
        // Geometry bounds need definite black evidence. Otsu may classify a large gray erasure
        // or shaded paper background as dark and enlarge the symbol by several modules.
        val threshold = minOf(image.threshold, 96)
        for (y in 0 until image.height step stride) for (x in 0 until image.width step stride) {
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
            // Permit half a module beyond the sampled image for tightly cropped photographs.
            if (start < minimum - period.pitch * .5 || end > maximum + period.pitch * .5) continue
            val count = values.count { it in start..end }
            if (count > bestCount) { bestCount = count; bestStart = start }
        }
        return bestStart
    }

}
