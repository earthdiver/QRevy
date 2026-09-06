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
    ) {
        fun derivation() = Derivation(errataPositions.toList(), guessedBits, guessedBytes,
            guessedPositions.toList(), rsMargin, searchPenalty, bitConstraintSolved)
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
    ): List<Result> {
        cancellation.check()
        val dimensionLimit = if (allowBitEnumeration && nonEnumerablePositions.isEmpty()) 12 else 0
        val exact = space?.assignments(dimensionLimit, cancellation)?.map { corrected ->
            Result(corrected, block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray(),
                space.dimension, 0, intArrayOf(), 0, bitConstraintSolved = true)
        }?.toList().orEmpty()
        if (exactOnly) return exact.ifEmpty {
            throw ReedSolomonErasures.DecodeException(
                if (space == null) "No exact known-bit RS-consistent assignment"
                else "Exact RS space exceeds bounded partial-bit solver dimension")
        }
        val conventional = try {
            decodeCandidates(block, nsym, allowBitEnumeration = allowBitEnumeration,
                nonEnumerablePositions = nonEnumerablePositions, cancellation = cancellation)
        } catch (error: ReedSolomonErasures.DecodeException) {
            if (exact.isEmpty()) throw error
            emptyList()
        }
        return BlockCandidateAccumulator().apply {
            conventional.forEach(::add)
            exact.forEach(::add)
        }.results()
    }

    fun decode(
        block: RsBlockWork,
        nsym: Int,
        maxEnumeratedBits: Int = 12,
        maxUnknownBitsPerByte: Int = 4,
        maxTrials: Int = 4096,
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
        maxEnumeratedBits: Int = 12,
        maxUnknownBitsPerByte: Int = 4,
        maxTrials: Int = 4096,
        allowBitEnumeration: Boolean = true,
        nonEnumerablePositions: Set<Int> = emptySet(),
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
    ): List<Result> {
        cancellation.check()
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
                return listOfNotNull(erasureOnlyResult)
                    .ifEmpty { throw ReedSolomonErasures.DecodeException("RS decode failed without enumerable partial erasures") }
            }
        }
        if (!allowBitEnumeration) {
            throw ReedSolomonErasures.DecodeException("Too many erasure bytes without partial-bit search")
        }

        val selectable = erasedPositions
            .map { it to block.unknownMasks[it].countOneBits() }
            .filter { it.first !in nonEnumerablePositions }
            .filter { it.second in 1..maxUnknownBitsPerByte }
            .sortedBy { it.second }
        val requiredBytes = erasedPositions.size - nsym
        val targetRemainingErasures = (nsym - 2).coerceAtLeast(0)
        val minimumDesiredBytes = maxOf(requiredBytes, erasedPositions.size - targetRemainingErasures)
        // A failed or low-margin erasure-only decode can need room for more than one unknown-position
        // error. Within the existing bit/trial bounds, converting additional partial erasures to
        // exact guesses can only increase the RS correction budget; final QR validation still
        // decides which of the resulting codewords is usable.
        val broadenForUnknownErrors = erasureOnlyResult == null || erasureOnlyResult.rsMargin < 2
        val selected = mutableListOf<Pair<Int, Int>>()
        var totalBits = 0
        for (entry in selectable) {
            if (!broadenForUnknownErrors && selected.size >= minimumDesiredBytes) break
            if (totalBits + entry.second > maxEnumeratedBits) continue
            selected += entry; totalBits += entry.second
        }
        if (selected.size < requiredBytes || totalBits > maxEnumeratedBits) {
            if (erasureOnlyResult != null) return listOf(erasureOnlyResult)
            throw ReedSolomonErasures.DecodeException("Too many erasure bytes for bounded partial-bit solver")
        }

        val selectedSet = selected.map { it.first }.toSet()
        val remainingErase = erasedPositions.filterNot { it in selectedSet }.toIntArray()
        val trials = minOf(1 shl totalBits, maxTrials)
        val candidates = BlockCandidateAccumulator()
        erasureOnlyResult?.let(candidates::add)
        for (assignment in 0 until trials) {
            cancellation.check()
            val guessed = block.codewords.copyOf()
            var cursor = 0
            for ((pos, _) in selected) {
                val unknownMask = block.unknownMasks[pos]
                var value = guessed[pos]
                for (bit in 0 until 8) {
                    val mask = 1 shl bit
                    if ((unknownMask and mask) == 0) continue
                    val v = (assignment shr cursor) and 1
                    value = if (v == 1) value or mask else value and mask.inv()
                    cursor++
                }
                guessed[pos] = value and 0xFF
            }
            val r = try { rs.decode(guessed, nsym, remainingErase) } catch (_: ReedSolomonErasures.DecodeException) { continue }
            var guessMismatch = 0
            for ((pos, _) in selected) if (r.corrected[pos] != guessed[pos]) guessMismatch++
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
                selected.map { it.first }.toIntArray(),
                margin,
                penalty,
            )
            candidates.add(candidate)
        }
        if (candidates.isEmpty()) throw ReedSolomonErasures.DecodeException("Partial-bit enumeration found no RS-consistent assignment")
        // [trials] is the explicit resource bound. Preserve every distinct solution within that
        // fully enumerated assignment set; final QR payload parsing, not a per-block result cap,
        // decides which candidate is usable.
        return candidates.results()
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
    private val representativeOrder = compareBy<PartialCodewordSolver.Result> { it.bitConstraintSolved }
        .thenByDescending { it.rsMargin }
        .thenBy { it.guessedBits }
        .thenBy { it.errataPositions.size }
        .thenBy { it.searchPenalty }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun add(result: PartialCodewordSolver.Result) {
        val entry = entries.getOrPut(Key(result.corrected)) { Entry(result) }
        entry.routes += result.derivation()
        entry.routes += result.alternativeDerivations
        if (representativeOrder.compare(result, entry.representative) < 0) entry.representative = result
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
