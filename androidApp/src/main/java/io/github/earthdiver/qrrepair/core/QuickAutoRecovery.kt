package io.github.earthdiver.qrrepair.core

/** Wall-clock allowance shared by all stages of one preparation or repair pass. */
class AutomaticScanBudget(
    private val limitMillis: Long = 3_000,
    private val stopRequested: () -> Boolean = { false },
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    val timeLimitsEnabled: Boolean = true,
    val trace: AutomaticScanTrace? = null,
    val firstRun: Boolean = false,
    private val sliceMultiplier: Int = if (firstRun) 2 else 1,
) {
    companion object {
        private const val NORMAL_MILLIS = 3_000L
        private const val STARTUP_EXTRA_MILLIS = 1_500L
        private const val DECODE_RESERVE_MILLIS = 900L

        /** Called on the worker, so queueing does not consume processing time. */
        fun forImport(firstRun: Boolean, stopRequested: () -> Boolean = { false },
            clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }, trace: AutomaticScanTrace? = null) =
            AutomaticScanBudget(NORMAL_MILLIS + if (firstRun) STARTUP_EXTRA_MILLIS else 0L,
                stopRequested, clockMillis, trace = trace, firstRun = firstRun)
    }

    private val started = clockMillis()
    init {
        require(limitMillis >= 0)
        require(sliceMultiplier in 1..3)
        trace?.event("TIME_BUDGET", "limitMs=$limitMillis firstRun=$firstRun timeLimits=$timeLimitsEnabled sliceMultiplier=$sliceMultiplier")
    }
    private val elapsedMillis: Long get() = (clockMillis() - started).coerceAtLeast(0)
    internal val remainingMillis: Long get() =
        if (timeLimitsEnabled) (limitMillis - elapsedMillis).coerceAtLeast(0) else Long.MAX_VALUE

    /** Preparation and repair share only the startup bonus, not their normal allowances. */
    fun repairAfterPreparation(): AutomaticScanBudget {
        val repairMillis = if (firstRun) minOf(NORMAL_MILLIS + STARTUP_EXTRA_MILLIS,
            (2 * NORMAL_MILLIS + STARTUP_EXTRA_MILLIS - elapsedMillis).coerceAtLeast(0)) else NORMAL_MILLIS
        return AutomaticScanBudget(repairMillis, stopRequested, clockMillis, timeLimitsEnabled, trace, firstRun)
    }

    internal fun curveGenerationStopped(): Boolean = stopped() || remainingMillis <= DECODE_RESERVE_MILLIS
    fun stopReason(): String? = when {
        stopRequested() -> "STOP_REQUESTED"
        timeLimitsEnabled && clockMillis() - started >= limitMillis -> "TOTAL_TIME_LIMIT"
        else -> null
    }
    fun stopped(): Boolean = stopReason() != null
    /** Called only after a slice stopped: a slice can expire before the total allowance. */
    internal fun stopIsTimeLimit(): Boolean = !stopRequested()
    fun slice(millis: Long): () -> Boolean {
        val start = clockMillis()
        val allowance = millis * sliceMultiplier
        return { stopped() || (timeLimitsEnabled && clockMillis() - start >= allowance) }
    }

    internal fun listDecodingBudget(): ListDecodingBudget = if (timeLimitsEnabled)
        ListDecodingBudget(limitMillis = 700L * sliceMultiplier, clockMillis = clockMillis)
    else ListDecodingBudget(limitMillis = Long.MAX_VALUE, clockMillis = { 0L })

    /** Finish checking alternative formats before spending time on another geometry. */
    internal fun exactVerificationSlice(counter: RecoveryDiscoveryCounter): () -> Boolean {
        val initialSlice = slice(600)
        var extended = false
        return {
            when {
                stopped() -> true
                !initialSlice() -> false
                counter.snapshot != RecoveryDiscoveryCount.Exact(1) -> true
                else -> {
                    if (!extended) {
                        extended = true
                        trace?.event("EXACT_VERIFICATION", "CONTINUE reason=ONE_CANDIDATE_WITHIN_TOTAL_BUDGET")
                    }
                    false
                }
            }
        }
    }
}

enum class AutomaticScanAssumption { NONE, UTF8, SINGLE_SEGMENT, EXACT_KNOWN_BITS, UTF8_SINGLE_SEGMENT }

data class QuickAutoRecoveryResult(
    val recovery: AutoRecoveryResult,
    val selected: RecoveryCandidate? = null,
    val assumption: AutomaticScanAssumption? = null,
    val diagnosticReport: String? = null,
    val failureReason: QuickAutoFailureReason? = null,
)

internal data class AutomaticScanSample(val hypothesis: GeometryHypothesis, val profile: SamplingProfile,
    val matrix: QrMatrix, val stats: MatrixSampler.SamplingStats) {
    val rankingUnknownCount = imageUnknownCountForGeometryRanking(matrix, hypothesis.geometry.version)
    val unknownCount = (0 until matrix.dimension).sumOf { y ->
        (0 until matrix.dimension).count { x -> matrix[x, y].value == ModuleValue.ERASED }
    }
    // A scheduling heuristic, not a claim that this matrix cannot be recovered.
    val prefersImageCorrection = unknownCount * 2 > matrix.dimension * matrix.dimension
    var basicStarted = false
}

/** Imported text marks only erase cells; fixed 0/1 edits and image edits are never bypassed. */
internal fun PreprocessSettings.hasOnlyErasedTextEdits(): Boolean = !photographicSource &&
    preRecoverySoftEdits.isEmpty() && preRecoveryEdits.values.all { it == ModuleValue.ERASED }

/** Short, independent trials. The original settings and sampled observations remain immutable. */
class QuickAutoRecovery(private val engine: RecoveryEngine = RecoveryEngine()) {
    fun run(image: LumaImage, settings: PreprocessSettings,
        budget: AutomaticScanBudget = AutomaticScanBudget()): QuickAutoRecoveryResult =
        runInternal(image, settings, budget, textMatrix = false)

    /** The rendered TXT grid has exact cell positions; only explicit stars authorize assignments. */
    fun runTextMatrix(image: LumaImage, settings: PreprocessSettings,
        budget: AutomaticScanBudget = AutomaticScanBudget()): QuickAutoRecoveryResult {
        require(settings.hasOnlyErasedTextEdits())
        require(settings.manualQuad != null && !settings.automaticThresholds)
        return runInternal(image, settings.copy(preRecoveryEdits = emptyMap(), preRecoverySoftEdits = emptySet(),
            preRecoveryPrioritySearch = emptySet()), budget, textMatrix = true,
            textPriority = settings.preRecoveryPrioritySearch)
    }

    private fun runInternal(image: LumaImage, settings: PreprocessSettings,
        budget: AutomaticScanBudget, textMatrix: Boolean, textPriority: Set<Coord> = emptySet()): QuickAutoRecoveryResult {
        // Quick automatic repair is an import operation, never a way around existing manual constraints.
        require(settings.preRecoveryEdits.isEmpty() && settings.preRecoveryPrioritySearch.isEmpty())
        val trace = budget.trace
        val repairStarted = trace?.elapsedMillis ?: 0L
        trace?.event("REPAIR", "START")
        if (textMatrix) trace?.event("INPUT_MODE", "TEXT_MATRIX fixedSampling=true priorityMarks=${textPriority.size} priorityCountLimit=false")
        trace?.event("INPUT", "size=${image.width}x${image.height} version=${settings.version} " +
            "logicalAssigned=${settings.logicalGridAssigned} polarity=${settings.confirmedFinderPolarity} " +
            "quad=${settings.manualQuad} timeLimits=${budget.timeLimitsEnabled}")
        val memory = RecoveryMemoryPolicy.current()
        val retention = RecoveryRetentionBudget(minOf(memory.retainedBytes, 8L * 1024 * 1024),
            maxCandidates = 32, workingLimitBytes = minOf(memory.workingBytes, 16L * 1024 * 1024))
        val accumulator = RecoveryResultAccumulator(retention)
        val counters = AutomaticScanAssumption.entries.associateWith { RecoveryDiscoveryCounter() }
        val allDiscovered = RecoveryDiscoveryCounter()
        var countingIncomplete = false
        // A frozen list clock also disables its internal first-pass time slices, not search/memory caps.
        val listBudget = budget.listDecodingBudget()
        val sampled = mutableListOf<AutomaticScanSample>()
        // Retain only grids already tried with +1; wider lists must not displace ordinary work.
        val widerListSamples = mutableListOf<AutomaticScanSample>()
        var internalError = false
        var timeLimited = false
        trace?.event("MEMORY", "retainedLimitBytes=${retention.limitBytes} workingLimitBytes=${retention.workingLimitBytes}")

        fun result(selected: RecoveryResultEntry? = null, assumption: AutomaticScanAssumption? = null): QuickAutoRecoveryResult {
            val retained = accumulator.result()
            val entries = retained.entries.map { entry ->
                if (selected != null && entry.candidate.identity() == selected.candidate.identity()) selected else entry
            }
            val count = if (countingIncomplete) RecoveryDiscoveryCount.AtLeast(maxOf(allDiscovered.snapshot.value,
                counters.values.maxOf { it.snapshot.value })) else allDiscovered.snapshot
            val end = when {
                selected != null -> "UNIQUE_VERIFIED_CANDIDATE"
                internalError -> "INTERNAL_ERROR"
                settings.manualQuad == null -> "NO_INITIAL_GRID"
                budget.stopReason() != null -> budget.stopReason()
                else -> "NO_UNIQUE_REPORTABLE_CANDIDATE"
            }
            val recovery = retained.copy(entries = entries,
                attempts = (entries.map { it.attempt } + retained.attempts.filter { it.recoveries.isEmpty() }).distinct(),
                discoveredCandidates = count, interrupted = budget.stopped())
            val failureReason = if (selected != null) null else QuickAutoFailureReason.classify(recovery,
                settings.manualQuad != null, budget.stopReason(), timeLimited, internalError)
            trace?.event("FINISH", "reason=$end repairMs=${trace.elapsedMillis - repairStarted} discovered=$count attempts=${retained.attemptedGeometries} " +
                "incomplete=${retained.searchDiagnostic?.incompleteReasons} failure=$failureReason")
            return QuickAutoRecoveryResult(recovery, selected?.candidate, assumption, trace?.report(), failureReason)
        }

        fun trial(sample: AutomaticScanSample, policy: RecoveryExecutionPolicy,
            assumptions: List<AutomaticScanAssumption> = payloadAssumptions,
            listExtension: Int = 1): QuickAutoRecoveryResult? {
            if (policy == RecoveryExecutionPolicy.AUTOMATIC_EXTENDED && listExtension == 1 &&
                widerListSamples.none { it === sample }) {
                widerListSamples += sample
                widerListSamples.sortWith(compareBy<AutomaticScanSample> { it.rankingUnknownCount }
                    .thenBy { it.hypothesis.quickScore })
                if (widerListSamples.size > 8) widerListSamples.removeAt(widerListSamples.lastIndex)
            }
            for (assumption in assumptions) {
                if (budget.stopped()) return null
                if (listExtension == 2 && listBudget.exhausted) return null
                if (policy == RecoveryExecutionPolicy.AUTOMATIC_BASIC && assumption == AutomaticScanAssumption.NONE &&
                    needsExactFormatTrial(sample.matrix, settings.formatConstraint)) {
                    trial(sample, RecoveryExecutionPolicy.AUTOMATIC_EXACT,
                        listOf(AutomaticScanAssumption.EXACT_KNOWN_BITS))?.let { return it }
                    if (budget.stopped()) return null
                }
                if (policy == RecoveryExecutionPolicy.AUTOMATIC_BASIC && assumption == AutomaticScanAssumption.NONE)
                    sample.basicStarted = true
                if (assumption in listOf(AutomaticScanAssumption.SINGLE_SEGMENT, AutomaticScanAssumption.UTF8_SINGLE_SEGMENT) &&
                    !engine.hasTrustablePayloadHeader(sample.matrix, settings.formatConstraint, 6)) continue
                val request = executionSettings(settings, assumption, listExtension).let {
                    if (textMatrix) it.copy(lmCorrectionEnabled = false, distortionCorrection = false) else it
                }
                val trialStarted = trace?.elapsedMillis ?: 0L
                // Only candidates found in this invocation can extend its verification time.
                val exactTrial = policy == RecoveryExecutionPolicy.AUTOMATIC_EXACT
                val discoveryCounter = if (exactTrial) RecoveryDiscoveryCounter() else counters.getValue(assumption)
                val stop = if (exactTrial) budget.exactVerificationSlice(discoveryCounter) else budget.slice(when (policy) {
                    RecoveryExecutionPolicy.AUTOMATIC_EXTENDED -> 450
                    else -> 200
                })
                trace?.event("DECODE", "START policy=$policy assumption=$assumption curved=${sample.hypothesis.geometry.warp != null} " +
                    "inverted=${sample.profile.inverted} thresholds=${sample.stats.thresholds} " +
                    "unknown=${sample.unknownCount} dataEccLimit=${request.effectiveDataEccMismatchLimit} " +
                    "tentativeBitLimit=0 exactSpaceBitLimit=${policy.exactSpaceBitLimit(0)} " +
                    "listExtension=${request.listDecodingAdditionalErrors}")
                val outcome = engine.recoverWithDiagnostics(sample.matrix,
                    request = RecoveryRequest(
                        trustKnownPayloadCount = request.trustKnownPayloadCount,
                        assumeUtf8ByteSegments = request.assumeUtf8ByteSegments,
                        formatConstraint = request.formatConstraint,
                        maxFormatMismatches = 6,
                        maxKnownDataEccMismatches = request.effectiveDataEccMismatchLimit,
                        listDecodingAdditionalErrors = request.listDecodingAdditionalErrors,
                        knownBitErrorLimit = request.knownBitErrorLimit,
                        executionPolicy = policy,
                        allowAutomaticPrioritySearch = textMatrix,
                    ),
                    execution = RecoveryExecutionControls(
                        retentionBudget = retention.fresh(),
                        stopRequested = stop,
                        stopIsTimeLimit = budget::stopIsTimeLimit,
                        listDecodingBudget = listBudget,
                        discoveryCounter = discoveryCounter,
                    ),
                )
                if (exactTrial) outcome.candidates.forEach(counters.getValue(assumption)::record)
                outcome.candidates.forEach(allDiscovered::record)
                countingIncomplete = countingIncomplete || outcome.searchDiagnostic.candidatesOmitted ||
                    outcome.discoveredCandidates is RecoveryDiscoveryCount.AtLeast
                val profile = sample.profile.copy(settings = request.copy(
                    blackMax = sample.stats.thresholds?.blackMax ?: request.blackMax,
                    whiteMin = sample.stats.thresholds?.whiteMin ?: request.whiteMin))
                val attempt = GeometryAttempt(sample.hypothesis, sample.matrix, sample.stats,
                    outcome.candidates, outcome.failure, outcome.searchDiagnostic)
                accumulator.add(attempt, profile)
                val trialStopped = stop()
                if (trialStopped && budget.stopIsTimeLimit()) timeLimited = true
                val reportable = canReport(outcome)
                trace?.event("DECODE", "END durationMs=${(trace.elapsedMillis - trialStarted)} " +
                    "stop=${budget.stopReason() ?: if (trialStopped) "TRIAL_TIME_LIMIT" else "NONE"} " +
                    "candidates=${outcome.discoveredCandidates} retainedCandidates=${outcome.candidates.size} reportable=$reportable failure=${outcome.failure?.reason} " +
                    "incomplete=${outcome.searchDiagnostic.incompleteReasons} unresolved=${outcome.searchDiagnostic.unresolvedCalculations} " +
                    "rejected=${outcome.searchDiagnostic.candidateRejections} " +
                    "list=${outcome.searchDiagnostic.listDecodingExecution}")
                trace?.event("FORMATS", outcome.searchDiagnostic.formatOutcomes.joinToString(" ") {
                    "${it.ecLevel}/${it.dataMask}=${it.state}(candidate=${it.hasCandidate},reason=${it.reason})"
                })
                if (!trialStopped && reportable) {
                    val candidate = outcome.candidates.single { it.isSelectableRecovery }
                    // A representative must survive the outer retention stage as well.
                    if (accumulator.result().entries.any { it.candidate.identity() == candidate.identity() })
                        return result(RecoveryResultEntry(attempt, candidate, profile), assumption)
                    trace?.event("SELECTION", "Candidate omitted by outer retention")
                }
                // A short decoding slice may expire before producing candidates even on a good
                // grid. Confirm format/header evidence before moving that grid to image correction.
                if (policy == RecoveryExecutionPolicy.AUTOMATIC_BASIC && assumption == AutomaticScanAssumption.NONE &&
                    !budget.stopped()) {
                    val multiple = outcome.candidates.count { it.isSelectableRecovery } >= 2
                    val confirmedFormat = if (!multiple && trialStopped) {
                        settings.formatConstraint?.takeIf { it.candidateCount == 1 }
                            ?: FormatSolver.trustedCandidate(sample.matrix)?.takeIf {
                                settings.formatConstraint?.permits(it.ecLevel, it.dataMask) != false
                            }?.let { QrFormatConstraint(it.ecLevel, it.dataMask) }
                    } else null
                    val timedOutWithHeader = confirmedFormat != null &&
                        engine.hasTrustablePayloadHeader(sample.matrix, confirmedFormat, 6)
                    if (multiple || timedOutWithHeader) {
                        trace?.event("ASSUMPTION_PRIORITY", "reason=" + if (multiple) "MULTIPLE_VALID_CANDIDATES"
                            else "TRIAL_TIME_LIMIT_WITH_CONFIRMED_HEADER")
                        return trial(sample, policy, payloadAssumptions.drop(1))
                    }
                }
            }
            return null
        }

        fun earlyTrial(input: AutomaticScanSample): QuickAutoRecoveryResult? {
            if (input.prefersImageCorrection) {
                trace?.event("DECODE", "DEFER reason=IMAGE_CORRECTION_FIRST unknown=${input.unknownCount}")
                return null
            }
            return trial(input, RecoveryExecutionPolicy.AUTOMATIC_BASIC, listOf(AutomaticScanAssumption.NONE))
        }

        fun sample(hypothesis: GeometryHypothesis, profile: SamplingProfile): AutomaticScanSample {
            val start = trace?.elapsedMillis ?: 0L
            trace?.event("SAMPLE", "START")
            val observations = GridThresholdEstimator.observe(image, hypothesis.geometry, profile.inverted, budget::stopped)
            val (matrix, stats) = MatrixSampler(image, profile).sampleObserved(hypothesis.geometry, profile, observations)
            if (textPriority.isNotEmpty()) {
                val orientation = orientations(requireNotNull(settings.manualQuad)).indexOf(hypothesis.geometry.imageQuad)
                require(orientation >= 0)
                val allowed = QrCellRegions(settings.version).codewordCoordinates
                textPriority.forEach { coord ->
                    var x = coord.x; var y = coord.y
                    repeat(orientation / 2) { val previous = x; x = y; y = matrix.dimension - 1 - previous }
                    if (orientation % 2 == 1) { val previous = x; x = y; y = previous }
                    if (Coord(x, y) in allowed && matrix[x, y].value == ModuleValue.ERASED)
                        matrix.setPrioritySearch(x, y, true)
                }
            }
            trace?.event("SAMPLE", "durationMs=${trace.elapsedMillis - start} stop=${budget.stopReason() ?: "NONE"}")
            return AutomaticScanSample(hypothesis, profile, matrix, stats)
        }

        try {
            val quad = settings.manualQuad ?: return result()
            trace?.event("ORIENTATIONS", "START")
            val seeds = orientations(quad).flatMapIndexed { index, oriented ->
                val pitch = oriented.topLeft.distanceTo(oriented.topRight) / (16 + 4 * settings.version)
                val geometry = GridGeometry(settings.version, FinderRole.TOP_LEFT,
                    oriented.topLeft.x, oriented.topLeft.y, pitch, 0.0, oriented, GeometryModel.MANUAL_PROJECTIVE).reanchored()
                settings.variants().map { profile ->
                    Triple(index, GeometryHypothesis(emptyList(), geometry, quickGeometryScore(image, geometry, profile)), profile)
                }
            }.sortedWith(compareBy<Triple<Int, GeometryHypothesis, SamplingProfile>> {
                if (settings.logicalGridAssigned && it.first == 0) 0 else 1
            }.thenBy { it.second.quickScore })
            trace?.event("ORIENTATIONS", "END ranked=${seeds.map { "${it.first}/${it.third.inverted}/${it.second.quickScore}" }}")
            // The image-ranked orientations get correction time before unrelated rotations
            // and payload assumptions can spend the shared allowance.
            for ((_, hypothesis, profile) in seeds.take(2)) {
                if (budget.stopped()) break
                val input = sample(hypothesis, profile)
                sampled += input
                sampled.sortBy { it.hypothesis.quickScore }
                if (sampled.size > 2) sampled.removeAt(sampled.lastIndex)
                earlyTrial(input)?.let { return it }
            }
            // Correct the image before trying additional payload assumptions. Stream useful
            // curved mappings and keep only two sampled candidates for later extended trials.
            var curveReserveReached = false
            for (input in sampled.sortedBy { it.hypothesis.quickScore }.take(2)) {
                if (budget.stopped()) break
                if (textMatrix) {
                    trial(input, RecoveryExecutionPolicy.AUTOMATIC_BASIC,
                        if (input.basicStarted) payloadAssumptions.drop(1) else payloadAssumptions)?.let { return it }
                    trial(input, RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)?.let { return it }
                    continue
                }
                var refinedInput: AutomaticScanSample? = null
                var uncorrectedVerified = false
                fun tryLm(): QuickAutoRecoveryResult? {
                    if (budget.stopped() || curveReserveReached) return null
                    val verifyOriginal = needsUncorrectedGridVerification(image, input.profile,
                        input.hypothesis.geometry, budget::stopped)
                    if (budget.stopped()) return null
                    if (verifyOriginal && !uncorrectedVerified) {
                        trace?.event("UNCORRECTED_GRID", "VERIFY reason=AT_MOST_TWO_FINDERS_AND_NO_ALIGNMENT")
                        uncorrectedVerified = true
                        trial(input, RecoveryExecutionPolicy.AUTOMATIC_BASIC,
                            if (input.basicStarted) payloadAssumptions.drop(1)
                            else payloadAssumptions)?.let { return it }
                        if (budget.stopped()) return null
                    }
                    val lmStop = budget.slice(250)
                    val lmStarted = trace?.elapsedMillis ?: 0L
                    trace?.event("LM", "START")
                    val refined = projectivePatternHypothesis(image, input.profile, listOf(input.hypothesis), lmStop)
                    if (lmStop() && budget.stopIsTimeLimit()) timeLimited = true
                    trace?.event("LM", "END durationMs=${trace.elapsedMillis - lmStarted} candidate=${refined != null} " +
                        "stop=${budget.stopReason() ?: if (lmStop()) "LM_TIME_LIMIT" else "NONE"}")
                    refinedInput = if (refined != null && !budget.stopped()) sample(refined, input.profile) else null
                    return refinedInput?.let(::earlyTrial)
                }
                if (!input.prefersImageCorrection) tryLm()?.let { return it }
                else trace?.event("LM", "DEFER reason=CURVED_CORRECTION_FIRST")
                val ranked = mutableListOf<AutomaticScanSample>()
                val seenCurves = mutableSetOf<GridGeometry>()
                // A projective LM fit must not displace the original curved-grid seed.
                val curveSeeds = listOfNotNull(input.hypothesis.geometry, refinedInput?.hypothesis?.geometry).distinct().toMutableList()
                var seedIndex = 0
                while (seedIndex < curveSeeds.size) {
                    val seed = curveSeeds[seedIndex++]
                    if (budget.curveGenerationStopped()) {
                        curveReserveReached = !budget.stopped()
                        if (curveReserveReached) timeLimited = true
                        if (curveReserveReached) trace?.event("CURVE_GENERATION",
                            "DEFER reason=DECODE_RESERVE remainingMs=${budget.remainingMillis}")
                        break
                    }
                    trace?.event("CURVE_GENERATION", "START progressive=true")
                    val detector = CurvedGridDetector(image, input.profile, stopRequested = budget::curveGenerationStopped)
                    val iterator = detector.progressiveCandidates(seed).iterator()
                    var generated = 0
                    while (!budget.curveGenerationStopped()) {
                        val generationStarted = trace?.elapsedMillis ?: 0L
                        if (!iterator.hasNext()) break
                        val geometry = iterator.next()
                        generated++
                        trace?.event("CURVE_GENERATION", "CANDIDATE index=$generated durationMs=${trace.elapsedMillis - generationStarted} " +
                            "memoryLimited=${detector.contourMemoryLimited} stop=${budget.stopReason() ?: "NONE"}")
                        if (budget.stopped()) break
                        if (!seenCurves.add(geometry)) continue
                        val candidate = sample(GeometryHypothesis(emptyList(), geometry,
                            quickGeometryScore(image, geometry, input.profile)), input.profile)
                        ranked += candidate
                        ranked.sortWith(compareBy<AutomaticScanSample> { it.rankingUnknownCount }.thenBy { it.hypothesis.quickScore })
                        if (ranked.size > 2) ranked.removeAt(ranked.lastIndex)
                        trace?.event("CURVE_RANKING", "unknown=${candidate.rankingUnknownCount} retained=${candidate in ranked}")
                        if (candidate in ranked) earlyTrial(candidate)?.let { return it }
                    }
                    trace?.event("CURVE_GENERATION", "END candidates=$generated memoryLimited=${detector.contourMemoryLimited} " +
                        "stop=${budget.stopReason() ?: "NONE"}")
                    if (budget.curveGenerationStopped() && !budget.stopped()) {
                        curveReserveReached = true
                        timeLimited = true
                        trace?.event("CURVE_GENERATION", "DEFER reason=DECODE_RESERVE remainingMs=${budget.remainingMillis}")
                        break
                    }
                    if (input.prefersImageCorrection && seedIndex == 1) {
                        tryLm()?.let { return it }
                        refinedInput?.hypothesis?.geometry?.takeIf { it !in curveSeeds }?.let(curveSeeds::add)
                    }
                }
                for (candidate in ranked + listOfNotNull(refinedInput) + input) {
                    if (budget.stopped()) break
                    if (candidate === input && uncorrectedVerified) continue
                    trial(candidate, RecoveryExecutionPolicy.AUTOMATIC_BASIC,
                        if (candidate.basicStarted) payloadAssumptions.drop(1)
                        else payloadAssumptions)?.let { return it }
                }
                for (candidate in ranked + listOfNotNull(refinedInput) + input) {
                    if (budget.stopped()) break
                    trial(candidate, RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)?.let { return it }
                }
            }
            for ((_, hypothesis, profile) in seeds.drop(2)) {
                if (budget.stopped()) break
                val input = sample(hypothesis, profile)
                trial(input, RecoveryExecutionPolicy.AUTOMATIC_BASIC)?.let { return it }
                if (textMatrix) trial(input, RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)?.let { return it }
            }
            if (!budget.stopped() && !listBudget.exhausted && widerListSamples.isNotEmpty()) {
                trace?.event("WIDER_LIST", "START extension=2 grids=${widerListSamples.size}")
                for (candidate in widerListSamples) {
                    if (budget.stopped() || listBudget.exhausted) break
                    trial(candidate, RecoveryExecutionPolicy.AUTOMATIC_EXTENDED, listExtension = 2)?.let { return it }
                }
                trace?.event("WIDER_LIST", "END stop=${budget.stopReason() ?: if (listBudget.exhausted)
                    "LIST_DECODING_TIME_LIMIT" else "NONE"}")
            }
        } catch (_: RecoveryCancelledException) {
            trace?.event("CANCELLED", "stop=${budget.stopReason() ?: "CANCELLATION"}")
            // Keep completed trials and the initial grid for the detailed workflow.
        } catch (error: Exception) {
            internalError = true
            trace?.event("ERROR", error.javaClass.simpleName)
            accumulator.markIncomplete(SearchIncompleteReason.INTERNAL_ERROR)
        }
        return result()
    }

    companion object {
        private val payloadAssumptions = listOf(AutomaticScanAssumption.NONE, AutomaticScanAssumption.UTF8,
            AutomaticScanAssumption.SINGLE_SEGMENT, AutomaticScanAssumption.UTF8_SINGLE_SEGMENT)

        // Confirmed or explicitly selected formats already use the ordinary single-format path.
        internal fun needsExactFormatTrial(matrix: QrMatrix, constraint: QrFormatConstraint?): Boolean =
            constraint?.candidateCount != 1 && FormatSolver.trustedCandidate(matrix) == null

        fun executionSettings(saved: PreprocessSettings, assumption: AutomaticScanAssumption,
            listExtension: Int = 1): PreprocessSettings {
            require(listExtension in 1..2)
            val input = if (saved.hasOnlyErasedTextEdits()) saved.copy(preRecoveryEdits = emptyMap(),
                preRecoverySoftEdits = emptySet(), preRecoveryPrioritySearch = emptySet()) else saved
            return input.copy(
                distortionCorrection = true, lmCorrectionEnabled = true,
                knownBitErrorLimit = 0,
                listDecodingAdditionalErrors = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) 0 else listExtension,
                formatMismatchLimitMode = ErrorLimitMode.MANUAL, formatMismatchLimit = 6,
                dataEccMismatchLimitMode = ErrorLimitMode.MANUAL,
                dataEccMismatchLimit = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) 0 else ErrorLimitPolicy.automaticScanData(saved.version),
                assumeUtf8ByteSegments = assumption in listOf(AutomaticScanAssumption.UTF8, AutomaticScanAssumption.UTF8_SINGLE_SEGMENT),
                trustKnownPayloadCount = assumption in listOf(AutomaticScanAssumption.SINGLE_SEGMENT, AutomaticScanAssumption.UTF8_SINGLE_SEGMENT),
            )
        }

        internal fun orientations(quad: ImageQuad): List<ImageQuad> = (0..3).flatMap { turn ->
            val p = quad.asList()
            val rotated = ImageQuad(p[turn], p[(turn + 1) % 4], p[(turn + 2) % 4], p[(turn + 3) % 4])
            listOf(rotated, rotated.transposed())
        }

        internal fun canReport(outcome: RecoveryOutcome): Boolean {
            val diagnostic = outcome.searchDiagnostic
            if (outcome.discoveredCandidates != RecoveryDiscoveryCount.Exact(1) ||
                !diagnostic.coverageComplete || diagnostic.unsearched || diagnostic.incompleteReasons.isNotEmpty() ||
                diagnostic.unresolvedCalculations || diagnostic.candidatesOmitted) return false
            val candidate = outcome.candidates.singleOrNull { it.isSelectableRecovery } ?: return false
            // With zero bit enumeration, SUCCESS_WINDOW in a list pass means its complete
            // current root lists were checked; later decoder parameter trials were skipped.
            val stops = candidate.combinationSearch.stops
            return stops.isEmpty() || (stops == setOf(CombinationSearchStop.SUCCESS_WINDOW) &&
                diagnostic.listDecodingExecution?.newTrials?.get(ListDecodingStage.WITHOUT_ASSIGNMENTS)?.let { it > 0 } == true)
        }
    }
}
