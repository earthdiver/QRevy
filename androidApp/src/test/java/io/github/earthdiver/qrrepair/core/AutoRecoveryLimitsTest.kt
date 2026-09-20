package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutoRecoveryLimitsTest {
    private val quad = ImageQuad(
        PointD(10.0, 10.0), PointD(210.0, 10.0),
        PointD(210.0, 210.0), PointD(10.0, 210.0),
    )
    private val image = LumaImage(220, 220, IntArray(220 * 220) { 255 })

    @Test fun geometryAttemptLimitIsHonored() {
        val settings = PreprocessSettings(
            manualQuad = quad,
            searchQuadNeighborhood = true,
        )

        val result = AutoRecoveryEngine().run(
            image,
            maxGeometryAttempts = 0,
            profile = SamplingProfile(settings, inverted = false),
        )

        assertEquals(0, result.attempts.size)
    }

    @Test fun finderLimitIsHonored() {
        val settings = PreprocessSettings(
            manualQuad = quad,
            manualFinders = mapOf(
                FinderRole.TOP_LEFT to PointD(20.0, 20.0),
                FinderRole.TOP_RIGHT to PointD(200.0, 20.0),
            ),
        )

        val result = AutoRecoveryEngine().run(
            image,
            maxFinders = 1,
            maxGeometryAttempts = 0,
            profile = SamplingProfile(settings, inverted = false),
        )

        assertEquals(1, result.finders.size)
    }

    @Test fun skippedGeometryCannotSupportAWholeSearchFailureClaim() {
        val settings = PreprocessSettings(manualQuad = quad, searchQuadNeighborhood = true,
            formatConstraint = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, 0),
            dataEccMismatchLimit = 0)
        val result = AutoRecoveryEngine().run(image, maxGeometryAttempts = 1,
            profile = SamplingProfile(settings, inverted = false))
        assertEquals(1, result.attempts.size)
        assertTrue(result.searchDiagnostic!!.unsearched)
        assertFalse(result.searchDiagnostic.coverageComplete)
        assertEquals("", result.searchDiagnostic.failureExplanation(result.bestFailure?.displayConditions))
    }

    @Test fun degenerateManualQuadProducesNoAttemptInsteadOfEscapingAsAnException() {
        val point = PointD(40.0, 40.0)
        val settings = PreprocessSettings(
            manualQuad = ImageQuad(point, point, point, point),
        )

        val result = AutoRecoveryEngine().run(
            image,
            profile = SamplingProfile(settings, inverted = false),
        )

        assertEquals(0, result.attempts.size)
    }
}
