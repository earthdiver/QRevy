package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Novelty is measured against the retained set before admission, regardless of retention limits. */
internal enum class RecordedRecoveryCandidate(val parsed: Boolean) {
    NO_PAYLOAD(false),
    PREVIOUSLY_PARSED(true),
    NEWLY_PARSED(true),
}

/** Mutable search evidence belongs to exactly one invocation, including its fallback trials. */
internal class RecoveryRunState(
    val diagnosticInput: QrMatrix,
    val retentionBudget: RecoveryRetentionBudget,
    baseCancellation: RecoveryCancellation,
    val partialBitSearchLimit: Int,
    val listDecodingBudget: ListDecodingBudget,
) {
    var listDecodingPhase = false
    var listDecodingSummary: ListDecodingSummary? = null
    val cancellation = RecoveryCancellation {
        baseCancellation.check()
        if (listDecodingPhase) listDecodingBudget.check()
        false
    }
    var ignoredFormatCoords: Set<Coord> = emptySet()
    val bitDiagnostics = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, RsBitDiagnostic>()
    val conventionalDiagnostics = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, ConventionalRsDiagnostic>()
    var failure: RecoveryFailure? = null
        private set
    var formatAttempts: Int = 0
    var combinationSearch = CombinationSearchSummary()
    var expectedFormats: Set<Pair<ErrorCorrectionLevel, Int>> = emptySet()
    val inputBlockers = mutableMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryInputBlocker?>()
    var earlyBlocker: RecoveryInputBlocker? = null
    val candidateRejections = linkedSetOf<RecoveryCandidateRejection>()
    val singleSegmentStatuses = linkedSetOf<SingleSegmentStatus>()
    val priorityHeaderHypotheses = linkedSetOf<String>()
    var acceptedCandidate = false
        private set
    // Track validation before retention can omit candidates or their alternative routes.
    var parsedWithoutBitConstraints = false
    var unresolvedCalculations = false
    var solverCompleteness = SearchCompleteness.COMPLETE
    var finished = false
    var completedSteps = 0
    val incompleteReasons = linkedSetOf<SearchIncompleteReason>()
    var candidateCalculations = 0
    val candidates = BoundedRecoveryCandidates(retentionBudget)

    /** Record validation before bounded retention; search routes decide when a parsed result ends a pass. */
    fun recordEvaluation(evaluation: RecoveryCandidateEvaluation): RecordedRecoveryCandidate = when (evaluation) {
        is RecoveryCandidateEvaluation.Accepted -> {
            if (evaluation.decoderFailed) incompleteReasons += SearchIncompleteReason.INTERNAL_ERROR
            acceptedCandidate = true
            val alreadyParsed = candidates.containsParsed(evaluation.candidate)
            candidates.add(evaluation.candidate)
            when {
                !evaluation.candidate.payloadParsed -> RecordedRecoveryCandidate.NO_PAYLOAD
                alreadyParsed -> RecordedRecoveryCandidate.PREVIOUSLY_PARSED
                else -> RecordedRecoveryCandidate.NEWLY_PARSED
            }
        }
        is RecoveryCandidateEvaluation.Rejected -> {
            candidateRejections += evaluation.reason
            evaluation.failure?.let(::considerFailure)
            RecordedRecoveryCandidate.NO_PAYLOAD
        }
        RecoveryCandidateEvaluation.UnresolvedPriorityAssignment -> {
            unresolvedCalculations = true
            RecordedRecoveryCandidate.NO_PAYLOAD
        }
    }

    fun considerFailure(candidate: RecoveryFailure) {
        val current = failure
        if (current == null || failureComparator.compare(candidate, current) <= 0) failure = candidate
    }

    private companion object {
        private val failureComparator = compareBy<RecoveryFailure> { it.formatMismatches }
            .thenByDescending { it.formatMatches }
            .thenBy { it.rsShortfallBits ?: Int.MAX_VALUE }
            .thenByDescending { it.stage.ordinal }
            // Do not replace an actionable rejection with the generic end-of-format diagnostic.
            .thenBy { if (it.reason == null) 1 else 0 }
            .thenBy { it.knownBitMismatches ?: Int.MAX_VALUE }
            .thenBy { it.erasedCodewords }
            .thenBy { it.partialGuessedBits }
    }

    fun searchDiagnostic(): RecoverySearchDiagnostic {
        val allInputsChecked = expectedFormats.isNotEmpty() && expectedFormats.all(inputBlockers::containsKey)
        val blockers = expectedFormats.map { inputBlockers[it] }.toSet()
        return RecoverySearchDiagnostic(
            inputBlocker = earlyBlocker ?: blockers.singleOrNull()?.takeIf { allInputsChecked },
            candidateRejections = candidateRejections.toSet(),
            listDecoding = listDecodingSummary,
            singleSegmentStatuses = singleSegmentStatuses.toSet(),
            priorityHeaderHypotheses = priorityHeaderHypotheses.toSet(),
            acceptedCandidate = acceptedCandidate,
            unresolvedCalculations = unresolvedCalculations,
            unsearched = incompleteReasons.isNotEmpty() || combinationSearch.unsearched,
            incompleteReasons = incompleteReasons + combinationSearch.incompleteReasons,
            completedSteps = completedSteps,
            candidateCalculations = candidateCalculations,
            coverageComplete = finished || incompleteReasons.isNotEmpty() || combinationSearch.unsearched,
            solverCompleteness = solverCompleteness,
            candidatesOmitted = candidates.candidatesOmitted,
            derivationsOmitted = candidates.derivationsOmitted,
        )
    }
}
