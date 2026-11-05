package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class DistortionEditorTest {
    private fun geometry() = GridGeometry(4, FinderRole.TOP_LEFT, 44.0, 44.0, 8.0, 0.0,
            ImageQuad(PointD(20.0, 20.0), PointD(276.0, 20.0), PointD(276.0, 276.0), PointD(20.0, 276.0)),
            GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(listOf(
                WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(.5, .5), PointD(7.0, -5.0)),
            )))

    @Test fun cellSelectionUsesTheCurvedMappingAndRejectsUninvertiblePoints() {
        val geometry = geometry()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val editor = QrEditorView(RuntimeEnvironment.getApplication()).apply {
            layout(0, 0, 600, 600)
            setDocument(bitmap, geometry, QrMatrix(33))
        }
        fun field(name: String) = QrEditorView::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(editor)
        val hit = QrEditorView::class.java.getDeclaredMethod("positionToCell", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType).apply { isAccessible = true }
        for ((x, y) in listOf(16 to 16, 0 to 0, 32 to 32, 12 to 20)) {
            val p = geometry.moduleToImage(x.toDouble(), y.toDouble())
            assertEquals(Coord(x, y), hit.invoke(editor, p.x.toFloat() * field("viewScale") + field("offsetX"),
                p.y.toFloat() * field("viewScale") + field("offsetY")))
        }
        assertNull(hit.invoke(editor, Float.MAX_VALUE, Float.MAX_VALUE))
        bitmap.recycle()
    }
    @Test fun settingsOverlayUsesCurvedLinesAndHitTestingWhileRetainingEditableBaseCorners() {
        val g = geometry()
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                highlightManualOnly = true
                setBitmap(bitmap); setVersion(4); setImageQuad(g.imageQuad!!)
                layout(0, 0, 600, 600)
                setCellEditing(g, QrMatrix(33), null)
            }
            assertEquals(g, view.previewGeometry())
            assertEquals(g.imageQuad, view.imageQuad())
            val rendered = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(rendered))
                val crossing = g.moduleToImage(16.5, 16.5)
                val x = kotlin.math.round(crossing.x * 2).toInt()
                val y = kotlin.math.round(crossing.y * 2).toInt()
                assertTrue("The curved grid crossing must be drawn at the sampling map's position",
                    (-1..1).any { dy -> (-1..1).any { dx ->
                        val color = rendered.getPixel(x + dx, y + dy)
                        android.graphics.Color.red(color) < 230 && android.graphics.Color.green(color) > 240 &&
                            android.graphics.Color.blue(color) > 240
                    } })
            } finally { rendered.recycle() }
            var selected: Coord? = null
            view.onEditCellSelected = { x, y -> selected = Coord(x, y) }
            val p = g.moduleToImage(16.0, 16.0)
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                android.view.MotionEvent.obtain(0, 10, action, (p.x * 2).toFloat(), (p.y * 2).toFloat(), 0).also {
                    view.onTouchEvent(it); it.recycle()
                }
            }
            assertEquals(Coord(16, 16), selected)
            val outline = MappedGridOutline()
            val endpoints = listOf(PointD(0.0, 16.0), PointD(32.0, 16.0))
            val points = outline.points(g, endpoints, false)
            assertEquals(33, points.size)
            assertEquals(g.moduleToImage(16.0, 16.0), points[16])
            assertTrue(kotlin.math.abs(points[16].y - (points.first().y + points.last().y) / 2) > 1.0)
            assertEquals(2, outline.points(g.asProjective(), endpoints, false).size)
            view.setImageQuad(g.imageQuad!!.copy(topLeft = PointD(22.0, 20.0)))
            assertNull(view.previewGeometry()!!.warp)
        } finally { bitmap.recycle() }
    }

    private fun stronglyCurvedGeometry(): GridGeometry {
        val quad = ImageQuad(PointD(50.0, 50.0), PointD(210.0, 50.0),
            PointD(210.0, 210.0), PointD(50.0, 210.0))
        val landmarks = (0..4).flatMap { iy -> (0..4).map { ix ->
            WarpLandmark(PointD(ix / 4.0, iy / 4.0),
                PointD(8.0 * (2.4 + 1.6 * sin(PI * ix + 4.2)), 0.0))
        } }
        return GridGeometry(1, FinderRole.TOP_LEFT, 74.0, 74.0, 8.0, 0.0,
            quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(landmarks)).reanchored()
    }

    private fun tapImagePoint(view: View, point: PointD) {
        val downTime = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(downTime, downTime + index * 10L, action,
                (point.x * 2.0).toFloat(), (point.y * 2.0).toFloat(), 0)
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
    }

    @Test fun settingsOverlaySelectsAndPaintsStronglyCurvedCellsAtFloatTouchPositions() {
        val geometry = stronglyCurvedGeometry()
        assertTrue(geometry.hasUsableWarp())
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            val selected = mutableListOf<Coord>()
            val painted = mutableListOf<Pair<Set<Coord>, ModuleValue>>()
            val overlay = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(bitmap)
                setVersion(1)
                setImageQuad(geometry.imageQuad!!)
                layout(0, 0, 600, 600)
                setCellEditing(geometry, QrMatrix(21), null)
                onEditCellSelected = { x, y -> selected += Coord(x, y) }
                onCellBrushApplied = { cells, value -> painted += cells to value }
            }
            tapImagePoint(overlay, PointD(10.0, 10.0))
            assertTrue(selected.isEmpty())
            val target = Coord(14, 2)
            val point = geometry.moduleToImage(target.x.toDouble(), target.y.toDouble())
            tapImagePoint(overlay, point)
            assertEquals(listOf(target), selected)
            overlay.cellEditorTool = EditorTool.BRUSH_ONE
            tapImagePoint(overlay, point)
            assertEquals(listOf(setOf(target) to ModuleValue.ONE), painted)
        } finally { bitmap.recycle() }
    }

    @Test fun expandedEditorSelectsAndPaintsStronglyCurvedCellsAtFloatTouchPositions() {
        val geometry = stronglyCurvedGeometry()
        assertTrue(geometry.hasUsableWarp())
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            val selected = mutableListOf<Coord>()
            val painted = mutableListOf<Pair<Set<Coord>, ModuleValue>>()
            val editor = QrEditorView(RuntimeEnvironment.getApplication()).apply {
                layout(0, 0, 600, 600)
                setDocument(bitmap, geometry, QrMatrix(21))
                onCellSelected = { x, y -> selected += Coord(x, y) }
                onBrushApplied = { cells, value -> painted += cells to value }
            }
            tapImagePoint(editor, PointD(10.0, 10.0))
            assertTrue(selected.isEmpty())
            val target = Coord(14, 2)
            val point = geometry.moduleToImage(target.x.toDouble(), target.y.toDouble())
            tapImagePoint(editor, point)
            assertEquals(listOf(target), selected)
            editor.editorTool = EditorTool.BRUSH_ONE
            tapImagePoint(editor, point)
            assertEquals(listOf(setOf(target) to ModuleValue.ONE), painted)
        } finally { bitmap.recycle() }
    }

    @Test fun cancelledCornerDragRefreshesOnlyWhenTheGridActuallyMoved() {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(bitmap); setVersion(4); setImageQuad(geometry().imageQuad!!)
                layout(0, 0, 600, 600)
            }
            var finished = 0
            view.onGeometryEditFinished = { finished++ }
            fun touch(action: Int, x: Float, y: Float) {
                android.view.MotionEvent.obtain(0, 10, action, x, y, 0).also {
                    view.onTouchEvent(it); it.recycle()
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 40f, 40f)
            touch(android.view.MotionEvent.ACTION_CANCEL, 40f, 40f)
            assertEquals(0, finished)
            touch(android.view.MotionEvent.ACTION_DOWN, 40f, 40f)
            touch(android.view.MotionEvent.ACTION_MOVE, 64f, 64f)
            touch(android.view.MotionEvent.ACTION_CANCEL, 64f, 64f)
            assertEquals(1, finished)
            assertNotEquals(geometry().imageQuad, view.imageQuad())
        } finally { bitmap.recycle() }
    }

}
