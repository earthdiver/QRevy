package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class FormatCellGroupTest {
    @Test fun rolesCoverBothCopiesAtEveryVersionWithoutIncludingTheFixedDarkCell() {
        for (version in 1..40) {
            val d = 17 + 4 * version
            val groups = (0 until d).flatMap { y ->
                (0 until d).mapNotNull { x -> FormatCellGroup.at(d, x, y) }
            }
            assertEquals(30, groups.size)
            for ((role, count) in listOf(FormatCellRole.ECC to 4, FormatCellRole.MASK to 6, FormatCellRole.PARITY to 20)) {
                val matching = groups.filter { it.role == role }
                assertEquals(count, matching.size)
                assertEquals(count, matching.first().coordinates(d).toSet().size)
                assertTrue(matching.all { it.coordinates(d) == matching.first().coordinates(d) })
            }
            assertNull(FormatCellGroup.at(d, 8, d - 8))
            assertNull(FormatCellGroup.at(d, 6, 8))
            val mask = FormatCellGroup.at(d, 2, 8)!!
            assertEquals(FormatCellRole.MASK, mask.role)
            assertEquals(2, mask.bitIndex)
            assertEquals(0, mask.copyIndex)
            assertEquals(listOf(Coord(2, 8), Coord(3, 8), Coord(4, 8),
                Coord(8, d - 3), Coord(8, d - 4), Coord(8, d - 5)), mask.coordinates(d))
            assertEquals(mask.copy(copyIndex = 1), FormatCellGroup.at(d, 8, d - 3))
        }
    }

    @Test fun bothCopiesPreserveUnknownAndConflictingInputWhileShowingFixedMaskInterpretation() {
        val after = QrMatrix(21).also { StructuralRepair.apply(it, ErrorCorrectionLevel.Q, 5) }
        val before = after.copy().apply {
            set(2, 8, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            set(8, 18, ModuleValue.ONE, 1f, ModuleSource.USER)
        }
        val snapshot = before.copy()
        val text = formatGroupDetailText(FormatCellGroup.at(21, 2, 8)!!, before, after)
        assertTrue(text.contains("[?]00 → [0]00"))
        assertTrue(text.contains("[?]01 → [1]01"))
        assertTrue(text.contains("[1]00 → [0]00"))
        assertTrue(text.contains("[0]01 → [1]01"))
        assertTrue(text.contains("0x5412"))
        assertTrue(text.contains("1 → 5"))
        for (y in 0 until 21) for (x in 0 until 21) assertEquals(snapshot[x, y], before[x, y])
    }

    @Test fun interpretationMatchesEveryEccLevelAndMaskAndExplainsParitySeparately() {
        for (ecc in ErrorCorrectionLevel.entries) for (mask in 0..7) {
            val matrix = QrMatrix(21).also { StructuralRepair.apply(it, ecc, mask) }
            val eccText = formatGroupDetailText(FormatCellGroup.at(21, 0, 8)!!, matrix, matrix)
            val maskText = formatGroupDetailText(FormatCellGroup.at(21, 2, 8)!!, matrix, matrix)
            assertTrue(eccText.contains("$ecc → $ecc"))
            assertTrue(maskText.contains("$mask → $mask"))
        }
        val unknown = QrMatrix(21)
        val text = formatGroupDetailText(FormatCellGroup.at(21, 5, 8)!!, unknown, unknown)
        assertTrue(text.contains("[?]????????? → [?]?????????"))
        assertTrue(text.contains("BCH"))
        assertTrue(text.contains("RS"))
    }
}
