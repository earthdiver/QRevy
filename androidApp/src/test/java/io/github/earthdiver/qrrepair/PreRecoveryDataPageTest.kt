package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.QrFormatConstraint
import io.github.earthdiver.qrrepair.core.FormatSolver
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRecoveryDataPageTest {
    @Test
    fun automaticFormatUsesTheSameBchTrustRuleAsRecovery() {
        val matrix = QrMatrix(21)
        writeFormatCopy(matrix, 0, fiveBits = 0b10110, flippedBits = setOf(1, 8, 13))
        writeFormatCopy(matrix, 1, fiveBits = 0b10110, flippedBits = setOf(0, 6))

        val content = buildPreRecoveryDataContent(
            PreRecoveryDataInput(matrix, version = 1),
            formatConstraint = null,
            unmasked = true,
        )

        assertFalse(content.needsManualFormat)
        assertTrue(content.text.startsWith("マスク解除後 ・ V1 ・ 推定形式: ECC=H、マスク=6"))
    }

    @Test
    fun aggregateKnownBitsDoNotReplaceACompleteBchTrustedCopy() {
        val matrix = QrMatrix(21)
        writeFormatCopy(matrix, 0, fiveBits = 0b10110, erasedBits = setOf(1, 3, 7, 9, 12))
        writeFormatCopy(matrix, 1, fiveBits = 0b10110, erasedBits = setOf(0, 2, 6, 8, 11))

        val content = buildPreRecoveryDataContent(
            PreRecoveryDataInput(matrix, version = 1),
            formatConstraint = null,
            unmasked = true,
            partialOnly = true,
        )

        assertTrue(content.needsManualFormat)
        assertTrue(content.text.contains("形式未確定"))
    }

    @Test
    fun unknownFormatUsesTheSelectedManualFormatForPartialInformation() {
        val input = PreRecoveryDataInput(QrMatrix(21), version = 1)
        val manual = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, 6)

        val masked = buildPreRecoveryDataContent(input, formatConstraint = manual, unmasked = false)
        val unmasked = buildPreRecoveryDataContent(input, formatConstraint = manual, unmasked = true)

        assertFalse(masked.needsManualFormat)
        assertTrue(masked.text.startsWith("マスク解除前 ・ V1 ・ 手動形式: ECC=L、マスク=6"))
        assertTrue(masked.text.contains("\n\nHEX\n"))
        assertTrue(masked.text.contains("\n\nBinary\n"))
        assertTrue(masked.text.endsWith("マスク解除後に解釈できます。"))
        assertFalse(unmasked.needsManualFormat)
        assertTrue(unmasked.text.startsWith("マスク解除後 ・ V1 ・ 手動形式: ECC=L、マスク=6"))
        assertTrue(unmasked.text.contains("検証済みデータはありません"))

        val partial = buildPreRecoveryDataContent(input, formatConstraint = manual, unmasked = true, partialOnly = true)
        assertTrue(partial.text.contains("\n\n部分情報\n"))
        assertTrue(!partial.text.contains("Formatが未確定"))
        assertTrue(!partial.text.contains("\n\nHEX\n"))
    }

    @Test
    fun fixedFormatOverridesAnAutomaticallyTrustedFormat() {
        val matrix = QrMatrix(21)
        writeFormatCopy(matrix, 0, fiveBits = 0b10110)
        writeFormatCopy(matrix, 1, fiveBits = 0b10110)
        val fixed = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, 3)

        val content = buildPreRecoveryDataContent(
            PreRecoveryDataInput(matrix, version = 1),
            formatConstraint = fixed,
            unmasked = true,
        )

        assertTrue(content.text.startsWith("マスク解除後 ・ V1 ・ 手動形式: ECC=L、マスク=3"))
    }

    @Test
    fun automaticFormatDoesNotInventAnEccLevel() {
        val content = buildPreRecoveryDataContent(
            PreRecoveryDataInput(QrMatrix(21), version = 1),
            formatConstraint = null,
            unmasked = true,
            partialOnly = true,
        )

        assertTrue(content.needsManualFormat)
        assertTrue(content.text.contains("形式未確定"))
        assertTrue(content.text.contains("基本画面で誤り訂正レベルとマスクを指定"))

        val raw = buildPreRecoveryDataContent(
            PreRecoveryDataInput(QrMatrix(21), version = 1),
            formatConstraint = null,
            unmasked = true,
        )
        assertTrue(!raw.text.contains("\n\nHEX\n"))
        assertTrue(raw.text.contains("基本画面で誤り訂正レベルとマスクを指定"))
    }

    @Test
    fun aPartialConstraintStillRequiresTheMissingFormatComponent() {
        val content = buildPreRecoveryDataContent(
            PreRecoveryDataInput(QrMatrix(21), version = 1),
            formatConstraint = QrFormatConstraint(
                ecLevel = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            ),
            unmasked = true,
        )

        assertTrue(content.needsManualFormat)
        assertTrue(content.text.contains("ECC=L、マスク=自動"))
        assertTrue(content.text.contains("基本画面で誤り訂正レベルとマスクを指定"))
    }

    private fun writeFormatCopy(
        matrix: QrMatrix,
        copy: Int,
        fiveBits: Int,
        flippedBits: Set<Int> = emptySet(),
        erasedBits: Set<Int> = emptySet(),
    ) {
        val codeword = FormatSolver.formatCodeword(fiveBits)
        formatCoordinates(matrix.dimension)[copy].forEachIndexed { index, (x, y) ->
            if (index in erasedBits) return@forEachIndexed
            val bit = ((codeword shr (14 - index)) and 1) xor if (index in flippedBits) 1 else 0
            matrix.set(x, y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }

    private fun formatCoordinates(dimension: Int): List<List<Pair<Int, Int>>> = listOf(
        listOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8,
            8 to 7, 8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        ),
        buildList {
            for (y in dimension - 1 downTo dimension - 7) add(8 to y)
            for (x in dimension - 8 until dimension) add(x to 8)
        },
    )
}
