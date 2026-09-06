package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritySearchRecoveryTest {
    @Test
    fun fourPriorityBitsAreTriedBeforeTheGeneralPartialBitSearch() {
        val text = "STAR-SEARCH"
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(encoded.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)
        // V1-L has seven ECC symbols. Ten erased codewords fail ordinary erasure decoding;
        // resolving four priority bits leaves six ordinary erasures and restores the payload.
        extraction.codewords.take(10).forEachIndexed { index, codeword ->
            val coord = codeword.coordinates.first()
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
            if (index < 4) matrix.setPrioritySearch(coord.x, coord.y, true)
        }

        val recovered = RecoveryEngine().recover(matrix, maxFormatCandidates = 1)

        assertEquals(4, matrix.prioritySearchCoordinates().size)
        val candidate = recovered.first { it.text == text }
        val after = RepairTraceBuilder.candidateMatrix(matrix, candidate, RecoveryEngine())
        assertTrue(matrix.prioritySearchCoordinates().all { (x, y) -> after[x, y].value != ModuleValue.ERASED })
    }

    @Test
    fun priorityAssignmentsCanCombineWithOrdinaryPartialBitSearch() {
        val text = "STAR-AND-PARTIAL"
        val encoded = Encoder.encode(text, ErrorCorrectionLevel.L, mapOf(EncodeHintType.QR_VERSION to 1))
        val matrix = QrMatrix(encoded.matrix.width)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, encoded.maskPattern)

        // Four priority erasures plus eight ordinary erasure bytes exceed V1-L's seven ECC
        // symbols. After assigning the priority bits, the bounded solver must enumerate the
        // three one-bit ordinary erasures; the other five full-byte erasures then fit RS.
        extraction.codewords.take(4).forEach { codeword ->
            val coord = codeword.coordinates.first()
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
            matrix.setPrioritySearch(coord.x, coord.y, true)
        }
        extraction.codewords.slice(4..6).forEach { codeword ->
            val coord = codeword.coordinates.first()
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        extraction.codewords.slice(7..11).forEach { codeword ->
            codeword.coordinates.forEach { coord ->
                matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }

        val recovered = RecoveryEngine().recover(matrix, maxFormatCandidates = 1)

        // The exact bit solver can now resolve these erasures before the priority loop. The
        // conventional priority+enumeration route remains valid when known-bit errors require it.
        assertTrue(recovered.any { it.text == text &&
            (it.partialGuessedBits == 3 || it.repairInfo.any { info -> info.bitConstraintSolved }) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun prioritySearchRejectsFunctionModules() {
        val matrix = QrMatrix(21)
        matrix.set(0, 0, ModuleValue.ERASED, 0f, ModuleSource.USER)
        matrix.setPrioritySearch(0, 0, true)
        RecoveryEngine().recover(matrix)
    }
}
