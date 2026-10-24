package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSelectionFrameTest {
    @Test fun stampsUseTheSameFrameAsRectangleSelectionsIncludingClippedEdges() {
        exercise { view, commits ->
            for (x in listOf(10, 0)) {
                send(view, MotionEvent.ACTION_DOWN, x)
                send(view, MotionEvent.ACTION_UP, x)
                val targets = (9..11).flatMap { y -> ((x - 1).coerceAtLeast(0)..x + 1).map { Coord(it, y) } }.toSet()
                assertEquals(targets, commits.last())
                val actual = render(view)
                select(view, targets)
                val expected = render(view)
                try { assertTrue("Stamp selection must render the rectangle frame", actual.sameAs(expected)) }
                finally { actual.recycle(); expected.recycle() }
            }
        }
    }

    @Test fun cancelledGesturesPreserveThePreviousSelectionAndItsFrame() {
        exercise { view, commits ->
            val old = setOf(Coord(3, 3), Coord(4, 3), Coord(3, 4), Coord(4, 4))
            for (tool in listOf(EditorTool.STAMP_ERASURE_SMALL, EditorTool.RECT_SELECT, EditorTool.BRUSH_ONE)) {
                tool(view, tool)
                select(view, old)
                val before = render(view)
                try {
                    send(view, MotionEvent.ACTION_DOWN, 10)
                    send(view, MotionEvent.ACTION_MOVE, 12)
                    send(view, MotionEvent.ACTION_CANCEL, 12)
                    val afterCancel = render(view)
                    try { assertTrue("Cancel must preserve selection for $tool", before.sameAs(afterCancel)) }
                    finally { afterCancel.recycle() }
                    send(view, MotionEvent.ACTION_DOWN, 10)
                    send(view, MotionEvent.ACTION_POINTER_DOWN, 11)
                    send(view, MotionEvent.ACTION_UP, 12)
                    val afterMultiTouch = render(view)
                    try { assertTrue("Multi-touch must preserve selection for $tool", before.sameAs(afterMultiTouch)) }
                    finally { afterMultiTouch.recycle() }
                    assertTrue(commits.isEmpty())
                } finally { before.recycle() }
            }
        }
    }

    private fun select(view: View, cells: Set<Coord>) {
        if (view is QrEditorView) view.setSelection(cells, asRectangle = true)
        else (view as PreprocessOverlayView).setCellSelection(cells, asRectangle = true)
    }
    private fun tool(view: View, tool: EditorTool) {
        if (view is QrEditorView) view.editorTool = tool else (view as PreprocessOverlayView).cellEditorTool = tool
    }
    private fun render(view: View) = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun send(view: View, action: Int, x: Int) {
        val event = MotionEvent.obtain(1000, 1200, action, 20f + x * 10, 120f, 0)
        view.onTouchEvent(event)
        event.recycle()
    }
    private fun exercise(test: (View, MutableList<Set<Coord>>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (compact in listOf(false, true)) {
                val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
                val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
                val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE)
                val commits = mutableListOf<Set<Coord>>()
                val view = if (compact) PreprocessOverlayView(activity.get()).apply {
                    setBitmap(bitmap); setImageQuad(quad); setCellEditing(geometry, QrMatrix(21))
                    onCellBrushApplied = { cells, _ -> commits += cells }
                    onCellRangeSelected = { commits += it }
                } else QrEditorView(activity.get()).apply {
                    setDocument(bitmap, geometry, QrMatrix(21))
                    onBrushApplied = { cells, _ -> commits += cells }
                    onRangeSelected = { commits += it }
                }
                view.layout(0, 0, 250, 250)
                tool(view, EditorTool.STAMP_ERASURE_SMALL)
                try { test(view, commits) } finally { bitmap.recycle() }
            }
        } finally { activity.pause().stop().destroy() }
    }
}
