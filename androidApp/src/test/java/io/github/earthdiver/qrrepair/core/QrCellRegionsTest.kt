package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import org.junit.Assert.*
import org.junit.Test

class QrCellRegionsTest {
    @Test fun identifiesAlignmentRemainderAndCodewordCells() {
        val regions = QrCellRegions(2)
        assertEquals(RepairReasonKind.ALIGNMENT_PATTERN, regions.structureAt(Coord(17, 19)))
        assertFalse(Coord(17, 19) in regions.codewordCoordinates)
        assertNull(regions.structureAt(Coord(0, 13)))
        assertFalse(Coord(0, 13) in regions.codewordCoordinates)
        assertTrue(Coord(24, 24) in regions.codewordCoordinates)
        assertEquals(RepairReasonKind.FORMAT_INFORMATION, regions.structureAt(Coord(8, 0)))
        assertEquals(RepairReasonKind.VERSION_INFORMATION, QrCellRegions(7).structureAt(Coord(34, 0)))
    }

    @Test fun allVersionsCoverEveryFunctionCellAndExactCodewordCount() {
        for (version in 1..40) {
            val regions = QrCellRegions(version)
            val dimension = 17 + 4 * version
            assertEquals(Version.getVersionForNumber(version).totalCodewords * 8, regions.codewordCoordinates.size)
            for (y in 0 until dimension) for (x in 0 until dimension) {
                val coord = Coord(x, y)
                if (QrFunctionMask.isFunction(version, x, y)) {
                    assertNotNull(regions.structureAt(coord))
                    assertNotEquals("Unclassified structure V$version $coord", RepairReasonKind.OTHER, regions.structureAt(coord))
                    assertFalse(coord in regions.codewordCoordinates)
                }
            }
            assertFalse(regions.contains(Coord(dimension, 0)))
        }
    }
}
