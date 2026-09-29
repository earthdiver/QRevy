package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MirroredInputOverlayTest {
    private val quad = ImageQuad(PointD(20.0, 25.0), PointD(220.0, 15.0), PointD(215.0, 225.0), PointD(25.0, 210.0))
    private fun overlay() = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
        setBitmap(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888))
        setImageQuad(quad)
    }

    @Test fun toggleExchangesRolesAndPreservesConfirmedPolarityAndImagePositions() {
        val overlay = overlay()
        val candidate = FinderCandidate(190.0, 50.0, 10.0, 0.0, 1.0, 5, polarity = FinderPolarity.INVERTED)
        overlay.setVerifiedFinder(FinderRole.TOP_RIGHT, candidate)
        overlay.setMirroredInput(true)
        assertEquals(quad.transposed(), overlay.imageQuad())
        assertEquals(mapOf(FinderRole.BOTTOM_LEFT to PointD(190.0, 50.0)), overlay.finderPoints())
        assertEquals(FinderPolarity.INVERTED, overlay.confirmedFinderPolarity())
        assertTrue(overlay.isLogicalGridAssigned())
        val original = GridGeometry(1, FinderRole.TOP_LEFT, 20.0, 25.0, 10.0, 0.0, quad)
        val mirrored = original.copy(imageQuad = overlay.imageQuad())
        assertTrue(io.github.earthdiver.qrrepair.RecoveryDiagnosticPresenter(false)
            .geometryDiagnosticText(mirrored).contains("mirrored input"))
        assertFalse(io.github.earthdiver.qrrepair.RecoveryDiagnosticPresenter(false)
            .geometryDiagnosticText(original).contains("mirrored input"))
        for (coord in listOf(Coord(0, 0), Coord(12, 9), Coord(20, 3), Coord(20, 20))) {
            assertTrue(original.moduleToImage(coord.x.toDouble(), coord.y.toDouble())
                .distanceTo(mirrored.moduleToImage(coord.y.toDouble(), coord.x.toDouble())) < 1e-8)
        }
        overlay.setMirroredInput(true)
        assertEquals(quad.transposed(), overlay.imageQuad())
        overlay.setMirroredInput(false)
        assertEquals(quad, overlay.imageQuad())
        assertEquals(mapOf(FinderRole.TOP_RIGHT to PointD(190.0, 50.0)), overlay.finderPoints())
    }

    @Test fun roleReassignmentAndImageResetKeepMirroredHandedness() {
        val overlay = overlay()
        overlay.setFinderPoint(FinderRole.TOP_RIGHT, PointD(190.0, 50.0))
        overlay.setMirroredInput(true)
        assertTrue(overlay.removeConfirmedFinder(FinderRole.BOTTOM_LEFT))
        assertFalse(overlay.isLogicalGridAssigned())
        val before = overlay.imageQuad()
        overlay.setFinderPoint(FinderRole.TOP_RIGHT, before.bottomLeft)
        val after = overlay.imageQuad()
        assertEquals(before.bottomLeft, after.topRight)
        fun winding(q: ImageQuad): Double {
            val x = q.topRight - q.topLeft
            val y = q.bottomLeft - q.topLeft
            return x.x * y.y - x.y * y.x
        }
        assertTrue(winding(after) < 0)
        overlay.setBitmap(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888))
        assertTrue(winding(overlay.imageQuad()) < 0)
        assertFalse(overlay.isLogicalGridAssigned())
    }
}
