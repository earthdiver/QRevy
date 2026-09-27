package io.github.earthdiver.qrrepair.core

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class RecoveryCandidateEvaluatorTest {
    @Test fun validParityDoesNotMakeAnInvalidQrPayloadAcceptable() {
        val fixture = Fixture(ByteArray(19) { 0xF0.toByte() })
        assertArrayEquals(fixture.raw, ReedSolomonErasures().decode(fixture.raw.copyOf(), 7).corrected)

        val rejected = fixture.evaluate() as RecoveryCandidateEvaluation.Rejected

        assertEquals(RecoveryCandidateRejection.QR_STRUCTURE, rejected.reason)
        assertNull(rejected.failure)
    }

    @Test fun explicitSingleSegmentConditionIsCheckedBeforeQrStructure() {
        val fixture = Fixture(ByteArray(19) { 0xF0.toByte() })
        val constraints = PayloadConstraintSolver.Result(0, 1, 4, 1,
            trustedStructure = PayloadConstraintSolver.TrustedStructure(4, 1, 12, 20, 152))

        val rejected = fixture.evaluate(constraints = constraints, trackSingleSegment = true)
            as RecoveryCandidateEvaluation.Rejected

        assertEquals(RecoveryCandidateRejection.SINGLE_SEGMENT_STRUCTURE, rejected.reason)
    }

    @Test fun fixedCellConflictsTakePrecedenceOverTheKnownBitLimit() {
        val fixture = Fixture()
        val observed = fixture.matrix.copy()
        val coord = fixture.coordinate
        observed.set(coord.x, coord.y, fixture.opposite, 1f, ModuleSource.USER)

        val rejected = fixture.evaluate(observed, limit = 0) as RecoveryCandidateEvaluation.Rejected

        assertEquals(RecoveryCandidateRejection.FIXED_CELLS, rejected.reason)
        val failure = requireNotNull(rejected.failure)
        assertEquals(RecoveryFailureReason.MANUAL_PIN_CONFLICT, failure.reason)
        assertEquals(listOf(ManualCellConflict(coord, fixture.opposite, fixture.expected)), failure.manualConflicts)
        assertNull(failure.knownBitMismatches)
        assertSame(fixture.conventional, failure.conventionalRsDiagnostic)
    }

    @Test fun conflictingPriorityGuessIsNotReportedAsAnOriginalFixedCellConflict() {
        val fixture = Fixture()
        val observed = fixture.matrix.copy()
        val coord = fixture.coordinate
        observed.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
        observed.setPrioritySearch(coord.x, coord.y, true)
        val trial = observed.copy().apply { set(coord.x, coord.y, fixture.opposite, 1f, ModuleSource.USER) }

        assertEquals(RecoveryCandidateEvaluation.UnresolvedPriorityAssignment,
            fixture.evaluate(observed, trial, priority = mapOf(coord to fixture.opposite)))
        assertEquals(ModuleValue.ERASED, observed[coord.x, coord.y].value)
        assertTrue(observed[coord.x, coord.y].prioritySearch)
    }

    @Test fun acceptedPriorityGuessPreservesOriginalObservationAndRepairEvidence() {
        val fixture = Fixture()
        val coord = fixture.coordinate
        val observed = fixture.matrix.copy().apply {
            set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
            setPrioritySearch(coord.x, coord.y, true)
        }
        val trial = observed.copy().apply { set(coord.x, coord.y, fixture.expected, 1f, ModuleSource.USER) }
        val accepted = fixture.evaluate(observed, trial, priority = mapOf(coord to fixture.expected))
            as RecoveryCandidateEvaluation.Accepted
        val candidate = accepted.candidate

        assertFalse(accepted.decoderFailed)
        assertEquals("A", candidate.text)
        assertArrayEquals(fixture.raw, candidate.correctedInterleaved)
        assertEquals(207, candidate.knownBitsCompared)
        assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
        assertEquals(1, candidate.originalErasedCodewords)
        assertEquals(0, candidate.erasedCodewords)
        assertEquals(128, candidate.extraction.codewords[0].unknownBits)
        assertEquals(128, candidate.repairInfo[0].originalUnknownMask)
        assertEquals(0, candidate.repairInfo[0].postConstraintUnknownMask)
        assertEquals(mapOf(coord to fixture.expected), candidate.priorityAssignments)
        assertSame(fixture.conventional, candidate.conventionalRsDiagnostic)
        assertSame(fixture.bitDiagnostic, candidate.rsBitDiagnostic)
    }

    @Test fun mismatchLimitUsesOriginalImageEvidenceAndKeepsFailureDetails() {
        val fixture = Fixture()
        val observed = fixture.matrix.copy()
        val coord = fixture.coordinate
        observed.set(coord.x, coord.y, fixture.opposite, 1f, ModuleSource.IMAGE)

        val rejected = fixture.evaluate(observed, limit = 0) as RecoveryCandidateEvaluation.Rejected
        val failure = requireNotNull(rejected.failure)
        assertEquals(RecoveryCandidateRejection.MISMATCH_LIMIT, rejected.reason)
        assertEquals(RecoveryFailureReason.KNOWN_DATA_ECC_MISMATCH_LIMIT, failure.reason)
        assertEquals(208, failure.knownBitsCompared)
        assertEquals(1, failure.knownBitMismatches)
        assertEquals(0, failure.knownBitMismatchLimit)
        assertEquals(1, failure.rsBlockCount)
        assertEquals(7, failure.ecSymbolsPerBlock)
        assertSame(fixture.bitDiagnostic, failure.rsBitDiagnostic)
        val accepted = fixture.evaluate(observed, limit = 1) as RecoveryCandidateEvaluation.Accepted
        assertEquals(1, accepted.candidate.knownBitMismatchesAfterCorrection)
        assertEquals("A", accepted.candidate.text)
    }

    @Test fun forcedBitsKeepTheirValuesAndReasonsInDeferredProvenance() {
        val fixture = Fixture()
        val coord = fixture.coordinate
        val observed = fixture.matrix.copy().apply { set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        val forcedBit = (fixture.raw[0] ushr 7) and 1
        val constraints = PayloadConstraintSolver.Result(1, 1, 4, 1,
            filled = listOf(PayloadConstraintSolver.FilledBit(0, 0, 0, 0, forcedBit,
                PayloadConstraintSolver.ConstraintKind.MODE)), forcedByTrustedHeader = true)

        val accepted = fixture.evaluate(observed, fixture.matrix, constraints = constraints)
            as RecoveryCandidateEvaluation.Accepted
        val candidate = accepted.candidate
        val info = candidate.repairInfo[0]

        assertEquals(1, candidate.constraintFilledBits)
        assertTrue(candidate.constraintForcedByUserSetting)
        assertEquals(128, info.originalUnknownMask)
        assertEquals(0, info.postConstraintUnknownMask)
        assertEquals(mapOf(0 to PayloadConstraintSolver.ConstraintKind.MODE), info.constraintKindsByBit)
        assertEquals(mapOf(0 to forcedBit), info.constraintValuesByBit)
    }

    @Test fun equivalentRoutesRemainBoundedWithoutChangingTheSelectedRepairExplanation() {
        val fixture = Fixture()
        val selected = fixture.combination.single().copy(errataPositions = intArrayOf(0))
        val alternate = selected.derivation().copy(guessedBits = 1, guessedPositions = listOf(0))
        val combination = listOf(selected.copy(alternativeDerivations = listOf(alternate, alternate.copy(guessedBits = 2))))

        val accepted = fixture.evaluate(combination = combination, alternativeLimit = 1)
            as RecoveryCandidateEvaluation.Accepted
        val candidate = accepted.candidate

        assertTrue(candidate.derivationsOmitted)
        assertEquals(listOf(selected.derivation(), alternate), candidate.blockDerivations[0])
        assertTrue(candidate.repairInfo is DeferredRepairInfo)
        assertTrue(candidate.repairInfo[0].rsErrata)
        assertFalse(candidate.repairInfo[0].partialEnumerated)
    }

    private class Fixture(data: ByteArray = PayloadBits().apply {
        put(4, 4); put(1, 8); put('A'.code, 8)
    }.finish()) {
        val raw = IntArray(26) { if (it < data.size) data[it].toInt() and 255 else 0 }.also {
            ReedSolomonEncoder(GenericGF.QR_CODE_FIELD_256).encode(it, 7)
        }
        val matrix = QrMatrix(21).apply {
            StructuralRepair.apply(this, ErrorCorrectionLevel.L, 0)
            CodewordExtractor.extract(this, 1, 0).codewords.forEachIndexed { index, word ->
                word.coordinates.forEachIndexed { bit, coord ->
                    val value = ((raw[index] ushr (7 - bit)) and 1) xor
                        if (CodewordExtractor.maskApplies(0, coord.x, coord.y)) 1 else 0
                    set(coord.x, coord.y, if (value == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                }
            }
        }
        val coordinate = CodewordExtractor.extract(matrix, 1, 0).codewords[0].coordinates[0]
        val expected = matrix[coordinate.x, coordinate.y].value
        val opposite = if (expected == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE
        val conventional = ConventionalRsDiagnostic(emptyList(), null)
        val bitDiagnostic = RsBitDiagnostic(0, 0, 0, 0, emptyList())
        val combination = listOf(PartialCodewordSolver.Result(raw, intArrayOf(), 0, 0, intArrayOf(), 7))

        fun evaluate(
            observed: QrMatrix = matrix,
            trial: QrMatrix = observed,
            limit: Int? = null,
            constraints: PayloadConstraintSolver.Result = PayloadConstraintSolver.Result(0, 0, null, null),
            trackSingleSegment: Boolean = false,
            priority: Map<Coord, ModuleValue> = emptyMap(),
            combination: List<PartialCodewordSolver.Result> = this.combination,
            alternativeLimit: Int = 2,
        ): RecoveryCandidateEvaluation {
            val extraction = CodewordExtractor.extract(trial, 1, 0)
            val evidence = CodewordExtractor.extract(observed, 1, 0)
            val de = QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.L)
            return RecoveryCandidateEvaluator(
                version = 1,
                format = FormatSolver.ranked(matrix).first { it.ecLevel == ErrorCorrectionLevel.L && it.dataMask == 0 },
                formatRoute = FormatSelectionReason.SEARCH,
                matrix = trial,
                diagnosticInput = observed,
                extraction = extraction,
                evidenceExtraction = evidence,
                de = de,
                constraints = constraints,
                trackSingleSegment = trackSingleSegment,
                singleSegmentStatus = null,
                priorityAssignments = priority,
                conventionalDiagnostic = conventional,
                bitDiagnostic = bitDiagnostic,
                originalErasures = evidence.codewords.count { it.unknownBits != 0 },
                constrainedErasures = de.blocks.sumOf { block -> block.unknownMasks.count { it != 0 } },
                rsShortfallBits = minimumRsShortfallBits(de.blocks, de.ecSymbolsPerBlock),
                maxKnownDataEccMismatches = limit,
                maxAlternativeDerivations = alternativeLimit,
            ).evaluate(combination)
        }
    }
}
