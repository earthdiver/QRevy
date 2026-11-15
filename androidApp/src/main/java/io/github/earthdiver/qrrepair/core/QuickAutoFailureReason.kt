package io.github.earthdiver.qrrepair.core

/** Presentation only: never changes candidate acceptance or solver completeness. */
enum class QuickAutoFailureReason {
    TIME_LIMIT, NO_INITIAL_GRID, MULTIPLE_CANDIDATES, SEARCH_LIMIT, UNRESOLVED, NO_CANDIDATE, INTERRUPTED, INTERNAL_ERROR;

    companion object {
        internal fun classify(recovery: AutoRecoveryResult, hasGrid: Boolean, stop: String?,
            timeLimited: Boolean = false, internalError: Boolean = false): QuickAutoFailureReason {
            val diagnostic = recovery.searchDiagnostic
            val reasons = diagnostic?.incompleteReasons.orEmpty()
            return when {
                stop == "STOP_REQUESTED" || SearchIncompleteReason.USER_INTERRUPTED in reasons -> INTERRUPTED
                internalError || SearchIncompleteReason.INTERNAL_ERROR in reasons -> INTERNAL_ERROR
                stop == "TOTAL_TIME_LIMIT" || timeLimited || reasons.any { it in setOf(
                    SearchIncompleteReason.TIME_LIMIT, SearchIncompleteReason.LIST_DECODING_TIME_LIMIT,
                    SearchIncompleteReason.KNOWN_BIT_SEARCH_TIME_LIMIT) } -> TIME_LIMIT
                !hasGrid -> NO_INITIAL_GRID
                SearchIncompleteReason.WORKING_MEMORY_LIMIT in reasons ||
                    SearchIncompleteReason.KNOWN_BIT_SEARCH_LIMIT in reasons || diagnostic?.candidatesOmitted == true -> SEARCH_LIMIT
                (recovery.discoveredCandidates?.value ?: 0) > 1 -> MULTIPLE_CANDIDATES
                diagnostic?.unresolvedCalculations == true || diagnostic?.unsearched == true ||
                    (recovery.discoveredCandidates?.value ?: 0) == 1 -> UNRESOLVED
                else -> NO_CANDIDATE
            }
        }
    }
}
