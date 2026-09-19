package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import io.github.earthdiver.qrrepair.core.Homography
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.hypot

object BitmapUtils {
    private const val MAX_TRANSFORM_DIMENSION = 2_600
    data class Prepared(val bitmap: Bitmap, val luma: LumaImage)
    data class Rectified(val prepared: Prepared, val qrQuad: io.github.earthdiver.qrrepair.core.ImageQuad)

    /** Creates a lossless sampling source for a matrix imported without an original image. */
    fun renderTextMatrixSource(matrix: QrMatrix, moduleSize: Int = 8, quietModules: Int = 4): Bitmap {
        require(moduleSize >= 2 && quietModules >= 0)
        val size = (matrix.dimension + quietModules * 2) * moduleSize
        val pixels = IntArray(size * size) { Color.WHITE }
        for (my in 0 until matrix.dimension) for (mx in 0 until matrix.dimension) {
            val color = when (matrix[mx, my].value) {
                ModuleValue.ZERO -> Color.WHITE
                ModuleValue.ONE -> Color.BLACK
                ModuleValue.ERASED -> Color.rgb(128, 128, 128)
            }
            val left = (quietModules + mx) * moduleSize
            val top = (quietModules + my) * moduleSize
            for (y in top until top + moduleSize) for (x in left until left + moduleSize) {
                pixels[y * size + x] = color
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    fun prepare(source: Bitmap, maxDimension: Int = 1800): Prepared {
        val longest = max(source.width, source.height)
        val bitmap = if (longest > maxDimension) {
            val scale = maxDimension.toDouble() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else source

        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luma = IntArray(argb.size)
        val rgb = IntArray(argb.size)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            rgb[i] = c and 0xFFFFFF
            luma[i] = (299 * r + 587 * g + 114 * b + 500) / 1000
        }
        return Prepared(bitmap, LumaImage(bitmap.width, bitmap.height, luma, rgb))
    }

    /** Rotates pixels into a new white-backed bitmap; every downstream coordinate uses this image. */
    fun rotate(source: Bitmap, degrees: Float): Prepared {
        if (degrees % 360f == 0f) return prepare(source)
        val matrix = Matrix().apply { setRotate(degrees) }
        val bounds = android.graphics.RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        matrix.mapRect(bounds)
        val out = Bitmap.createBitmap(
            kotlin.math.ceil(bounds.width().toDouble()).toInt().coerceAtLeast(1),
            kotlin.math.ceil(bounds.height().toDouble()).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(out).apply {
            drawColor(Color.WHITE)
            translate(-bounds.left, -bounds.top)
            drawBitmap(source, matrix, null)
        }
        return try {
            prepare(out).also { prepared ->
                if (prepared.bitmap !== out) out.recycle()
            }
        } catch (error: Throwable) {
            out.recycle()
            throw error
        }
    }

    /** Rectifies [quad] and retains source pixels for [quietModules] beyond every QR edge. */
    fun rectify(
        source: Bitmap,
        quad: io.github.earthdiver.qrrepair.core.ImageQuad,
        version: Int,
        quietModules: Double = 6.0,
    ): Rectified {
        require(version in 1..40 && quietModules >= 0.0)
        val top = hypot(quad.topRight.x - quad.topLeft.x, quad.topRight.y - quad.topLeft.y)
        val bottom = hypot(quad.bottomRight.x - quad.bottomLeft.x, quad.bottomRight.y - quad.bottomLeft.y)
        val left = hypot(quad.bottomLeft.x - quad.topLeft.x, quad.bottomLeft.y - quad.topLeft.y)
        val right = hypot(quad.bottomRight.x - quad.topRight.x, quad.bottomRight.y - quad.topRight.y)
        // A QR matrix is square. One shared side and module pitch keep all four margins equal.
        val requestedSide = ((top + bottom + left + right) / 4.0).roundToInt().coerceAtLeast(2)
        val moduleSpan = (17 + 4 * version - 1).coerceAtLeast(1)
        val expansion = 1.0 + 2.0 * quietModules / moduleSpan
        var side = minOf(requestedSide, (MAX_TRANSFORM_DIMENSION / expansion).toInt().coerceAtLeast(2))
        var margin = (side.toDouble() / moduleSpan * quietModules).roundToInt().coerceAtLeast(0)
        while (side + margin * 2 > MAX_TRANSFORM_DIMENSION && side > 2) {
            side--
            margin = (side.toDouble() / moduleSpan * quietModules).roundToInt().coerceAtLeast(0)
        }
        val outputSize = side + margin * 2
        require(outputSize in 1..MAX_TRANSFORM_DIMENSION) { "Rectified image is too large" }
        val qrQuad = io.github.earthdiver.qrrepair.core.ImageQuad(
            PointD(margin.toDouble(), margin.toDouble()),
            PointD((margin + side - 1).toDouble(), margin.toDouble()),
            PointD((margin + side - 1).toDouble(), (margin + side - 1).toDouble()),
            PointD(margin.toDouble(), (margin + side - 1).toDouble()),
        )
        val destination = listOf(
            qrQuad.topLeft, qrQuad.topRight, qrQuad.bottomRight, qrQuad.bottomLeft,
        )
        val destinationToSource = Homography.fromCorrespondences(destination, quad.asList())
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val outputPixels = IntArray(outputSize * outputSize) { Color.WHITE }
        for (y in 0 until outputSize) for (x in 0 until outputSize) {
            val p = destinationToSource.map(x.toDouble(), y.toDouble())
            val sx = p.x.roundToInt()
            val sy = p.y.roundToInt()
            if (sx in 0 until source.width && sy in 0 until source.height) {
                outputPixels[y * outputSize + x] = sourcePixels[sy * source.width + sx]
            }
        }
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        out.setPixels(outputPixels, 0, outputSize, 0, 0, outputSize, outputSize)
        return try {
            Rectified(prepare(out).also { prepared ->
                if (prepared.bitmap !== out) out.recycle()
            }, qrQuad)
        } catch (error: Throwable) {
            out.recycle()
            throw error
        }
    }
}
