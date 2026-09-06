package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairProvenanceTest {
    @Test fun structuralChangesCarrySpecificReasons() {
        val source = QrMatrix(21)
        val repaired = source.copy()
        StructuralRepair.apply(repaired, ErrorCorrectionLevel.L, 0, preserveUserEdits = false)

        val traces = RepairTraceBuilder.build(source, repaired, null)
        assertEquals(RepairReasonKind.FINDER_PATTERN, traces[Coord(0, 0)]?.primaryReason)
        assertEquals(RepairReasonKind.SEPARATOR, traces[Coord(7, 0)]?.primaryReason)
        assertEquals(RepairReasonKind.FIXED_DARK_MODULE, traces[Coord(8, 13)]?.primaryReason)
    }

    @Test fun alignmentReasonsTakePrecedenceWhereTheyCrossTimingLines() {
        for (version in listOf(7, 14, 40)) {
            val source = QrMatrix(17 + 4 * version)
            val repaired = source.copy()
            StructuralRepair.apply(repaired, ErrorCorrectionLevel.L, 0)
            val traces = RepairTraceBuilder.build(source, repaired, null)
            val interiorCenters = Version.getVersionForNumber(version).alignmentPatternCenters.drop(1).dropLast(1)
            for (center in interiorCenters) for (offset in -2..2) {
                for (coord in listOf(Coord(center + offset, 6), Coord(6, center + offset))) {
                    assertEquals("V$version $coord", RepairReasonKind.ALIGNMENT_PATTERN, traces.getValue(coord).primaryReason)
                }
            }
            for (coord in listOf(Coord(8, 6), Coord(6, 8))) {
                assertEquals("V$version $coord", RepairReasonKind.TIMING_PATTERN, traces.getValue(coord).primaryReason)
            }
        }
    }

    @Test fun correctionDoesNotBecomeInputConfidence() {
        val source = QrMatrix(21)
        source.set(10, 10, ModuleValue.ERASED, .2f, ModuleSource.IMAGE)
        val repaired = source.copy()
        repaired.set(10, 10, ModuleValue.ONE, 1f, ModuleSource.RECOVERED)
        val trace = RepairTraceBuilder.build(source, repaired, null).getValue(Coord(10, 10))
        assertEquals(.2f, trace.originalConfidence, 0f)
        assertEquals(1f, repaired[10, 10].confidence, 0f)
        val explanation = trace.explanationJa()
        assertTrue(explanation.contains("修復前セルの相対信頼度（確率ではありません）=0.20"))
        assertTrue(!explanation.contains("画像判定の相対信頼度"))
    }

    @Test fun manualAndStructuralInputConfidenceIsNotDescribedAsImageEvidence() {
        for (inputSource in listOf(ModuleSource.USER, ModuleSource.STRUCTURAL)) {
            val source = QrMatrix(21)
            source.set(0, 0, ModuleValue.ERASED, .3f, inputSource)
            val repaired = source.copy()
            StructuralRepair.apply(repaired, ErrorCorrectionLevel.L, 0)
            val trace = RepairTraceBuilder.build(source, repaired, null).getValue(Coord(0, 0))
            assertEquals(.3f, trace.originalConfidence, 0f)
            assertTrue(trace.explanationJa().contains("修復前セルの相対信頼度（確率ではありません）=0.30"))
        }
    }

    @Test fun candidateDiffReturnsOnlyDifferentModules() {
        val a = QrMatrix(21)
        val b = a.copy()
        b.set(10, 10, ModuleValue.ONE, 1f, ModuleSource.USER)
        b.set(11, 10, ModuleValue.ZERO, 1f, ModuleSource.USER)

        val diff = RepairTraceBuilder.diffCoordinates(a, b)
        // (10,10) changed from ERASED to ONE and (11,10) from ERASED to ZERO.
        assertTrue(Coord(10, 10) in diff)
        assertTrue(Coord(11, 10) in diff)
        assertEquals(2, diff.size)
    }

    @Test fun partialBitSearchIsThePrimaryVisibleReasonWhileRsRemainsSupportingEvidence() {
        val source = QrMatrix(21)
        val repaired = source.copy()
        val coord = Coord(10, 10)
        repaired.set(coord.x, coord.y, ModuleValue.ONE, 1f, ModuleSource.RECOVERED)
        val extraction = CodewordExtraction(
            listOf(PartialCodeword(0, 0x80, listOf(coord) + List(7) { Coord(11 + it, 10) })),
        )
        val candidate = RecoveryCandidate(
            version = 1,
            ecLevel = ErrorCorrectionLevel.L,
            dataMask = 0,
            text = "x",
            dataBytes = byteArrayOf(),
            formatMatches = 15,
            formatMismatches = 0,
            originalErasedCodewords = 1,
            erasedCodewords = 0,
            correctedErrataSymbols = 1,
            knownBitMismatchesAfterCorrection = 0,
            knownBitsCompared = 0,
            correctedInterleaved = intArrayOf(0x80),
            extraction = extraction,
            partialGuessedBits = 1,
            repairInfo = listOf(CodewordRepairInfo(0, 0, 0, 0x80, 0x80, rsErrata = true, partialEnumerated = true)),
        )

        val trace = RepairTraceBuilder.build(source, repaired, candidate).getValue(coord)

        assertEquals(RepairReasonKind.PARTIAL_BIT_SEARCH, trace.primaryReason)
        assertTrue(RepairReasonKind.RS_ERASURE_RECOVERY in trace.supportingReasons)
    }

    @Test fun structuralRepairResolvesManualErasuresButPreservesManualBits() {
        val matrix = QrMatrix(21)
        matrix.set(0, 0, ModuleValue.ZERO, 1f, ModuleSource.USER)
        matrix.set(1, 0, ModuleValue.ERASED, 0f, ModuleSource.USER)

        StructuralRepair.apply(matrix, ErrorCorrectionLevel.L, 0, preserveUserEdits = true)

        assertEquals(ModuleValue.ZERO, matrix[0, 0].value)
        assertEquals(ModuleSource.USER, matrix[0, 0].source)
        assertEquals(ModuleValue.ONE, matrix[1, 0].value)
        assertEquals(ModuleSource.STRUCTURAL, matrix[1, 0].source)
    }
}
