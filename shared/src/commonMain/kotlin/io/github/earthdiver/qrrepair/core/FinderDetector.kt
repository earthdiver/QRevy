package io.github.earthdiver.qrrepair.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Finder detector that intentionally does not require the other two finder patterns.
 * It first searches 1:1:3:1:1 run ratios and then fits a 7x7 finder template to estimate
 * module pitch and QR-axis angle (modulo 90 degrees).
 */
class FinderDetector(
    private val image: LumaImage,
    blackThresholdOverride: Int? = null,
    whiteThresholdOverride: Int? = null,
    private val minimumModuleSize: Double = .8,
) {
    private val blackThreshold = blackThresholdOverride?.coerceIn(0, 254) ?: image.threshold
    private val whiteThreshold = whiteThresholdOverride?.coerceIn(blackThreshold + 1, 255)
        ?: (blackThreshold + 1).coerceAtMost(255)
    private enum class PixelClass { BLACK, WHITE, UNKNOWN }
    private data class RawCandidate(
        var x: Double,
        var y: Double,
        var module: Double,
        var count: Int,
        var ratioScore: Double,
        var strongSeed: Boolean,
        val componentSeed: Boolean = false,
    )

    private data class Run(val value: PixelClass, val start: Int, val length: Int)

    fun detect(maxCandidates: Int = 6): List<FinderCandidate> {
        val rowStep = max(1, image.height / 700)
        return rankedCandidates(scanRows(0, image.height, rowStep) + blackComponentSeeds(), maxCandidates)
    }

    /**
     * Treats a user tap as an approximate hint and determines the finder center, pitch, and angle
     * from image evidence in its neighborhood. Local rows are scanned densely, so a finder that
     * was skipped by the bounded global scan can still be recovered.
     */
    fun detectNear(
        approximate: PointD,
        searchRadius: Double = max(32.0, minOf(image.width, image.height) * 0.18),
    ): FinderCandidate? {
        require(searchRadius > 0.0 && searchRadius.isFinite())
        val y0 = (approximate.y - searchRadius).toInt().coerceIn(0, image.height)
        val y1 = (approximate.y + searchRadius + 1.0).toInt().coerceIn(0, image.height)
        val accept: (Double, Double) -> Boolean = { x, y ->
            hypot(x - approximate.x, y - approximate.y) <= searchRadius
        }
        val x0 = (approximate.x - searchRadius).toInt().coerceIn(0, image.width)
        val x1 = (approximate.x + searchRadius + 1.0).toInt().coerceIn(0, image.width)
        val local = scanRows(y0, y1, 1, accept) +
            blackComponentSeeds(accept, intArrayOf(x0, y0, x1, y1))
        return rankedCandidates(local, 12)
            .filter { hypot(it.centerX - approximate.x, it.centerY - approximate.y) <= searchRadius }
            .maxByOrNull {
                it.score - 0.18 * hypot(it.centerX - approximate.x, it.centerY - approximate.y) / searchRadius
            }
    }

    private fun scanRows(
        startY: Int,
        endY: Int,
        rowStep: Int,
        accept: (Double, Double) -> Boolean = { _, _ -> true },
    ): List<RawCandidate> {
        val raw = mutableListOf<RawCandidate>()
        var y = startY
        while (y < endY) {
            val runs = rowRuns(y)
            for (i in 0..runs.size - 5) {
                val r = runs.subList(i, i + 5)
                if (r.map { it.value } != listOf(
                        PixelClass.BLACK, PixelClass.WHITE, PixelClass.BLACK,
                        PixelClass.WHITE, PixelClass.BLACK,
                    )
                ) continue
                val lengths = IntArray(5) { r[it].length }
                val ratio = ratioScore(lengths) ?: continue
                val total = lengths.sum()
                val cx = r[0].start + lengths[0] + lengths[1] + lengths[2] / 2.0
                if (!accept(cx, y.toDouble())) continue
                val vertical = crossCheckVertical(cx.toInt(), y, total) ?: continue
                val cy = vertical.first
                if (!accept(cx, cy)) continue
                val module = (total / 7.0 + vertical.second) / 2.0
                merge(raw, cx, cy, module, ratio, strongSeed = true)
            }
            // A damaged outer ring no longer produces the complete 1:1:3:1:1 signature. The
            // surviving white/black/white core still carries a useful 1:3:1 cue. Treat this only
            // as a weak seed; the two-dimensional template refinement below remains the gate.
            for (i in 0..runs.size - 3) {
                val r = runs.subList(i, i + 3)
                if (r.map { it.value } != listOf(PixelClass.WHITE, PixelClass.BLACK, PixelClass.WHITE)) continue
                val partial = partialRatioScore(r[0].length, r[1].length, r[2].length) ?: continue
                val module = r[1].length / 3.0
                val cx = r[1].start + (r[1].length - 1) / 2.0
                if (!accept(cx, y.toDouble())) continue
                val vertical = crossCheckPartialVertical(cx.toInt(), y, module) ?: continue
                if (!accept(cx, vertical.first)) continue
                merge(raw, cx, vertical.first, (module + vertical.second) / 2.0, partial + 6.0, strongSeed = false)
            }
            y += rowStep
        }
        return raw
    }

    /**
     * Seeds the isolated dark 3x3 center of a finder independently of run ratios. This remains
     * useful under perspective, display moire, or overlay contamination where a horizontal or
     * vertical 1:1:3:1:1 cross is broken but the nested-square center is still obvious.
     */
    private fun blackComponentSeeds(
        accept: (Double, Double) -> Boolean = { _, _ -> true },
        bounds: IntArray = intArrayOf(0, 0, image.width, image.height),
    ): List<RawCandidate> {
        val x0 = bounds[0].coerceIn(0, image.width)
        val y0 = bounds[1].coerceIn(0, image.height)
        val x1 = bounds[2].coerceIn(x0, image.width)
        val y1 = bounds[3].coerceIn(y0, image.height)
        val regionWidth = x1 - x0
        val regionHeight = y1 - y0
        if (regionWidth == 0 || regionHeight == 0) return emptyList()
        // Local finder passes used to allocate two full-image arrays even though only a corner
        // region was searched. On 1800px camera frames that caused heavy GC and minute-long UI
        // stalls. Store visitation locally while keeping global pixel indices in the queue.
        val visited = BooleanArray(regionWidth * regionHeight)
        val queue = IntArray(regionWidth * regionHeight)
        val out = mutableListOf<RawCandidate>()
        fun visitedIndex(x: Int, y: Int) = (y - y0) * regionWidth + (x - x0)
        for (sy in y0 until y1) for (sx in x0 until x1) {
            val startVisited = visitedIndex(sx, sy)
            if (visited[startVisited] || classify(sx, sy) != PixelClass.BLACK) continue
            var head = 0; var tail = 0
            queue[tail++] = sy * image.width + sx; visited[startVisited] = true
            var minX = sx; var maxX = sx; var minY = sy; var maxY = sy; var area = 0
            while (head < tail) {
                val index = queue[head++]; val x = index % image.width; val y = index / image.width
                area++; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                fun add(nx: Int, ny: Int) {
                    if (nx !in x0 until x1 || ny !in y0 until y1) return
                    val vi = visitedIndex(nx, ny)
                    if (!visited[vi] && classify(nx, ny) == PixelClass.BLACK) {
                        visited[vi] = true; queue[tail++] = ny * image.width + nx
                    }
                }
                add(x - 1, y); add(x + 1, y); add(x, y - 1); add(x, y + 1)
            }
            val width = maxX - minX + 1; val height = maxY - minY + 1
            if (minOf(width, height) < 3 || maxOf(width, height) > minOf(image.width, image.height) / 3) continue
            val aspect = width.toDouble() / height
            val fill = area.toDouble() / (width * height)
            if (aspect !in .58..1.72 || fill < .48) continue
            val cx = (minX + maxX) / 2.0; val cy = (minY + maxY) / 2.0
            if (!accept(cx, cy)) continue
            out += RawCandidate(cx, cy, (width + height) / 6.0, 2, 8.0, strongSeed = false, componentSeed = true)
        }
        return out
    }

    private fun rankedCandidates(raw: List<RawCandidate>, maxCandidates: Int): List<FinderCandidate> =
        raw.filter {
            it.count >= 1 &&
                // Refinement tests hundreds of angle/scale combinations per seed. Reject display
                // moire and single-pixel texture before that expensive work. Keep seeds whose
                // largest coarse scale can still reach the requested useful finder pitch.
                it.module * 1.36 >= minimumModuleSize
        }
            // Keep separate refinement budgets. In a camera scene, text and UI decoration can
            // generate many nominally complete crosses; they must not starve a perspective-
            // distorted real finder or an independently found nested-square center.
            .let { candidates ->
                listOf(0, 1, 2).flatMap { group ->
                    candidates.filter {
                        when (group) {
                            0 -> it.strongSeed
                            1 -> it.componentSeed
                            else -> !it.strongSeed && !it.componentSeed
                        }
                    }
                        .sortedWith(compareByDescending<RawCandidate> { it.count }.thenBy { it.ratioScore })
                        .take(maxCandidates * 3)
                }
            }
            .mapNotNull { refine(it) }
            .sortedByDescending { it.reliabilityScore() }
            .fold(mutableListOf<FinderCandidate>()) { acc, c ->
                if (acc.none { hypot(it.centerX - c.centerX, it.centerY - c.centerY) < max(it.moduleSize, c.moduleSize) * 3.0 }) {
                    acc += c
                }
                acc
            }
            .take(maxCandidates)

    private fun rowRuns(y: Int): List<Run> {
        if (image.width == 0) return emptyList()
        val out = ArrayList<Run>()
        var start = 0
        var current = classify(0, y)
        for (x in 1 until image.width) {
            val value = classify(x, y)
            if (value != current) {
                out += Run(current, start, x - start)
                start = x
                current = value
            }
        }
        out += Run(current, start, image.width - start)
        return out
    }

    private fun ratioScore(lengths: IntArray): Double? {
        val total = lengths.sum().toDouble()
        if (total < 7.0) return null
        val m = total / 7.0
        val target = doubleArrayOf(m, m, 3.0 * m, m, m)
        // Finder scans in real photos are noisy; keep this permissive and let template fitting reject false positives.
        if (abs(lengths[2] - target[2]) > 1.7 * m) return null
        for (i in intArrayOf(0, 1, 3, 4)) if (abs(lengths[i] - m) > 0.9 * m) return null
        return lengths.indices.sumOf { abs(lengths[it] - target[it]) / m }
    }

    private fun partialRatioScore(whiteBefore: Int, blackCenter: Int, whiteAfter: Int): Double? {
        if (blackCenter < 3) return null
        val module = blackCenter / 3.0
        if (whiteBefore < module * .35 || whiteAfter < module * .35) return null
        return minOf(4.0, abs(whiteBefore - module) / module) +
            minOf(4.0, abs(whiteAfter - module) / module)
    }

    private fun crossCheckPartialVertical(cx: Int, cy: Int, expectedModule: Double): Pair<Double, Double>? {
        if (cx !in 0 until image.width || cy !in 0 until image.height || classify(cx, cy) != PixelClass.BLACK) return null
        var top = cy
        while (top >= 0 && classify(cx, top) == PixelClass.BLACK) top--
        var bottom = cy + 1
        while (bottom < image.height && classify(cx, bottom) == PixelClass.BLACK) bottom++
        val black = bottom - top - 1
        val module = black / 3.0
        if (module < .8 || abs(module - expectedModule) > max(module, expectedModule) * .65) return null
        var whiteBefore = 0
        var y = top
        while (y >= 0 && classify(cx, y) == PixelClass.WHITE) { whiteBefore++; y-- }
        var whiteAfter = 0
        y = bottom
        while (y < image.height && classify(cx, y) == PixelClass.WHITE) { whiteAfter++; y++ }
        partialRatioScore(whiteBefore, black, whiteAfter) ?: return null
        return (top + 1 + (black - 1) / 2.0) to module
    }

    private fun crossCheckVertical(cx: Int, cy: Int, horizontalTotal: Int): Pair<Double, Double>? {
        if (cx !in 0 until image.width || cy !in 0 until image.height || classify(cx, cy) != PixelClass.BLACK) return null
        val counts = IntArray(5)

        var y = cy
        var upCentral = 0
        while (y >= 0 && classify(cx, y) == PixelClass.BLACK) { counts[2]++; upCentral++; y-- }
        while (y >= 0 && classify(cx, y) == PixelClass.WHITE) { counts[1]++; y-- }
        while (y >= 0 && classify(cx, y) == PixelClass.BLACK) { counts[0]++; y-- }

        y = cy + 1
        while (y < image.height && classify(cx, y) == PixelClass.BLACK) { counts[2]++; y++ }
        while (y < image.height && classify(cx, y) == PixelClass.WHITE) { counts[3]++; y++ }
        while (y < image.height && classify(cx, y) == PixelClass.BLACK) { counts[4]++; y++ }

        if (counts.any { it == 0 }) return null
        ratioScore(counts) ?: return null
        val total = counts.sum()
        if (abs(total - horizontalTotal) > max(horizontalTotal, total) * 0.55) return null
        val top = cy - upCentral + 1
        val centerY = top + (counts[2] - 1) / 2.0
        return centerY to (total / 7.0)
    }

    private fun merge(list: MutableList<RawCandidate>, x: Double, y: Double, module: Double, ratio: Double, strongSeed: Boolean) {
        val existing = list.firstOrNull {
            // Never average a complete finder cross with a partial 1:3:1 seed. On damaged QR
            // images nearby data runs can satisfy the partial rule and drag an otherwise exact
            // finder center by more than a module. Even two complete-looking crosses must remain
            // separate when their centers differ by about one module: photographing a displayed
            // QR frequently creates a second run-ratio match in the data immediately beside the
            // real finder. The old 2.5-module radius averaged both and visibly moved the result.
            it.strongSeed == strongSeed &&
            shouldMergeFinderSeeds(it.x, it.y, it.module, x, y, module) &&
                abs(it.module - module) <= max(it.module, module) * 0.6
        }
        if (existing == null) {
            list += RawCandidate(x, y, module, 1, ratio, strongSeed)
        } else {
            val n = existing.count + 1
            existing.x = (existing.x * existing.count + x) / n
            existing.y = (existing.y * existing.count + y) / n
            existing.module = (existing.module * existing.count + module) / n
            existing.ratioScore = minOf(existing.ratioScore, ratio)
            existing.count = n
        }
    }

    private fun refine(raw: RawCandidate): FinderCandidate? {
        var bestScore = -1.0
        var bestPitch = raw.module
        var bestAngle = 0.0

        // Search the complete 90-degree-periodic angle/scale domain. A coarser first pass can
        // select the wrong basin on damaged, photographed, or dotted finders and prevent the
        // later local optimizer from reaching the correct orientation and pitch.
        var angleDeg = 0
        while (angleDeg < 90) {
            val angle = angleDeg * PI / 180.0
            var scale = 0.68
            while (scale <= 1.36 + 1e-9) {
                val pitch = raw.module * scale
                val score = templateScore(raw.x, raw.y, pitch, angle)
                val better = score > bestScore + 1e-9
                val tied = kotlin.math.abs(score - bestScore) <= 1e-9
                val closerToRunPitch = kotlin.math.abs(pitch - raw.module) < kotlin.math.abs(bestPitch - raw.module)
                if (better || (tied && closerToRunPitch)) {
                    bestScore = score
                    bestPitch = pitch
                    bestAngle = angle
                }
                scale += 0.04
            }
            angleDeg += 3
        }

        var bestX = raw.x
        var bestY = raw.y
        // Multi-resolution local optimization turns a rough run-ratio seed into a precise center.
        // The final pass resolves substantially less than one module without assuming axis alignment.
        for ((positionStep, pitchStep, angleStep) in listOf(
            Triple(.24, .045, 1.5), Triple(.10, .018, .55), Triple(.035, .007, .18),
        )) {
            val baseX = bestX; val baseY = bestY; val basePitch = bestPitch; val baseAngle = bestAngle
            for (ox in -1..1) for (oy in -1..1) for (ps in -1..1) for (asign in -1..1) {
                val x = baseX + ox * basePitch * positionStep
                val y = baseY + oy * basePitch * positionStep
                val pitch = basePitch * (1.0 + ps * pitchStep)
                val angle = baseAngle + asign * angleStep * PI / 180.0
                val score = templateScore(x, y, pitch, angle)
                if (score > bestScore + 1e-9 ||
                    (abs(score - bestScore) <= 1e-9 && hypot(x - raw.x, y - raw.y) < hypot(bestX - raw.x, bestY - raw.y))
                ) {
                    bestScore = score
                    bestX = x
                    bestY = y
                    bestPitch = pitch
                    bestAngle = angle
                }
            }
        }

        val minimumScore = when {
            raw.componentSeed -> .90
            raw.strongSeed -> .68
            else -> .48
        }
        if (bestScore < minimumScore) return null
        val combined = bestScore + 0.025 * raw.count.coerceAtMost(8) - 0.01 * raw.ratioScore - if (raw.strongSeed) 0.0 else 0.12
        return FinderCandidate(
            bestX, bestY, bestPitch, bestAngle, combined, raw.count,
            if (raw.strongSeed) FinderEvidence.COMPLETE_CROSS else FinderEvidence.PARTIAL_CORE,
        )
    }

    private fun templateScore(cx: Double, cy: Double, pitch: Double, angle: Double): Double {
        if (pitch < 0.8) return 0.0
        val c = cos(angle)
        val s = sin(angle)
        var good = 0.0
        var total = 0.0
        for (my in -3..3) for (mx in -3..3) {
            val px = cx + c * mx * pitch - s * my * pitch
            val py = cy + s * mx * pitch + c * my * pitch
            val lum = image.sampleMean(px, py, pitch * 0.13) ?: continue
            val expectedBlack = finderExpected(mx + 3, my + 3)
            val actual = classify(lum)
            if (actual == PixelClass.UNKNOWN) continue
            total += 1.0
            if ((actual == PixelClass.BLACK) == expectedBlack) good += 1.0
        }
        if (total < 30) return 0.0
        // Finder patterns have a white separator outside the 7x7 nested square. Scoring it in
        // the same rotated coordinates rejects data patches that accidentally mimic 7x7 centers.
        var separatorGood = 0.0
        var separatorTotal = 0.0
        for (k in -4..4) for ((mx, my) in listOf(k to -4, k to 4, -4 to k, 4 to k)) {
            val px = cx + c * mx * pitch - s * my * pitch
            val py = cy + s * mx * pitch + c * my * pitch
            val lum = image.sampleMean(px, py, pitch * 0.13) ?: continue
            when (classify(lum)) {
                PixelClass.WHITE -> { separatorGood += 1.0; separatorTotal += 1.0 }
                PixelClass.BLACK -> separatorTotal += 1.0
                PixelClass.UNKNOWN -> Unit
            }
        }
        val separatorWeight = .65
        return (good + separatorGood * separatorWeight) /
            (total + separatorTotal * separatorWeight)
    }

    private fun finderExpected(x: Int, y: Int): Boolean =
        x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4)

    private fun classify(x: Int, y: Int): PixelClass = classify(image.luma(x, y).toDouble())

    private fun classify(luma: Double): PixelClass = when {
        luma <= blackThreshold -> PixelClass.BLACK
        luma >= whiteThreshold -> PixelClass.WHITE
        else -> PixelClass.UNKNOWN
    }
}

/** Same-finder row observations vary sub-module; farther matches must be refined independently. */
internal fun shouldMergeFinderSeeds(
    firstX: Double,
    firstY: Double,
    firstModule: Double,
    secondX: Double,
    secondY: Double,
    secondModule: Double,
): Boolean = hypot(firstX - secondX, firstY - secondY) <= max(firstModule, secondModule) * 0.85
