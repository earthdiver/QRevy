package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentDetectorPolarityTest {
    @Test fun invertedProfileDetectsBrightLogicalAlignmentPattern() {
        val pitch = 4
        val size = 100
        val pixels = IntArray(size * size) { 0 }
        val center = 18 * pitch
        for (dy in -2..2) for (dx in -2..2) {
            val logicalBlack = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) == 2 || (dx == 0 && dy == 0)
            for (y in center + dy * pitch until center + (dy + 1) * pitch) {
                for (x in center + dx * pitch until center + (dx + 1) * pitch) {
                    pixels[y * size + x] = if (logicalBlack) 255 else 0
                }
            }
        }
        val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 3.0 * pitch, 3.0 * pitch, pitch.toDouble(), 0.0)
        val profile = SamplingProfile(PreprocessSettings(polarity = PolarityMode.INVERTED, version = 2), inverted = true)

        val candidate = AlignmentDetector(LumaImage(size, size, pixels), profile).findBest(geometry)

        assertNotNull(candidate)
        assertTrue(kotlin.math.abs(candidate!!.imageX - center) <= pitch)
        assertTrue(kotlin.math.abs(candidate.imageY - center) <= pitch)
    }

    @Test fun alignmentRefinementAddsAnInteriorCorrectionWithoutReplacingOtherCorners() {
        val pitch = 4
        val size = 110
        val pixels = IntArray(size * size) { 255 }
        val shiftedCenter = 18 * pitch + 2
        for (dy in -2..2) for (dx in -2..2) {
            val black = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) == 2 || (dx == 0 && dy == 0)
            for (y in shiftedCenter + dy * pitch until shiftedCenter + (dy + 1) * pitch) {
                for (x in shiftedCenter + dx * pitch until shiftedCenter + (dx + 1) * pitch) {
                    pixels[y * size + x] = if (black) 0 else 255
                }
            }
        }
        val image = LumaImage(size, size, pixels)
        val profile = SamplingProfile(
            PreprocessSettings(version = 2, automaticThresholds = false),
            inverted = false,
        )
        val base = GridGeometry(
            2,
            FinderRole.TOP_LEFT,
            3.0 * pitch,
            3.0 * pitch,
            pitch.toDouble(),
            0.0,
        ).asProjective(manual = true)

        val result = alignmentRefinedGeometry(image, profile, base)

        assertNotNull(result)
        val (refined, alignment) = result!!
        val target = PointD(alignment.imageX, alignment.imageY)
        val baseError = base.moduleToImage(18.0, 18.0).distanceTo(target)
        val refinedError = refined.moduleToImage(18.0, 18.0).distanceTo(target)
        assertTrue(refinedError < baseError)
        assertTrue(refinedError <= pitch * .05)
        val before = base.moduleCenterQuad().asList()
        val after = refined.moduleCenterQuad().asList()
        assertEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        assertEquals(before[3], after[3])
        assertTrue(before[2] != after[2])

        val autoProfile = profile.copy(settings = profile.settings.copy(manualQuad = base.moduleCenterQuad()))
        val auto = AutoRecoveryEngine().run(image, maxGeometryAttempts = 2, profile = autoProfile)
        assertEquals(2, auto.attempts.size)
        assertTrue(auto.attempts.any { it.hypothesis.alignment == null })
        assertNotNull(auto.attempts.single { it.hypothesis.alignment != null }.hypothesis.alignment)
    }
}
