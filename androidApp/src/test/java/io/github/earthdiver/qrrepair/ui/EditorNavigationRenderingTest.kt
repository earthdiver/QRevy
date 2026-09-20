package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorNavigationRenderingTest {
    private class Fixture {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 95.0, 95.0, 15.0, 0.0)
        val matrix = QrMatrix(21).apply {
            for (y in 0..20) for (x in 0..20) {
                val value = when (x % 4) { 0 -> ModuleValue.ZERO; 1 -> ModuleValue.ONE; else -> ModuleValue.ERASED }
                set(x, y, value, 1f, if (x % 2 == 0) ModuleSource.USER else ModuleSource.USER_SOFT)
            }
            setPrioritySearch(10, 10, true)
        }
        var callbacks = 0
        val view = QrEditorView(RuntimeEnvironment.getApplication()).apply {
            setDocument(source, geometry, this@Fixture.matrix)
            setCodewordOverlayVersion(1)
            showCodewordOverlay = true
            showQrStructureOverlay = true
            setSelection(setOf(Coord(10, 10)))
            onCellSelected = { _, _ -> callbacks++ }
            onBrushApplied = { _, _ -> callbacks++ }
            onGeometryMoved = { _, _ -> callbacks++ }
            onRangeSelected = { callbacks++ }
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 400)
        }
        private val original = matrix.copy()

        fun event(action: Int, x: Float = 200f, y: Float = 200f, time: Long, down: Long = 1000L) {
            MotionEvent.obtain(down, time, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
        }

        fun pinch(action: Int, span: Float, time: Long) {
            val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(2) { i -> MotionEvent.PointerCoords().apply {
                x = 200f; y = 200f + if (i == 0) -span / 2 else span / 2
                pressure = 1f; size = 1f; touchMajor = 20f; touchMinor = 20f
            } }
            MotionEvent.obtain(1000, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0).also { view.onTouchEvent(it); it.recycle() }
        }

        fun centralOverlayPixels(): Int {
            val rendered = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(rendered))
                // The source is uniform and the outer QR boundary stays outside this window.
                return (130 until 270).sumOf { y -> (130 until 270).count { x -> rendered.getPixel(x, y) != Color.LTGRAY } }
            } finally { rendered.recycle() }
        }

        fun assertUnchanged() {
            assertEquals("Navigation must not invoke editing, sampling or selection callbacks", 0, callbacks)
            assertEquals(Coord(10, 10), view.selectedCell())
            for (y in 0..20) for (x in 0..20) {
                assertEquals(original[x, y].value, matrix[x, y].value)
                assertEquals(original[x, y].source, matrix[x, y].source)
                assertEquals(original[x, y].prioritySearch, matrix[x, y].prioritySearch)
            }
        }
    }

    @Test fun panSuppressesAllCellOverlaysAndRestoresThemOnReleaseOrCancel() {
        for (end in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val f = Fixture()
            try {
                assertTrue(f.centralOverlayPixels() > 100)
                f.event(MotionEvent.ACTION_DOWN, time = 1000)
                assertTrue("A simple touch should not hide cells", f.centralOverlayPixels() > 100)
                f.event(MotionEvent.ACTION_MOVE, x = 230f, y = 215f, time = 1050)
                assertEquals("Pan still redraws cells", 0, f.centralOverlayPixels())
                f.event(end, x = 230f, y = 215f, time = 1100)
                assertTrue("Overlays must return after the gesture", f.centralOverlayPixels() > 100)
                f.assertUnchanged()
            } finally { f.source.recycle() }
        }
    }

    @Test fun pinchKeepsOverlaysHiddenUntilTheRemainingFingerIsReleased() {
        val f = Fixture()
        try {
            f.event(MotionEvent.ACTION_DOWN, y = 175f, time = 1000)
            f.pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50f, 1010)
            f.pinch(MotionEvent.ACTION_MOVE, 200f, 1200)
            f.pinch(MotionEvent.ACTION_MOVE, 250f, 1300)
            assertEquals("Pinch still redraws cells", 0, f.centralOverlayPixels())
            f.pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 250f, 1400)
            assertEquals("One finger is still down", 0, f.centralOverlayPixels())
            f.event(MotionEvent.ACTION_MOVE, x = 210f, y = 80f, time = 1450)
            assertEquals(0, f.centralOverlayPixels())
            f.event(MotionEvent.ACTION_UP, x = 210f, y = 80f, time = 1500)
            assertTrue("Pinch ended with overlays still hidden", f.centralOverlayPixels() > 100)
            f.assertUnchanged()
        } finally { f.source.recycle() }
    }

    @Test fun brushFeedbackIsVisibleAndMultiTouchStillCancelsEditing() {
        val f = Fixture()
        try {
            f.view.editorTool = EditorTool.BRUSH_ONE
            f.event(MotionEvent.ACTION_DOWN, time = 1000)
            f.event(MotionEvent.ACTION_MOVE, x = 230f, time = 1100)
            assertTrue("Brush feedback was hidden", f.centralOverlayPixels() > 100)
            f.pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50f, 1200)
            f.pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50f, 1300)
            f.event(MotionEvent.ACTION_UP, time = 1400)
            f.assertUnchanged()
        } finally { f.source.recycle() }
    }
}
