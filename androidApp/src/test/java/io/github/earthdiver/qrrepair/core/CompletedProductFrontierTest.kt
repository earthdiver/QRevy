package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class CompletedProductFrontierTest {
    private data class Value(val ordinal: Int, val rank: Int)
    private fun groups(sizes: IntArray) = sizes.map { size -> List(size) { Value(it, (size - it) * 3) } }

    @Test fun appendedCandidatesMatchFilteredFullTraversalOrder() {
        val frontier = CompletedProductFrontier(3)
        val old = intArrayOf(2, 1, 2)
        val revisions = LongArray(3)
        frontier.complete(old, revisions)
        for (sizes in listOf(intArrayOf(3, 1, 2), intArrayOf(2, 3, 2), intArrayOf(3, 2, 3))) {
            val input = groups(sizes)
            val relevant = frontier.relevance<Value>(sizes, revisions) { block, value, count ->
                assertEquals(old[block], count)
                value.ordinal >= count
            }
            val expected = bestFirstCartesianProductSequence(input, { it.rank }).toList()
                .filter { product -> product.indices.any { product[it].ordinal >= old[it] } }
            val actual = bestFirstCartesianProductSequence(input, { it.rank }, isRelevantPrefix = relevant).toList()
            assertEquals(expected, actual)
            assertEquals(actual.size, actual.toSet().size)
        }
    }

    @Test fun incompletePassAndChangedRoutesReplayAllProducts() {
        val frontier = CompletedProductFrontier(2)
        val sizes = intArrayOf(3, 2)
        val revisions = longArrayOf(0, 0)
        val input = groups(sizes)
        fun products() = bestFirstCartesianProductSequence(input, { it.rank },
            isRelevantPrefix = frontier.relevance<Value>(sizes, revisions) { _, value, count -> value.ordinal >= count }).toList()
        val expected = bestFirstCartesianProductSequence(input, { it.rank }).toList()
        assertEquals(expected, products())
        var limited = false
        bestFirstCartesianProductSequence(input, { it.rank }, maxExpandedNodes = 1,
            onLimitReached = { limited = true }).toList()
        assertTrue(limited)
        // An interrupted/limited traversal never calls complete.
        assertEquals(expected, products())
        frontier.complete(sizes, revisions)
        assertTrue(products().isEmpty())
        revisions[0]++
        assertEquals(expected, products())
    }

    @Test fun skippedOldPrefixesDoNotConsumeTheNewProductNodeBudget() {
        val frontier = CompletedProductFrontier(2)
        frontier.complete(intArrayOf(100, 1), longArrayOf(0, 0))
        val input = listOf(List(101) { Value(it, it) }, listOf(Value(0, 0)))
        var limited = false
        val actual = bestFirstCartesianProductSequence(input, { it.rank }, maxExpandedNodes = 2,
            onLimitReached = { limited = true },
            isRelevantPrefix = frontier.relevance<Value>(intArrayOf(101, 1), longArrayOf(0, 0)) {
                    _, value, count -> value.ordinal >= count
            }).toList()
        assertEquals(listOf(listOf(Value(100, 100), Value(0, 0))), actual)
        assertFalse(limited)
    }
}
