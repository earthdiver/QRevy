package io.github.earthdiver.qrrepair.ui

import io.github.earthdiver.qrrepair.core.Coord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFootprintTest {
    @Test
    fun centeredStampUsesRequestedOddModuleSize() {
        val cells = moduleFootprint(Coord(10, 10), size = 7, dimension = 21)

        assertEquals(49, cells.size)
        assertTrue(Coord(7, 7) in cells)
        assertTrue(Coord(13, 13) in cells)
    }

    @Test
    fun stampIsClippedAtQrBoundary() {
        val cells = moduleFootprint(Coord(0, 0), size = 5, dimension = 21)

        assertEquals(9, cells.size)
        assertTrue(cells.all { it.x in 0..2 && it.y in 0..2 })
    }

    @Test
    fun magnifierGridOnlyDrawsBoundariesVisibleNearItsSourceWindow() {
        assertEquals(8..13, magnifierGridLineRange(7.7, 11.6, dimension = 177))
        assertEquals(0..2, magnifierGridLineRange(-4.0, 1.2, dimension = 21))
        assertEquals(20..21, magnifierGridLineRange(19.7, 26.0, dimension = 21))
    }
}
