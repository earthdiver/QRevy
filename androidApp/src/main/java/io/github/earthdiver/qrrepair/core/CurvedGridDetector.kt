package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Image-only landmarks; QR payload, repaired bits and user edits are never fitting targets. */
internal class CurvedGridDetector(
    private val image: LumaImage,
    private val profile: SamplingProfile,
    private val cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
    private val stopRequested: () -> Boolean = { false },
) {
    private data class Sample(val x: Double, val y: Double, val black: Boolean)
    private data class Match(val point: PointD, val dx: Double, val dy: Double, val score: Double)

    private val contours = CurvedFinderContours(image, profile.inverted, ::stopped)

    fun candidates(base: GridGeometry): List<GridGeometry> {
        if (base.warp != null || stopped()) return emptyList()
        val contourCandidates = contours.seeds(base).take(2).flatMap { seed ->
            val last = (base.dimension - 1).toDouble()
            val anchors = seed.points.map { p -> WarpLandmark(
                PointD(p.module.x / last, p.module.y / last), p.image - seed.base.moduleToImage(p.module.x, p.module.y)) }
            refine(seed.base, anchors, centerAlignment = true)
        }
        return (contourCandidates + patchCandidates(base)).take(12)
    }

    private fun patchCandidates(base: GridGeometry): List<GridGeometry> {
        if (base.warp != null || stopped()) return emptyList()
        val last = (base.dimension - 1).toDouble()
        fun landmark(x: Double, y: Double, imagePoint: PointD, smoothing: Double = 1e-5): WarpLandmark =
            WarpLandmark(PointD(x / last, y / last), imagePoint - base.moduleToImage(x, y), smoothing)
        val anchors = ArrayList<WarpLandmark>()
        // Four overlapping corner patches measure each finder locally, including its white separator.
        for (role in FinderRole.entries) {
            val center = base.finderModulePoint(role)
            val measured = ArrayList<WarpLandmark>()
            for ((ox, oy) in listOf(-2 to -2, 2 to -2, 2 to 2, -2 to 2)) {
                if (stopped()) return emptyList()
                val x = center.x + ox; val y = center.y + oy
                val samples = patch(x, y) { dx, dy ->
                    val ring = max(abs(ox + dx), abs(oy + dy))
                    ring <= 1 || ring == 3
                }
                val match = match(base, x, y, samples, 1.5, 1.5) ?: continue
                measured += landmark(x, y, match.point)
            }
            // A single ambiguous stripe must not displace an entire corner.
            if (measured.size < 3) return emptyList()
            anchors += measured
        }
        return refine(base, anchors)
    }

    private fun refine(base: GridGeometry, anchors: List<WarpLandmark>, centerAlignment: Boolean = false): List<GridGeometry> {
        val last = (base.dimension - 1).toDouble()
        fun landmark(x: Double, y: Double, imagePoint: PointD, smoothing: Double = 1e-5) =
            WarpLandmark(PointD(x / last, y / last), imagePoint - base.moduleToImage(x, y), smoothing)
        val first = fit(base, anchors) ?: return emptyList()
        val seeds = mutableListOf(first)
        val aligned = anchors.toMutableList()
        val centers = Version.getVersionForNumber(base.version).alignmentPatternCenters
        val positions = centers.flatMap { x -> centers.map { y -> x to y } }.filter { (x, y) ->
            !((x <= 8 && y <= 8) || (x >= base.dimension - 9 && y <= 8) || (x <= 8 && y >= base.dimension - 9))
        }.sortedByDescending { (x, y) -> x + y }.take(8)
        for ((mx, my) in positions) {
            if (stopped()) return seeds
            val x = mx.toDouble(); val y = my.toDouble()
            val centerSamples = (-2..2).flatMap { dy -> (-2..2).map { dx ->
                val ring = max(abs(dx), abs(dy))
                Sample(x + dx, y + dy, ring == 0 || ring == 2)
            } }
            val centerMatch = if (centerAlignment) match(first, x, y, centerSamples, 3.0, 3.0, minimumScore = .85) else null
            val match = centerMatch
                ?: match(first, x, y, patch(x, y) { dx, dy ->
                    val ring = max(abs(dx), abs(dy)); ring == 0 || ring == 2
                }, 3.0, 3.0) ?: continue
            aligned += landmark(x, y, match.point)
        }
        if (aligned.size > anchors.size) fit(base, aligned)?.let(seeds::add)
        val result = seeds.toMutableList()
        for (seed in seeds) {
            val timing = ArrayList<WarpLandmark>()
            for (axis in 0..1) {
                var previous = PointD(0.0, 0.0)
                val modules = (10 until base.dimension - 10 step 2).toList()
                val selected = if (modules.size <= 16) modules else (0..15).map { modules[it * (modules.size - 1) / 15] }
                for (k in selected) {
                    if (stopped()) return result
                    val x = if (axis == 0) k.toDouble() else 6.0
                    val y = if (axis == 0) 6.0 else k.toDouble()
                    val samples = buildList {
                        for (i in 0 until 40) for (cross in listOf(-.4, 0.0, .4)) {
                            val along = k - 2.5 + (i + .5) / 8.0
                            val black = ((k - 2 + i / 8) and 1) == 0
                            add(if (axis == 0) Sample(along, y + cross, black) else Sample(x + cross, along, black))
                        }
                    }
                    val match = match(seed, x, y, samples, if (axis == 0) .75 else .875,
                        if (axis == 0) .875 else .75, .125, previous) ?: continue
                    previous = PointD(match.dx, match.dy)
                    val point = landmark(x, y, match.point, 1e-3)
                    if (seed.warp!!.landmarks.none { it.position == point.position }) timing += point
                }
            }
            if (timing.size >= 2) fit(base, seed.warp!!.landmarks + timing)?.let(result::add)
        }
        return result
    }

    private fun patch(x: Double, y: Double, black: (Int, Int) -> Boolean): List<Sample> = buildList {
        for (dy in -2..2) for (dx in -2..2) for (sy in listOf(-.4, 0.0, .4)) for (sx in listOf(-.4, 0.0, .4)) {
            add(Sample(x + dx + sx, y + dy + sy, black(dx, dy)))
        }
    }

    private fun stopped(): Boolean {
        cancellation.check()
        return stopRequested()
    }

    private fun fit(base: GridGeometry, anchors: List<WarpLandmark>): GridGeometry? = try {
        base.copy(model = GeometryModel.THIN_PLATE_SPLINE, warp = ThinPlateWarp(anchors))
            .takeIf { it.hasUsableWarp() }?.reanchored()
    } catch (_: IllegalArgumentException) { null }

    private fun match(
        geometry: GridGeometry, x: Double, y: Double, samples: List<Sample>,
        rangeX: Double, rangeY: Double, step: Double = .25, previous: PointD? = null,
        minimumScore: Double = .8,
    ): Match? {
        val targetMean = samples.count { !it.black }.toDouble() / samples.size
        val targetVariance = targetMean * (1 - targetMean)
        if (targetVariance <= 0) return null
        var best: Match? = null
        var bestObjective = Double.NEGATIVE_INFINITY
        val nx = (rangeX / step).toInt(); val ny = (rangeY / step).toInt()
        for (iy in -ny..ny) {
            if (stopped()) return null
            for (ix in -nx..nx) {
                val dx = ix * step; val dy = iy * step
                var sum = 0.0; var squared = 0.0; var covariance = 0.0; var count = 0
                for (s in samples) {
                    val p = geometry.moduleToImage(s.x + dx, s.y + dy)
                    val raw = image.sampleMean(p.x, p.y, 0.0) ?: break
                    val value = profile.normalize(raw)
                    sum += value; squared += value * value
                    covariance += value * ((if (s.black) 0.0 else 1.0) - targetMean)
                    count++
                }
                if (count != samples.size) continue
                val variance = squared / count - (sum / count) * (sum / count)
                if (variance < 400.0) continue
                val correlation = covariance / count / sqrt(variance * targetVariance)
                val continuity = previous?.let { .15 * ((dx - it.x) * (dx - it.x) + (dy - it.y) * (dy - it.y)) } ?: 0.0
                val objective = correlation - .015 * (dx * dx + dy * dy) - continuity
                if (objective > bestObjective) {
                    bestObjective = objective
                    best = Match(geometry.moduleToImage(x + dx, y + dy), dx, dy, correlation)
                }
            }
        }
        return best?.takeIf { it.score >= minimumScore && abs(it.dx) < rangeX && abs(it.dy) < rangeY }
    }
}
