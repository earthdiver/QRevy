package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ListDecodingRecoveryTest {
    private class Fixture(val version: Int = 1, val ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L, val text: String = "GS RECOVERY") {
        val qr = Encoder.encode(text, ecc, mapOf(EncodeHintType.QR_VERSION to version, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, version, qr.maskPattern)
        val raw = extraction.codewords.map { it.value }.toIntArray()
        val de = QrBlockInterleaver.deinterleave(extraction, version, ecc)
        val constraint = QrFormatConstraint(ecc, qr.maskPattern)
        fun flip(rawIndex: Int, xor: Int, source: ModuleSource = ModuleSource.IMAGE) {
            extraction.codewords[rawIndex].coordinates.forEachIndexed { bit, coord ->
                if (xor and (1 shl (7 - bit)) != 0) {
                    val opposite = if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
                    matrix.set(coord.x, coord.y, opposite, 1f, source)
                }
            }
        }
        fun recover(limit: Int? = null, budget: ListDecodingBudget = ListDecodingBudget(),
            partialBitLimit: Int = PartialBitSearchLimit.DEFAULT, workingLimit: Long = 32L * 1024 * 1024,
            stop: () -> Boolean = { false }) =
            RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = constraint,
                maxKnownDataEccMismatches = limit, listDecodingBudget = budget, partialBitSearchLimit = partialBitLimit,
                retentionBudget = RecoveryRetentionBudget(workingLimitBytes = workingLimit), stopRequested = stop)
    }

    @Test fun fallbackRecoversFourErroneousBytesBeyondVersionOneLowRsCapacity() {
        val f = Fixture()
        for (i in 2..5) f.flip(i, 1)
        val conventional = f.recover(budget = ListDecodingBudget(0))
        assertTrue(conventional.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        val outcome = f.recover()
        val correct = outcome.candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(f.text, correct.text)
        assertEquals(4, correct.knownBitMismatchesAfterCorrection)
        assertEquals(11, correct.listDecodingMismatchLimit)
        assertNotNull(correct.actualBlockMethods.single().listDecoding)
        assertNull(correct.actualBlockMethods.single().rsMargin)
        assertTrue(correct.repairInfo.any { it.listDecoding != null })
        assertEquals(ListDecoderMethod.WU, correct.actualBlockMethods.single().listDecoding?.method)
        assertEquals(0, requireNotNull(outcome.searchDiagnostic.listDecoding).interpolations)
        assertTrue(requireNotNull(outcome.searchDiagnostic.listDecoding).methodTrials.getValue(ListDecoderMethod.WU) > 0)
        assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun fivePercentCapAppliesEvenWithUnlimitedAndSmallerManualCapsRemainBinding() {
        val f = Fixture()
        for (i in 2..5) f.flip(i, 15)
        val wide = f.recover()
        assertTrue(wide.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        assertTrue(RecoveryCandidateRejection.LIST_DECODING_MISMATCH_LIMIT in wide.searchDiagnostic.candidateRejections)
        val narrow = Fixture()
        for (i in 2..5) narrow.flip(i, 1)
        assertTrue(narrow.recover(limit = 3).candidates.none { it.correctedInterleaved.contentEquals(narrow.raw) })
        assertTrue(narrow.recover(limit = 4).candidates.any { it.correctedInterleaved.contentEquals(narrow.raw) })
    }

    @Test fun unknownBitSearchEnablesListDecodingDespiteTheOriginalErasureCount() {
        val f = Fixture()
        for (raw in 2..8) {
            val coord = f.extraction.codewords[raw].coordinates[7]
            f.matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }
        for (raw in 24..25) for (coord in f.extraction.codewords[raw].coordinates) {
            f.matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }
        for (raw in 9..11) f.flip(raw, 1)
        assertTrue(f.recover(budget = ListDecodingBudget(0)).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        val outcome = f.recover()
        val restored = outcome.candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(f.text, restored.text)
        assertEquals(3, restored.knownBitMismatchesAfterCorrection)
        assertEquals(7, restored.partialGuessedBits)
        assertEquals(2, restored.actualBlockMethods.single().listDecoding?.remainingErasures)
        assertTrue(ListDecodingTrialSkip.TOO_MANY_ERASURES in requireNotNull(outcome.searchDiagnostic.listDecoding).skipReasons)
    }

    @Test fun multipleBlocksCanNeedListDecodingAndTheirMismatchesShareOneQrCap() {
        val f = Fixture(3, ErrorCorrectionLevel.Q)
        for ((rawIndex, location) in f.de.rawLocations.withIndex()) {
            if (requireNotNull(location).localIndex in 2..11) f.flip(rawIndex, 1)
        }
        val result = f.recover()
        val correct = result.candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(20, correct.knownBitMismatchesAfterCorrection)
        assertEquals(2, correct.actualBlockMethods.count { it.listDecoding != null })
        assertTrue(f.recover(limit = 19).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
    }

    @Test fun fixedCellsRemainProtectedDuringListDecoding() {
        val f = Fixture()
        f.flip(2, 1, ModuleSource.USER)
        for (i in 3..5) f.flip(i, 1)
        val outcome = f.recover()
        assertTrue(outcome.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        assertTrue(RecoveryCandidateRejection.FIXED_CELLS in outcome.searchDiagnostic.candidateRejections)
    }

    @Test fun rejectingATemporaryPriorityValueDoesNotBlameFixedInputCells() {
        val f = Fixture()
        for (i in 2..5) f.flip(i, 1)
        val priority = f.extraction.codewords[2].coordinates[7]
        f.matrix[priority.x, priority.y].source = ModuleSource.USER
        val extraction = CodewordExtractor.extract(f.matrix, f.version, f.qr.maskPattern)
        val trial = QrBlockInterleaver.deinterleave(extraction, f.version, f.ecc)
        val original = QrBlockInterleaver.deinterleave(extraction, f.version, f.ecc)
        original.blocks.single().unknownMasks[2] = 1
        val rejections = linkedSetOf<RecoveryCandidateRejection>()
        var decoderTrials = 0
        var acceptedConflictingValue = false
        ListDecodingQrSearch(ReedSolomonErasures()).run(trial, original, f.matrix, extraction, setOf(priority),
            f.version, 3, false, listOf(emptySet()), 12, 32L * 1024 * 1024,
            RecoveryCancellation.NONE, onRejection = { rejections += it },
            onProgress = { _, search -> decoderTrials = search.methodTrials.values.sum() }, onStops = {},
        ) { combination ->
            if (combination.single().corrected.contentEquals(f.raw)) acceptedConflictingValue = true
            false
        }
        assertTrue(decoderTrials > 0)
        assertFalse(acceptedConflictingValue)
        assertFalse(RecoveryCandidateRejection.FIXED_CELLS in rejections)
    }

    @Test fun aMiscorrectedOrdinaryBlockStillSuppliesItsOtherListCandidates() {
        val f = Fixture(5, ErrorCorrectionLevel.M)
        val block = f.de.blocks.first()
        val rs = ReedSolomonErasures()
        val wrongData = block.codewords.copyOf(block.numDataCodewords).also { it[0] = it[0] xor 0xD0 }
        val wrongWord = rs.encode(wrongData, f.de.ecSymbolsPerBlock)
        val differing = block.codewords.indices.filter { block.codewords[it] != wrongWord[it] }
        assertEquals(f.de.ecSymbolsPerBlock + 1, differing.size)
        val radius = f.de.ecSymbolsPerBlock / 2 + 1
        // Include an invalid mode, then choose the lightest parity changes to stay under 5%.
        val changed = listOf(0) + differing.filter { it != 0 }
            .sortedBy { (block.codewords[it] xor wrongWord[it]).countOneBits() }.take(radius - 1)
        val received = block.codewords.copyOf()
        for (local in changed) {
            val raw = f.de.rawLocations.indexOfFirst { it?.blockIndex == 0 && it.localIndex == local }
            val xor = block.codewords[local] xor wrongWord[local]
            f.flip(raw, xor)
            received[local] = wrongWord[local]
        }
        assertArrayEquals(wrongWord, rs.decode(received, f.de.ecSymbolsPerBlock, intArrayOf()).corrected)
        assertTrue(f.recover(budget = ListDecodingBudget(0)).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        val restored = f.recover().candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(f.text, restored.text)
        assertNotNull(restored.actualBlockMethods.first().listDecoding)
    }

    @Test fun anExhaustedSharedBudgetDoesNotPreventLaterOrdinaryRecovery() {
        val damaged = Fixture()
        for (i in 2..5) damaged.flip(i, 1)
        var clock = 0L
        val budget = ListDecodingBudget(50, clockMillis = { clock++ })
        assertTrue(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in damaged.recover(budget = budget).searchDiagnostic.incompleteReasons)
        val clean = Fixture()
        val outcome = clean.recover(budget = budget)
        assertTrue(outcome.candidates.any { it.text == clean.text })
        assertNull(outcome.searchDiagnostic.listDecoding)
        assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun ordinarySuccessDoesNotSpendTheListDecodingBudget() {
        val f = Fixture()
        val budget = ListDecodingBudget(0)
        val outcome = f.recover(budget = budget)
        assertTrue(outcome.candidates.any { it.text == f.text })
        assertNull(outcome.searchDiagnostic.listDecoding)
        assertEquals(0L, budget.elapsedMillis)
    }

    @Test fun deadlineAndStopButtonHaveDistinctDiagnostics() {
        val f = Fixture()
        for (i in 2..5) f.flip(i, 1)
        var clock = 0L
        val deadline = f.recover(budget = ListDecodingBudget(50, clockMillis = { clock++ }))
        assertTrue(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in deadline.searchDiagnostic.incompleteReasons)
        assertFalse(SearchIncompleteReason.INTERNAL_ERROR in deadline.searchDiagnostic.incompleteReasons)
        var started = false
        val stopped = f.recover(budget = ListDecodingBudget(onProgress = { _, _ -> started = true }), stop = { started })
        assertTrue(SearchIncompleteReason.USER_INTERRUPTED in stopped.searchDiagnostic.incompleteReasons)
        assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in stopped.searchDiagnostic.incompleteReasons)
    }

    @Test fun kvAutomaticallyAddsADecodedCandidateBeforeRepeatingUnknownBitAssignments() {
        val f = Fixture(1, ErrorCorrectionLevel.H, "KV RECOVER")
        for (raw in 0 until 26) {
            val coord = f.extraction.codewords[raw].coordinates[7]
            f.matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        }
        f.flip(0, 128)
        f.flip(1, 128)
        assertTrue(f.recover(budget = ListDecodingBudget(0)).candidates.none { it.payloadParsed })
        val outcome = f.recover()
        assertFalse(SearchIncompleteReason.INTERNAL_ERROR in outcome.searchDiagnostic.incompleteReasons)
        val restored = outcome.candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(f.text, restored.text)
        assertEquals(0, restored.partialGuessedBits)
        assertEquals(2, restored.knownBitMismatchesAfterCorrection)
        assertEquals(ListDecoderMethod.KOETTER_VARDY, restored.actualBlockMethods.single().listDecoding?.method)
        assertTrue(requireNotNull(outcome.searchDiagnostic.listDecoding).methodTrials.getValue(ListDecoderMethod.KOETTER_VARDY) > 0)
        val smallMemory = f.recover(workingLimit = 1024L * 1024)
        assertTrue(smallMemory.candidates.any { it.correctedInterleaved.contentEquals(f.raw) })
        f.matrix[f.extraction.codewords[0].coordinates[0].x, f.extraction.codewords[0].coordinates[0].y].source = ModuleSource.USER
        val pinned = f.recover()
        assertTrue(pinned.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        assertTrue(RecoveryCandidateRejection.FIXED_CELLS in pinned.searchDiagnostic.candidateRejections)
    }
}
