package io.github.earthdiver.qrrepair.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Color
import android.graphics.Paint

/** A square identifies manual provenance independently of value, priority and selection. */
internal class ManualCellMarker {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(canvas: Canvas, x: Float, y: Float, halfSize: Float, strokeWidth: Float, tentative: Boolean = false) {
        // Two dash/gap pairs per side keep small markers legible and enlarge with zoom.
        paint.pathEffect = if (tentative) DashPathEffect(floatArrayOf(halfSize * .6f, halfSize * .4f), 0f) else null
        // A dark halo keeps the cyan border readable on white and coloured source images.
        paint.color = Color.BLACK
        paint.strokeWidth = strokeWidth * 2.4f
        canvas.drawRect(x - halfSize, y - halfSize, x + halfSize, y + halfSize, paint)
        paint.color = Color.CYAN
        paint.strokeWidth = strokeWidth
        canvas.drawRect(x - halfSize, y - halfSize, x + halfSize, y + halfSize, paint)
    }
}
