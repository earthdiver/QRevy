package io.github.earthdiver.qrrepair.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.earthdiver.qrrepair.PrecisionStyle
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import kotlin.math.min

/** Read-only preview. The activity owns source bitmaps; drawing never modifies recovery data. */
internal class RepairPreviewView(context: Context, private val square: Boolean = false) : View(context) {
    private val paint = Paint()
    private val imageBounds = RectF()
    private var matrix: QrMatrix? = null
    private var source: Bitmap? = null

    fun setDocument(matrix: QrMatrix?, source: Bitmap?) {
        this.matrix = matrix
        this.source = source
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (square) {
            val side = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(side, side)
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat() - 32 * resources.displayMetrics.density
        if (side <= 0) return
        val left = (width - side) / 2
        val top = (height - side) / 2
        val qr = matrix
        val bitmap = source?.takeUnless { it.isRecycled }
        if (qr != null) {
            paint.color = Color.WHITE
            canvas.drawRect(left, top, left + side, top + side, paint)
            val cell = side / (qr.dimension + 8)
            for (y in 0 until qr.dimension) for (x in 0 until qr.dimension) {
                paint.color = when (qr[x, y].value) {
                    ModuleValue.ZERO -> Color.WHITE
                    ModuleValue.ONE -> Color.BLACK
                    ModuleValue.ERASED -> Color.GRAY
                }
                canvas.drawRect(left + (x + 4) * cell, top + (y + 4) * cell,
                    left + (x + 5) * cell, top + (y + 5) * cell, paint)
            }
        } else if (bitmap != null) {
            val scale = side / maxOf(bitmap.width, bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            paint.color = Color.WHITE
            imageBounds.set((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
            canvas.drawBitmap(bitmap, null, imageBounds, paint)
        } else {
            // Abstract finder emblem, deliberately not a decodable QR or a fake user session.
            val unit = side / 9
            paint.color = PrecisionStyle.ACCENT
            canvas.drawRect(left + unit, top + unit, left + 8*unit, top + 8*unit, paint)
            paint.color = if (background == null) PrecisionStyle.BACKGROUND else PrecisionStyle.SURFACE
            canvas.drawRect(left + 2*unit, top + 2*unit, left + 7*unit, top + 7*unit, paint)
            paint.color = PrecisionStyle.ACCENT
            canvas.drawRect(left + 3*unit, top + 3*unit, left + 6*unit, top + 6*unit, paint)
            paint.color = if (background == null) PrecisionStyle.BACKGROUND else PrecisionStyle.SURFACE
            canvas.drawRect(left + 6*unit, top, left + 9*unit, top + 3*unit, paint)
            paint.color = PrecisionStyle.ACCENT
            canvas.drawRect(left + 7*unit, top + unit, left + 8*unit, top + 2*unit, paint)
            canvas.drawRect(left + 8*unit, top + 2*unit, left + 9*unit, top + 3*unit, paint)
        }
    }
}
