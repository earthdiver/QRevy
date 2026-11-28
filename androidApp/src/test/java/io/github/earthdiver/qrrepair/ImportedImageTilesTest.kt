package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Matrix
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportedImageTilesTest {
    @Test fun jpegChromaAndExifOrientationsMatchWholeImageReduction() {
        for ((horizontal, vertical) in listOf(1 to 1, 2 to 1, 2 to 2)) for ((width, height) in listOf(2051 to 73, 2603 to 2007)) {
            val bytes = syntheticJpeg(width, height, horizontal, vertical)
            val source = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                for (orientation in 1..8) {
                    val spec = imageOrientationTransform(orientation)
                    val transform = Matrix().apply { setRotate(spec.rotationDegrees); postScale(spec.scaleX, spec.scaleY) }
                    val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, false)
                    val expected = BitmapUtils.boundImportedSource(upright)
                    @Suppress("DEPRECATION")
                    val decoder = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                    val actual = try {
                        decodeImportedTiles(source.width, source.height, orientation) { rect ->
                            readImportedRegion(source.width, rect) { padded ->
                                decoder.decodeRegion(padded, android.graphics.BitmapFactory.Options())
                            }
                        }
                    } finally { decoder.recycle() }
                    try {
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(expected.width * expected.height)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                        assertArrayEquals("Chroma $horizontal:$vertical, EXIF $orientation", b, a)
                    } finally {
                        actual.recycle(); expected.recycle()
                        if (upright !== source) upright.recycle()
                    }
                }
            } finally { source.recycle() }
        }
    }

    @Test fun regionFailuresReleaseImagesAndFallBackToBoundedDecoding() {
        for (failure in listOf("null", "size", "argument")) {
            val file = java.io.File.createTempFile("qrevy-synthetic-fallback-", ".png")
            val source = Bitmap.createBitmap(2603, 2007, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()) }
            val tiles = mutableListOf<Bitmap>()
            var decoder: android.graphics.BitmapRegionDecoder? = null
            var calls = 0
            var result: Bitmap? = null
            try {
                file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val controller = ImageImportController(org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
                    java.util.concurrent.Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) },
                    readRegion = { native, rect ->
                        decoder = native
                        if (++calls == 2) when (failure) {
                            "null" -> null
                            "size" -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { tiles += it }
                            else -> throw IllegalArgumentException("Synthetic region failure")
                        } else native.decodeRegion(rect, android.graphics.BitmapFactory.Options()).also { tiles += it }
                    })
                controller.load(1, android.net.Uri.fromFile(file), null) { result = it }
                val decoded = requireNotNull(result)
                assertEquals(2, calls)
                assertTrue(requireNotNull(decoder).isRecycled)
                assertTrue(tiles.all { it.isRecycled })
                assertTrue(decoded.width <= 2600 && decoded.height <= 2600)
                assertEquals(0xff123456.toInt(), decoded.getPixel(decoded.width - 1, decoded.height - 1))
            } finally { result?.recycle(); source.recycle(); file.delete() }
        }
    }

    @Test fun failureOfBothDecodePathsReportsALocalizedErrorAndReleasesTheDecoder() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val originalLanguage = AppLanguageManager.current
        try {
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(context, language)
                val file = java.io.File.createTempFile("qrevy-synthetic-decode-failure-", ".png")
                val source = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                var decoder: android.graphics.BitmapRegionDecoder? = null
                var error: Throwable? = null
                try {
                    file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val controller = ImageImportController(context.contentResolver,
                        java.util.concurrent.Executor { it.run() }, { it() }, { 1 }, { false }, {}, { error = it },
                        readRegion = { native, _ ->
                            decoder = native
                            file.writeBytes(byteArrayOf(0, 1, 2))
                            null
                        })
                    controller.load(1, android.net.Uri.fromFile(file), null) { fail("Invalid input must not be imported") }
                    assertTrue(requireNotNull(decoder).isRecycled)
                    assertEquals(if (language == AppLanguage.JAPANESE) "画像をデコードできませんでした" else "Could not decode the image",
                        requireNotNull(error).message)
                } finally { source.recycle(); file.delete() }
            }
        } finally { AppLanguageManager.applyTo(context, originalLanguage) }
    }

    @Test fun unexpectedProgrammingErrorsAreNotRetriedAsCodecFailures() {
        val error = IllegalStateException("Synthetic programming error")
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            readImportedRegion(80, android.graphics.Rect(0, 0, 40, 40)) { throw error }
        })
    }

    private fun syntheticJpeg(width: Int, height: Int, horizontal: Int, vertical: Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y,
            (((x * 29 + y * 3) and 255) shl 16) or (((x * 7 + y * 23) and 255) shl 8) or ((x * 41 + y * 11) and 255))
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = java.io.ByteArrayOutputStream()
        try {
            javax.imageio.stream.MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val params = writer.defaultWriteParam
                val metadata = writer.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), params)
                val name = "javax_imageio_jpeg_image_1.0"
                val tree = metadata.getAsTree(name) as javax.imageio.metadata.IIOMetadataNode
                val sof = tree.getElementsByTagName("sof").item(0)
                for (i in 0 until sof.childNodes.length) {
                    val component = sof.childNodes.item(i) as javax.imageio.metadata.IIOMetadataNode
                    component.setAttribute("HsamplingFactor", if (i == 0) horizontal.toString() else "1")
                    component.setAttribute("VsamplingFactor", if (i == 0) vertical.toString() else "1")
                }
                metadata.setFromTree(name, tree)
                writer.write(null, javax.imageio.IIOImage(image, null, metadata), params)
            }
            return bytes.toByteArray()
        } finally { writer.dispose(); image.flush() }
    }

    @Test fun controllerLoadsAnEncodedSourceThroughTheRegionDecoder() {
        val file = java.io.File.createTempFile("qrevy-synthetic-import-", ".png")
        val source = Bitmap.createBitmap(2603, 33, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()) }
        var result: Bitmap? = null
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val controller = ImageImportController(org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
                java.util.concurrent.Executor { it.run() }, { it() }, { 1 }, { false }, {}, { throw AssertionError(it) })
            controller.load(1, android.net.Uri.fromFile(file), null) { result = it }
            val decoded = requireNotNull(result)
            assertEquals(1302, decoded.width)
            assertEquals(17, decoded.height)
            assertEquals(0xff123456.toInt(), decoded.getPixel(1301, 16))
        } finally { result?.recycle(); source.recycle(); file.delete() }
    }

    @Test fun allExifOrientationsMatchWholeImageReductionAtOddTileAndSampleEdges() {
        val width = 2603
        val height = 33
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { i ->
            val alpha = if (i % 7 == 0) 0x80 else 0xff
            (alpha shl 24) or ((i * 13 and 255) shl 16) or ((i / width * 17 and 255) shl 8) or (i * 31 and 255)
        }
        source.setPixels(pixels, 0, width, 0, 0, width, height)
        try {
            for (orientation in 1..8) {
                val spec = imageOrientationTransform(orientation)
                val matrix = Matrix().apply { setRotate(spec.rotationDegrees); postScale(spec.scaleX, spec.scaleY) }
                val upright = Bitmap.createBitmap(source, 0, 0, width, height, matrix, false)
                val expected = BitmapUtils.boundImportedSource(upright)
                val actual = decodeImportedTiles(width, height, orientation) { r ->
                    assertEquals(width, r.width())
                    assertTrue(r.width().toLong() * r.height() <= IMPORT_REGION_PIXELS)
                    Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height())
                        .let { if (it === source) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                }
                try {
                    assertEquals(expected.width, actual.width)
                    assertEquals(expected.height, actual.height)
                    val a = IntArray(actual.width * actual.height)
                    val b = IntArray(a.size)
                    actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                    expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                    assertArrayEquals("EXIF $orientation", b, a)
                } finally {
                    actual.recycle(); expected.recycle()
                    if (upright !== source) upright.recycle()
                }
            }
        } finally { source.recycle() }
    }

    @Test fun largePhotoOnlyRequestsBoundedDecodedRegions() {
        for ((width, height) in listOf(5712 to 4284, 8001 to 6001)) {
            var area = 0L
            val result = decodeImportedTiles(width, height, 6) { r ->
                assertEquals(width, r.width())
                    assertTrue(r.width().toLong() * r.height() <= IMPORT_REGION_PIXELS)
                area += r.width().toLong() * r.height()
                Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()) }
            }
            try {
                assertEquals(width.toLong() * height, area)
                assertTrue(result.width <= 2600 && result.height <= 2600)
                assertEquals(0xff123456.toInt(), result.getPixel(result.width - 1, result.height - 1))
            } finally { result.recycle() }
        }
    }
}
