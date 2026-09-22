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
        onProgress: (RecoveryProgress) -> Unit = {},
    ): AutoRecoveryResult {
        val variants = settings.variants()
        val accumulator = RecoveryResultAccumulator(retentionBudget)
        var finders = emptyList<FinderCandidate>()
        for ((index, profile) in variants.withIndex()) {
            finders = AutoRecoveryEngine(engine).run(
                image, profile = profile, retentionBudget = retentionBudget, resultAccumulator = accumulator,
                cancellation = cancellation,
                onProgress = { done, total, message ->
                    onProgress(RecoveryProgress(profile, index, variants.size, done, total, message))
                },
            ).finders
        }
        return accumulator.result(finders)
    }
}
