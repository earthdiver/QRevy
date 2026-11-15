package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Final evidence under the requested search policy, not uniqueness over every possible QR. */
enum class RecoveryFormatState { CANDIDATE, EXCLUDED, UNRESOLVED }

data class RecoveryFormatOutcome(
    val ecLevel: ErrorCorrectionLevel,
    val dataMask: Int,
    val state: RecoveryFormatState,
    val hasCandidate: Boolean = false,
    val reason: String,
)

/** One format pass must finish all its input variants before superseding an earlier failure. */
internal class RecoveryFormatTrial(
    val key: Pair<ErrorCorrectionLevel, Int>,
    val assigned: Boolean,
) {
    var candidate = false
    var completed = false
    var exclusion: String? = null
    var unresolved: String? = null

    fun exclude(reason: String) {
        exclusion = reason
        completed = true
    }
}

internal class RecoveryFormatProgress {
    private val outcomes = linkedMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryFormatOutcome>()
    private val priorityOutcomes = linkedMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryFormatOutcome>()
    private var priorityFormats: Set<Pair<ErrorCorrectionLevel, Int>>? = null
    private val assignmentOutcomes = linkedMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryFormatOutcome>()
    private val passOutcomes = linkedMapOf<Pair<ErrorCorrectionLevel, Int>, RecoveryFormatOutcome>()

    /** Every pass enumerates the same complete 0/1 space; retain only per-format aggregates. */
    fun beginPriorityPass(expected: Set<Pair<ErrorCorrectionLevel, Int>>) {
        check(priorityFormats == null)
        priorityFormats = expected.toSet()
        passOutcomes.clear()
        assignmentOutcomes.clear()
    }

    fun finishPriorityAssignment() {
        for (key in checkNotNull(priorityFormats)) {
            val current = assignmentOutcomes[key] ?: missing(key)
            val previous = passOutcomes[key]
            val candidate = current.hasCandidate || previous?.hasCandidate == true
            val unresolved = current.state == RecoveryFormatState.UNRESOLVED || previous?.state == RecoveryFormatState.UNRESOLVED
            passOutcomes[key] = priorityOutcome(key, candidate, unresolved)
        }
        assignmentOutcomes.clear()
    }

    /** Partial passes never replace earlier uncertainty with a successful subset of assignments. */
    fun finishPriorityPass(completed: Boolean) {
        for (key in checkNotNull(priorityFormats)) {
            val current = passOutcomes[key] ?: missing(key)
            val candidate = current.hasCandidate || assignmentOutcomes[key]?.hasCandidate == true ||
                priorityOutcomes[key]?.hasCandidate == true
            priorityOutcomes[key] = priorityOutcome(key, candidate,
                !completed || current.state == RecoveryFormatState.UNRESOLVED)
        }
        priorityFormats = null
        passOutcomes.clear()
        assignmentOutcomes.clear()
    }

    fun finish(trial: RecoveryFormatTrial) {
        val target = if (trial.assigned) {
            check(priorityFormats != null)
            assignmentOutcomes
        } else outcomes
        val previous = target[trial.key]
        val pending = trial.unresolved ?: if (!trial.completed) "INTERRUPTED" else null
        val hasCandidate = trial.candidate || previous?.hasCandidate == true
        val (state, reason) = when {
            pending != null -> RecoveryFormatState.UNRESOLVED to pending
            trial.candidate -> RecoveryFormatState.CANDIDATE to "VALIDATED_CANDIDATE"
            trial.exclusion != null && !hasCandidate -> RecoveryFormatState.EXCLUDED to trial.exclusion!!
            previous != null -> previous.state to previous.reason
            else -> RecoveryFormatState.UNRESOLVED to "NO_CONCLUSIVE_RESULT"
        }
        target[trial.key] = RecoveryFormatOutcome(trial.key.first, trial.key.second, state, hasCandidate, reason)
    }

    fun snapshot(expected: Set<Pair<ErrorCorrectionLevel, Int>>): List<RecoveryFormatOutcome> =
        expected.map { key ->
            val ordinary = outcomes[key]
            val priority = priorityOutcomes[key]
            val candidate = ordinary?.hasCandidate == true || priority?.hasCandidate == true ||
                passOutcomes[key]?.hasCandidate == true || assignmentOutcomes[key]?.hasCandidate == true
            when {
                key in priorityFormats.orEmpty() -> priorityOutcome(key, candidate, unresolved = true)
                priority != null -> priorityOutcome(key, candidate, priority.state == RecoveryFormatState.UNRESOLVED)
                else -> ordinary ?: missing(key)
            }
        }

    private fun missing(key: Pair<ErrorCorrectionLevel, Int>) = RecoveryFormatOutcome(key.first, key.second,
        RecoveryFormatState.UNRESOLVED, reason = "NOT_ATTEMPTED")

    private fun priorityOutcome(key: Pair<ErrorCorrectionLevel, Int>, candidate: Boolean, unresolved: Boolean): RecoveryFormatOutcome {
        val (state, reason) = when {
            unresolved -> RecoveryFormatState.UNRESOLVED to "PRIORITY_ASSIGNMENT_UNRESOLVED"
            candidate -> RecoveryFormatState.CANDIDATE to "VALIDATED_CANDIDATE"
            else -> RecoveryFormatState.EXCLUDED to "ALL_PRIORITY_ASSIGNMENTS_EXCLUDED"
        }
        return RecoveryFormatOutcome(key.first, key.second, state, candidate, reason)
    }
}
