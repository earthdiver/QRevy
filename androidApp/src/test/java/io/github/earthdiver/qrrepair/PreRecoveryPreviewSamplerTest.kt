package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test

class PreRecoveryPreviewSamplerTest {
    private val image = LumaImage(120, 120, IntArray(120 * 120) { if (it % 120 < 60) 40 else 220 })
    private val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 25.0, 25.0, 4.0, 0.0)
    private val profile = SamplingProfile(PreprocessSettings(automaticThresholds = false, blackMax = 80, whiteMin = 180), false)

    @Test fun manualEditsAndPriorityMarksNeverContaminateTheNextPreview() {
        val sampler = PreRecoveryPreviewSampler()
        val original = sampler.sample(image, geometry, profile, true).first
        val edited = sampler.sample(image, geometry, profile, true).first
        val fixed = Coord(5, 5)
        val tentative = Coord(6, 5)
        val unknown = Coord(7, 5)
        val edits = PreRecoveryCellEditState(
            mapOf(fixed to ModuleValue.ONE, tentative to ModuleValue.ZERO, unknown to ModuleValue.ERASED),
            setOf(unknown), initialSoftEdits = setOf(tentative),
        )
        edits.applyTo(edited, allowFixedCorrections = false)
        assertEquals(ModuleSource.USER, edited[fixed.x, fixed.y].source)
        assertEquals(ModuleSource.USER_SOFT, edited[tentative.x, tentative.y].source)
        assertEquals(ModuleValue.ERASED, edited[unknown.x, unknown.y].value)
        assertTrue(edited[unknown.x, unknown.y].prioritySearch)
        val fresh = sampler.sample(image, geometry, profile, true).first
        assertNotSame(original, fresh)
        assertMatrixEquals(original, fresh)
        edits.applyTo(fresh, allowFixedCorrections = true)
        assertEquals(ModuleSource.USER_UNLOCKED, fresh[fixed.x, fixed.y].source)
        assertEquals(ModuleSource.USER_SOFT, fresh[tentative.x, tentative.y].source)
        assertTrue(fresh[unknown.x, unknown.y].prioritySearch)
    }

    @Test fun changingImageGeometryPolarityOrThresholdsMatchesAnUncachedSample() {
        val sampler = PreRecoveryPreviewSampler()
        val otherImage = LumaImage(120, 120, IntArray(120 * 120) { 120 })
        val cases = listOf(
            Triple(image, geometry, profile),
            Triple(image, geometry, profile.copy(inverted = true)),
            Triple(image, geometry.copy(finderCenterX = 45.0), profile),
            Triple(otherImage, geometry, profile),
            Triple(image, geometry, profile.copy(settings = profile.settings.copy(blackMax = 20, whiteMin = 240))),
            Triple(image, geometry, profile),
        )
        for ((input, grid, sampling) in cases) {
            val expected = MatrixSampler(input, sampling).sample(grid)
            val actual = sampler.sample(input, grid, sampling, true)
            assertMatrixEquals(expected.first, actual.first)
            assertEquals(expected.second, actual.second)
        }
    }

    @Test fun automaticPreviewUsesProvisionalThresholdsUntilLogicalGridIsAssigned() {
        val sampler = PreRecoveryPreviewSampler()
        val settings = profile.settings.copy(automaticThresholds = true, blackMax = 5, whiteMin = 250,
            provisionalBlackMax = 80, provisionalWhiteMin = 180)
        val sampling = SamplingProfile(settings, false)
        val actual = sampler.sample(image, geometry, sampling, false)
        val expected = MatrixSampler(image, profile).sample(geometry)
        assertMatrixEquals(expected.first, actual.first)
        val changedSliders = sampling.copy(settings = settings.copy(blackMax = 10, whiteMin = 245))
        assertMatrixEquals(actual.first, sampler.sample(image, geometry, changedSliders, false).first)
    }

    private fun assertMatrixEquals(expected: QrMatrix, actual: QrMatrix) {
        assertEquals(expected.dimension, actual.dimension)
        for (y in 0 until expected.dimension) for (x in 0 until expected.dimension) {
            assertEquals("Cell ($x, $y)", expected[x, y], actual[x, y])
        }
    }
}
