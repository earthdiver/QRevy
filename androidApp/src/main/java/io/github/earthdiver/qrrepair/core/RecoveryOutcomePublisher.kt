package io.github.earthdiver.qrrepair.core

/** Captures original manual observations, then publishes one shared diagnostic for all retained routes. */
internal class RecoveryOutcomePublisher(
    matrix: QrMatrix,
    manualEdits: Map<Coord, ModuleValue>,
    private val conditions: RecoveryDisplayConditions,
    private val cancellation: RecoveryCancellation,
) {
    private val manual = buildMap {
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val cell = matrix[x, y]
            if (cell.source.isUserEdit) put(Coord(x, y), cell.value)
        }
    } + manualEdits

    fun publish(
        found: List<RecoveryCandidate>,
        run: RecoveryRunState,
        discoveryCounter: RecoveryDiscoveryCounter,
    ): RecoveryOutcome {
        // Share one immutable summary across potentially thousands of retained derivations.
        val publishedDiagnostic by lazy(LazyThreadSafetyMode.NONE) { run.searchDiagnostic() }
        fun annotate(candidate: RecoveryCandidate): RecoveryCandidate {
            cancellation.check()
            var manualMismatches = 0
            var manualCompared = 0
            candidate.extraction.codewords.forEachIndexed { raw, word ->
                cancellation.check()
                word.coordinates.forEachIndexed bitLoop@{ bit, coord ->
                    val observed = manual[coord]?.bitOrNull() ?: return@bitLoop
                    manualCompared++
                    val expected = ((candidate.correctedInterleaved[raw] shr (7 - bit)) and 1) xor
                        if (CodewordExtractor.maskApplies(candidate.dataMask, coord.x, coord.y)) 1 else 0
                    if (observed != expected) manualMismatches++
                }
            }
            return candidate.copy(combinationSearch = run.combinationSearch, displayConditions = conditions,
                searchDiagnostic = publishedDiagnostic,
                formatSelection = FormatSelection(conditions.format?.ecLevel != null, conditions.format?.dataMask != null),
                manualDataEccMismatches = manualMismatches, manualDataEccCompared = manualCompared,
                alternativeDerivations = candidate.alternativeDerivations.map(::annotate))
        }
        val candidates = found.map(::annotate)
        return RecoveryOutcome(
            candidates = candidates,
            failure = run.failure?.copy(combinationSearch = run.combinationSearch, displayConditions = conditions,
                searchDiagnostic = publishedDiagnostic),
            searchDiagnostic = publishedDiagnostic,
            formatAttempts = run.formatAttempts,
            discoveredCandidates = discoveryCounter.snapshot,
        )
    }
}
