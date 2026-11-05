package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class ProjectiveSamplingRecoveryTest {
    @Test fun jointFitAndItsCurvedCandidatesPreserveTheSelectedGridAndEdits() {
        val text = "PROJECTIVE FIT"
        val grid = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(
            EncodeHintType.QR_VERSION to 3, EncodeHintType.QR_MASK_PATTERN to 2,
        )).matrix
        val last = grid.width - 1.0
        val logical = listOf(PointD(0.0, 0.0), PointD(last, 0.0), PointD(last, last), PointD(0.0, last))
        val truth = ImageQuad(PointD(30.0, 33.0), PointD(44.0, 190.0), PointD(205.0, 217.0), PointD(223.0, 22.0))
        val inverse = Homography.fromCorrespondences(truth.asList(), logical)
        val image = LumaImage(252, 252, IntArray(252 * 252) { i ->
            val p = inverse.map((i % 252).toDouble(), (i / 252).toDouble())
            val x = p.x.roundToInt()
            val y = p.y.roundToInt()
            if (x in 0 until grid.width && y in 0 until grid.height && grid[x, y].toInt() == 1) 235 else 20
        })
        val seed = ImageQuad(truth.topLeft + PointD(6.0, -3.0), truth.topRight + PointD(-4.0, 6.0),
            truth.bottomRight + PointD(3.0, -7.0), truth.bottomLeft + PointD(-5.0, -2.0))
        val erased = Coord(14, 14)
        val fixed = Coord(13, 13)
        val fixedValue = if (grid[fixed.x, fixed.y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO
        val settings = PreprocessSettings(version = 3, manualQuad = seed, searchQuadNeighborhood = true,
            mirroredInput = true, preRecoveryEdits = mapOf(erased to ModuleValue.ERASED, fixed to fixedValue),
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2), listDecodingAdditionalErrors = 0)
        val preview = GridGeometry(3, FinderRole.TOP_LEFT, seed.topLeft.x, seed.topLeft.y, 6.0, 0.0,
            seed, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        for (selectedPreview in listOf(null, preview)) {
            val configured = settings.copy(distortionCorrection = selectedPreview != null, previewGeometry = selectedPreview)
            val result = AutoRecoveryEngine().run(image, profile = SamplingProfile(configured, true),
                listDecodingBudget = ListDecodingBudget(0))
            val joint = result.testedHypotheses.single { it.patternRefined && it.geometry.warp == null }
            val hypotheses = result.testedHypotheses
            if (selectedPreview == null) {
                assertEquals(joint, hypotheses.first())
                assertEquals(seed, hypotheses[1].geometry.moduleCenterQuad())
            } else {
                assertEquals(selectedPreview, hypotheses.first().geometry)
                assertEquals(joint, hypotheses[1])
            }
            assertTrue(hypotheses.any { !it.patternRefined && it.geometry.moduleCenterQuad() == seed })
            assertEquals(hypotheses.size, hypotheses.map { it.geometry }.distinct().size)
            val additional = hypotheses.drop(if (selectedPreview == null) 1 else 2)
            assertTrue(additional.dropWhile { it.geometry.warp == null }.all { it.geometry.warp != null })
            val curved = hypotheses.filter { it.geometry.warp != null }
            if (selectedPreview == null) {
                assertTrue(curved.isEmpty())
            } else {
                val fromJoint = curved.filter { it.patternRefined }
                assertTrue("Missing curved candidates from the LM grid", fromJoint.isNotEmpty())
                assertTrue(fromJoint.any { it.geometry.imageQuad == joint.geometry.imageQuad })
                assertTrue(fromJoint.size <= 12)
                assertTrue(curved.size <= 60)
            }
            joint.geometry.moduleCenterQuad().asList().zip(truth.asList()).forEach { (p, expected) ->
                assertTrue("Recovered corner $p is too far from $expected", p.distanceTo(expected) < 2.0)
            }
            // Equivalent payloads from multiple grids may be merged. Check image sampling via
            // the exact joint grid, and verify the selected recovery still carries the manual edits.
            val entry = result.entries.first { it.candidate.text == text }
            val sampled = MatrixSampler(image, SamplingProfile(settings, true)).sample(joint.geometry).first
            assertTrue(RecoveryEngine().recover(sampled, formatConstraint = settings.formatConstraint,
                listDecodingBudget = ListDecodingBudget(0)).any { it.text == text })
            assertEquals(ModuleValue.ERASED, entry.attempt.matrix[erased.x, erased.y].value)
            assertEquals(ModuleSource.USER_SOFT, entry.attempt.matrix[erased.x, erased.y].source)
            assertEquals(ModuleSource.USER, entry.attempt.matrix[fixed.x, fixed.y].source)
        }
    }
}
