package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

class CurvedGridRecoveryTest {
    private val text = "QRevy curved surface fixture 0123456789"
    private val encoded = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 4)).matrix
    private val n = encoded.width
    private val pitch = 10.0
    private val margin = 40.0
    private val side = ((n + 8) * pitch).toInt()
    private fun base() = GridGeometry(4, FinderRole.TOP_LEFT, 75.0, 75.0, pitch, 0.0,
        ImageQuad(PointD(45.0, 45.0), PointD(365.0, 45.0), PointD(365.0, 365.0), PointD(45.0, 365.0)),
        GeometryModel.MANUAL_PROJECTIVE).reanchored()
    private fun image(amplitude: Double = 1.4, inverted: Boolean = false): LumaImage = LumaImage(side, side, IntArray(side * side) { i ->
        val x = (i % side - margin) / pitch - .5
        val y = (i / side - margin) / pitch - .5 - amplitude * sin(PI * x / (n - 1))
        val mx = floor(x + .5).toInt(); val my = floor(y + .5).toInt()
        val black = mx in 0 until n && my in 0 until n && encoded[mx, my].toInt() == 1
        if (black xor inverted) 0 else 255
    })
    private fun settings(enabled: Boolean = true) = PreprocessSettings(version = 4, manualQuad = base().imageQuad,
        distortionCorrection = enabled, automaticThresholds = true, polarity = PolarityMode.NORMAL, blackMax = 80, whiteMin = 175,
        listDecodingAdditionalErrors = 0, partialBitSearchLimit = 12)
    private fun errors(image: LumaImage, g: GridGeometry, profile: SamplingProfile): Int {
        val matrix = MatrixSampler(image, profile).sample(g).first
        return (0 until n).sumOf { y -> (0 until n).count { x -> matrix[x, y].value.bitOrNull() != encoded[x, y].toInt() } }
    }

    @Test fun imageMeasuredCurvatureImprovesSamplingAndRecoversTheExactPayload() {
        for (inverted in listOf(false, true)) {
            val image = image(inverted = inverted); val profile = SamplingProfile(settings(), inverted)
            val candidates = CurvedGridDetector(image, profile).candidates(base())
            assertTrue("No curved candidates", candidates.isNotEmpty())
            val before = errors(image, base(), profile)
            assertFalse(RecoveryEngine().recover(MatrixSampler(image, profile).sample(base()).first,
                listDecodingBudget = ListDecodingBudget(0)).any { it.payloadParsed && it.text == text })
            val best = candidates.minBy { errors(image, it, profile) }
            val after = errors(image, best, profile)
            assertTrue("Before=$before after=$after", before > 50 && after < before / 2)
            val matrix = MatrixSampler(image, profile).sample(best).first
            assertTrue("Before=$before after=$after", RecoveryEngine().recover(matrix, listDecodingBudget = ListDecodingBudget(0))
                .any { it.payloadParsed && it.text == text })
            assertTrue(candidates.all { it.hasUsableWarp() })
        }
    }

    @Test fun repairUsesExactlyTheDisplayedMappingUnlessAdditionalPositionSearchIsEnabled() {
        val image = image()
        val initial = settings()
        val profile = SamplingProfile(initial, false)
        val selected = CurvedGridDetector(image, profile).candidates(base()).minBy { errors(image, it, profile) }
        val selectedSettings = initial.copy(previewGeometry = selected)
        val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(selectedSettings, false),
            listDecodingBudget = ListDecodingBudget(0))
        assertEquals(listOf(selected), result.testedHypotheses.map { it.geometry })
        assertTrue(result.rankedRecoveries.any { it.second.payloadParsed && it.second.text == text })
        val expected = MatrixSampler(image, profile).sample(selected).first
        val observed = result.attempts.single().matrix
        for (y in 0 until n) for (x in 0 until n) assertEquals(expected[x, y], observed[x, y])
        val expanded = AutoRecoveryEngine().run(image, maxGeometryAttempts = 1,
            profile = SamplingProfile(selectedSettings.copy(searchQuadNeighborhood = true), false),
            listDecodingBudget = ListDecodingBudget(0))
        assertEquals(selected, expanded.testedHypotheses.first().geometry)
        assertTrue(expanded.testedHypotheses.any { it.geometry.warp == null })
    }

    @Test fun contourLandmarksRecoverStrongCurvatureFromALooseCrop() {
        val loose = ImageQuad(PointD(6.0, 6.0), PointD(side - 7.0, 6.0),
            PointD(side - 7.0, side - 7.0), PointD(6.0, side - 7.0))
        for (variant in 0..3) {
            val original = image(amplitude = 2.8, inverted = variant == 1)
            fun transform(p: PointD): PointD = when (variant) {
                2 -> PointD(p.y, p.x)
                3 -> PointD(side - 1.0 - p.y, p.x)
                else -> p
            }
            val pixels = IntArray(side * side)
            for (y in 0 until side) for (x in 0 until side) {
                val p = transform(PointD(x.toDouble(), y.toDouble()))
                pixels[p.y.toInt() * side + p.x.toInt()] = original.pixels[y * side + x]
            }
            val input = LumaImage(side, side, pixels)
            val q = loose.asList().map(::transform).let { ImageQuad(it[0], it[1], it[2], it[3]) }
            // This stress fixture exercises geometry beyond the default strict mismatch allowance.
            // Use the supported relaxed preset, and separately verify that strict limits still apply.
            val selected = settings().copy(manualQuad = q, mirroredInput = variant == 2,
                formatMismatchLimitMode = ErrorLimitMode.AUTOMATIC, dataEccMismatchLimitMode = ErrorLimitMode.RELAXED)
            val profile = SamplingProfile(selected, variant == 1)
            val geometry = base().copy(imageQuad = q).reanchored()
            val measured = CurvedFinderContours(input, profile.inverted) { false }.seeds(geometry)
            assertTrue("No contours for variant $variant", measured.isNotEmpty())
            assertTrue(measured.all { it.points.size == 12 })
            val ordinary = AutoRecoveryEngine().run(input, profile = profile.copy(settings = selected.copy(distortionCorrection = false)),
                listDecodingBudget = ListDecodingBudget(0))
            assertFalse(ordinary.entries.any { it.candidate.payloadParsed && it.candidate.text == text })
            val result = AutoRecoveryEngine().run(input, profile = profile, listDecodingBudget = ListDecodingBudget(0))
            assertTrue("No recovery for variant $variant", result.entries.any { it.candidate.payloadParsed && it.candidate.text == text })
            assertTrue(result.testedHypotheses.all { it.geometry.version == 4 })
            assertTrue(result.testedHypotheses.any { it.geometry.warp != null && it.geometry.imageQuad != q })
            assertTrue(result.entries.all { it.candidate.knownBitMismatchesAfterCorrection <= selected.effectiveDataEccMismatchLimit!! })
            if (variant == 0) {
                val strict = selected.copy(dataEccMismatchLimitMode = ErrorLimitMode.STRICT)
                val limited = AutoRecoveryEngine().run(input, profile = profile.copy(settings = strict),
                    listDecodingBudget = ListDecodingBudget(0))
                assertTrue(limited.entries.all { it.candidate.knownBitMismatchesAfterCorrection <= strict.effectiveDataEccMismatchLimit!! })
            }
        }
    }

    @Test fun cancellationInterruptsContourExtraction() {
        var checks = 0
        val detector = CurvedGridDetector(image(), SamplingProfile(settings(), false),
            RecoveryCancellation { ++checks > 20 })
        assertThrows(RecoveryCancelledException::class.java) { detector.candidates(base()) }
        assertTrue(checks > 20)
    }

    @Test fun optionalCandidatesKeepOrdinaryGeometryVersionAndFixedUserEdits() {
        val edits = mapOf(Coord(12, 12) to if (encoded[12, 12].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, Coord(13, 12) to ModuleValue.ERASED)
        val selected = settings().copy(preRecoveryEdits = edits, lockManualBits = true,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M))
        val result = AutoRecoveryEngine().run(image(), profile = SamplingProfile(selected, false),
            listDecodingBudget = ListDecodingBudget(0))
        assertTrue(result.testedHypotheses.any { it.geometry.warp == null })
        assertTrue(result.testedHypotheses.any { it.geometry.warp != null })
        assertTrue(result.testedHypotheses.all { it.geometry.version == 4 })
        for (attempt in result.attempts) for ((coord, value) in edits) {
            assertEquals(value, attempt.matrix[coord.x, coord.y].value)
            assertTrue(attempt.matrix[coord.x, coord.y].source.isUserEdit)
        }
        assertTrue(result.entries.any { it.candidate.payloadParsed && it.candidate.text == text })
        assertTrue(result.entries.all { it.candidate.ecLevel == ErrorCorrectionLevel.M })
        val ordinary = AutoRecoveryEngine().run(image(), profile = SamplingProfile(selected.copy(distortionCorrection = false), false),
            listDecodingBudget = ListDecodingBudget(0))
        assertTrue(ordinary.testedHypotheses.all { it.geometry.warp == null })
        assertEquals(ordinary.testedHypotheses, result.testedHypotheses.filter { it.geometry.warp == null })
    }

    @Test fun mirroredSourceKeepsTheSelectedLogicalOrientation() {
        val source = image()
        val mirrored = LumaImage(side, side, IntArray(side * side) { source.pixels[(it % side) * side + it / side] })
        val geometry = base().copy(imageQuad = base().imageQuad!!.transposed()).reanchored()
        val profile = SamplingProfile(settings().copy(mirroredInput = true, manualQuad = geometry.imageQuad), false)
        val candidates = CurvedGridDetector(mirrored, profile).candidates(geometry)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { candidate -> RecoveryEngine().recover(MatrixSampler(mirrored, profile).sample(candidate).first,
            listDecodingBudget = ListDecodingBudget(0)).any { it.payloadParsed && it.text == text } })
    }

    @Test fun missingLandmarksAndCancellationDoNotProduceInventedWarps() {
        val flat = LumaImage(side, side, IntArray(side * side) { 127 })
        val profile = SamplingProfile(settings(), false)
        assertTrue(CurvedGridDetector(flat, profile).candidates(base()).isEmpty())
        assertTrue(CurvedGridDetector(image(), profile, stopRequested = { true }).candidates(base()).isEmpty())
        assertThrows(RecoveryCancelledException::class.java) {
            CurvedGridDetector(image(), profile, RecoveryCancellation { true }).candidates(base())
        }
    }
}
