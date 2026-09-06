package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PreRecoveryEditReflectionTest {
    @Test fun userEditChangesExtractedDataBitButFunctionCellDoesNotBelongToExtraction() {
        val version = 1
        val dimension = 21
        val original = QrMatrix(dimension).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val before = CodewordExtractor.extract(original, version, 0)
        val dataCoord = before.codewords.first().coordinates.first()
        val edited = original.copy().apply { set(dataCoord.x, dataCoord.y, ModuleValue.ONE, 1f, ModuleSource.USER) }
        val after = CodewordExtractor.extract(edited, version, 0)

        assertNotEquals(before.codewords.first().value, after.codewords.first().value)
        assertEquals(ModuleSource.USER, edited[dataCoord.x, dataCoord.y].source)
        assertEquals(true, QrFunctionMask.isFunction(version, 0, 0))
        assertEquals(false, before.codewords.flatMap { it.coordinates }.contains(Coord(0, 0)))
    }
}
