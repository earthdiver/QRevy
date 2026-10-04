package io.github.earthdiver.qrrepair.core

/** Geometry only: no decoded payload, ECC/mask selection or user edits are accepted here. */
data class InitialGridEstimate(
    val version: Int,
    val moduleCenterQuad: ImageQuad,
    val source: InitialGridSource,
    /** Image-verified positions in logical QR roles; empty unless the stricter assignment gate passes. */
    val autoAssignedFinders: Map<FinderRole, PointD> = emptyMap(),
    val confirmedPolarity: FinderPolarity = FinderPolarity.UNKNOWN,
)

enum class InitialGridSource { ZXING_CPP, PERIODICITY, TWO_FINDER }

/** Combines externally detected boundaries with the finder-independent periodicity fallback. */
object InitialGridEstimator {
    fun estimate(image: LumaImage, detectedBoundaries: List<ImageQuad>): InitialGridEstimate? {
        // Validate every boundary before coalescing: an unverified extra symbol must not
        // disappear merely because another candidate is strong enough to assign roles.
        val inverse by lazy { LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] }) }
        val estimates = detectedBoundaries.map { fromBoundary(image, inverse, it) }
        val native = if (estimates.all { it != null }) mergeNativeEstimates(estimates.filterNotNull()) else null
        if (native != null) return native
        fun periodicity(input: LumaImage) = GridPeriodicityEstimator.estimate(input)?.let {
            InitialGridEstimate(it.version, it.moduleCenterQuad, InitialGridSource.PERIODICITY)
        }
        val normalPeriodicity = periodicity(image)
        val invertedPeriodicity = periodicity(inverse)
        // A conflict is not a detection failure: do not hide it with a later fallback.
        if (normalPeriodicity != null || invertedPeriodicity != null) {
            return mergePolarityEstimates(normalPeriodicity, invertedPeriodicity)
        }
        if (detectedBoundaries.size > 1) return null
        return mergePolarityEstimates(TwoFinderGridEstimator.estimate(image), TwoFinderGridEstimator.estimate(inverse))
    }

    /** Both inputs use source-image coordinates; only image-verified roles establish polarity. */
    internal fun mergePolarityEstimates(normal: InitialGridEstimate?, inverted: InitialGridEstimate?): InitialGridEstimate? {
        fun confirmed(estimate: InitialGridEstimate?, polarity: FinderPolarity) = estimate?.copy(
            confirmedPolarity = if (estimate.autoAssignedFinders.isEmpty()) FinderPolarity.UNKNOWN else polarity,
        )
        // Reuse the native gate: same version/orientation, corners within 0.25 module,
        // no conflicting confirmed polarities, and no averaging of verified candidates.
        return mergeNativeEstimates(listOfNotNull(confirmed(normal, FinderPolarity.NORMAL),
            confirmed(inverted, FinderPolarity.INVERTED)))
    }

    /** One group only; distinct or unverified symbols retain the existing manual fallback. */
    internal fun mergeNativeEstimates(estimates: List<InitialGridEstimate>): InitialGridEstimate? {
        if (estimates.isEmpty()) return null
        // Complete-link agreement prevents a chain of small shifts from merging distant grids.
        for (i in estimates.indices) for (j in i + 1 until estimates.size) {
            val a = estimates[i]; val b = estimates[j]
            if (a.version != b.version) return null
            if (a.confirmedPolarity != FinderPolarity.UNKNOWN && b.confirmedPolarity != FinderPolarity.UNKNOWN &&
                a.confirmedPolarity != b.confirmedPolarity) return null
            val pa = a.moduleCenterQuad.asList(); val pb = b.moduleCenterQuad.asList()
            val intervals = (16 + 4 * a.version).toDouble()
            for (k in 0..3) {
                // Already in logical order: never rotate corners just to make them match.
                val pitch = minOf(pa[k].distanceTo(pa[(k + 1) % 4]), pa[k].distanceTo(pa[(k + 3) % 4]),
                    pb[k].distanceTo(pb[(k + 1) % 4]), pb[k].distanceTo(pb[(k + 3) % 4])) / intervals
                if (!pitch.isFinite() || pitch <= 0 || pa[k].distanceTo(pb[k]) > .25 * pitch) return null
            }
        }
        // Keep an individually verified candidate, never an untested average. Prefer a
        // candidate that passed the stricter role gate, then the medoid with stable tie-breaking.
        fun distanceSum(e: InitialGridEstimate): Double = estimates.sumOf { other ->
            e.moduleCenterQuad.asList().zip(other.moduleCenterQuad.asList()).sumOf { (a, b) -> a.distanceTo(b) }
        }
        return estimates.minWithOrNull(compareBy<InitialGridEstimate> { it.autoAssignedFinders.isEmpty() }
            .thenBy { distanceSum(it) }
            .thenBy { it.moduleCenterQuad.topLeft.x }.thenBy { it.moduleCenterQuad.topLeft.y }
            .thenBy { it.moduleCenterQuad.topRight.x }.thenBy { it.moduleCenterQuad.topRight.y }
            .thenBy { it.moduleCenterQuad.bottomRight.x }.thenBy { it.moduleCenterQuad.bottomRight.y }
            .thenBy { it.moduleCenterQuad.bottomLeft.x }.thenBy { it.moduleCenterQuad.bottomLeft.y })
    }

    /** Native positions are outer boundaries, whereas the editor stores outer module centers. */
    internal fun fromBoundary(image: LumaImage, boundary: ImageQuad): InitialGridEstimate? =
        fromBoundary(image, LumaImage(image.width, image.height,
            IntArray(image.pixels.size) { 255 - image.pixels[it] }), boundary)

    private fun fromBoundary(image: LumaImage, inverse: LumaImage, boundary: ImageQuad): InitialGridEstimate? {
        val normal = fromNormalizedBoundary(image, boundary)
        val inverted = fromNormalizedBoundary(inverse, boundary)
        // Conflicting polarity evidence must not silently select an orientation.
        if (normal != null && inverted != null) return null
        val result = normal ?: inverted ?: return null
        return result.copy(confirmedPolarity = if (result.autoAssignedFinders.isEmpty()) FinderPolarity.UNKNOWN
            else if (normal != null) FinderPolarity.NORMAL else FinderPolarity.INVERTED)
    }

    private fun fromNormalizedBoundary(image: LumaImage, boundary: ImageQuad): InitialGridEstimate? {
        val points = boundary.asList()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        for (i in 0..3) {
            val a = points[(i + 1) % 4] - points[i]
            val b = points[(i + 2) % 4] - points[(i + 1) % 4]
            val cross = a.x * b.y - a.y * b.x
            if (!cross.isFinite() || cross <= 0.0) return null
        }
        val threshold = image.thresholdInside(boundary) ?: return null
        data class Choice(val version: Int, val quad: ImageQuad, val mismatch: Double,
            val worstFinderMismatch: Double, val timingMismatch: Double, val separatorMismatch: Double)
        val allScores = mutableListOf<Double>()
        val choices = mutableListOf<Choice>()
        for (version in 1..40) {
            val n = 17 + 4 * version
            if ((0..3).any { points[it].distanceTo(points[(it + 1) % 4]) / n < .75 }) continue
            for (rotation in 0..3) {
                try {
                    val h = Homography.fromCorrespondences(
                        listOf(PointD(0.0, 0.0), PointD(n.toDouble(), 0.0),
                            PointD(n.toDouble(), n.toDouble()), PointD(0.0, n.toDouble())),
                        List(4) { points[(it + rotation) % 4] },
                    )
                    // Outer edges may be cropped, but the convex grid's extreme centers must be visible.
                    val centers = listOf(h.map(.5, .5), h.map(n - .5, .5),
                        h.map(n - .5, n - .5), h.map(.5, n - .5))
                    if (centers.any { !it.x.isFinite() || !it.y.isFinite() || !image.inBounds(it.x, it.y) }) continue
                    fun mismatch(x: Int, y: Int, black: Boolean): Double {
                        val p = h.map(x + .5, y + .5)
                        val value = image.sampleMean(p.x, p.y, 0.0) ?: return 1.0
                        return if ((value <= threshold) == black) 0.0 else 1.0
                    }
                    var finderMismatch = 0.0
                    var worstFinderMismatch = 0.0
                    var separatorMismatch = 0.0
                    for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                        var localMismatch = 0.0
                        for (y in 0..6) for (x in 0..6) {
                            localMismatch += mismatch(sx + x, sy + y,
                                x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4))
                        }
                        finderMismatch += localMismatch
                        worstFinderMismatch = maxOf(worstFinderMismatch, localMismatch / 49)
                        val separatorX = if (sx == 0) 7 else -1
                        val separatorY = if (sy == 0) 7 else -1
                        var whiteMismatch = 0.0
                        for (i in 0..6) {
                            whiteMismatch += mismatch(sx + separatorX, sy + i, false)
                            whiteMismatch += mismatch(sx + i, sy + separatorY, false)
                        }
                        whiteMismatch += mismatch(sx + separatorX, sy + separatorY, false)
                        separatorMismatch = maxOf(separatorMismatch, whiteMismatch / 15)
                    }
                    finderMismatch /= 147
                    var timingMismatch = 0.0
                    for (i in 8..n - 9) {
                        timingMismatch += mismatch(i, 6, i % 2 == 0) + mismatch(6, i, i % 2 == 0)
                    }
                    timingMismatch /= 2 * (n - 16)
                    val score = .55 * finderMismatch + .45 * timingMismatch
                    allScores += score // Include rejected alternatives when measuring the assignment margin.
                    // Conservative structural gate; agreement is a heuristic, not a probability.
                    if (finderMismatch > .20 || timingMismatch > .20) continue
                    choices += Choice(version, ImageQuad(centers[0], centers[1], centers[2], centers[3]),
                        score, worstFinderMismatch, timingMismatch, separatorMismatch)
                } catch (_: IllegalArgumentException) {
                    // Degenerate/projectively invalid boundaries must not break image import.
                }
            }
        }
        val ordered = choices.sortedBy { it.mismatch }
        val best = ordered.firstOrNull() ?: return null
        // Ambiguous version or orientation remains a manual/periodicity task.
        if (ordered.drop(1).any { it.mismatch - best.mismatch < .03 }) return null
        // Auto-assignment is stricter than merely proposing a grid. Every Finder and its
        // separator must agree independently, with enough resolution and a clear runner-up gap.
        val n = 17 + 4 * best.version
        val assignmentMargin = allScores.sorted().getOrNull(1)?.minus(best.mismatch) ?: 1.0
        val assign = best.worstFinderMismatch <= 2.0 / 49 && best.timingMismatch <= .05 &&
            best.separatorMismatch <= 1.0 / 15 && assignmentMargin >= .08 &&
            (0..3).all { points[it].distanceTo(points[(it + 1) % 4]) / n >= 2.0 }
        var finders: Map<FinderRole, PointD> = emptyMap()
        if (assign) {
            val h = Homography.fromCorrespondences(
                listOf(PointD(.5, .5), PointD(n - .5, .5), PointD(n - .5, n - .5), PointD(.5, n - .5)),
                best.quad.asList(),
            )
            // Verify several points within each module, not just centers. A slightly displaced
            // quad can match centers yet place a Finder too far away for an automatic constraint.
            val wellCentered = listOf(0 to 0, n - 7 to 0, 0 to n - 7).all { (sx, sy) ->
                listOf(-.25 to 0.0, .25 to 0.0, 0.0 to -.25, 0.0 to .25).all { (dx, dy) ->
                    var errors = 0
                    for (y in 0..6) for (x in 0..6) {
                        val p = h.map(sx + x + .5 + dx, sy + y + .5 + dy)
                        val value = image.sampleMean(p.x, p.y, 0.0)
                        val black = x == 0 || y == 0 || x == 6 || y == 6 || (x in 2..4 && y in 2..4)
                        if (value == null || (value <= threshold) != black) errors++
                    }
                    errors <= 2
                }
            }
            if (wellCentered) finders = mapOf(FinderRole.TOP_LEFT to h.map(3.5, 3.5),
                FinderRole.TOP_RIGHT to h.map(n - 3.5, 3.5),
                FinderRole.BOTTOM_LEFT to h.map(3.5, n - 3.5))
        }
        return InitialGridEstimate(best.version, best.quad, InitialGridSource.ZXING_CPP, finders)
    }
}
