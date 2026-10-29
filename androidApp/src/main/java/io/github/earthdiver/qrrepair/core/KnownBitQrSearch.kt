package io.github.earthdiver.qrrepair.core

/** Original observations only: grammar is evaluated after each error hypothesis. */
internal object KnownBitQrSearch {
    fun run(de: DeinterleavedQr, version: Int, limit: Int, pins: List<Set<RsBitConstraintSolver.Bit>>,
        dimensionLimit: Int, memory: Long, budget: KnownBitErrorBudget, cancellation: RecoveryCancellation,
        evaluate: (List<PartialCodewordSolver.Result>) -> Unit): Set<CombinationSearchStop> {
        val stops = linkedSetOf<CombinationSearchStop>()
        val sources = mutableListOf<RsCandidateSource>()
        val hypotheses = mutableListOf<KnownBitErrorSearch.Hypothesis>()
        var bytes = 0L
        var nodes = 65_536
        val check = RecoveryCancellation { cancellation.check(); budget.check(); false }
        fun visit(index: Int, remaining: Int) {
            check.check()
            if (index == de.blocks.size) {
                if (nodes <= 0) { stops += CombinationSearchStop.NODE_LIMIT; return }
                val traversal = RsPayloadTraversal(sources, version, nodes, check)
                for (combination in traversal.combinations { prefix ->
                    StrictQrPayloadValidator.canBePrefix(prefix.flatMapIndexed { i, r ->
                        r.corrected.take(de.blocks[i].numDataCodewords).map(Int::toByte) }.toByteArray(), version)
                }) {
                    evaluate(combination.mapIndexed { i, r -> r.copy(bitConstraintSolved = false,
                        knownBitErrors = hypotheses[i].errors, knownBitSearch = true,
                        errataPositions = (r.errataPositions.toList() + hypotheses[i].errors.map { it.codeword }).distinct().toIntArray()) })
                }
                nodes -= traversal.nodes
                if (traversal.limited) stops += CombinationSearchStop.NODE_LIMIT
                if (traversal.dimensionLimitedBlock != null) stops += CombinationSearchStop.COMBINATION_LIMIT
                return
            }
            val block = de.blocks[index]
            val required = KnownBitErrorSearch.workingBytes(block.codewords.size, de.ecSymbolsPerBlock)
            if (required > memory - bytes) { stops += CombinationSearchStop.WORKING_MEMORY_LIMIT; return }
            bytes += required
            try {
                val search = KnownBitErrorSearch(block, de.ecSymbolsPerBlock, check)
                for (h in search.hypotheses(remaining, pins[index], budget)) {
                    val spaceBytes = h.space.restrictionWorkingBytes + 2048L + block.numDataCodewords * 4096L
                    if (spaceBytes > memory - bytes) { stops += CombinationSearchStop.WORKING_MEMORY_LIMIT; continue }
                    bytes += spaceBytes
                    sources += RsCandidateSource(h.block, emptyList(), h.space, dimensionLimit)
                    hypotheses += h
                    try { visit(index + 1, remaining - h.errors.size) } finally {
                        sources.removeAt(sources.lastIndex); hypotheses.removeAt(hypotheses.lastIndex); bytes -= spaceBytes
                    }
                    if (nodes <= 0) break
                }
            } finally { bytes -= required }
        }
        budget.measure { visit(0, limit) }
        return stops
    }
}
