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
    @Test fun sparseBrushEventsFillTheStrokeIncludingTheRelease() = exercise { view, commits ->
        for (reverse in listOf(false, true)) {
            send(view, MotionEvent.ACTION_DOWN, if (reverse) 12 else 2)
            send(view, MotionEvent.ACTION_MOVE, 7)
            assertTrue(commits.isEmpty())
            send(view, MotionEvent.ACTION_UP, if (reverse) 2 else 12)
            assertEquals(listOf((2..12).map { Coord(it, 10) }.toSet()), commits)
            commits.clear()
        }
    }

    @Test fun sparseBatchedSamplesFollowEachSegmentOfTheStroke() = exercise { view, commits ->
        send(view, MotionEvent.ACTION_DOWN, 2, 2)
        val move = event(MotionEvent.ACTION_MOVE, 2, 12)
        move.addBatch(1200L, 140f, 140f, 1f, 1f, 0)
        try { view.onTouchEvent(move) } finally { move.recycle() }
        send(view, MotionEvent.ACTION_UP, 12, 12)
        val expected = (2..12).map { Coord(2, it) } + (2..12).map { Coord(it, 12) }
        assertEquals(listOf(expected.toSet()), commits)
    }

    @Test fun sparseDiagonalBrushEventsRemainContinuousInBothDirections() = exercise { view, commits ->
        for (reverse in listOf(false, true)) {
            val start = if (reverse) 12 else 2
            val end = if (reverse) 2 else 12
            send(view, MotionEvent.ACTION_DOWN, start, start)
            send(view, MotionEvent.ACTION_UP, end, end)
            assertEquals(listOf((2..12).map { Coord(it, it) }.toSet()), commits)
            commits.clear()
        }
    }

    @Test fun leavingTheGridFinishesAtTheBoundaryAndReentryStartsIndependently() = exercise { view, commits ->
        send(view, MotionEvent.ACTION_DOWN, 2)
        send(view, MotionEvent.ACTION_MOVE, -2)
        send(view, MotionEvent.ACTION_MOVE, 12)
        send(view, MotionEvent.ACTION_UP, 12)
        assertEquals(listOf(setOf(Coord(0, 10), Coord(1, 10), Coord(2, 10), Coord(12, 10))), commits)
        commits.clear()
        send(view, MotionEvent.ACTION_DOWN, 5)
        send(view, MotionEvent.ACTION_UP, 5)
        assertEquals(listOf(setOf(Coord(5, 10))), commits)
    }

    @Test fun sparseBrushReleaseOutsideFillsToEveryEdgeAndCorner() = exercise { view, commits ->
        for ((start, end, expected) in listOf(
            Triple(Coord(2, 10), Coord(-2, 10), (0..2).map { Coord(it, 10) }),
            Triple(Coord(18, 10), Coord(24, 10), (18..20).map { Coord(it, 10) }),
            Triple(Coord(10, 2), Coord(10, -2), (0..2).map { Coord(10, it) }),
            Triple(Coord(10, 18), Coord(10, 24), (18..20).map { Coord(10, it) }),
            Triple(Coord(2, 2), Coord(-2, -2), (0..2).map { Coord(it, it) }),
        )) {
            send(view, MotionEvent.ACTION_DOWN, start.x, start.y)
            send(view, MotionEvent.ACTION_UP, end.x, end.y)
            assertEquals(listOf(expected.toSet()), commits)
            commits.clear()
        }
    }

    @Test fun batchedExitFinishesAtTheBoundaryWithoutConnectingToReentry() = exercise { view, commits ->
        send(view, MotionEvent.ACTION_DOWN, 2)
        val move = event(MotionEvent.ACTION_MOVE, -2)
        move.addBatch(1200L, 140f, 120f, 1f, 1f, 0)
        try { view.onTouchEvent(move) } finally { move.recycle() }
        send(view, MotionEvent.ACTION_UP, 12)
        assertEquals(listOf(setOf(Coord(0, 10), Coord(1, 10), Coord(2, 10), Coord(12, 10))), commits)
    }

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

    private fun event(action: Int, x: Int, y: Int = 10) = MotionEvent.obtain(1000, 1000L + x * 10,
        action, 20f + x * 10, 20f + y * 10, 0)
    private fun send(view: View, action: Int, x: Int, y: Int = 10) {
        val event = event(action, x, y)
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
