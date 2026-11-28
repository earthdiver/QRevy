package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ZxingJavaGridDetectorTest {
    @Test fun fallbackChecksCancellationWhileBuildingTheThresholdImage() {
        val image = LumaImage(80, 80, IntArray(6400) { 255 })
        var checks = 0
        org.junit.Assert.assertThrows(RecoveryCancelledException::class.java) {
            ZxingJavaGridDetector.detect(image, RecoveryCancellation { ++checks >= 10 })
        }
        assertEquals(10, checks)
    }

    @Test fun boundaryValidationHonorsAlreadyStoppedWork() {
        val image = LumaImage(80, 80, IntArray(6400) { 255 })
        val quad = ImageQuad(PointD(0.0,0.0),PointD(79.0,0.0),PointD(79.0,79.0),PointD(0.0,79.0))
        org.junit.Assert.assertThrows(RecoveryCancelledException::class.java) {
            InitialGridEstimator.fromBoundary(image, quad, RecoveryCancellation { true })
        }
    }

    @Test fun damagedAlignmentPreservesVersionAndCorners() {
        for (payload in listOf("BOUNDARY 42", "FINDER GEOMETRY", "ALIGNMENT OCCLUSION")) {
            val qr = Encoder.encode(payload, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 5)).matrix
            val n = qr.width
            val pitch = 8
            val size = (n + 8) * pitch
            for (inverted in listOf(false, true)) {
                val image = LumaImage(size, size, IntArray(size * size) { i ->
                    val x = i % size / pitch - 4
                    val y = i / size / pitch - 4
                    val black = x in 0 until n && y in 0 until n &&
                        ((x >= n - 12 && y >= n - 12) || qr.get(x, y).toInt() == 1)
                    if (black != inverted) 0 else 255
                })
                val estimate = requireNotNull(InitialGridEstimator.estimate(image, ZxingJavaGridDetector.detect(image))) { payload }
                assertEquals(5, estimate.version)
                assertEquals(3, estimate.autoAssignedFinders.size)
                val expected = listOf(PointD(35.5, 35.5), PointD(323.5, 35.5), PointD(323.5, 323.5), PointD(35.5, 323.5))
                for ((actual, target) in estimate.moduleCenterQuad.asList().zip(expected)) assertTrue(actual.distanceTo(target) < 1.0)
            }
        }
    }
}
