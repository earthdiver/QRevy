package io.github.earthdiver.qrrepair.core

import java.util.IdentityHashMap

/**
 * Live accounting for one bounded solver/result collection, including alternative derivations.
 * This is a conservative accounting unit, not a measurement or cap of the entire Android heap.
 * Block solvers, source images and a currently inspected matrix need additional working memory.
 */
class RecoveryRetentionBudget(
    val limitBytes: Long = 32L * 1024 * 1024,
    val maxCandidates: Int = 256,
    val maxAlternativeDerivations: Int = 8,
    val workingLimitBytes: Long = 32L * 1024 * 1024,
) {
    init {
        require(limitBytes >= 0 && workingLimitBytes >= 0)
        require(maxCandidates > 0 && maxAlternativeDerivations >= 0)
    }
    var retainedBytes: Long = 0
        private set
    var exhausted: Boolean = false
        private set

    private data class ContextCharge(val bytes: Long, var references: Int)
    private data class CandidateCharge(val bytes: Long, val context: CodewordExtraction)
    private class CandidateKey(candidate: RecoveryCandidate) {
        val codewords = candidate.correctedInterleaved
        val extraction = candidate.extraction
        val version = candidate.version
        val ecLevel = candidate.ecLevel
        val mask = candidate.dataMask
        override fun equals(other: Any?): Boolean = other is CandidateKey &&
            codewords === other.codewords && extraction === other.extraction && version == other.version &&
            ecLevel == other.ecLevel && mask == other.mask
        override fun hashCode(): Int = 31 * (31 * (31 * (31 * System.identityHashCode(codewords) +
            System.identityHashCode(extraction)) + version) + ecLevel.ordinal) + mask
    }
    private val contexts = IdentityHashMap<CodewordExtraction, ContextCharge>()
    private val candidates = hashMapOf<CandidateKey, CandidateCharge>()

    /** Independent ownership for a solver invocation or the search-wide result collection. */
    fun fresh() = RecoveryRetentionBudget(limitBytes, maxCandidates, maxAlternativeDerivations, workingLimitBytes)

    /** Array identity survives candidate annotation/copy without allocating a second charge. */
    fun tryRetain(candidate: RecoveryCandidate, maxRetainedBytes: Long = limitBytes): Boolean {
        require(maxRetainedBytes >= 0)
        val effectiveLimit = minOf(limitBytes, maxRetainedBytes)
        if (retainedBytes > effectiveLimit) return false
        val added = mutableListOf<RecoveryCandidate>()
        fun retain(route: RecoveryCandidate): Boolean {
            val key = CandidateKey(route)
            if (!candidates.containsKey(key)) {
                val contextBytes = if (contexts.containsKey(route.extraction)) 0L else contextCost(route)
                val cost = candidateCost(route)
                if (cost + contextBytes > effectiveLimit - retainedBytes) return false
                contexts[route.extraction]?.let { it.references++ }
                    ?: run { contexts[route.extraction] = ContextCharge(contextBytes, 1) }
                candidates[key] = CandidateCharge(cost, route.extraction)
                retainedBytes += cost + contextBytes
                added += route
            }
            return route.alternativeDerivations.all(::retain)
        }
        if (retain(candidate)) return true
        added.forEach(::releaseOne)
        exhausted = true
        return false
    }

    fun releaseCandidate(candidate: RecoveryCandidate) {
        candidate.alternativeDerivations.forEach(::releaseCandidate)
        releaseOne(candidate)
    }

    fun releaseCandidates(candidates: List<RecoveryCandidate>) = candidates.forEach(::releaseCandidate)

    internal fun sameResources(first: RecoveryCandidate, second: RecoveryCandidate): Boolean =
        CandidateKey(first) == CandidateKey(second)

    private fun releaseOne(candidate: RecoveryCandidate) {
        val charge = candidates.remove(CandidateKey(candidate)) ?: return
        exhausted = false
        retainedBytes -= charge.bytes
        val context = requireNotNull(contexts[charge.context])
        if (--context.references == 0) {
            retainedBytes -= context.bytes
            contexts.remove(charge.context)
        }
    }

    private fun candidateCost(candidate: RecoveryCandidate): Long =
        2048L + candidate.correctedInterleaved.size * 4L + candidate.dataBytes.size * 6L +
            candidate.actualBlockMethods.size * 512L + candidate.blockDerivations.values.sumOf { routes ->
                routes.sumOf { 128L + (it.errataPositions.size + it.guessedPositions.size) * 24L }
            }

    private fun contextCost(candidate: RecoveryCandidate): Long =
        // Shared extraction coordinates, reverse mapping, observed masks and constraint maps.
        // This remains an accounting estimate, not an Android heap measurement.
        1024L + candidate.extraction.codewords.size * 512L + candidate.constraintFilledBits * 192L

}

/** Conservative pre-allocation allowance for eager block lists, including solver temporaries. */
internal fun blockCandidateWorkingBytes(
    block: RsBlockWork,
    exactDimension: Int?,
    allowEnumeration: Boolean,
    nonEnumerable: Set<Int>,
    exactOnly: Boolean,
    ecSymbols: Int,
    conventionalMargin: Int? = null,
    partialBitSearchLimit: Int = PartialBitSearchLimit.DEFAULT,
): Long {
    require(partialBitSearchLimit in PartialBitSearchLimit.supported)
    val dimensionLimit = if (allowEnumeration && nonEnumerable.isEmpty()) partialBitSearchLimit else 0
    val exact = exactDimension?.takeIf { it in 0..dimensionLimit }?.let { 1L shl it } ?: 0L
    val erased = block.unknownMasks.count { it != 0 }
    if (exactOnly) {
        // The exact-only path returns Result objects directly: no deduplication map or boxed
        // derivation lists. Account for corrected values, erased positions, headers and refs.
        return exact.coerceAtLeast(1L) * (128L + block.codewords.size * 4L + erased * 4L)
    }
    // A consistent known-bit RS solution confines all uncertain values to erased symbols.
    // With two symbols of spare capacity, ordinary erasure decoding has a comfortable margin
    // and decodeCandidates returns immediately. A diagnosis of this exact unmodified block
    // can establish that same early return even when observed-bit errors make the space empty.
    val comfortableErasureDecode =
        (exactDimension != null && erased <= ecSymbols - 2) || (conventionalMargin ?: Int.MIN_VALUE) >= 2
    var enumeratedBits = 0
    if (allowEnumeration && !comfortableErasureDecode) {
        block.unknownMasks.indices.filter { it !in nonEnumerable }
            .map { block.unknownMasks[it].countOneBits() }.filter { it in 1..4 }.sorted()
            .forEach { bits -> if (enumeratedBits + bits <= partialBitSearchLimit) enumeratedBits += bits }
    }
    val conventional = if (!allowEnumeration || comfortableErasureDecode) 1L else 1L + (1L shl enumeratedBits)
    val trialWorkspace = if (allowEnumeration && !comfortableErasureDecode)
        partialBitTrialWorkingBytes(block.codewords.size, ecSymbols, enumeratedBits) else 0L
    return trialWorkspace + (exact + conventional).coerceAtLeast(1L) * (384L + block.codewords.size * 16L)
}

/** Generate cell explanations only when inspected; never cache a full list per candidate. */
internal class DeferredRepairInfo(
    override val size: Int,
    private val describe: (Int) -> CodewordRepairInfo,
) : AbstractList<CodewordRepairInfo>() {
    override fun get(index: Int): CodewordRepairInfo {
        if (index !in indices) throw IndexOutOfBoundsException("Repair info index: $index")
        return describe(index)
    }
}
