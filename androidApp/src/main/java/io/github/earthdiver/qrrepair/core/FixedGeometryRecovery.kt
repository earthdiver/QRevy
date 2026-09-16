package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

data class GeometryHypothesis(
    val finderIndices: List<Int>,
    val geometry: GridGeometry,
    val quickScore: Double,
    val finderRoleMap: Map<FinderRole, Int> = emptyMap(),
    val alignment: AlignmentCandidate? = null,
) {
    val finderIndex: Int get() = finderIndices.firstOrNull() ?: -1
}

data class GeometryAttempt(
    val hypothesis: GeometryHypothesis,
    val matrix: QrMatrix,
    val samplingStats: MatrixSampler.SamplingStats,
    val recoveries: List<RecoveryCandidate>,
    val failure: RecoveryFailure? = null,
)

data class AutoRecoveryResult(
    val finders: List<FinderCandidate>,
    val attempts: List<GeometryAttempt>,
    val profile: SamplingProfile? = null,
) {
    /** A later geometry/format may exhaust the shared request budget after this result was built. */
    fun withRetentionLimit(): AutoRecoveryResult = copy(attempts = attempts.map { attempt ->
        attempt.copy(
            recoveries = attempt.recoveries.map { it.copy(combinationSearch = it.combinationSearch.withRetentionLimit()) },
            failure = attempt.failure?.let { it.copy(combinationSearch = it.combinationSearch.withRetentionLimit()) },
        )
    })
    val successfulAttempts: List<GeometryAttempt>
        get() = attempts.filter { attempt -> attempt.recoveries.any { it.payloadParsed } }
    val bestAttempt: GeometryAttempt? get() = attempts.minWithOrNull(geometryAttemptComparator)
    val rankedRecoveries: List<Pair<GeometryAttempt, RecoveryCandidate>>
        get() = attempts.flatMap { attempt -> attempt.recoveries.map { attempt to it } }
            .sortedWith { first, second ->
                recoveryCandidateComparator.compare(first.second, second.second).let { comparison ->
                    if (comparison != 0) comparison
                    else compareValues(first.first.hypothesis.quickScore, second.first.hypothesis.quickScore)
                }
            }
    val bestFailure: RecoveryFailure? get() = bestAttempt?.takeIf { it.recoveries.isEmpty() }?.failure
}

internal val geometryAttemptComparator = Comparator<GeometryAttempt> { first, second ->
    val firstRecovery = first.recoveries.firstOrNull()
    val secondRecovery = second.recoveries.firstOrNull()
    val firstFailure = first.failure
    val secondFailure = second.failure
    val firstParsed = firstRecovery?.payloadParsed == true
    val secondParsed = secondRecovery?.payloadParsed == true
    val firstMismatches = firstRecovery?.formatMismatches ?: firstFailure?.formatMismatches ?: Int.MAX_VALUE
    val secondMismatches = secondRecovery?.formatMismatches ?: secondFailure?.formatMismatches ?: Int.MAX_VALUE
    val firstMatches = firstRecovery?.formatMatches ?: firstFailure?.formatMatches ?: 0
    val secondMatches = secondRecovery?.formatMatches ?: secondFailure?.formatMatches ?: 0
    when {
        firstParsed != secondParsed -> if (firstParsed) -1 else 1
        firstMismatches != secondMismatches -> compareValues(firstMismatches, secondMismatches)
        firstMatches != secondMatches -> compareValues(secondMatches, firstMatches)
        firstRecovery != null && secondRecovery == null -> -1
        firstRecovery == null && secondRecovery != null -> 1
        firstRecovery != null && secondRecovery != null -> recoveryCandidateComparator.compare(firstRecovery, secondRecovery)
        firstFailure != null && secondFailure == null -> -1
        firstFailure == null && secondFailure != null -> 1
        firstFailure != null && secondFailure != null -> compareValuesBy(
            firstFailure,
            secondFailure,
            { it.formatMismatches },
            { -it.formatMatches },
            { it.rsShortfallBits ?: Int.MAX_VALUE },
            { -it.stage.ordinal },
            { it.erasedCodewords },
        )
        else -> compareValues(first.hypothesis.quickScore, second.hypothesis.quickScore)
    }.let { if (it != 0) it else compareValues(first.hypothesis.quickScore, second.hypothesis.quickScore) }
}

internal fun confirmedFinderCandidate(
    settings: PreprocessSettings,
    point: PointD,
    measured: FinderCandidate? = null,
): FinderCandidate {
    val dimension = 17 + 4 * settings.version
    val pitch = settings.logicalQuad?.let {
        (it.topLeft.distanceTo(it.topRight) + it.topLeft.distanceTo(it.bottomLeft)) / (2.0 * (dimension - 1))
    } ?: 6.0
    val angle = settings.logicalQuad?.let { atan2(it.topRight.y - it.topLeft.y, it.topRight.x - it.topLeft.x) }
        ?: measured?.angleRad ?: 0.0
    return FinderCandidate(
        point.x, point.y, measured?.moduleSize ?: pitch, angle, 2.0, 99,
        FinderEvidence.MANUAL_HINT, measured?.polarity ?: FinderPolarity.UNKNOWN, FinderDetectionSource.MANUAL,
    )
}

class AutoRecoveryEngine(private val recoveryEngine: RecoveryEngine = RecoveryEngine()) {
    fun run(
        image: LumaImage,
        maxFinders: Int = 6,
        maxGeometryAttempts: Int = 72,
        profile: SamplingProfile? = null,
        retentionBudget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        onProgress: ((done: Int, total: Int, message: String) -> Unit)? = null,
    ): AutoRecoveryResult {
        cancellation.check()
        require(maxFinders >= 0) { "maxFinders must be non-negative" }
        require(maxGeometryAttempts >= 0) { "maxGeometryAttempts must be non-negative" }
        val settings = profile?.settings ?: return AutoRecoveryResult(emptyList(), emptyList(), profile)
        val baseHypotheses = manualGeometrySeeds(settings).take(maxGeometryAttempts).mapNotNull { (version, quad) ->
            cancellation.check()
            runCatching {
                val dimension = 17 + 4 * version
                val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
                    (2.0 * (dimension - 1)).coerceAtLeast(1.0)
                val geometry = GridGeometry(
                    version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y, pitch, 0.0,
                    quad, GeometryModel.MANUAL_PROJECTIVE,
                ).reanchored()
                GeometryHypothesis(emptyList(), geometry, quickGeometryScore(image, geometry, profile))
            }.getOrNull()
        }
        // Alignment evidence adds hypotheses; it never replaces a user-selected geometry.
        val refinedHypotheses = baseHypotheses.mapNotNull { base ->
            cancellation.check()
            val refined = alignmentRefinedGeometry(image, profile, base.geometry) ?: return@mapNotNull null
            val score = quickGeometryScore(image, refined.first, profile)
            // Timing-pattern evidence is independent of the 5x5 alignment template. Reject a
            // refinement that makes it materially worse before spending a full recovery attempt.
            if (score > base.quickScore + 12.0) return@mapNotNull null
            base.copy(geometry = refined.first, quickScore = score, alignment = refined.second)
        }
        val hypotheses = (baseHypotheses + refinedHypotheses).take(maxGeometryAttempts)
        val finders = settings.manualFinders.entries.take(maxFinders).map { (_, point) -> confirmedFinderCandidate(settings, point) }
        val attempts = hypotheses.mapIndexedNotNull { index, hypothesis ->
            cancellation.check()
            if (retentionBudget.exhausted) return@mapIndexedNotNull null
            onProgress?.invoke(index, hypotheses.size, localizedText(
                "指定QR格子 / V${hypothesis.geometry.version} を試行中",
                "Trying selected QR grid / V${hypothesis.geometry.version}",
            ))
            runCatching {
                cancellation.check()
                val (matrix, stats) = MatrixSampler(image, profile).sample(hypothesis.geometry)
                settings.preRecoveryEdits.forEach { (coord, value) ->
                    if (coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension) {
                        matrix.set(coord.x, coord.y, value, 1f, settings.sourceForPreRecoveryEdit(value))
                        matrix.setPrioritySearch(coord.x, coord.y, coord in settings.preRecoveryPrioritySearch)
                    }
                }
                val recoveries = recoveryEngine.recover(
                    matrix,
                    trustKnownPayloadCount = settings.trustKnownPayloadCount,
                    formatConstraint = settings.formatConstraint,
                    maxFormatMismatches = settings.formatMismatchLimit,
                    maxKnownDataEccMismatches = settings.dataEccMismatchLimit,
                    manualEdits = settings.preRecoveryEdits,
                displayLockManualBits = settings.lockManualBits,
                    retentionBudget = retentionBudget,
                    cancellation = cancellation,
                ) { done, total ->
                    onProgress?.invoke(index, hypotheses.size, localizedText(
                        "*優先セルを試行中… $done/$total",
                        "Trying priority * cells… $done/$total",
                    ))
                }
                GeometryAttempt(hypothesis, matrix, stats, recoveries, recoveryEngine.lastFailure)
            }.onFailure { if (it is RecoveryCancelledException) throw it }.getOrNull()
        }
        onProgress?.invoke(attempts.size, hypotheses.size, localizedText("試行完了", "Search complete"))
        val result = AutoRecoveryResult(finders, attempts.sortedWith(geometryAttemptComparator), profile)
        return if (retentionBudget.exhausted) result.withRetentionLimit() else result
    }

    private fun quickGeometryScore(image: LumaImage, geometry: GridGeometry, profile: SamplingProfile): Double {
        val dimension = geometry.dimension
        var score = 0.0
        var inside = 0
        for (gridY in 0..4) for (gridX in 0..4) {
            val point = geometry.moduleToImage((dimension - 1) * gridX / 4.0, (dimension - 1) * gridY / 4.0)
            if (image.inBounds(point.x, point.y)) inside++
        }
        score += (1.0 - inside / 25.0) * 35.0
        var known = 0
        var mismatches = 0
        for (module in 8..dimension - 9) for ((x, y) in arrayOf(module to 6, 6 to module)) {
            val point = geometry.moduleToImage(x.toDouble(), y.toDouble())
            val radius = geometry.localModulePitch(x.toDouble(), y.toDouble()) * .1
            val luma = image.sampleMean(point.x, point.y, radius) ?: continue
            known++
            val black = profile.normalize(luma) <= profile.thresholdAt(image, point.x, point.y, radius)
            if (black != ((module and 1) == 0)) mismatches++
        }
        score += if (known >= 6) 24.0 * mismatches / known else 12.0
        return score
    }
}

/** Fits one interior alignment observation by moving only its nearest logical corner. */
internal fun alignmentRefinedGeometry(
    image: LumaImage,
    profile: SamplingProfile,
    base: GridGeometry,
    maxCorrectionModules: Double = 1.25,
    iterations: Int = 8,
): Pair<GridGeometry, AlignmentCandidate>? {
    require(maxCorrectionModules > 0.0 && maxCorrectionModules.isFinite())
    require(iterations > 0)
    val alignment = AlignmentDetector(image, profile).findBest(base) ?: return null
    val predicted = base.moduleToImage(alignment.moduleX.toDouble(), alignment.moduleY.toDouble())
    val localPitch = base.localModulePitch(alignment.moduleX.toDouble(), alignment.moduleY.toDouble()).coerceAtLeast(.25)
    val correction = predicted.distanceTo(PointD(alignment.imageX, alignment.imageY))
    if (correction < localPitch * .10 || correction > localPitch * maxCorrectionModules) return null

    val logicalCorners = listOf(
        PointD(0.0, 0.0),
        PointD((base.dimension - 1).toDouble(), 0.0),
        PointD((base.dimension - 1).toDouble(), (base.dimension - 1).toDouble()),
        PointD(0.0, (base.dimension - 1).toDouble()),
    )
    val modulePoint = PointD(alignment.moduleX.toDouble(), alignment.moduleY.toDouble())
    val cornerIndex = logicalCorners.indices.minBy { logicalCorners[it].distanceTo(modulePoint) }
    var quad = base.moduleCenterQuad()
    repeat(iterations) {
        val current = base.copy(imageQuad = quad, model = GeometryModel.PROJECTIVE).reanchored()
        val currentPrediction = current.moduleToImage(modulePoint.x, modulePoint.y)
        val delta = PointD(alignment.imageX - currentPrediction.x, alignment.imageY - currentPrediction.y)
        if (delta.distanceTo(PointD(0.0, 0.0)) < localPitch * .001) return@repeat
        val oldCorner = quad.asList()[cornerIndex]
        quad = quad.withCorner(cornerIndex, oldCorner + delta)
    }
    val refined = runCatching {
        base.copy(imageQuad = quad, model = GeometryModel.PROJECTIVE).reanchored().also { geometry ->
            val mapped = geometry.moduleToImage(modulePoint.x, modulePoint.y)
            require(mapped.distanceTo(PointD(alignment.imageX, alignment.imageY)) <= localPitch * .05)
            geometry.moduleCenterQuad().asList().forEach { point -> require(point.x.isFinite() && point.y.isFinite()) }
        }
    }.getOrNull() ?: return null
    return refined to alignment
}

internal data class FinderDetectionInput(val image: LumaImage, val padding: Int) {
    fun toDetection(point: PointD) = PointD(point.x + padding, point.y + padding)
    fun toSource(candidate: FinderCandidate) = candidate.copy(
        centerX = candidate.centerX - padding,
        centerY = candidate.centerY - padding,
    )
}

/** Prepares a small local image used only after the user assigns a Finder role. */
internal fun prepareFinderDetection(image: LumaImage, profile: SamplingProfile?): FinderDetectionInput {
    var normalized = if (profile?.inverted != true) image else {
        LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
    }
    val settings = profile?.settings
    if (settings?.quietZoneCorrection != true) return FinderDetectionInput(normalized, 0)
    val dimension = 17 + 4 * settings.version
    settings.logicalQuad?.let { quad ->
        val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))
        val geometry = runCatching {
            GridGeometry(settings.version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                pitch.coerceAtLeast(.25), 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        }.getOrNull()
        if (geometry != null) {
            val masked = normalized.pixels.copyOf()
            for (y in 0 until normalized.height) for (x in 0 until normalized.width) {
                val module = runCatching { geometry.imageToModule(x.toDouble(), y.toDouble()) }.getOrNull()
                if (module == null || module.x < -.5 || module.y < -.5 ||
                    module.x > dimension - .5 || module.y > dimension - .5
                ) masked[y * normalized.width + x] = 255
            }
            normalized = LumaImage(normalized.width, normalized.height, masked)
        }
    }
    val estimatedPitch = settings.logicalQuad?.let { quad ->
        (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))
    } ?: minOf(image.width, image.height).toDouble() / (dimension + 8)
    val padding = (estimatedPitch * 4.0).roundToInt().coerceIn(4, maxOf(4, minOf(image.width, image.height) / 3))
    val width = normalized.width + padding * 2
    val pixels = IntArray(width * (normalized.height + padding * 2)) { 255 }
    for (y in 0 until normalized.height) normalized.pixels.copyInto(
        pixels, (y + padding) * width + padding, y * normalized.width, (y + 1) * normalized.width,
    )
    return FinderDetectionInput(LumaImage(width, normalized.height + padding * 2, pixels), padding)
}

internal fun manualGeometrySeeds(settings: PreprocessSettings): List<Pair<Int, ImageQuad>> {
    val quad = settings.logicalQuad ?: return emptyList()
    val quads = if (settings.searchQuadNeighborhood) {
        manualQuadNeighborhood(
            quad,
            settings.version,
            rotationPivotRole = settings.manualFinders.keys.singleOrNull(),
        )
    } else listOf(quad)
    return quads.map { settings.version to it }
}

internal fun manualQuadNeighborhood(
    quad: ImageQuad,
    version: Int,
    displacementModules: Double = 0.60,
    rotationPivotRole: FinderRole? = null,
): List<ImageQuad> {
    require(displacementModules > 0.0 && displacementModules.isFinite())
    val points = quad.asList()
    val dimension = 17 + 4 * version
    val horizontal = (points[0].distanceTo(points[1]) + points[3].distanceTo(points[2])) / 2.0
    val vertical = (points[0].distanceTo(points[3]) + points[1].distanceTo(points[2])) / 2.0
    val pitch = ((horizontal + vertical) / (2.0 * (dimension - 1))).coerceAtLeast(.25)
    val center = PointD(points.map { it.x }.average(), points.map { it.y }.average())
    fun from(values: List<PointD>) = ImageQuad(values[0], values[1], values[2], values[3])
    fun translated(dx: Double, dy: Double) = from(points.map { PointD(it.x + dx, it.y + dy) })
    fun scaledByOuterDisplacement(displacement: Double): ImageQuad {
        val meanRadius = points.map { it.distanceTo(center) }.average().coerceAtLeast(.001)
        val scale = 1.0 + displacement / meanRadius
        return from(points.map { center + (it - center) * scale })
    }
    val result = mutableListOf(quad)
    val offset = displacementModules * pitch
    val diagonalOffset = offset / kotlin.math.sqrt(2.0)
    for ((dx, dy) in listOf(
        -offset to 0.0, offset to 0.0, 0.0 to -offset, 0.0 to offset,
        -diagonalOffset to -diagonalOffset,
        diagonalOffset to -diagonalOffset,
        diagonalOffset to diagonalOffset,
        -diagonalOffset to diagonalOffset,
    )) result += translated(dx, dy)
    result += scaledByOuterDisplacement(-offset)
    result += scaledByOuterDisplacement(offset)
    for (index in points.indices) {
        val vector = points[index] - center
        val length = hypot(vector.x, vector.y).coerceAtLeast(.001)
        val unit = PointD(vector.x / length, vector.y / length)
        for (direction in listOf(-1.0, 1.0)) {
            val changed = points.toMutableList()
            changed[index] = changed[index] + unit * (offset * direction)
            result += from(changed)
        }
    }
    if (rotationPivotRole != null) {
        result += rotateAboutLogicalCorner(quad, version, rotationPivotRole, -displacementModules)
        result += rotateAboutLogicalCorner(quad, version, rotationPivotRole, displacementModules)
    }
    return result
}

/** Rotates around one user-confirmed Finder-side logical corner; the farthest corner moves by the requested width. */
internal fun rotateAboutLogicalCorner(
    quad: ImageQuad,
    version: Int,
    role: FinderRole,
    displacementModules: Double,
): ImageQuad {
    require(displacementModules.isFinite())
    val points = quad.asList()
    val pivotIndex = when (role) {
        FinderRole.TOP_LEFT -> 0
        FinderRole.TOP_RIGHT -> 1
        FinderRole.BOTTOM_LEFT -> 3
    }
    val pivot = points[pivotIndex]
    val dimension = 17 + 4 * version
    val horizontal = (points[0].distanceTo(points[1]) + points[3].distanceTo(points[2])) / 2.0
    val vertical = (points[0].distanceTo(points[3]) + points[1].distanceTo(points[2])) / 2.0
    val pitch = ((horizontal + vertical) / (2.0 * (dimension - 1))).coerceAtLeast(.25)
    val maxRadius = points.maxOf { it.distanceTo(pivot) }.coerceAtLeast(.001)
    val displacement = abs(displacementModules) * pitch
    val angleMagnitude = 2.0 * asin((displacement / (2.0 * maxRadius)).coerceIn(0.0, 1.0))
    val angle = if (displacementModules < 0.0) -angleMagnitude else angleMagnitude
    val cosine = cos(angle)
    val sine = sin(angle)
    val rotated = points.map { point ->
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        PointD(
            pivot.x + cosine * dx - sine * dy,
            pivot.y + sine * dx + cosine * dy,
        )
    }
    return ImageQuad(rotated[0], rotated[1], rotated[2], rotated[3])
}
