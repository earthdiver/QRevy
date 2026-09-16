package io.github.earthdiver.qrrepair

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class ConventionalRsDiagnosticTest {
    private val rs = ReedSolomonErasures()

    @Test fun countsErrorSymbolsRatherThanChangedBitsAndDoesNotMutateInput() {
        val encoded = rs.encode(IntArray(16) { it * 7 }, 10)
        val input = encoded.copyOf().apply { this[3] = this[3] xor 7 }
        val masks = IntArray(26).apply { this[8] = 255; this[9] = 1 }
        val de = DeinterleavedQr(listOf(RsBlockWork(16, input, masks)),
            Array(26) { RawCodewordLocation(0, it) }, 10)
        val before = input.copyOf()
        val diagnostic = ConventionalRsDiagnostic.analyze(de)
        val block = diagnostic.blocks.single()
        assertEquals(26, diagnostic.totalSymbols)
        assertEquals(16, diagnostic.dataSymbols)
        assertEquals(10, diagnostic.eccSymbols)
        assertEquals(2, block.erasures)
        assertEquals(1, block.errors)
        assertEquals(6, block.margin)
        assertArrayEquals(encoded, diagnostic.correctedInterleaved)
        assertArrayEquals(before, input)
        assertEquals(255, masks[8])
    }

    @Test fun oneOverloadedBlockDoesNotHideOtherBlocksOrInventErrorCount() {
        val encoded = rs.encode(IntArray(16) { it }, 10)
        val de = DeinterleavedQr(listOf(
            RsBlockWork(16, encoded.copyOf(), IntArray(26) { if (it < 11) 1 else 0 }),
            RsBlockWork(16, encoded.copyOf(), IntArray(26)),
        ), Array(52) { RawCodewordLocation(it / 26, it % 26) }, 10)
        val diagnostic = ConventionalRsDiagnostic.analyze(de)
        assertFalse(diagnostic.succeeded)
        assertEquals(ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED, diagnostic.blocks[0].status)
        assertNull(diagnostic.blocks[0].errors)
        assertNull(diagnostic.blocks[0].margin)
        assertEquals(ConventionalRsStatus.SUCCESS, diagnostic.blocks[1].status)
        assertEquals(10, diagnostic.blocks[1].margin)
        assertNull(diagnostic.minMargin)
        assertNull(diagnostic.correctedInterleaved)
    }

    private data class Fixture(val input: QrMatrix, val extraction: CodewordExtraction, val mask: Int)
    private fun fixture(): Fixture {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1))
        val input = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return Fixture(input, CodewordExtractor.extract(input, 1, qr.maskPattern), qr.maskPattern)
    }
    private fun erase(f: Fixture, words: IntRange) {
        words.forEach { raw -> f.extraction.codewords[raw].coordinates.forEach {
            f.input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        } }
    }

    @Test fun successfulStructureRecoveryKeepsTheOriginalConventionalFailure() {
        val f = fixture()
        erase(f, 14..25)
        val candidates = RecoveryEngine().recover(f.input, trustKnownPayloadCount = true,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, f.mask))
        val candidate = candidates.first { it.text == "HELLO" }
        assertTrue(candidate.constraintFilledBits > 0)
        val diagnostic = candidate.conventionalRsDiagnostic!!
        assertEquals(12, diagnostic.blocks.single().erasures)
        assertEquals(ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED, diagnostic.blocks.single().status)
        assertFalse(diagnostic.succeeded)
        assertNull(diagnostic.matches(candidate.correctedInterleaved))
        assertEquals(1, candidate.actualBlockMethods.size)
    }

    @Test fun exactOnlyRouteStillReportsSeparateConventionalSuccessAndNonApplicableRouteMargin() {
        val f = fixture()
        val candidate = RecoveryEngine().recover(f.input, maxKnownDataEccMismatches = 0,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, f.mask)).first { it.text == "HELLO" }
        val diagnostic = candidate.conventionalRsDiagnostic!!
        assertTrue(diagnostic.succeeded)
        assertEquals(true, diagnostic.matches(candidate.correctedInterleaved))
        assertEquals(true, diagnostic.qrStructureValid)
        assertEquals(true, diagnostic.settingsAccepted)
        assertEquals(10, diagnostic.minMargin)
        assertTrue(candidate.actualBlockMethods.single().bitConstraintSolved)
        assertNull(candidate.actualBlockMethods.single().rsMargin)
    }

    @Test fun failedExactOnlyRecoveryRecordsConventionalResultRejectedByMismatchLimit() {
        val f = fixture()
        val coord = f.extraction.codewords[5].coordinates.first()
        f.input.set(coord.x, coord.y, if (f.input[coord.x, coord.y].value == ModuleValue.ONE)
            ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        val engine = RecoveryEngine()
        val candidates = engine.recover(f.input, maxKnownDataEccMismatches = 0,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, f.mask))
        assertTrue(candidates.isEmpty())
        val diagnostic = engine.lastFailure!!.conventionalRsDiagnostic!!
        assertTrue(diagnostic.succeeded)
        assertEquals(1, diagnostic.knownBitMismatches)
        assertEquals(false, diagnostic.settingsAccepted)
        assertEquals(8, diagnostic.minMargin)
    }
}
