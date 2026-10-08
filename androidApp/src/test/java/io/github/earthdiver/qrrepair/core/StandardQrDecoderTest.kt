package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.ResultMetadataType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
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

    @Test fun fnc1SecondPositionPreservesBodyOriginalBytesAndMetadata() {
        for ((version, ecc) in listOf(1 to ErrorCorrectionLevel.L, 5 to ErrorCorrectionLevel.Q,
            10 to ErrorCorrectionLevel.M, 27 to ErrorCorrectionLevel.H)) for (append in listOf(false, true)) {
            val capacity = Version.getVersionForNumber(version).let { it.totalCodewords - it.getECBlocksForLevel(ecc).totalECCodewords }
            val data = PayloadBits().apply {
                if (append) { put(3, 4); put(0x0123, 16) }
                put(9, 4); put(1, 8)
                put(4, 4); put(1, if (version <= 9) 8 else 16); put(65, 8)
            }.finish(capacity)
            val decoded = requireNotNull(decodePayload(data, version, ecc))
            assertEquals("A", decoded.text)
            assertArrayEquals(data, decoded.rawBytes)
            assertEquals(data.size * 8, decoded.numBits)
            assertEquals(ecc.toString(), decoded.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL])
            assertEquals("]Q5", decoded.resultMetadata[ResultMetadataType.SYMBOLOGY_IDENTIFIER])
            val segments = decoded.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<*>
            assertArrayEquals(byteArrayOf(65), segments.single() as ByteArray)
            assertEquals(if (append) 1 else null, decoded.resultMetadata[ResultMetadataType.STRUCTURED_APPEND_SEQUENCE])
            assertEquals(if (append) 0x23 else null, decoded.resultMetadata[ResultMetadataType.STRUCTURED_APPEND_PARITY])
            assertTrue(decoded.resultPoints.size >= 3)
        }
    }

    @Test fun fnc1IndicatorsEitherDecodeCorrectlyOrContinueToRepair() {
        for (indicator in listOf(65, 165)) {
            val data = PayloadBits().apply {
                put(9, 4); put(indicator, 8)
                put(4, 4); put(1, 8); put(65, 8)
            }.finish()
            val decoded = decodePayload(data)
            if (decoded != null) assertEquals("A", decoded.text)
            else assertEquals("A", recoverPayload(data).single().text)
        }
    }

    @Test fun emptyFnc1BodyIsValidButAnInvalidBodyMustContinueToRepair() {
        val empty = PayloadBits().apply { put(9, 4); put(1, 8) }.finish()
        assertEquals("", requireNotNull(decodePayload(empty)).text)
        val invalid = PayloadBits().apply { put(9, 4); put(1, 8); put(6, 4) }.finish()
        assertNull(decodePayload(invalid))
    }

    private fun decodePayload(data: ByteArray, version: Int = 1, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L): com.google.zxing.Result? {
        val matrix = postTerminatorMatrix(data, version, ecc)
        val encoded = com.google.zxing.qrcode.encoder.ByteMatrix(matrix.dimension, matrix.dimension)
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            encoded.set(x, y, if (matrix[x, y].value == ModuleValue.ONE) 1 else 0)
        }
        val (size, pixels) = render(encoded)
        return StandardQrDecoder.decode(size, size, pixels)
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
