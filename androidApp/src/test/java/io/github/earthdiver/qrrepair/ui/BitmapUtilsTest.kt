package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
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
        assertTrue(corrected.qrQuad.bottomRight.x <= 2_600.0)
    }

    @Test fun rotateRecyclesOversizedIntermediateAfterPrepareScalesIt() {
        val source = Bitmap.createBitmap(1_800, 1_800, Bitmap.Config.ARGB_8888)

        val rotated = BitmapUtils.rotate(source, 45f)

        assertEquals(1_800, maxOf(rotated.bitmap.width, rotated.bitmap.height))
        assertTrue(!rotated.bitmap.isRecycled)
        assertTrue(!source.isRecycled)
    }
}
