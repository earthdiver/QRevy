package qrevy.web

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import kotlin.test.*

class BrowserInitialGridEstimatorTest {
    private fun damaged(inverted: Boolean = false, rotation: Int = 0, finder: Boolean = true, timing: Boolean = true): LumaImage {
        val qr = Encoder.encode("STRUCTURE 42", ErrorCorrectionLevel.Q, mapOf(EncodeHintType.QR_VERSION to 2)).matrix
        val size = 264
        return LumaImage(size, size, IntArray(size * size) { i ->
            var x = i % size / 8 - 4
            var y = i / size / 8 - 4
            repeat(rotation) { val oldX = x; x = y; y = 24 - oldX }
            val value = when {
                x !in 0..24 || y !in 0..24 -> 255
                x in 13..20 || (y >= 8 && x <= 20) || (y >= 5 && x < y - 4) -> 128
                !finder && x in 0..6 && y in 0..6 -> 128
                !timing && y == 6 && x in 8..16 -> 128
                qr.get(x, y).toInt() == 1 -> 0
                else -> 255
            }
            if (inverted) 255 - value else value
        })
    }

    @Test fun survivingStructureResolvesQuietZoneAliasWithoutAssigningOrientation() {
        for (rotation in 0..3) for (inverted in listOf(false, true)) {
            val image = damaged(inverted, rotation)
            val label = "rotation=$rotation inverted=$inverted"
            // Exercise the competing periodic grids directly, as well as the complete browser estimate.
            val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
            val resolved = PeriodicityStructure.resolveQuietZoneAlias(image, inverse,
                assertNotNull(GridPeriodicityEstimator.estimate(image), label),
                assertNotNull(GridPeriodicityEstimator.estimate(inverse), label))
            for (estimate in listOf(resolved, BrowserInitialGridEstimator.estimate(image, emptyList()))) {
                assertNotNull(estimate, label)
                assertEquals(2, estimate.version)
                for ((point, expected) in estimate.moduleCenterQuad.asList().zip(listOf(
                    PointD(35.5, 35.5), PointD(227.5, 35.5), PointD(227.5, 227.5), PointD(35.5, 227.5)))) {
                    assertTrue(point.distanceTo(expected) < .1, label)
                }
                assertTrue(estimate.autoAssignedFinders.isEmpty())
                assertEquals(FinderPolarity.UNKNOWN, estimate.confirmedPolarity)
            }
            assertEquals(InitialGridEstimator.estimate(image, emptyList()),
                BrowserInitialGridEstimator.estimate(image, emptyList()), label)
        }
    }

    @Test fun insufficientStructureDoesNotResolveAmbiguity() {
        val image = damaged()
        val normal = assertNotNull(GridPeriodicityEstimator.estimate(image))
        val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        val inverted = assertNotNull(GridPeriodicityEstimator.estimate(inverse))
        for (damagedImage in listOf(damaged(finder = false), damaged(timing = false))) {
            val damagedInverse = LumaImage(damagedImage.width, damagedImage.height,
                IntArray(damagedImage.pixels.size) { 255 - damagedImage.pixels[it] })
            assertNull(PeriodicityStructure.resolveQuietZoneAlias(damagedImage, damagedInverse, normal, inverted))
        }
        assertNull(PeriodicityStructure.resolveQuietZoneAlias(image, inverse, normal, inverted.copy(version = 5)))
        assertNull(PeriodicityStructure.resolveQuietZoneAlias(image, inverse, normal, inverted.copy(modulePitch = 12.0)))
        assertNull(BrowserInitialGridEstimator.estimate(LumaImage(264, 264, IntArray(264 * 264) { 128 }), emptyList()))
    }

    @Test fun cancellationPropagates() {
        assertFailsWith<RecoveryCancelledException> {
            BrowserInitialGridEstimator.estimate(damaged(), emptyList(), RecoveryCancellation { true })
        }
    }
}
