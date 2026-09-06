package io.github.earthdiver.qrrepair.core

/** Payload text alone cannot identify a QR: masks, ECC and binary streams can differ. */
internal data class RecoveryIdentity(val version: Int, val ecLevel: Int, val mask: Int, val codewords: CodewordIdentity)

/** Recovery-owned corrected arrays are immutable after publication; do not box/copy every byte. */
internal class CodewordIdentity(private val codewords: IntArray) {
    override fun equals(other: Any?): Boolean = other is CodewordIdentity && codewords.contentEquals(other.codewords)
    override fun hashCode(): Int = codewords.contentHashCode()
}

internal fun RecoveryCandidate.identity() =
    RecoveryIdentity(version, ecLevel.ordinal, dataMask, CodewordIdentity(correctedInterleaved))

/** Keep the best-ranked derivation for display, without losing evidence from the other routes. */
internal fun mergeRecoveryDerivations(candidates: List<RecoveryCandidate>): List<RecoveryCandidate> =
    candidates.sortedWith(recoveryCandidateComparator).groupBy { it.identity() }.values.map { group ->
        val routes = group.flatMap { listOf(it.copy(alternativeDerivations = emptyList())) + it.alternativeDerivations }
        routes.first().copy(alternativeDerivations = routes.drop(1))
    }

internal data class GuidanceCandidates(val candidates: List<RecoveryCandidate>, val total: Int)

/** Bound matrix allocation while explicitly retaining the selected validated result. */
internal fun guidanceCandidates(
    candidates: List<RecoveryCandidate>, selected: RecoveryCandidate?, limit: Int = 16,
): GuidanceCandidates {
    require(limit > 0)
    val validated = candidates.filter { it.payloadParsed }.distinctBy { it.identity() }
    val sample = validated.take(limit).toMutableList()
    val selectedResult = selected?.takeIf { it.payloadParsed }?.let { choice ->
        validated.firstOrNull { it.identity() == choice.identity() }
    }
    if (selectedResult != null && sample.none { it.identity() == selectedResult.identity() }) {
        sample[sample.lastIndex] = selectedResult
    }
    return GuidanceCandidates(sample, validated.size)
}
