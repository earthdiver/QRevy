package io.github.earthdiver.qrrepair.core

/** Remembers only one count/revision per block, never a set of visited candidate products. */
internal class CompletedProductFrontier(blocks: Int) {
    private val counts = IntArray(blocks)
    private val revisions = LongArray(blocks)
    private var completed = false

    fun <T> relevance(currentCounts: IntArray, currentRevisions: LongArray,
        admittedAfter: (Int, T, Int) -> Boolean): (List<T>) -> Boolean {
        require(currentCounts.size == counts.size && currentRevisions.size == counts.size)
        val replay = !completed || counts.indices.any {
            currentRevisions[it] != revisions[it] || currentCounts[it] < counts[it]
        }
        val newSuffix = BooleanArray(counts.size + 1)
        for (i in counts.indices.reversed()) newSuffix[i] = newSuffix[i + 1] || currentCounts[i] > counts[i]
        return { prefix ->
            replay || newSuffix[prefix.size] || prefix.indices.any { admittedAfter(it, prefix[it], counts[it]) }
        }
    }

    /** Call only after exhausting a finite traversal without a node/combination/memory stop. */
    fun complete(currentCounts: IntArray, currentRevisions: LongArray) {
        require(currentCounts.size == counts.size && currentRevisions.size == counts.size)
        currentCounts.copyInto(counts)
        currentRevisions.copyInto(revisions)
        completed = true
    }
}
