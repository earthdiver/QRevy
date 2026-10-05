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

val RecoveryCandidate.isSelectableRecovery: Boolean
    get() = payloadParsed || StrictQrPayloadValidator.isValid(dataBytes, version)

/** Keep the best distinct results throughout the existing search window, not its first results. */
internal class BoundedRecoveryCandidates(private val budget: RecoveryRetentionBudget) {
    private val entries = linkedMapOf<RecoveryIdentity, RecoveryCandidate>()
    var candidatesOmitted: Boolean = false
        private set
    var derivationsOmitted: Boolean = false
        private set

    fun results(): List<RecoveryCandidate> = entries.values.sortedWith(recoveryCandidateComparator)

    fun containsParsed(candidate: RecoveryCandidate): Boolean = entries[candidate.identity()]?.payloadParsed == true

    fun add(candidate: RecoveryCandidate) {
        val key = candidate.identity()
        val previous = entries[key]
        if (previous != null) {
            addDerivation(key, previous, candidate)
            return
        }
        val worst = worstLast()
        if (entries.size >= budget.maxCandidates && worst != null &&
            recoveryCandidateComparator.compare(candidate, worst) >= 0) {
            candidatesOmitted = true
            return
        }
        val originalOrder = entries.keys.toList()
        val evicted = mutableListOf<RecoveryCandidate>()
        if (entries.size >= budget.maxCandidates && worst != null) {
            entries.remove(worst.identity())
            budget.releaseCandidate(worst)
            evicted += worst
        }
        while (!budget.tryRetain(candidate)) {
            if (releaseOptionalDerivations()) continue
            val lower = worstLast()
                ?.takeIf { recoveryCandidateComparator.compare(candidate, it) < 0 } ?: break
            entries.remove(lower.identity())
            budget.releaseCandidate(lower)
            evicted += lower
        }
        if (budget.tryRetain(candidate)) {
            entries[key] = candidate
        } else {
            // A larger context may not fit even after replacing every lower-ranked result.
            // Restore the old bounded set rather than losing usable results on a failed admission.
            evicted.forEach { old -> check(budget.tryRetain(old)); entries[old.identity()] = old }
            val restored = originalOrder.mapNotNull { identity -> entries[identity]?.let { identity to it } }
            entries.clear()
            entries.putAll(restored)
            candidatesOmitted = true
        }
        if (evicted.isNotEmpty()) candidatesOmitted = true
        if (candidate.derivationsOmitted) derivationsOmitted = true
    }

    private fun worstLast(): RecoveryCandidate? {
        var worst: RecoveryCandidate? = null
        for (candidate in entries.values) {
            if (worst == null || recoveryCandidateComparator.compare(candidate, worst) >= 0) worst = candidate
        }
        return worst
    }

    private fun releaseOptionalDerivations(): Boolean {
        var released = false
        for ((key, candidate) in entries.toMap()) {
            if (candidate.alternativeDerivations.isEmpty()) continue
            candidate.alternativeDerivations.filterNot { budget.sameResources(it, candidate) }
                .forEach(budget::releaseCandidate)
            entries[key] = candidate.copy(alternativeDerivations = emptyList(), derivationsOmitted = true)
            released = true
        }
        if (released) derivationsOmitted = true
        return released
    }

    private fun addDerivation(key: RecoveryIdentity, previous: RecoveryCandidate, candidate: RecoveryCandidate) {
        val routes = (listOf(previous.copy(alternativeDerivations = emptyList())) +
            previous.alternativeDerivations + candidate).sortedWith(recoveryCandidateComparator)
        val chosen = routes.take(1 + budget.maxAlternativeDerivations)
        val dropped = routes.drop(chosen.size)
        // Release only arrays absent from all surviving routes; annotation can share an array.
        dropped.filter { route -> chosen.none { budget.sameResources(it, route) } }
            .forEach(budget::releaseCandidate)
        if (chosen.any { it === candidate } && !budget.tryRetain(candidate)) {
            // Extra explanations must never terminate exploration of distinct QR results.
            derivationsOmitted = true
            entries[key] = previous.copy(derivationsOmitted = true)
            // Restore any previously retained route removed while attempting the replacement.
            check(budget.tryRetain(previous))
            return
        }
        val omitted = dropped.isNotEmpty() || previous.derivationsOmitted || candidate.derivationsOmitted
        if (omitted) derivationsOmitted = true
        val replacement = chosen.first().copy(alternativeDerivations = chosen.drop(1), derivationsOmitted = omitted)
        // LinkedHashMap.put keeps an equal old key, which would pin an evicted route's array.
        // Replace that key too while preserving the identity's original discovery position.
        val updated = entries.map { (oldKey, value) ->
            if (value === previous) replacement.identity() to replacement else oldKey to value
        }
        entries.clear()
        entries.putAll(updated)
    }

    fun clear() {
        budget.releaseCandidates(entries.values.toList())
        entries.clear()
    }
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
