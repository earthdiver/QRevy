package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class LmSelectedGridTest {
    @Test fun noAlignmentKeepsTheConfirmedLmGridFirstAndAlsoTestsTheOriginal() {
        val original = ImageQuad(PointD(10.0, 10.0), PointD(90.0, 10.0), PointD(90.0, 90.0), PointD(10.0, 90.0))
        val selected = GridGeometry(1, FinderRole.TOP_LEFT, 11.0, 11.0, 4.0, 0.0,
            original.copy(topLeft = PointD(11.0, 11.0)), GeometryModel.PROJECTIVE).reanchored()
        val settings = PreprocessSettings(version = 1, manualQuad = original, lmGeometry = selected,
            listDecodingAdditionalErrors = 0)
        val image = LumaImage(100, 100, IntArray(10000) { 255 })
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false),
            listDecodingBudget = ListDecodingBudget(0))
        assertEquals(selected, result.testedHypotheses.first().geometry)
        assertEquals(original, result.testedHypotheses.last().geometry.moduleCenterQuad())
        assertEquals(2, result.attemptedGeometries)
        val nearby = AutoRecoveryEngine().run(image,
            profile = SamplingProfile(settings.copy(searchQuadNeighborhood = true), false),
            maxGeometryAttempts = 1, listDecodingBudget = ListDecodingBudget(0))
        assertTrue(nearby.testedHypotheses.all { it.geometry.moduleCenterQuad() == selected.moduleCenterQuad() })
    }

    private fun fixture(alignmentVisible: Boolean, finders: Int = 2): Pair<LumaImage, PreprocessSettings> {
        val qr = Encoder.encode("LM FALLBACK", ErrorCorrectionLevel.H,
            mapOf(EncodeHintType.QR_VERSION to 2, EncodeHintType.QR_MASK_PATTERN to 2)).matrix
        val pitch = 6
        val side = (qr.width + 8) * pitch
        val image = LumaImage(side, side, IntArray(side * side) { i ->
            val x = i % side / pitch - 4
            val y = i / side / pitch - 4
            when {
                x !in 0 until qr.width || y !in 0 until qr.height -> 255
                finders < 3 && x < 8 && y >= qr.height - 8 -> 128
                finders < 2 && x >= qr.width - 8 && y < 8 -> 128
                finders < 1 && x < 8 && y < 8 -> 128
                !alignmentVisible && x in 12..22 && y in 12..22 -> 128
                qr[x, y].toInt() == 1 -> 0
                else -> 255
            }
        })
        val quad = ImageQuad(PointD(26.5, 26.5), PointD(170.5, 26.5), PointD(170.5, 170.5), PointD(26.5, 170.5))
        val original = GridGeometry(2, FinderRole.TOP_LEFT, 26.5, 26.5, 6.0, 0.0, quad,
            GeometryModel.MANUAL_PROJECTIVE).reanchored()
        val moved = quad.asList().map { it + PointD(6.0, 6.0) }.let { ImageQuad(it[0], it[1], it[2], it[3]) }
        val lm = original.copy(imageQuad = moved).reanchored()
        val settings = PreprocessSettings(version = 2, manualQuad = quad, lmGeometry = lm,
            trustKnownPayloadCount = true, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.H, 2),
            dataEccMismatchLimit = 0, formatMismatchLimit = 6, listDecodingAdditionalErrors = 0)
        return image to settings
    }

    @Test fun missingAlignmentRecoversOnTheOriginalWithTheSelectedConditionsAndEdits() {
        val (image, saved) = fixture(false)
        val erased = Coord(10, 10)
        val fixed = Coord(11, 11)
        val original = saved.lmGeometry!!.copy(imageQuad = saved.manualQuad).reanchored()
        val observed = MatrixSampler(image, SamplingProfile(saved, false)).sample(original).first
        val settings = saved.copy(preRecoveryEdits = mapOf(erased to ModuleValue.ERASED, fixed to observed[fixed.x, fixed.y].value))
        assertTrue(needsUncorrectedGridVerification(image, SamplingProfile(settings, false), original))
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false))
        assertEquals(2, result.attemptedGeometries)
        assertTrue(result.attempts.single { it.hypothesis.geometry == settings.lmGeometry }.recoveries.isEmpty())
        val entry = result.entries.single { it.candidate.text == "LM FALLBACK" }
        assertEquals(settings.manualQuad, entry.attempt.hypothesis.geometry.moduleCenterQuad())
        assertEquals(ModuleValue.ERASED, entry.attempt.matrix[erased.x, erased.y].value)
        assertEquals(ModuleSource.USER, entry.attempt.matrix[fixed.x, fixed.y].source)
        assertEquals(observed[fixed.x, fixed.y].value, entry.attempt.matrix[fixed.x, fixed.y].value)
        assertTrue(entry.candidate.displayConditions!!.trustSingleSegment)
        assertEquals(ErrorCorrectionLevel.H, entry.candidate.ecLevel)
        assertEquals(2, entry.candidate.dataMask)
        assertSame(saved.lmGeometry, settings.lmGeometry)
    }

    @Test fun visibleAlignmentDoesNotAddAnOriginalGridTrial() {
        val (image, settings) = fixture(true)
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false))
        assertEquals(listOf(settings.lmGeometry), result.testedHypotheses.map { it.geometry })
    }

    @Test fun invalidOriginalCornersDoNotSuppressTheSelectedCorrection() {
        val (image, saved) = fixture(false)
        val point = PointD(10.0, 10.0)
        val settings = saved.copy(manualQuad = ImageQuad(point, point, point, point))
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false))
        assertEquals(listOf(settings.lmGeometry), result.testedHypotheses.map { it.geometry })
    }

    @Test fun threeFindersDoNotAddAnOriginalGridTrialEvenWithoutAlignment() {
        val (image, settings) = fixture(false, finders = 3)
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(settings, false))
        assertEquals(listOf(settings.lmGeometry), result.testedHypotheses.map { it.geometry })
    }

    @Test fun evidenceConditionCountsVisibleFindersAndRespectsPolarity() {
        for (count in 0..3) for (alignment in listOf(false, true)) {
            val (image, settings) = fixture(alignment, finders = count)
            val original = settings.lmGeometry!!.copy(imageQuad = settings.manualQuad).reanchored()
            val profile = SamplingProfile(settings, false)
            assertEquals(count, verifiedFinderCount(image, profile, original))
            assertEquals(count <= 2 && !alignment, needsUncorrectedGridVerification(image, profile, original))
            val inverse = LumaImage(image.width, image.height, IntArray(image.width * image.height) { i ->
                255 - image.luma(i % image.width, i / image.width)
            })
            assertEquals(count, verifiedFinderCount(inverse, SamplingProfile(settings, true), original))
            assertFalse(needsUncorrectedGridVerification(image, profile, original) { true })
        }
    }

    @Test fun alignmentSearchHonorsCancellation() {
        val (image, settings) = fixture(true)
        var calls = 0
        assertNull(AlignmentDetector(image, SamplingProfile(settings, false)).findBest(settings.lmGeometry!!,
            stopped = { ++calls >= 3 }))
        assertEquals(3, calls)
    }
}
