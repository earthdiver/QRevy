package io.github.earthdiver.qrrepair.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer

object StandardQrDecoder {
    fun decode(width: Int, height: Int, argb: IntArray): Result? {
        require(width > 0 && height > 0 && argb.size == width * height)
        return runCatching {
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb))),
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }.getOrNull()
    }
}
