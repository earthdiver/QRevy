package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import io.github.earthdiver.qrrepair.core.Homography
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.hypot

object BitmapUtils {
    /** Applies tones once to a geometrically prepared source; marker colors remain original. */
    fun adjust(source: Prepared, adjustment: io.github.earthdiver.qrrepair.core.ImageAdjustment): Prepared {
        if (adjustment.isIdentity) return source
        val pixels = IntArray(source.bitmap.width * source.bitmap.height)
        source.bitmap.getPixels(pixels, 0, source.bitmap.width, 0, 0, source.bitmap.width, source.bitmap.height)
        val luma = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = adjustment.argb(pixels[i])
            pixels[i] = c
            luma[i] = (299 * ((c ushr 16) and 255) + 587 * ((c ushr 8) and 255) + 114 * (c and 255) + 500) / 1000
        }
        val bitmap = Bitmap.createBitmap(pixels, source.bitmap.width, source.bitmap.height, Bitmap.Config.ARGB_8888)
        return Prepared(bitmap, LumaImage(bitmap.width, bitmap.height, luma, source.luma.rgbPixels))
    }

    /** Geometry first, tones last: restoring and editing use exactly the same pipeline. */
    fun forSettings(source: Prepared, settings: io.github.earthdiver.qrrepair.core.PreprocessSettings,
                    quietModules: Double = 1.0): Prepared {
        var working = source
        try {
            if (settings.rotationDegrees % 360f != 0f) working = rotate(source.bitmap, settings.rotationDegrees)
            settings.perspectiveCorrectionQuad?.let { quad ->
                val next = rectify(working.bitmap, quad, settings.version, quietModules).prepared
                if (working.bitmap !== source.bitmap && working.bitmap !== next.bitmap) working.bitmap.recycle()
                working = next
            }
            val adjusted = adjust(working, settings.imageAdjustment)
            if (working.bitmap !== source.bitmap && working.bitmap !== adjusted.bitmap) working.bitmap.recycle()
            return adjusted
        } catch (error: Throwable) {
            if (working.bitmap !== source.bitmap && !working.bitmap.isRecycled) working.bitmap.recycle()
            throw error
        }
    }

    private const val MAX_PREPARED_DIMENSION = 1_800
    private const val MAX_TRANSFORM_DIMENSION = 2_600
    data class Prepared(val bitmap: Bitmap, val luma: LumaImage)
    data class Rectified(val prepared: Prepared, val qrQuad: io.github.earthdiver.qrrepair.core.ImageQuad)

    /** Composites transparency before tones. The caller owns any returned copy and the source. */
    internal fun onWhiteBackground(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        if (pixels.all { Color.alpha(it) == 255 }) return source
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val alpha = Color.alpha(pixel)
            val white = 255 * (255 - alpha)
            pixels[i] = Color.rgb(
                (Color.red(pixel) * alpha + white + 127) / 255,
                (Color.green(pixel) * alpha + white + 127) / 255,
                (Color.blue(pixel) * alpha + white + 127) / 255,
            )
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

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

    fun prepare(source: Bitmap, maxDimension: Int = MAX_PREPARED_DIMENSION): Prepared {
        val longest = max(source.width, source.height)
        val scaled = if (longest > maxDimension) {
            val scale = maxDimension.toDouble() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else source

        val bitmap = try {
            onWhiteBackground(scaled)
        } catch (error: Throwable) {
            if (scaled !== source) scaled.recycle()
            throw error
        }
        if (scaled !== source && scaled !== bitmap) scaled.recycle()
        try {
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
        } catch (error: Throwable) {
            if (bitmap !== source) bitmap.recycle()
            throw error
        }
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

    private data class RectificationLayout(val outputSize: Int, val qrQuad: ImageQuad) {
        fun quadAtSize(width: Int, height: Int): ImageQuad {
            if (width == outputSize && height == outputSize) return qrQuad
            val scaleX = width.toDouble() / outputSize
            val scaleY = height.toDouble() / outputSize
            // Coordinates denote pixel centers, while bitmap resizing scales pixel edges.
            fun scaled(point: PointD) = PointD((point.x + .5) * scaleX - .5, (point.y + .5) * scaleY - .5)
            return ImageQuad(scaled(qrQuad.topLeft), scaled(qrQuad.topRight),
                scaled(qrQuad.bottomRight), scaled(qrQuad.bottomLeft))
        }
    }

    /** Canonical physical grid in the prepared image, without allocating another bitmap. */
    internal fun rectifiedGrid(quad: ImageQuad, version: Int, quietModules: Double = 1.0): ImageQuad {
        val layout = rectificationLayout(quad, version, quietModules)
        val size = minOf(layout.outputSize, MAX_PREPARED_DIMENSION)
        return layout.quadAtSize(size, size)
    }

    private fun rectificationLayout(quad: ImageQuad, version: Int, quietModules: Double): RectificationLayout {
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
        val qrQuad = ImageQuad(
            PointD(margin.toDouble(), margin.toDouble()),
            PointD((margin + side - 1).toDouble(), margin.toDouble()),
            PointD((margin + side - 1).toDouble(), (margin + side - 1).toDouble()),
            PointD(margin.toDouble(), (margin + side - 1).toDouble()),
        )
        return RectificationLayout(outputSize, qrQuad)
    }

    /** Rectifies [quad] and retains source pixels for [quietModules] beyond every QR edge. */
    fun rectify(
        source: Bitmap,
        quad: io.github.earthdiver.qrrepair.core.ImageQuad,
        version: Int,
        quietModules: Double = 6.0,
    ): Rectified {
        val layout = rectificationLayout(quad, version, quietModules)
        val outputSize = layout.outputSize
        val qrQuad = layout.qrQuad
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
            val prepared = prepare(out)
            val preparedQuad = layout.quadAtSize(prepared.bitmap.width, prepared.bitmap.height)
            if (prepared.bitmap !== out) out.recycle()
            Rectified(prepared, preparedQuad)
        } catch (error: Throwable) {
            out.recycle()
            throw error
        }
    }
}
