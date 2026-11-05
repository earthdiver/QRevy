package io.github.earthdiver.qrrepair.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Bounded eight-parameter Levenberg-Marquardt fit to image evidence, at a fixed QR version. */
object ProjectivePatternRefiner {
    fun refine(
        image: LumaImage,
        seed: GridGeometry,
        starts: List<ImageQuad>,
        alignmentCenters: IntArray,
        inverted: Boolean,
        stopped: () -> Boolean = { false },
    ): GridGeometry? {
        if (stopped() || seed.warp != null || seed.imageQuad == null) return null
        return try {
            fit(image, seed, starts, alignmentCenters, inverted, stopped)
        } catch (_: IllegalArgumentException) {
            // A degenerate input mapping cannot provide a projective refinement.
            null
        }
    }

    private fun fit(image: LumaImage, seed: GridGeometry, starts: List<ImageQuad>, centers: IntArray,
                    inverted: Boolean, stopped: () -> Boolean): GridGeometry? {
        val quad = seed.moduleCenterQuad()
        val frame = Frame(seed)
        if (!frame.valid(frame.origin)) return null
        val crop = ProjectivePatternImage.crop(image, quad, frame.pitch, inverted, stopped) ?: return null
        val template = ProjectivePatternTemplate(seed.dimension, centers)
        var trials = (listOf(quad) + starts).distinct().take(4).mapNotNull {
            try { frame.parameters(it).takeIf(frame::valid) } catch (_: IllegalArgumentException) { null }
        }
        var winner: DoubleArray? = null
        for (sigma in listOf(.32, .14, .035)) {
            if (stopped() || trials.isEmpty()) return null
            val observations = template.observations(seed, crop, sigma)
            if (observations.size < 8) return null
            val layer = crop.blurred(sigma * frame.pitch, stopped) ?: return null
            val problem = FitProblem(frame, layer, observations)
            trials = trials.mapNotNull { start -> problem.minimize(start, stopped) }
            if (sigma == .035) {
                // Compare every start on identical fine-scale evidence and check the undamped
                // image equations. The seed prior must not masquerade as a measured direction.
                var best = problem.evaluate(frame.origin)?.cost ?: return null
                for (trial in trials) {
                    val measurement = problem.evaluate(trial) ?: continue
                    val cost = measurement.cost
                    if (cost < best - 1e-8 && measurement.hasEightDirections()) {
                        winner = trial
                        best = cost
                    }
                }
            }
        }
        val refined = frame.quad(winner ?: return null)
        return seed.copy(imageQuad = refined, model = GeometryModel.PROJECTIVE).reanchored()
    }

    /** Logical coordinates span [-1,1]; parameter increments and the displacement bound use modules. */
    private class Frame(seed: GridGeometry) {
        private val corners = listOf(PointD(-1.0, -1.0), PointD(1.0, -1.0), PointD(1.0, 1.0), PointD(-1.0, 1.0))
        private val points = seed.moduleCenterQuad().asList()
        val center = PointD(points.map { it.x }.average(), points.map { it.y }.average())
        val factor = 2.0 / (seed.dimension - 1)
        val pitch = points.indices.sumOf { points[it].distanceTo(points[(it + 1) % 4]) } / (4 * (seed.dimension - 1))
        val origin = parameters(seed.moduleCenterQuad())
        private val winding = cross(points)
        private data class Bound(val x: Double, val y: Double, val original: PointD, val radius: Double)
        private val bounds = buildList {
            for (j in 0..4) for (i in 0..4) {
                val x = i * .5 - 1
                val y = j * .5 - 1
                add(Bound(x, y, map(origin, x, y), 2.5 * seed.localModulePitch((x + 1) / factor, (y + 1) / factor)))
            }
        }
        fun parameters(quad: ImageQuad): DoubleArray {
            val h = Homography.fromCorrespondences(corners, quad.asList().map { (it - center) * (1 / pitch) })
            return doubleArrayOf(h.h11, h.h12, h.h13, h.h21, h.h22, h.h23, h.h31 / factor, h.h32 / factor)
        }
        private fun map(h: DoubleArray, x: Double, y: Double): PointD {
            val scale = pitch / (1 + factor * (h[6] * x + h[7] * y))
            return PointD(center.x + scale * (h[0] * x + h[1] * y + h[2]), center.y + scale * (h[3] * x + h[4] * y + h[5]))
        }
        fun quad(h: DoubleArray): ImageQuad = corners.map { map(h, it.x, it.y) }.let { ImageQuad(it[0], it[1], it[2], it[3]) }
        private fun cross(p: List<PointD>): Double {
            val top = p[1] - p[0]
            val left = p[3] - p[0]
            return top.x * left.y - top.y * left.x
        }
        fun valid(h: DoubleArray): Boolean {
            if (h.any { !it.isFinite() } || 1 - factor * (abs(h[6]) + abs(h[7])) < .25) return false
            if (!(cross(quad(h).asList()) * winding > 0)) return false
            return bounds.all { map(h, it.x, it.y).distanceTo(it.original) <= it.radius }
        }
    }

    private class FitProblem(val frame: Frame, val image: ProjectivePatternImage, val samples: List<PatternObservation>) {
        private val value = DoubleArray(3)
        private val jacobian = DoubleArray(8)

        fun evaluate(h: DoubleArray): Equations? {
            if (!frame.valid(h)) return null
            val system = Equations()
            for ((x, y, weight, target) in samples) {
                val den = 1 + frame.factor * (h[6] * x + h[7] * y)
                val u = (h[0] * x + h[1] * y + h[2]) / den
                val v = (h[3] * x + h[4] * y + h[5]) / den
                if (!image.sample(frame.center.x + frame.pitch * u, frame.center.y + frame.pitch * v, value)) return null
                val residual = value[0] - target
                val magnitude = abs(residual)
                system.cost += weight * if (magnitude <= HUBER) .5 * residual * residual else HUBER * (magnitude - .5 * HUBER)
                val gx = value[1] * frame.pitch / den
                val gy = value[2] * frame.pitch / den
                val perspective = -(gx * u + gy * v) * frame.factor
                jacobian[0] = gx * x
                jacobian[1] = gx * y
                jacobian[2] = gx
                jacobian[3] = gy * x
                jacobian[4] = gy * y
                jacobian[5] = gy
                jacobian[6] = perspective * x
                jacobian[7] = perspective * y
                system.add(jacobian, residual, if (magnitude <= HUBER) weight else weight * HUBER / magnitude)
            }
            for (i in h.indices) {
                val displacement = h[i] - frame.origin[i]
                system.cost += .5 * PRIOR * displacement * displacement
                system.rhs[i] -= PRIOR * displacement
            }
            return system
        }

        fun minimize(start: DoubleArray, stopped: () -> Boolean): DoubleArray? {
            if (stopped()) return null
            var h = start
            var current = evaluate(h) ?: return null
            var damping = .001
            repeat(18) {
                if (stopped()) return null
                val step = current.step(damping) ?: return null
                val trial = DoubleArray(8) { h[it] + step[it] }
                val next = evaluate(trial)
                if (next != null && next.cost < current.cost) {
                    current = next
                    h = trial
                    damping = max(1e-7, .4 * damping)
                    if (step.sumOf { it * it } < .0005 * .0005) return h
                } else {
                    damping *= 5
                    if (damping > 1e6) return h
                }
            }
            return h
        }
    }

    private const val HUBER = .18
    private const val PRIOR = .00004

    /** Accumulate the small symmetric system without retaining a row per image sample. */
    private class Equations {
        private val normal = DoubleArray(64)
        val rhs = DoubleArray(8)
        var cost = 0.0

        fun add(row: DoubleArray, residual: Double, weight: Double) {
            for (i in row.indices) {
                val w = weight * row[i]
                rhs[i] -= w * residual
                for (j in 0..i) normal[8 * i + j] += w * row[j]
            }
        }
        fun hasEightDirections() = factor(normal.copyOf(), (0..7).maxOf { normal[it * 9] } * 1e-8) != null
        fun step(damping: Double): DoubleArray? {
            val a = normal.copyOf()
            for (i in 0..7) a[i * 9] += PRIOR + damping * (normal[i * 9] + 1e-5)
            val l = factor(a, 0.0) ?: return null
            val out = rhs.copyOf()
            for (i in 0..7) {
                for (j in 0 until i) out[i] -= l[8 * i + j] * out[j]
                out[i] /= l[i * 9]
            }
            for (i in 7 downTo 0) {
                for (j in (i + 1)..7) out[i] -= l[8 * j + i] * out[j]
                out[i] /= l[i * 9]
            }
            return out
        }
        private fun factor(a: DoubleArray, minimumPivot: Double): DoubleArray? {
            for (i in 0..7) for (j in 0..i) {
                var entry = a[8 * i + j]
                for (k in 0 until j) entry -= a[8 * i + k] * a[8 * j + k]
                if (i == j && !(entry > minimumPivot)) return null
                a[8 * i + j] = if (i == j) sqrt(entry) else entry / a[j * 9]
            }
            return a
        }
    }
}
