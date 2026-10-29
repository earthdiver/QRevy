package io.github.earthdiver.qrrepair.core

/** Bounded bit-level search that converts a few partial bytes into exact guesses before RS decoding. */
class PartialCodewordSolver(private val rs: ReedSolomonErasures) {
    /** Route metadata is independent of the corrected values shared by equivalent solutions. */
    data class Derivation(
        val errataPositions: List<Int>,
        val guessedBits: Int,
        val guessedBytes: Int,
        val guessedPositions: List<Int>,
        val rsMargin: Int,
        val searchPenalty: Int,
        val bitConstraintSolved: Boolean,
        val listDecoding: ListDecodingDerivation? = null,
        val knownBitErrors: List<RsBitConstraintSolver.Bit> = emptyList(),
        val knownBitSearch: Boolean = false,
    )

    data class Result(
        val corrected: IntArray,
        val errataPositions: IntArray,
        val guessedBits: Int,
        val guessedBytes: Int,
        /** Local RS-block codeword positions whose partial bits were enumerated. */
        val guessedPositions: IntArray,
        val rsMargin: Int,
        /** Explanatory search ordering only; a lower value is not proof of correctness. */
        val searchPenalty: Int = 0,
        /** This result was obtained without changing any known bit of the supplied block. */
        val bitConstraintSolved: Boolean = false,
        val alternativeDerivations: List<Derivation> = emptyList(),
        /** Lowest traversal penalty across all retained routes; not a property of the representative route. */
        val orderingPenalty: Int = searchPenalty,
        val listDecoding: ListDecodingDerivation? = null,
        val knownBitErrors: List<RsBitConstraintSolver.Bit> = emptyList(),
        val knownBitSearch: Boolean = false,
        /** Original pre-inference observation; populated by the list-decoding integration. */
        val originalKnownMismatches: Int? = null,
    ) {
        fun derivation() = Derivation(errataPositions.toList(), guessedBits, guessedBytes,
            guessedPositions.toList(), rsMargin, searchPenalty, bitConstraintSolved, listDecoding, knownBitErrors, knownBitSearch)
    }

    /** Supplement, never replace, errors+erasures decoding when observed-bit errors are allowed. */
    fun decodeWithBitConstraints(
        block: RsBlockWork,
        nsym: Int,
        space: RsBitConstraintSolver.Space?,
        exactOnly: Boolean,
        allowBitEnumeration: Boolean = true,
        nonEnumerablePositions: Set<Int> = emptySet(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        maxEnumeratedBits: Int = PartialBitSearchLimit.DEFAULT,
        reportSearch: (SearchCompleteness) -> Unit = {},
    ): List<Result> = reportingCompletion(reportSearch) {
        cancellation.check()
        require(maxEnumeratedBits in 0..20)
        val dimensionLimit = if (allowBitEnumeration && nonEnumerablePositions.isEmpty()) maxEnumeratedBits else 0
        val exact = space?.assignments(dimensionLimit, cancellation)?.map { corrected ->
            Result(corrected, block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray(),
                space.dimension, 0, intArrayOf(), 0, bitConstraintSolved = true)
        }?.toList().orEmpty()
        if (exactOnly) {
            return@reportingCompletion exact.ifEmpty {
                throw ReedSolomonErasures.DecodeException(
                    if (space == null) "No exact known-bit RS-consistent assignment"
                    else "Exact RS space exceeds bounded partial-bit solver dimension",
                    if (space == null) RsFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT else RsFailureReason.BIT_SPACE_LIMIT)
            }
        }
        val conventional = try {
            decodeCandidates(block, nsym, maxEnumeratedBits = maxEnumeratedBits, allowBitEnumeration = allowBitEnumeration,
                nonEnumerablePositions = nonEnumerablePositions, cancellation = cancellation)
        } catch (error: ReedSolomonErasures.DecodeException) {
            if (exact.isEmpty()) {
                throw error
            }
            emptyList()
        }
        return@reportingCompletion BlockCandidateAccumulator().apply {
            conventional.forEach(::add)
            exact.forEach(::add)
        }.results()
    }

    fun decode(
        block: RsBlockWork,
        nsym: Int,
        maxEnumeratedBits: Int = PartialBitSearchLimit.DEFAULT,
        maxUnknownBitsPerByte: Int = 4,
        maxTrials: Int = 1 shl maxEnumeratedBits,
        allowBitEnumeration: Boolean = true,
        nonEnumerablePositions: Set<Int> = emptySet(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
    ): Result = decodeCandidates(
        block, nsym, maxEnumeratedBits, maxUnknownBitsPerByte, maxTrials,
        allowBitEnumeration, nonEnumerablePositions, cancellation,
    ).first()

    /**
     * Returns every distinct RS solution found by the bounded bit search, ordered by penalty.
     * Silently selecting the first tied solution can turn an information-theoretically ambiguous
     * observation into a falsely unique payload, so ambiguity is preserved for the QR-level parser.
     */
    fun decodeCandidates(
        block: RsBlockWork,
        nsym: Int,
        maxEnumeratedBits: Int = PartialBitSearchLimit.DEFAULT,
        maxUnknownBitsPerByte: Int = 4,
        maxTrials: Int = 1 shl maxEnumeratedBits,
        allowBitEnumeration: Boolean = true,
        nonEnumerablePositions: Set<Int> = emptySet(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        reportSearch: (SearchCompleteness) -> Unit = {},
    ): List<Result> = reportingCompletion(reportSearch) {
        cancellation.check()
        require(maxEnumeratedBits in 0..20)
        require(maxTrials > 0)
        val erasedPositions = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }
        var erasureOnlyResult: Result? = null
        if (erasedPositions.size <= nsym) {
            val erase = erasedPositions.toIntArray()
            try {
                val r = rs.decode(block.codewords, nsym, erase)
                val rsMargin = margin(nsym, erase, r.errataPositions)
                erasureOnlyResult = Result(r.corrected, r.errataPositions, 0, 0, intArrayOf(), rsMargin, -rsMargin)
            } catch (error: ReedSolomonErasures.DecodeException) {
                // An erasure-only attempt can consume the complete RS budget while a damaged
                // observed byte still needs two symbols of correction capacity. A few partial
                // erasures may be enumerable, so let the bounded search turn them into exact
                // guesses and reserve room for that otherwise unknown-position error.
                if (!allowBitEnumeration) throw error
            }
            val hasComfortableMargin = erasureOnlyResult?.rsMargin?.let { it >= 2 } == true
            if (!allowBitEnumeration || hasComfortableMargin) {
                return@reportingCompletion listOfNotNull(erasureOnlyResult)
                    .ifEmpty { throw ReedSolomonErasures.DecodeException("RS decode failed without enumerable partial erasures") }
            }
        }
        if (!allowBitEnumeration) {
            throw ReedSolomonErasures.DecodeException("Too many erasure bytes without partial-bit search",
                RsFailureReason.ERASURE_CAPACITY_EXCEEDED)
        }

        val plan = PartialBitTrialPlan(block, maxEnumeratedBits, maxUnknownBitsPerByte, nonEnumerablePositions)
        val selected = plan.selected
        val totalBits = plan.bitCount
        val requiredBytes = erasedPositions.size - nsym
        if (selected.size < requiredBytes || totalBits > maxEnumeratedBits) {
            if (erasureOnlyResult != null) return@reportingCompletion listOf(erasureOnlyResult)
            throw ReedSolomonErasures.DecodeException("Too many erasure bytes for bounded partial-bit solver",
                RsFailureReason.BOUNDED_BIT_SEARCH_LIMIT)
        }

        val remainingErase = plan.remainingErasures
        val trials = minOf(1 shl totalBits, maxTrials)
        val candidates = BlockCandidateAccumulator()
        erasureOnlyResult?.let(candidates::add)
        val cursor = plan.cursor(rs, nsym)
        for (assignment in 0 until trials) {
            cancellation.check()
            val guessed = cursor.assignment(assignment)
            val r = try { cursor.decode() } catch (_: ReedSolomonErasures.DecodeException) { continue }
            var guessMismatch = 0
            for (pos in selected) if (r.corrected[pos] != guessed[pos]) guessMismatch++
            var knownBitMismatch = 0
            for (pos in block.codewords.indices) {
                val knownMask = block.unknownMasks[pos].inv() and 0xFF
                knownBitMismatch += ((r.corrected[pos] xor block.codewords[pos]) and knownMask).countOneBits()
            }
            val margin = margin(nsym, remainingErase, r.errataPositions)
            val penalty = guessMismatch * 100 + knownBitMismatch * 20 - margin
            val candidate = Result(
                r.corrected,
                r.errataPositions,
                totalBits,
                selected.size,
                selected,
                margin,
                penalty,
            )
            candidates.add(candidate)
        }
        if (candidates.isEmpty()) throw ReedSolomonErasures.DecodeException("Partial-bit enumeration found no RS-consistent assignment",
            RsFailureReason.NO_CONSISTENT_ASSIGNMENT)
        // [trials] is the explicit resource bound. Preserve every distinct solution within that
        // fully enumerated assignment set; final QR payload parsing, not a per-block result cap,
        // decides which candidate is usable.
        return@reportingCompletion candidates.results()
    }

    /** Completion of the specified bounded policy is independent of finding a solution. */
    private inline fun <T> reportingCompletion(report: (SearchCompleteness) -> Unit, action: () -> T): T {
        val result = try {
            action()
        } catch (error: ReedSolomonErasures.DecodeException) {
            report(SearchCompleteness.COMPLETE)
            throw error
        } catch (error: RecoveryCancelledException) {
            report(SearchCompleteness.UNSEARCHED)
            throw error
        } catch (error: Exception) {
            report(SearchCompleteness.UNKNOWN)
            throw error
        }
        report(SearchCompleteness.COMPLETE)
        return result
    }

    private fun margin(nsym: Int, erase: IntArray, errata: IntArray): Int {
        val e = erase.toSet()
        val errors = errata.count { it !in e }
        return nsym - (erase.size + 2 * errors)
    }
}

/** Deduplicate values while retaining route evidence and the best conventional explanation. */
internal class BlockCandidateAccumulator {
    private class Key(private val values: IntArray) {
        override fun equals(other: Any?) = other is Key && values.contentEquals(other.values)
        override fun hashCode() = values.contentHashCode()
    }
    private class Entry(var representative: PartialCodewordSolver.Result) {
        val routes = linkedSetOf<PartialCodewordSolver.Derivation>()
    }
    private val entries = linkedMapOf<Key, Entry>()
    // Conventional margins remain preferred over the binary route's non-applicable placeholder.
    // Within conventional routes follow QR result ranking: margin, search bits, target symbols.
    private val representativeOrder = compareBy<PartialCodewordSolver.Result> { it.listDecoding != null }
        .thenBy { it.bitConstraintSolved }
        .thenByDescending { it.rsMargin }
        .thenBy { it.guessedBits }
        .thenBy { it.errataPositions.size }
        .thenBy { it.searchPenalty }

    var workingBytes: Long = 0
        private set

    var routeRevision: Long = 0
        private set

    /** Admission order comes from the existing map; no per-candidate revision table is retained. */
    fun admittedAfter(result: PartialCodewordSolver.Result, count: Int): Boolean {
        for ((index, entry) in entries.values.withIndex())
            if (entry.representative.corrected.contentEquals(result.corrected)) return index >= count
        error("Candidate does not belong to this accumulator")
    }

    val size: Int get() = entries.size
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Conservative added charge, checked before retaining new list-decoded values or explanations. */
    fun additionalBytes(result: PartialCodewordSolver.Result): Long {
        val entry = entries[Key(result.corrected)]
        val routes = (listOf(result.derivation()) + result.alternativeDerivations).distinct()
        return (if (entry == null) 512L + result.corrected.size * 16L else 0L) +
            routes.filter { entry == null || it !in entry.routes }.sumOf {
                256L + (it.errataPositions.size + it.guessedPositions.size) * 24L
            }
    }

    fun add(result: PartialCodewordSolver.Result) {
        workingBytes += additionalBytes(result)
        val previous = entries[Key(result.corrected)]
        val entry = entries.getOrPut(Key(result.corrected)) { Entry(result) }
        val oldRoutes = entry.routes.size
        val oldRepresentative = entry.representative
        entry.routes += result.derivation()
        entry.routes += result.alternativeDerivations
        if (representativeOrder.compare(result, entry.representative) < 0) entry.representative = result
        if (previous != null && (entry.routes.size != oldRoutes || entry.representative !== oldRepresentative)) routeRevision++
    }

    fun results(): List<PartialCodewordSolver.Result> = entries.values.map { entry ->
        val representative = entry.representative
        val mainRoute = representative.derivation()
        representative.copy(
            alternativeDerivations = entry.routes.filterNot { it == mainRoute },
            orderingPenalty = entry.routes.minOf { it.searchPenalty },
        )
    }.sortedBy { it.orderingPenalty }
}
