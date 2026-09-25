package io.github.earthdiver.qrrepair.core

data class RecoveryProgress(
    val profile: SamplingProfile,
    val variantIndex: Int,
    val variantCount: Int,
    val done: Int,
    val total: Int,
    val message: String,
)

/** Runs the requested sampling profiles with one retention budget and one result accumulator. */
internal class RecoveryWorkflow(private val engine: RecoveryEngine) {
    fun run(
        image: LumaImage,
        settings: PreprocessSettings,
        retentionBudget: RecoveryRetentionBudget,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        stopRequested: () -> Boolean = { false },
        onPriorityProgress: (Int, Int) -> Unit = { _, _ -> },
        onProgress: (RecoveryProgress) -> Unit = {},
    ): AutoRecoveryResult {
        require(settings.preRecoveryPrioritySearch.size <= settings.prioritySearchLimit) { "Priority marks exceed the configured limit" }
        val variants = settings.variants()
        val accumulator = RecoveryResultAccumulator(retentionBudget)
        var finders = emptyList<FinderCandidate>()
        for ((index, profile) in variants.withIndex()) {
            cancellation.check()
            if (stopRequested()) break
            finders = AutoRecoveryEngine(engine).run(
                image, profile = profile, retentionBudget = retentionBudget, resultAccumulator = accumulator,
                cancellation = cancellation,
                stopRequested = stopRequested,
                onPriorityProgress = onPriorityProgress,
                onProgress = { done, total, message ->
                    onProgress(RecoveryProgress(profile, index, variants.size, done, total, message))
                },
            ).finders
        }
        val interrupted = stopRequested()
        if (interrupted) accumulator.markIncomplete(hasUnsearched = true)
        return accumulator.result(finders).copy(interrupted = interrupted)
    }
}
