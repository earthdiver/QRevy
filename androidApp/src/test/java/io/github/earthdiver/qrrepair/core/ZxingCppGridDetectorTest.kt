package io.github.earthdiver.qrrepair.core

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZxingCppGridDetectorTest {
    @Test fun unavailableAndroidJniDoesNotPreventImport() {
        // Host JVM cannot load the Android .so. This exercises the real linkage fallback.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            repeat(2) { assertEquals(emptyList<ImageQuad>(), ZxingCppGridDetector.detect(bitmap)) }
        } finally {
            bitmap.recycle()
        }
    }
}
