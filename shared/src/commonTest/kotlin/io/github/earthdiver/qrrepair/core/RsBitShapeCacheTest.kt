package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import kotlin.test.*

class RsBitShapeCacheTest {
    @Test fun shapeReuseRecomputesRightHandSideAndInconsistency() {
        val rs = ReedSolomonErasures()
        val cached = RsBitConstraintSolver(rs, 128 * 1024)
        val fresh = RsBitConstraintSolver(rs)
        val random = Random(64128)
        val held = mutableListOf<Pair<RsBitConstraintSolver.Space, List<List<Int>>>>()
        repeat(24) { iteration ->
            val word = rs.encode(IntArray(5) { random.nextInt(256) }, 4)
            val masks = IntArray(9) { if (it < 4) 255 else if (it == 4) 1 else 0 }
            if (iteration % 3 == 0) word[8] = word[8] xor 16
            val block = RsBlockWork(5, word, masks)
            val a = cached.analyze(block, 4)
            val b = fresh.analyze(block, 4)
            assertEquals(b?.rank, a?.rank)
            assertEquals(b?.dimension, a?.dimension)
            assertEquals(b?.assignments(8)?.map { it.toList() }?.toList(), a?.assignments(8)?.map { it.toList() }?.toList())
            assertEquals(b?.fixedBits, a?.fixedBits)
            if (a != null) held += a to a.assignments(8).map { it.toList() }.toList()
            assertTrue(cached.cacheWorkingBytes in 1..(128 * 1024))
        }
        // Retained spaces must not acquire a later call's right-hand side.
        for ((space, expected) in held) assertEquals(expected, space.assignments(8).map { it.toList() }.toList())
        val masks = IntArray(9)
        val bad = rs.encode(intArrayOf(1, 2, 3, 4, 5), 4).also { it[8] = it[8] xor 1 }
        assertNull(cached.analyze(RsBlockWork(5, bad, masks), 4))
        val good = rs.encode(intArrayOf(6, 7, 8, 9, 10), 4)
        assertContentEquals(good, assertNotNull(cached.analyze(RsBlockWork(5, good, masks), 4)).assignments().single())
        assertNull(cached.analyze(RsBlockWork(5, bad, masks), 4))
    }

    @Test fun capacityEvictionAndDisabledCachePreserveSolutions() {
        val rs = ReedSolomonErasures()
        val cached = RsBitConstraintSolver(rs, 8 * 1024)
        val fresh = RsBitConstraintSolver(rs)
        repeat(20) { index ->
            val word = rs.encode(intArrayOf(index, 2, 3, 4, 5), 4)
            val masks = IntArray(9) { if (it == index % 9) 255 else 0 }
            val block = RsBlockWork(5, word, masks)
            assertEquals(fresh.analyze(block, 4)?.assignments()?.map { it.toList() }?.toList(),
                cached.analyze(block, 4)?.assignments()?.map { it.toList() }?.toList())
            assertTrue(cached.cacheWorkingBytes <= 8 * 1024)
            assertEquals(0L, fresh.cacheWorkingBytes)
        }
    }
}
