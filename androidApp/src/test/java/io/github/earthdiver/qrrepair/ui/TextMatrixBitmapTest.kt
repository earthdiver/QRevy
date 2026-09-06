package io.github.earthdiver.qrrepair.ui

import android.graphics.Color
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextMatrixBitmapTest {
    @Test fun rendersKnownAndUnknownCellsAtExactModuleCenters() {
        val matrix = QrMatrix(21)
        matrix.set(0, 0, ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        matrix.set(1, 0, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        matrix.set(2, 0, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)

        val bitmap = BitmapUtils.renderTextMatrixSource(matrix, moduleSize = 4, quietModules = 1)
        try {
            assertEquals(92, bitmap.width)
            assertEquals(Color.BLACK, bitmap.getPixel(5, 5))
            assertEquals(Color.WHITE, bitmap.getPixel(9, 5))
            assertEquals(Color.rgb(128, 128, 128), bitmap.getPixel(13, 5))
            assertEquals(Color.WHITE, bitmap.getPixel(1, 1))
        } finally {
            bitmap.recycle()
        }
    }
}
