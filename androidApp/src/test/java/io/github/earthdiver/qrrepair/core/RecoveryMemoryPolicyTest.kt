package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryMemoryPolicyTest {
    private val mib = 1024L * 1024

    @Test fun largerAvailableHeapsPermitMoreRetainedResultsWithoutConsumingSolverReserve() {
        val small = RecoveryMemoryPolicy.forHeap(256 * mib, 80 * mib)
        val large = RecoveryMemoryPolicy.forHeap(1024 * mib, 80 * mib)
        assertTrue(large.retainedBytes > small.retainedBytes)
        assertTrue(large.workingBytes > small.workingBytes)
        assertTrue(small.retainedBytes * 2 + small.workingBytes <= (256 - 80 - 32) * mib)
        assertTrue(large.retainedBytes <= 128 * mib)
        assertTrue(large.workingBytes <= 192 * mib)
    }

    @Test fun nearlyFullHeapCannotStartAnotherLargeAllocationBank() {
        val policy = RecoveryMemoryPolicy.forHeap(256 * mib, 250 * mib)
        assertEquals(0L, policy.retainedBytes)
        assertEquals(0L, policy.workingBytes)
        assertEquals(32 * mib, policy.historyBytes)
    }
}
