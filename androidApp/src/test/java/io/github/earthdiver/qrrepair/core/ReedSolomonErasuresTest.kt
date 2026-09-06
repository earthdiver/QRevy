package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class ReedSolomonErasuresTest {
    @Test
    fun correctsMixedErrorsAndErasuresWithinBound() {
        val rs = ReedSolomonErasures()
        val random = Random(42)
        repeat(100) {
            val data = IntArray(22) { random.nextInt(256) }
            val encoded = rs.encode(data, 22)
            val damaged = encoded.copyOf()
            val positions = encoded.indices.shuffled(random).take(14)
            val erasures = positions.take(8).toIntArray()
            positions.forEach { pos ->
                var v = random.nextInt(256)
                if (v == damaged[pos]) v = (v + 1) and 0xFF
                damaged[pos] = v
            }
            val corrected = rs.decode(damaged, 22, erasures).corrected
            assertArrayEquals(encoded, corrected)
        }
    }
}
