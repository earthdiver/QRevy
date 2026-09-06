package io.github.earthdiver.qrrepair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QrTextMatrixParserTest {
    @Test fun parsesLfAndPreservesPriorityCells() {
        val rows = MutableList(21) { "0".repeat(21) }
        rows[3] = rows[3].replaceRange(4, 5, "1")
        rows[5] = rows[5].replaceRange(6, 7, "?")
        rows[7] = rows[7].replaceRange(8, 9, "*")

        val parsed = QrTextMatrixParser.parse(rows.joinToString("\n", postfix = "\n"))

        assertEquals(1, parsed.version)
        assertEquals(ModuleValue.ONE, parsed.matrix[4, 3].value)
        assertEquals(ModuleValue.ERASED, parsed.matrix[6, 5].value)
        assertTrue(parsed.matrix[8, 7].prioritySearch)
    }

    @Test fun acceptsCrLf() {
        val parsed = QrTextMatrixParser.parse(List(25) { "1".repeat(25) }.joinToString("\r\n"))
        assertEquals(2, parsed.version)
    }

    @Test fun rejectsInvalidCharactersWhitespaceAndNonSquareInput() {
        val valid = List(21) { "0".repeat(21) }
        assertFailsWith<IllegalArgumentException> {
            QrTextMatrixParser.parse(valid.toMutableList().also { it[2] = "0".repeat(20) + " " }.joinToString("\n"))
        }
        assertFailsWith<IllegalArgumentException> {
            QrTextMatrixParser.parse(List(21) { "0".repeat(20) }.joinToString("\n"))
        }
        assertFailsWith<IllegalArgumentException> {
            QrTextMatrixParser.parse(valid.joinToString("\n", postfix = "\n\n"))
        }
    }

    @Test fun rejectsSquareSizesThatAreNotQrVersions() {
        assertFailsWith<IllegalArgumentException> {
            QrTextMatrixParser.parse(List(22) { "0".repeat(22) }.joinToString("\n"))
        }
    }

    @Test fun rejectsMoreThanFourPriorityCells() {
        val first = "*****" + "0".repeat(16)
        assertFailsWith<IllegalArgumentException> {
            QrTextMatrixParser.parse((listOf(first) + List(20) { "0".repeat(21) }).joinToString("\n"))
        }
    }
}
