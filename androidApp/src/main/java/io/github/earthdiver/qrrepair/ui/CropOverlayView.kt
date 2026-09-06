package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bitmap crop selector whose result is expressed in source-bitmap coordinates. */
class CropOverlayView(context: Context) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00C8FF.toInt() }
    private var bitmap: Bitmap? = null
    private val crop = RectF()
    private val imageRect = RectF()
    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f

    fun setBitmap(value: Bitmap) {
        bitmap = value
        crop.set(value.width * .08f, value.height * .08f, value.width * .92f, value.height * .92f)
        invalidate()
    }

    fun cropRect(): RectF = RectF(crop)

    override fun onDraw(canvas: Canvas) {
        val source = bitmap ?: return
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        imageRect.set((width - drawW) / 2f, (height - drawH) / 2f, (width + drawW) / 2f, (height + drawH) / 2f)
        canvas.drawBitmap(source, null, imageRect, imagePaint)
        val r = bitmapToView(crop)
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, r.top, shadePaint)
        canvas.drawRect(imageRect.left, r.bottom, imageRect.right, imageRect.bottom, shadePaint)
        canvas.drawRect(imageRect.left, r.top, r.left, r.bottom, shadePaint)
        canvas.drawRect(r.right, r.top, imageRect.right, r.bottom, shadePaint)
        canvas.drawRect(r, borderPaint)
        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(7f), handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = bitmap ?: return false
        if (imageRect.isEmpty) return false
        val (x, y) = viewToBitmap(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val tolerance = dp(28f) * source.width / imageRect.width()
                dragMode = (if (abs(x - crop.left) <= tolerance) LEFT else 0) or
                    (if (abs(x - crop.right) <= tolerance) RIGHT else 0) or
                    (if (abs(y - crop.top) <= tolerance) TOP else 0) or
                    (if (abs(y - crop.bottom) <= tolerance) BOTTOM else 0)
                if (dragMode == 0 && crop.contains(x, y)) dragMode = MOVE
                lastX = x; lastY = y
                touchDownX = event.x; touchDownY = event.y
                return dragMode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                val minSize = max(32f, min(source.width, source.height) * .05f)
                val dx = x - lastX; val dy = y - lastY
                if (dragMode == MOVE) {
                    crop.offset(dx.coerceIn(-crop.left, source.width - crop.right), dy.coerceIn(-crop.top, source.height - crop.bottom))
                } else {
                    if (dragMode and LEFT != 0) crop.left = x.coerceIn(0f, crop.right - minSize)
                    if (dragMode and RIGHT != 0) crop.right = x.coerceIn(crop.left + minSize, source.width.toFloat())
                    if (dragMode and TOP != 0) crop.top = y.coerceIn(0f, crop.bottom - minSize)
                    if (dragMode and BOTTOM != 0) crop.bottom = y.coerceIn(crop.top + minSize, source.height.toFloat())
                }
                lastX = x; lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragMode = 0
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.hypot(event.x - touchDownX, event.y - touchDownY) <= slop) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragMode = 0; return true }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun bitmapToView(rect: RectF): RectF {
        val source = bitmap ?: return RectF()
        val sx = imageRect.width() / source.width
        val sy = imageRect.height() / source.height
        return RectF(imageRect.left + rect.left * sx, imageRect.top + rect.top * sy,
            imageRect.left + rect.right * sx, imageRect.top + rect.bottom * sy)
    }

    private fun viewToBitmap(x: Float, y: Float): Pair<Float, Float> {
        val source = bitmap ?: return 0f to 0f
        return ((x - imageRect.left) * source.width / imageRect.width()).coerceIn(0f, source.width.toFloat()) to
            ((y - imageRect.top) * source.height / imageRect.height()).coerceIn(0f, source.height.toFloat())
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val LEFT = 1; const val TOP = 2; const val RIGHT = 4; const val BOTTOM = 8; const val MOVE = 16
    }
}
