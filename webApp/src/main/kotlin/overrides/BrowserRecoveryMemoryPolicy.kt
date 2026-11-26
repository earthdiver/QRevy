package io.github.earthdiver.qrrepair.core

/** Divide available Java heap between published results, a current trial, and solver work. */
internal data class RecoveryMemoryPolicy(
    val retainedBytes: Long,
    val workingBytes: Long,
    val historyBytes: Long,
) {
    companion object {
        private const val MIB = 1024L * 1024

        fun forHeap(maxBytes: Long, usedBytes: Long): RecoveryMemoryPolicy {
            require(maxBytes > 0 && usedBytes >= 0)
            // Leave additional room for inspectors, temporary copies and runtime allocations.
            val reserve = minOf(maxBytes / 8, 32 * MIB)
            val available = (maxBytes - usedBytes.coerceAtMost(maxBytes) - reserve).coerceAtLeast(0)
            return RecoveryMemoryPolicy(
                retainedBytes = minOf(128 * MIB, available / 4),
                workingBytes = minOf(192 * MIB, available / 2),
                historyBytes = (maxBytes / 8).coerceIn(32 * MIB, 64 * MIB),
            )
        }

        // Web adaptation: Safari does not expose Java heap statistics. Fixed conservative budgets
        // preserve explicit solver accounting without claiming a measurement of browser memory.
        fun current(): RecoveryMemoryPolicy = RecoveryMemoryPolicy(32 * MIB, 32 * MIB, 32 * MIB)
    }
}
