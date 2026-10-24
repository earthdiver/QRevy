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
        listDecodingBudget: ListDecodingBudget = ListDecodingBudget(),
        discoveryCounter: RecoveryDiscoveryCounter = RecoveryDiscoveryCounter(),
        onProgress: (RecoveryProgress) -> Unit = {},
    ): AutoRecoveryResult {
        require(settings.preRecoveryPrioritySearch.size <= settings.prioritySearchLimit) { "Priority marks exceed the configured limit" }
        val variants = settings.variants()
        val accumulator = RecoveryResultAccumulator(retentionBudget)
        var finders = emptyList<FinderCandidate>()
        for ((index, profile) in variants.withIndex()) {
            cancellation.check()
            if (stopRequested()) {
                accumulator.markIncomplete(SearchIncompleteReason.USER_INTERRUPTED)
                break
            }
            try {
                finders = AutoRecoveryEngine(engine).run(
                    image, profile = profile, retentionBudget = retentionBudget, resultAccumulator = accumulator,
                    cancellation = cancellation,
                    stopRequested = stopRequested,
                    onPriorityProgress = onPriorityProgress,
                    listDecodingBudget = listDecodingBudget,
                    discoveryCounter = discoveryCounter,
                    onProgress = { done, total, message ->
                        onProgress(RecoveryProgress(profile, index, variants.size, done, total, message))
                    },
                ).finders
            } catch (error: RecoveryCancelledException) {
                throw error
            } catch (_: Exception) {
                accumulator.markIncomplete(SearchIncompleteReason.INTERNAL_ERROR)
            }
        }
        val result = accumulator.result(finders)
        return result.copy(discoveredCandidates = discoveryCounter.snapshot, interrupted = SearchIncompleteReason.USER_INTERRUPTED in
            result.searchDiagnostic?.incompleteReasons.orEmpty())
    }
}
