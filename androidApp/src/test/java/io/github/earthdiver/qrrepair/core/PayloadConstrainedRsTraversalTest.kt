package io.github.earthdiver.qrrepair.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PayloadConstrainedRsTraversalTest {
    private val rs = ReedSolomonErasures()
    private fun bytes(bits: String): IntArray = bits.padEnd((bits.length + 7) / 8 * 8, '0').chunked(8).map { it.toInt(2) }.toIntArray()

    @Test fun jointHeaderAndValuePruningMatchesExhaustiveQrValidationAcrossBlockBoundaries() {
        val streams = listOf(
            "0001" + "0000000011" + "1111100111" + "0000", // Numeric 999
            "0010" + "000000010" + "11111101000" + "0000", // Alphanumeric 2024
            "0100" + "00000001" + "11111111" + "0000", // Binary byte
            "0100" + "00000001" + "10000000" + "0001" + "0000000001" + "1001" + "0000",
        )
        val random = Random(971)
        for (stream in streams) repeat(6) {
            val data = bytes(stream)
            assertTrue(StrictQrPayloadValidator.isValid(data.map { it.toByte() }.toByteArray(), 1))
            val masks = IntArray(data.size)
            (0 until stream.length).shuffled(random).take(10).forEach { position ->
                masks[position / 8] = masks[position / 8] or (1 shl (7 - position % 8))
            }
            // The count crosses the block boundary; RS parity couples header and body bits.
            val sources = listOf(0 until 1, 1 until data.size).map { indices ->
                val values = indices.map { data[it] }.toIntArray()
                val words = rs.encode(values, 4)
                val unknown = indices.map { masks[it] }.toIntArray() + IntArray(4) { 255 }
                // A known parity bit makes body/header variables correlated through RS.
                unknown[values.size] = 254
                val block = RsBlockWork(values.size, words, unknown)
                RsCandidateSource(block, emptyList(), requireNotNull(RsBitConstraintSolver(rs).analyze(block, 4)), 12)
            }
            val all = sources.map { source -> source.space!!.assignments().map { it.take(source.block.numDataCodewords) }.toList() }
            val expected = all[0].flatMap { a -> all[1].map { b -> a + b } }
                .filter { StrictQrPayloadValidator.isValid(it.map { b -> b.toByte() }.toByteArray(), 1) }.toSet()
            val traversal = RsPayloadTraversal(sources, 1, 65_536)
            val found = traversal.combinations { prefix ->
                val prefixBytes = prefix.flatMapIndexed { i, result -> result.corrected.take(sources[i].block.numDataCodewords) }
                StrictQrPayloadValidator.canBePrefix(prefixBytes.map { it.toByte() }.toByteArray(), 1)
            }.map { combination -> combination.flatMapIndexed { i, result -> result.corrected.take(sources[i].block.numDataCodewords) } }
                .filter { StrictQrPayloadValidator.isValid(it.map { b -> b.toByte() }.toByteArray(), 1) }.toList()
            assertFalse(traversal.limited)
            assertEquals(expected, found.toSet())
            assertEquals(found.size, found.toSet().size)
        }
    }
}
