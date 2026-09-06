package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRemainderBitsTest {
    @Test fun version2SevenRemainderBitsAreResolvedFromMask() {
        val version = 2
        val matrix = QrMatrix(25).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, version, 0)
        val codeCoords = extraction.codewords.flatMapTo(hashSetOf()) { it.coordinates }
        val remainder = buildList {
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                val c = Coord(x, y)
                if (!QrFunctionMask.isFunction(version, x, y) && c !in codeCoords) add(c)
            }
        }
        assertEquals(7, remainder.size)
        remainder.forEach { matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.USER) }

        val candidate = RecoveryCandidate(
            version, ErrorCorrectionLevel.M, 0, null, byteArrayOf(), 0, 0,
            0, 0, 0, 0, 0, IntArray(extraction.codewords.size), extraction,
        )
        RecoveryEngine().applyCorrection(matrix, candidate)

        assertTrue(remainder.all { matrix[it.x, it.y].value != ModuleValue.ERASED })
        assertTrue(remainder.all { matrix[it.x, it.y].source == ModuleSource.STRUCTURAL })
        assertTrue(remainder.all {
            matrix[it.x, it.y].value == if (CodewordExtractor.maskApplies(0, it.x, it.y)) ModuleValue.ONE else ModuleValue.ZERO
        })
        val source = QrMatrix(25).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val traces = RepairTraceBuilder.build(source, matrix, candidate)
        assertTrue(remainder.all { traces[it]?.primaryReason == RepairReasonKind.REMAINDER_BITS })
    }
}
