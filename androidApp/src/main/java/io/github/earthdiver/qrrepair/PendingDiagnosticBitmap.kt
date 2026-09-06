package io.github.earthdiver.qrrepair

import android.graphics.Bitmap

internal data class PendingDiagnosticBitmap(val bitmap: Bitmap, val owned: Boolean) {
    fun release() {
        if (owned && !bitmap.isRecycled) bitmap.recycle()
    }
}
