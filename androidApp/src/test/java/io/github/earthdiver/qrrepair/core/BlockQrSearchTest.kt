package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class BlockQrSearchTest {
    @Test fun lazilyAnalyzedSpacesChargeTheirCacheBeforeBlockAllocation() {
        val fixture = Fixture(eraseWords(validMatrix(), 1))
        val solver = RsBitConstraintSolver(maximumCacheBytes = 128L * 1024)
        val blockAllowance = blockCandidateWorkingBytes(fixture.blocks.blocks.single(), 0,
            false, emptySet(), false, fixture.blocks.ecSymbolsPerBlock,
            fixture.conventional.blocks.single().margin)
        val spaces by lazy { fixture.blocks.blocks.map { solver.analyze(it, fixture.blocks.ecSymbolsPerBlock) } }
        assertEquals(0L, solver.cacheWorkingBytes)

        val outcome = fixture.search(trialSpaces = { spaces },
            availableWorkingBytes = { blockAllowance - solver.cacheWorkingBytes })

        assertTrue(solver.cacheWorkingBytes > 0)
        assertEquals(BlockQrSearch.Failure.Block(0, 1, RsFailureReason.BIT_SPACE_LIMIT, 0), outcome.failure)
        assertFalse(outcome.parsed)
        assertEquals(1, fixture.blockAttempts)
        assertEquals(0, fixture.evaluations)
        assertTrue(fixture.completeness.isEmpty())
        assertEquals(listOf(setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT)), fixture.passes)
    }

    @Test fun basicRsPassAvoidsExactSpacesAndRequiresTheEvaluatorsParsedResult() {
        for (parsed in listOf(false, true)) {
            val fixture = Fixture()
            val outcome = fixture.search(input = fixture.input.copy(basicOnly = true),
                trialSpaces = { error("A basic pass must not analyze exact spaces") },
                evaluate = { combination ->
                    assertArrayEquals(fixture.blocks.blocks.single().codewords, combination.single().corrected)
                    parsed
                })

            assertEquals(parsed, outcome.parsed)
            assertEquals(if (parsed) null else BlockQrSearch.Failure.Payload(emptySet()), outcome.failure)
            assertEquals(1, fixture.blockAttempts)
            assertEquals(1, fixture.evaluations)
            assertEquals(listOf(SearchCompleteness.COMPLETE), fixture.completeness)
            assertEquals(listOf(emptySet<CombinationSearchStop>()), fixture.passes)
        }
    }

    @Test fun invalidQrPrefixesAreRejectedEvenWhenTheirRsParityIsValid() {
        val fixture = Fixture(postTerminatorMatrix(ByteArray(19) { 0xF0.toByte() }))
        assertTrue(fixture.conventional.succeeded)
        val rejections = mutableListOf<RecoveryCandidateRejection>()

        val outcome = fixture.search(payloadChecks = RecoveryPayloadChecks(onRejection = rejections::add))

        assertEquals(BlockQrSearch.Failure.Payload(emptySet()), outcome.failure)
        assertFalse(outcome.parsed)
        assertEquals(0, fixture.evaluations)
        assertEquals(listOf(RecoveryCandidateRejection.QR_STRUCTURE), rejections)
        assertEquals(listOf(emptySet<CombinationSearchStop>()), fixture.passes)
    }

    @Test fun cancellationRetainsStartedBlockAndCompletedSolverEvidenceWithoutCompletingAPass() {
        val beforeBlock = Fixture()
        assertThrows(RecoveryCancelledException::class.java) {
            beforeBlock.search(cancellation = RecoveryCancellation { beforeBlock.blockAttempts > 0 })
        }
        assertEquals(1, beforeBlock.blockAttempts)
        assertEquals(0, beforeBlock.evaluations)
        assertTrue(beforeBlock.completeness.isEmpty())
        assertTrue(beforeBlock.passes.isEmpty())

        val duringEvaluation = Fixture()
        assertThrows(RecoveryCancelledException::class.java) {
            duringEvaluation.search(evaluate = { throw RecoveryCancelledException() })
        }
        assertEquals(1, duringEvaluation.blockAttempts)
        assertEquals(1, duringEvaluation.evaluations)
        assertEquals(listOf(SearchCompleteness.COMPLETE), duringEvaluation.completeness)
        assertTrue(duringEvaluation.passes.isEmpty())
    }

    private class Fixture(val matrix: QrMatrix = validMatrix()) {
        val extraction = CodewordExtractor.extract(matrix, 1, 0)
        val blocks = QrBlockInterleaver.deinterleave(extraction, 1, ErrorCorrectionLevel.L)
        val conventional = ConventionalRsDiagnostic.analyze(blocks)
        val input = BlockQrSearch.Input(blocks, 1, matrix, extraction, conventional, emptySet(),
            RecoveryPhase.ORIGINAL_RS, basicOnly = false, allowBitEnumeration = false, exactOnly = false,
            partialBitSearchLimit = PartialBitSearchLimit.DEFAULT, useConventionalMargins = true)
        var blockAttempts = 0
        var evaluations = 0
        val completeness = mutableListOf<SearchCompleteness>()
        val passes = mutableListOf<Set<CombinationSearchStop>>()

        fun search(
            input: BlockQrSearch.Input = this.input,
            trialSpaces: () -> List<RsBitConstraintSolver.Space?> = {
                blocks.blocks.map { RsBitConstraintSolver().analyze(it, blocks.ecSymbolsPerBlock) }
            },
            availableWorkingBytes: () -> Long = { 32L * 1024 * 1024 },
            cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
            payloadChecks: RecoveryPayloadChecks = RecoveryPayloadChecks(),
            evaluate: (List<PartialCodewordSolver.Result>) -> Boolean = { true },
        ): BlockQrSearch.Outcome = BlockQrSearch(ReedSolomonErasures()).run(input, trialSpaces,
            { error("Only image retries use cached original blocks") },
            availableWorkingBytes, cancellation, payloadChecks,
            onBlockAttempt = { blockAttempts++ }, onSearch = completeness::add, onStops = { passes += it.toSet() },
        ) { combination -> evaluations++; evaluate(combination) }
    }

    private companion object {
        fun validMatrix() = postTerminatorMatrix(PayloadBits().apply {
            put(4, 4); put(1, 8); put('A'.code, 8)
        }.finish())

        fun eraseWords(matrix: QrMatrix, count: Int): QrMatrix = matrix.apply {
            CodewordExtractor.extract(matrix, 1, 0).codewords.take(count).forEach { word ->
                word.coordinates.forEach { coord -> set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
            }
        }
    }
}
