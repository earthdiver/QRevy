package io.github.earthdiver.qrrepair.core

/** Adds complete decoder lists incrementally, validating combinations before the next bit trial. */
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
        onRejection: (RecoveryCandidateRejection) -> Unit,
        onProgress: (Int, ListDecodingBlockSearch) -> Unit,
        onStops: (Set<CombinationSearchStop>) -> Unit,
        evaluate: (List<PartialCodewordSolver.Result>) -> Boolean,
    ): Boolean {
        val searches = mutableListOf<ListDecodingBlockSearch>()
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
        fun used() = searches.sumOf { it.workingBytes } + de.blocks.sumOf { 256L + it.codewords.size * 28L }
        try {
            for ((index, block) in de.blocks.withIndex()) {
                cancellation.check()
                val space = bits.analyze(block, de.ecSymbolsPerBlock, cancellation)
                val dimensionLimit = if (allowEnumeration && nonEnumerable[index].isEmpty()) partialBitLimit else 0
                val exactCount = space?.dimension?.takeIf { it in 0..dimensionLimit }?.let { 1L shl it } ?: 0L
                // Only exact-space values and one ordinary result are eager. Include their
                // temporary lists and accumulator records; bit trials allocate incrementally.
                val required = (exactCount + 1L) * (2048L + block.codewords.size * 128L)
                if (required + 8192L + block.codewords.size * 64L > workingLimitBytes - used()) {
                    stops += CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return false
                }
                // Keep exact bit-constraint routes, but defer repeated conventional bit enumeration
                // until after the cheaper base list and optional KV pass have been validated.
                val exact = try {
                    solver.decodeWithBitConstraints(block, de.ecSymbolsPerBlock, space, true,
                        allowBitEnumeration = allowEnumeration, nonEnumerablePositions = nonEnumerable[index],
                        cancellation = cancellation, maxEnumeratedBits = partialBitLimit)
                } catch (_: ReedSolomonErasures.DecodeException) { emptyList() }
                val conventional = try {
                    solver.decodeCandidates(block, de.ecSymbolsPerBlock, allowBitEnumeration = false, cancellation = cancellation)
                } catch (_: ReedSolomonErasures.DecodeException) { emptyList() }
                val initial = exact + conventional
                searches += ListDecodingBlockSearch(block, original.blocks[index], initial, partialBitLimit,
                    allowEnumeration, nonEnumerable[index], mismatchLimit, accepts = { values ->
                        val valid = values.indices.all { (values[it] xor pinValues[index][it]) and pinMasks[index][it] == 0 }
                        if (!valid && values.indices.any { (values[it] xor pinValues[index][it]) and fixedMasks[index][it] != 0 }) {
                            onRejection(RecoveryCandidateRejection.FIXED_CELLS)
                        }
                        valid
                    }, check = cancellation::check)
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
                    if (!search.hasNext && search.candidates.isEmpty()) return false
                }
                if (!changed) continue
                val groups = searches.map { it.candidates }
                if (groups.any { it.isEmpty() }) continue
                val affordable = ((workingLimitBytes - used()) / (256L + groups.size * 16L))
                    .coerceIn(0L, (maximum - expanded).toLong()).toInt()
                if (affordable == 0) {
                    stops += if (expanded >= maximum) CombinationSearchStop.NODE_LIMIT else CombinationSearchStop.WORKING_MEMORY_LIMIT
                    return false
                }
                var parsed = false
                val products = bestFirstCartesianProductSequence(groups, { it.orderingPenalty }, affordable,
                    onLimitReached = { stops += if (expanded >= maximum) CombinationSearchStop.NODE_LIMIT else CombinationSearchStop.WORKING_MEMORY_LIMIT },
                ) { prefix ->
                    cancellation.check()
                    expanded++
                    if (prefix.sumOf { requireNotNull(it.originalKnownMismatches) } > mismatchLimit) {
                        onRejection(RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT)
                        false
                    } else {
                        val data = prefix.flatMapIndexed { index, result ->
                            result.corrected.take(de.blocks[index].numDataCodewords).map { it.toByte() }
                        }.toByteArray()
                        StrictQrPayloadValidator.canBePrefix(data, version).also {
                            if (!it) onRejection(RecoveryCandidateRejection.QR_STRUCTURE)
                        }
                    }
                }
                for (combination in products) {
                    cancellation.check()
                    parsed = evaluate(combination) || parsed
                    if (++combinations >= maximum) { stops += CombinationSearchStop.COMBINATION_LIMIT; break }
                }
                if (parsed) { stops += CombinationSearchStop.SUCCESS_WINDOW; return true }
                if (expanded >= maximum) stops += CombinationSearchStop.NODE_LIMIT
                if (stops.any { it != CombinationSearchStop.WORKING_MEMORY_LIMIT }) return false
            }
            return false
        } finally { onStops(stops) }
    }
}
