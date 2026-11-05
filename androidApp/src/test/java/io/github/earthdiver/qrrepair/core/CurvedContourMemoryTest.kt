package io.github.earthdiver.qrrepair.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class CurvedContourMemoryTest {
    private val text = "QREVY"
    private data class Fixture(val image: LumaImage, val geometry: GridGeometry)

    private fun fixture(side: Int = 960, tiledFooter: Boolean = false): Fixture {
        val qrSide = 210
        val qr = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, qrSide, qrSide)
        val offsetX = (side - qrSide) / 2
        val offsetY = if (tiledFooter) 20 else offsetX
        val image = LumaImage(side, side, IntArray(side * side) { index ->
            val x = index % side; val y = index / side
            val qx = x - offsetX; val qy = y - offsetY
            when {
                qx in 0 until qrSide && qy in 0 until qrSide -> if (qr[qx, qy]) 0 else 255
                tiledFooter && y >= 260 -> if (((x / 4) xor (y / 4)) and 1 == 0) 0 else 255
                else -> if ((x xor y) and 1 == 0) 0 else 255
            }
        })
        // Version 1 occupies 21 modules at seven pixels each, centered by QRCodeWriter.
        val x = offsetX + (qrSide - 21 * 7) / 2 + 3.0
        val y = offsetY + (qrSide - 21 * 7) / 2 + 3.0
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, x + 21, y + 21, 7.0, 0.0,
            ImageQuad(PointD(x, y), PointD(x + 140, y), PointD(x + 140, y + 140), PointD(x, y + 140)),
            GeometryModel.MANUAL_PROJECTIVE).reanchored()
        return Fixture(image, geometry)
    }

    @Test fun checkerboardBackgroundRetainsTheQrFindersWithinTheContourBudget() {
        val (image, geometry) = fixture()
        val detector = CurvedFinderContours(image, false) { false }
        val seeds = detector.seeds(geometry)
        assertFalse(detector.memoryLimited)
        assertTrue(seeds.isNotEmpty())
        assertTrue(seeds.all { it.points.size == 12 })
    }

    @Test fun anUnfinishedScaleDoesNotPublishItsEarlierFinderMatches() {
        val (image, geometry) = fixture(side = 320, tiledFooter = true)
        assertTrue(CurvedFinderContours(image, false) { false }.seeds(geometry).isNotEmpty())
        val limited = CurvedFinderContours(image, false, maxWorkingBytes = 3L * 1024 * 1024) { false }
        assertTrue(limited.seeds(geometry).isEmpty())
        assertTrue(limited.memoryLimited)
    }

    @Test fun contourBudgetExhaustionStillAllowsPatchCandidatesAndOrdinaryRecovery() {
        val (image, geometry) = fixture(side = 256)
        val settings = PreprocessSettings(version = 1, manualQuad = geometry.imageQuad,
            distortionCorrection = true, polarity = PolarityMode.NORMAL,
            manualFinders = mapOf(FinderRole.TOP_LEFT to geometry.moduleToImage(3.0, 3.0)),
            listDecodingAdditionalErrors = 0)
        val profile = SamplingProfile(settings, false)
        val detector = CurvedGridDetector(image, profile, maxContourWorkingBytes = 0)
        assertTrue(detector.candidates(geometry).isNotEmpty())
        assertTrue(detector.contourMemoryLimited)
        val result = AutoRecoveryEngine(maxContourWorkingBytes = 0).run(image,
            maxGeometryAttempts = 1, profile = profile, listDecodingBudget = ListDecodingBudget(0))
        assertTrue(result.testedHypotheses.any { it.geometry.warp == null })
        assertTrue(result.entries.any { it.candidate.isSelectableRecovery && it.candidate.text == text })
        val diagnostic = requireNotNull(result.searchDiagnostic)
        assertTrue(SearchIncompleteReason.WORKING_MEMORY_LIMIT in diagnostic.incompleteReasons)
        assertEquals(SearchCompleteness.UNSEARCHED, diagnostic.completeness)
    }
}
