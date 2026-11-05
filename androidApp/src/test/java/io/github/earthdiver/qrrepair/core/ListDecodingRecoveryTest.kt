package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ListDecodingRecoveryTest {
    @Test fun selectedExtensionRecoversTwoAndThreeExtraErrorsWithoutAssignments() {
        for ((ecc, extra) in listOf(ErrorCorrectionLevel.H to 2, ErrorCorrectionLevel.H to 3, ErrorCorrectionLevel.Q to 2)) {
            val f = Fixture(ecc = ecc, text = "RADIUS")
            val radius = f.de.ecSymbolsPerBlock / 2 + extra
            val data = f.de.blocks.single().numDataCodewords
            for (i in data until data + radius) f.flip(i, 1)
            val smaller = f.recover(additionalErrors = extra - 1)
            assertTrue(smaller.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
            val result = f.recover(additionalErrors = extra)
            val correct = result.candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
            assertEquals(f.text, correct.text)
            assertEquals(0, correct.partialGuessedBits)
            assertEquals(extra, correct.displayConditions!!.listDecodingAdditionalErrors)
            assertEquals(radius, correct.actualBlockMethods.single().listDecoding!!.radius)
            val summary = requireNotNull(result.searchDiagnostic.listDecoding)
            assertEquals(setOf(extra), summary.extensionLimits)
            assertTrue(summary.completedHardRadiusTrials.getOrDefault(radius, 0) > 0)
            assertTrue(summary.hardRadiusTrials.keys.all { it <= radius })
            if (ecc == ErrorCorrectionLevel.Q) assertEquals(ListDecoderMethod.WU, correct.actualBlockMethods.single().listDecoding!!.method)
            assertTrue(f.recover(limit = radius - 1, additionalErrors = extra).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
            val coord = f.extraction.codewords[data].coordinates.last()
            f.matrix[coord.x, coord.y].source = ModuleSource.USER
            assertTrue(f.recover(additionalErrors = extra).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        }
    }

    @Test fun widerMultipleBlocksStillShareTheQrMismatchLimit() {
        val f = Fixture(3, ErrorCorrectionLevel.Q)
        // Parity errors cannot be removed by payload-suffix propagation.
        for ((raw, location) in f.de.rawLocations.withIndex()) {
            val loc = requireNotNull(location)
            val data = f.de.blocks[loc.blockIndex].numDataCodewords
            if (loc.localIndex in data until data + 11) f.flip(raw, 1)
        }
        assertTrue(f.recover(additionalErrors = 1).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        val restored = f.recover(additionalErrors = 2).candidates.single { it.correctedInterleaved.contentEquals(f.raw) }
        assertEquals(f.text, restored.text)
        assertEquals(22, restored.knownBitMismatchesAfterCorrection)
        assertTrue(restored.actualBlockMethods.all { it.listDecoding?.radius == 11 })
        assertTrue(f.recover(limit = 21, additionalErrors = 2).candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
    }

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
            additionalErrors: Int = ListDecodingLimit.DEFAULT, stop: () -> Boolean = { false }) =
            RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = constraint,
                maxKnownDataEccMismatches = limit, listDecodingBudget = budget, partialBitSearchLimit = partialBitLimit,
                listDecodingAdditionalErrors = additionalErrors,
                retentionBudget = RecoveryRetentionBudget(workingLimitBytes = workingLimit), stopRequested = stop)
    }

    @Test fun disablingListsPreventsListRecoveryButKeepsConventionalCorrection() {
        for (errors in listOf(3, 4)) {
            val f = Fixture()
            for (i in 2 until 2 + errors) f.flip(i, 1)
            val budget = ListDecodingBudget(0)
            val outcome = f.recover(additionalErrors = 0, budget = budget)
            assertEquals(errors == 3, outcome.candidates.any { it.correctedInterleaved.contentEquals(f.raw) })
            assertTrue(outcome.candidates.all { it.actualBlockMethods.all { method -> method.listDecoding == null } })
            assertNull(outcome.searchDiagnostic.listDecoding)
            assertTrue(outcome.searchDiagnostic.listDecodingExecution!!.stages.values.all {
                it == setOf(ListDecodingStatus.DISABLED)
            })
            assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
            assertEquals(0L, budget.elapsedMillis)
            if (errors == 4) assertTrue(f.recover().candidates.any { it.correctedInterleaved.contentEquals(f.raw) })
        }
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
        assertEquals(1, requireNotNull(outcome.searchDiagnostic.listDecoding).trials)
        assertEquals(mapOf(ListDecoderMethod.WU to 1), requireNotNull(outcome.searchDiagnostic.listDecoding).methodTrials)
        assertEquals(1, requireNotNull(outcome.searchDiagnostic.listDecoding).reusedTrials)
        val execution = requireNotNull(outcome.searchDiagnostic.listDecodingExecution)
        assertEquals(mapOf(ListDecodingStage.WITHOUT_ASSIGNMENTS to 1, ListDecodingStage.ADDITIONAL to 0),
            execution.newTrials)
        assertTrue(execution.stages.values.all {
            it == setOf(ListDecodingStatus.COMPLETED)
        })
        assertTrue(correct.combinationSearch.stops.isEmpty())
        assertFalse(SearchIncompleteReason.LIST_DECODING_TIME_LIMIT in outcome.searchDiagnostic.incompleteReasons)
    }

    @Test fun ineligibleInputsReportBothListStagesAsUnexecutedWithZeroTrials() {
        val f = Fixture(text = "NO LIST TRIALS")
        for (raw in 2..19) {
            for (coord in f.extraction.codewords[raw].coordinates.take(if (raw < 16) 8 else 4)) {
                f.matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        val outcome = f.recover()
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.incompleteReasons.isEmpty())
        val summary = requireNotNull(outcome.searchDiagnostic.listDecoding)
        assertEquals(0, summary.trials)
        assertEquals(0, summary.reusedTrials)
        assertTrue(summary.methodTrials.isEmpty())
        assertTrue(summary.skipReasons.containsAll(setOf(ListDecodingTrialSkip.TOO_MANY_ERASURES,
            ListDecodingTrialSkip.KV_PARAMETERS_UNAVAILABLE)))
        val execution = requireNotNull(outcome.searchDiagnostic.listDecodingExecution)
        for (stage in ListDecodingStage.entries) {
            assertEquals(setOf(ListDecodingStatus.INELIGIBLE), execution.stages[stage])
        }
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
        // Keep the errors outside the suffix that structural propagation can repair.
        for (raw in 19..21) f.flip(raw, 1)
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
            val loc = requireNotNull(location)
            val data = f.de.blocks[loc.blockIndex].numDataCodewords
            if (loc.localIndex in data until data + 10) f.flip(rawIndex, 1)
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
        assertTrue(outcome.searchDiagnostic.listDecodingExecution!!.stages.values.all {
            it == setOf(ListDecodingStatus.ORIGINAL_RS_SUCCEEDED)
        })
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

    @Test fun findingAPayloadStillReportsPendingAssignmentTrials() {
        val f = Fixture(1, ErrorCorrectionLevel.H, "KV RECOVER")
        for (word in f.extraction.codewords) {
            val coord = word.coordinates.last()
            f.matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        f.flip(0, 128)
        f.flip(1, 128)
        val extraction = CodewordExtractor.extract(f.matrix, 1, 0)
        val de = QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.H)
        val stops = linkedSetOf<CombinationSearchStop>()
        var assignmentsRemain = false
        val parsed = ListDecodingQrSearch(ReedSolomonErasures()).run(de, de, f.matrix, extraction,
            emptySet(), 1, 2, true, listOf(emptySet()), 12, 32L * 1024 * 1024, RecoveryCancellation.NONE,
            onRejection = {}, onProgress = { _, search ->
                if (ListDecoderMethod.KOETTER_VARDY in search.methodTrials) assignmentsRemain = search.hasNext
            }, onStops = { stops += it },
        ) { it.single().corrected.contentEquals(f.raw) }
        assertTrue(parsed)
        assertTrue(assignmentsRemain)
        assertEquals(setOf(CombinationSearchStop.SUCCESS_WINDOW), stops)
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
        assertEquals(setOf(ListDecodingStatus.COMPLETED),
            outcome.searchDiagnostic.listDecodingExecution?.stages?.get(ListDecodingStage.WITHOUT_ASSIGNMENTS))
        val smallMemory = f.recover(workingLimit = 1024L * 1024)
        assertTrue(smallMemory.candidates.any { it.correctedInterleaved.contentEquals(f.raw) })
        f.matrix[f.extraction.codewords[0].coordinates[0].x, f.extraction.codewords[0].coordinates[0].y].source = ModuleSource.USER
        val pinned = f.recover()
        assertTrue(pinned.candidates.none { it.correctedInterleaved.contentEquals(f.raw) })
        assertTrue(RecoveryCandidateRejection.FIXED_CELLS in pinned.searchDiagnostic.candidateRejections)
    }
}
