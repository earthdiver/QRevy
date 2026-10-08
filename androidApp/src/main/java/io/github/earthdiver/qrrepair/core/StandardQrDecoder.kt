package io.github.earthdiver.qrrepair.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge

object StandardQrDecoder {
    fun decode(width: Int, height: Int, argb: IntArray): Result? {
        require(width > 0 && height > 0 && argb.size == width * height)
        return runCatching {
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
            val result = MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb))),
                hints,
            )
            correctFnc1SecondPosition(result, hints)
        }.getOrNull()
    }

    private fun correctFnc1SecondPosition(result: Result, hints: Map<DecodeHintType, *>): Result {
        val data = result.rawBytes ?: return result
        if (ZxingPayloadBridge.fnc1ApplicationIndicator(data) == null) return result
        val ecLevel = ErrorCorrectionLevel.valueOf(result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL] as String)
        // QRCodeReader returns corrected data codewords without parity. Their count and ECC
        // identify the version; an ambiguous or missing match must continue to the repair flow.
        val version = (1..40).map(Version::getVersionForNumber).single {
            it.totalCodewords - it.getECBlocksForLevel(ecLevel).totalECCodewords == data.size
        }
        val decoded = ZxingPayloadBridge.decodeData(data, version.versionNumber, ecLevel, hints)
        val metadata = result.resultMetadata.toMutableMap()
        metadata.remove(ResultMetadataType.BYTE_SEGMENTS)
        metadata.remove(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE)
        metadata.remove(ResultMetadataType.STRUCTURED_APPEND_PARITY)
        decoded.byteSegments?.let { metadata[ResultMetadataType.BYTE_SEGMENTS] = it }
        if (decoded.hasStructuredAppend()) {
            metadata[ResultMetadataType.STRUCTURED_APPEND_SEQUENCE] = decoded.structuredAppendSequenceNumber
            metadata[ResultMetadataType.STRUCTURED_APPEND_PARITY] = decoded.structuredAppendParity
        }
        metadata[ResultMetadataType.SYMBOLOGY_IDENTIFIER] = "]Q${decoded.symbologyModifier}"
        return Result(decoded.text, data, result.numBits, result.resultPoints, result.barcodeFormat, result.timestamp)
            .also { it.putAllMetadata(metadata) }
    }
}
