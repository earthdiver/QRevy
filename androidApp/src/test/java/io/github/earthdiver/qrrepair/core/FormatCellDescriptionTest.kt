package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class FormatCellDescriptionTest {
    private val candidate = RecoveryCandidate(1, ErrorCorrectionLevel.Q, 3, null, byteArrayOf(),
        0, 0, 0, 0, 0, 0, 0, intArrayOf(), CodewordExtraction(emptyList()))

    @Test fun comparisonClearlyIdentifiesKnownFormatInformation() {
        val c = candidate.copy(formatSelection = FormatSelection(false, false), formatRoute = FormatSelectionReason.SEARCH)
        for ((matches, mismatches) in listOf(13 to 0, 11 to 2)) {
            val text = formatCellDescription(2, 8, ModuleValue.ERASED, ModuleValue.ZERO,
                c.copy(formatMatches = matches, formatMismatches = mismatches), false)
            assertTrue(text.contains("既知の形式情報13bitとの比較：一致${matches}bit／不一致${mismatches}bit"))
            assertFalse(text.contains("形式情報の比較対象"))
        }
        val empty = formatCellDescription(2, 8, ModuleValue.ERASED, ModuleValue.ZERO, c, false)
        assertTrue(empty.contains("既知の形式情報：0bit（一致を確認できません）"))
    }

    @Test fun recoveryCapturesEccAndMaskSelectionIndependently() {
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode("HELLO", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        for (ecc in listOf(false, true)) for (mask in listOf(false, true)) {
            val constraint = if (ecc || mask) QrFormatConstraint(if (ecc) qr.ecLevel else null, if (mask) qr.maskPattern else null) else null
            val results = RecoveryEngine().recover(matrix, formatConstraint = constraint)
            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it.formatSelection == FormatSelection(ecc, mask) })
        }
    }

    @Test fun bitReasonsRespectPartialSettingsIncludingParityAndBothCopies() {
        for (ecc in listOf(false, true)) for (mask in listOf(false, true)) {
            val permitted = ErrorCorrectionLevel.entries.flatMap { ec -> (0..7).map { ec to it } }
                .filter { (ec, m) -> (!ecc || ec == candidate.ecLevel) && (!mask || m == candidate.dataMask) }
                .map { (ec, m) -> QrMatrix(21).also { StructuralRepair.apply(it, ec, m) } }
            for (route in listOf(FormatSelectionReason.OBSERVED, FormatSelectionReason.SEARCH)) {
                val c = candidate.copy(formatSelection = FormatSelection(ecc, mask), formatRoute = route)
                for (copy in FormatSolver.copies(21)) for ((x, y) in copy) {
                    val fixed = permitted.map { it[x, y].value }.distinct().size == 1
                    assertEquals(if (fixed) FormatSelectionReason.USER else route, c.formatReasonAt(x, y))
                    val text = formatCellDescription(x, y, ModuleValue.ERASED, ModuleValue.ONE, c, false)
                    assertTrue(text.contains(when {
                        fixed -> "ユーザー指定"
                        route == FormatSelectionReason.OBSERVED -> "読み取った形式情報から訂正・判定"
                        else -> "複数の形式を探索"
                    }))
                    assertEquals(FormatSelectionReason.USER, c.formatReasonAt(x, y, true))
                }
            }
        }
        assertTrue(formatCellDescription(8, 8, ModuleValue.ERASED, ModuleValue.ONE, candidate, false).contains("記録なし"))
    }

    @Test fun actualRecoveryRecordsObservedAndSearchedRoutes() {
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode("HELLO", ErrorCorrectionLevel.L)
        val source = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        for (kind in 0..3) {
            val matrix = source.copy()
            for (copy in FormatSolver.copies(matrix.dimension)) copy.forEachIndexed { bit, (x, y) ->
                when {
                    (kind == 1 && bit < 2) || (kind == 2 && bit == 5) -> matrix.set(x, y, if (matrix[x, y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
                    kind == 2 && bit >= 6 || kind == 3 -> matrix.set(x, y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
                }
            }
            if (kind == 2) assertNotNull(FormatSolver.matchingInformationCandidate(matrix))
            val result = RecoveryEngine().recover(matrix).first { it.text == "HELLO" }
            assertEquals("kind=$kind", if (kind == 3) FormatSelectionReason.SEARCH else FormatSelectionReason.OBSERVED, result.formatRoute)
        }
    }

    @Test fun formatExplanationDistinguishesFillingChangingAgreementAndManualValues() {
        val filled = formatCellDescription(8, 8, ModuleValue.ERASED, ModuleValue.ONE, candidate, false)
        assertTrue(filled.contains("不明 → 黒"))
        assertTrue(filled.contains("ECCレベル Q / マスク 3"))
        assertTrue(filled.contains("黒になるため補完しました"))
        assertTrue(filled.contains("このセル単独で確認したわけではありません"))
        assertFalse(filled.contains("固定構造"))
        assertFalse(filled.contains("0.99"))
        assertTrue(formatCellDescription(8, 8, ModuleValue.ZERO, ModuleValue.ONE, candidate, false).contains("変更しました"))
        assertTrue(formatCellDescription(8, 8, ModuleValue.ONE, ModuleValue.ONE, candidate, false).contains("一致しています"))
        assertTrue(formatCellDescription(8, 8, ModuleValue.ZERO, ModuleValue.ONE, candidate, true).contains("ユーザー指定の値"))
    }
}
