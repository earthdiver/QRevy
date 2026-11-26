package io.github.earthdiver.qrrepair

/** Initial and current stage selection for the main recovery-data dialog. */
internal data class RecoveryDataDisplayState(
    var repairedStage: Boolean = true,
    var unmasked: Boolean = true,
    var partialInformation: Boolean = false,
) {
    companion object {
        /** Failed or unvalidated results start with diagnostic information from the input. */
        fun forRecovery(hasValidatedCandidate: Boolean) = RecoveryDataDisplayState(
            repairedStage = hasValidatedCandidate,
            unmasked = true,
            partialInformation = !hasValidatedCandidate,
        )
    }
}
