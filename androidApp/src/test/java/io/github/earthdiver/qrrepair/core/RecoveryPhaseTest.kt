package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryPhaseTest {
    /** Erasures follow two independently generated RS differences, not an external image. */
    private class Fixture {
        val text = "synthetic payload example"
        val qr = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 3))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 3, qr.maskPattern)
        val original = extraction.codewords.map { it.value }.toIntArray()
        init {
            val rs = ReedSolomonErasures()
            val data = original.copyOf(44)
            val a = rs.encode(data.copyOf().apply { this[3] = this[3] xor 1 }, 26)
            val b = rs.encode(data.copyOf().apply { this[4] = this[4] xor 2 }, 26)
            for (raw in original.indices) {
                val mask = (original[raw] xor a[raw]) or (original[raw] xor b[raw])
                extraction.codewords[raw].coordinates.forEachIndexed { bit, coord ->
                    val inSuffix = raw * 8 + bit in (12 + text.length * 8) until 44 * 8
                    if (inSuffix || mask and (1 shl (7 - bit)) != 0)
                        matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                }
            }
        }
        fun recover(budget: ListDecodingBudget, errorLimit: Int = 5) = RecoveryEngine().recoverWithDiagnostics(matrix,
            trustKnownPayloadCount = true, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, qr.maskPattern),
            maxKnownDataEccMismatches = errorLimit, listDecodingBudget = budget)
    }

    @Test fun smallExactSpacesRemainAvailableAlongsideTentativeRsCandidates() {
        val fixture = Fixture()
        val outcome = fixture.recover(ListDecodingBudget(0))
        val exact = outcome.candidates.filter { it.knownBitMismatchesAfterCorrection == 0 }
        assertEquals(4, exact.size)
        assertTrue(exact.any { it.correctedInterleaved.contentEquals(fixture.original) && it.text == fixture.text })
        assertTrue(exact.all { it.conventionalRsDiagnostic?.succeeded == false })
        assertTrue(exact.any { candidate ->
            (listOf(candidate) + candidate.alternativeDerivations).any { route ->
                route.actualBlockMethods.any { it.bitConstraintSolved } ||
                    route.blockDerivations.values.flatten().any { it.bitConstraintSolved }
            }
        })
        val execution = requireNotNull(outcome.searchDiagnostic.listDecodingExecution)
        assertEquals(setOf(ListDecodingStatus.TIME_LIMIT), execution.stages[ListDecodingStage.ADDITIONAL])
        assertFalse(SearchIncompleteReason.INTERNAL_ERROR in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun aDeferredEarlyListPassDoesNotPreventExactRecovery() {
        val fixture = Fixture()
        var clock = 0L
        val outcome = fixture.recover(ListDecodingBudget(5_000, clockMillis = { clock += 250; clock }))
        assertTrue(outcome.candidates.any { it.text == fixture.text })
        assertEquals(setOf(ListDecodingStatus.DEFERRED),
            outcome.searchDiagnostic.listDecodingExecution?.stages?.get(ListDecodingStage.WITHOUT_ASSIGNMENTS))
        assertFalse(SearchIncompleteReason.INTERNAL_ERROR in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun zeroErrorLimitExplainsWhyBothListPassesAreSkipped() {
        val fixture = Fixture()
        val outcome = fixture.recover(ListDecodingBudget(0), errorLimit = 0)
        assertEquals(4, outcome.candidates.size)
        assertTrue(outcome.candidates.any { it.text == fixture.text })
        assertNull(outcome.searchDiagnostic.listDecoding)
        assertTrue(outcome.searchDiagnostic.listDecodingExecution!!.stages.values.all { it == setOf(ListDecodingStatus.ZERO_ERROR_LIMIT) })
    }
}
