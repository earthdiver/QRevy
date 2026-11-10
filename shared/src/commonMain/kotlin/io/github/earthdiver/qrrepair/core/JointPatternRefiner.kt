package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Bounded joint fit of a projective map and six smooth displacement modes per image axis.
 * Only measured finder/separator and alignment brightness is used. Payload bits and edits
 * are not fitting targets. A returned map has converged and reduces the unweighted image
 * residual sum of squares, measured on identical fine-scale probes before and after fitting.
 */
object JointPatternRefiner {
    data class Result(
        val geometry: GridGeometry,
        val initialResidualSumSquares: Double,
        val residualSumSquares: Double,
    )

    private const val SIZE = 20
    private const val MAX_ITERATIONS = 64
    private const val HUBER = .18
    private const val PROJECTIVE_PRIOR = .00004
    private const val CURVE_PRIOR = .0004
    private val corners = listOf(PointD(-1.0, -1.0), PointD(1.0, -1.0), PointD(1.0, 1.0), PointD(-1.0, 1.0))
    private val knots = (0..2).flatMap { y -> (0..2).map { x -> PointD(x / 2.0, y / 2.0) } }
    // Remove constant/x/y displacement at the knots to reduce overlap with the projective fit.
    private val modes: List<ThinPlateWarp> by lazy {
        val basis = mutableListOf<DoubleArray>()
        val nonlinear = mutableListOf<DoubleArray>()
        val candidates = listOf(DoubleArray(9) { 1.0 }, DoubleArray(9) { knots[it].x },
            DoubleArray(9) { knots[it].y }) + (0..8).map { k -> DoubleArray(9) { if (it == k) 1.0 else 0.0 } }
        for ((index, source) in candidates.withIndex()) {
            val v = source.copyOf()
            repeat(2) {
                for (q in basis) {
                    val dot = v.indices.sumOf { v[it] * q[it] }
                    for (i in v.indices) v[i] -= dot * q[i]
                }
            }
            val norm = sqrt(v.sumOf { it * it })
            if (norm > 1e-8) {
                for (i in v.indices) v[i] /= norm
                basis += v
                if (index >= 3) nonlinear += v
            }
        }
        check(nonlinear.size == 6)
        nonlinear.map { v -> ThinPlateWarp(knots.mapIndexed { i, p ->
            WarpLandmark(p, PointD(v[i], 0.0), 1e-6)
        }) }
    }

    fun refine(
        image: LumaImage,
        seed: GridGeometry,
        alignmentCenters: IntArray,
        inverted: Boolean,
        stopped: () -> Boolean = { false },
        maxIterationsPerScale: Int = MAX_ITERATIONS,
    ): Result? {
        require(maxIterationsPerScale in 1..MAX_ITERATIONS)
        if (stopped() || seed.imageQuad == null) return null
        return try {
            fit(image, seed, alignmentCenters, inverted, stopped, maxIterationsPerScale)
        } catch (_: IllegalArgumentException) {
            // Degenerate maps or spline exports leave the original candidate unchanged.
            null
        }
    }

    private data class Probe(
        val x: Double, val y: Double, val weight: Double, val target: Double,
        val offset: PointD, val modes: DoubleArray,
    )

    private class Frame(val seed: GridGeometry) {
        val last = seed.dimension - 1.0
        val pitch = seed.localModulePitch(last / 2, last / 2)
        private val quad = requireNotNull(seed.imageQuad)
        private val center = quad.asList().reduce { a, b -> a + b } * .25
        private val h = Homography.fromCorrespondences(corners, quad.asList().map { (it - center) * (1 / pitch) })
        val origin = doubleArrayOf(h.h11, h.h12, h.h13, h.h21, h.h22, h.h23, h.h31, h.h32) + DoubleArray(12)
        val guards = (0..8).flatMap { y -> (0..8).map { x -> probe(x / 4.0 - 1, y / 4.0 - 1) } }
        private val winding = cross(quad.asList())

        fun probe(x: Double, y: Double, weight: Double = 1.0, target: Double = 0.0): Probe {
            val u = (x + 1) / 2
            val v = (y + 1) / 2
            return Probe(x, y, weight, target, seed.warp?.offset(u, v) ?: PointD(0.0, 0.0),
                DoubleArray(6) { modes[it].offset(u, v).x })
        }

        fun planar(p: DoubleArray, x: Double, y: Double): PointD {
            val den = 1 + p[6] * x + p[7] * y
            return center + PointD((p[0] * x + p[1] * y + p[2]) / den,
                (p[3] * x + p[4] * y + p[5]) / den) * pitch
        }

        fun displacement(p: DoubleArray, s: Probe) = s.offset + PointD(
            (0..5).sumOf { p[8 + it] * s.modes[it] },
            (0..5).sumOf { p[14 + it] * s.modes[it] },
        ) * pitch

        fun position(p: DoubleArray, s: Probe) = planar(p, s.x, s.y) + displacement(p, s)
        fun movement(a: DoubleArray, b: DoubleArray) = guards.maxOf { position(a, it).distanceTo(position(b, it)) } / pitch

        fun valid(p: DoubleArray): Boolean = pitch.isFinite() && pitch > 0 && p.all { it.isFinite() } &&
            1 - abs(p[6]) - abs(p[7]) > .25 && cross(corners.map { planar(p, it.x, it.y) }) * winding > 0 &&
            movement(p, origin) <= 1.5

        private fun cross(p: List<PointD>): Double {
            val x = p[1] - p[0]
            val y = p[3] - p[0]
            return x.x * y.y - x.y * y.x
        }
    }

    private class Equations {
        var cost = 0.0
        var residualSumSquares = 0.0
        val normal = Array(SIZE) { DoubleArray(SIZE) }
        val rhs = DoubleArray(SIZE)
        fun stationary(): Boolean = rhs.indices.maxOf { abs(rhs[it]) / sqrt(max(normal[it][it], 1e-12)) } < 1e-6
    }

    private class Problem(val frame: Frame, val image: ProjectivePatternImage, val probes: List<Probe>, val stopped: () -> Boolean) {
        fun evaluate(p: DoubleArray): Equations? {
            if (stopped() || !frame.valid(p)) return null
            val result = Equations()
            val value = DoubleArray(3)
            val jacobian = DoubleArray(SIZE)
            for ((index, s) in probes.withIndex()) {
                if (index % 64 == 0 && stopped()) return null
                val point = frame.position(p, s)
                if (!image.sample(point.x, point.y, value)) return null
                val residual = value[0] - s.target
                val magnitude = abs(residual)
                result.residualSumSquares += residual * residual
                result.cost += s.weight * huber(residual)
                val den = 1 + p[6] * s.x + p[7] * s.y
                val u = (p[0] * s.x + p[1] * s.y + p[2]) / den
                val v = (p[3] * s.x + p[4] * s.y + p[5]) / den
                val gx = value[1] * frame.pitch
                val gy = value[2] * frame.pitch
                jacobian[0] = gx * s.x / den; jacobian[1] = gx * s.y / den; jacobian[2] = gx / den
                jacobian[3] = gy * s.x / den; jacobian[4] = gy * s.y / den; jacobian[5] = gy / den
                jacobian[6] = -(gx * u + gy * v) * s.x / den
                jacobian[7] = -(gx * u + gy * v) * s.y / den
                for (i in 0..5) { jacobian[8 + i] = gx * s.modes[i]; jacobian[14 + i] = gy * s.modes[i] }
                val weight = s.weight * if (magnitude <= HUBER) 1.0 else HUBER / magnitude
                for (i in 0 until SIZE) {
                    result.rhs[i] -= weight * jacobian[i] * residual
                    for (j in 0..i) result.normal[i][j] += weight * jacobian[i] * jacobian[j]
                }
            }
            for (i in 0 until SIZE) {
                val prior = if (i < 8) PROJECTIVE_PRIOR else CURVE_PRIOR
                val d = p[i] - frame.origin[i]
                result.cost += .5 * prior * d * d
                result.normal[i][i] += prior
                result.rhs[i] -= prior * d
                for (j in 0 until i) result.normal[j][i] = result.normal[i][j]
            }
            return result.takeIf { it.cost.isFinite() && it.residualSumSquares.isFinite() }
        }

        data class Minimum(val parameters: DoubleArray, val converged: Boolean)
        fun minimize(start: DoubleArray, iterations: Int): Minimum? {
            var parameters = start
            var current = evaluate(parameters) ?: return null
            var damping = .001
            repeat(iterations) {
                if (stopped()) return null
                if (current.stationary()) return Minimum(parameters, true)
                val normal = Array(SIZE) { current.normal[it].copyOf() }
                for (i in 0 until SIZE) normal[i][i] += damping * (normal[i][i] + 1e-5)
                val step = solve(normal, current.rhs.copyOf()) ?: return null
                val trial = DoubleArray(SIZE) { parameters[it] + step[it] }
                val next = evaluate(trial)
                if (next != null && next.cost < current.cost) {
                    val converged = frame.movement(parameters, trial) < 1e-4 &&
                        current.cost - next.cost <= 1e-6 * max(current.cost, 1e-12)
                    parameters = trial
                    current = next
                    damping = max(1e-7, damping * .4)
                    if (converged) return Minimum(parameters, true)
                } else {
                    damping *= 5
                    if (damping > 1e6) return Minimum(parameters, false)
                }
            }
            // Hitting the iteration cap is not convergence.
            return Minimum(parameters, current.stationary())
        }
    }

    private fun fit(image: LumaImage, seed: GridGeometry, centers: IntArray, inverted: Boolean,
                    stopped: () -> Boolean, iterations: Int): Result? {
        val frame = Frame(seed)
        if (!frame.valid(frame.origin) || stopped() || !seed.hasUsableWarp()) return null
        val locations = (seed.warp?.landmarks.orEmpty().map { it.position } + knots).distinct()
        if (locations.size > 64) return null
        val crop = ProjectivePatternImage.crop(image, seed.moduleCenterQuad(), frame.pitch, inverted, stopped) ?: return null
        val template = ProjectivePatternTemplate(seed.dimension, centers)
        var parameters = frame.origin.copyOf()
        var finalProblem: Problem? = null
        for (sigma in listOf(.32, .14, .035)) {
            if (stopped()) return null
            val observations = template.observations(seed, crop, sigma)
            if (observations.size < 8) return null
            val probes = observations.map { frame.probe(it.x, it.y, it.weight, it.target) }
            val layer = crop.blurred(sigma * frame.pitch, stopped) ?: return null
            val problem = Problem(frame, layer, probes, stopped)
            val minimum = problem.minimize(parameters, minOf(iterations, if (sigma == .035) MAX_ITERATIONS else 24)) ?: return null
            parameters = minimum.parameters
            if (sigma == .035) {
                if (!minimum.converged) return null
                finalProblem = problem
            }
        }
        val problem = finalProblem ?: return null
        val initial = problem.evaluate(frame.origin)?.residualSumSquares ?: return null
        if (stopped()) return null
        val points = corners.map { frame.planar(parameters, it.x, it.y) }
        val output = seed.copy(imageQuad = ImageQuad(points[0], points[1], points[2], points[3]),
            model = GeometryModel.THIN_PLATE_SPLINE, warp = ThinPlateWarp(locations.map { p ->
                WarpLandmark(p, frame.displacement(parameters, frame.probe(2 * p.x - 1, 2 * p.y - 1)), 1e-6)
            })).reanchored()
        // The exported TPS must represent the optimized map accurately. Validate the map that
        // will actually be sampled/saved, not just the in-memory optimization parameters.
        for (s in frame.guards) {
            if (stopped()) return null
            val point = output.moduleToImage((s.x + 1) * frame.last / 2, (s.y + 1) * frame.last / 2)
            if (point.distanceTo(frame.position(parameters, s)) / frame.pitch >= .01) return null
        }
        if (stopped() || !output.hasUsableWarp()) return null
        val value = DoubleArray(3)
        var residualSumSquares = 0.0
        for ((index, s) in problem.probes.withIndex()) {
            if (index % 64 == 0 && stopped()) return null
            val point = output.moduleToImage((s.x + 1) * frame.last / 2, (s.y + 1) * frame.last / 2)
            if (!problem.image.sample(point.x, point.y, value)) return null
            val residual = value[0] - s.target
            residualSumSquares += residual * residual
        }
        return if (!stopped() && residualSumSquares.isFinite() && residualSumSquares < initial)
            Result(output, initial, residualSumSquares) else null
    }

    private fun huber(residual: Double): Double = if (abs(residual) <= HUBER) .5 * residual * residual
        else HUBER * (abs(residual) - .5 * HUBER)

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        for (i in b.indices) {
            val pivot = (i until b.size).maxBy { abs(a[it][i]) }
            if (!a[pivot][i].isFinite() || abs(a[pivot][i]) < 1e-15) return null
            val row = a[pivot]; a[pivot] = a[i]; a[i] = row
            val t = b[pivot]; b[pivot] = b[i]; b[i] = t
            for (j in i + 1 until b.size) {
                val factor = a[j][i] / a[i][i]
                for (k in i until b.size) a[j][k] -= factor * a[i][k]
                b[j] -= factor * b[i]
            }
        }
        for (i in b.lastIndex downTo 0) {
            for (j in i + 1 until b.size) b[i] -= a[i][j] * b[j]
            b[i] /= a[i][i]
        }
        return b.takeIf { it.all(Double::isFinite) }
    }
}
