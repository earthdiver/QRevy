package io.github.earthdiver.qrrepair.core

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Source
import io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.Status

internal fun PayloadBits.byteSegment(text: String, version: Int = 1) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    put(4, 4)
    put(bytes.size, if (version <= 9) 8 else 16)
    bytes.forEach { put(it.toInt() and 255, 8) }
}

/** Synthetic fixture only. Splits data into RS blocks before interleaving and masking. */
internal fun postTerminatorMatrix(data: ByteArray, version: Int = 1,
    ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L, mask: Int = 0): QrMatrix {
    val matrix = QrMatrix(version * 4 + 17)
    StructuralRepair.apply(matrix, ecc, mask)
    val extraction = CodewordExtractor.extract(matrix, version, mask)
    val de = QrBlockInterleaver.deinterleave(extraction, version, ecc)
    require(data.size == de.blocks.sumOf { it.numDataCodewords })
    var offset = 0
    val blocks = de.blocks.map { block ->
        IntArray(block.codewords.size) { i -> if (i < block.numDataCodewords) data[offset + i].toInt() and 255 else 0 }
            .also { ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(it, de.ecSymbolsPerBlock); offset += block.numDataCodewords }
    }
    val raw = QrBlockInterleaver.reinterleave(de, blocks)
    extraction.codewords.forEachIndexed { i, cw -> cw.coordinates.forEachIndexed { bit, c ->
        val value = ((raw[i] ushr (7 - bit)) and 1) xor if (CodewordExtractor.maskApplies(mask, c.x, c.y)) 1 else 0
        matrix.set(c.x, c.y, if (value == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
    } }
    return matrix
}

class PostTerminatorAnalysisTest {
    private fun analyze(data: ByteArray, version: Int = 1, unknown: IntArray = IntArray(data.size)) =
        PostTerminatorAnalysis.analyze(data, version, ErrorCorrectionLevel.L, unknown)

    private fun interpretations(report: PostTerminatorAnalysis.Report) = listOf(report) + report.alternatives

    @Test fun readsByteAlignedSegmentsForEveryZeroFillLengthWithoutChangingInput() {
        val widths = mutableSetOf<Int>()
        for (count in 1..24) {
            val after = 18 + count / 3 * 10 + intArrayOf(0, 4, 7)[count % 3]
            val fill = (8 - after % 8) % 8
            widths += fill
            val data = PayloadBits().apply {
                put(1, 4); put(count, 10)
                repeat(count / 3) { put(0, 10) }
                if (count % 3 != 0) put(0, if (count % 3 == 1) 4 else 7)
                put(0, 4); put(0, fill); byteSegment("aligned")
            }.finish(80)
            val original = data.copyOf()
            val report = analyze(data)
            val parsed = interpretations(report).first { it.groups.firstOrNull()?.text == "aligned" }
            assertEquals(after + fill, parsed.groups.first().startBit)
            assertEquals(after + fill, parsed.boundaries.first().startBit)
            assertEquals(after, parsed.boundaries.first().afterTerminatorBit)
            assertArrayEquals(original, data)
            assertFalse(StrictQrPayloadValidator.isValid(data, 1))
        }
        assertEquals((0..7).toSet(), widths)
    }

    @Test fun skipsKnownZeroModesFromByteBoundaryWithoutConsumingPayloadLeadingZeros() {
        for (zeros in listOf(1, 2, 20)) for (mode in listOf(1, 2, 4)) {
            val data = PayloadBits().apply {
                put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1)
                repeat(zeros) { put(0, 4) }
                when (mode) {
                    1 -> { put(1, 4); put(3, 10); put(123, 10) }
                    2 -> { put(2, 4); put(1, 9); put(11, 6) }
                    4 -> byteSegment("B")
                }
            }.finish(40)
            val original = data.copyOf()
            val expected = if (mode == 1) "123" else "B"
            val report = interpretations(analyze(data)).first { it.groups.singleOrNull()?.text == expected }
            assertEquals(24 + 4 * zeros, report.groups.single().startBit)
            assertEquals(PostTerminatorAnalysis.Boundary(23, 24 + 4 * zeros, PostTerminatorAnalysis.StartKind.BYTE_ALIGNED),
                report.boundaries.single())
            assertTrue(report.tail.startsWith("0".repeat(1 + 4 * zeros)))
            assertArrayEquals(original, data)
        }
    }

    @Test fun identicalStartsAfterZeroSkippingProduceOnlyOneInterpretation() {
        val data = PayloadBits().apply {
            put(2, 4); put(2, 9); put(461, 11); put(0, 4)
            put(0, 4); put(0, 4); byteSegment("C")
        }.finish()
        val report = analyze(data)
        assertEquals("C", report.groups.single().text)
        assertEquals(36, report.groups.single().startBit)
        assertTrue(report.alternatives.isEmpty())
    }

    @Test fun alignedZeroSkippingStopsAtUnknownModeBitsAndReportsTheirOriginalPosition() {
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1)
            put(0, 4); put(0, 4); byteSegment("B")
        }.finish()
        val unknown = IntArray(data.size).apply { this[3] = 1 }
        val report = interpretations(analyze(data, unknown = unknown)).single {
            it.boundaries.single().kind == PostTerminatorAnalysis.StartKind.BYTE_ALIGNED
        }
        assertEquals(28, report.remainingStartBit)
        assertEquals(28, report.stop?.bit)
        assertEquals("000?", report.stop?.modeBits)
        assertEquals(PostTerminatorAnalysis.StopReason.UNKNOWN_MODE_BITS, report.stop?.reason)
        assertTrue(report.groups.isEmpty())
        assertTrue('?' in report.tail)
    }

    @Test fun alignedZeroSkippingKeepsUnsupportedDataAndDoesNotInventPayloadFromZeros() {
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1)
            put(0, 4); put(0, 4); put(12, 4); put(0x55, 8)
        }.finish()
        val report = interpretations(analyze(data)).single {
            it.boundaries.single().kind == PostTerminatorAnalysis.StartKind.BYTE_ALIGNED
        }
        assertEquals(32, report.stop?.bit)
        assertEquals("1100", report.stop?.modeBits)
        assertEquals(PostTerminatorAnalysis.StopReason.UNSUPPORTED_MODE, report.stop?.reason)
        assertTrue(report.groups.isEmpty())
        val zeros = data.copyOf().apply { fill(0, 3) }
        assertTrue(interpretations(analyze(zeros)).all { it.groups.isEmpty() && it.combinedContent == null })
    }

    @Test fun retainsBothValidInterpretationsWithoutCombiningTheirPayloads() {
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6)
            put(0, 4); put(0, 1); byteSegment("A@")
        }.finish()
        val report = analyze(data)
        assertEquals("BR", report.groups.single().text)
        assertEquals("ABR", report.combinedContent?.text)
        val aligned = report.alternatives.single()
        assertEquals("A@", aligned.groups.single().text)
        assertNull(aligned.combinedContent)
        assertEquals(PostTerminatorAnalysis.StartKind.BYTE_ALIGNED, aligned.boundaries.single().kind)
    }

    @Test fun neverSkipsUnknownOrNonzeroAlignmentBits() {
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6)
            put(0, 4); put(0, 1); byteSegment("A@")
        }.finish()
        val unknown = IntArray(data.size).apply { this[2] = 1 }
        val unreadable = analyze(data, unknown = unknown)
        assertTrue(unreadable.alternatives.isEmpty())
        assertEquals(PostTerminatorAnalysis.StopReason.UNKNOWN_MODE_BITS, unreadable.stop?.reason)
        assertTrue('?' in unreadable.tail)
        val nonzero = data.copyOf().apply { this[2] = (this[2].toInt() or 1).toByte() }
        assertTrue(analyze(nonzero).alternatives.isEmpty())
    }

    @Test fun reportsUnsupportedModeAtAlignedStartAndKeepsOpaqueSuffix() {
        val data = PayloadBits().apply {
            put(1, 4); put(7, 10); put(123, 10); put(456, 10); put(7, 4)
            put(0, 4); put(0, 6)
            put(12, 4); put(0x55, 8); put(0xA6, 8)
        }.finish()
        val report = analyze(data)
        val aligned = report.alternatives.single()
        assertEquals(48, aligned.remainingStartBit)
        assertEquals(48, aligned.stop?.bit)
        assertEquals("1100", aligned.stop?.modeBits)
        assertEquals(PostTerminatorAnalysis.StopReason.UNSUPPORTED_MODE, aligned.stop?.reason)
        assertEquals(PostTerminatorAnalysis.Boundary(42, 48, PostTerminatorAnalysis.StartKind.BYTE_ALIGNED),
            aligned.boundaries.single())
        assertEquals(72, aligned.paddingCandidateStartBit)
        assertEquals((data.size * 8 - 42), aligned.tail.length)
        assertEquals(report.tail, aligned.tail)
        assertTrue(aligned.groups.isEmpty())
        assertNull(aligned.combinedContent)
    }

    @Test fun stopReasonIdentifiesALaterUnsupportedSegment() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("B"); put(6, 4) }.finish()
        val report = analyze(data)
        assertEquals(24, report.remainingStartBit)
        assertEquals(44, report.stop?.bit)
        assertEquals("0110", report.stop?.modeBits)
        assertEquals(PostTerminatorAnalysis.StopReason.UNSUPPORTED_MODE, report.stop?.reason)
    }

    @Test fun paddingLikeBytesInsideOpaqueDataArePreservedAndUnknownSuffixIsNotInferred() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); put(12, 4); put(0, 4) }.finish()
        data[4] = 0xEC.toByte(); data[5] = 0x11; data[6] = 0x42
        val report = analyze(data)
        assertTrue(requireNotNull(report.paddingCandidateStartBit) > 6 * 8)
        assertEquals((data.size * 8 - 24), report.tail.length)
        val unknown = IntArray(data.size).apply { this[lastIndex] = 1 }
        assertNull(analyze(data, unknown = unknown).paddingCandidateStartBit)
    }

    @Test fun boundsAmbiguousInterpretationsAndReportsUnexploredBranches() {
        val data = PayloadBits().apply {
            repeat(12) {
                put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1)
                byteSegment("A@"); put(0, 4)
            }
        }.finish(100)
        val report = analyze(data)
        assertTrue(report.alternativesTruncated)
        assertEquals(16, interpretations(report).size)
        assertTrue(interpretations(report).all { it.groups.size <= 16 })
    }

    @Test fun laterAlignmentUsesOriginalOffsetsAndPreservesEciContext() {
        val data = PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment("A"); put(0, 4)
            put(1, 4); put(7, 10); put(0, 10); put(0, 10); put(0, 4)
            put(0, 4); put(0, 2); put(0, 4); put(0, 4); byteSegment("人間")
        }.finish(40)
        val report = interpretations(analyze(data)).first { it.groups.lastOrNull()?.text == "人間" }
        assertEquals(listOf(36, 88), report.groups.map { it.startBit })
        assertEquals(listOf("0000000", "人間"), report.groups.map { it.text })
        assertEquals(PostTerminatorAnalysis.Boundary(78, 88, PostTerminatorAnalysis.StartKind.BYTE_ALIGNED),
            report.boundaries.last())
        assertNull(report.combinedContent)
    }

    @Test fun combinesEverySupportedCommonModeInPayloadOrder() {
        for ((mode, expected) in listOf(1 to "123123123", 2 to "AAA", 4 to "AAA", 8 to "\u3000\u3000\u3000", 13 to "\u3000\u3000\u3000")) {
            val data = PayloadBits().apply {
                repeat(3) { index ->
                    if (index > 0) put(0, 4)
                    when (mode) {
                        1 -> { put(1, 4); put(3, 10); put(123, 10) }
                        2 -> { put(2, 4); put(1, 9); put(10, 6) }
                        4 -> byteSegment("A")
                        8 -> { put(8, 4); put(1, 8); put(0, 13) }
                        13 -> { put(13, 4); put(1, 4); put(1, 8); put(0, 13) }
                    }
                }
            }.finish(40)
            val combined = requireNotNull(analyze(data).combinedContent)
            assertEquals(mode, combined.mode)
            assertEquals(expected, combined.text)
            if (mode == 4) assertArrayEquals(byteArrayOf(65, 65, 65), combined.bytes)
            else assertNull(combined.bytes)
            assertFalse(StrictQrPayloadValidator.isValid(data, 1))
        }
    }

    @Test fun combinedBytePayloadRetainsBinaryBytesAndEciChanges() {
        val data = PayloadBits().apply {
            put(7, 4); put(3, 8)
            put(4, 4); put(3, 8); put(0, 8); put(255, 8); put(128, 8)
            put(0, 4)
            put(7, 4); put(26, 8); byteSegment("人間")
            put(0, 4); byteSegment("終")
        }.finish(40)
        val combined = requireNotNull(analyze(data).combinedContent)
        assertEquals("\u0000ÿ\u0080人間終", combined.text)
        assertArrayEquals(byteArrayOf(0, -1, -128) + "人間終".toByteArray(Charsets.UTF_8), combined.bytes)
    }

    @Test fun combinedAlphanumericTextPreservesFnc1Context() {
        val data = PayloadBits().apply {
            put(5, 4)
            repeat(2) { index ->
                if (index > 0) put(0, 4)
                put(2, 4); put(1, 9); put(38, 6)
            }
        }.finish()
        assertEquals("\u001d\u001d", analyze(data).combinedContent?.text)
    }

    @Test fun mixedModesOrMissingMainPayloadDoNotOfferCombinedContent() {
        for (mixedMain in listOf(false, true)) {
            val data = PayloadBits().apply {
                byteSegment("A")
                if (!mixedMain) put(0, 4)
                put(1, 4); put(3, 10); put(123, 10)
                put(0, 4); byteSegment("B")
            }.finish(40)
            assertTrue(analyze(data).groups.isNotEmpty())
            assertNull(analyze(data).combinedContent)
        }
        val emptyMain = PayloadBits().apply { put(0, 4); byteSegment("B") }.finish()
        assertNull(analyze(emptyMain).combinedContent)
        assertNull(analyze(PayloadBits().apply { byteSegment("A") }.finish()).combinedContent)
    }

    @Test fun combinedContentStopsBeforeUnknownOrMalformedRemainder() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); byteSegment("B"); put(0, 4); byteSegment("C")
        }.finish()
        val unknown = IntArray(data.size).apply { this[7] = 0x80 }
        val report = analyze(data, unknown = unknown)
        assertEquals(Status.UNPARSED_TAIL, report.status)
        assertEquals("AB", report.combinedContent?.text)
        assertArrayEquals(byteArrayOf(65, 66), report.combinedContent?.bytes)
        val invalid = PayloadBits().apply {
            byteSegment("A"); put(0, 4); byteSegment("B"); put(0, 4); put(6, 4)
        }.finish()
        assertEquals("AB", analyze(invalid).combinedContent?.text)
    }

    @Test fun unverifiedBodyUsesOnlyThePrefixAndKeepsBinaryPayloadBytes() {
        val data = PayloadBits().apply {
            put(4, 4); put(3, 8); put(0, 8); put(255, 8); put(128, 8)
            put(0, 4); byteSegment("tail")
        }.finish()
        val body = requireNotNull(QrContentPreviewParser.parseUnverifiedPrefix(data, 1, ErrorCorrectionLevel.L))
        assertFalse(body.qrStructureValid)
        assertFalse(StrictQrPayloadValidator.isValid(data, 1))
        assertArrayEquals(byteArrayOf(0, -1, -128), body.byteSegments.single().second)
        assertFalse(body.recoveredText.orEmpty().contains("tail"))
        assertTrue(CodewordHex.payloadByteSegments(body.byteSegments).contains("00 FF 80"))
        val bad = PayloadBits().apply { put(6, 4); put(0, 4); byteSegment("tail") }.finish()
        assertNull(QrContentPreviewParser.parseUnverifiedPrefix(bad, 1, ErrorCorrectionLevel.L))
    }

    @Test fun unverifiedTextUsesTheNormalPresentationAndPreservesEci() {
        val data = PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment("本文\n")
            put(0, 4); byteSegment("tail")
        }.finish(40)
        val body = requireNotNull(QrContentPreviewParser.parseUnverifiedPrefix(data, 1, ErrorCorrectionLevel.L))
        assertEquals("本文\n", body.recoveredText)
        assertEquals(QrContentKind.TEXT, body.kind)
        val ranges = mutableListOf<IntRange>()
        val rendered = body.resultContentText(null) { start, end -> ranges += start until end }
        assertEquals("本文\n", rendered.substring(ranges.single()))
        assertFalse(rendered.contains("tail"))
    }

    @Test fun extractsSegmentsImmediatelyAfterAnUnalignedTerminator() {
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6) // Alphanumeric A, terminator starts at bit 19.
            put(0, 4)
            byteSegment("tail")
        }.finish()
        val report = analyze(data)
        assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
        assertEquals(19, report.terminatorBit)
        assertEquals(23, report.groups.single().startBit)
        assertEquals(67, report.groups.single().endBit)
        assertEquals("tail", report.groups.single().text)
        assertFalse(StrictQrPayloadValidator.isValid(data, 1))
    }

    @Test fun ordinaryPaddingAndNulInsideTheBodyAreNotAdditionalSegments() {
        val data = PayloadBits().apply { byteSegment("A\u0000B") }.finish()
        assertEquals(Status.STANDARD_PADDING, analyze(data).status)
        assertEquals(36, analyze(data).terminatorBit)
        assertTrue(analyze(data).groups.isEmpty())
        assertTrue(StrictQrPayloadValidator.isValid(data, 1))
    }

    @Test fun preservesMultipleGroupsAndAllTextIncludingNulsAndUrls() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4)
            byteSegment("https://example.invalid/one\u0000two"); put(0, 4)
            byteSegment("last")
        }.finish(80)
        val report = analyze(data)
        assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
        assertEquals(listOf("https://example.invalid/one\u0000two", "last"), report.groups.map { it.text })
        assertTrue(report.groups[1].startBit > report.groups[0].endBit)
    }

    @Test fun retainsEciAndFnc1StateAcrossTheTerminator() {
        val eci = PayloadBits().apply {
            put(7, 4); put(3, 8) // ISO-8859-1, deliberately different from UTF-8.
            byteSegment("A"); put(0, 4); byteSegment("é")
        }.finish()
        assertEquals("Ã©", analyze(eci).groups.single().text)
        val fnc1 = PayloadBits().apply {
            put(5, 4); byteSegment("A"); put(0, 4)
            put(2, 4); put(1, 9); put(38, 6) // FNC1 alphanumeric % becomes GS.
        }.finish()
        assertEquals("\u001d", analyze(fnc1).groups.single().text)
    }

    @Test fun handlesVersionDependentByteCounts() {
        for (version in listOf(1, 9, 10, 26, 27, 40)) {
            val data = PayloadBits().apply { byteSegment("A", version); put(0, 4); byteSegment("tail", version) }.finish(40)
            assertEquals("version $version", "tail", analyze(data, version).groups.single().text)
        }
    }

    @Test fun unknownInputIsNeverInventedAndMalformedTailsKeepRawBits() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("tail") }.finish()
        val unknown = IntArray(data.size)
        unknown[2] = 0x80 // In the main byte payload.
        assertEquals(Status.UNREADABLE_PREFIX, analyze(data, unknown = unknown).status)
        unknown[2] = 0
        unknown[5] = 0x80 // In the additional byte payload.
        val report = analyze(data, unknown = unknown)
        assertEquals(Status.UNPARSED_TAIL, report.status)
        assertTrue(report.groups.isEmpty())
        assertTrue('?' in report.tail)
        val invalid = PayloadBits().apply { byteSegment("A"); put(0, 4); put(4, 4); put(255, 8) }.finish()
        assertEquals(Status.UNPARSED_TAIL, analyze(invalid).status)
        assertEquals(128, analyze(invalid).tail.length)
    }

    @Test fun noFullTerminatorCannotBeAnalyzed() {
        val shortened = PayloadBits().apply { put(1, 4); put(2, 10); put(12, 7) }.finish(3)
        assertEquals(Status.NO_TERMINATOR, analyze(shortened).status)
    }

    @Test fun skipsRepeatedZeroModesAndPreservesOriginalOffsetsAndData() {
        for (zeros in listOf(1, 2, 20)) {
            val data = PayloadBits().apply {
                byteSegment("A"); put(0, 4)
                repeat(zeros) { put(0, 4) }
                byteSegment("B"); put(0, 4); put(0, 4)
                byteSegment("C")
            }.finish(40)
            val original = data.copyOf()
            val report = analyze(data)
            val start = 24 + zeros * 4
            assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
            assertEquals(listOf(start, start + 28), report.groups.map { it.startBit })
            assertEquals(listOf(start + 20, start + 48), report.groups.map { it.endBit })
            assertEquals("ABC", report.combinedContent?.text)
            assertEquals(listOf("B", "C"), report.groups.map { it.text })
            assertTrue(report.tail.startsWith("0000".repeat(zeros) + "0100"))
            assertArrayEquals(original, data)
        }
    }

    @Test fun skippingZeroModesPreservesLeadingZerosInNumericAndAlphanumericModes() {
        for (mode in listOf(1, 2)) {
            val data = PayloadBits().apply {
                byteSegment("A"); put(0, 4); put(0, 4)
                put(mode, 4)
                if (mode == 1) { put(3, 10); put(123, 10) }
                else { put(1, 9); put(10, 6) }
            }.finish()
            val report = analyze(data)
            assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
            assertEquals(28, report.groups.single().startBit)
            assertEquals(if (mode == 1) "123" else "A", report.groups.single().text)
        }
    }

    @Test fun unknownZeroModeIsNotSkipped() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(0, 4); put(0, 4); byteSegment("B")
        }.finish()
        val unknown = IntArray(data.size).apply { this[3] = 0x08 }
        val report = analyze(data, unknown = unknown)
        assertEquals(Status.UNPARSED_TAIL, report.status)
        assertEquals(28, report.remainingStartBit)
        assertTrue(report.groups.isEmpty())
    }

    @Test fun zerosWithoutAnAdditionalSegmentDoNotProduceContent() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4) }.finish().copyOf()
        data.fill(0, 3)
        val report = analyze(data)
        assertEquals(Status.UNPARSED_TAIL, report.status)
        assertTrue(report.groups.isEmpty())
        assertNull(report.combinedContent)
    }

    @Test fun anAdditionalSegmentMayFillCapacityWithoutAFullFinalTerminator() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4)
            put(1, 4); put(2, 10); put(12, 7)
        }.finish(6)
        val report = analyze(data)
        assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
        assertEquals("12", report.groups.single().text)
        assertEquals(45, report.groups.single().endBit)
    }

    @Test fun parsesMixedNumericAlphanumericKanjiAndHanziSegments() {
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4)
            put(1, 4); put(3, 10); put(123, 10)
            put(2, 4); put(1, 9); put(10, 6)
            put(8, 4); put(1, 8); put(0, 13) // Shift_JIS 8140 (ideographic space).
            put(13, 4); put(1, 4); put(1, 8); put(0, 13) // GB2312 A1A1.
        }.finish(40)
        val report = analyze(data)
        assertEquals(Status.ADDITIONAL_SEGMENTS, report.status)
        assertEquals(listOf(1, 2, 8, 13), report.groups.single().modes)
        assertEquals("123A\u3000\u3000", report.groups.single().text)
    }

    @Test fun fixedConflictsExcludeRsCorrectionsButKeepObservedData() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("tail") }.finish()
        val extraction = CodewordExtractor.extract(postTerminatorMatrix(data), 1, 0)
        val diagnostic = ConventionalRsDiagnostic.analyze(QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.L))
            .copy(manualConflicts = listOf(ManualCellConflict(Coord(20, 20), ModuleValue.ZERO, ModuleValue.ONE)))
        val attempt = PostTerminatorAnalysis.forAttempt(extraction, 1, ErrorCorrectionLevel.L, diagnostic)
        assertTrue(attempt.rsCorrectionRejected)
        assertEquals(listOf(Source.OBSERVED), attempt.views.map { it.source })
        assertEquals("tail", attempt.views.single().report.groups.single().text)
    }

    @Test fun boundsRepeatedGroupsAndPreservesTheirUnparsedRemainder() {
        val data = PayloadBits().apply {
            byteSegment("A")
            repeat(17) { put(0, 4); byteSegment("B") }
        }.finish(60)
        val report = analyze(data)
        assertEquals(Status.LIMIT, report.status)
        assertEquals(16, report.groups.size)
        assertEquals("A" + "B".repeat(16), report.combinedContent?.text)
        assertNotNull(report.remainingStartBit)
    }

    @Test fun failedRecoveryStillOffersConventionalRsAnalysisWithoutAcceptingHiddenQr() {
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("tail") }.finish()
        val matrix = postTerminatorMatrix(data)
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        val failure = requireNotNull(outcome.failure)
        val attempt = PostTerminatorAnalysis.forAttempt(CodewordExtractor.extract(matrix, 1, 0), 1,
            ErrorCorrectionLevel.L, failure.conventionalRsDiagnostic)
        assertFalse(attempt.rsCorrectionRejected)
        assertEquals("tail", attempt.views.single { it.source == Source.CONVENTIONAL_RS }.report.groups.single().text)
    }

    @Test fun deinterleavesUnequalBlocksAndReportsCorrectedTailEvidence() {
        val version = 5
        val ecc = ErrorCorrectionLevel.Q
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment("tail") }.finish(62)
        val matrix = postTerminatorMatrix(data, version, ecc)
        val extraction = CodewordExtractor.extract(matrix, version, 0)
        val de = QrBlockInterleaver.deinterleave(extraction, version, ecc)
        val rawIndex = de.rawLocations.indexOfFirst { it?.blockIndex == 0 && it.localIndex == 5 }
        val modified = extraction.copy(codewords = extraction.codewords.mapIndexed { i, cw ->
            if (i == rawIndex) cw.copy(value = cw.value xor 0x80, unknownBits = 1) else cw
        })
        val diagnostic = ConventionalRsDiagnostic.analyze(QrBlockInterleaver.deinterleave(modified, version, ecc))
        val result = PostTerminatorAnalysis.forAttempt(modified, version, ecc, diagnostic).views.single { it.source == Source.CONVENTIONAL_RS }
        assertEquals("tail", result.report.groups.single().text)
        assertEquals(1, result.changedTailBits)
        assertEquals(1, result.filledTailBits)
        val rejected = diagnostic.copy(knownBitMismatches = 1, knownBitMismatchLimit = 0)
        val attempt = PostTerminatorAnalysis.forAttempt(modified, version, ecc, rejected)
        assertTrue(attempt.rsCorrectionRejected)
        assertEquals(listOf(Source.OBSERVED), attempt.views.map { it.source })
    }
}
