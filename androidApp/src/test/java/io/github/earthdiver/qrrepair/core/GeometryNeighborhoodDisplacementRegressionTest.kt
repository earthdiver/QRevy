package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GeometryNeighborhoodDisplacementRegressionTest {
    private val widths = (3..8).map { it / 10.0 }
    private val version = 3
    private val text = "GEOMETRY WIDTH REGRESSION"
    private val pixelsPerModule = 10
    private val quietModules = 4

    @Test
    fun equalDisplacementSweepRecoversSyntheticQrAndKeepsTransformsComparable() {
        val encoded = Encoder.encode(
            text,
            ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.QR_VERSION to version),
        )
        val image = renderCenterEvidence(encoded.matrix)
        val truth = trueQuad(encoded.matrix.width)
        val profile = SamplingProfile(
            PreprocessSettings(
                version = version,
                manualQuad = truth,
                blackMax = 80,
                whiteMin = 175,
                automaticThresholds = false,
            ),
            inverted = false,
        )
        val successes = linkedMapOf<Double, Int>()

        for (searchWidth in widths) {
            var recoveredCases = 0
            for (injectedWidth in widths) {
                for (kind in Perturbation.entries) {
                    val input = perturb(truth, injectedWidth, kind)
                    val variants = manualQuadNeighborhood(input, version, searchWidth)
                    assertComparableDisplacements(input, variants, searchWidth)
                    if (variants.any { candidate -> recovers(image, profile, candidate) }) recoveredCases++
                }
            }
            successes[searchWidth] = recoveredCases
        }
        // Every exact-width correction must recover all three perturbation families. The full
        // score map is included in failures so changes to the useful-width plateau are visible.
        for (width in widths) {
            for (kind in Perturbation.entries) {
                val input = perturb(truth, width, kind)
                assertTrue(
                    "Exact correction failed width=$width kind=$kind scores=$successes",
                    manualQuadNeighborhood(input, version, width).any { recovers(image, profile, it) },
                )
            }
        }
        assertTrue("No useful search width: $successes", successes.values.any { it > 0 })
        assertEquals(
            "The default 0.6-module width fell below another tested width: $successes",
            successes.values.maxOrNull(),
            successes[0.6],
        )
    }

    private fun recovers(image: LumaImage, profile: SamplingProfile, quad: ImageQuad): Boolean {
        val dimension = 17 + 4 * version
        val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))
        val geometry = GridGeometry(
            version,
            FinderRole.TOP_LEFT,
            quad.topLeft.x,
            quad.topLeft.y,
            pitch,
            0.0,
            quad,
            GeometryModel.MANUAL_PROJECTIVE,
        ).reanchored()
        val matrix = MatrixSampler(image, profile).sample(geometry).first
        // Compare geometry using the ordinary decoder; the timed list-decoding extension
        // must not change this displacement sweep's recovery threshold.
        return RecoveryEngine().recover(matrix, maxFormatCandidates = 1, gsBudget = GsSearchBudget(0)).any { it.text == text }
    }

    private fun perturb(quad: ImageQuad, modules: Double, kind: Perturbation): ImageQuad {
        val points = quad.asList()
        val pitch = pixelsPerModule.toDouble()
        val displacement = modules * pitch
        return when (kind) {
            Perturbation.AXIS_TRANSLATION -> from(points.map { PointD(it.x + displacement, it.y) })
            Perturbation.DIAGONAL_TRANSLATION -> {
                val component = displacement / sqrt(2.0)
                from(points.map { PointD(it.x + component, it.y + component) })
            }
            Perturbation.OUTER_SCALE -> {
                val center = center(points)
                val meanRadius = points.map { it.distanceTo(center) }.average()
                val scale = 1.0 + displacement / meanRadius
                from(points.map { center + (it - center) * scale })
            }
        }
    }

    private fun assertComparableDisplacements(input: ImageQuad, variants: List<ImageQuad>, modules: Double) {
        val expected = modules * inferredPitch(input)
        val base = input.asList()
        val axis = variants[1].asList().zip(base).map { (a, b) -> a.distanceTo(b) }.average()
        val diagonal = variants[5].asList().zip(base).map { (a, b) -> a.distanceTo(b) }.average()
        val scale = variants[9].asList().zip(base).map { (a, b) -> a.distanceTo(b) }.average()
        assertEquals(expected, axis, 1e-6)
        assertEquals(expected, diagonal, 1e-6)
        assertEquals(expected, scale, 1e-6)
    }

    private fun inferredPitch(quad: ImageQuad): Double {
        val dimension = 17 + 4 * version
        return (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))
    }

    private fun renderCenterEvidence(matrix: ByteMatrix): LumaImage {
        val modules = matrix.width + quietModules * 2
        val size = modules * pixelsPerModule
        val pixels = IntArray(size * size) { 128 }
        val halfPatch = 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            val value = if (matrix.get(x, y).toInt() == 1) 0 else 255
            val centerX = (x + quietModules) * pixelsPerModule + pixelsPerModule / 2
            val centerY = (y + quietModules) * pixelsPerModule + pixelsPerModule / 2
            for (py in centerY - halfPatch..centerY + halfPatch) {
                for (px in centerX - halfPatch..centerX + halfPatch) pixels[py * size + px] = value
            }
        }
        return LumaImage(size, size, pixels)
    }

    private fun trueQuad(dimension: Int): ImageQuad {
        val first = (quietModules + .5) * pixelsPerModule
        val last = (quietModules + dimension - .5) * pixelsPerModule
        return ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last))
    }

    private fun center(points: List<PointD>) = PointD(points.map { it.x }.average(), points.map { it.y }.average())
    private fun from(points: List<PointD>) = ImageQuad(points[0], points[1], points[2], points[3])

    private enum class Perturbation { AXIS_TRANSLATION, DIAGONAL_TRANSLATION, OUTER_SCALE }
}
