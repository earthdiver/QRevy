package io.github.earthdiver.qrrepair

/** Initial and current stage selection for the main recovery-data dialog. */
internal data class RecoveryDataDisplayState(
    var repairedStage: Boolean = true,
    var unmasked: Boolean = true,
    var partialInformation: Boolean = false,
)
