package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.Homography
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BitmapUtilsTest {
    @Test fun rectifyMapsSelectedQuadToRectangle() {
        val source = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 20..119) for (x in 20..119) setPixel(x, y, Color.BLACK)
        }
        val corrected = BitmapUtils.rectify(
            source,
            ImageQuad(PointD(20.0, 20.0), PointD(120.0, 20.0), PointD(120.0, 120.0), PointD(20.0, 120.0)),
            version = 1,
        )

        assertEquals(160, corrected.prepared.bitmap.width)
        assertEquals(160, corrected.prepared.bitmap.height)
        assertEquals(PointD(30.0, 30.0), corrected.qrQuad.topLeft)
        assertEquals(PointD(129.0, 129.0), corrected.qrQuad.bottomRight)
        assertTrue(Color.red(corrected.prepared.bitmap.getPixel(80, 80)) < 32)
        assertEquals(Color.WHITE, corrected.prepared.bitmap.getPixel(0, 0))
        assertEquals(corrected.prepared.bitmap.width * corrected.prepared.bitmap.height, corrected.prepared.luma.pixels.size)
    }

    @Test fun screenAndDiagnosticMarginsUseOneUniformModulePitch() {
        val source = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(120.0, 20.0), PointD(120.0, 120.0), PointD(20.0, 120.0))

        val screen = BitmapUtils.rectify(source, quad, version = 1, quietModules = 1.0)
        val diagnostic = BitmapUtils.rectify(source, quad, version = 1, quietModules = 4.0)

        assertEquals(5.0, screen.qrQuad.topLeft.x, 0.0)
        assertEquals(5.0, screen.qrQuad.topLeft.y, 0.0)
        assertEquals(140, diagnostic.prepared.bitmap.width)
        assertEquals(140, diagnostic.prepared.bitmap.height)
        assertEquals(20.0, diagnostic.qrQuad.topLeft.x, 0.0)
        assertEquals(20.0, diagnostic.qrQuad.topLeft.y, 0.0)
    }

    @Test fun rectifyBoundsExtremeOutputBeforeAllocatingPixelBuffers() {
        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val corrected = BitmapUtils.rectify(
            source,
            ImageQuad(
                PointD(0.0, 0.0),
                PointD(10_000.0, 0.0),
                PointD(10_000.0, 10_000.0),
                PointD(0.0, 10_000.0),
            ),
            version = 1,
        )

        assertTrue(corrected.prepared.bitmap.width <= 1_800)
        assertTrue(corrected.prepared.bitmap.height <= 1_800)
        for (corner in corrected.qrQuad.asList()) {
            assertTrue(corner.x in 0.0..(corrected.prepared.bitmap.width - 1).toDouble())
            assertTrue(corner.y in 0.0..(corrected.prepared.bitmap.height - 1).toDouble())
        }
    }

    @Test fun resizedRectificationSamplesTheOriginalQrModules() {
        val modules = Encoder.encode("RESIZED GRID", ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.QR_VERSION to 1)).matrix
        val pitch = 84
        val width = modules.width * pitch
        val pixels = IntArray(width * width) { i ->
            if (modules[(i % width) / pitch, (i / width) / pitch].toInt() == 1) Color.BLACK else Color.WHITE
        }
        val source = Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
        val first = (pitch - 1) / 2.0
        val last = first + (modules.width - 1) * pitch
        val corrected = BitmapUtils.rectify(source,
            ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last)),
            version = 1, quietModules = 1.0)
        try {
            assertEquals(1_800, corrected.prepared.bitmap.width)
            val edge = (modules.width - 1).toDouble()
            val mapping = Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(edge, 0.0), PointD(edge, edge), PointD(0.0, edge)),
                corrected.qrQuad.asList())
            for (y in 0 until modules.height) for (x in 0 until modules.width) {
                val point = mapping.map(x.toDouble(), y.toDouble())
                val luma = requireNotNull(corrected.prepared.luma.sampleMean(point.x, point.y, 0.0))
                assertEquals("module ($x, $y)", modules[x, y].toInt(), if (luma < 128) 1 else 0)
            }
        } finally {
            corrected.prepared.bitmap.recycle()
            source.recycle()
        }
    }

    @Test fun rotateRecyclesOversizedIntermediateAfterPrepareScalesIt() {
        val source = Bitmap.createBitmap(1_800, 1_800, Bitmap.Config.ARGB_8888)

        val rotated = BitmapUtils.rotate(source, 45f)

        assertEquals(1_800, maxOf(rotated.bitmap.width, rotated.bitmap.height))
        assertTrue(!rotated.bitmap.isRecycled)
        assertTrue(!source.isRecycled)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun rotatedAndRectifiedGridKeepsSourceModulesThroughResizingAndReset() {
        val modules = Encoder.encode("ROTATED GRID", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1)).matrix
        val pitch = 60
        val size = 29 * pitch
        val pixels = IntArray(size * size) { i ->
            val x = (i % size) / pitch - 4
            val y = (i / size) / pitch - 4
            if (x in 0..20 && y in 0..20 && modules[x, y].toInt() == 1) Color.BLACK else Color.WHITE
        }
        val source = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val first = 4.5 * pitch - .5
        val last = first + 20 * pitch
        val original = ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last))
        val rotated = BitmapUtils.rotate(source, 45f)
        val quad = BitmapUtils.gridAfterRotation(source, original, PreprocessSettings(), 45f)
        val rectified = BitmapUtils.rectify(rotated.bitmap, quad, 1, quietModules = 1.0)
        try {
            assertEquals(1_800, rotated.bitmap.width)
            val mapping = Homography.fromCorrespondences(
                listOf(PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 20.0), PointD(0.0, 20.0)), quad.asList())
            for (y in 0..20) for (x in 0..20) {
                val p = mapping.map(x.toDouble(), y.toDouble())
                val luma = requireNotNull(rotated.luma.sampleMean(p.x, p.y, 0.0))
                assertEquals("module ($x, $y)", modules[x, y].toInt(), if (luma < 128) 1 else 0)
            }
            val reset = BitmapUtils.gridAfterRotation(source, rectified.qrQuad,
                PreprocessSettings(rotationDegrees = 45f, perspectiveCorrectionQuad = quad), 0f)
            original.asList().zip(reset.asList()).forEach { (expected, actual) ->
                assertTrue(expected.distanceTo(actual) < .001)
            }
        } finally {
            rectified.prepared.bitmap.recycle()
            rotated.bitmap.recycle()
            source.recycle()
        }
    }
}
