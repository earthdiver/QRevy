package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import android.util.Log
import zxingcpp.BarcodeReader

/** Android-only native adapter. Decoded text is deliberately never used as a repair result. */
object ZxingCppGridDetector {
    fun detect(bitmap: Bitmap): List<ImageQuad> = try {
        // Construct per import, on the existing worker; no shared mutable native reader.
        val reader = BarcodeReader(BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE_MODEL_2),
            tryHarder = true,
            tryRotate = true,
            tryDownscale = true,
            returnErrors = true,
            maxNumberOfSymbols = 16,
        ))
        reader.read(bitmap).map { result ->
            val p = result.position
            ImageQuad(
                PointD(p.topLeft.x.toDouble(), p.topLeft.y.toDouble()),
                PointD(p.topRight.x.toDouble(), p.topRight.y.toDouble()),
                PointD(p.bottomRight.x.toDouble(), p.bottomRight.y.toDouble()),
                PointD(p.bottomLeft.x.toDouble(), p.bottomLeft.y.toDouble()),
            )
        }
    } catch (_: LinkageError) {
        // Unsupported ABI/load failure: importing and finder-independent recovery still work.
        Log.w("QrGridDetector", "Native detector unavailable; using periodicity estimation")
        emptyList()
    } catch (_: RuntimeException) {
        Log.w("QrGridDetector", "Native detection failed; using periodicity estimation")
        emptyList()
    }
}
