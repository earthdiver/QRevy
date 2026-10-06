package io.github.earthdiver.qrrepair.core

/** Exact spaces stay symbolic; completed candidates use the same QR evaluator as ordinary RS. */
internal class StreamingQrSearch(private val rs: ReedSolomonErasures) {
    data class Outcome(val parsed: Boolean, val candidates: Int, val failedBlock: Int? = null, val failure: RsFailureReason? = null)

    fun run(
        de: DeinterleavedQr,
        spaces: List<RsBitConstraintSolver.Space?>,
        version: Int,
        exactOnly: Boolean,
        allowEnumeration: Boolean,
        nonEnumerable: List<Set<Int>>,
        partialBitLimit: Int,
        workingLimitBytes: Long,
        cancellation: RecoveryCancellation,
        onStops: (Set<CombinationSearchStop>) -> Unit,
        onSearch: (SearchCompleteness) -> Unit,
        evaluate: (List<PartialCodewordSolver.Result>) -> Boolean,
    ): Outcome {
        val stops = linkedSetOf<CombinationSearchStop>()
        var parsed = false
        var candidates = 0
        val sources = mutableListOf<RsCandidateSource>()
        var working = 16_384L + spaces.sumOf { it?.restrictionWorkingBytes ?: 0L } + de.blocks.sumOf { 2048L + it.codewords.size * 64L + it.numDataCodewords * 4096L }
        try {
            for ((index, block) in de.blocks.withIndex()) {
                cancellation.check()
                val space = spaces[index]
                val limit = if (allowEnumeration && nonEnumerable[index].isEmpty()) partialBitLimit else 0
                val required = if (exactOnly) 0L else blockCandidateWorkingBytes(block, 0.takeIf { space?.dimension == 0 },
                    allowEnumeration, nonEnumerable[index], false, de.ecSymbolsPerBlock, partialBitSearchLimit = partialBitLimit)
                if (required > workingLimitBytes - working) {
                    stops += CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return Outcome(parsed, candidates, index, RsFailureReason.BIT_SPACE_LIMIT)
                }
                working += required
                val fixed = if (exactOnly) emptyList() else try {
                    PartialCodewordSolver(rs).decodeWithBitConstraints(block, de.ecSymbolsPerBlock,
                        space?.takeIf { it.dimension == 0 }, false, allowEnumeration, nonEnumerable[index],
                        cancellation, partialBitLimit, onSearch)
                } catch (error: ReedSolomonErasures.DecodeException) {
                    if (space == null || (limit == 0 && space.dimension > 0)) return Outcome(parsed, candidates, index, error.reason)
                    emptyList()
                }
                if (fixed.isEmpty() && (space == null || (limit == 0 && space.dimension > 0)))
                    return Outcome(parsed, candidates, index, if (space == null) RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT
                        else RsFailureReason.BIT_SPACE_LIMIT)
                sources += RsCandidateSource(block, fixed, space, limit)
            }
            val traversal = RsPayloadTraversal(sources, version, 65_536, cancellation)
            val combinations = traversal.combinations { prefix ->
                val data = prefix.flatMapIndexed { index, result ->
                    result.corrected.take(de.blocks[index].numDataCodewords).map { it.toByte() }
                }.toByteArray()
                StrictQrPayloadValidator.canBePrefix(data, version)
            }.takeInExpandingWindows(4096, 65_536, { !parsed }) {
                stops += if (parsed) CombinationSearchStop.SUCCESS_WINDOW else CombinationSearchStop.COMBINATION_LIMIT
            }
            for (combination in combinations) {
                cancellation.check()
                candidates++
                parsed = evaluate(combination) || parsed
            }
            if (traversal.limited) stops += CombinationSearchStop.NODE_LIMIT
            onSearch(SearchCompleteness.COMPLETE)
            return if (candidates == 0 && traversal.dimensionLimitedBlock != null)
                Outcome(parsed, candidates, traversal.dimensionLimitedBlock, RsFailureReason.BIT_SPACE_LIMIT)
            else Outcome(parsed, candidates)
        } finally { onStops(stops) }
    }
}
