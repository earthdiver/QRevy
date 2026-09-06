package io.github.earthdiver.qrrepair

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageOrientationTransformTest {
    @Test fun sharpCameraUpperRightOrientationRotatesPixelsClockwise() {
        assertEquals(ImageOrientationTransform(rotationDegrees = 90f), imageOrientationTransform(6))
    }

    @Test fun normalOrientationLeavesPixelsUnchanged() {
        assertEquals(ImageOrientationTransform(), imageOrientationTransform(1))
    }
}
