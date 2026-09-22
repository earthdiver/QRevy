package io.github.earthdiver.qrrepair.core

/** Evidence belongs to one completed invocation, even when the engine is reused or called recursively. */
data class RecoveryOutcome(
    val candidates: List<RecoveryCandidate>,
    val failure: RecoveryFailure?,
    val searchDiagnostic: RecoverySearchDiagnostic,
    val formatAttempts: Int,
)
