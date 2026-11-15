package io.github.earthdiver.qrrepair.core

/** Per-invocation work selection; never changes the user's saved search settings. */
enum class RecoveryExecutionPolicy {
    DETAILED,
    AUTOMATIC_EXACT,
    AUTOMATIC_BASIC,
    AUTOMATIC_EXTENDED;

    /** Independent of tentative partial-byte assignments and list-decoder parameters. */
    internal fun exactSpaceBitLimit(partialBitLimit: Int): Int = when (this) {
        DETAILED -> partialBitLimit
        AUTOMATIC_EXACT -> 0
        AUTOMATIC_BASIC, AUTOMATIC_EXTENDED -> 8
    }
}
