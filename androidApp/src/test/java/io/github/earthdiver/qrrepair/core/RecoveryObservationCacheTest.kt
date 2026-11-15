package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryObservationCacheTest {
    private val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 12.0, 12.0, 3.0, 0.0)
    private fun image(value: Int = 100) = LumaImage(100, 100, IntArray(10000) { value })
    private val settings = PreprocessSettings(blackMax = 110, whiteMin = 160, photographicSource = true)
    private var reads = 0
    private fun cache(limit: Long = 2L * 1024 * 1024) = RecoveryObservationCache(limit) { image, geometry, inverted, stopped ->
        reads++
        GridThresholdEstimator.observe(image, geometry, inverted, stopped)
    }
    private fun assertMatrix(expected: QrMatrix, actual: QrMatrix) {
        for (y in 0 until expected.dimension) for (x in 0 until expected.dimension) {
            assertEquals(expected[x, y], actual[x, y])
            assertEquals(expected.imageMargin(x, y), actual.imageMargin(x, y))
        }
    }

    @Test fun equivalentImagesAndDecoderChangesReuseObservationsButReturnIndependentMatrices() {
        val cache = cache()
        val source = image()
        val first = cache.sampler(source) {}(geometry, SamplingProfile(settings, false)).first
        first.set(10, 10, ModuleValue.ZERO, 1f, ModuleSource.USER)
        val changed = settings.copy(assumeUtf8ByteSegments = true, trustKnownPayloadCount = true,
            dataEccMismatchLimit = 0, listDecodingAdditionalErrors = 2)
        val second = cache.sampler(image()) {}(geometry, SamplingProfile(changed, false)).first
        assertEquals(1, reads)
        assertMatrix(MatrixSampler(source, SamplingProfile(changed, false)).sample(geometry).first, second)
    }

    @Test fun changedThresholdsColorsAndPhotographicMarginsAreAppliedToCachedObservations() {
        val cache = cache()
        val source = image()
        val sample = cache.sampler(source) {}
        val variants = listOf(settings, settings.copy(blackMax = 90),
            settings.copy(automaticThresholds = true), settings.copy(photographicSource = false),
            settings.copy(useUnknownColorClusters = true,
                unknownColorClusters = listOf(UnknownColorCluster(0, 0, 0, 100))))
        for (setting in variants) {
            val colored = LumaImage(100, 100, source.pixels, IntArray(10000))
            val profile = SamplingProfile(setting, false)
            val actual = cache.sampler(colored) {}(geometry, profile)
            val expected = MatrixSampler(colored, profile).sample(geometry)
            assertMatrix(expected.first, actual.first)
            assertEquals(expected.second, actual.second)
        }
        assertEquals(1, reads)
        assertEquals(ModuleValue.ONE, sample(geometry, SamplingProfile(settings, false)).first[10, 10].value)
    }

    @Test fun changedPixelsGeometryAndPolarityCannotReuseStaleObservations() {
        val cache = cache()
        val source = image()
        cache.sampler(source) {}(geometry, SamplingProfile(settings, false))
        source.pixels.fill(200)
        val sampler = cache.sampler(source) {}
        val moved = geometry.copy(finderCenterX = geometry.finderCenterX + 1)
        for ((grid, inverted) in listOf(geometry to false, moved to false, moved to true)) {
            val profile = SamplingProfile(settings, inverted)
            assertMatrix(MatrixSampler(source, profile).sample(grid).first, sampler(grid, profile).first)
        }
        assertEquals(4, reads)
    }

    @Test fun boundedCacheEvictsOldGridsAndDoesNotRetainCancelledReads() {
        val cache = cache(22_000)
        val sampler = cache.sampler(image()) {}
        val profile = SamplingProfile(settings, false)
        sampler(geometry, profile)
        sampler(geometry.copy(finderCenterX = 13.0), profile)
        sampler(geometry, profile)
        assertEquals(3, reads)
        var interrupted = false
        var attempts = 0
        val cancellable = RecoveryObservationCache { input, grid, inverted, stopped ->
            attempts++
            if (attempts == 1) interrupted = true
            GridThresholdEstimator.observe(input, grid, inverted, stopped)
        }
        val input = image()
        val run = cancellable.sampler(input) { if (interrupted) throw RecoveryCancelledException() }
        assertThrows(RecoveryCancelledException::class.java) { run(geometry, profile) }
        interrupted = false
        run(geometry, profile)
        run(geometry, profile)
        assertEquals(2, attempts)
    }
}
