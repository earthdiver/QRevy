package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorStrokeHistoryTest {
    @Test fun batchedBrushSamplesAndReleaseMatchSeparateEventsWithOneCommit() {
        exercise { view, commits ->
            send(view, MotionEvent.ACTION_DOWN, 4)
            val move = event(MotionEvent.ACTION_MOVE, 5)
            for (x in 6..9) move.addBatch(1000L + x * 10, 20f + x * 10, 120f, 1f, 1f, 0)
            view.onTouchEvent(move)
            move.recycle()
            assertTrue(commits.isEmpty())
            send(view, MotionEvent.ACTION_UP, 10)
            assertEquals(listOf((4..10).map { Coord(it, 10) }.toSet()), commits)
            commits.clear()
            send(view, MotionEvent.ACTION_DOWN, 4)
            for (x in 5..9) send(view, MotionEvent.ACTION_MOVE, x)
            send(view, MotionEvent.ACTION_UP, 10)
            assertEquals(listOf((4..10).map { Coord(it, 10) }.toSet()), commits)
        }
    }

    @Test fun cancellationAndMultiplePointersDiscardTheWholeStroke() {
        exercise { view, commits ->
            send(view, MotionEvent.ACTION_DOWN, 4)
            send(view, MotionEvent.ACTION_MOVE, 5)
            send(view, MotionEvent.ACTION_CANCEL, 5)
            assertTrue(commits.isEmpty())
            send(view, MotionEvent.ACTION_DOWN, 4)
            send(view, MotionEvent.ACTION_POINTER_DOWN, 5)
            send(view, MotionEvent.ACTION_UP, 6)
            assertTrue(commits.isEmpty())
            send(view, MotionEvent.ACTION_DOWN, 7)
            send(view, MotionEvent.ACTION_UP, 8)
            assertEquals(listOf(setOf(Coord(7, 10), Coord(8, 10))), commits)
        }
    }

    private fun event(action: Int, x: Int) = MotionEvent.obtain(1000, 1000L + x * 10,
        action, 20f + x * 10, 120f, 0)
    private fun send(view: View, action: Int, x: Int) {
        val event = event(action, x)
        view.onTouchEvent(event)
        event.recycle()
    }
    private fun exercise(test: (View, MutableList<Set<Coord>>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (compact in listOf(false, true)) for ((tool, value) in listOf(
                EditorTool.BRUSH_ZERO to ModuleValue.ZERO, EditorTool.BRUSH_ONE to ModuleValue.ONE,
                EditorTool.BRUSH_ERASURE to ModuleValue.ERASED,
            )) {
                val bitmap = Bitmap.createBitmap(250, 250, Bitmap.Config.ARGB_8888)
                val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
                val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE)
                val commits = mutableListOf<Set<Coord>>()
                val commit: (Set<Coord>, ModuleValue) -> Unit = { cells, actual ->
                    assertEquals(value, actual); commits += cells
                }
                val view = if (compact) PreprocessOverlayView(controller.get()).apply {
                    setBitmap(bitmap)
                    setImageQuad(quad)
                    setCellEditing(geometry, QrMatrix(21))
                    cellEditorTool = tool
                    onCellBrushApplied = commit
                } else QrEditorView(controller.get()).apply {
                    setDocument(bitmap, geometry, QrMatrix(21))
                    editorTool = tool
                    onBrushApplied = commit
                }
                view.layout(0, 0, 250, 250)
                test(view, commits)
                bitmap.recycle()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
