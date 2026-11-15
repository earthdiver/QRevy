package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class AutomaticExactSpaceTest {
    private val format = QrFormatConstraint(ErrorCorrectionLevel.L, 3)

    private fun erasedPayloadBitAndParity(): QrMatrix {
        val encoded = Encoder.encode("a", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 3)).matrix
        val matrix = QrMatrix(21).apply {
            for (y in 0 until 21) for (x in 0 until 21)
                set(x, y, if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val words = CodewordExtractor.extract(matrix, 1, 3).codewords
        // One free payload bit, with all parity erased. UTF-8 distinguishes ASCII from a lone lead byte.
        for (p in listOf(words[1].coordinates[4]) + words.drop(19).flatMap { it.coordinates })
            matrix.set(p.x, p.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        return matrix
    }

    private fun recover(matrix: QrMatrix, utf8: Boolean) = RecoveryEngine().recoverWithDiagnostics(matrix,
        formatConstraint = format, assumeUtf8ByteSegments = utf8, maxKnownDataEccMismatches = 11,
        knownBitErrorLimit = 0, listDecodingAdditionalErrors = 0, executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_BASIC)

    @Test fun utf8ResolvesAnExactSpaceWhileTentativeAndKnownBitSearchStayDisabled() {
        val matrix = erasedPayloadBitAndParity()
        val result = recover(matrix, true)
        val candidate = result.candidates.single()
        assertEquals("a", candidate.text)
        assertTrue(QuickAutoRecovery.canReport(result))
        assertEquals(0, candidate.displayConditions!!.partialBitSearchLimit)
        assertEquals(8, candidate.displayConditions.exactSpaceBitLimit)
        assertFalse(result.searchDiagnostic.knownBitSearchAttempted)
        assertEquals(0L, result.searchDiagnostic.knownBitTrials)
        CodewordExtractor.extract(matrix, 1, 3).codewords.forEachIndexed { i, word ->
            assertEquals(0, (candidate.correctedInterleaved[i] xor word.value) and word.unknownBits.inv() and 255)
        }
        assertEquals(0, candidate.knownBitMismatchesAfterCorrection)
    }

    @Test fun multipleExactPayloadsNeverBecomeAutomaticSuccess() {
        val result = recover(erasedPayloadBitAndParity(), false)
        assertEquals(2, result.candidates.size)
        assertFalse(QuickAutoRecovery.canReport(result))
    }

    @Test fun exactEnumerationCannotOverwriteAKnownPaddingBit() {
        val matrix = erasedPayloadBitAndParity()
        val p = CodewordExtractor.extract(matrix, 1, 3).codewords[4].coordinates[0]
        matrix.set(p.x, p.y, if (matrix[p.x, p.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
            1f, ModuleSource.IMAGE)
        val result = RecoveryEngine().recoverWithDiagnostics(matrix, formatConstraint = format,
            assumeUtf8ByteSegments = true, trustKnownPayloadCount = true, maxKnownDataEccMismatches = 11,
            knownBitErrorLimit = 0, listDecodingAdditionalErrors = 0, executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_BASIC)
        assertFalse(QuickAutoRecovery.canReport(result))
        assertTrue(result.candidates.isEmpty())
    }

    private fun branchingBlocks(): DeinterleavedQr {
        val rs = ReedSolomonErasures()
        val data = intArrayOf(0x40, 0x10, 0)
        val block = RsBlockWork(3, rs.encode(data, 4), intArrayOf(0, 0x1f, 0xf0, 255, 255, 255, 255))
        return DeinterleavedQr(listOf(block), emptyArray(), 4)
    }

    @Test fun aCandidateDoesNotHideAnotherHeaderBranchAboveTheDimensionLimit() {
        val de = branchingBlocks()
        val spaces = de.blocks.map { RsBitConstraintSolver().analyze(it, de.ecSymbolsPerBlock) }
        val outcome = StreamingQrSearch(ReedSolomonErasures()).run(de, spaces, 1, true, true,
            listOf(emptySet()), 0, 16L * 1024 * 1024, RecoveryCancellation.NONE,
            onStops = {}, onSearch = {}, exactSpaceBitLimit = 1) { true }
        assertTrue(outcome.candidates > 0)
        assertEquals(RsFailureReason.BIT_SPACE_LIMIT, outcome.failure)
    }

    @Test fun combinedTraversalRespectsItsSharedNodeBudget() {
        val base = branchingBlocks()
        val de = base.copy(blocks = base.blocks + base.blocks.map { it.copyDeep() })
        val spaces = de.blocks.map { RsBitConstraintSolver().analyze(it, de.ecSymbolsPerBlock) }
        var used = 0
        val stops = mutableSetOf<CombinationSearchStop>()
        StreamingQrSearch(ReedSolomonErasures()).run(de, spaces, 1, true, true,
            listOf(emptySet(), emptySet()), 0, 16L * 1024 * 1024, RecoveryCancellation.NONE,
            onStops = { stops += it }, onSearch = {}, exactSpaceBitLimit = 8, maximumNodes = 10,
            onNodes = { used += it }) { false }
        assertTrue(used <= 10)
        assertTrue(CombinationSearchStop.NODE_LIMIT in stops)
    }

    @Test fun combinedAssumptionsAreIndependentOfTheSavedOptions() {
        val saved = PreprocessSettings(assumeUtf8ByteSegments = false, trustKnownPayloadCount = false, knownBitErrorLimit = 2)
        val actual = QuickAutoRecovery.executionSettings(saved, AutomaticScanAssumption.UTF8_SINGLE_SEGMENT)
        assertTrue(actual.assumeUtf8ByteSegments)
        assertTrue(actual.trustKnownPayloadCount)
        assertEquals(0, actual.knownBitErrorLimit)
        assertFalse(saved.assumeUtf8ByteSegments)
        assertFalse(saved.trustKnownPayloadCount)
        assertEquals(2, saved.knownBitErrorLimit)
    }
}
