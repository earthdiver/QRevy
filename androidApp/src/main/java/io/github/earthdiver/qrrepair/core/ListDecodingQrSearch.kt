package io.github.earthdiver.qrrepair.core

/** Adds complete decoder lists incrementally; only a newly decoded QR ends further bit trials. */
internal class ListDecodingQrSearch(private val rs: ReedSolomonErasures) {
    fun run(
        de: DeinterleavedQr,
        original: DeinterleavedQr,
        matrix: QrMatrix,
        extraction: CodewordExtraction,
        priorityAssignments: Set<Coord>,
        version: Int,
        mismatchLimit: Int,
        allowEnumeration: Boolean,
        nonEnumerable: List<Set<Int>>,
        partialBitLimit: Int,
        workingLimitBytes: Long,
        cancellation: RecoveryCancellation,
        baseOnly: Boolean = false,
        cache: ListDecodingTrialCache? = null,
        additionalErrors: Int = ListDecodingLimit.DEFAULT,
        onRejection: (RecoveryCandidateRejection) -> Unit,
        onProgress: (Int, ListDecodingBlockSearch) -> Unit,
        onStops: (Set<CombinationSearchStop>) -> Unit,
        evaluate: (List<PartialCodewordSolver.Result>) -> Boolean,
    ): Boolean {
        val prefixCacheBudget = minOf(32L * 1024, workingLimitBytes / 64)
        val prefixCache = QrPrefixValidationCache(version, prefixCacheBudget)
        val searches = mutableListOf<ListDecodingBlockSearch>()
        val spaces = mutableListOf<RsBitConstraintSolver.Space?>()
        val stops = linkedSetOf<CombinationSearchStop>()
        var expanded = 0
        var combinations = 0
        val maximum = 65_536
        val solver = PartialCodewordSolver(rs)
        val bits = RsBitConstraintSolver(rs)
        val pinMasks = de.blocks.map { IntArray(it.codewords.size) }
        val fixedMasks = de.blocks.map { IntArray(it.codewords.size) }
        val pinValues = de.blocks.map { IntArray(it.codewords.size) }
        for (raw in extraction.codewords.indices) {
            val location = requireNotNull(de.rawLocations[raw])
            val word = extraction.codewords[raw]
            word.coordinates.forEachIndexed { bit, coord ->
                val cell = matrix[coord.x, coord.y]
                if (cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) {
                    pinMasks[location.blockIndex][location.localIndex] =
                        pinMasks[location.blockIndex][location.localIndex] or (1 shl (7 - bit))
                    if (coord !in priorityAssignments) {
                        fixedMasks[location.blockIndex][location.localIndex] =
                            fixedMasks[location.blockIndex][location.localIndex] or (1 shl (7 - bit))
                    }
                }
            }
            pinValues[location.blockIndex][location.localIndex] = word.value
        }
        fun used() = prefixCacheBudget + (cache?.workingBytes ?: 0L) + searches.sumOf { it.workingBytes } + spaces.sumOf { it?.restrictionWorkingBytes ?: 0L } +
            de.blocks.sumOf { 256L + it.codewords.size * 28L } +
            if (spaces.any { it != null }) 16_384L + de.blocks.sumOf {
                2048L + it.codewords.size * 64L + it.numDataCodewords * 4096L
            } else 0L
        try {
            for ((index, block) in de.blocks.withIndex()) {
                cancellation.check()
                val space = bits.analyze(block, de.ecSymbolsPerBlock, cancellation)
                val dimensionLimit = if (allowEnumeration && nonEnumerable[index].isEmpty()) partialBitLimit else 0
                spaces += space?.takeIf { it.dimension > 0 && dimensionLimit > 0 && !baseOnly }
                val exactCount = if (space?.dimension == 0) 1L else 0L
                // Only zero-dimensional exact solutions and one ordinary result are eager. Include their
                // temporary lists and accumulator records; bit trials allocate incrementally.
                val required = (exactCount + 1L) * (2048L + block.codewords.size * 128L)
                if (required + 8192L + block.codewords.size * 64L > workingLimitBytes - used()) {
                    stops += CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return false
                }
                // Keep exact bit-constraint routes, but defer repeated conventional bit enumeration
                // until after the cheaper base list and optional KV pass have been validated.
                val exact = try {
                    solver.decodeWithBitConstraints(block, de.ecSymbolsPerBlock, space?.takeIf { it.dimension == 0 }, true,
                        allowBitEnumeration = allowEnumeration, nonEnumerablePositions = nonEnumerable[index],
                        cancellation = cancellation, maxEnumeratedBits = partialBitLimit)
                } catch (_: ReedSolomonErasures.DecodeException) { emptyList() }
                val conventional = try {
                    solver.decodeCandidates(block, de.ecSymbolsPerBlock, allowBitEnumeration = false, cancellation = cancellation)
                } catch (_: ReedSolomonErasures.DecodeException) { emptyList() }
                val initial = exact + conventional
                // KV uses partial observations without enumerating tentative assignments.
                searches += ListDecodingBlockSearch(block, original.blocks[index], initial, if (baseOnly) 0 else partialBitLimit,
                    allowEnumeration || baseOnly, nonEnumerable[index], mismatchLimit, accepts = { values ->
                        val valid = values.indices.all { (values[it] xor pinValues[index][it]) and pinMasks[index][it] == 0 }
                        if (!valid && values.indices.any { (values[it] xor pinValues[index][it]) and fixedMasks[index][it] != 0 }) {
                            onRejection(RecoveryCandidateRejection.FIXED_CELLS)
                        }
                        valid
                    }, check = cancellation::check, cache = cache, additionalErrors = additionalErrors)
                if (used() > workingLimitBytes) {
                    stops += CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return false
                }
            }
            while (searches.any { it.hasNext }) {
                var changed = false
                // Complete each trial's root list before checking QR; do not select one root.
                for ((index, search) in searches.withIndex()) if (search.hasNext) {
                    cancellation.check()
                    try { changed = search.advance(workingLimitBytes - used()) || changed }
                    finally {
                        onProgress(index, search)
                        if (search.memoryLimited) stops += CombinationSearchStop.WORKING_MEMORY_LIMIT
                        if (search.mismatchRejections > 0) onRejection(RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT)
                    }
                    if (!search.hasNext && search.candidates.isEmpty() && spaces[index] == null) return false
                }
                if (!changed) continue
                val groups = searches.map { it.candidates }
                if (groups.indices.any { groups[it].isEmpty() && spaces[it] == null }) continue
                val affordable = ((workingLimitBytes - used()) / (256L + groups.size * 16L))
                    .coerceIn(0L, (maximum - expanded).toLong()).toInt()
                if (affordable == 0) {
                    stops += if (expanded >= maximum) CombinationSearchStop.NODE_LIMIT else CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return false
                }
                var parsed = false
                val symbolic = spaces.any { it != null }
                val acceptPrefix: (List<PartialCodewordSolver.Result>) -> Boolean = { prefix ->
                    cancellation.check()
                    if (!symbolic) expanded++
                    if (prefix.withIndex().sumOf { (index, result) -> result.originalKnownMismatches ?: result.corrected.indices.sumOf {
                            ((result.corrected[it] xor original.blocks[index].codewords[it]) and original.blocks[index].unknownMasks[it].inv() and 255).countOneBits()
                        } } > mismatchLimit) {
                        onRejection(RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT)
                        false
                    } else {
                        val data = prefix.flatMapIndexed { index, result ->
                            result.corrected.take(de.blocks[index].numDataCodewords).map { it.toByte() }
                        }.toByteArray()
                        prefixCache.canBePrefix(data).also {
                            if (!it) onRejection(RecoveryCandidateRejection.QR_STRUCTURE)
                        }
                    }
                }
                val traversal = if (symbolic) RsPayloadTraversal(groups.indices.map {
                    RsCandidateSource(de.blocks[it], groups[it], spaces[it], partialBitLimit)
                }, version, maximum - expanded, cancellation) else null
                val products = traversal?.combinations(acceptPrefix) ?: bestFirstCartesianProductSequence(groups,
                    { it.orderingPenalty }, affordable,
                    onLimitReached = { stops += if (expanded >= maximum) CombinationSearchStop.NODE_LIMIT else CombinationSearchStop.WORKING_MEMORY_LIMIT },
                    canExtend = acceptPrefix)
                for (combination in products) {
                    if (combinations >= maximum) { stops += CombinationSearchStop.COMBINATION_LIMIT; break }
                    cancellation.check()
                    parsed = evaluate(combination) || parsed
                    combinations++
                }
                expanded += traversal?.nodes ?: 0
                if (traversal?.limited == true) stops += CombinationSearchStop.NODE_LIMIT
                if (parsed) {
                    if (searches.any { it.hasNext }) stops += CombinationSearchStop.SUCCESS_WINDOW
                    return true
                }
                if (expanded >= maximum && searches.any { it.hasNext }) stops += CombinationSearchStop.NODE_LIMIT
                if (stops.any { it != CombinationSearchStop.WORKING_MEMORY_LIMIT }) return false
            }
            return false
        } finally { onStops(stops) }
    }
}
