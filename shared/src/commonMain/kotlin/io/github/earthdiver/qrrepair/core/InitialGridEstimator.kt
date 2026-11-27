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
    fun estimate(image: LumaImage, detectedBoundaries: List<ImageQuad>,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        trace: GridEstimationTrace? = null): InitialGridEstimate? {
        cancellation.check()
        // External boundaries are validated in source coordinates; they do not depend on the crop margin.
        if (detectedBoundaries.isNotEmpty()) nativeEstimate(image, detectedBoundaries, cancellation, trace)?.let { return it }
        val originalEstimate by lazy { estimateInImage(image, detectedBoundaries, cancellation, trace) }
        // Several external symbols remain ambiguous; a single symbol window cannot decide between them.
        if (detectedBoundaries.size <= 1) trace.measure("SYMBOL_WINDOW") {
            estimateInSymbolWindow(image, cancellation, trace) { originalEstimate }
        }?.let { return it }
        return originalEstimate
    }

    private fun nativeEstimate(image: LumaImage, detectedBoundaries: List<ImageQuad>,
        cancellation: RecoveryCancellation, trace: GridEstimationTrace?): InitialGridEstimate? {
        // Validate every boundary before coalescing: an unverified extra symbol must not
        // disappear merely because another candidate is strong enough to assign roles.
        val inverse by lazy { LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] }) }
        val estimates = trace.measure("NATIVE_BOUNDARIES") {
            detectedBoundaries.map { fromBoundary(image, inverse, it, cancellation) }
        }
        return if (estimates.all { it != null }) mergeNativeEstimates(estimates.filterNotNull()) else null
    }

    /**
     * Estimates in a window derived from the symbol content ([SymbolExtent]) rather than from the crop.
     * The window adds a fixed proportional margin, filled with the surround tone where the crop is
     * tighter, so a tight crop and a crop with a wide clean margin present the same pixels to the
     * estimator. When that finds nothing, the content bounds alone are tried: a symbol whose outer
     * modules are unknown has no edge of its own, and only the content extent can delimit it.
     * Pixels inside the image are copied unchanged; the result is mapped back to source coordinates.
     */
    private fun estimateInSymbolWindow(image: LumaImage,
        cancellation: RecoveryCancellation, trace: GridEstimationTrace?,
        originalEstimate: () -> InitialGridEstimate?): InitialGridEstimate? {
        cancellation.check()
        val bounds = SymbolExtent.bounds(image) ?: return null
        // A light surround is the usual quiet zone; a dark one may belong to a light-on-dark symbol, and
        // content reaching the image edge leaves either possible.
        val surround = SymbolExtent.surroundTone(image)
        val fills = when {
            surround == null -> listOf(255, 0)
            surround >= SymbolExtent.LIGHT_BORDER -> listOf(255)
            else -> listOf(0, 255)
        }
        val side = maxOf(bounds[2] - bounds[0] + 1, bounds[3] - bounds[1] + 1)
        for (margin in listOf(maxOf(8, (side * SYMBOL_WINDOW_MARGIN).toInt()), 0)) {
            cancellation.check()
            if (margin == 0 && bounds.contentEquals(intArrayOf(0, 0, image.width - 1, image.height - 1))) {
                originalEstimate()?.let { return it }
                continue
            }
            val inside = bounds[0] >= margin && bounds[1] >= margin &&
                bounds[2] + margin < image.width && bounds[3] + margin < image.height
            for (fill in if (inside) fills.take(1) else fills) {
                estimateInWindow(image, bounds, margin, fill, cancellation, trace)?.let { return it }
            }
        }
        return null
    }

    private fun estimateInWindow(image: LumaImage, bounds: IntArray, margin: Int, fill: Int,
        cancellation: RecoveryCancellation, trace: GridEstimationTrace?): InitialGridEstimate? {
        cancellation.check()
        val left = bounds[0] - margin; val top = bounds[1] - margin
        val width = bounds[2] - bounds[0] + 1 + 2 * margin; val height = bounds[3] - bounds[1] + 1 + 2 * margin
        val pixels = IntArray(width * height) { fill }
        val x0 = maxOf(0, left); val x1 = minOf(image.width, left + width)
        for (y in maxOf(0, top) until minOf(image.height, top + height))
            image.pixels.copyInto(pixels, (y - top) * width + (x0 - left), y * image.width + x0, y * image.width + x1)
        val windowed = estimateInImage(LumaImage(width, height, pixels), emptyList(), cancellation, trace) ?: return null
        fun back(p: PointD) = PointD(p.x + left, p.y + top)
        val quad = windowed.moduleCenterQuad.asList().map(::back)
        val dimension = 17 + 4 * windowed.version
        val pitch = (quad[0].distanceTo(quad[1]) + quad[0].distanceTo(quad[3])) / (2.0 * (dimension - 1))
        // The symbol must come from the original pixels, not from the added uniform border.
        if (quad.any { it.x < -1.5 * pitch || it.y < -1.5 * pitch ||
                it.x > image.width - 1 + 1.5 * pitch || it.y > image.height - 1 + 1.5 * pitch }) return null
        return windowed.copy(moduleCenterQuad = ImageQuad(quad[0], quad[1], quad[2], quad[3]),
            autoAssignedFinders = windowed.autoAssignedFinders.mapValues { back(it.value) })
    }

    private const val SYMBOL_WINDOW_MARGIN = 0.15

    /** Periodicity and two-Finder fallbacks after external boundaries, if any, did not validate. */
    private fun estimateInImage(image: LumaImage, detectedBoundaries: List<ImageQuad>,
        cancellation: RecoveryCancellation, trace: GridEstimationTrace?): InitialGridEstimate? {
        cancellation.check()
        val inverse by lazy { LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] }) }
        fun periodicity(input: LumaImage, estimate: GridPeriodicityEstimate?) = estimate?.let {
            InitialGridEstimate(it.version, it.moduleCenterQuad, InitialGridSource.PERIODICITY)
        }?.takeUnless { hasUniformPerimeter(input, it) && !PeriodicityStructure.hasTimingSupport(input, it) }
        val normal = trace.measure("NORMAL_PERIODICITY") { GridPeriodicityEstimator.estimate(image, cancellation, trace) }
        val inverted = trace.measure("INVERTED_PERIODICITY") { GridPeriodicityEstimator.estimate(inverse, cancellation, trace) }
        val normalPeriodicity = periodicity(image, normal)
        val invertedPeriodicity = periodicity(inverse, inverted)
        if (normalPeriodicity != null || invertedPeriodicity != null) {
            val merged = mergePolarityEstimates(normalPeriodicity, invertedPeriodicity)
            if (merged != null || normalPeriodicity == null || invertedPeriodicity == null || detectedBoundaries.size > 1)
                return merged
            // An inverted quiet zone can look like a larger periodic grid. Resolve only with
            // independently verified QR structure; unresolved conflicts still stop the fallback.
            return trace.measure("POLARITY_CHECK") {
                resolvePeriodicityConflict(image, inverse, normalPeriodicity, invertedPeriodicity, cancellation)
                    ?: PeriodicityStructure.resolveQuietZoneAlias(image, inverse,
                        requireNotNull(normal), requireNotNull(inverted), cancellation)
            }
        }
        if (detectedBoundaries.size > 1) return null
        return mergePolarityEstimates(
            trace.measure("NORMAL_TWO_FINDERS") { TwoFinderGridEstimator.estimate(image, cancellation) },
            trace.measure("INVERTED_TWO_FINDERS") { TwoFinderGridEstimator.estimate(inverse, cancellation) })
            ?: if (normal != null && inverted != null) trace.measure("QUIET_ZONE_ALIAS") {
                PeriodicityStructure.resolveQuietZoneAlias(image, inverse, normal, inverted, cancellation)
            } else null
    }

    /**
     * Flags a potentially uniform surround or insufficient perimeter evidence. Damaged symbols can
     * also meet this condition, so independent timing support is checked before rejecting a grid.
     */
    internal fun hasUniformPerimeter(image: LumaImage, estimate: InitialGridEstimate): Boolean {
        val n = 17 + 4 * estimate.version
        val last = (n - 1).toDouble()
        val map = runCatching {
            Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
                estimate.moduleCenterQuad.asList())
        }.getOrNull() ?: return true
        var dark = 0
        var light = 0
        var total = 0
        for (i in 0 until n - 1) for ((x, y) in listOf(i to 0, n - 1 to i, n - 1 - i to n - 1, 0 to n - 1 - i)) {
            total++
            val p = map.map(x.toDouble(), y.toDouble())
            val xi = kotlin.math.floor(p.x).toInt(); val yi = kotlin.math.floor(p.y).toInt()
            if (xi !in 0 until image.width || yi !in 0 until image.height) continue
            if (image.luma(xi, yi) <= image.threshold) dark++ else light++
        }
        val known = dark + light
        return known * 2 < total || minOf(dark, light) < maxOf(3.0, known * UNIFORM_PERIMETER_FRACTION)
    }

    /** Minimum minority-color fraction before independent structural support is required. */
    private const val UNIFORM_PERIMETER_FRACTION = 0.02

    /** Preserve periodicity geometry and role assignment; this only adjudicates conflicting grids. */
    internal fun resolvePeriodicityConflict(image: LumaImage, inverse: LumaImage,
        normal: InitialGridEstimate, inverted: InitialGridEstimate,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE): InitialGridEstimate? {
        fun supported(input: LumaImage, estimate: InitialGridEstimate): Boolean {
            cancellation.check()
            val last = (16 + 4 * estimate.version).toDouble()
            return try {
                val h = Homography.fromCorrespondences(
                    listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last)),
                    estimate.moduleCenterQuad.asList())
                val boundary = ImageQuad(h.map(-.5, -.5), h.map(last + .5, -.5),
                    h.map(last + .5, last + .5), h.map(-.5, last + .5))
                // Reuse the boundary verifier's strict three-Finder, separator, timing and
                // centering checks. Test only the proposed version, not a new version search.
                fromNormalizedBoundary(input, boundary, cancellation, estimate.version..estimate.version)
                    ?.autoAssignedFinders?.size == 3
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        val normalSupported = supported(image, normal)
        val invertedSupported = supported(inverse, inverted)
        return when {
            normalSupported == invertedSupported -> null
            normalSupported -> normal
            else -> inverted
        }
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

    /** Validates a platform detector's outer boundary and returns the editor's outer module centers. */
    fun fromBoundary(image: LumaImage, boundary: ImageQuad): InitialGridEstimate? =
        fromBoundary(image, LumaImage(image.width, image.height,
            IntArray(image.pixels.size) { 255 - image.pixels[it] }), boundary)

    private fun fromBoundary(image: LumaImage, inverse: LumaImage, boundary: ImageQuad,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE): InitialGridEstimate? {
        val normal = fromNormalizedBoundary(image, boundary, cancellation)
        val inverted = fromNormalizedBoundary(inverse, boundary, cancellation)
        // Conflicting polarity evidence must not silently select an orientation.
        if (normal != null && inverted != null) return null
        val result = normal ?: inverted ?: return null
        return result.copy(confirmedPolarity = if (result.autoAssignedFinders.isEmpty()) FinderPolarity.UNKNOWN
            else if (normal != null) FinderPolarity.NORMAL else FinderPolarity.INVERTED)
    }

    private fun fromNormalizedBoundary(image: LumaImage, boundary: ImageQuad,
        cancellation: RecoveryCancellation, versions: IntRange = 1..40): InitialGridEstimate? {
        cancellation.check()
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
        for (version in versions) {
            cancellation.check()
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
