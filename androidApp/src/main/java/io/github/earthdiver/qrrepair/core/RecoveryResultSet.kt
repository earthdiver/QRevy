package io.github.earthdiver.qrrepair.core

import java.util.IdentityHashMap

/** A derivation keeps its own observation and repair evidence without retaining another full grid. */
data class RecoveryResultDerivation(
    val hypothesis: GeometryHypothesis,
    val samplingStats: MatrixSampler.SamplingStats,
    val profile: SamplingProfile?,
    val candidate: RecoveryCandidate,
)

/** Every selectable QR is paired with the exact sampled input that produced its representative. */
data class RecoveryResultEntry(
    val attempt: GeometryAttempt,
    val candidate: RecoveryCandidate,
    val profile: SamplingProfile?,
    val alternativeDerivations: List<RecoveryResultDerivation> = emptyList(),
    val derivationsOmitted: Boolean = false,
    val candidateIndex: Int = 0,
)

internal val recoveryResultComparator = Comparator<RecoveryResultEntry> { first, second ->
    recoveryCandidateComparator.compare(first.candidate, second.candidate).let { comparison ->
        if (comparison != 0) comparison
        else compareValues(first.attempt.hypothesis.quickScore, second.attempt.hypothesis.quickScore)
    }
}

/**
 * Consume attempts as they finish. Only ranked distinct QR results and the best unsuccessful
 * reference survive; the total attempt count and diagnostic evidence do not depend on retention.
 * Use one accumulator across sampling profiles so a completed profile cannot pin discarded grids.
 */
class RecoveryResultAccumulator(
    private val policy: RecoveryRetentionBudget = RecoveryRetentionBudget(),
) {
    private var entriesByIdentity = linkedMapOf<RecoveryIdentity, RecoveryResultEntry>()
    private var reference: GeometryAttempt? = null
    private var referenceProfile: SamplingProfile? = null
    private var summary: RecoverySearchDiagnostic? = null
    private var sawMissingDiagnostic = false
    private var candidatesOmitted = false
    private var derivationsOmitted = false
    private val incompleteReasons = linkedSetOf<SearchIncompleteReason>()
    private var completedPolicies = 0
    private var excludedInputs = false
    var attemptedGeometries: Int = 0
        private set
    private val testedHypotheses = mutableListOf<GeometryHypothesis>()

    fun add(attempt: GeometryAttempt, profile: SamplingProfile?) {
        attemptedGeometries++
        testedHypotheses += attempt.hypothesis
        val diagnostic = attempt.searchDiagnostic
        if (diagnostic == null) sawMissingDiagnostic = true
        else summary = if (summary == null) diagnostic else
            RecoverySearchDiagnostic.combine(listOf(summary, diagnostic))
        // The origin must never hold the complete incoming candidate list after eviction.
        val origin = attempt.copy(recoveries = emptyList(), failure = null)
        val pending = LinkedHashMap(entriesByIdentity)
        var hasSelectableResult = false
        var referenceCandidate: RecoveryCandidate? = null
        for ((index, candidate) in attempt.recoveries.withIndex()) {
            if (candidate.isSelectableRecovery) {
                hasSelectableResult = true
                val incoming = RecoveryResultEntry(origin, candidate, profile, candidateIndex = index)
                val identity = candidate.identity()
                pending[identity] = pending[identity]?.let { merge(it, incoming) } ?: incoming
            } else if (referenceCandidate == null ||
                recoveryCandidateComparator.compare(candidate, referenceCandidate) < 0) {
                referenceCandidate = candidate
            }
        }
        if (!hasSelectableResult) {
            val next = origin.copy(recoveries = listOfNotNull(referenceCandidate), failure = attempt.failure)
            val previous = reference
            if (previous == null || geometryAttemptComparator.compare(next, previous) < 0) {
                reference = next
                referenceProfile = profile
            }
        }
        rebuild(pending.values)
    }

    fun markIncomplete(reason: SearchIncompleteReason) {
        incompleteReasons += reason
    }

    /** Includes normal rejection of unusable geometry and policy-limited empty scopes. */
    fun completePolicy(hasExcludedInputs: Boolean = false) {
        completedPolicies++
        excludedInputs = excludedInputs || hasExcludedInputs
    }

    fun result(finders: List<FinderCandidate> = emptyList()): AutoRecoveryResult {
        val ranked = entriesByIdentity.values.sortedWith(recoveryResultComparator)
        val origins = ranked.groupBy { it.attempt }.mapValues { (origin, entries) ->
            origin.copy(recoveries = entries.map { it.candidate })
        }
        val entries = ranked.map { it.copy(attempt = origins.getValue(it.attempt)) }
        val attempts = (origins.values + listOfNotNull(reference)).sortedWith(geometryAttemptComparator)
        val known = summary ?: if (attemptedGeometries > 0 || incompleteReasons.isNotEmpty() || completedPolicies > 0)
            RecoverySearchDiagnostic(coverageComplete = !sawMissingDiagnostic,
                solverCompleteness = if (completedPolicies > 0) SearchCompleteness.COMPLETE else SearchCompleteness.UNKNOWN)
            else null
        val diagnostic = known?.copy(
            // Execution completion does not establish a contradiction for policy-excluded inputs.
            inputBlocker = known.inputBlocker.takeUnless { excludedInputs },
            coverageComplete = known.coverageComplete && !sawMissingDiagnostic,
            unsearched = known.unsearched || incompleteReasons.isNotEmpty(),
            incompleteReasons = known.incompleteReasons + incompleteReasons,
            candidatesOmitted = known.candidatesOmitted || candidatesOmitted,
            derivationsOmitted = known.derivationsOmitted || derivationsOmitted,
        )
        return AutoRecoveryResult(finders, attempts, entries.firstOrNull()?.profile ?: referenceProfile,
            diagnostic, entries, attemptedGeometries, testedHypotheses.toList())
    }

    private fun merge(existing: RecoveryResultEntry, incoming: RecoveryResultEntry): RecoveryResultEntry {
        val incomingWins = recoveryResultComparator.compare(incoming, existing) < 0
        val representative = if (incomingWins) incoming else existing
        val other = if (incomingWins) existing else incoming
        val route = RecoveryResultDerivation(other.attempt.hypothesis,
            other.attempt.samplingStats, other.profile, other.candidate)
        val alternatives = (if (incomingWins) listOf(route) + existing.alternativeDerivations
            else existing.alternativeDerivations + route).sortedWith { first, second ->
            recoveryCandidateComparator.compare(first.candidate, second.candidate).let { comparison ->
                if (comparison != 0) comparison else compareValues(first.hypothesis.quickScore, second.hypothesis.quickScore)
            }
        }
        val limit = minOf(4, policy.maxAlternativeDerivations)
        val omitted = alternatives.size > limit
        if (omitted) derivationsOmitted = true
        return representative.copy(alternativeDerivations = alternatives.take(limit),
            derivationsOmitted = existing.derivationsOmitted || omitted)
    }

    private fun rebuild(pending: Collection<RecoveryResultEntry>) {
        // At most one bounded invocation coexists with the previous retained set. Re-accounting
        // in rank order allows later, better results to displace early results at the byte limit.
        val budget = policy.fresh()
        val matrices = IdentityHashMap<QrMatrix, Boolean>()
        var matrixBytes = 0L
        fun retain(candidate: RecoveryCandidate?, matrix: QrMatrix?): Boolean {
            val additionalMatrix = if (matrix == null || matrices.containsKey(matrix)) 0L else
                64L + matrix.dimension.toLong() * matrix.dimension * 48L
            if (additionalMatrix > policy.limitBytes - matrixBytes - budget.retainedBytes) return false
            if (candidate != null && !budget.tryRetain(candidate,
                    maxRetainedBytes = policy.limitBytes - matrixBytes - additionalMatrix)) return false
            if (matrix != null) matrices[matrix] = true
            matrixBytes += additionalMatrix
            return true
        }
        val retained = linkedMapOf<RecoveryIdentity, RecoveryResultEntry>()
        val localAlternatives = mutableListOf<Pair<RecoveryIdentity, List<RecoveryCandidate>>>()
        val alternatives = mutableListOf<Pair<RecoveryIdentity, List<RecoveryResultDerivation>>>()
        for (entry in pending.sortedWith(recoveryResultComparator)) {
            val primary = if (entry.candidate.alternativeDerivations.isEmpty()) entry.candidate
                else entry.candidate.copy(alternativeDerivations = emptyList())
            if (retained.size >= policy.maxCandidates || !retain(primary, entry.attempt.matrix)) {
                candidatesOmitted = true
                continue
            }
            val identity = entry.candidate.identity()
            retained[identity] = entry.copy(candidate = primary, alternativeDerivations = emptyList())
            localAlternatives += identity to entry.candidate.alternativeDerivations
            alternatives += identity to entry.alternativeDerivations
        }
        // Optional duplicate explanations cannot evict a distinct selectable result.
        for ((identity, routes) in localAlternatives) {
            if (routes.isEmpty()) continue
            val kept = routes.filter { retain(it, null) }
            val entry = retained.getValue(identity)
            val omitted = kept.size != routes.size
            if (omitted) derivationsOmitted = true
            retained[identity] = entry.copy(candidate = entry.candidate.copy(alternativeDerivations = kept,
                derivationsOmitted = entry.candidate.derivationsOmitted || omitted))
        }
        for ((identity, routes) in alternatives) {
            val kept = routes.filter { retain(it.candidate, null) }
            val entry = retained.getValue(identity)
            val omitted = kept.size != routes.size
            if (omitted) derivationsOmitted = true
            retained[identity] = entry.copy(alternativeDerivations = kept,
                derivationsOmitted = entry.derivationsOmitted || omitted)
        }
        reference?.let { referenceAttempt ->
            val failure = referenceAttempt.failure
            val diagnosticBytes = 1024L + (failure?.rsBitDiagnostic?.independentCells?.size ?: 0) * 32L +
                (failure?.manualConflicts?.size ?: 0) * 64L +
                (failure?.conventionalRsDiagnostic?.correctedInterleaved?.size ?: 0) * 4L +
                (failure?.conventionalRsDiagnostic?.blocks?.size ?: 0) * 128L
            if (diagnosticBytes > policy.limitBytes - matrixBytes - budget.retainedBytes ||
                !retain(referenceAttempt.recoveries.firstOrNull(), referenceAttempt.matrix) ||
                diagnosticBytes > policy.limitBytes - matrixBytes - budget.retainedBytes) {
            reference = null
            referenceProfile = null
            derivationsOmitted = true
            }
        }
        entriesByIdentity = retained
    }
}
