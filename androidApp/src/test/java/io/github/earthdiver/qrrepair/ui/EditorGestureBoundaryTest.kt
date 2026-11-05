package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.QrMatrix
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
class EditorGestureBoundaryTest {
    @Test fun rectangleReleaseUsesTheEndpointDefinedByEachEditor() = exercise(EditorTool.RECT_SELECT) { view, commits ->
        send(view, MotionEvent.ACTION_DOWN, 4)
        send(view, MotionEvent.ACTION_MOVE, 6)
        send(view, MotionEvent.ACTION_UP, 8)
        val last = if (view is PreprocessOverlayView) 6 else 8
        assertEquals(listOf((4..last).map { Coord(it, 10) }.toSet()), commits)
    }

    @Test fun rectangleStartingOutsideTheGridCannotCommitCells() = exercise(EditorTool.RECT_SELECT) { view, commits ->
        val previous = Coord(3, 10)
        if (view is QrEditorView) view.setSelection(setOf(previous), asRectangle = true)
        else (view as PreprocessOverlayView).setCellSelection(setOf(previous), asRectangle = true)
        send(view, MotionEvent.ACTION_DOWN, -2)
        send(view, MotionEvent.ACTION_MOVE, 6)
        send(view, MotionEvent.ACTION_UP, 8)
        assertTrue(commits.isEmpty())
        if (view is QrEditorView) assertNull(view.selectedCell())
        else assertEquals(previous, (view as PreprocessOverlayView).selectedEditCell())
    }

    @Test fun movingAStampOutsideTheGridPreservesEachEditorsPreviewUntilRelease() =
        exercise(EditorTool.STAMP_ERASURE_SMALL) { view, commits ->
            val initial = render(view)
            send(view, MotionEvent.ACTION_DOWN, 10)
            val inside = render(view)
            send(view, MotionEvent.ACTION_MOVE, -2)
            val outside = render(view)
            send(view, MotionEvent.ACTION_UP, -2)
            val released = render(view)
            try {
                assertFalse(initial.sameAs(inside))
                assertTrue(outside.sameAs(if (view is PreprocessOverlayView) inside else initial))
                assertTrue(initial.sameAs(released))
                assertTrue(commits.isEmpty())
            } finally {
                initial.recycle(); inside.recycle(); outside.recycle(); released.recycle()
            }
        }

    @Test fun wideBrushAccumulatesClippedFootprintsAndStampsCommitOnlyTheReleaseFootprint() {
        exercise(EditorTool.BRUSH_ERASURE_WIDE) { view, commits ->
            send(view, MotionEvent.ACTION_DOWN, 0)
            send(view, MotionEvent.ACTION_MOVE, 1)
            send(view, MotionEvent.ACTION_UP, 2)
            assertEquals(listOf((9..11).flatMap { y -> (0..3).map { x -> Coord(x, y) } }.toSet()), commits)
        }
        for ((tool, radius) in listOf(EditorTool.STAMP_ERASURE_MEDIUM to 2, EditorTool.STAMP_ERASURE_LARGE to 3)) {
            exercise(tool) { view, commits ->
                send(view, MotionEvent.ACTION_DOWN, 4)
                send(view, MotionEvent.ACTION_MOVE, 6)
                send(view, MotionEvent.ACTION_UP, 0)
                assertEquals(listOf((10 - radius..10 + radius).flatMap { y ->
                    (0..radius).map { x -> Coord(x, y) }
                }.toSet()), commits)
            }
        }
    }

    private fun render(view: View) = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun send(view: View, action: Int, x: Int) {
        val event = MotionEvent.obtain(1000, 1200, action, 20f + x * 10, 120f, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun exercise(tool: EditorTool, test: (View, MutableList<Set<Coord>>) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (compact in listOf(false, true)) {
                val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
                val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
                val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE)
                val commits = mutableListOf<Set<Coord>>()
                val view = if (compact) PreprocessOverlayView(activity.get()).apply {
                    setBitmap(bitmap); setImageQuad(quad); setCellEditing(geometry, QrMatrix(21))
                    cellEditorTool = tool
                    onCellBrushApplied = { cells, _ -> commits += cells }
                    onCellRangeSelected = { commits += it }
                } else QrEditorView(activity.get()).apply {
                    setDocument(bitmap, geometry, QrMatrix(21))
                    editorTool = tool
                    onBrushApplied = { cells, _ -> commits += cells }
                    onRangeSelected = { commits += it }
                }
                view.layout(0, 0, 250, 250)
                try { test(view, commits) } finally { bitmap.recycle() }
            }
        } finally { activity.pause().stop().destroy() }
    }
}
