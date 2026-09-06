package io.github.earthdiver.qrrepair.core

/**
 * Request-wide accounting for retained QR candidates, including alternative derivations.
 * This is a conservative accounting unit, not a measurement or cap of the entire Android heap.
 * Block solvers, source images and a currently inspected matrix need additional working memory.
 */
class RecoveryRetentionBudget(val limitBytes: Long = 32L * 1024 * 1024) {
    init { require(limitBytes >= 0) }
    var retainedBytes: Long = 0
        private set
    var exhausted: Boolean = false
        private set

    fun tryRetain(codewords: Int, dataBytes: Int, blocks: Int): Boolean {
        require(codewords >= 0 && dataBytes >= 0 && blocks >= 0)
        // Primitive codewords, data/text, list references and compact per-candidate metadata.
        val cost = 2048L + codewords * 4L + dataBytes * 6L + blocks * 64L
        if (exhausted || cost > limitBytes - retainedBytes) {
            exhausted = true
            return false
        }
        retainedBytes += cost
        return true
    }
}

/** Generate cell explanations only when inspected; never cache a full list per candidate. */
internal class DeferredRepairInfo(
    override val size: Int,
    private val describe: (Int) -> CodewordRepairInfo,
) : AbstractList<CodewordRepairInfo>() {
    override fun get(index: Int): CodewordRepairInfo {
        if (index !in indices) throw IndexOutOfBoundsException("Repair info index: $index")
        return describe(index)
    }
}
