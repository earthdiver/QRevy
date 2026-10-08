package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ListDecodingContinuationTest {
    private class Fixture(damageSecondBlock: Boolean = true, deferOriginal: Boolean = false, erasedWords: Int = 35) {
        val text = "abcdefghijklmnopqrstuvwx"
        val qr = Encoder.encode(text, ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 3, qr.maskPattern)
        val original = extraction.codewords.map { it.value }.toIntArray()
        private val de = QrBlockInterleaver.deinterleave(extraction, 3, ErrorCorrectionLevel.Q)
        val alternative: IntArray

        init {
            val rs = ReedSolomonErasures()
            // The first block needs exact bit constraints: even twelve guessed bits leave
            // too many erasures for ordinary RS. Its known bits are all correct.
            for ((raw, location) in de.rawLocations.withIndex()) if (location!!.blockIndex == 0 && location.localIndex < erasedWords) {
                for (coord in extraction.codewords[raw].coordinates.takeLast(2))
                    matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
            // The second block is closer to a different, syntactically valid byte payload.
            // List decoding can also recover the original at one error beyond ordinary RS.
            val block = de.blocks[1]
            val otherData = block.codewords.copyOf(block.numDataCodewords).also {
                if (damageSecondBlock) it[0] = it[0] xor 26
            }
            val other = rs.encode(otherData, de.ecSymbolsPerBlock)
            val differing = other.indices.filter { other[it] != block.codewords[it] }
            assertEquals(if (damageSecondBlock) de.ecSymbolsPerBlock + 1 else 0, differing.size)
            val partial = if (deferOriginal) differing.filter { (other[it] xor block.codewords[it]).countOneBits() == 1 }
                .associateWith { other[it] xor block.codewords[it] } + mapOf(1 to 1, 2 to 1, 3 to 1) else emptyMap()
            val changed = differing.filter { it !in partial }.sortedBy { (other[it] xor block.codewords[it]).countOneBits() }
                .take(de.ecSymbolsPerBlock / 2 + 1)
            val received = block.codewords.copyOf()
            for (local in changed) {
                received[local] = other[local]
                val raw = de.rawLocations.indexOfFirst { it?.blockIndex == 1 && it.localIndex == local }
                val xor = block.codewords[local] xor other[local]
                extraction.codewords[raw].coordinates.forEachIndexed { bit, coord ->
                    if (xor and (1 shl (7 - bit)) != 0) {
                        val value = if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                        matrix.set(coord.x, coord.y, value, 1f, ModuleSource.IMAGE)
                    }
                }
            }
            assertTrue(changed.sumOf { (other[it] xor block.codewords[it]).countOneBits() } <= ErrorLimitPolicy.listDecodingData(3))
            assertArrayEquals(other, rs.decode(received, de.ecSymbolsPerBlock, intArrayOf()).corrected)
            // With five partial erasures, the first list trial only rediscovers the old
            // payload. Recovering the original also requires the subsequent bit assignments.
            for ((local, mask) in partial) {
                val raw = de.rawLocations.indexOfFirst { it?.blockIndex == 1 && it.localIndex == local }
                extraction.codewords[raw].coordinates.forEachIndexed { bit, coord ->
                    if (mask and (1 shl (7 - bit)) != 0)
                        matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                }
            }
            alternative = QrBlockInterleaver.reinterleave(de, listOf(de.blocks[0].codewords, other))
        }

        fun recover(budget: ListDecodingBudget = ListDecodingBudget(), limit: Int? = null,
            stop: () -> Boolean = { false }) = RecoveryEngine().recoverWithDiagnostics(matrix,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.Q, qr.maskPattern),
                maxKnownDataEccMismatches = limit, listDecodingBudget = budget, stopRequested = stop)
    }

    @Test fun aValidatedBitConstraintCandidateDoesNotHideAnotherListDecodedPayload() {
        val fixture = Fixture()
        val outcome = assertRetainsBothPayloads(fixture)
        // The extra list-decoding cap must not retroactively remove the ordinary candidate.
        assertTrue(outcome.candidates.single { it.correctedInterleaved.contentEquals(fixture.alternative) }
            .knownBitMismatchesAfterCorrection > ErrorLimitPolicy.listDecodingData(3))
    }

    @Test fun rediscoveringTheExistingPayloadDoesNotStopLaterUnknownBitTrials() {
        val fixture = Fixture(deferOriginal = true)
        val outcome = assertRetainsBothPayloads(fixture)
        val restored = outcome.candidates.single { it.correctedInterleaved.contentEquals(fixture.original) }
        assertTrue(restored.partialGuessedBits > 0)
        assertTrue(requireNotNull(outcome.searchDiagnostic.listDecoding).trials > 1)
    }

    private fun assertRetainsBothPayloads(fixture: Fixture): RecoveryOutcome {
        val ordinary = fixture.recover(budget = ListDecodingBudget(0))
        val existing = ordinary.candidates.single { it.correctedInterleaved.contentEquals(fixture.alternative) }
        assertTrue(existing.payloadParsed)
        assertTrue(existing.actualBlockMethods.any { it.bitConstraintSolved })
        assertTrue(ordinary.candidates.none { it.correctedInterleaved.contentEquals(fixture.original) })

        val outcome = fixture.recover()
        assertNotNull(outcome.searchDiagnostic.listDecoding)
        assertTrue(outcome.candidates.any { it.correctedInterleaved.contentEquals(fixture.alternative) })
        val restored = outcome.candidates.single { it.correctedInterleaved.contentEquals(fixture.original) }
        assertEquals(fixture.text, restored.text)
        assertTrue(restored.actualBlockMethods[0].bitConstraintSolved)
        assertNotNull(restored.actualBlockMethods[1].listDecoding)
        assertEquals(outcome.candidates.size, outcome.candidates.map { it.identity() }.toSet().size)
        assertFalse(SearchIncompleteReason.INTERNAL_ERROR in outcome.searchDiagnostic.incompleteReasons)
        return outcome
    }

    @Test fun aZeroErrorLimitKeepsTheExactCandidateWithoutListDecoding() {
        val fixture = Fixture(damageSecondBlock = false)
        val outcome = fixture.recover(limit = 0, budget = ListDecodingBudget(0))
        assertTrue(outcome.candidates.any { it.correctedInterleaved.contentEquals(fixture.original) })
        assertTrue(outcome.candidates.all { it.knownBitMismatchesAfterCorrection == 0 })
        assertNull(outcome.searchDiagnostic.listDecoding)
        assertFalse(outcome.searchDiagnostic.unsearched)
    }

    @Test fun anOrdinaryRouteAlsoSupportedByExactConstraintsDoesNotNeedListDecoding() {
        val fixture = Fixture(damageSecondBlock = false, erasedWords = 1)
        val outcome = fixture.recover(budget = ListDecodingBudget(0))
        val restored = outcome.candidates.single { it.correctedInterleaved.contentEquals(fixture.original) }
        assertTrue(restored.actualBlockMethods.none { it.bitConstraintSolved })
        assertTrue(restored.blockDerivations.values.flatten().any { it.bitConstraintSolved })
        assertNull(outcome.searchDiagnostic.listDecoding)
    }

    @Test fun aTimeLimitKeepsPreviouslyValidatedBitConstraintCandidates() {
        val fixture = Fixture()
        val outcome = fixture.recover(budget = ListDecodingBudget(0))
        assertTrue(outcome.candidates.any { it.correctedInterleaved.contentEquals(fixture.alternative) })
        assertTrue(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
        assertFalse(SearchIncompleteReason.USER_INTERRUPTED in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun interruptionKeepsPreviouslyValidatedBitConstraintCandidates() {
        val fixture = Fixture()
        var clock = 0L
        var additionalStarted = false
        val outcome = fixture.recover(budget = ListDecodingBudget(
            onProgress = { elapsed, _ -> if (elapsed >= 2_000) additionalStarted = true },
            clockMillis = { clock += 250; clock }), stop = { additionalStarted })
        assertTrue(outcome.candidates.any { it.correctedInterleaved.contentEquals(fixture.alternative) })
        assertTrue(SearchIncompleteReason.USER_INTERRUPTED in outcome.searchDiagnostic.incompleteReasons)
        assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
        assertEquals(setOf(ListDecodingStatus.INTERRUPTED),
            outcome.searchDiagnostic.listDecodingExecution?.stages?.get(ListDecodingStage.ADDITIONAL))
    }
}
