package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.github.earthdiver.qrrepair.core.CellConfidence
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.draggedHandlePoint
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min


class QrEditorView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var geometry: GridGeometry? = null
    private var matrix: QrMatrix? = null
    private var selected: Pair<Int, Int>? = null
    private var selectedCells: Set<Coord> = emptySet()
    private var selectionIsRectangle = false
    private var confidence: Map<Coord, CellConfidence> = emptyMap()
    private var heatmapEnabled = false
    private var codewordOverlay: CodewordOverlayModel? = null
    private var codewordOverlayVersion: Int? = null

    var showCodewordOverlay: Boolean = false
        set(value) { field = value; invalidate() }

    var onCellSelected: ((Int, Int) -> Unit)? = null
    var onGeometryMoved: ((geometry: GridGeometry, finished: Boolean) -> Unit)? = null
    var onBrushApplied: ((Set<Coord>, ModuleValue) -> Unit)? = null
    var onRangeSelected: ((Set<Coord>) -> Unit)? = null

    var editorTool: EditorTool = EditorTool.NAVIGATE
        set(value) {
            field = value
            cellGesture.reset()
            navigatingViewport = false
            invalidate()
        }

    var cornerEditEnabled: Boolean = false
        set(value) {
            field = value
            draggingCorner = null
            dragStartTouch = null
            dragStartCorner = null
            dragStartGeometry = null
            invalidate()
        }

    private var draggingCorner: Int? = null
    private var dragStartTouch: PointD? = null
    private var dragStartCorner: PointD? = null
    private var dragStartGeometry: GridGeometry? = null
    private var viewScale = 1f
    private var fitScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val cellGesture = CellEditGesture()
    private val boundaryPath = Path()
    private var pendingClickCell: Coord? = null
    private var navigatingViewport = false

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val manualCellMarker = ManualCellMarker()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 120, 255); strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.MAGENTA
    }
    var showQrStructureOverlay: Boolean = false
        set(value) { field = value; invalidate() }
    private val rangeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(52, 0, 229, 255)
    }
    private val rangeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 229, 255)
    }
    private val valueTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val valueTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val codewordFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val codewordBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val codewordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val codewordTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (draggingCorner != null) return false
            navigatingViewport = true
            invalidate()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (draggingCorner != null) return false
            val old = viewScale
            val next = (viewScale * detector.scaleFactor).coerceIn(fitScale * 0.75f, fitScale * 30f)
            if (old <= 0f || next == old) return true
            val imageFocusX = (detector.focusX - offsetX) / old
            val imageFocusY = (detector.focusY - offsetY) / old
            viewScale = next
            offsetX = detector.focusX - imageFocusX * next
            offsetY = detector.focusY - imageFocusY * next
            invalidate(); return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress || draggingCorner != null || editorTool != EditorTool.NAVIGATE) return false
            navigatingViewport = true
            offsetX -= distanceX; offsetY -= distanceY; invalidate(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (cornerEditEnabled || editorTool != EditorTool.NAVIGATE) return false
            val cell = eventToCell(e) ?: return false
            pendingClickCell = cell
            performClick()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { resetViewTransform(); return true }
    })

    fun setImageOnly(bitmap: Bitmap) {
        val changed = this.bitmap !== bitmap
        this.bitmap = bitmap; geometry = null; matrix = null; selected = null; selectedCells = emptySet(); selectionIsRectangle = false; draggingCorner = null
        dragStartTouch = null; dragStartCorner = null; dragStartGeometry = null
        confidence = emptyMap(); heatmapEnabled = false
        if (changed) resetViewTransform(); invalidate()
    }

    fun setDocument(bitmap: Bitmap, geometry: GridGeometry, matrix: QrMatrix, keepViewport: Boolean = false) {
        val bitmapChanged = this.bitmap !== bitmap
        this.bitmap = bitmap; this.geometry = geometry; this.matrix = matrix
        rebuildCodewordOverlay()
        if (bitmapChanged || !keepViewport) resetViewTransform(); invalidate()
    }

    fun updateGeometryAndMatrix(geometry: GridGeometry, matrix: QrMatrix) { this.geometry = geometry; this.matrix = matrix; invalidate() }
    fun previewGeometry(geometry: GridGeometry) { this.geometry = geometry; invalidate() }
    fun setSelected(x: Int, y: Int) { selected = x to y; selectedCells = setOf(Coord(x, y)); selectionIsRectangle = false; invalidate() }
    fun setSelection(coords: Collection<Coord>, asRectangle: Boolean = false, focus: Coord? = coords.firstOrNull()) {
        selectedCells = coords.toSet()
        selected = (focus?.takeIf { it in selectedCells } ?: coords.firstOrNull())?.let { it.x to it.y }
        selectionIsRectangle = asRectangle && selectedCells.isNotEmpty()
        invalidate()
    }
    fun clearSelection() { selectedCells = emptySet(); selected = null; selectionIsRectangle = false; invalidate() }
    fun selectedCell(): Coord? = selected?.let { Coord(it.first, it.second) }
    fun setHeatmap(enabled: Boolean, values: Map<Coord, CellConfidence>) { heatmapEnabled = enabled; confidence = values; invalidate() }
    fun setCodewordOverlay(model: CodewordOverlayModel?) { codewordOverlay = model; invalidate() }
    fun setCodewordOverlayVersion(version: Int) {
        codewordOverlayVersion = version
        rebuildCodewordOverlay()
        invalidate()
    }
    fun codewordCellAt(coord: Coord): CodewordOverlayCell? = codewordOverlay?.cells?.get(coord)
    fun refresh() = invalidate()

    private fun rebuildCodewordOverlay() {
        val version = codewordOverlayVersion ?: return
        val current = matrix ?: return
        codewordOverlay = runCatching { buildCodewordOverlay(current, version) }.getOrNull()
    }

    fun focusCell(x: Int, y: Int) {
        val g = geometry ?: return
        val p = g.moduleToImage(x.toDouble(), y.toDouble())
        val localPitch = g.localModulePitch(x.toDouble(), y.toDouble()).coerceAtLeast(0.1)
        val target = max(viewScale, (26.0 / localPitch).toFloat()).coerceAtMost(fitScale * 30f)
        viewScale = target
        offsetX = width / 2f - p.x.toFloat() * viewScale
        offsetY = height / 2f - p.y.toFloat() * viewScale
        selected = x to y
        selectedCells = setOf(Coord(x, y))
        selectionIsRectangle = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); if (oldw == 0 || oldh == 0) resetViewTransform() }

    private fun resetViewTransform() {
        val b = bitmap ?: return
        if (width <= 0 || height <= 0) return
        fitScale = min(width.toFloat() / b.width, height.toFloat() / b.height).coerceAtLeast(0.01f)
        viewScale = fitScale; offsetX = (width - b.width * viewScale) / 2f; offsetY = (height - b.height * viewScale) / 2f
        invalidate()
    }

    private fun viewToImage(x: Float, y: Float): PointD? {
        if (viewScale <= 0f) return null
        return PointD((x - offsetX) / viewScale.toDouble(), (y - offsetY) / viewScale.toDouble())
    }

    private fun eventToCell(event: MotionEvent): Coord? = positionToCell(event.x, event.y)

    private fun positionToCell(viewX: Float, viewY: Float): Coord? {
        val g = geometry ?: return null
        val p = viewToImage(viewX, viewY) ?: return null
        val module = g.imageToModule(p.x, p.y)
        if (!module.x.isFinite() || !module.y.isFinite()) return null
        val x = floor(module.x + 0.5).toInt()
        val y = floor(module.y + 0.5).toInt()
        return if (x in 0 until g.dimension && y in 0 until g.dimension) Coord(x, y) else null
    }

    private fun drawRange(canvas: Canvas, g: GridGeometry, cells: Set<Coord>, invScale: Float) {
        if (cells.isEmpty()) return
        val minX = cells.minOf { it.x }
        val maxX = cells.maxOf { it.x }
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y }
        val path = mappedOutline(g, listOf(PointD(minX - .5, minY - .5), PointD(maxX + .5, minY - .5),
            PointD(maxX + .5, maxY + .5), PointD(minX - .5, maxY + .5)))
        rangeBorderPaint.strokeWidth = 3f * invScale
        canvas.drawPath(path, rangeFillPaint)
        canvas.drawPath(path, rangeBorderPaint)
    }

    private fun drawModuleValue(canvas: Canvas, value: ModuleValue, prioritySearch: Boolean, p: PointD, pitch: Float, invScale: Float) {
        val textSize = (pitch * .68f).coerceAtLeast(8f * invScale)
        valueTextPaint.textSize = textSize
        valueTextOutlinePaint.textSize = textSize
        valueTextOutlinePaint.strokeWidth = max(1.2f * invScale, textSize * .16f)
        val baseline = p.y.toFloat() - (valueTextPaint.ascent() + valueTextPaint.descent()) / 2f
        val label = if (prioritySearch) "*" else when (value) {
            ModuleValue.ZERO -> "0"
            ModuleValue.ONE -> "1"
            ModuleValue.ERASED -> "?"
        }
        valueTextPaint.color = if (prioritySearch) Color.rgb(190, 110, 240) else if (value == ModuleValue.ERASED) Color.YELLOW else Color.WHITE
        canvas.drawText(label, p.x.toFloat(), baseline, valueTextOutlinePaint)
        canvas.drawText(label, p.x.toFloat(), baseline, valueTextPaint)
    }

    var highlightManualOnly: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(28, 28, 28))
        val b = bitmap ?: return; val g = geometry; val m = matrix
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(viewScale, viewScale); canvas.drawBitmap(b, 0f, 0f, bitmapPaint)
        if (g != null && m != null) {
            val invScale = 1f / max(viewScale, 0.001f); boundaryPaint.strokeWidth = 2f * invScale
            val last = g.dimension - .5
            boundaryPath.set(mappedOutline(g, listOf(PointD(-.5, -.5), PointD(last, -.5),
                PointD(last, last), PointD(-.5, last))))
            canvas.drawPath(boundaryPath, boundaryPaint)

            // Navigation changes only the viewport. Skip the cell-sized overlay loops until
            // all fingers are released, including the one-finger tail after a pinch.
            if (navigatingViewport) {
                canvas.restore()
                return
            }

            if (heatmapEnabled) drawHeatmap(canvas, g, invScale)

            val onScreenPitch = g.localModulePitch() * viewScale
            if (showCodewordOverlay && onScreenPitch >= CODEWORD_MIN_PITCH_PX) {
                drawCodewordOverlay(canvas, g, invScale)
            }
            val drawImageStates = onScreenPitch >= 5.0
            val drawValues = onScreenPitch >= 12.0
            val previewRange = cellGesture.rangeCells
            when {
                previewRange.isNotEmpty() -> drawRange(canvas, g, previewRange, invScale)
                selectionIsRectangle -> drawRange(canvas, g, selectedCells, invScale)
            }
            for (y in 0 until g.dimension) for (x in 0 until g.dimension) {
                val cell = m[x, y]
                val selected = selectedCells.any { it.x == x && it.y == y } || cellGesture.strokeCells.any { it.x == x && it.y == y }
                if (highlightManualOnly && !cell.source.isUserEdit && !selected) continue
                val important = cell.value == ModuleValue.ERASED || cell.source != ModuleSource.IMAGE || selected
                if (!important && !drawImageStates) continue
                val p = g.moduleToImage(x.toDouble(), y.toDouble())
                val markerRadius = max(1.4f * invScale, (g.localModulePitch(x.toDouble(), y.toDouble()) * 0.14).toFloat())
                when {
                    selected -> { overlayPaint.style = Paint.Style.STROKE; overlayPaint.strokeWidth = 1.5f * invScale; overlayPaint.color = Color.YELLOW; canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), markerRadius * 2.2f, overlayPaint) }
                    cell.prioritySearch -> { overlayPaint.style = Paint.Style.STROKE; overlayPaint.strokeWidth = 1.35f * invScale; overlayPaint.color = Color.rgb(190, 110, 240); canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), markerRadius * 2.0f, overlayPaint) }
                    cell.value == ModuleValue.ERASED -> { overlayPaint.style = Paint.Style.STROKE; overlayPaint.strokeWidth = 2f * invScale; overlayPaint.color = Color.rgb(255, 180, 0); canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), markerRadius * 1.5f, overlayPaint); canvas.drawLine(p.x.toFloat()-markerRadius,p.y.toFloat()-markerRadius,p.x.toFloat()+markerRadius,p.y.toFloat()+markerRadius,overlayPaint) }
                    cell.source == ModuleSource.RECOVERED || cell.source == ModuleSource.STRUCTURAL || cell.source == ModuleSource.CONSTRAINT -> { overlayPaint.style = Paint.Style.STROKE; overlayPaint.strokeWidth = 1.7f * invScale; overlayPaint.color = Color.rgb(80,220,120); canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), markerRadius * 1.25f, overlayPaint) }
                    drawImageStates -> { overlayPaint.style = if (cell.value == ModuleValue.ONE) Paint.Style.FILL else Paint.Style.STROKE; overlayPaint.strokeWidth = 1f * invScale; overlayPaint.color = if (cell.value == ModuleValue.ONE) Color.argb(95,0,0,0) else Color.argb(100,255,255,255); canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), markerRadius * .65f, overlayPaint) }
                }
                if (drawValues) drawModuleValue(
                    canvas,
                    cell.value,
                    cell.prioritySearch,
                    p,
                    g.localModulePitch(x.toDouble(), y.toDouble()).toFloat(),
                    invScale,
                )
            }

            if (showCodewordOverlay && onScreenPitch >= CODEWORD_NUMBER_MIN_PITCH_PX) {
                drawCodewordNumbers(canvas, g, invScale)
            }

            // Draw structure guides last so dense 0/1/? labels cannot hide them.
            if (showQrStructureOverlay && !highlightManualOnly) drawQrStructures(canvas, g, invScale)

            // Draw provenance separately and last: selection, * and structure overlays must
            // not hide the fact that a value was explicitly assigned by the user.
            for (y in 0 until g.dimension) for (x in 0 until g.dimension) {
                if (!m[x, y].source.isUserEdit) continue
                val point = g.moduleToImage(x.toDouble(), y.toDouble())
                val halfSize = max(4f * invScale, (g.localModulePitch(x.toDouble(), y.toDouble()) * .38).toFloat())
                manualCellMarker.draw(canvas, point.x.toFloat(), point.y.toFloat(), halfSize, 1.5f * invScale, m[x, y].source == ModuleSource.USER_SOFT || m[x, y].value == ModuleValue.ERASED)
            }

            if (cellGesture.strokeCells.isNotEmpty() || previewRange.isNotEmpty()) {
                overlayPaint.style = Paint.Style.FILL
                overlayPaint.color = Color.argb(75, 0, 210, 255)
                for (coord in cellGesture.strokeCells + previewRange) {
                    val p = g.moduleToImage(coord.x.toDouble(), coord.y.toDouble())
                    val r = (g.localModulePitch(coord.x.toDouble(), coord.y.toDouble()) * .36).toFloat()
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, overlayPaint)
                }
            }

            if (cornerEditEnabled) {
                val q = g.moduleCenterQuad().asList(); val r = 10f * invScale
                for ((i, p) in q.withIndex()) {
                    handlePaint.color = if (draggingCorner == i) Color.YELLOW else Color.MAGENTA
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, handlePaint)
                    overlayPaint.style = Paint.Style.STROKE; overlayPaint.strokeWidth = 2f * invScale; overlayPaint.color = Color.WHITE
                    canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r * 1.35f, overlayPaint)
                }
            }
        }
        canvas.restore()
    }

    private fun drawCodewordOverlay(canvas: Canvas, g: GridGeometry, invScale: Float) {
        val model = codewordOverlay ?: return
        val byCoord = model.cells
        for (entry in model.entries) {
            codewordFillPaint.color = when (entry.kind) {
                CodewordOverlayKind.DATA -> Color.argb(66, 0, 145, 255)
                CodewordOverlayKind.ECC -> Color.argb(72, 255, 118, 20)
                CodewordOverlayKind.UNCLASSIFIED -> Color.argb(62, 170, 130, 210)
            }
            codewordBorderPaint.color = when (entry.kind) {
                CodewordOverlayKind.DATA -> Color.rgb(30, 180, 255)
                CodewordOverlayKind.ECC -> Color.rgb(255, 155, 45)
                CodewordOverlayKind.UNCLASSIFIED -> Color.rgb(200, 175, 225)
            }
            codewordBorderPaint.strokeWidth = 1.8f * invScale
            for (coord in entry.coordinates) {
                if (!isModuleVisible(g, coord)) continue
                val corners = moduleCorners(g, coord)
                val fill = Path().apply {
                    moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                    for (index in 1..3) lineTo(corners[index].x.toFloat(), corners[index].y.toFloat())
                    close()
                }
                canvas.drawPath(fill, codewordFillPaint)
                val neighbors = arrayOf(
                    Coord(coord.x, coord.y - 1),
                    Coord(coord.x + 1, coord.y),
                    Coord(coord.x, coord.y + 1),
                    Coord(coord.x - 1, coord.y),
                )
                for (side in 0..3) {
                    if (byCoord[neighbors[side]]?.entry?.rawIndex == entry.rawIndex) continue
                    val next = (side + 1) % 4
                    canvas.drawLine(
                        corners[side].x.toFloat(), corners[side].y.toFloat(),
                        corners[next].x.toFloat(), corners[next].y.toFloat(),
                        codewordBorderPaint,
                    )
                }
            }
        }
    }

    private fun drawCodewordNumbers(canvas: Canvas, g: GridGeometry, invScale: Float) {
        codewordOverlay?.entries?.forEach { entry ->
            val first = entry.coordinates.firstOrNull() ?: return@forEach
            if (isModuleVisible(g, first)) drawCodewordNumber(canvas, g, entry, invScale)
        }
    }

    private fun isModuleVisible(g: GridGeometry, coord: Coord): Boolean {
        val p = g.moduleToImage(coord.x.toDouble(), coord.y.toDouble())
        val margin = g.localModulePitch(coord.x.toDouble(), coord.y.toDouble())
        val left = -offsetX / viewScale - margin
        val top = -offsetY / viewScale - margin
        val right = (width - offsetX) / viewScale + margin
        val bottom = (height - offsetY) / viewScale + margin
        return p.x in left..right && p.y in top..bottom
    }

    private fun moduleCorners(g: GridGeometry, coord: Coord): List<PointD> = listOf(
        g.moduleToImage(coord.x - .5, coord.y - .5),
        g.moduleToImage(coord.x + .5, coord.y - .5),
        g.moduleToImage(coord.x + .5, coord.y + .5),
        g.moduleToImage(coord.x - .5, coord.y + .5),
    )

    private fun drawCodewordNumber(canvas: Canvas, g: GridGeometry, entry: CodewordOverlayEntry, invScale: Float) {
        val first = entry.coordinates.firstOrNull() ?: return
        val p = g.moduleToImage(first.x.toDouble(), first.y.toDouble())
        val pitch = g.localModulePitch(first.x.toDouble(), first.y.toDouble()).toFloat()
        val textSize = (pitch * .43f).coerceAtLeast(7f * invScale)
        codewordTextPaint.textSize = textSize
        codewordTextOutlinePaint.textSize = textSize
        codewordTextOutlinePaint.strokeWidth = max(1.2f * invScale, textSize * .18f)
        val baseline = p.y.toFloat() - (codewordTextPaint.ascent() + codewordTextPaint.descent()) / 2f
        val label = "#${entry.rawIndex}"
        canvas.drawText(label, p.x.toFloat(), baseline, codewordTextOutlinePaint)
        canvas.drawText(label, p.x.toFloat(), baseline, codewordTextPaint)
    }

    /** Subdivide module-space edges so outlines follow non-rigid sampling coordinates. */
    private fun mappedOutline(g: GridGeometry, points: List<PointD>, closed: Boolean = true): Path = Path().apply {
        val first = g.moduleToImage(points[0].x, points[0].y)
        moveTo(first.x.toFloat(), first.y.toFloat())
        for (index in 0 until if (closed) points.size else points.size - 1) {
            val a = points[index]; val b = points[(index + 1) % points.size]
            val steps = if (g.warp == null) 1 else kotlin.math.ceil(a.distanceTo(b)).toInt().coerceIn(1, 256)
            for (step in 1..steps) {
                val m = a + (b - a) * (step.toDouble() / steps)
                val p = g.moduleToImage(m.x, m.y)
                lineTo(p.x.toFloat(), p.y.toFloat())
            }
        }
        if (closed) close()
    }

    private fun drawQrStructures(canvas: Canvas, g: GridGeometry, invScale: Float) {
        overlayPaint.style = Paint.Style.STROKE; overlayPaint.color = Color.rgb(0, 255, 120); overlayPaint.strokeWidth = 2.0f * invScale
        fun box(x0: Int, y0: Int, size: Int) {
            val path = mappedOutline(g, listOf(PointD(x0 - .5, y0 - .5), PointD(x0 + size - .5, y0 - .5),
                PointD(x0 + size - .5, y0 + size - .5), PointD(x0 - .5, y0 + size - .5)))
            canvas.drawPath(path, overlayPaint)
        }
        box(0, 0, 7); box(g.dimension - 7, 0, 7); box(0, g.dimension - 7, 7)
        val last = (g.dimension - 9).toDouble()
        canvas.drawPath(mappedOutline(g, listOf(PointD(8.0, 6.0), PointD(last, 6.0)), false), overlayPaint)
        canvas.drawPath(mappedOutline(g, listOf(PointD(6.0, 8.0), PointD(6.0, last)), false), overlayPaint)
    }

    private fun drawHeatmap(canvas: Canvas, g: GridGeometry, invScale: Float) {
        overlayPaint.style = Paint.Style.FILL
        for ((coord, info) in confidence) {
            if (coord.x !in 0 until g.dimension || coord.y !in 0 until g.dimension) continue
            val p = g.moduleToImage(coord.x.toDouble(), coord.y.toDouble())
            val pitch = g.localModulePitch(coord.x.toDouble(), coord.y.toDouble()).toFloat()
            overlayPaint.color = heatColor(info.score)
            canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), max(1.6f * invScale, pitch * .34f), overlayPaint)
        }
    }

    private companion object {
        const val CODEWORD_MIN_PITCH_PX = 6.0
        const val CODEWORD_NUMBER_MIN_PITCH_PX = 24.0
    }

    private fun heatColor(score: Float): Int {
        val s = score.coerceIn(0f, 1f)
        val r: Int
        val g: Int
        if (s < .5f) { r = 255; g = (s * 2f * 210f).toInt() }
        else { r = ((1f - s) * 2f * 255f).toInt(); g = 210 }
        return Color.argb(75, r, g, 20)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) navigatingViewport = false
        if (cornerEditEnabled && geometry != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = viewToImage(event.x, event.y)
                    if (p != null) {
                        val threshold = 30.0 / max(viewScale, 0.001f)
                        val corners = geometry!!.moduleCenterQuad().asList()
                        val nearest = corners.indices.minByOrNull { i -> hypot(corners[i].x - p.x, corners[i].y - p.y) }
                        if (nearest != null && corners[nearest].distanceTo(p) <= threshold) {
                            draggingCorner = nearest
                            dragStartTouch = p
                            dragStartCorner = corners[nearest]
                            dragStartGeometry = geometry
                            parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = draggingCorner
                    val p = viewToImage(event.x, event.y)
                    val startTouch = dragStartTouch
                    val startCorner = dragStartCorner
                    val base = dragStartGeometry
                    if (idx != null && p != null && startTouch != null && startCorner != null && base != null) {
                        val updated = runCatching { base.withCorner(idx, draggedHandlePoint(startCorner, startTouch, p)) }.getOrNull()
                        if (updated != null) {
                            geometry = updated
                            onGeometryMoved?.invoke(updated, false)
                            invalidate()
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val idx = draggingCorner
                    val p = viewToImage(event.x, event.y)
                    val startTouch = dragStartTouch
                    val startCorner = dragStartCorner
                    val base = dragStartGeometry
                    if (idx != null && p != null && startTouch != null && startCorner != null && base != null) {
                        val updated = runCatching { base.withCorner(idx, draggedHandlePoint(startCorner, startTouch, p)) }.getOrNull()
                        if (updated != null) {
                            geometry = updated
                            onGeometryMoved?.invoke(updated, true)
                        }
                    }
                    draggingCorner = null; dragStartTouch = null; dragStartCorner = null; dragStartGeometry = null
                    parent?.requestDisallowInterceptTouchEvent(false); invalidate()
                    if (idx != null) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val active = draggingCorner != null
                    dragStartGeometry?.let {
                        geometry = it
                        onGeometryMoved?.invoke(it, false)
                    }
                    draggingCorner = null; dragStartTouch = null; dragStartCorner = null; dragStartGeometry = null
                    parent?.requestDisallowInterceptTouchEvent(false); invalidate()
                    if (active) return true
                }
            }
        }

        cellGesture.handle(event, editorTool, geometry?.dimension ?: 0, ::positionToCell)?.let { update ->
            update.disallowIntercept?.let { parent?.requestDisallowInterceptTouchEvent(it) }
            update.commit?.let { commit ->
                setSelection(commit.cells, asRectangle = commit.asRectangle)
                if (commit.cells.isNotEmpty()) {
                    if (commit.value != null) onBrushApplied?.invoke(commit.cells, commit.value)
                    else onRangeSelected?.invoke(commit.cells)
                }
            }
            if (update.redraw) invalidate()
            return true
        }

        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            navigatingViewport = false
            invalidate()
        }
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val cell = pendingClickCell ?: selected?.let { Coord(it.first, it.second) } ?: return false
        pendingClickCell = null
        selected = cell.x to cell.y
        selectedCells = setOf(cell)
        selectionIsRectangle = false
        onCellSelected?.invoke(cell.x, cell.y)
        invalidate()
        return true
    }
}
