package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class PayloadConstraintFallbackTest {
    private class Fixture(extraBodyError: Boolean = false, extraErasure: Boolean = false, suffixXor: Int = 255) {
        val splitGroup = extraBodyError || extraErasure
        val text = if (splitGroup) "000000984" + "0".repeat(21) else "984" + "0".repeat(27)
        val qr = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_MASK_PATTERN to 0))
        val format = QrFormatConstraint(ErrorCorrectionLevel.L, qr.maskPattern)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        val original = extraction.codewords.map { it.value }.toIntArray()
        init {
            val errors = if (splitGroup) listOf(4, 8, 10, 15, 16, 17) + if (extraBodyError) listOf(12) else emptyList()
                else listOf(2, 4, 6, 8, 15, 16, 17)
            for (raw in errors) {
                val mask = when { raw >= 15 -> suffixXor; splitGroup && raw == 4 -> 3; !splitGroup && raw == 2 -> 0x30; else -> 1 }
                extraction.codewords[raw].coordinates.forEachIndexed { bit, coord ->
                    if (mask and (1 shl (7 - bit)) != 0) matrix.set(coord.x, coord.y,
                        if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                        1f, ModuleSource.IMAGE)
                }
            }
            if (splitGroup) erase(5, 0) else erase(2, 4)
            if (extraErasure) erase(12, 7)
        }
        private fun erase(raw: Int, bit: Int) {
            val c = extraction.codewords[raw].coordinates[bit]
            matrix.set(c.x, c.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        fun recover(limit: Int? = null, budget: ListDecodingBudget = ListDecodingBudget(0)) =
            RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format, trustKnownPayloadCount = true,
                maxKnownDataEccMismatches = limit, listDecodingBudget = budget)
        fun inputs(trust: Boolean = true): List<PayloadRecoveryInput> {
            val observed = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(matrix, 1, qr.maskPattern), 1, ErrorCorrectionLevel.L)
            val inferred = observed.copyDeep()
            val constraints = PayloadConstraintSolver.apply(inferred, 1, trustKnownCount = trust)
            return PayloadRecoveryInput.variants(observed, inferred, constraints, false, trust)
        }
    }

    @Test fun structureOnlyRetainsUnknownBodyBitsAndIndependentProvenance() {
        val fixture = Fixture()
        val inputs = fixture.inputs()
        assertEquals(2, inputs.size)
        assertEquals(0x08, inputs[0].blocks.blocks.single().unknownMasks[2])
        assertEquals(0, inputs[1].blocks.blocks.single().unknownMasks[2])
        assertTrue(inputs[0].constraints.filled.none { it.kind == PayloadConstraintSolver.ConstraintKind.DATA_VALUE })
        assertTrue(inputs[1].constraints.filled.any { it.kind == PayloadConstraintSolver.ConstraintKind.DATA_VALUE })
        assertEquals(inputs[0].constraints.trustedStructure, inputs[1].constraints.trustedStructure)
        assertTrue(fixture.inputs(trust = false).any { it.constraints.filledBits == 0 })
    }

    @Test fun ordinaryRsCanRecoverWithoutAnIncorrectBodyInference() {
        val fixture = Fixture()
        val result = fixture.recover().candidates.first { it.text == fixture.text }
        assertArrayEquals(fixture.original, result.correctedInterleaved)
        assertEquals(24, result.constraintFilledBits)
        assertEquals(29, result.knownBitMismatchesAfterCorrection)
        assertTrue(result.constraintForcedByUserSetting)
        assertFalse(result.actualBlockMethods.any { it.bitConstraintSolved || it.listDecoding != null })
        assertTrue(fixture.recover(limit = 28).candidates.none { it.text == fixture.text })
        val pinned = fixture.extraction.codewords[15].coordinates[0]
        fixture.matrix[pinned.x, pinned.y].source = ModuleSource.USER
        assertTrue(fixture.recover().candidates.none { it.text == fixture.text })
    }

    @Test fun partialBitSearchUsesTheStructureOnlyInput() {
        val fixture = Fixture(extraErasure = true)
        val result = fixture.recover().candidates.first { it.text == fixture.text }
        assertArrayEquals(fixture.original, result.correctedInterleaved)
        assertTrue((listOf(result) + result.alternativeDerivations).any { it.partialGuessedBits > 0 })
    }

    @Test fun listDecodingUsesTheStructureOnlyInputAndSharedTimeLimit() {
        val fixture = Fixture(extraBodyError = true, suffixXor = 1)
        val stopped = fixture.recover(budget = ListDecodingBudget(0))
        assertTrue(stopped.candidates.none { it.text == fixture.text })
        assertTrue(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in stopped.searchDiagnostic.incompleteReasons)
        val result = fixture.recover(budget = ListDecodingBudget(30_000)).candidates.first { it.text == fixture.text }
        assertArrayEquals(fixture.original, result.correctedInterleaved)
        assertTrue((listOf(result) + result.alternativeDerivations).any { route ->
            route.actualBlockMethods.any { it.listDecoding != null }
        })
    }
}
