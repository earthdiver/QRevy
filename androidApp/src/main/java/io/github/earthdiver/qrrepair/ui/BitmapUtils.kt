package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import io.github.earthdiver.qrrepair.core.Homography
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.LumaImage
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.hypot

object BitmapUtils {
    fun resample(source: Bitmap, width: Int, height: Int): Bitmap {
        val pixels = io.github.earthdiver.qrrepair.core.ImageResampling.resize(
            source.width, source.height, width, height) { y, row ->
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun boundImportedSource(source: Bitmap): Bitmap {
        val sampling = io.github.earthdiver.qrrepair.core.ImageResampling
        val sample = sampling.importSample(source.width, source.height)
        val pixels = sampling.downsample(source.width, source.height, sample) { y, row ->
            source.getPixels(row, 0, source.width, 0, y, source.width, 1)
        }
        return Bitmap.createBitmap(pixels, sampling.sampledSize(source.width, sample),
            sampling.sampledSize(source.height, sample), Bitmap.Config.ARGB_8888)
    }

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
        // Retain the exact historical raster when opening an unchanged legacy session.
        if (settings.legacyImageRotationDegrees != null &&
            settings.rotationDegrees == settings.legacyImageRotationDegrees) {
            return legacyForSettings(source, settings, quietModules)
        }
        var working = source
        try {
            if (settings.perspectiveCorrectionQuad != null || settings.rotationDegrees % 360f != 0f) {
                working = renderTransform(source.bitmap, imageTransform(source.bitmap, settings, quietModules))
            }
            val adjusted = adjust(working, settings.imageAdjustment)
            if (working.bitmap !== source.bitmap && working.bitmap !== adjusted.bitmap) working.bitmap.recycle()
            return adjusted
        } catch (error: Throwable) {
            if (working.bitmap !== source.bitmap && !working.bitmap.isRecycled) working.bitmap.recycle()
            throw error
        }
    }

    private fun legacyForSettings(source: Prepared, settings: PreprocessSettings, quietModules: Double): Prepared {
        var working = source
        try {
            if (settings.rotationDegrees % 360f != 0f) working = legacyRotate(source.bitmap, settings.rotationDegrees)
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

    /** Longest side of the analysis image. */
    const val MAX_PREPARED_DIMENSION = 1_800
    /** Longest side of the reduced copy used to locate symbol content for the initial crop. */
    private const val EXTENT_DIMENSION = 1_024
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

    /** Initial crop around the symbol content, located on a reduced copy; null keeps the whole image. */
    fun symbolInitialCrop(source: Bitmap): RectF? {
        val reduced = prepare(source, EXTENT_DIMENSION)
        try {
            val f = io.github.earthdiver.qrrepair.core.SymbolExtent.initialCrop(reduced.luma) ?: return null
            return RectF((f[0] * source.width).toFloat(), (f[1] * source.height).toFloat(),
                (f[2] * source.width).toFloat(), (f[3] * source.height).toFloat())
        } finally {
            if (reduced.bitmap !== source && !reduced.bitmap.isRecycled) reduced.bitmap.recycle()
        }
    }

    /**
     * Scale applied to a crop before analysis. The basis is the larger of the crop and the initial
     * crop. Narrowing the crop does not increase the scale; wider crops reduce it when needed to
     * keep the analysis image within the maximum dimension.
     */
    fun analysisCropScale(cropWidth: Float, cropHeight: Float, basisWidth: Float, basisHeight: Float,
                          maxDimension: Int = MAX_PREPARED_DIMENSION): Double =
        minOf(1.0, maxDimension / maxOf(cropWidth, cropHeight, basisWidth, basisHeight).toDouble())

    fun prepare(source: Bitmap, maxDimension: Int = MAX_PREPARED_DIMENSION): Prepared {
        val (width, height) = preparedSize(source.width, source.height, maxDimension)
        val scaled = if (width != source.width || height != source.height) {
            resample(source, width, height)
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

    private fun preparedSize(width: Int, height: Int, maxDimension: Int = MAX_PREPARED_DIMENSION): Pair<Int, Int> {
        val scale = minOf(1.0, maxDimension.toDouble() / max(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private class RotationLayout(source: Bitmap, degrees: Float) {
        val matrix = Matrix().apply { setRotate(degrees) }
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()).also(matrix::mapRect)
        val width = kotlin.math.ceil(bounds.width().toDouble()).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(bounds.height().toDouble()).toInt().coerceAtLeast(1)

        fun pixelCenterTransform(): Matrix {
            val (preparedWidth, preparedHeight) = preparedSize(width, height)
            return Matrix(matrix).apply {
                preTranslate(.5f, .5f)
                postTranslate(-bounds.left, -bounds.top)
                postScale(preparedWidth.toFloat() / width, preparedHeight.toFloat() / height)
                postTranslate(-.5f, -.5f)
            }
        }
    }

    /** Maps through the immutable source so the ordered grid survives every image transform. */
    internal fun gridAfterRotation(source: Bitmap, quad: ImageQuad, before: PreprocessSettings,
                                   degrees: Float): ImageQuad {
        val toSource = imageTransform(source, before).sourceToImage.inverse()
        val toImage = imageTransform(source, before.copy(rotationDegrees = degrees)).sourceToImage
        return mapQuad(quad) { toImage.map(toSource.map(it)) }
    }

    /** Rotates pixels into a new white-backed bitmap; every downstream coordinate uses this image. */
    fun rotate(source: Bitmap, degrees: Float): Prepared {
        if (degrees % 360f == 0f) return prepare(source)
        return renderTransform(source, imageTransform(source, PreprocessSettings(rotationDegrees = degrees)))
    }

    private fun legacyRotate(source: Bitmap, degrees: Float): Prepared {
        if (degrees % 360f == 0f) return prepare(source)
        val layout = RotationLayout(source, degrees)
        val out = Bitmap.createBitmap(
            layout.width,
            layout.height,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(out).apply {
            drawColor(Color.WHITE)
            translate(-layout.bounds.left, -layout.bounds.top)
            drawBitmap(source, layout.matrix, null)
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

    internal data class ImageTransform(
        val width: Int, val height: Int, val sourceToImage: Homography,
        val baseWidth: Int, val baseHeight: Int, val imageToBase: Homography,
    )

    internal fun mapQuad(quad: ImageQuad, mapped: (PointD) -> PointD): ImageQuad =
        ImageQuad(mapped(quad.topLeft), mapped(quad.topRight), mapped(quad.bottomRight), mapped(quad.bottomLeft))

    internal fun sourceQuad(source: Bitmap, settings: PreprocessSettings, selection: ImageQuad): ImageQuad {
        val inverse = imageTransform(source, settings).sourceToImage.inverse()
        return mapQuad(selection) {
            val p = inverse.map(it)
            // Canonicalize sub-micropixel roundoff from inverse mappings, not image measurements.
            PointD(kotlin.math.round(p.x * 1_000_000.0) / 1_000_000.0,
                kotlin.math.round(p.y * 1_000_000.0) / 1_000_000.0)
        }
    }

    private val identity = Homography(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)

    private fun compose(after: Homography, before: Homography): Homography {
        fun values(h: Homography) = doubleArrayOf(h.h11, h.h12, h.h13, h.h21, h.h22, h.h23, h.h31, h.h32, 1.0)
        val a = values(after); val b = values(before)
        val c = DoubleArray(9) { i -> (0..2).sumOf { k -> a[(i / 3) * 3 + k] * b[k * 3 + i % 3] } }
        require(c[8].isFinite() && kotlin.math.abs(c[8]) > 1e-12)
        return Homography(c[0] / c[8], c[1] / c[8], c[2] / c[8], c[3] / c[8], c[4] / c[8],
            c[5] / c[8], c[6] / c[8], c[7] / c[8])
    }

    private fun Matrix.homography(): Homography {
        val values = FloatArray(9)
        getValues(values)
        return Homography(values[0].toDouble(), values[1].toDouble(), values[2].toDouble(),
            values[3].toDouble(), values[4].toDouble(), values[5].toDouble(), values[6].toDouble(), values[7].toDouble())
    }

    /** Geometry only: no intermediate bitmap or resampling, including output size limiting. */
    internal fun imageTransform(source: Bitmap, settings: PreprocessSettings, quietModules: Double = 1.0): ImageTransform {
        var width = source.width
        var height = source.height
        var sourceToBase = identity
        if (settings.perspectiveCorrectionQuad == null && settings.legacyImageRotationDegrees == settings.rotationDegrees) {
            val oldLayout = RotationLayout(source, settings.rotationDegrees)
            val (outWidth, outHeight) = preparedSize(oldLayout.width, oldLayout.height)
            val oldRotation = oldLayout.pixelCenterTransform().homography()
            return ImageTransform(outWidth, outHeight, oldRotation, width, height, oldRotation.inverse())
        }
        settings.perspectiveCorrectionQuad?.let { quad ->
            val layout = rectificationLayout(quad, settings.version, quietModules)
            width = minOf(layout.outputSize, MAX_PREPARED_DIMENSION)
            height = width
            sourceToBase = Homography.fromCorrespondences(quad.asList(), layout.quadAtSize(width, height).asList())
            settings.legacyImageRotationDegrees?.let { reference ->
                val oldRotation = RotationLayout(source, reference).pixelCenterTransform().homography()
                sourceToBase = compose(sourceToBase, oldRotation)
            }
        }
        val degrees = settings.rotationDegrees.toDouble() -
            (settings.legacyImageRotationDegrees?.takeIf { settings.perspectiveCorrectionQuad != null } ?: 0f)
        val radians = degrees * kotlin.math.PI / 180.0
        fun snapped(value: Double) = if (kotlin.math.abs(value) < 1e-12) 0.0 else value
        val c = snapped(kotlin.math.cos(radians)); val s = snapped(kotlin.math.sin(radians))
        val left = minOf(0.0, width * c, -height * s, width * c - height * s)
        val top = minOf(0.0, width * s, height * c, width * s + height * c)
        val rotatedWidth = kotlin.math.ceil(kotlin.math.abs(width * c) + kotlin.math.abs(height * s)).toInt().coerceAtLeast(1)
        val rotatedHeight = kotlin.math.ceil(kotlin.math.abs(width * s) + kotlin.math.abs(height * c)).toInt().coerceAtLeast(1)
        val (outWidth, outHeight) = preparedSize(rotatedWidth, rotatedHeight)
        val sx = outWidth.toDouble() / rotatedWidth
        val sy = outHeight.toDouble() / rotatedHeight
        val rotation = Homography(c * sx, -s * sx, ((c - s) * .5 - left) * sx - .5,
            s * sy, c * sy, ((s + c) * .5 - top) * sy - .5, 0.0, 0.0)
        return ImageTransform(outWidth, outHeight, compose(rotation, sourceToBase), width, height, rotation.inverse())
    }

    private fun renderTransform(source: Bitmap, transform: ImageTransform): Prepared {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        fun channel(pixel: Int, shift: Int): Int {
            val alpha = Color.alpha(pixel)
            return (((pixel ushr shift) and 255) * alpha + 255 * (255 - alpha) + 127) / 255
        }
        fun pixel(x: Int, y: Int): Int = if (x in 0 until source.width && y in 0 until source.height)
            pixels[y * source.width + x] else Color.WHITE
        val inverse = transform.sourceToImage.inverse()
        val output = IntArray(transform.width * transform.height) { Color.WHITE }
        for (y in 0 until transform.height) for (x in 0 until transform.width) {
            val base = transform.imageToBase.map(x.toDouble(), y.toDouble())
            if (base.x < -.5 || base.y < -.5 || base.x >= transform.baseWidth - .5 || base.y >= transform.baseHeight - .5) continue
            val point = runCatching { inverse.map(x.toDouble(), y.toDouble()) }.getOrNull() ?: continue
            if (!point.x.isFinite() || !point.y.isFinite() || point.x < -1.0 || point.y < -1.0 ||
                point.x >= source.width || point.y >= source.height) continue
            val ix = kotlin.math.floor(point.x).toInt(); val iy = kotlin.math.floor(point.y).toInt()
            val fx = point.x - ix; val fy = point.y - iy
            val p00 = pixel(ix, iy); val p10 = pixel(ix + 1, iy)
            val p01 = pixel(ix, iy + 1); val p11 = pixel(ix + 1, iy + 1)
            fun interpolated(shift: Int): Int = ((channel(p00, shift) * (1 - fx) + channel(p10, shift) * fx) * (1 - fy) +
                (channel(p01, shift) * (1 - fx) + channel(p11, shift) * fx) * fy).roundToInt().coerceIn(0, 255)
            output[y * transform.width + x] = Color.rgb(interpolated(16), interpolated(8), interpolated(0))
        }
        val bitmap = Bitmap.createBitmap(output, transform.width, transform.height, Bitmap.Config.ARGB_8888)
        return try { prepare(bitmap) } catch (error: Throwable) { bitmap.recycle(); throw error }
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
