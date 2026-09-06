package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FormatBchTest {
    @Test fun version7BchMatchesIsoValueUsedByZxing() {
        assertEquals(0x07C94, StructuralRepair.versionCodeword(7))
    }

    @Test fun formatMaskExampleIsStable() {
        // M / mask 0 => five data bits 00000; known masked format string is 0x5412.
        assertEquals(0x5412, FormatSolver.formatCodeword(0))
    }

    @Test fun trustsMatchingCompleteCopiesAfterBchCorrection() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b10110, flippedBits = setOf(1, 8, 13))
        writeCopy(matrix, 1, fiveBits = 0b10110, flippedBits = setOf(0, 6))

        val trusted = FormatSolver.trustedCandidate(matrix)
        assertNotNull(trusted)
        requireNotNull(trusted)
        assertEquals(0b110, trusted.dataMask)
        assertEquals(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, trusted.ecLevel)
        assertNull(FormatSolver.matchingInformationCandidate(matrix))
    }

    @Test fun trustsOneCompleteCopyOnlyWhenAllKnownBitsInOtherCopyAgree() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b01011, flippedBits = setOf(4, 10))
        writeCopy(matrix, 1, fiveBits = 0b01011, erasedBits = setOf(2, 5, 9, 12))

        assertNotNull(FormatSolver.trustedCandidate(matrix))

        val conflicting = formatCoordinates(matrix.dimension)[1][7]
        matrix.set(conflicting.first, conflicting.second, opposite(matrix[conflicting.first, conflicting.second].value), 1f, ModuleSource.IMAGE)
        assertNull(FormatSolver.trustedCandidate(matrix))
    }

    @Test fun provisionallyUsesMatchingInformationBitsWhenBchEvidenceIsDamaged() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b10110, flippedBits = setOf(5, 6, 7, 8))
        writeCopy(matrix, 1, fiveBits = 0b10110, flippedBits = setOf(9, 10, 11, 12))

        assertNull(FormatSolver.trustedCandidate(matrix))
        val provisional = FormatSolver.matchingInformationCandidate(matrix)
        assertNotNull(provisional)
        requireNotNull(provisional)
        assertEquals(0b110, provisional.dataMask)
        assertEquals(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, provisional.ecLevel)
    }

    @Test fun doesNotProvisionallyUseDisagreeingInformationBits() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b10110, erasedBits = setOf(5))
        writeCopy(matrix, 1, fiveBits = 0b10111, erasedBits = setOf(5))

        assertNull(FormatSolver.matchingInformationCandidate(matrix))
    }

    @Test fun provisionallyCombinesComplementaryInformationBitsWhenKnownParityAgrees() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b10110, erasedBits = setOf(1, 3, 7, 9, 12))
        writeCopy(matrix, 1, fiveBits = 0b10110, erasedBits = setOf(0, 2, 6, 8, 11))

        assertNull(FormatSolver.trustedCandidate(matrix))
        val provisional = FormatSolver.matchingInformationCandidate(matrix)
        assertNotNull(provisional)
        requireNotNull(provisional)
        assertEquals(0b110, provisional.dataMask)
        assertEquals(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H, provisional.ecLevel)
    }

    @Test fun rejectsComplementaryInformationBitsWhenKnownParityConflicts() {
        val matrix = emptyMatrix()
        writeCopy(matrix, 0, fiveBits = 0b10110, erasedBits = setOf(1, 3, 7, 9, 12))
        writeCopy(matrix, 1, fiveBits = 0b10110, erasedBits = setOf(0, 2, 6, 8, 11))
        val conflicting = formatCoordinates(matrix.dimension)[1][13]
        matrix.set(conflicting.first, conflicting.second, opposite(matrix[conflicting.first, conflicting.second].value), 1f, ModuleSource.IMAGE)

        assertNull(FormatSolver.matchingInformationCandidate(matrix))
    }

    private fun emptyMatrix(): QrMatrix = QrMatrix(21).also { matrix ->
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
    }

    private fun writeCopy(
        matrix: QrMatrix,
        copy: Int,
        fiveBits: Int,
        flippedBits: Set<Int> = emptySet(),
        erasedBits: Set<Int> = emptySet(),
    ) {
        val codeword = FormatSolver.formatCodeword(fiveBits)
        formatCoordinates(matrix.dimension)[copy].forEachIndexed { i, (x, y) ->
            if (i in erasedBits) return@forEachIndexed
            val bit = ((codeword shr (14 - i)) and 1) xor if (i in flippedBits) 1 else 0
            matrix.set(x, y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }

    private fun opposite(value: ModuleValue): ModuleValue =
        if (value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE

    private fun formatCoordinates(d: Int): List<List<Pair<Int, Int>>> = listOf(
        listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
            8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        ),
        buildList {
            for (y in d - 1 downTo d - 7) add(8 to y)
            for (x in d - 8 until d) add(x to 8)
        },
    )
}
