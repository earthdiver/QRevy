package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Test

class StandardQrDecoderTest {
    @Test fun intactQrIsDecodedBeforeRepairFlow() {
        val expected = "INTACT-QR-IMMEDIATE"
        val encoded = Encoder.encode(expected, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 2))
        val (size, pixels) = render(encoded.matrix)

        assertEquals(expected, StandardQrDecoder.decode(size, size, pixels)?.text)
    }

    @Test fun defaultLatin1BytePayloadIsNotForcedThroughUtf8() {
        val expected = "caf\u00e9"
        // ZXing's QR default encodes this as ISO-8859-1 without requiring an ECI segment.
        val encoded = Encoder.encode(expected, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 2))
        val (size, pixels) = render(encoded.matrix)

        assertEquals(expected, StandardQrDecoder.decode(size, size, pixels)?.text)
    }

    private fun render(matrix: com.google.zxing.qrcode.encoder.ByteMatrix): Pair<Int, IntArray> {
        val scale = 6
        val quiet = 4
        val size = (matrix.width + quiet * 2) * scale
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y).toInt() == 0) continue
            for (py in 0 until scale) for (px in 0 until scale) {
                pixels[((y + quiet) * scale + py) * size + (x + quiet) * scale + px] = 0xFF000000.toInt()
            }
        }
        return size to pixels
    }
}
