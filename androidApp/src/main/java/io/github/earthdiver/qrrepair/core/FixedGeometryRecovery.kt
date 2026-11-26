package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
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
    val patternRefined: Boolean = false,
) {
    val finderIndex: Int get() = finderIndices.firstOrNull() ?: -1
}

data class GeometryAttempt(
    val hypothesis: GeometryHypothesis,
    val matrix: QrMatrix,
    val samplingStats: MatrixSampler.SamplingStats,
    val recoveries: List<RecoveryCandidate>,
    val failure: RecoveryFailure? = null,
    val searchDiagnostic: RecoverySearchDiagnostic? = recoveries.firstOrNull()?.searchDiagnostic ?: failure?.searchDiagnostic,
)

data class AutoRecoveryResult(
    val finders: List<FinderCandidate>,
    val attempts: List<GeometryAttempt>,
    val profile: SamplingProfile? = null,
    val searchDiagnostic: RecoverySearchDiagnostic? = RecoverySearchDiagnostic.combine(attempts.map { it.searchDiagnostic }),
    val entries: List<RecoveryResultEntry> = attempts.flatMap { attempt ->
        attempt.recoveries.mapIndexedNotNull { index, candidate ->
            candidate.takeIf { it.isSelectableRecovery }?.let {
                RecoveryResultEntry(attempt, it, profile, candidateIndex = index)
            }
        }
    }.sortedWith(recoveryResultComparator),
    val attemptedGeometries: Int = attempts.size,
    val testedHypotheses: List<GeometryHypothesis> = attempts.map { it.hypothesis },
    val interrupted: Boolean = false,
    val discoveredCandidates: RecoveryDiscoveryCount? = null,
) {
    val candidatesTruncated: Boolean get() = searchDiagnostic?.candidatesOmitted == true
    val successfulAttempts: List<GeometryAttempt>
        get() = attempts.filter { attempt -> attempt.recoveries.any { it.payloadParsed } }
    val bestAttempt: GeometryAttempt? get() = attempts.minWithOrNull(geometryAttemptComparator)
    val rankedRecoveries: List<Pair<GeometryAttempt, RecoveryCandidate>>
        get() = entries.map { it.attempt to it.candidate }
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

class AutoRecoveryEngine(
    private val recoveryEngine: RecoveryEngine = RecoveryEngine(),
    private val maxContourWorkingBytes: Long = CurvedFinderContours.DEFAULT_WORKING_BYTES,
) {
    fun run(
        image: LumaImage,
        maxFinders: Int = 6,
        maxGeometryAttempts: Int = 72,
        profile: SamplingProfile? = null,
        retentionBudget: RecoveryRetentionBudget = RecoveryRetentionBudget(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        resultAccumulator: RecoveryResultAccumulator = RecoveryResultAccumulator(retentionBudget),
        stopRequested: () -> Boolean = { false },
        onPriorityProgress: ((done: Int, total: Int) -> Unit)? = null,
        listDecodingBudget: ListDecodingBudget = ListDecodingBudget(),
        knownBitErrorBudget: KnownBitErrorBudget = KnownBitErrorBudget(),
        discoveryCounter: RecoveryDiscoveryCounter = RecoveryDiscoveryCounter(),
        onProgress: ((done: Int, total: Int, message: String) -> Unit)? = null,
        sampleGrid: ((GridGeometry, SamplingProfile) -> Pair<QrMatrix, MatrixSampler.SamplingStats>)? = null,
    ): AutoRecoveryResult {
        cancellation.check()
        require(maxFinders >= 0) { "maxFinders must be non-negative" }
        require(maxGeometryAttempts >= 0) { "maxGeometryAttempts must be non-negative" }
        val settings = profile?.settings ?: return AutoRecoveryResult(emptyList(), emptyList(), profile,
            discoveredCandidates = discoveryCounter.snapshot)
        val preview = (settings.previewGeometry ?: settings.lmGeometry)?.takeIf {
            (settings.distortionCorrection || settings.lmGeometry != null) && settings.logicalQuad != null && it.version == settings.version
        }?.let { GeometryHypothesis(emptyList(), it, quickGeometryScore(image, it, profile)) }
        val uncorrected = if (preview != null && settings.lmCorrectionEnabled && maxGeometryAttempts > 1) {
            try {
                val quad = settings.logicalQuad!!
                val dimension = 17 + 4 * settings.version
                val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
                    (2.0 * (dimension - 1))
                val original = GridGeometry(settings.version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                    pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
                val sameGrid = original.moduleCenterQuad() == preview.geometry.moduleCenterQuad() && preview.geometry.warp == null
                if (!sameGrid && needsUncorrectedGridVerification(image, profile, original,
                        stopped = { cancellation.check(); stopRequested() }))
                    GeometryHypothesis(emptyList(), original, quickGeometryScore(image, original, profile))
                else null
            } catch (_: IllegalArgumentException) {
                // An unusable reference must not suppress the valid selected correction.
                null
            }
        } else null
        val seeds = if (preview != null && !settings.searchQuadNeighborhood) emptyList()
            else manualGeometrySeeds(settings.copy(manualQuad = settings.lmGeometry?.moduleCenterQuad() ?: settings.manualQuad))
        val selectedSeeds = seeds.take(maxGeometryAttempts)
        val baseHypotheses = selectedSeeds.mapNotNull { (version, quad) ->
            cancellation.check()
            try {
                val dimension = 17 + 4 * version
                val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
                    (2.0 * (dimension - 1)).coerceAtLeast(1.0)
                val geometry = GridGeometry(
                    version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y, pitch, 0.0,
                    quad, GeometryModel.MANUAL_PROJECTIVE,
                ).reanchored()
                GeometryHypothesis(emptyList(), geometry, quickGeometryScore(image, geometry, profile))
            } catch (_: IllegalArgumentException) {
                // A degenerate coordinate mapping is an inapplicable geometry, not unfinished work.
                null
            } catch (_: Exception) {
                resultAccumulator.markIncomplete(SearchIncompleteReason.INTERNAL_ERROR)
                null
            }
        }
        // Alignment evidence adds hypotheses; it never replaces a user-selected geometry.
        val refinedHypotheses = (if (settings.searchQuadNeighborhood) baseHypotheses else emptyList()).mapNotNull { base ->
            cancellation.check()
            val refined = alignmentRefinedGeometry(image, profile, base.geometry) ?: return@mapNotNull null
            val score = quickGeometryScore(image, refined.first, profile)
            // Timing-pattern evidence is independent of the 5x5 alignment template. Reject a
            // refinement that makes it materially worse before spending a full recovery attempt.
            if (score > base.quickScore + 12.0) return@mapNotNull null
            base.copy(geometry = refined.first, quickScore = score, alignment = refined.second)
        }
        // Keep the ordinary attempt budget intact. Non-rigid candidates have a separate bounded budget.
        val ordinary = (baseHypotheses + refinedHypotheses).take(maxGeometryAttempts)
        val patternLimitReached = settings.searchQuadNeighborhood && ordinary.isNotEmpty() && ordinary.size >= maxGeometryAttempts
        val patternFit = if (settings.searchQuadNeighborhood && !patternLimitReached)
            projectivePatternHypothesis(image, profile, ordinary) { cancellation.check(); stopRequested() }
        else null
        var generationInterrupted = false
        val generationStopped = { stopRequested().also { if (it) generationInterrupted = true } }
        val curvedDetector = if (settings.distortionCorrection) CurvedGridDetector(image, profile, cancellation, generationStopped,
            minOf(maxContourWorkingBytes, retentionBudget.workingLimitBytes, RecoveryMemoryPolicy.current().workingBytes)) else null
        // The joint fit adds one seed without displacing the four ordinary curvature seeds.
        val curvedSeeds = (listOfNotNull(patternFit) + ordinary.sortedBy { it.quickScore }.take(4))
            .distinctBy { it.geometry }
        val leading = listOfNotNull(preview, uncorrected).take(maxGeometryAttempts)
        val ordinarySlots = leading.filter { it !== preview } + listOfNotNull(patternFit) + ordinary
        val hypotheses = detailedGeometrySequence(
            preview = leading.firstOrNull { it === preview }, ordinary = ordinarySlots,
            curvedSeeds = if (curvedDetector == null) emptyList() else curvedSeeds,
            needsJointFit = { it.patternRefined || settings.lmGeometry != null },
            curves = { requireNotNull(curvedDetector).detailedCandidates(it) },
            score = { quickGeometryScore(image, it, profile) },
            refine = { jointlyRefinedGeometry(image, it, profile) { cancellation.check(); generationStopped() } },
            check = { cancellation.check() },
        )
        val finders = settings.manualFinders.entries.take(maxFinders).map { (_, point) -> confirmedFinderCandidate(settings, point) }
        var completed = 0
        var interrupted = false
        for ((index, hypothesis) in hypotheses.withIndex()) {
            if (stopRequested()) {
                resultAccumulator.markIncomplete(SearchIncompleteReason.USER_INTERRUPTED)
                interrupted = true
                break
            }
            cancellation.check()
            onPriorityProgress?.invoke(0, 0)
            onProgress?.invoke(index, -1, localizedText(
                "指定QR格子 / V${hypothesis.geometry.version} を試行中",
                "Trying selected QR grid / V${hypothesis.geometry.version}",
            ))
            runCatching {
                cancellation.check()
                val (matrix, stats) = sampleGrid?.invoke(hypothesis.geometry, profile)
                    ?: MatrixSampler(image, profile).sample(hypothesis.geometry)
                settings.preRecoveryEdits.forEach { (coord, value) ->
                    if (coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension) {
                        matrix.set(coord.x, coord.y, value, 1f, settings.sourceForPreRecoveryEdit(value, coord))
                        matrix.setPrioritySearch(coord.x, coord.y, coord in settings.preRecoveryPrioritySearch)
                    }
                }
                val outcome = recoveryEngine.recoverWithDiagnostics(
                    matrix,
                    request = RecoveryRequest.fromSettings(settings, hypothesis.geometry.version),
                    execution = RecoveryExecutionControls(
                        retentionBudget = retentionBudget.fresh(),
                        cancellation = cancellation,
                        stopRequested = stopRequested,
                        listDecodingBudget = listDecodingBudget,
                        knownBitErrorBudget = knownBitErrorBudget,
                        discoveryCounter = discoveryCounter,
                        onPriorityProgress = { done, total -> onPriorityProgress?.invoke(done, total) },
                    ),
                )
                GeometryAttempt(hypothesis, matrix, stats, outcome.candidates, outcome.failure, outcome.searchDiagnostic)
            }.onFailure {
                if (it is RecoveryCancelledException || it is Error) throw it
                resultAccumulator.markIncomplete(SearchIncompleteReason.INTERNAL_ERROR)
            }.getOrNull()?.let {
                completed++
                resultAccumulator.add(it, profile)
            }
        }
        if (curvedDetector?.contourMemoryLimited == true) resultAccumulator.markIncomplete(SearchIncompleteReason.WORKING_MEMORY_LIMIT)
        // A stop during lazy candidate generation can end the sequence without yielding another grid.
        if (generationInterrupted && !interrupted) {
            resultAccumulator.markIncomplete(SearchIncompleteReason.USER_INTERRUPTED)
            interrupted = true
        }
        // Completion counts describe finished attempts, not a late stop-button state.
        if (!interrupted) resultAccumulator.completePolicy(hasExcludedInputs =
            patternLimitReached || seeds.size > selectedSeeds.size || selectedSeeds.size != baseHypotheses.size ||
                baseHypotheses.size + refinedHypotheses.size > ordinary.size ||
                (settings.distortionCorrection && ordinary.size > 4))
        val result = resultAccumulator.result(finders).copy(discoveredCandidates = discoveryCounter.snapshot)
        val unfinished = result.searchDiagnostic?.completeness != SearchCompleteness.COMPLETE
        onProgress?.invoke(completed, if (interrupted) -1 else completed, if (unfinished)
            localizedText("試行終了（完了状況は結果を確認してください）", "Trials ended; see results for completion status")
            else localizedText("試行完了", "Trials completed"))
        return result
    }
}

/** Keep the selected grid and up to three distinct starting positions for one joint fit. */
internal fun projectivePatternHypothesis(
    image: LumaImage, profile: SamplingProfile, ordinary: List<GeometryHypothesis>, stopped: () -> Boolean,
): GeometryHypothesis? {
    val seed = ordinary.firstOrNull()?.geometry ?: return null
    val starts = mutableListOf(seed.moduleCenterQuad())
    fun displacement(a: ImageQuad, b: ImageQuad): Double =
        a.asList().zip(b.asList()).sumOf { (p, q) ->
            val delta = p - q
            delta.x * delta.x + delta.y * delta.y
        } / 8.0
    val pitch = seed.localModulePitch()
    for (candidate in ordinary.sortedBy { it.quickScore }) {
        val quad = candidate.geometry.moduleCenterQuad()
        if (starts.all { displacement(it, quad) > .25 * .25 * pitch * pitch }) starts += quad
        if (starts.size == 4) break
    }
    val centers = Version.getVersionForNumber(seed.version).alignmentPatternCenters
    val fit = ProjectivePatternRefiner.refine(image, seed, starts, centers, profile.inverted, stopped) ?: return null
    if (ordinary.any { displacement(it.geometry.moduleCenterQuad(), fit.moduleCenterQuad()) < .0001 * pitch * pitch }) return null
    return GeometryHypothesis(emptyList(), fit, quickGeometryScore(image, fit, profile), patternRefined = true)
}

internal fun quickGeometryScore(image: LumaImage, geometry: GridGeometry, profile: SamplingProfile): Double {
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
        val black = profile.normalize(luma) <= profile.threshold
        if (black != ((module and 1) == 0)) mismatches++
    }
    score += if (known >= 6) 24.0 * mismatches / known else 12.0
    return score
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

/** Search every permitted polarity; return evidence in original image coordinates. */
internal fun refineFinder(image: LumaImage, settings: PreprocessSettings, hint: PointD): FinderCandidate? =
    refineFinderPlacement(image, settings, hint).candidate

internal enum class FinderRefinementOutcome {
    /** A detected center is consistent with the tap and the current grid. */
    REFINED,
    /** No finder-like pattern was found near the tap. */
    NOT_FOUND,
    /** Patterns were found, but none fitted the tap and grid better than the tapped point itself. */
    REJECTED,
}

/**
 * Result of refining one tapped Finder. A rejected or missing refinement keeps the tapped point; the
 * caller must not move an assigned Finder to an unrelated pattern.
 */
internal data class FinderRefinement(
    val outcome: FinderRefinementOutcome,
    val candidate: FinderCandidate? = null,
    /** Distance between the tapped point and the accepted center, in reference module widths. */
    val displacementModules: Double? = null,
    /** Whether the tapped point lies on the accepted Finder's 7x7 modules. */
    val tapOnFinder: Boolean = false,
) {
    /**
     * A center found from a tap outside the Finder, or from a pattern that matches a Finder only in
     * part, is shown to the user before it is used.
     */
    val needsConfirmation: Boolean
        get() = outcome == FinderRefinementOutcome.REFINED && !tapOnFinder
}

/** A tap anywhere on a 7x7 Finder, including its corners, lies within this many modules of the center. */
internal const val FINDER_SEARCH_RADIUS_MODULES = 5.0
/** Half the side of a Finder: a tap within this many modules of the center along both Finder axes is on it. */
internal const val FINDER_HALF_SIDE_MODULES = 3.5

/** A partial inverted Finder must have at least this fraction of the grid's module width, and at most its inverse. */
private const val INVERTED_PARTIAL_PITCH_MIN = 0.85
/** A Finder recognized only by its core must be tapped within this many modules of that core. */
internal const val FINDER_PARTIAL_TAP_MODULES = 1.5
/** An assigned grid places a Finder within this many modules of where the user may tap for it. */
internal const val FINDER_EXPECTED_TOLERANCE_MODULES = 4.0

/** Whether [point] lies on the 7x7 modules of [finder], measured along the Finder's own axes. */
internal fun FinderCandidate.contains(point: PointD, pitch: Double = moduleSize): Boolean {
    val dx = point.x - centerX; val dy = point.y - centerY
    val c = kotlin.math.cos(angleRad); val s = kotlin.math.sin(angleRad)
    val u = (dx * c + dy * s) / pitch; val v = (-dx * s + dy * c) / pitch
    return maxOf(kotlin.math.abs(u), kotlin.math.abs(v)) <= FINDER_HALF_SIDE_MODULES
}
/** A detected center must fit the Finder template clearly better than the tapped point to replace it. */
private const val FINDER_TEMPLATE_MARGIN = 0.08
private const val FINDER_SMALL_CORRECTION_MODULES = 0.5
/** A sub-module correction may fit marginally worse at the sampled positions than a tap near the center. */
private const val FINDER_SMALL_CORRECTION_TOLERANCE = 0.03

/** Module width implied by the current corners, whether or not their logical roles are assigned yet. */
internal fun finderReferencePitch(settings: PreprocessSettings): Double? {
    val quad = settings.manualQuad ?: return null
    val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
        (2.0 * (16 + 4 * settings.version))
    return pitch.takeIf { it.isFinite() && it >= .25 }
}

/**
 * Refines a tapped Finder only within a Finder-sized neighborhood. Candidates are compared with the
 * tapped point using the same template, so a damaged Finder that the detector cannot recognize keeps
 * the user's point instead of moving to a nearby data pattern.
 */
internal fun refineFinderPlacement(image: LumaImage, settings: PreprocessSettings, hint: PointD,
    role: FinderRole? = null, physicalGrid: ImageQuad? = null): FinderRefinement {
    val geometry = if (settings.automaticThresholds) settings.logicalQuad?.let { quad ->
        val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (16 + 4 * settings.version))
        runCatching { GridGeometry(settings.version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
            pitch.coerceAtLeast(.25), 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored() }.getOrNull()
    } else null
    // Unassigned corners may be a coarse default; only an assigned grid constrains the module width.
    val referencePitch = finderReferencePitch(settings)?.takeIf { settings.logicalGridAssigned }
    val searchRadius = referencePitch?.let { max(it * FINDER_SEARCH_RADIUS_MODULES, 8.0) }
    // Where a grid places Finders; a pattern far from them is another pattern. An assigned grid knows the
    // tapped role. An estimated grid without orientation allows a Finder at any of its four corners.
    val physicalPitch = if (referencePitch == null && physicalGrid != null)
        finderReferencePitch(settings.copy(manualQuad = physicalGrid)) else null
    val checkPitch = referencePitch ?: physicalPitch
    val expectedCenters: List<PointD>? = run {
        val quad = (if (referencePitch != null) settings.logicalQuad else physicalGrid) ?: return@run null
        val pitch = checkPitch ?: return@run null
        val roles = if (referencePitch != null) listOfNotNull(role) else null
        if (referencePitch != null && roles.isNullOrEmpty()) return@run null
        runCatching {
            val grid = GridGeometry(settings.version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
            val last = (grid.dimension - 4).toDouble()
            val modules = roles?.map { grid.finderModulePoint(it) }
                ?: listOf(PointD(3.0, 3.0), PointD(last, 3.0), PointD(3.0, last), PointD(last, last))
            modules.map { grid.moduleToImage(it.x, it.y) }
        }.getOrNull()
    }
    class Found(val candidate: FinderCandidate, val scorer: FinderDetector)
    val found = settings.variants().flatMap { profile ->
        // A misaligned grid can hide the real finder while still producing a false candidate.
        // Compare both automatic threshold sources, even when grid-based detection succeeds.
        val thresholds = if (settings.automaticThresholds) listOfNotNull(
            geometry?.let { MatrixSampler(image, profile).sample(it).second.thresholds },
            profile.provisionalThresholds,
        ).distinct() else listOf(ThresholdPair(settings.blackMax, settings.whiteMin))
        val input = prepareFinderDetection(image, profile)
        // Score the tap and the candidate on the unmasked image in the same polarity.
        val unmasked = if (!profile.inverted) image else
            LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        thresholds.mapNotNull { pair ->
            val detector = FinderDetector(input.image, pair.blackMax, pair.whiteMin)
            val point = input.toDetection(hint)
            val detected = (if (searchRadius != null) detector.detectNear(point, searchRadius) else detector.detectNear(point))
                ?.let(input::toSource)?.copy(
                    polarity = if (profile.inverted) FinderPolarity.INVERTED else FinderPolarity.NORMAL,
                    detectionSource = FinderDetectionSource.RAW,
                ) ?: return@mapNotNull null
            Found(detected, FinderDetector(unmasked, pair.blackMax, pair.whiteMin))
        }
    }
    if (found.isEmpty()) return FinderRefinement(FinderRefinementOutcome.NOT_FOUND)
    fun displacement(c: FinderCandidate) = hypot(c.centerX - hint.x, c.centerY - hint.y) / (checkPitch ?: c.moduleSize)
    fun plausible(f: Found): Boolean {
        val c = f.candidate
        if (checkPitch != null && c.moduleSize !in checkPitch * .75..checkPitch * 1.33) return false
        val moved = displacement(c)
        if (moved > FINDER_SEARCH_RADIUS_MODULES) return false
        if (expectedCenters != null && expectedCenters.none { hypot(c.centerX - it.x, c.centerY - it.y) <=
                FINDER_EXPECTED_TOLERANCE_MODULES * checkPitch!! }) return false
        // A partial pattern is accepted only around the core the user tapped; from elsewhere, including
        // outside a Finder, only a complete Finder is offered.
        if (c.evidence != FinderEvidence.COMPLETE_CROSS && moved > FINDER_PARTIAL_TAP_MODULES) return false
        // Cells are read in normal polarity until reversal is known. The light ring around a normal
        // Finder's core can match an inverted core in part, at a narrower module, so a partial inverted
        // match must also agree with the grid's module width before it may establish reversal.
        if (c.evidence != FinderEvidence.COMPLETE_CROSS && c.polarity == FinderPolarity.INVERTED &&
            settings.confirmedFinderPolarity != FinderPolarity.INVERTED && settings.polarity != PolarityMode.INVERTED &&
            checkPitch != null && c.moduleSize !in checkPitch * INVERTED_PARTIAL_PITCH_MIN..checkPitch / INVERTED_PARTIAL_PITCH_MIN) return false
        // Both centers get the same pitch and angle search, so neither inherits the candidate's fit.
        val pitches = listOfNotNull(c.moduleSize, referencePitch)
        val tapFit = pitches.maxOf { f.scorer.bestTemplateFitAt(hint.x, hint.y, it) }
        val candidateFit = pitches.maxOf { f.scorer.bestTemplateFitAt(c.centerX, c.centerY, it) }
        return candidateFit >= tapFit + FINDER_TEMPLATE_MARGIN ||
            (moved <= FINDER_SMALL_CORRECTION_MODULES && candidateFit >= tapFit - FINDER_SMALL_CORRECTION_TOLERANCE)
    }
    val accepted = found.filter(::plausible).maxByOrNull { f ->
        f.candidate.score - 0.18 * displacement(f.candidate) / FINDER_SEARCH_RADIUS_MODULES
    } ?: return FinderRefinement(FinderRefinementOutcome.REJECTED)
    val candidate = accepted.candidate
    return FinderRefinement(FinderRefinementOutcome.REFINED, candidate, displacement(candidate),
        tapOnFinder = candidate.evidence == FinderEvidence.COMPLETE_CROSS &&
            candidate.contains(hint))
}

internal fun manualGeometrySeeds(settings: PreprocessSettings): List<Pair<Int, ImageQuad>> {
    val quad = settings.logicalQuad ?: return emptyList()
    val quads = if (settings.searchQuadNeighborhood) {
        manualQuadNeighborhood(
            quad,
            settings.version,
            rotationPivotRole = (settings.manualFinders.keys + listOfNotNull(settings.visibleFinderRole)).singleOrNull(),
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
