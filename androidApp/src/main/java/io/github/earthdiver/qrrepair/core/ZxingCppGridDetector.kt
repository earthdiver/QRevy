package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import zxingcpp.BarcodeReader

/** One native pass supplies the ordinary scan and geometry for recovery if decoding fails. */
object ZxingCppGridDetector {
    data class Scan(val boundaries: List<ImageQuad>, val decoded: Result? = null)

    fun detect(bitmap: Bitmap, image: LumaImage? = null, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): List<ImageQuad> {
        cancellation.check()
        val native = scan(bitmap).boundaries
        cancellation.check()
        if (native.isNotEmpty()) return native
        if (image != null) return ZxingJavaGridDetector.detect(image, cancellation)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (299 * ((p ushr 16) and 255) + 587 * ((p ushr 8) and 255) + 114 * (p and 255) + 500) / 1000
        }
        return ZxingJavaGridDetector.detect(LumaImage(bitmap.width, bitmap.height, luma), cancellation)
    }

    fun scan(bitmap: Bitmap): Scan = try {
        // Construct per import, on the existing worker; no shared mutable native reader.
        val reader = BarcodeReader(BarcodeReader.Options(
            formats = setOf(BarcodeReader.Format.QR_CODE_MODEL_2),
            tryHarder = true,
            tryRotate = true,
            tryInvert = true,
            tryDownscale = true,
            returnErrors = true,
            maxNumberOfSymbols = 16,
            textMode = BarcodeReader.TextMode.PLAIN,
        ))
        val results = reader.read(bitmap)
        val boundaries = results.map { result ->
            val p = result.position
            ImageQuad(
                PointD(p.topLeft.x.toDouble(), p.topLeft.y.toDouble()),
                PointD(p.topRight.x.toDouble(), p.topRight.y.toDouble()),
                PointD(p.bottomRight.x.toDouble(), p.bottomRight.y.toDouble()),
                PointD(p.bottomLeft.x.toDouble(), p.bottomLeft.y.toDouble()),
            )
        }
        // Native payload bytes are only for the ordinary scan display, never RS repair evidence.
        val decoded = results.firstOrNull { it.error == null }?.let {
            Result(it.text, it.bytes?.copyOf(), emptyArray(), BarcodeFormat.QR_CODE)
        }
        Scan(boundaries, decoded)
    } catch (_: LinkageError) {
        // Unsupported ABI/load failure: importing and finder-independent recovery still work.
        Log.w("QrGridDetector", "Native detector unavailable; using verified Java geometry and periodicity estimation")
        Scan(emptyList())
    } catch (_: RuntimeException) {
        Log.w("QrGridDetector", "Native detection failed; using verified Java geometry and periodicity estimation")
        Scan(emptyList())
    }
}
