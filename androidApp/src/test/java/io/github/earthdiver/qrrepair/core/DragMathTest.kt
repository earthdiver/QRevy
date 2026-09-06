package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DragMathTest {
    @Test fun dragPreservesInitialFingerOffset() {
        val result = draggedHandlePoint(
            startHandle = PointD(100.0, 80.0),
            startTouch = PointD(106.0, 75.0),
            currentTouch = PointD(126.0, 95.0),
        )
        assertEquals(120.0, result.x, 0.0)
        assertEquals(100.0, result.y, 0.0)
    }
}
