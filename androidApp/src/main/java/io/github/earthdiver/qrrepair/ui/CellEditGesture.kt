package io.github.earthdiver.qrrepair.ui

import android.view.MotionEvent
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleValue

enum class EditorTool {
    NAVIGATE,
    BRUSH_ZERO,
    BRUSH_ONE,
    BRUSH_ERASURE,
    BRUSH_ERASURE_WIDE,
    RECT_SELECT,
    STAMP_ERASURE_SMALL,
    STAMP_ERASURE_MEDIUM,
    STAMP_ERASURE_LARGE,
}

internal fun moduleFootprint(center: Coord, size: Int, dimension: Int): Set<Coord> {
    require(size > 0 && size % 2 == 1)
    val radius = size / 2
    return buildSet {
        for (y in center.y - radius..center.y + radius) {
            for (x in center.x - radius..center.x + radius) {
                if (x in 0 until dimension && y in 0 until dimension) add(Coord(x, y))
            }
        }
    }
}

/**
 * Accumulates one cell-edit gesture without changing the document or its committed selection.
 * The host supplies coordinate mapping and applies commits, so magnification and image clipping
 * remain specific to each view. The options preserve their existing grid-boundary interactions.
 */
internal class CellEditGesture(
    private val requireCellOnDown: Boolean = false,
    private val keepStampPreviewOutsideGrid: Boolean = false,
    private val includeReleaseInRange: Boolean = true,
) {
    data class Commit(val cells: Set<Coord>, val value: ModuleValue?, val asRectangle: Boolean)
    data class Update(
        val disallowIntercept: Boolean? = null,
        val commit: Commit? = null,
        val redraw: Boolean = true,
    )

    private val stroke = linkedSetOf<Coord>()
    val strokeCells: Set<Coord> get() = stroke
    private var rangeStart: Coord? = null
    private var rangeEnd: Coord? = null
    private var cancelledByMultiTouch = false
    val rangeCells: Set<Coord>
        get() {
            val start = rangeStart ?: return emptySet()
            val end = rangeEnd ?: return emptySet()
            return buildSet {
                for (y in minOf(start.y, end.y)..maxOf(start.y, end.y)) {
                    for (x in minOf(start.x, end.x)..maxOf(start.x, end.x)) add(Coord(x, y))
                }
            }
        }

    fun clearPreview() {
        stroke.clear()
        rangeStart = null
        rangeEnd = null
    }

    fun reset() {
        clearPreview()
        cancelledByMultiTouch = false
    }

    fun transposeRange() {
        rangeStart = rangeStart?.let { Coord(it.y, it.x) }
        rangeEnd = rangeEnd?.let { Coord(it.y, it.x) }
        stroke.clear()
    }

    /** Returns null for navigation or events belonging to another interaction. */
    fun handle(
        event: MotionEvent,
        tool: EditorTool,
        dimension: Int,
        cellAt: (Float, Float) -> Coord?,
        enabled: Boolean = true,
    ): Update? {
        if (tool == EditorTool.NAVIGATE) return null
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1) {
            cancelledByMultiTouch = true
            clearPreview()
            return Update(disallowIntercept = false)
        }
        if (cancelledByMultiTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) cancelledByMultiTouch = false
            return Update(redraw = false)
        }
        if (!enabled) return null
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
            else -> return null
        }
        val cell = cellAt(event.x, event.y)
        if (action == MotionEvent.ACTION_DOWN && requireCellOnDown && cell == null) return Update(redraw = false)
        val brushSize = when (tool) {
            EditorTool.BRUSH_ZERO, EditorTool.BRUSH_ONE, EditorTool.BRUSH_ERASURE -> 1
            EditorTool.BRUSH_ERASURE_WIDE -> 3
            else -> null
        }
        val stampSize = when (tool) {
            EditorTool.STAMP_ERASURE_SMALL -> 3
            EditorTool.STAMP_ERASURE_MEDIUM -> 5
            EditorTool.STAMP_ERASURE_LARGE -> 7
            else -> null
        }
        val value = when (tool) {
            EditorTool.BRUSH_ZERO -> ModuleValue.ZERO
            EditorTool.BRUSH_ONE -> ModuleValue.ONE
            EditorTool.RECT_SELECT -> null
            else -> ModuleValue.ERASED
        }
        if (brushSize != null) {
            if (action == MotionEvent.ACTION_DOWN) stroke.clear()
            if (action != MotionEvent.ACTION_CANCEL) {
                for (index in 0 until event.historySize) {
                    cellAt(event.getHistoricalX(index), event.getHistoricalY(index))?.let {
                        stroke += moduleFootprint(it, brushSize, dimension)
                    }
                }
                cell?.let { stroke += moduleFootprint(it, brushSize, dimension) }
            }
        } else if (stampSize != null) {
            if (action != MotionEvent.ACTION_MOVE || cell != null || !keepStampPreviewOutsideGrid) {
                stroke.clear()
                if (action != MotionEvent.ACTION_CANCEL) cell?.let { stroke += moduleFootprint(it, stampSize, dimension) }
            }
        } else when (action) {
            MotionEvent.ACTION_DOWN -> { rangeStart = cell; rangeEnd = cell }
            MotionEvent.ACTION_MOVE -> if (cell != null) rangeEnd = cell
            MotionEvent.ACTION_UP -> if (includeReleaseInRange && cell != null) rangeEnd = cell
        }
        val commit = if (action == MotionEvent.ACTION_UP) {
            Commit(if (tool == EditorTool.RECT_SELECT) rangeCells else stroke.toSet(), value,
                asRectangle = stampSize != null || tool == EditorTool.RECT_SELECT)
        } else null
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (tool == EditorTool.RECT_SELECT) { rangeStart = null; rangeEnd = null }
            else stroke.clear()
        }
        return Update(
            disallowIntercept = when {
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL -> false
                action == MotionEvent.ACTION_DOWN -> true
                stampSize != null && !keepStampPreviewOutsideGrid -> true
                else -> null
            },
            commit = commit?.takeIf { it.value == null || it.cells.isNotEmpty() },
        )
    }
}
