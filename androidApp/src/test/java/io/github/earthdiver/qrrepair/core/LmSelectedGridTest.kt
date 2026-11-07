package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class LmSelectedGridTest {
    @Test fun fineTuningOffUsesOnlyTheConfirmedLmGrid() {
        val original = ImageQuad(PointD(10.0, 10.0), PointD(90.0, 10.0), PointD(90.0, 90.0), PointD(10.0, 90.0))
        val selected = GridGeometry(1, FinderRole.TOP_LEFT, 11.0, 11.0, 4.0, 0.0,
            original.copy(topLeft = PointD(11.0, 11.0)), GeometryModel.PROJECTIVE).reanchored()
        val settings = PreprocessSettings(version = 1, manualQuad = original, lmGeometry = selected,
            listDecodingAdditionalErrors = 0)
        val image = LumaImage(100, 100, IntArray(10000) { 255 })
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false),
            listDecodingBudget = ListDecodingBudget(0))
        assertEquals(listOf(selected), result.testedHypotheses.map { it.geometry })
        assertEquals(1, result.attemptedGeometries)
        val nearby = AutoRecoveryEngine().run(image,
            profile = SamplingProfile(settings.copy(searchQuadNeighborhood = true), false),
            maxGeometryAttempts = 1, listDecodingBudget = ListDecodingBudget(0))
        assertTrue(nearby.testedHypotheses.all { it.geometry.moduleCenterQuad() == selected.moduleCenterQuad() })
    }
}
