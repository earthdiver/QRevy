package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import io.github.earthdiver.qrrepair.localizedText
import io.github.earthdiver.qrrepair.core.CellConfidence
import io.github.earthdiver.qrrepair.core.CodewordExtractor
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ModuleRepairTrace
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrFunctionMask
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RepairFilter
import io.github.earthdiver.qrrepair.core.RepairReasonKind
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Clean QR result inspector. It deliberately uses module coordinates rather than source-image geometry,
 * so high-version symbols remain inspectable even when the source photograph is heavily distorted.
 */
class QrResultInspectorView(context: Context) : View(context) {
    private val quiet = 2f
    private var before: QrMatrix? = null
    private var after: QrMatrix? = null
    private var traces: Map<Coord, ModuleRepairTrace> = emptyMap()
    private var filter: RepairFilter = RepairFilter.ALL_CHANGED
    private var candidateDiff: Set<Coord> = emptySet()
    private var highlightedCodeword: Set<Coord> = emptySet()
    private var selected: Coord? = null
    private var confidence: Map<Coord, CellConfidence> = emptyMap()
    private var heatmapEnabled = false
    private var compareEnabled = true
    private var beforeFraction = 0.5f
    private var dataMask: Int? = null
    private var unmasked = false

    private val paired get() = compareEnabled && beforeFraction > 0f && beforeFraction < 1f
    private val paneWidth get() = if (paired) width / 2f else width.toFloat()
    private val headerHeight get() = if (paired) 24f * resources.displayMetrics.density else 0f
    private var gesturePane = 0

    private var scale = 1f
    private var fitScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    var onCellSelected: ((Int, Int) -> Unit)? = null
    private var pendingClickCell: Coord? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 11f }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            val next = (scale * detector.scaleFactor).coerceIn(fitScale * 0.8f, fitScale * 35f)
            if (next == old) return true
            val focusX = detector.focusX - if (paired) gesturePane * paneWidth else 0f
            val lx = (focusX - offsetX) / old
            val ly = (detector.focusY - offsetY) / old
            scale = next
            offsetX = focusX - lx * next
            offsetY = detector.focusY - ly * next
            invalidate()
            return true
        }
    })

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            offsetX -= distanceX
            offsetY -= distanceY
            invalidate()
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val c = pointToCell(e.x, e.y) ?: return false
            pendingClickCell = c
            performClick()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetTransform()
            return true
        }
    })

    fun setDocument(
        before: QrMatrix,
        after: QrMatrix,
        traces: Map<Coord, ModuleRepairTrace>,
        keepViewport: Boolean = false,
        dataMask: Int? = null,
    ) {
        require(before.dimension == after.dimension)
        val dimensionChanged = this.after?.dimension != after.dimension
        require(dataMask == null || dataMask in 0..7)
        this.dataMask = dataMask
        this.before = before
        this.after = after
        this.traces = traces
        if (!keepViewport || dimensionChanged) {
            selected = null
            highlightedCodeword = emptySet()
            resetTransform()
        }
        invalidate()
    }

    fun clearDocument() {
        dataMask = null
        unmasked = false
        before = null
        after = null
        traces = emptyMap()
        candidateDiff = emptySet()
        highlightedCodeword = emptySet()
        selected = null
        confidence = emptyMap()
        heatmapEnabled = false
        invalidate()
    }

    fun setUnmasked(enabled: Boolean) { unmasked = enabled; invalidate() }

    fun setFilter(value: RepairFilter) { filter = value; invalidate() }
    fun setCandidateDiff(coords: Set<Coord>) { candidateDiff = coords; invalidate() }
    fun setHighlightedCodeword(coords: Collection<Coord>) { highlightedCodeword = coords.toSet(); invalidate() }
    fun clearHighlightedCodeword() { highlightedCodeword = emptySet(); invalidate() }
    fun setSelected(x: Int, y: Int) { selected = Coord(x, y); invalidate() }
    fun setComparisonEnabled(enabled: Boolean) {
        if (compareEnabled == enabled) return
        compareEnabled = enabled; resetTransform(); invalidate()
    }
    fun setBeforeFraction(fraction: Float) {
        val wasPaired = paired
        beforeFraction = fraction.coerceIn(0f, 1f)
        if (wasPaired != paired) resetTransform()
        invalidate()
    }
    fun setHeatmap(enabled: Boolean, values: Map<Coord, CellConfidence>) { heatmapEnabled = enabled; confidence = values; invalidate() }

    fun focusCell(x: Int, y: Int) {
        val m = after ?: return
        if (x !in 0 until m.dimension || y !in 0 until m.dimension) return
        val target = max(scale, 24f).coerceAtMost(fitScale * 35f)
        scale = target
        offsetX = paneWidth / 2f - (quiet + x + .5f) * scale
        offsetY = (height + headerHeight) / 2f - (quiet + y + .5f) * scale
        selected = Coord(x, y)
        invalidate()
    }

    fun visibleChangeCount(): Int = when (filter) {
        RepairFilter.CANDIDATE_DIFF -> candidateDiff.size
        else -> traces.values.count { it.matches(filter) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) resetTransform()
        else {
            offsetX += (w - oldw) / if (paired) 4f else 2f
            offsetY += (h - oldh) / 2f
        }
    }

    private fun resetTransform() {
        val m = after ?: return
        if (width <= 0 || height <= 0) return
        val logical = m.dimension + quiet * 2f
        fitScale = min(paneWidth / logical, (height - headerHeight) / logical).coerceAtLeast(0.05f)
        scale = fitScale
        offsetX = (paneWidth - logical * scale) / 2f
        offsetY = headerHeight + (height - headerHeight - logical * scale) / 2f
        invalidate()
    }

    private fun pointToCell(px: Float, py: Float): Coord? {
        val m = after ?: return null
        if (scale <= 0f) return null
        if (py < headerHeight || px < 0 || px >= width || py >= height) return null
        val localX = if (paired && px >= paneWidth) px - paneWidth else px
        val lx = (localX - offsetX) / scale - quiet
        val ly = (py - offsetY) / scale - quiet
        val x = floor(lx.toDouble()).toInt()
        val y = floor(ly.toDouble()).toInt()
        return if (x in 0 until m.dimension && y in 0 until m.dimension) Coord(x, y) else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(232, 232, 232))
        val a = after ?: return
        val b = before ?: a
        val logicalSize = a.dimension + quiet * 2f

        fun drawPane(matrix: QrMatrix, left: Float, showEvidence: Boolean) {
            canvas.save()
            canvas.clipRect(left, headerHeight, left + paneWidth, height.toFloat())
            canvas.translate(left + offsetX, offsetY)
            canvas.scale(scale, scale)
            fill.color = Color.WHITE
            canvas.drawRect(0f, 0f, logicalSize, logicalSize, fill)
            drawMatrix(canvas, matrix)
            if (showEvidence) {
                if (heatmapEnabled) drawHeatmap(canvas, a)
                drawOverlays(canvas, matrix)
            }
            canvas.restore()
        }
        if (paired) {
            drawPane(b, 0f, false)
            drawPane(a, paneWidth, true)
            stroke.color = Color.rgb(0, 110, 220)
            stroke.strokeWidth = resources.displayMetrics.density
            canvas.drawLine(paneWidth, 0f, paneWidth, height.toFloat(), stroke)
            text.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                13f,
                resources.displayMetrics,
            )
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(localizedText("修復前", "Before"), paneWidth / 2f, headerHeight * .72f, text)
            canvas.drawText(localizedText("修復後", "After"), paneWidth * 1.5f, headerHeight * .72f, text)
        } else {
            val showingBefore = compareEnabled && beforeFraction == 1f
            drawPane(if (showingBefore) b else a, 0f, !showingBefore)
        }
    }

    private fun drawMatrix(canvas: Canvas, matrix: QrMatrix) {
        val version = (matrix.dimension - 17) / 4
        val mask = dataMask
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val stored = matrix[x, y].value
            val invert = unmasked && mask != null &&
                !QrFunctionMask.isFunction(version, x, y) && CodewordExtractor.maskApplies(mask, x, y)
            val displayed = if (invert) when (stored) {
                ModuleValue.ONE -> ModuleValue.ZERO
                ModuleValue.ZERO -> ModuleValue.ONE
                ModuleValue.ERASED -> ModuleValue.ERASED
            } else stored
            fill.color = when (displayed) {
                ModuleValue.ONE -> Color.BLACK
                ModuleValue.ZERO -> Color.WHITE
                ModuleValue.ERASED -> Color.rgb(185, 185, 185)
            }
            canvas.drawRect(quiet + x, quiet + y, quiet + x + 1f, quiet + y + 1f, fill)
            if (matrix[x, y].value == ModuleValue.ERASED && scale >= 7f) {
                stroke.style = Paint.Style.STROKE
                stroke.strokeWidth = max(1f / scale, 0.05f)
                stroke.color = Color.rgb(120, 120, 120)
                canvas.drawLine(quiet + x + .2f, quiet + y + .2f, quiet + x + .8f, quiet + y + .8f, stroke)
                canvas.drawLine(quiet + x + .8f, quiet + y + .2f, quiet + x + .2f, quiet + y + .8f, stroke)
            }
        }
    }

    private fun drawHeatmap(canvas: Canvas, matrix: QrMatrix) {
        fill.style = Paint.Style.FILL
        for ((coord, info) in confidence) {
            if (coord.x !in 0 until matrix.dimension || coord.y !in 0 until matrix.dimension) continue
            val s = info.score.coerceIn(0f, 1f)
            val r: Int
            val g: Int
            if (s < .5f) { r = 255; g = (s * 2f * 210f).toInt() }
            else { r = ((1f - s) * 2f * 255f).toInt(); g = 210 }
            fill.color = Color.argb(88, r, g, 20)
            canvas.drawRect(quiet + coord.x, quiet + coord.y, quiet + coord.x + 1f, quiet + coord.y + 1f, fill)
        }
    }

    private fun drawOverlays(canvas: Canvas, matrix: QrMatrix) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = max(1.4f / scale, 0.08f)

        val visible: Map<Coord, ModuleRepairTrace?> = if (filter == RepairFilter.CANDIDATE_DIFF) {
            candidateDiff.associateWith { null }
        } else {
            traces.filterValues { it.matches(filter) }
        }
        for ((coord, trace) in visible) {
            if (coord.x !in 0 until matrix.dimension || coord.y !in 0 until matrix.dimension) continue
            stroke.color = if (filter == RepairFilter.CANDIDATE_DIFF) Color.rgb(210, 20, 105) else colorFor(trace?.primaryReason)
            canvas.drawRect(
                quiet + coord.x + .08f,
                quiet + coord.y + .08f,
                quiet + coord.x + .92f,
                quiet + coord.y + .92f,
                stroke,
            )
        }

        if (candidateDiff.isNotEmpty() && filter != RepairFilter.CANDIDATE_DIFF) {
            stroke.strokeWidth = max(1f / scale, 0.055f)
            stroke.color = Color.rgb(210, 20, 105)
            for (coord in candidateDiff) {
                canvas.drawCircle(quiet + coord.x + .5f, quiet + coord.y + .5f, .18f, stroke)
            }
        }

        if (highlightedCodeword.isNotEmpty()) {
            stroke.strokeWidth = max(3.2f / scale, 0.17f)
            for (coord in highlightedCodeword) {
                // Codeword highlighting changes thickness only; provenance owns the color.
                stroke.color = colorFor(traces[coord]?.primaryReason)
                canvas.drawRect(quiet + coord.x + .12f, quiet + coord.y + .12f, quiet + coord.x + .88f, quiet + coord.y + .88f, stroke)
            }
        }

        selected?.let { c ->
            if (c.x in 0 until matrix.dimension && c.y in 0 until matrix.dimension) {
                stroke.strokeWidth = max(2.5f / scale, 0.14f)
                stroke.color = Color.CYAN
                canvas.drawRect(quiet + c.x + .04f, quiet + c.y + .04f, quiet + c.x + .96f, quiet + c.y + .96f, stroke)
            }
        }
    }

    private fun colorFor(reason: RepairReasonKind?): Int = when (reason) {
        RepairReasonKind.FORMAT_INFORMATION -> Color.rgb(190, 135, 0)
        RepairReasonKind.USER_OVERRIDE -> Color.rgb(0, 145, 200)
        RepairReasonKind.RS_ERASURE_RECOVERY, RepairReasonKind.RS_ERROR_CORRECTION -> Color.rgb(230, 115, 0)
        RepairReasonKind.PAYLOAD_MODE, RepairReasonKind.PAYLOAD_COUNT, RepairReasonKind.PAYLOAD_DATA_VALUE,
        RepairReasonKind.PAYLOAD_TERMINATOR, RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT,
        RepairReasonKind.PAYLOAD_PADDING, RepairReasonKind.PAYLOAD_STRUCTURE -> Color.rgb(139, 92, 246)
        RepairReasonKind.PARTIAL_BIT_SEARCH -> Color.rgb(210, 20, 105)
        RepairReasonKind.FINDER_PATTERN, RepairReasonKind.SEPARATOR,
        RepairReasonKind.TIMING_PATTERN, RepairReasonKind.ALIGNMENT_PATTERN,
        RepairReasonKind.VERSION_INFORMATION,
        RepairReasonKind.FIXED_DARK_MODULE, RepairReasonKind.REMAINDER_BITS -> Color.rgb(0, 155, 80)
        else -> Color.rgb(80, 80, 80)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gesturePane = if (paired && event.x >= paneWidth) 1 else 0
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        val a = scaleDetector.onTouchEvent(event)
        val b = gesture.onTouchEvent(event)
        return a || b || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val cell = pendingClickCell ?: selected ?: return false
        pendingClickCell = null
        selected = cell
        onCellSelected?.invoke(cell.x, cell.y)
        invalidate()
        return true
    }
}
