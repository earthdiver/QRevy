package io.github.earthdiver.qrrepair.ui

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodewordOverlayTest {
    @Test
    fun trustedFormatClassifiesInterleavedDataAndEccCodewords() {
        val encoded = Encoder.encode(
            "CODEWORD-OVERLAY-V5-Q",
            ErrorCorrectionLevel.Q,
            mapOf(EncodeHintType.QR_VERSION to 5),
        )
        val matrix = encoded.matrix.toQrMatrix()

        val overlay = buildCodewordOverlay(matrix, version = 5)

        assertEquals("Q", overlay.classifiedEcLevel)
        assertTrue(overlay.entries.any { it.kind == CodewordOverlayKind.DATA })
        assertTrue(overlay.entries.any { it.kind == CodewordOverlayKind.ECC })
        assertTrue(overlay.entries.none { it.kind == CodewordOverlayKind.UNCLASSIFIED })
        assertTrue(overlay.entries.mapNotNull { it.blockIndex }.distinct().size > 1)
        overlay.entries.forEach { entry ->
            assertEquals(8, entry.coordinates.size)
            entry.coordinates.forEachIndexed { bit, coord ->
                val cell = assertNotNull(overlay.cells[coord]).let { overlay.cells.getValue(coord) }
                assertEquals(entry.rawIndex, cell.entry.rawIndex)
                assertEquals(bit, cell.bitIndex)
            }
        }
    }

    @Test
    fun uncertainFormatKeepsRawOrderWithoutClaimingDataOrEcc() {
        val matrix = QrMatrix(21)

        val overlay = buildCodewordOverlay(matrix, version = 1)

        assertEquals(null, overlay.classifiedEcLevel)
        assertTrue(overlay.entries.isNotEmpty())
        assertTrue(overlay.entries.all { it.kind == CodewordOverlayKind.UNCLASSIFIED })
        assertEquals(overlay.entries.indices.toList(), overlay.entries.map { it.rawIndex })
        assertNotNull(overlay.cells[overlay.entries.first().coordinates.first()])
    }

    private fun com.google.zxing.qrcode.encoder.ByteMatrix.toQrMatrix(): QrMatrix =
        QrMatrix(width).also { result ->
            for (y in 0 until height) for (x in 0 until width) {
                result.set(
                    x,
                    y,
                    if (get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    confidence = 1f,
                    source = ModuleSource.IMAGE,
                )
            }
        }
}
