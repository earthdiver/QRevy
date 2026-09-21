package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class GridThresholdEstimatorTest {
    private val code = Encoder.encode("Global thresholds", ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.QR_VERSION to 2)).matrix!!
    private val n = code.width
    private val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 32.0, 32.0, 8.0, 0.0)
    private val automatic = SamplingProfile(PreprocessSettings(version = 2, automaticThresholds = true), false)

    private fun image(tone: (x: Int, y: Int, black: Boolean) -> Int): LumaImage {
        val size = (n + 1) * 8
        return LumaImage(size, size, IntArray(size * size) { i ->
            val x = ((i % size - 4) / 8).coerceIn(0, n - 1)
            val y = ((i / size - 4) / 8).coerceIn(0, n - 1)
            tone(x, y, code[x, y].toInt() == 1)
        })
    }

    private fun assertTruth(matrix: QrMatrix) {
        for (y in 0 until n) for (x in 0 until n) assertEquals("$x,$y", code[x, y].toInt(), matrix[x, y].value.bitOrNull())
    }

    @Test fun finderRefinementUsesGridThresholdsWhenTheImageWidePairDoesNotSeparateTheFinder() {
        val source = image { _, _, black -> if (black) 111 else 123 }
        val inverse = LumaImage(source.width, source.height, source.pixels.map { 255 - it }.toIntArray())
        val settings = automatic.settings.copy(manualQuad = geometry.moduleCenterQuad(),
            provisionalBlackMax = 40, provisionalWhiteMin = 80, quietZoneCorrection = false)
        val hint = geometry.moduleToImage(3.0, 3.0)
        val normal = requireNotNull(refineFinder(source, settings, hint))
        val inverted = requireNotNull(refineFinder(inverse,
            settings.copy(provisionalBlackMax = 175, provisionalWhiteMin = 215), hint))
        assertEquals(FinderPolarity.NORMAL, normal.polarity)
        assertEquals(FinderPolarity.INVERTED, inverted.polarity)
        assertEquals(normal.centerX, inverted.centerX, .001)
        assertEquals(normal.centerY, inverted.centerY, .001)
    }

    @Test fun invertedFallbackPreservesGrayErasuresAndDataBitsWithSourceThresholds() {
        val source = image { x, y, black ->
            if (QrFunctionMask.isFunction(2, x, y)) 60 else if (black) 20 else 100
        }
        val inverse = LumaImage(source.width, source.height, source.pixels.map { 255 - it }.toIntArray())
        val normal = automatic.copy(settings = automatic.settings.copy(provisionalBlackMax = 40, provisionalWhiteMin = 80))
        val inverted = SamplingProfile(normal.settings.copy(provisionalBlackMax = 175, provisionalWhiteMin = 215), true)
        val a = MatrixSampler(source, normal).sample(geometry)
        val b = MatrixSampler(inverse, inverted).sample(geometry)
        assertEquals(ThresholdPair(40, 80), a.second.thresholds)
        assertEquals(a.second, b.second)
        for (y in 0 until n) for (x in 0 until n) {
            assertEquals(a.first[x, y], b.first[x, y])
            if (QrFunctionMask.isFunction(2, x, y)) assertEquals(ModuleValue.ERASED, b.first[x, y].value)
            else assertEquals(code[x, y].toInt(), b.first[x, y].value.bitOrNull())
        }
    }

    @Test fun narrowButStableBlackWhiteSeparationRemainsReadable() {
        val source = image { _, _, black -> if (black) 111 else 123 }
        val (matrix, stats) = MatrixSampler(source, automatic).sample(geometry)
        assertTruth(matrix)
        assertEquals(stats.thresholds!!.blackMax + 1, stats.thresholds.whiteMin)
        assertTrue(stats.thresholds.blackMax in 112..121)
    }

    @Test fun smoothSeparatedIlluminationGradientUsesOneSharedBoundary() {
        val source = image { x, _, black -> (if (black) 18 else 139) + x * 4 }
        assertTruth(MatrixSampler(source, automatic).sample(geometry).first)
    }

    @Test fun overlappingIlluminationRangesAbstainInsteadOfForcingKnownBits() {
        val source = image { x, _, black -> (if (black) 20 else 100) + x * 5 }
        val (matrix, stats) = MatrixSampler(source, automatic).sample(geometry)
        assertTrue(stats.thresholds!!.whiteMin - stats.thresholds.blackMax > 20)
        var unknown = 0
        for (y in 0 until n) for (x in 0 until n) {
            val bit = matrix[x, y].value.bitOrNull()
            if (bit == null) unknown++ else assertEquals(code[x, y].toInt(), bit)
        }
        assertTrue(unknown > 0 && unknown < n * n)
    }

    @Test fun grayCoverIsUnknownWithoutUsingPayloadCellsAsTrainingLabels() {
        val source = image { x, y, black -> if (x in 9..15 && y in 9..15) 128 else if (black) 20 else 230 }
        val matrix = MatrixSampler(source, automatic).sample(geometry).first
        for (y in 0 until n) for (x in 0 until n) {
            if (x in 9..15 && y in 9..15) assertEquals("$x,$y", ModuleValue.ERASED, matrix[x, y].value)
            else assertEquals("$x,$y", code[x, y].toInt(), matrix[x, y].value.bitOrNull())
        }
    }

    @Test fun shadowedWhiteWithSupportInSeparateRegionsStaysWhite() {
        val source = image { x, _, black -> if (black) 20 else if (x < n / 2) 112 else 230 }
        assertTruth(MatrixSampler(source, automatic).sample(geometry).first)
    }

    @Test fun unsupportedGridUsesOriginalProvisionalPairRegardlessOfPreviousEffectivePair() {
        val source = LumaImage(220, 220, IntArray(220 * 220) { 176 })
        val settings = automatic.settings.copy(blackMax = 30, whiteMin = 90, provisionalBlackMax = 80, provisionalWhiteMin = 220)
        val first = MatrixSampler(source, SamplingProfile(settings, false)).sample(geometry)
        val second = MatrixSampler(source, SamplingProfile(settings.copy(blackMax = 190, whiteMin = 240), false)).sample(geometry)
        assertEquals(ThresholdPair(80, 220), first.second.thresholds)
        assertEquals(first.second, second.second)
        for (y in 0 until n) for (x in 0 until n) assertEquals(ModuleValue.ERASED, first.first[x, y].value)
    }

    @Test fun sparseVisibleFunctionCellsUseTheProvisionalThreeToneThresholds() {
        fun covered(x: Int, y: Int) = QrFunctionMask.isFunction(2, x, y) && Coord(x, y) !in setOf(Coord(0, 0), Coord(1, 1))
        val source = image { x, y, black -> if (covered(x, y)) 176 else if (black) 0 else 255 }
        val profile = automatic.copy(settings = automatic.settings.copy(provisionalBlackMax = 80, provisionalWhiteMin = 220))
        val (matrix, stats) = MatrixSampler(source, profile).sample(geometry)
        assertEquals(ThresholdPair(80, 220), stats.thresholds)
        for (y in 0 until n) for (x in 0 until n) {
            if (covered(x, y)) assertEquals(ModuleValue.ERASED, matrix[x, y].value)
            else assertEquals(code[x, y].toInt(), matrix[x, y].value.bitOrNull())
        }
    }

    @Test fun manualPairIsKeptEvenWhenAutomaticEstimateWouldDiffer() {
        val source = image { _, _, black -> if (black) 111 else 123 }
        val manual = automatic.copy(settings = automatic.settings.copy(automaticThresholds = false, blackMax = 115, whiteMin = 119))
        assertTruth(MatrixSampler(source, manual).sample(geometry).first)
        assertEquals(ThresholdPair(115, 119), MatrixSampler(source, manual).sample(geometry).second.thresholds)
    }

    @Test fun subModuleBoundaryCrossingsAreUnknownEvenWithAdjacentManualThresholds() {
        val source = LumaImage(220, 220, IntArray(220 * 220) { if (it % 220 < 88) 0 else 255 })
        val manual = automatic.copy(settings = automatic.settings.copy(automaticThresholds = false, blackMax = 127, whiteMin = 128))
        val matrix = MatrixSampler(source, manual).sample(geometry).first
        assertEquals(ModuleValue.ONE, matrix[9, 10].value)
        assertEquals(ModuleValue.ERASED, matrix[10, 10].value)
        assertEquals(ModuleValue.ZERO, matrix[11, 10].value)
    }

    @Test fun invertedImageAndProfileProduceTheSameGridAndThresholds() {
        val source = image { x, _, black -> (if (black) 18 else 139) + x * 4 }
        val inverse = LumaImage(source.width, source.height, source.pixels.map { 255 - it }.toIntArray())
        val first = MatrixSampler(source, automatic).sample(geometry)
        val second = MatrixSampler(inverse, automatic.copy(inverted = true)).sample(geometry)
        assertEquals(first.second.thresholds, second.second.thresholds)
        assertTruth(second.first)
    }

    @Test fun manualAndAutomaticSamplingDoNotAddRecoveryAttemptsAndPreserveEdits() {
        val source = image { _, _, black -> if (black) 20 else 230 }
        val fixed = Coord(11, 11); val soft = Coord(12, 11)
        for (auto in listOf(false, true)) {
            val settings = automatic.settings.copy(automaticThresholds = auto, manualQuad = geometry.moduleCenterQuad(),
                polarity = PolarityMode.NORMAL, preRecoveryEdits = mapOf(fixed to ModuleValue.ONE, soft to ModuleValue.ZERO),
                preRecoverySoftEdits = setOf(soft))
            val profile = SamplingProfile(settings, false)
            val result = AutoRecoveryEngine().run(source, maxGeometryAttempts = 1, profile = profile)
            assertEquals(1, result.attempts.size)
            val attempt = result.attempts.single()
            assertEquals(ModuleValue.ONE, attempt.matrix[fixed.x, fixed.y].value)
            assertEquals(ModuleSource.USER, attempt.matrix[fixed.x, fixed.y].source)
            assertEquals(ModuleValue.ZERO, attempt.matrix[soft.x, soft.y].value)
            assertEquals(ModuleSource.USER_SOFT, attempt.matrix[soft.x, soft.y].source)
            val preview = MatrixSampler(source, profile).sample(attempt.hypothesis.geometry)
            assertEquals(preview.second, attempt.samplingStats)
            for (y in 0 until n) for (x in 0 until n) if (Coord(x, y) !in settings.preRecoveryEdits)
                assertEquals(preview.first[x, y].value, attempt.matrix[x, y].value)
        }
    }
}
