package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.view.MotionEvent
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
class FinderInteractionTest {
    private fun withOverlay(block: (PreprocessOverlayView) -> Unit) {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        try {
            val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(bitmap)
                setVersion(1)
                setImageQuad(ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
                    PointD(220.0, 220.0), PointD(20.0, 220.0)))
                assertTrue(setFinderPoint(FinderRole.TOP_LEFT, PointD(50.0, 50.0)))
                layout(0, 0, 240, 240)
            }
            block(view)
        } finally {
            bitmap.recycle()
        }
    }

    private fun touch(view: PreprocessOverlayView, action: Int, point: PointD) {
        MotionEvent.obtain(0, 10, action, point.x.toFloat(), point.y.toFloat(), 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun tap(view: PreprocessOverlayView, point: PointD) {
        touch(view, MotionEvent.ACTION_DOWN, point)
        touch(view, MotionEvent.ACTION_UP, point)
    }

    @Test fun choosingRemovalCancelsPendingPlacement() = withOverlay { view ->
        var hints = 0
        view.onFinderHintPlaced = { _, _, _, _ -> hints++ }
        view.finderPlacementRole = FinderRole.TOP_LEFT
        assertTrue(view.beginFinderRemoval())
        tap(view, PointD(50.0, 50.0))
        assertEquals(0, hints)
        assertNull(view.finderPlacementRole)
        assertTrue(view.finderPoints().isEmpty())
        assertFalse(view.isLogicalGridAssigned())
    }

    @Test fun choosingPlacementCancelsRemovalAndKeepsCornersEditable() = withOverlay { view ->
        assertTrue(view.beginFinderRemoval())
        view.finderPlacementRole = FinderRole.TOP_LEFT
        view.onFinderHintPlaced = { role, point, _, _ -> assertTrue(view.placeFinder(role, point)) }
        tap(view, PointD(50.0, 50.0))
        assertEquals(setOf(FinderRole.TOP_LEFT), view.finderPoints().keys)
        assertNull(view.finderPlacementRole)
        touch(view, MotionEvent.ACTION_DOWN, PointD(220.0, 220.0))
        touch(view, MotionEvent.ACTION_MOVE, PointD(200.0, 200.0))
        touch(view, MotionEvent.ACTION_UP, PointD(200.0, 200.0))
        assertEquals(PointD(200.0, 200.0), view.imageQuad().bottomRight)
    }

    @Test fun removalWithoutConfirmedFindersStillCancelsPendingPlacement() = withOverlay { view ->
        assertTrue(view.removeConfirmedFinder(FinderRole.TOP_LEFT))
        var hints = 0
        view.onFinderHintPlaced = { _, _, _, _ -> hints++ }
        view.finderPlacementRole = FinderRole.TOP_LEFT
        assertFalse(view.beginFinderRemoval())
        tap(view, PointD(50.0, 50.0))
        assertEquals(0, hints)
        assertNull(view.finderPlacementRole)
        assertTrue(view.finderPoints().isEmpty())
    }

    @Test fun repeatedModeChangesPreserveConstraintsAndReleaseTheNextCornerGesture() = withOverlay { view ->
        var hints = 0
        view.onFinderHintPlaced = { role, point, _, _ ->
            hints++
            assertTrue(view.placeFinder(role, point))
        }
        repeat(10) { cycle ->
            val before = view.imageQuad()
            view.finderPlacementRole = FinderRole.TOP_RIGHT
            assertTrue(view.beginFinderRemoval())
            tap(view, PointD(50.0, 50.0))
            assertEquals(cycle * 2, hints)
            assertTrue(view.finderPoints().isEmpty())
            assertEquals(before, view.imageQuad())
            view.finderPlacementRole = FinderRole.TOP_LEFT
            tap(view, PointD(50.0, 50.0))
            assertTrue(view.beginFinderRemoval())
            view.finderPlacementRole = FinderRole.TOP_LEFT
            tap(view, PointD(50.0, 50.0))
            assertEquals((cycle + 1) * 2, hints)
            assertEquals(mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0)), view.finderPoints())
            val destination = if (cycle % 2 == 0) PointD(200.0, 200.0) else PointD(220.0, 220.0)
            touch(view, MotionEvent.ACTION_DOWN, view.imageQuad().bottomRight)
            touch(view, MotionEvent.ACTION_MOVE, destination)
            touch(view, MotionEvent.ACTION_UP, destination)
            assertEquals(destination, view.imageQuad().bottomRight)
            assertTrue(view.isLogicalGridAssigned())
        }
    }
}
