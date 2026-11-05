package io.github.earthdiver.qrrepair.core

/** A count is either exact or a proven lower bound; neither describes search completeness. */
sealed class RecoveryDiscoveryCount {
    abstract val value: Int
    data class Exact(override val value: Int) : RecoveryDiscoveryCount() {
        init { require(value >= 0) }
    }
    data class AtLeast(override val value: Int) : RecoveryDiscoveryCount() {
        init { require(value >= 0) }
    }
}

/** Worker-confined, request-wide accounting independent of both candidate retention stages. */
class RecoveryDiscoveryCounter(
    private val limitBytes: Long = 1024L * 1024,
    private val onChanged: (RecoveryDiscoveryCount) -> Unit = {},
) {
    init { require(limitBytes >= 0) }
    private val identities = hashSetOf<RecoveryIdentity>()
    private var usedBytes = 0L
    var snapshot: RecoveryDiscoveryCount = RecoveryDiscoveryCount.Exact(0)
        private set

    internal fun record(candidate: RecoveryCandidate) {
        if (snapshot is RecoveryDiscoveryCount.AtLeast || !candidate.isSelectableRecovery) return
        if (candidate.identity() in identities) return
        // Account conservatively for the key, array, hash-table entry and table capacity.
        val bytes = 256L + candidate.correctedInterleaved.size.toLong() * 4
        if (bytes > limitBytes - usedBytes) {
            snapshot = RecoveryDiscoveryCount.AtLeast(snapshot.value + 1)
            identities.clear()
            usedBytes = 0
        } else {
            // Own the key's array: no candidate, matrix, or alternative derivation is retained.
            identities += RecoveryIdentity(candidate.version, candidate.ecLevel.ordinal, candidate.dataMask,
                CodewordIdentity(candidate.correctedInterleaved.copyOf()))
            usedBytes += bytes
            snapshot = RecoveryDiscoveryCount.Exact(snapshot.value + 1)
        }
        onChanged(snapshot)
    }
}
