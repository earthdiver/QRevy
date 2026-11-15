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
    maxContourWorkingBytes: Long = minOf(CurvedFinderContours.DEFAULT_WORKING_BYTES,
        RecoveryMemoryPolicy.current().workingBytes),
    private val cachePatternSamples: Boolean = true,
) {
    private data class Sample(val x: Double, val y: Double, val black: Boolean)
    private data class Match(val point: PointD, val dx: Double, val dy: Double, val score: Double)

    private val contours = CurvedFinderContours(image, profile.inverted, maxContourWorkingBytes, stopped = ::stopped)
    val contourMemoryLimited: Boolean get() = contours.memoryLimited

    fun candidates(base: GridGeometry, maxCandidates: Int = 12): List<GridGeometry> {
        return detailedCandidates(base, maxCandidates).toList()
    }

    /** Same order and cap as detailed candidate collection; consumption may be interleaved with decoding. */
    fun detailedCandidates(base: GridGeometry, maxCandidates: Int = 12): Sequence<GridGeometry> {
        require(maxCandidates >= 0)
        return candidateSequence(base, patchesFirst = false).take(maxCandidates)
    }

    /** Yield useful mappings before whole-image contours or later timing fits are evaluated. */
    fun progressiveCandidates(base: GridGeometry): Sequence<GridGeometry> =
        candidateSequence(base, patchesFirst = true).take(12)

    private fun candidateSequence(base: GridGeometry, patchesFirst: Boolean): Sequence<GridGeometry> = sequence {
        if (base.warp != null || stopped()) return@sequence
        if (patchesFirst) yieldAll(patchCandidates(base, preferredFirst = true))
        if (stopped()) return@sequence
        val contourSeeds = if (patchesFirst) contours.progressiveSeeds(base) else contours.seeds(base).asSequence()
        for (seed in contourSeeds.take(2)) {
            val last = (base.dimension - 1).toDouble()
            val anchors = seed.points.map { p -> WarpLandmark(
                PointD(p.module.x / last, p.module.y / last), p.image - seed.base.moduleToImage(p.module.x, p.module.y)) }
            yieldAll(refine(seed.base, anchors, centerAlignment = true, preferredFirst = patchesFirst))
            if (stopped()) return@sequence
        }
        if (!patchesFirst) yieldAll(patchCandidates(base))
    }

    private fun patchCandidates(base: GridGeometry, preferredFirst: Boolean = false): Sequence<GridGeometry> = sequence {
        if (base.warp != null || stopped()) return@sequence
        val last = (base.dimension - 1).toDouble()
        fun landmark(x: Double, y: Double, imagePoint: PointD, smoothing: Double = 1e-5): WarpLandmark =
            WarpLandmark(PointD(x / last, y / last), imagePoint - base.moduleToImage(x, y), smoothing)
        val anchors = ArrayList<WarpLandmark>()
        // Four overlapping corner patches measure each finder locally, including its white separator.
        for (role in FinderRole.entries) {
            val center = base.finderModulePoint(role)
            val measured = ArrayList<WarpLandmark>()
            for ((ox, oy) in listOf(-2 to -2, 2 to -2, 2 to 2, -2 to 2)) {
                if (stopped()) return@sequence
                val x = center.x + ox; val y = center.y + oy
                val samples = patch(x, y) { dx, dy ->
                    val ring = max(abs(ox + dx), abs(oy + dy))
                    ring <= 1 || ring == 3
                }
                val match = match(base, x, y, samples, 1.5, 1.5) ?: continue
                measured += landmark(x, y, match.point)
            }
            // A single ambiguous stripe must not displace an entire corner.
            if (measured.size < 3) return@sequence
            anchors += measured
        }
        yieldAll(refine(base, anchors, preferredFirst = preferredFirst))
    }

    private fun refine(base: GridGeometry, anchors: List<WarpLandmark>, centerAlignment: Boolean = false,
        preferredFirst: Boolean = false): Sequence<GridGeometry> = sequence {
        val last = (base.dimension - 1).toDouble()
        fun landmark(x: Double, y: Double, imagePoint: PointD, smoothing: Double = 1e-5) =
            WarpLandmark(PointD(x / last, y / last), imagePoint - base.moduleToImage(x, y), smoothing)
        val first = fit(base, anchors) ?: return@sequence
        val seeds = mutableListOf(first)
        if (!preferredFirst) yield(first)
        val aligned = anchors.toMutableList()
        val centers = Version.getVersionForNumber(base.version).alignmentPatternCenters
        val positions = centers.flatMap { x -> centers.map { y -> x to y } }.filter { (x, y) ->
            !((x <= 8 && y <= 8) || (x >= base.dimension - 9 && y <= 8) || (x <= 8 && y >= base.dimension - 9))
        }.sortedByDescending { (x, y) -> x + y }.take(8)
        for ((mx, my) in positions) {
            if (stopped()) return@sequence
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
        if (aligned.size > anchors.size) fit(base, aligned)?.let { seeds.add(it); yield(it) }
        if (stopped()) return@sequence
        for (seed in if (preferredFirst) seeds.asReversed() else seeds) {
            // Refine the aligned mapping before revisiting the finder-only fallback.
            if (preferredFirst && seed === first) yield(first)
            val timing = ArrayList<WarpLandmark>()
            for (axis in 0..1) {
                var previous = PointD(0.0, 0.0)
                val modules = (10 until base.dimension - 10 step 2).toList()
                val selected = if (modules.size <= 16) modules else (0..15).map { modules[it * (modules.size - 1) / 15] }
                for (k in selected) {
                    if (stopped()) return@sequence
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
                        if (axis == 0) .875 else .75, .125, previous, rejectAmbiguousPolarity = true) ?: continue
                    previous = PointD(match.dx, match.dy)
                    val point = landmark(x, y, match.point, 1e-3)
                    if (seed.warp!!.landmarks.none { it.position == point.position }) timing += point
                }
            }
            if (timing.size >= 2) fit(base, seed.warp!!.landmarks + timing)?.let { yield(it) }
        }
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
        rejectAmbiguousPolarity: Boolean = false,
    ): Match? {
        val targetMean = samples.count { !it.black }.toDouble() / samples.size
        val targetVariance = targetMean * (1 - targetMean)
        if (targetVariance <= 0) return null
        var best: Match? = null
        var bestObjective = Double.NEGATIVE_INFINITY
        var oppositeCorrelation = Double.NEGATIVE_INFINITY
        val nx = (rangeX / step).toInt(); val ny = (rangeY / step).toInt()
        val cache = if (cachePatternSamples) PatternSampleCache.create(
            DoubleArray(samples.size) { samples[it].x }, DoubleArray(samples.size) { samples[it].y }, nx, ny, step) else null
        val observe: (Double, Double) -> Double? = { mx, my ->
            val p = geometry.moduleToImage(mx, my)
            image.sampleMean(p.x, p.y, 0.0)?.let(profile::normalize)
        }
        for (iy in -ny..ny) {
            if (stopped()) return null
            for (ix in -nx..nx) {
                val dx = ix * step; val dy = iy * step
                var sum = 0.0; var squared = 0.0; var covariance = 0.0; var count = 0
                for (i in samples.indices) {
                    val s = samples[i]
                    val value = if (cache != null) cache.value(i, ix + nx, iy + ny, observe)
                        else observe(s.x + dx, s.y + dy) ?: Double.NaN
                    if (value.isNaN()) break
                    sum += value; squared += value * value
                    covariance += value * ((if (s.black) 0.0 else 1.0) - targetMean)
                    count++
                }
                if (count != samples.size) continue
                val variance = squared / count - (sum / count) * (sum / count)
                if (variance < 400.0) continue
                val correlation = covariance / count / sqrt(variance * targetVariance)
                // An alternating stripe can match a locally reversed pattern after a one-cell
                // phase shift. Compare image evidence before continuity/displacement priors:
                // those priors must not turn an ambiguous correspondence into an observation.
                if (rejectAmbiguousPolarity && abs(dx) < rangeX && abs(dy) < rangeY) {
                    oppositeCorrelation = max(oppositeCorrelation, -correlation)
                }
                val continuity = previous?.let { .15 * ((dx - it.x) * (dx - it.x) + (dy - it.y) * (dy - it.y)) } ?: 0.0
                val objective = correlation - .015 * (dx * dx + dy * dy) - continuity
                if (objective > bestObjective) {
                    bestObjective = objective
                    best = Match(geometry.moduleToImage(x + dx, y + dy), dx, dy, correlation)
                }
            }
        }
        // Keep the finder/alignment-only seeds when timing polarity cannot be distinguished.
        if (rejectAmbiguousPolarity && oppositeCorrelation >= minimumScore &&
            (best?.score ?: Double.NEGATIVE_INFINITY) - oppositeCorrelation < .05) return null
        return best?.takeIf { it.score >= minimumScore && abs(it.dx) < rangeX && abs(it.dy) < rangeY }
    }
}
