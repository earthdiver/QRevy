package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingDiagnosticBitmapTest {
    @Test
    fun releaseRecyclesOwnedTemporaryBitmap() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        PendingDiagnosticBitmap(bitmap, owned = true).release()

        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun releasePreservesBorrowedSourceBitmap() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        PendingDiagnosticBitmap(bitmap, owned = false).release()

        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }
}
