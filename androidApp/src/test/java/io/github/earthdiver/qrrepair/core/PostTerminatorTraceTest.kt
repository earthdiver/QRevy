package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.PayloadSegmentDiagnostics.Status
import io.github.earthdiver.qrrepair.core.StrictQrPayloadValidator.Issue
import org.junit.Assert.*
import org.junit.Test

class PostTerminatorTraceTest {
    private fun analyze(data: ByteArray, unknown: IntArray = IntArray(data.size)) =
        PostTerminatorAnalysis.analyze(data, 3, ErrorCorrectionLevel.L, unknown)

    @Test fun unsupportedEciAndLaterContextFailureHaveSeparateOriginalOffsets() {
        val data = PayloadBits().apply {
            byteSegment("A".repeat(43)); put(0, 4)
            put(7, 4); put(51, 8); put(3, 4)
        }.finish(55)
        val report = analyze(data)
        assertEquals(356, report.terminatorBit)
        assertEquals(360, report.boundaries.single().startBit)
        val eci = report.trace[0]
        assertEquals(360, eci.structure.startBit)
        assertEquals(372, eci.structure.endBit)
        assertEquals(51, eci.structure.eci)
        assertEquals(Status.UNSUPPORTED_ECI, eci.status)
        assertEquals(372, report.stop?.bit)
        assertEquals(372, report.remainingStartBit)
        assertEquals(Issue.INVALID_CONTEXT, report.trace[1].structure.issue)
        assertEquals(372, report.trace[1].structure.problemStartBit)
        assertEquals(376, report.trace[1].structure.problemEndBit)
        assertFalse(StrictQrPayloadValidator.isValid(data, 3))
    }

    @Test fun allEciWidthsAreRecordedIncludingControlsWithoutPayload() {
        for ((assignment, width) in listOf(26 to 8, 170 to 16, 90000 to 24)) {
            val data = PayloadBits().apply {
                byteSegment("A"); put(0, 4); put(7, 4)
                put(assignment or when (width) { 16 -> 0x8000; 24 -> 0xC00000; else -> 0 }, width)
            }.finish()
            val report = analyze(data)
            assertNull(report.stop)
            assertTrue(report.groups.isEmpty())
            assertEquals(PostTerminatorAnalysis.Status.ADDITIONAL_SEGMENTS, report.status)
            val entry = report.trace.single()
            assertEquals(assignment, entry.structure.eci)
            assertEquals(24 + 4 + width, entry.structure.endBit)
            assertEquals(if (assignment == 90000) Status.UNSUPPORTED_ECI else Status.PARSED, entry.status)
            if (assignment == 26) assertEquals("UTF-8", entry.charsetName)
        }
    }

    @Test fun unsupportedEciNeverReusesPreviousCharsetAndLaterSupportedEciResumesText() {
        val data = PayloadBits().apply {
            put(7, 4); put(3, 8); byteSegment("A"); put(0, 4)
            put(7, 4); put(51, 8); byteSegment("B")
            put(1, 4); put(3, 10); put(123, 10)
            put(7, 4); put(26, 8); byteSegment("人間")
        }.finish(60)
        val report = analyze(data)
        assertNull(report.stop)
        val entries = report.trace
        assertEquals(Status.UNSUPPORTED_ECI, entries[0].status)
        assertEquals(Status.UNKNOWN_ENCODING, entries[1].status)
        assertNull(entries[1].text)
        assertArrayEquals(byteArrayOf(66), entries[1].bytes)
        assertEquals("123", entries[2].text)
        assertEquals("人間", entries[4].text)
        assertNull(report.groups.single().text)
    }

    @Test fun malformedUtf8KeepsBytesWithoutInsertingReplacementCharacters() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(7, 4); put(26, 8)
            put(4, 4); put(1, 8); put(255, 8); byteSegment("B")
        }.finish()
        val report = analyze(data)
        assertEquals(Status.INVALID_TEXT, report.trace[1].status)
        assertArrayEquals(byteArrayOf(-1), report.trace[1].bytes)
        assertEquals("B", report.trace[2].text)
        assertNull(report.combinedContent?.text)
        assertArrayEquals(byteArrayOf(65, -1, 66), report.combinedContent?.bytes)
    }

    @Test fun invalidNumericValueReportsTheActualFieldAfterTheCount() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); byteSegment("B")
            put(1, 4); put(3, 10); put(1023, 10)
        }.finish()
        val report = analyze(data)
        assertEquals("B", report.groups.single().text)
        assertEquals("AB", report.combinedContent?.text)
        val bad = report.trace.last().structure
        assertEquals(44, bad.startBit)
        assertEquals(58, bad.problemStartBit)
        assertEquals(68, bad.problemEndBit)
        assertEquals(Issue.INVALID_VALUE, bad.issue)
        assertEquals(3, bad.count)
    }

    @Test fun insufficientPayloadShowsDeclaredBitsAndAvailableBits() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(4, 4); put(255, 8)
        }.finish(6)
        val item = analyze(data).trace.single().structure
        assertEquals(255, item.count)
        assertEquals(2040, item.requiredBits)
        assertEquals(12, item.availableBits)
        assertEquals(36, item.problemStartBit)
        assertEquals(48, item.problemEndBit)
        assertEquals(Issue.INCOMPLETE, item.issue)
    }

    @Test fun eciUnknownMalformedAndTruncatedHeadersStopAtTheirOwnFields() {
        val valid = PayloadBits().apply { byteSegment("A"); put(0, 4); put(7, 4); put(26, 8) }.finish()
        val unknown = IntArray(valid.size).apply { this[4] = 0x80 }
        val unclear = analyze(valid, unknown).trace.single().structure
        assertEquals(Issue.UNKNOWN_BITS, unclear.issue)
        assertEquals(32, unclear.problemStartBit)
        assertEquals(33, unclear.problemEndBit)
        assertNull(unclear.eci)
        val malformed = PayloadBits().apply { byteSegment("A"); put(0, 4); put(7, 4); put(0xE0, 8) }.finish()
        assertEquals(Issue.INVALID_ECI, analyze(malformed).trace.single().structure.issue)
        val truncated = valid.copyOf(4)
        val incomplete = analyze(truncated).trace.single().structure
        assertEquals(Issue.INCOMPLETE, incomplete.issue)
        assertEquals(28, incomplete.problemStartBit)
        assertEquals(32, incomplete.problemEndBit)
    }

    @Test fun zeroLengthPayloadIsParsedAndDistinctFromNoPayloadOrFailure() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("") }.finish()
        val report = analyze(data)
        assertNull(report.stop)
        assertEquals(0, report.trace.single().structure.count)
        assertEquals("", report.groups.single().text)
        assertEquals("A", report.combinedContent?.text)
    }

    @Test fun offsetsRemainOriginalAfterMultipleTerminatorsAndSkippedZeros() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(0, 4); byteSegment("B")
            put(0, 4); put(0, 4); put(7, 4); put(51, 8); put(6, 4)
        }.finish()
        val report = analyze(data)
        assertEquals(listOf(28, 56, 68), report.trace.map { it.structure.startBit })
        assertEquals(68, report.stop?.bit)
        assertEquals("AB", report.combinedContent?.text)
    }

    @Test fun unknownCountAndInvalidControlContextIdentifyTheirOwnFields() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("B") }.finish()
        val unknown = IntArray(data.size).apply { this[3] = 0x04 }
        val count = analyze(data, unknown).trace.single().structure
        assertEquals(Issue.UNKNOWN_BITS, count.issue)
        assertNull(count.count)
        assertEquals(29, count.problemStartBit)
        for (mode in listOf(3, 5, 9)) {
            val invalid = PayloadBits().apply { byteSegment("A"); put(0, 4); put(mode, 4) }.finish()
            val control = analyze(invalid).trace.single().structure
            assertEquals(Issue.INVALID_CONTEXT, control.issue)
            assertEquals(24, control.startBit)
            assertEquals(24, control.problemStartBit)
            assertEquals(28, control.problemEndBit)
        }
    }

    @Test fun invalidAlphanumericAndHanziFieldsRetainTheirModeAndExactRange() {
        val alpha = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(2, 4); put(1, 9); put(63, 6)
        }.finish()
        val item = analyze(alpha).trace.single().structure
        assertEquals(Issue.INVALID_VALUE, item.issue)
        assertEquals(2, item.mode)
        assertEquals(37, item.problemStartBit)
        assertEquals(43, item.problemEndBit)
        val hanzi = PayloadBits().apply { byteSegment("A"); put(0, 4); put(13, 4); put(2, 4) }.finish()
        val subset = analyze(hanzi).trace.single().structure
        assertEquals(Issue.INVALID_VALUE, subset.issue)
        assertEquals(13, subset.mode)
        assertEquals(28, subset.problemStartBit)
        assertEquals(32, subset.problemEndBit)
    }

    @Test fun repeatedControlOnlyContinuationsAreBounded() {
        val data = PayloadBits().apply {
            byteSegment("A")
            repeat(100) { put(0, 4); put(7, 4); put(26, 8) }
        }.finish(220)
        val report = analyze(data)
        assertEquals(PostTerminatorAnalysis.Status.LIMIT, report.status)
        assertEquals(16, report.trace.size)
        assertTrue(report.groups.isEmpty())
    }
}
