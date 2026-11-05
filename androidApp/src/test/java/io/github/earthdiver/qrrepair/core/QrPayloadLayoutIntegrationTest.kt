package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class QrPayloadLayoutIntegrationTest {
    private data class Segment(val mode: Int, val countWidths: List<Int>, val body: Int, val bodyWidth: Int)
    private data class Fixture(val version: Int, val segment: Segment, val headerEnd: Int, val data: ByteArray) {
        val words: IntArray get() = IntArray(data.size) { data[it].toInt() and 255 }
        fun unknownCountBit(): IntArray = IntArray(data.size).also {
            val bit = headerEnd - 1
            it[bit / 8] = 1 shl (7 - bit % 8)
        }
        fun partials(unknown: IntArray = IntArray(data.size)): List<PartialCodeword> =
            words.mapIndexed { index, value -> PartialCodeword(value, unknown[index], emptyList()) }
    }

    // Literal widths and encoded values keep these streams independent of the production layout helper.
    private fun fixtures(): List<Fixture> = listOf(
        Segment(1, listOf(10, 10, 12, 12, 14, 14), 7, 4),
        Segment(2, listOf(9, 9, 11, 11, 13, 13), 10, 6),
        Segment(4, listOf(8, 8, 16, 16, 16, 16), 65, 8),
        Segment(8, listOf(8, 8, 10, 10, 12, 12), 0x73F, 13),
        Segment(13, listOf(8, 8, 10, 10, 12, 12), 0, 13),
    ).flatMap { segment ->
        listOf(1, 9, 10, 26, 27, 40).zip(segment.countWidths).map { (version, width) ->
            val data = PayloadBits().apply {
                put(segment.mode, 4)
                if (segment.mode == 13) put(1, 4)
                put(1, width)
                put(segment.body, segment.bodyWidth)
            }.finish(8)
            Fixture(version, segment, (if (segment.mode == 13) 8 else 4) + width, data)
        }
    }

    @Test fun completeHeadersRequireTheLastCountBitAcrossVersionBoundaries() {
        for (fixture in fixtures()) {
            val (version, segment, _, data) = fixture
            val known = IntArray(data.size)
            assertTrue(completePayloadHeaderAvailable(fixture.words, known, version))
            assertFalse(completePayloadHeaderAvailable(fixture.words, fixture.unknownCountBit(), version))
            assertEquals(segment.mode != 13, trustablePayloadHeaderAvailable(fixture.words, known, version))
            assertEquals(segment.mode != 13,
                priorityHeaderHypothesis(fixture.words, known, fixture.unknownCountBit(), version) != null)
        }
    }

    @Test fun payloadParsingAndDiagnosticsKeepTheDeclaredBodyBoundaries() {
        for (fixture in fixtures()) {
            val (version, segment, headerEnd, data) = fixture
            assertTrue("mode=${segment.mode}, version=$version", StrictQrPayloadValidator.isValid(data, version))
            val trace = StrictQrPayloadValidator.inspectSegments(data, version, IntArray(data.size), data.size * 8).segments.first()
            assertEquals(1, trace.count)
            assertEquals(segment.bodyWidth, trace.requiredBits)
            assertEquals(headerEnd + segment.bodyWidth, trace.endBit)
            assertEquals(1, PartialPayloadAnalyzer.analyze(fixture.partials(), version).count)
            assertNull(PartialPayloadAnalyzer.analyze(fixture.partials(fixture.unknownCountBit()), version).count)
        }
    }

    @Test fun singleSegmentForcingAndSimplePreviewKeepTheirOwnSupportedModes() {
        for (fixture in fixtures()) {
            val (version, segment, _, data) = fixture
            val block = RsBlockWork(data.size, fixture.words, IntArray(data.size))
            val result = PayloadConstraintSolver.apply(DeinterleavedQr(listOf(block), emptyArray(), 0),
                version, trustKnownCount = true)
            assertEquals(segment.mode != 13, result.forcedByTrustedHeader)

            val preview = CodewordHex.interpreted(CodewordExtraction(fixture.partials()), version)
            when (segment.mode) {
                1 -> assertTrue(preview.endsWith("7"))
                2 -> assertTrue(preview.endsWith("A"))
                4 -> assertTrue(preview.endsWith("41"))
                else -> assertTrue(preview.endsWith("（この簡易表示では未対応）") ||
                    preview.endsWith("(not supported by this preview)"))
            }
        }
    }
}
