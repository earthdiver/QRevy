package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.localizedText
import io.github.earthdiver.qrrepair.core.FinderCandidate
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.alignQuadCornerToFinder
import io.github.earthdiver.qrrepair.core.assignedFromFinder
import io.github.earthdiver.qrrepair.core.provisionalFinderRole
import io.github.earthdiver.qrrepair.core.reliabilityScore
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class PreprocessOverlayView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var version = 1
    /** A periodicity estimate is sufficient for threshold preview, even before QR corner roles are confirmed. */
    private var gridPreviewAvailable = false
    private var logicalGridAssigned = false
    private val corners = MutableList(4) { PointD(0.0, 0.0) }
    private val finders = linkedMapOf<FinderRole, PointD>()
    private val autoDetectedFinders = mutableSetOf<FinderRole>()
    private val confirmedFinderRoles = mutableSetOf<FinderRole>()
    private val confirmedFinderEvidence = linkedMapOf<FinderRole, FinderCandidate>()
    private var confirmedPolarityHint = io.github.earthdiver.qrrepair.core.FinderPolarity.UNKNOWN
    private var finderRemovalEnabled = false
    private var dragCorner: Int? = null
    private var selectedCorner: Int? = null
    private var dragPoint: PointD? = null
    private var dragStartTouch: PointD? = null
    private var dragStartCorner: PointD? = null
    private var cornerDragStarted = false
    var finderPlacementRole: FinderRole? = null
    var onFinderHintPlaced: ((FinderRole, PointD) -> Unit)? = null
    var onFinderPlacementRejected: ((FinderRole) -> Unit)? = null
    var onLogicalGridAssigned: ((quarterTurns: Int) -> Unit)? = null
    var onFinderRemoved: ((FinderRole) -> Unit)? = null
    var onGeometryEdited: (() -> Unit)? = null
    var onGeometryEditFinished: (() -> Unit)? = null
    var onEditCellSelected: ((Int, Int) -> Unit)? = null
    var onCellBrushApplied: ((Set<Coord>, ModuleValue) -> Unit)? = null
    var onCellRangeSelected: ((Set<Coord>) -> Unit)? = null
    var onClassificationSummaryChanged: ((String) -> Unit)? = null
    private var editGeometry: GridGeometry? = null
    private var editMatrix: QrMatrix? = null
    private var editCell: Coord? = null
    var cellEditorTool: EditorTool = EditorTool.NAVIGATE
    private val editStroke = linkedSetOf<Coord>()
    private var editSelection: Set<Coord> = emptySet()
    private var editSelectionIsRectangle = false
    private var editRangeStart: Coord? = null
    private var editRangeEnd: Coord? = null
    private var cellEditInteractive = false
    private var lastClassificationSummary = ""

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageDestination = RectF()
    private val boundaryPath = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.CYAN }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(.7f); color = 0x6600FFFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA }
    private val finderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.YELLOW }
    private val autoFinderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4f); color = 0xFFFF8C00.toInt() }
    private val structurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xAA00FF66.toInt() }
    private val expectedDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x4439FF88 }
    private val expectedLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x22FFFFFF }
    private val expectedOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(.7f); color = 0x887CFFB2.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.YELLOW }
    private val editRangeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x3300E5FF }
    private val editRangeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = 0xFF00E5FF.toInt() }
    private val manualCellMarker = ManualCellMarker()
    private val magnifierPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val magnifierBackgroundPaint = Paint().apply { color = 0xDD000000.toInt() }
    private val magnifierBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val classificationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val classificationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val classificationTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        corners[0] = PointD(value.width * .08, value.height * .08)
        corners[1] = PointD(value.width * .92, value.height * .08)
        corners[2] = PointD(value.width * .92, value.height * .92)
        corners[3] = PointD(value.width * .08, value.height * .92)
        gridPreviewAvailable = false
        logicalGridAssigned = false
        invalidate()
    }

    fun setVersion(value: Int) { version = value.coerceIn(1, 40); invalidate() }
    fun setImageQuad(value: ImageQuad) { value.asList().forEachIndexed { index, point -> corners[index] = point }; gridPreviewAvailable = true; logicalGridAssigned = true; invalidate() }
    fun setPhysicalImageQuad(value: ImageQuad) { value.asList().forEachIndexed { index, point -> corners[index] = point }; gridPreviewAvailable = true; logicalGridAssigned = false; invalidate() }
    fun isLogicalGridAssigned(): Boolean = logicalGridAssigned
    private fun assignLogicalGrid(point: PointD, role: FinderRole) {
        if (logicalGridAssigned) return
        val physical = imageQuad().asList()
        val assigned = imageQuad().assignedFromFinder(point, role).asList()
        val quarterTurns = physical.indices.minBy { physical[it].distanceTo(assigned[0]) }
        assigned.forEachIndexed { index, corner -> corners[index] = corner }
        gridPreviewAvailable = true
        logicalGridAssigned = true
        onLogicalGridAssigned?.invoke(quarterTurns)
    }
    fun setFinderPoints(value: Map<FinderRole, PointD>) {
        if (value.isNotEmpty()) {
            gridPreviewAvailable = true
            logicalGridAssigned = true
        }
        finders.clear(); finders.putAll(value); autoDetectedFinders.clear()
        confirmedFinderRoles.clear(); confirmedFinderRoles += value.keys
        confirmedFinderEvidence.clear(); invalidate()
    }
    fun setConfirmedFinderPolarity(value: io.github.earthdiver.qrrepair.core.FinderPolarity) {
        confirmedPolarityHint = value
    }
    fun setAutoDetectedFinderPoints(value: Map<FinderRole, PointD>) {
        for ((role, point) in value) if (role !in finders) finders[role] = point
        autoDetectedFinders += value.keys
        invalidate()
    }
    fun beginFinderRemoval(): Boolean {
        if (confirmedFinderRoles.isEmpty()) return false
        finderRemovalEnabled = true
        invalidate()
        return true
    }
    fun cancelFinderRemoval() { finderRemovalEnabled = false; invalidate() }

    data class ProvisionalFinderAdjustment(val role: FinderRole) {
        val label: String get() = when (role) {
            FinderRole.TOP_LEFT -> localizedText("左上", "Top left")
            FinderRole.TOP_RIGHT -> localizedText("右上", "Top right")
            FinderRole.BOTTOM_LEFT -> localizedText("左下", "Bottom left")
        }
    }

    /** Uses only direct finder evidence; it deliberately moves one corner and leaves the rest alone. */
    fun applyProvisionalFinder(candidate: FinderCandidate): ProvisionalFinderAdjustment? {
        val source = bitmap ?: return null
        val role = provisionalFinderRole(candidate, source.width, source.height) ?: return null
        val point = PointD(candidate.centerX, candidate.centerY)
        val aligned = runCatching { alignQuadCornerToFinder(imageQuad(), version, role, point) }.getOrNull() ?: return null
        aligned.asList().forEachIndexed { index, p -> corners[index] = p }
        finders[role] = point
        autoDetectedFinders += role
        selectedCorner = when (role) {
            FinderRole.TOP_LEFT -> 0
            FinderRole.TOP_RIGHT -> 1
            FinderRole.BOTTOM_LEFT -> 3
        }
        dragPoint = corners[selectedCorner!!]
        invalidate()
        return ProvisionalFinderAdjustment(role)
    }
    fun setFinderPoint(role: FinderRole, point: PointD) {
        assignLogicalGrid(point, role)
        finders[role] = point; autoDetectedFinders.remove(role); confirmedFinderRoles += role; invalidate()
    }
    fun setVerifiedFinder(role: FinderRole, candidate: FinderCandidate) {
        confirmedFinderEvidence[role] = candidate
        setFinderPoint(role, PointD(candidate.centerX, candidate.centerY))
    }
    fun imageQuad(): ImageQuad = ImageQuad(corners[0], corners[1], corners[2], corners[3])
    internal fun expectedFinderCenter(role: FinderRole): PointD {
        val dimension = 17 + 4 * version
        val d = (dimension - 1).toDouble()
        val mx = if (role == FinderRole.TOP_RIGHT) dimension - 4.0 else 3.0
        val my = if (role == FinderRole.BOTTOM_LEFT) dimension - 4.0 else 3.0
        val u = mx / d
        val v = my / d
        val top = PointD(corners[0].x + (corners[1].x - corners[0].x) * u, corners[0].y + (corners[1].y - corners[0].y) * u)
        val bottom = PointD(corners[3].x + (corners[2].x - corners[3].x) * u, corners[3].y + (corners[2].y - corners[3].y) * u)
        return PointD(top.x + (bottom.x - top.x) * v, top.y + (bottom.y - top.y) * v)
    }
    /** Assigned Finders are constraints for recovery; each can be released in the Finder UI. */
    fun finderPoints(): Map<FinderRole, PointD> = confirmedFinderPoints(finders, confirmedFinderRoles)
    fun confirmedFinderPolarity(): io.github.earthdiver.qrrepair.core.FinderPolarity {
        val polarities = confirmedFinderRoles.mapNotNull { role -> confirmedFinderEvidence[role]?.polarity }
            .filter { it != io.github.earthdiver.qrrepair.core.FinderPolarity.UNKNOWN }
            .distinct()
        return polarities.singleOrNull()
            ?: confirmedPolarityHint.takeIf { confirmedFinderRoles.isNotEmpty() }
            ?: io.github.earthdiver.qrrepair.core.FinderPolarity.UNKNOWN
    }
    fun clearFinders() {
        finders.clear(); autoDetectedFinders.clear(); confirmedFinderRoles.clear()
        confirmedFinderEvidence.clear(); confirmedPolarityHint = io.github.earthdiver.qrrepair.core.FinderPolarity.UNKNOWN
        finderRemovalEnabled = false; invalidate()
    }
    fun removeConfirmedFinder(role: FinderRole): Boolean {
        if (role !in confirmedFinderRoles) return false
        confirmedFinderEvidence.remove(role)
        confirmedFinderRoles.remove(role)
        autoDetectedFinders.remove(role)
        finders.remove(role)
        if (confirmedFinderRoles.isEmpty()) {
            confirmedPolarityHint = io.github.earthdiver.qrrepair.core.FinderPolarity.UNKNOWN
            // Keep the current corners as a provisional physical grid. This permits a new
            // Finder role to rotate the logical grid again without losing manual cell edits.
            logicalGridAssigned = false
        }
        finderRemovalEnabled = false
        invalidate()
        return true
    }
    var highlightManualOnly: Boolean = false
        set(value) { field = value; invalidate() }

    fun setCellEditing(geometry: GridGeometry, matrix: QrMatrix, selected: Coord? = editCell) {
        editGeometry = geometry; editMatrix = matrix
        cellEditInteractive = true
        editCell = selected?.takeIf { it.x in 0 until matrix.dimension && it.y in 0 until matrix.dimension }
        invalidate()
    }
    fun setCellEditPreview(geometry: GridGeometry, matrix: QrMatrix) {
        editGeometry = geometry; editMatrix = matrix; editCell = null
        editSelection = emptySet(); editSelectionIsRectangle = false; cellEditInteractive = false
        invalidate()
    }
    fun clearCellEditing() {
        editGeometry = null; editMatrix = null; editCell = null
        editStroke.clear(); editSelection = emptySet(); editSelectionIsRectangle = false
        editRangeStart = null; editRangeEnd = null
        cellEditorTool = EditorTool.NAVIGATE
        invalidate()
    }
    fun selectedEditCell(): Coord? = editCell
    fun selectEditCell(coord: Coord) {
        val matrix = editMatrix ?: return
        editCell = Coord(coord.x.coerceIn(0, matrix.dimension - 1), coord.y.coerceIn(0, matrix.dimension - 1))
        editSelection = setOf(editCell!!)
        editSelectionIsRectangle = false
        invalidate()
    }
    fun setCellSelection(coords: Collection<Coord>, asRectangle: Boolean = false) {
        val matrix = editMatrix ?: return
        editSelection = coords.filter { it.x in 0 until matrix.dimension && it.y in 0 until matrix.dimension }.toSet()
        editSelectionIsRectangle = asRectangle && editSelection.isNotEmpty()
        invalidate()
    }
    fun clearCornerSelection() {
        selectedCorner = null; dragCorner = null; dragPoint = null
        dragStartTouch = null; dragStartCorner = null; cornerDragStarted = false
        invalidate()
    }

    private fun currentEditRangeCells(): Set<Coord> {
        val start = editRangeStart ?: return emptySet()
        val end = editRangeEnd ?: return emptySet()
        return buildSet {
            for (y in minOf(start.y, end.y)..maxOf(start.y, end.y)) {
                for (x in minOf(start.x, end.x)..maxOf(start.x, end.x)) add(Coord(x, y))
            }
        }
    }

    private fun editFootprint(center: Coord, size: Int): Set<Coord> {
        val dimension = editMatrix?.dimension ?: return emptySet()
        return moduleFootprint(center, size, dimension)
    }

    private fun EditorTool.editBrushSize(): Int? = when (this) {
        EditorTool.BRUSH_ZERO, EditorTool.BRUSH_ONE, EditorTool.BRUSH_ERASURE -> 1
        EditorTool.BRUSH_ERASURE_WIDE -> 3
        else -> null
    }

    private fun EditorTool.editStampSize(): Int? = when (this) {
        EditorTool.STAMP_ERASURE_SMALL -> 3
        EditorTool.STAMP_ERASURE_MEDIUM -> 5
        EditorTool.STAMP_ERASURE_LARGE -> 7
        else -> null
    }

    private fun drawEditRange(
        canvas: Canvas,
        geometry: GridGeometry,
        cells: Set<Coord>,
        screenPoint: (PointD) -> PointD,
    ) {
        if (cells.isEmpty()) return
        val minX = cells.minOf { it.x }
        val maxX = cells.maxOf { it.x }
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y }
        val points = listOf(
            geometry.moduleToImage(minX - .5, minY - .5),
            geometry.moduleToImage(maxX + .5, minY - .5),
            geometry.moduleToImage(maxX + .5, maxY + .5),
            geometry.moduleToImage(minX - .5, maxY + .5),
        ).map(screenPoint)
        val path = Path().apply {
            moveTo(points[0].x.toFloat(), points[0].y.toFloat())
            for (index in 1..3) lineTo(points[index].x.toFloat(), points[index].y.toFloat())
            close()
        }
        canvas.drawPath(path, editRangeFillPaint)
        canvas.drawPath(path, editRangeBorderPaint)
    }

    /** Moves the last selected corner in image-axis increments of a quarter module. */
    fun nudgeSelectedCorner(moduleDx: Double, moduleDy: Double): Boolean {
        val source = bitmap ?: return false
        val index = selectedCorner ?: return false
        val dimension = 17 + 4 * version
        val horizontal = (corners[0].distanceTo(corners[1]) + corners[3].distanceTo(corners[2])) / 2.0
        val vertical = (corners[0].distanceTo(corners[3]) + corners[1].distanceTo(corners[2])) / 2.0
        val pitch = ((horizontal + vertical) / (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val p = corners[index]
        corners[index] = PointD(
            (p.x + moduleDx * pitch).coerceIn(0.0, source.width.toDouble()),
            (p.y + moduleDy * pitch).coerceIn(0.0, source.height.toDouble()),
        )
        dragPoint = corners[index]
        onGeometryEdited?.invoke()
        onGeometryEditFinished?.invoke()
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val source = bitmap ?: return
        val scale = min(width.toDouble() / source.width, height.toDouble() / source.height)
        val ox = (width - source.width * scale) / 2.0
        val oy = (height - source.height * scale) / 2.0
        imageDestination.set(ox.toFloat(), oy.toFloat(), (ox + source.width * scale).toFloat(), (oy + source.height * scale).toFloat())
        canvas.drawBitmap(source, null, imageDestination, imagePaint)
        fun v(p: PointD) = PointD(ox + p.x * scale, oy + p.y * scale)
        val vc = corners.map(::v)
        // Classification and edit overlays are tied to the last sampled geometry. Besides being
        // stale during a drag, redrawing every module here is O(dimension²). Keep the lightweight
        // boundary/grid responsive and restore sampled overlays after ACTION_UP refreshes them.
        val renderSampledCells = dragCorner == null
        if (gridPreviewAvailable && renderSampledCells) drawClassificationPreview(canvas, scale, ox, oy)
        val dimension = 17 + 4 * version
        val halfModule = 0.5 / (dimension - 1)
        fun gridPoint(u: Double, v: Double): PointD {
            val top = PointD(vc[0].x + (vc[1].x - vc[0].x) * u, vc[0].y + (vc[1].y - vc[0].y) * u)
            val bottom = PointD(vc[3].x + (vc[2].x - vc[3].x) * u, vc[3].y + (vc[2].y - vc[3].y) * u)
            return PointD(top.x + (bottom.x - top.x) * v, top.y + (bottom.y - top.y) * v)
        }
        val boundaryCorners = listOf(
            gridPoint(-halfModule, -halfModule),
            gridPoint(1.0 + halfModule, -halfModule),
            gridPoint(1.0 + halfModule, 1.0 + halfModule),
            gridPoint(-halfModule, 1.0 + halfModule),
        )
        boundaryPath.rewind()
        boundaryPath.moveTo(boundaryCorners[0].x.toFloat(), boundaryCorners[0].y.toFloat())
        for (i in 1..3) boundaryPath.lineTo(boundaryCorners[i].x.toFloat(), boundaryCorners[i].y.toFloat())
        boundaryPath.close()
        canvas.drawPath(boundaryPath, linePaint)
        if (gridPreviewAvailable) {
            val gridStep = when { dimension <= 33 -> 1; dimension <= 65 -> 2; else -> 4 }
            val gridLines = ((0..dimension step gridStep) + dimension).distinct()
            for (i in gridLines) {
                val t = (i - 0.5) / (dimension - 1)
                val verticalStart = gridPoint(t, -halfModule)
                val verticalEnd = gridPoint(t, 1.0 + halfModule)
                val horizontalStart = gridPoint(-halfModule, t)
                val horizontalEnd = gridPoint(1.0 + halfModule, t)
                canvas.drawLine(verticalStart.x.toFloat(), verticalStart.y.toFloat(), verticalEnd.x.toFloat(), verticalEnd.y.toFloat(), gridPaint)
                canvas.drawLine(horizontalStart.x.toFloat(), horizontalStart.y.toFloat(), horizontalEnd.x.toFloat(), horizontalEnd.y.toFloat(), gridPaint)
            }
            if (logicalGridAssigned && !highlightManualOnly) {
                drawExpectedStructures(canvas, scale, ox, oy)
                val timing = 6.0 / (dimension - 1)
                drawInterpolated(canvas, vc[0], vc[3], vc[1], vc[2], timing, structurePaint)
                drawInterpolated(canvas, vc[0], vc[1], vc[3], vc[2], timing, structurePaint)
                drawFinderBox(canvas, vc, dimension, 0, 0)
                drawFinderBox(canvas, vc, dimension, dimension - 7, 0)
                drawFinderBox(canvas, vc, dimension, 0, dimension - 7)
            }
        }
        vc.forEachIndexed { index, point ->
            val nearbyRole = when (index) {
                0 -> FinderRole.TOP_LEFT
                1 -> FinderRole.TOP_RIGHT
                3 -> FinderRole.BOTTOM_LEFT
                else -> null
            }
            handlePaint.color = when (nearbyRole) {
                in confirmedFinderRoles -> 0xFF00C853.toInt()
                in autoDetectedFinders -> 0xFFFF8C00.toInt()
                else -> 0xFFFF8C00.toInt()
            }
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(10f), handlePaint)
            if (selectedCorner == index) canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(15f), selectedPaint)
        }
        finders.forEach { (role, p) ->
            val paint = if (role in autoDetectedFinders) autoFinderPaint else finderPaint
            // A Finder marker represents observed/user evidence, so draw it at its stored point.
            // Expected QR structure is already shown separately by the grid overlay.
            val q = v(p); canvas.drawCircle(q.x.toFloat(), q.y.toFloat(), dp(18f), paint)
            paint.style = Paint.Style.FILL; paint.textSize = dp(12f)
            canvas.drawText(role.name, q.x.toFloat() + dp(20f), q.y.toFloat(), paint)
            paint.style = Paint.Style.STROKE
        }
        if (renderSampledCells) editGeometry?.let { geometry ->
            val matrix = editMatrix ?: return@let
            val activeRange = currentEditRangeCells()
            when {
                activeRange.isNotEmpty() -> drawEditRange(canvas, geometry, activeRange, ::v)
                editSelectionIsRectangle -> drawEditRange(canvas, geometry, editSelection, ::v)
            }
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                if (!matrix[x, y].source.isUserEdit) continue
                val q = v(geometry.moduleToImage(x.toDouble(), y.toDouble()))
                manualCellMarker.draw(canvas, q.x.toFloat(), q.y.toFloat(), dp(5f), dp(1.5f), matrix[x, y].source == ModuleSource.USER_SOFT || matrix[x, y].value == ModuleValue.ERASED)
            }
            editCell?.takeIf { cellEditorTool == EditorTool.NAVIGATE }?.let { cell ->
                val imagePoint = geometry.moduleToImage(cell.x.toDouble(), cell.y.toDouble())
                val q = v(imagePoint)
                selectedPaint.color = Color.YELLOW
                selectedPaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(q.x.toFloat(), q.y.toFloat(), dp(10f), selectedPaint)
                drawMagnifier(canvas, source, imagePoint, scale)
            }
            for (cell in editSelection + editStroke + currentEditRangeCells()) {
                val q = v(geometry.moduleToImage(cell.x.toDouble(), cell.y.toDouble()))
                selectedPaint.color = Color.YELLOW
                selectedPaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(q.x.toFloat(), q.y.toFloat(), dp(4f), selectedPaint)
            }
        }
        (dragPoint ?: selectedCorner?.let(corners::get))?.let { drawMagnifier(canvas, source, it, scale) }
    }

    /** Draws the ideal fixed QR modules lightly, so alignment can be judged against the image. */
    private fun drawExpectedStructures(canvas: Canvas, fitScale: Double, ox: Double, oy: Double) {
        val dimension = 17 + 4 * version
        val quad = imageQuad()
        val pitch = ((quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = runCatching {
            GridGeometry(
                version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE,
            ).reanchored()
        }.getOrNull() ?: return
        fun screen(p: PointD) = PointD(ox + p.x * fitScale, oy + p.y * fitScale)
        fun module(mx: Int, my: Int, black: Boolean) {
            val points = listOf(
                geometry.moduleToImage(mx - .43, my - .43),
                geometry.moduleToImage(mx + .43, my - .43),
                geometry.moduleToImage(mx + .43, my + .43),
                geometry.moduleToImage(mx - .43, my + .43),
            ).map(::screen)
            val path = Path().apply {
                moveTo(points[0].x.toFloat(), points[0].y.toFloat())
                for (i in 1..3) lineTo(points[i].x.toFloat(), points[i].y.toFloat())
                close()
            }
            canvas.drawPath(path, if (black) expectedDarkPaint else expectedLightPaint)
            canvas.drawPath(path, expectedOutlinePaint)
        }
        fun finder(originX: Int, originY: Int) {
            for (y in 0 until 7) for (x in 0 until 7) {
                val black = x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4)
                module(originX + x, originY + y, black)
            }
        }
        finder(0, 0)
        finder(dimension - 7, 0)
        finder(0, dimension - 7)
        for (k in 8..dimension - 9) {
            module(k, 6, (k and 1) == 0)
            module(6, k, (k and 1) == 0)
        }
        module(8, dimension - 8, black = true)
    }

    private fun drawClassificationPreview(canvas: Canvas, fitScale: Double, ox: Double, oy: Double) {
        val matrix = editMatrix ?: return
        val dimension = 17 + 4 * version
        val quad = imageQuad()
        val pitch = ((quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = runCatching {
            GridGeometry(version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y, pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        }.getOrNull() ?: return
        val moduleOnScreen = pitch * fitScale
        val step = if (editMatrix != null || dimension <= 85) 1 else 2
        val radius = (moduleOnScreen * .31).coerceIn(dp(1.4f).toDouble(), dp(6f).toDouble()).toFloat()
        var ones = 0; var zeros = 0; var unknown = 0; var priority = 0
        runCatching {
            for (my in 0 until dimension step step) for (mx in 0 until dimension step step) {
                val p = geometry.moduleToImage(mx.toDouble(), my.toDouble())
                val editedCell = matrix.takeIf { it.dimension == dimension }?.get(mx, my) ?: continue
                val value = editedCell.value
                val vx = (ox + p.x * fitScale).toFloat()
                val vy = (oy + p.y * fitScale).toFloat()
                if (editedCell.prioritySearch) priority++ else when (value) {
                    ModuleValue.ONE -> ones++
                    ModuleValue.ZERO -> zeros++
                    ModuleValue.ERASED -> unknown++
                }
                if (highlightManualOnly && editedCell.source.isUserEdit != true &&
                    Coord(mx, my) != editCell && Coord(mx, my) !in editSelection) continue
                drawClassificationValue(
                    canvas = canvas,
                    value = value,
                    x = vx,
                    y = vy,
                    textSize = (moduleOnScreen * .72).coerceIn(dp(6f).toDouble(), dp(13f).toDouble()).toFloat(),
                    unknownRadius = radius,
                    prioritySearch = editedCell.prioritySearch,
                )
            }
        }
        val legend = localizedText("判定: 1=$ones  0=$zeros  ?=$unknown  *=$priority", "Classification: 1=$ones  0=$zeros  ?=$unknown  *=$priority") +
            if (step > 1) localizedText("（間引き表示）", " (sampled view)") else ""
        if (legend != lastClassificationSummary) {
            lastClassificationSummary = legend
            post { if (lastClassificationSummary == legend) onClassificationSummaryChanged?.invoke(legend) }
        }
    }

    /** Keeps 1/0 legible over either light or dark source pixels without semantic cell colors. */
    private fun drawClassificationValue(
        canvas: Canvas,
        value: ModuleValue,
        x: Float,
        y: Float,
        textSize: Float,
        unknownRadius: Float,
        prioritySearch: Boolean = false,
    ) {
        if (value == ModuleValue.ERASED) {
            classificationPaint.color = if (prioritySearch) 0xDDAA55DD.toInt() else 0xDDFFD600.toInt()
            canvas.drawCircle(x, y, unknownRadius, classificationPaint)
        }
        val label = if (prioritySearch) "*" else when (value) {
            ModuleValue.ONE -> "1"
            ModuleValue.ZERO -> "0"
            ModuleValue.ERASED -> "?"
        }
        classificationTextPaint.textSize = textSize
        classificationTextOutlinePaint.textSize = textSize
        classificationTextOutlinePaint.strokeWidth = max(dp(1f), textSize * .20f)
        val baseline = y - (classificationTextPaint.ascent() + classificationTextPaint.descent()) / 2f
        if (value != ModuleValue.ERASED) canvas.drawText(label, x, baseline, classificationTextOutlinePaint)
        classificationTextPaint.color = if (value == ModuleValue.ERASED) Color.BLACK else Color.WHITE
        canvas.drawText(label, x, baseline, classificationTextPaint)
    }

    private data class MagnifierMapping(val source: Rect, val destination: RectF)

    private fun magnifierMapping(source: Bitmap, point: PointD, fitScale: Double): MagnifierMapping? {
        val size = min(dp(144f), min(width, height) * .42f)
        if (size < dp(72f)) return null
        val margin = dp(12f)
        val centerX = if (point.x * fitScale < width / 2.0) width - margin - size / 2f else margin + size / 2f
        val centerY = margin + size / 2f
        val halfSource = max(4.0, size / (2.0 * fitScale * 5.0))
        val left = (point.x - halfSource).coerceIn(0.0, (source.width - 1).toDouble())
        val top = (point.y - halfSource).coerceIn(0.0, (source.height - 1).toDouble())
        val right = (point.x + halfSource).coerceIn(left + 1.0, source.width.toDouble())
        val bottom = (point.y + halfSource).coerceIn(top + 1.0, source.height.toDouble())
        return MagnifierMapping(
            Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()),
            RectF(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f),
        )
    }

    private fun drawMagnifier(canvas: Canvas, source: Bitmap, point: PointD, fitScale: Double) {
        val mapping = magnifierMapping(source, point, fitScale) ?: return
        val src = mapping.source
        val dst = mapping.destination
        canvas.drawRect(dst, magnifierBackgroundPaint)
        canvas.drawBitmap(source, src, dst, magnifierPaint)
        canvas.save()
        canvas.clipRect(dst)
        if (gridPreviewAvailable) drawMagnifierGrid(canvas, src, dst)
        val geometry = editGeometry
        val matrix = editMatrix
        if (geometry != null && matrix != null) {
            val selectedCells = editSelection + editStroke + currentEditRangeCells()
            val moduleSize = (geometry.localModulePitch(
                editCell?.x?.toDouble() ?: 0.0,
                editCell?.y?.toDouble() ?: 0.0,
            ) * dst.width() / src.width().coerceAtLeast(1)).toFloat()
            val textSize = (moduleSize * .70f).coerceIn(dp(8f), dp(18f))
            val unknownRadius = (moduleSize * .30f).coerceIn(dp(3f), dp(9f))
            for (my in 0 until matrix.dimension) for (mx in 0 until matrix.dimension) {
                val imagePoint = geometry.moduleToImage(mx.toDouble(), my.toDouble())
                if (imagePoint.x !in src.left.toDouble()..src.right.toDouble() ||
                    imagePoint.y !in src.top.toDouble()..src.bottom.toDouble()
                ) continue
                val labelX = dst.left + ((imagePoint.x - src.left) / src.width().coerceAtLeast(1)) * dst.width()
                val labelY = dst.top + ((imagePoint.y - src.top) / src.height().coerceAtLeast(1)) * dst.height()
                if (!highlightManualOnly || matrix[mx, my].source.isUserEdit || Coord(mx, my) in selectedCells || Coord(mx, my) == editCell) {
                    drawClassificationValue(canvas, matrix[mx, my].value, labelX.toFloat(), labelY.toFloat(), textSize, unknownRadius, matrix[mx, my].prioritySearch)
                }
                if (Coord(mx, my) in selectedCells) {
                    selectedPaint.color = Color.YELLOW
                    selectedPaint.strokeWidth = dp(1.5f)
                    canvas.drawCircle(labelX.toFloat(), labelY.toFloat(), unknownRadius * 1.45f, selectedPaint)
                }
                if (matrix[mx, my].source.isUserEdit) {
                    manualCellMarker.draw(canvas, labelX.toFloat(), labelY.toFloat(),
                        (moduleSize * .38f).coerceIn(dp(5f), dp(16f)), dp(1.5f), matrix[mx, my].source == ModuleSource.USER_SOFT || matrix[mx, my].value == ModuleValue.ERASED)
                }
            }
        }
        canvas.restore()
        canvas.drawRect(dst, magnifierBorderPaint)
        selectedPaint.color = Color.YELLOW
        selectedPaint.strokeWidth = dp(1.5f)
        // When the source rectangle is clipped at an image edge, the selected point is not at
        // the inset center. Draw the crosshair at its true mapped position.
        val crossX = dst.left + ((point.x - src.left) / src.width().coerceAtLeast(1)) * dst.width()
        val crossY = dst.top + ((point.y - src.top) / src.height().coerceAtLeast(1)) * dst.height()
        canvas.drawLine(crossX.toFloat() - dp(18f), crossY.toFloat(), crossX.toFloat() + dp(18f), crossY.toFloat(), selectedPaint)
        canvas.drawLine(crossX.toFloat(), crossY.toFloat() - dp(18f), crossX.toFloat(), crossY.toFloat() + dp(18f), selectedPaint)
        canvas.drawCircle(crossX.toFloat(), crossY.toFloat(), dp(5f), selectedPaint)
    }

    private fun drawMagnifierGrid(canvas: Canvas, src: Rect, dst: RectF) {
        val dimension = 17 + 4 * version
        val quad = imageQuad()
        val pitch = ((quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = runCatching {
            GridGeometry(
                version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                pitch, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE,
            ).reanchored()
        }.getOrNull() ?: return
        val sourceCorners = listOf(
            PointD(src.left.toDouble(), src.top.toDouble()),
            PointD(src.right.toDouble(), src.top.toDouble()),
            PointD(src.right.toDouble(), src.bottom.toDouble()),
            PointD(src.left.toDouble(), src.bottom.toDouble()),
        ).map { geometry.imageToModule(it.x, it.y) }
        val xLines = magnifierGridLineRange(sourceCorners.minOf { it.x }, sourceCorners.maxOf { it.x }, dimension)
        val yLines = magnifierGridLineRange(sourceCorners.minOf { it.y }, sourceCorners.maxOf { it.y }, dimension)
        fun map(p: PointD) = PointD(
            dst.left + (p.x - src.left) / src.width().coerceAtLeast(1) * dst.width(),
            dst.top + (p.y - src.top) / src.height().coerceAtLeast(1) * dst.height(),
        )
        val saved = canvas.save()
        canvas.clipRect(dst)
        for (line in xLines) {
            val moduleX = line - .5
            val start = map(geometry.moduleToImage(moduleX, -.5))
            val end = map(geometry.moduleToImage(moduleX, dimension - .5))
            canvas.drawLine(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), gridPaint)
        }
        for (line in yLines) {
            val moduleY = line - .5
            val start = map(geometry.moduleToImage(-.5, moduleY))
            val end = map(geometry.moduleToImage(dimension - .5, moduleY))
            canvas.drawLine(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), gridPaint)
        }
        canvas.restoreToCount(saved)
    }

    private fun drawInterpolated(canvas: Canvas, a: PointD, b: PointD, c: PointD, d: PointD, t: Double, paint: Paint = gridPaint) {
        val p = PointD(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        val q = PointD(c.x + (d.x - c.x) * t, c.y + (d.y - c.y) * t)
        canvas.drawLine(p.x.toFloat(), p.y.toFloat(), q.x.toFloat(), q.y.toFloat(), paint)
    }

    private fun drawFinderBox(canvas: Canvas, q: List<PointD>, dimension: Int, moduleX: Int, moduleY: Int) {
        fun map(x: Double, y: Double): PointD {
            val u = x / (dimension - 1); val v = y / (dimension - 1)
            val top = PointD(q[0].x + (q[1].x - q[0].x) * u, q[0].y + (q[1].y - q[0].y) * u)
            val bottom = PointD(q[3].x + (q[2].x - q[3].x) * u, q[3].y + (q[2].y - q[3].y) * u)
            return PointD(top.x + (bottom.x - top.x) * v, top.y + (bottom.y - top.y) * v)
        }
        val pts = listOf(map(moduleX.toDouble(), moduleY.toDouble()), map((moduleX + 6).toDouble(), moduleY.toDouble()), map((moduleX + 6).toDouble(), (moduleY + 6).toDouble()), map(moduleX.toDouble(), (moduleY + 6).toDouble()))
        val path = Path().apply { moveTo(pts[0].x.toFloat(), pts[0].y.toFloat()); for (i in 1..3) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat()); close() }
        canvas.drawPath(path, structurePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = bitmap ?: return false
        val scale = min(width.toDouble() / source.width, height.toDouble() / source.height)
        val ox = (width - source.width * scale) / 2.0; val oy = (height - source.height * scale) / 2.0
        var p = PointD(((event.x - ox) / scale).coerceIn(0.0, source.width.toDouble()), ((event.y - oy) / scale).coerceIn(0.0, source.height.toDouble()))
        val magnifierFocus = if (cellEditorTool == EditorTool.NAVIGATE) {
            editGeometry?.takeIf { cellEditInteractive }?.let { geometry -> editCell?.let { geometry.moduleToImage(it.x.toDouble(), it.y.toDouble()) } }
        } else {
            null
        }
        val magnifier = magnifierFocus?.let { magnifierMapping(source, it, scale) }
        if (magnifier != null && magnifier.destination.contains(event.x, event.y)) {
            p = PointD(
                (magnifier.source.left + (event.x - magnifier.destination.left) / magnifier.destination.width() * magnifier.source.width()).toDouble(),
                (magnifier.source.top + (event.y - magnifier.destination.top) / magnifier.destination.height() * magnifier.source.height()).toDouble(),
            )
        }
        fun editCoord(): Coord? {
            val geometry = editGeometry ?: return null
            val dimension = editMatrix?.dimension ?: return null
            val module = geometry.imageToModule(p.x, p.y)
            return Coord(kotlin.math.floor(module.x + .5).toInt(), kotlin.math.floor(module.y + .5).toInt())
                .takeIf { it.x in 0 until dimension && it.y in 0 until dimension }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (editGeometry != null && cellEditInteractive) {
                    val cell = editCoord() ?: return true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    when (cellEditorTool) {
                        EditorTool.NAVIGATE -> {
                            editCell = cell
                            onEditCellSelected?.invoke(cell.x, cell.y)
                        }
                        EditorTool.BRUSH_ZERO, EditorTool.BRUSH_ONE, EditorTool.BRUSH_ERASURE, EditorTool.BRUSH_ERASURE_WIDE -> {
                            editStroke.clear(); editStroke += editFootprint(cell, cellEditorTool.editBrushSize() ?: 1); editCell = cell
                            editSelectionIsRectangle = false
                        }
                        EditorTool.STAMP_ERASURE_SMALL, EditorTool.STAMP_ERASURE_MEDIUM, EditorTool.STAMP_ERASURE_LARGE -> {
                            editStroke.clear(); editStroke += editFootprint(cell, cellEditorTool.editStampSize() ?: 3); editCell = cell
                            editSelectionIsRectangle = false
                        }
                        EditorTool.RECT_SELECT -> {
                            editRangeStart = cell; editRangeEnd = cell; editCell = cell
                            editSelection = emptySet(); editSelectionIsRectangle = false
                        }
                    }
                    invalidate()
                    return true
                }
                finderPlacementRole?.let { role ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (logicalGridAssigned && role !in confirmedFinderRoles) {
                        val q = imageQuad()
                        val dimension = 17 + 4 * version
                        val modulePitch = ((q.topLeft.distanceTo(q.topRight) + q.topLeft.distanceTo(q.bottomLeft)) /
                            (2.0 * (dimension - 1))).coerceAtLeast(.25)
                        val tolerance = max(modulePitch * 4.0, dp(36f) / scale)
                        if (p.distanceTo(expectedFinderCenter(role)) > tolerance) {
                            finderPlacementRole = null
                            onFinderPlacementRejected?.invoke(role)
                            invalidate()
                            return true
                        }
                    }
                    assignLogicalGrid(p, role)
                    finders[role] = p
                    confirmedFinderRoles += role
                    autoDetectedFinders.remove(role)
                    finderPlacementRole = null; invalidate()
                    onGeometryEdited?.invoke()
                    onGeometryEditFinished?.invoke()
                    onFinderHintPlaced?.invoke(role, p)
                    return true
                }
                if (finderRemovalEnabled) {
                    val role = confirmedFinderRoles.minByOrNull { confirmedRole ->
                        val point = finders.getValue(confirmedRole)
                        hypot(point.x - p.x, point.y - p.y)
                    }?.takeIf { confirmedRole ->
                        val point = finders.getValue(confirmedRole)
                        hypot(point.x - p.x, point.y - p.y) <= dp(36f) / scale
                    }
                    if (role != null) {
                        if (removeConfirmedFinder(role)) onFinderRemoved?.invoke(role)
                    }
                    return true
                }
                val tolerance = dp(32f) / scale
                val editableCorners = corners.indices.filter { index ->
                    val role = when (index) {
                        0 -> FinderRole.TOP_LEFT
                        1 -> FinderRole.TOP_RIGHT
                        3 -> FinderRole.BOTTOM_LEFT
                        else -> null
                    }
                    role !in confirmedFinderRoles
                }
                dragCorner = editableCorners.minByOrNull { hypot(corners[it].x - p.x, corners[it].y - p.y) }
                    ?.takeIf { hypot(corners[it].x - p.x, corners[it].y - p.y) <= tolerance }
                selectedCorner = dragCorner
                dragPoint = dragCorner?.let(corners::get)
                dragStartTouch = if (dragCorner != null) p else null
                dragStartCorner = dragCorner?.let { corners[it] }
                cornerDragStarted = false
                if (dragCorner != null) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return dragCorner != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (editGeometry != null && cellEditInteractive) {
                    editCoord()?.let { cell ->
                        when (cellEditorTool) {
                            EditorTool.BRUSH_ZERO, EditorTool.BRUSH_ONE, EditorTool.BRUSH_ERASURE, EditorTool.BRUSH_ERASURE_WIDE -> {
                                editStroke += editFootprint(cell, cellEditorTool.editBrushSize() ?: 1); editCell = cell
                            }
                            EditorTool.STAMP_ERASURE_SMALL, EditorTool.STAMP_ERASURE_MEDIUM, EditorTool.STAMP_ERASURE_LARGE -> {
                                editStroke.clear(); editStroke += editFootprint(cell, cellEditorTool.editStampSize() ?: 3); editCell = cell
                            }
                            EditorTool.RECT_SELECT -> { editRangeEnd = cell; editCell = cell }
                            EditorTool.NAVIGATE -> Unit
                        }
                    }
                    invalidate()
                    return true
                }
                dragCorner?.let { index ->
                    val startTouch = dragStartTouch ?: p
                    val startCorner = dragStartCorner ?: corners[index]
                    if (!cornerDragStarted) {
                        val slopInImage = ViewConfiguration.get(context).scaledTouchSlop / scale
                        cornerDragStarted = startTouch.distanceTo(p) > slopInImage
                    }
                    if (cornerDragStarted) {
                        // Preserve the initial finger-to-handle offset. Selecting a handle no longer
                        // snaps its center to the finger, and ordinary tap jitter changes nothing.
                        corners[index] = PointD(
                            (startCorner.x + p.x - startTouch.x).coerceIn(0.0, source.width.toDouble()),
                            (startCorner.y + p.y - startTouch.y).coerceIn(0.0, source.height.toDouble()),
                        )
                        onGeometryEdited?.invoke()
                    }
                    // The magnifier and its crosshair follow the actual corner handle, not the
                    // finger. The two differ whenever the drag begins off-center.
                    dragPoint = corners[index]
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (editGeometry != null && cellEditInteractive) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (cellEditorTool == EditorTool.NAVIGATE) performClick()
                        val brushValue = when (cellEditorTool) {
                            EditorTool.BRUSH_ZERO -> ModuleValue.ZERO
                            EditorTool.BRUSH_ONE -> ModuleValue.ONE
                            EditorTool.BRUSH_ERASURE -> ModuleValue.ERASED
                            EditorTool.BRUSH_ERASURE_WIDE,
                            EditorTool.STAMP_ERASURE_SMALL,
                            EditorTool.STAMP_ERASURE_MEDIUM,
                            EditorTool.STAMP_ERASURE_LARGE -> ModuleValue.ERASED
                            else -> null
                        }
                        if (brushValue != null && editStroke.isNotEmpty()) {
                            editSelection = editStroke.toSet()
                            editSelectionIsRectangle = false
                            onCellBrushApplied?.invoke(editSelection, brushValue)
                        }
                        if (cellEditorTool == EditorTool.RECT_SELECT) {
                            val start = editRangeStart
                            val end = editRangeEnd
                            if (start != null && end != null) {
                                editSelection = buildSet {
                                    for (y in minOf(start.y, end.y)..maxOf(start.y, end.y)) {
                                        for (x in minOf(start.x, end.x)..maxOf(start.x, end.x)) add(Coord(x, y))
                                    }
                                }
                                editSelectionIsRectangle = true
                                onCellRangeSelected?.invoke(editSelection)
                            }
                        }
                    }
                    editStroke.clear(); editRangeStart = null; editRangeEnd = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
                val active = dragCorner != null
                val changed = event.actionMasked == MotionEvent.ACTION_UP && cornerDragStarted
                dragCorner = null; dragPoint = null; dragStartTouch = null; dragStartCorner = null
                cornerDragStarted = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (changed) onGeometryEditFinished?.invoke()
                if (active && !changed && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return active
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

internal fun magnifierGridLineRange(minModule: Double, maxModule: Double, dimension: Int): IntRange {
    val first = floor(minModule + .5).toInt().coerceIn(0, dimension)
    val last = ceil(maxModule + .5).toInt().coerceIn(0, dimension)
    return first..last
}

internal fun confirmedFinderPoints(
    all: Map<FinderRole, PointD>,
    confirmed: Set<FinderRole>,
): Map<FinderRole, PointD> = all.filterKeys { it in confirmed }
