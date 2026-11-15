package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class ExactFormatExclusionTest {
    private fun matrix(text: String, level: ErrorCorrectionLevel): QrMatrix {
        val encoded = Encoder.encode(text, level,
            mapOf(EncodeHintType.QR_VERSION to 4, EncodeHintType.QR_MASK_PATTERN to 2)).matrix
        return QrMatrix(encoded.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }

    private fun flip(matrix: QrMatrix, cell: Coord) {
        matrix.set(cell.x, cell.y, if (matrix[cell.x, cell.y].value == ModuleValue.ONE)
            ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
    }

    @Test fun contradictoryBlocksExcludeTheFormatRegardlessOfFreeBlockOrder() {
        for (badBlock in 0..1) {
            val input = matrix("a".repeat(60), ErrorCorrectionLevel.M)
            val extraction = CodewordExtractor.extract(input, 4, 2)
            val de = QrBlockInterleaver.deinterleave(extraction, 4, ErrorCorrectionLevel.M)
            de.rawLocations.forEachIndexed { raw, location ->
                if (location!!.blockIndex != badBlock) extraction.codewords[raw].coordinates.forEach {
                    input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                }
            }
            val parity = de.rawLocations.indexOfFirst {
                it!!.blockIndex == badBlock && it.localIndex == de.blocks[badBlock].numDataCodewords
            }
            flip(input, extraction.codewords[parity].coordinates.first())
            val observed = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(input, 4, 2), 4, ErrorCorrectionLevel.M)
            val spaces = observed.blocks.map { RsBitConstraintSolver().analyze(it, observed.ecSymbolsPerBlock) }
            assertNull(spaces[badBlock])
            assertEquals(256, spaces[1 - badBlock]!!.dimension)
            for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXACT)) {
                val result = RecoveryEngine().recoverWithDiagnostics(input,
                    formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2),
                    maxKnownDataEccMismatches = 0, listDecodingAdditionalErrors = 0, executionPolicy = policy)
                val state = result.searchDiagnostic.formatOutcomes.single()
                assertEquals("$policy / block $badBlock: $state", RecoveryFormatState.EXCLUDED, state.state)
                assertEquals("NO_EXACT_SOLUTION", state.reason)
                assertFalse(result.searchDiagnostic.unresolvedCalculations)
                assertTrue(result.searchDiagnostic.incompleteReasons.isEmpty())
                assertTrue(result.candidates.isEmpty())
                assertFalse(QuickAutoRecovery.canReport(result))
            }
        }
    }

    @Test fun uniqueCandidateIsReportableWhenOtherFormatsHaveContradictoryAndFreeBlocks() {
        val input = matrix("STATE 42", ErrorCorrectionLevel.H)
        val extraction = CodewordExtractor.extract(input, 4, 2)
        val de = QrBlockInterleaver.deinterleave(extraction, 4, ErrorCorrectionLevel.H)
        // H can reconstruct ten erased words in each of these two blocks. Under M
        // the same erased cells include the header and prevent suffix inference;
        // they leave one free block while the fully observed other block contradicts RS.
        de.rawLocations.forEachIndexed { raw, location ->
            val loc = requireNotNull(location)
            if (loc.blockIndex % 2 == 0 && loc.localIndex < 10)
                extraction.codewords[raw].coordinates.forEach { input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        }
        val alternative = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(input, 4, 2), 4, ErrorCorrectionLevel.M)
        val spaces = alternative.blocks.map { RsBitConstraintSolver().analyze(it, alternative.ecSymbolsPerBlock) }
        assertTrue(spaces.any { it == null })
        assertTrue(spaces.any { it != null && it.dimension > 0 })
        FormatSolver.copies(input.dimension).flatten().forEach { (x, y) ->
            input.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXACT)) {
            val result = RecoveryEngine().recoverWithDiagnostics(input, maxKnownDataEccMismatches = 0,
                listDecodingAdditionalErrors = 0, executionPolicy = policy)
            assertEquals("STATE 42", result.candidates.single().text)
            assertEquals(RecoveryDiscoveryCount.Exact(1), result.discoveredCandidates)
            assertFalse(result.searchDiagnostic.toString(), result.searchDiagnostic.unresolvedCalculations)
            assertTrue(result.searchDiagnostic.formatOutcomes.filter { it.state != RecoveryFormatState.CANDIDATE }
                .all { it.state == RecoveryFormatState.EXCLUDED })
            assertTrue(QuickAutoRecovery.canReport(result))
        }
    }

    @Test fun aKnownBitConflictStillAllowsRepairWhenTheErrorLimitIsPositive() {
        val input = matrix("STATE 42", ErrorCorrectionLevel.M)
        flip(input, CodewordExtractor.extract(input, 4, 2).codewords.last().coordinates.last())
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_BASIC)) {
            val result = RecoveryEngine().recoverWithDiagnostics(input,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2),
                maxKnownDataEccMismatches = 1, listDecodingAdditionalErrors = 0, executionPolicy = policy)
            assertEquals("STATE 42", result.candidates.single().text)
            assertEquals(1, result.candidates.single().knownBitMismatchesAfterCorrection)
            assertTrue(QuickAutoRecovery.canReport(result))
        }
    }

    @Test fun aContradictorySingleSegmentSuffixExcludesEvenWhenAnotherBlockRemainsFree() {
        val input = matrix("a".repeat(60), ErrorCorrectionLevel.M)
        val extraction = CodewordExtractor.extract(input, 4, 2)
        val de = QrBlockInterleaver.deinterleave(extraction, 4, ErrorCorrectionLevel.M)
        val block = de.blocks[1]
        val suffix = block.numDataCodewords - 1
        val changedData = block.codewords.copyOf(block.numDataCodewords).apply { this[suffix] = this[suffix] xor 1 }
        val changed = ReedSolomonErasures().encode(changedData, de.ecSymbolsPerBlock)
        de.rawLocations.forEachIndexed { raw, location ->
            val loc = requireNotNull(location)
            val cells = extraction.codewords[raw].coordinates
            if (loc.blockIndex == 1) {
                val difference = block.codewords[loc.localIndex] xor changed[loc.localIndex]
                cells.forEachIndexed { bit, cell -> if (difference and (1 shl (7 - bit)) != 0) flip(input, cell) }
            }
            if ((loc.blockIndex == 0 && loc.localIndex >= 2) || (loc.blockIndex == 1 && loc.localIndex == suffix))
                cells.forEach { input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        }
        val observed = QrBlockInterleaver.deinterleave(CodewordExtractor.extract(input, 4, 2), 4, ErrorCorrectionLevel.M)
        assertTrue(observed.blocks.all { RsBitConstraintSolver().analyze(it, observed.ecSymbolsPerBlock) != null })
        val inferred = observed.copyDeep()
        val constraints = PayloadConstraintSolver.apply(inferred, 4, trustKnownCount = true)
        assertTrue(constraints.filledBits > 0)
        assertTrue(RsBitConstraintSolver().analyze(inferred.blocks[0], inferred.ecSymbolsPerBlock)!!.dimension > 0)
        assertNull(RsBitConstraintSolver().analyze(inferred.blocks[1], inferred.ecSymbolsPerBlock))
        for (policy in listOf(RecoveryExecutionPolicy.DETAILED, RecoveryExecutionPolicy.AUTOMATIC_EXACT)) {
            val result = RecoveryEngine().recoverWithDiagnostics(input, trustKnownPayloadCount = true,
                formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2),
                maxKnownDataEccMismatches = 0, listDecodingAdditionalErrors = 0, executionPolicy = policy)
            assertEquals(RecoveryFormatState.EXCLUDED, result.searchDiagnostic.formatOutcomes.single().state)
            assertFalse(result.searchDiagnostic.unresolvedCalculations)
            assertTrue(result.candidates.isEmpty())
        }
        // Without the single-segment condition, the free original input must stay unresolved.
        val unconstrained = RecoveryEngine().recoverWithDiagnostics(input,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2),
            maxKnownDataEccMismatches = 0, listDecodingAdditionalErrors = 0,
            executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXACT)
        assertTrue(unconstrained.searchDiagnostic.unresolvedCalculations)
        assertFalse(QuickAutoRecovery.canReport(unconstrained))
    }
}
