package io.github.earthdiver.qrrepair.core

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitialGridEstimatorTest {
    @Test fun damagedPerimeterPreservesAnInternallySupportedGrid() {
        val unit = 6
        for (margin in listOf(0, unit)) for (inverted in listOf(false, true)) {
            val size = 21 * unit + 2 * margin
            val edge = margin.toDouble()
            val end = edge + 21 * unit
            val original = render(1, ImageQuad(PointD(edge, edge), PointD(end, edge),
                PointD(end, end), PointD(edge, end)), size = size)
            val pixels = original.pixels.copyOf()
            for (row in 0 until size) for (column in 0 until size) {
                val moduleColumn = (column - margin).toDouble() / unit
                val moduleRow = (row - margin).toDouble() / unit
                if (moduleColumn >= 0 && moduleColumn < 21 && moduleRow >= 0 && moduleRow < 21 &&
                    (moduleColumn < 1 || moduleColumn >= 20 || moduleRow < 1 || moduleRow >= 20))
                    pixels[row * size + column] = 12
                if (inverted) pixels[row * size + column] = 255 - pixels[row * size + column]
            }
            val result = assertNotNull(InitialGridEstimator.estimate(LumaImage(size, size, pixels), emptyList()),
                "margin=$margin inverted=$inverted")
            assertEquals(1, result.version)
            assertTrue(result.autoAssignedFinders.isEmpty())
            assertEquals(FinderPolarity.UNKNOWN, result.confirmedPolarity)
            assertTrue(result.moduleCenterQuad.topLeft.distanceTo(PointD(edge + 2.5, edge + 2.5)) < 1)
        }
    }

    @Test fun identicalFullImageWindowsAreEstimatedOnlyOnce() {
        val random = kotlin.random.Random(42)
        val image = LumaImage(64, 64, IntArray(64 * 64) { random.nextInt(256) })
        val stages = mutableMapOf<String, Int>()
        assertNull(InitialGridEstimator.estimate(image, emptyList(), trace = GridEstimationTrace { stage, detail ->
            if (detail == "START") stages[stage] = (stages[stage] ?: 0) + 1
        }))
        for (stage in listOf("NORMAL_PERIODICITY", "INVERTED_PERIODICITY", "NORMAL_TWO_FINDERS", "INVERTED_TWO_FINDERS"))
            assertEquals(3, stages[stage], stage)
    }

    @Test fun clippedPerimeterCanRetainIndependentTimingEvidence() {
        val image = render(4, ImageQuad(PointD(-30.0, -30.0), PointD(168.0, -30.0),
            PointD(168.0, 168.0), PointD(-30.0, 168.0)), size = 138)
        val estimate = InitialGridEstimate(4, ImageQuad(PointD(-27.0, -27.0), PointD(165.0, -27.0),
            PointD(165.0, 165.0), PointD(-27.0, 165.0)), InitialGridSource.PERIODICITY)
        assertTrue(InitialGridEstimator.hasUniformPerimeter(image, estimate))
        assertTrue(PeriodicityStructure.hasTimingSupport(image, estimate))
        val unknown = LumaImage(138, 138, IntArray(138 * 138) { 128 })
        assertTrue(!PeriodicityStructure.hasTimingSupport(unknown, estimate))
        val uniform = LumaImage(138, 138, IntArray(138 * 138) { 12 })
        assertTrue(!PeriodicityStructure.hasTimingSupport(uniform, estimate))
    }

    private fun render(version: Int, quad: ImageQuad, finders: Boolean = true, size: Int = 400): LumaImage {
        val n = 17 + 4 * version
        val h = Homography.fromCorrespondences(listOf(PointD(0.0, 0.0), PointD(n.toDouble(), 0.0),
            PointD(n.toDouble(), n.toDouble()), PointD(0.0, n.toDouble())), quad.asList()).inverse()
        fun black(x: Int, y: Int): Boolean {
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val dx = x - sx; val dy = y - sy
                if (dx in -1..7 && dy in -1..7) {
                    if (!finders || dx !in 0..6 || dy !in 0..6) return false
                    return dx == 0 || dy == 0 || dx == 6 || dy == 6 || (dx in 2..4 && dy in 2..4)
                }
            }
            if (x == 6 && y in 8..n - 9) return y % 2 == 0
            if (y == 6 && x in 8..n - 9) return x % 2 == 0
            return (x * 17 + y * 11 + x * y) % 7 < 3
        }
        return LumaImage(size, size, IntArray(size * size) { i ->
            val p = h.map((i % size).toDouble(), (i / size).toDouble())
            val x = floor(p.x).toInt(); val y = floor(p.y).toInt()
            if (x in 0 until n && y in 0 until n && black(x, y)) 12 else 245
        })
    }

    private val perspective = ImageQuad(PointD(55.0, 45.0), PointD(330.0, 65.0),
        PointD(305.0, 350.0), PointD(85.0, 305.0))

    @Test fun quietZoneDoesNotTurnAnInvertedBackgroundIntoAConflictingQrGrid() {
        for (version in listOf(1, 4, 7)) for (margin in listOf(10, 20, 40)) {
            val span = (17 + 4 * version) * 5
            val size = span + 2 * margin
            val a = margin.toDouble(); val b = a + span
            val points = ImageQuad(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b)).asList()
            for (rotation in 0..3) {
                val quad = ImageQuad(points[rotation], points[(rotation + 1) % 4],
                    points[(rotation + 2) % 4], points[(rotation + 3) % 4])
                val rendered = render(version, quad, size = size)
                val expected = assertNotNull(GridPeriodicityEstimator.estimate(rendered))
                assertEquals(version, expected.version)
                for (inverted in listOf(false, true)) {
                    val image = if (!inverted) rendered else LumaImage(size, size,
                        IntArray(size * size) { 255 - rendered.pixels[it] })
                    val result = assertNotNull(InitialGridEstimator.estimate(image, emptyList()),
                        "V$version margin=$margin rotation=$rotation inverted=$inverted")
                    assertEquals(version, result.version)
                    assertEquals(InitialGridSource.PERIODICITY, result.source)
                    assertTrue(result.autoAssignedFinders.isEmpty())
                    assertEquals(FinderPolarity.UNKNOWN, result.confirmedPolarity)
                    for ((actual, center) in result.moduleCenterQuad.asList().zip(expected.moduleCenterQuad.asList()))
                        assertTrue(actual.distanceTo(center) < .001)
                }
            }
        }
    }

    @Test fun twoStructurallySupportedOppositePolarityGridsRemainAmbiguous() {
        val firstQuad = ImageQuad(PointD(20.0, 20.0), PointD(125.0, 20.0),
            PointD(125.0, 125.0), PointD(20.0, 125.0))
        val secondQuad = ImageQuad(PointD(220.0, 220.0), PointD(365.0, 220.0),
            PointD(365.0, 365.0), PointD(220.0, 365.0))
        val first = render(1, firstQuad)
        val second = render(3, secondQuad)
        val image = LumaImage(400, 400, IntArray(160000) { i ->
            if (i % 400 in 210..375 && i / 400 in 210..375) 255 - second.pixels[i] else first.pixels[i]
        })
        val inverse = LumaImage(400, 400, IntArray(160000) { 255 - image.pixels[it] })
        val normal = assertNotNull(InitialGridEstimator.fromBoundary(image, firstQuad))
        val inverted = assertNotNull(InitialGridEstimator.fromBoundary(image, secondQuad))
        assertEquals(3, normal.autoAssignedFinders.size)
        assertEquals(3, inverted.autoAssignedFinders.size)
        assertNull(InitialGridEstimator.resolvePeriodicityConflict(image, inverse, normal, inverted))
        assertNull(InitialGridEstimator.resolvePeriodicityConflict(inverse, image, inverted, normal))
    }

    @Test fun cancellationDuringStructuralConflictVerificationPropagates() {
        val image = render(3, perspective)
        val normal = assertNotNull(InitialGridEstimator.fromBoundary(image, perspective))
        val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        var checks = 0
        assertFailsWith<RecoveryCancelledException> {
            InitialGridEstimator.resolvePeriodicityConflict(image, inverse, normal, normal.copy(version = 4),
                RecoveryCancellation { ++checks >= 3 })
        }
        assertEquals(3, checks)
    }

    @Test fun boundaryValidationUsesOneThresholdInsideThePolygonRegardlessOfBackground() {
        val rendered = render(3, perspective)
        val points = perspective.asList()
        val expected = assertNotNull(InitialGridEstimator.fromBoundary(rendered, perspective))
        for (background in listOf(0, 255)) for (inverted in listOf(false, true)) {
            val image = LumaImage(400, 400, IntArray(160000) { i ->
                val x = i % 400; val y = i / 400
                val inside = (0..3).all { k ->
                    val a = points[k]; val b = points[(k + 1) % 4]
                    (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= 0
                }
                val value = if (inside) { if (rendered.pixels[i] == 12) 110 else 130 } else background
                if (inverted) 255 - value else value
            })
            assertTrue(image.threshold !in 110..129, "Whole-image threshold should not separate the QR tones")
            assertEquals(if (inverted) 125 else 110, image.thresholdInside(perspective))
            val result = assertNotNull(InitialGridEstimator.fromBoundary(image, perspective))
            assertEquals(expected.version, result.version)
            assertEquals(expected.moduleCenterQuad, result.moduleCenterQuad)
            assertEquals(expected.autoAssignedFinders, result.autoAssignedFinders)
            assertEquals(if (inverted) FinderPolarity.INVERTED else FinderPolarity.NORMAL, result.confirmedPolarity)
        }
    }

    @Test fun croppedOuterEdgesAreAcceptedWhenAllModuleCentersRemainInside() {
        val q = ImageQuad(PointD(-1.0, -1.0), PointD(83.0, -1.0),
            PointD(83.0, 83.0), PointD(-1.0, 83.0))
        val rendered = render(1, q, size = 82)
        for (inverted in listOf(false, true)) {
            val image = if (!inverted) rendered else LumaImage(82, 82, IntArray(82 * 82) { 255 - rendered.pixels[it] })
            val result = assertNotNull(InitialGridEstimator.fromBoundary(image, q))
            assertEquals(1, result.version)
            for (p in result.moduleCenterQuad.asList()) assertTrue(image.inBounds(p.x, p.y))
            assertTrue(result.moduleCenterQuad.topLeft.distanceTo(PointD(1.0, 1.0)) < 1e-8)
        }
    }

    @Test fun croppedModuleCentersPreventBoundaryAcceptance() {
        for (shift in listOf(-2.0, 2.0)) {
            val q = ImageQuad(PointD(-1.0 + shift, -1.0), PointD(83.0 + shift, -1.0),
                PointD(83.0 + shift, 83.0), PointD(-1.0 + shift, 83.0))
            assertNull(InitialGridEstimator.fromBoundary(render(1, q, size = 82), q))
        }
    }

    @Test fun contrastOutsideTheBoundaryCannotValidateAFlatInterior() {
        val image = LumaImage(400, 400, IntArray(160000) { i -> if (i % 400 < 40) 0 else 130 })
        assertNull(image.thresholdInside(perspective))
        assertNull(InitialGridEstimator.fromBoundary(image, perspective))
        val outside = shift(perspective, 500.0)
        assertNull(image.thresholdInside(outside))
        assertNull(InitialGridEstimator.fromBoundary(image, outside))
    }

    @Test fun duplicateBoundaryWithUnknownPolarityKeepsVerifiedRolesButConflictingPolaritiesDoNotMerge() {
        val verified = assertNotNull(InitialGridEstimator.fromBoundary(render(3, perspective), perspective))
        val unassigned = verified.copy(autoAssignedFinders = emptyMap(), confirmedPolarity = FinderPolarity.UNKNOWN)
        assertEquals(verified, InitialGridEstimator.mergeNativeEstimates(listOf(unassigned, verified)))
        assertEquals(null, InitialGridEstimator.mergeNativeEstimates(listOf(verified,
            verified.copy(confirmedPolarity = FinderPolarity.INVERTED))))
    }

    @Test fun invertedBoundariesPreserveVersionCornersAndFinderRoles() {
        for (version in listOf(1, 3, 7)) {
            val image = render(version, perspective)
            val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
            val normal = assertNotNull(InitialGridEstimator.estimate(image, listOf(perspective)))
            val inverted = assertNotNull(InitialGridEstimator.estimate(inverse, listOf(perspective)))
            assertEquals(normal.version, inverted.version)
            assertEquals(normal.moduleCenterQuad, inverted.moduleCenterQuad)
            assertEquals(normal.autoAssignedFinders, inverted.autoAssignedFinders)
            assertEquals(InitialGridSource.ZXING_CPP, inverted.source)
            assertEquals(FinderPolarity.NORMAL, normal.confirmedPolarity)
            assertEquals(FinderPolarity.INVERTED, inverted.confirmedPolarity)
        }
    }

    @Test fun estimatesUnknownDimensionAndConvertsPerspectiveBoundaryToCenters() {
        for (version in listOf(1, 3, 7)) {
            val image = render(version, perspective)
            val result = assertNotNull(InitialGridEstimator.estimate(image, listOf(perspective)))
            assertEquals(InitialGridSource.ZXING_CPP, result.source)
            assertEquals(version, result.version)
            val n = 17 + 4 * version
            val expected = Homography.fromCorrespondences(listOf(PointD(0.0, 0.0), PointD(n.toDouble(), 0.0),
                PointD(n.toDouble(), n.toDouble()), PointD(0.0, n.toDouble())), perspective.asList()).map(.5, .5)
            assertTrue(expected.distanceTo(result.moduleCenterQuad.topLeft) < 1e-8)
            assertTrue(result.moduleCenterQuad.topLeft.distanceTo(perspective.topLeft) > 1.0)
        }
    }

    @Test fun boundaryStartingCornerDoesNotChangeLogicalGrid() {
        val image = render(3, perspective)
        val p = perspective.asList()
        for (r in 0..3) {
            val rotated = ImageQuad(p[r], p[(r + 1) % 4], p[(r + 2) % 4], p[(r + 3) % 4])
            val result = assertNotNull(InitialGridEstimator.estimate(image, listOf(rotated)))
            assertEquals(InitialGridSource.ZXING_CPP, result.source)
            assertEquals(3, result.version)
            assertTrue(result.moduleCenterQuad.topLeft.distanceTo(p[0]) < 12)
        }
    }

    @Test fun missingFindersRetainExistingPeriodicityRecovery() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val image = render(4, q, finders = false, size = 300)
        val old = assertNotNull(GridPeriodicityEstimator.estimate(image))
        for (candidates in listOf(emptyList(), listOf(q))) {
            val result = assertNotNull(InitialGridEstimator.estimate(image, candidates))
            assertEquals(InitialGridSource.PERIODICITY, result.source)
            assertTrue(result.autoAssignedFinders.isEmpty())
            assertEquals(old.version, result.version)
            assertEquals(old.moduleCenterQuad, result.moduleCenterQuad)
        }
    }

    @Test fun missingFindersPreservePeriodicityCornersAfterInversion() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val image = render(4, q, finders = false, size = 300)
        val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        for (boundaries in listOf(emptyList(), listOf(q))) {
            val normal = assertNotNull(InitialGridEstimator.estimate(image, boundaries))
            val inverted = assertNotNull(InitialGridEstimator.estimate(inverse, boundaries))
            assertEquals(InitialGridSource.PERIODICITY, inverted.source)
            assertEquals(normal.version, inverted.version)
            for ((a, b) in normal.moduleCenterQuad.asList().zip(inverted.moduleCenterQuad.asList())) {
                assertTrue(a.distanceTo(b) < .001)
            }
            assertTrue(inverted.autoAssignedFinders.isEmpty())
            assertEquals(FinderPolarity.UNKNOWN, inverted.confirmedPolarity)
        }
    }

    @Test fun oppositePolarityBackgroundGridOnAUniformSurroundIsNotASymbol() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val image = render(4, q, finders = false)
        val inverse = LumaImage(image.width, image.height, IntArray(image.pixels.size) { 255 - image.pixels[it] })
        val normal = assertNotNull(GridPeriodicityEstimator.estimate(image))
        val inverted = assertNotNull(GridPeriodicityEstimator.estimate(inverse))
        assertEquals(4, normal.version)
        assertEquals(10, inverted.version)
        assertTrue(InitialGridEstimator.hasUniformPerimeter(inverse,
            InitialGridEstimate(inverted.version, inverted.moduleCenterQuad, InitialGridSource.PERIODICITY)))
        for (boundaries in listOf(emptyList(), listOf(q))) for (input in listOf(image, inverse)) {
            val result = assertNotNull(InitialGridEstimator.estimate(input, boundaries))
            assertEquals(4, result.version)
            // Without Finder evidence the grid is offered for review, never as confirmed roles or polarity.
            assertTrue(result.autoAssignedFinders.isEmpty())
            assertEquals(FinderPolarity.UNKNOWN, result.confirmedPolarity)
        }
    }

    @Test fun fallbackPolarityAgreementAllowsSmallCornerDifferencesWithoutConfirmingPolarity() {
        val q = ImageQuad(PointD(30.0, 30.0), PointD(310.0, 30.0), PointD(310.0, 310.0), PointD(30.0, 310.0))
        val a = InitialGridEstimate(3, q, InitialGridSource.PERIODICITY)
        val b = a.copy(moduleCenterQuad = shift(q, 1.0))
        val result = assertNotNull(InitialGridEstimator.mergePolarityEstimates(a, b))
        assertEquals(result, InitialGridEstimator.mergePolarityEstimates(b, a))
        assertEquals(a, result)
        assertEquals(FinderPolarity.UNKNOWN, result.confirmedPolarity)
        assertNull(InitialGridEstimator.mergePolarityEstimates(null, null))
    }

    @Test fun conflictingFallbackVersionsCornersOrientationsAndVerifiedPolaritiesAreRejected() {
        val q = ImageQuad(PointD(30.0, 30.0), PointD(310.0, 30.0), PointD(310.0, 310.0), PointD(30.0, 310.0))
        val a = InitialGridEstimate(3, q, InitialGridSource.PERIODICITY)
        val p = q.asList()
        for (b in listOf(a.copy(version = 4), a.copy(moduleCenterQuad = shift(q, 3.0)),
            a.copy(moduleCenterQuad = ImageQuad(p[1], p[2], p[3], p[0])))) {
            assertNull(InitialGridEstimator.mergePolarityEstimates(a, b))
            assertNull(InitialGridEstimator.mergePolarityEstimates(b, a))
        }
        val verified = a.copy(source = InitialGridSource.TWO_FINDER,
            autoAssignedFinders = mapOf(FinderRole.TOP_RIGHT to PointD(280.0, 60.0),
                FinderRole.BOTTOM_LEFT to PointD(60.0, 280.0)))
        assertNull(InitialGridEstimator.mergePolarityEstimates(verified, verified))
    }

    private fun shift(q: ImageQuad, dx: Double): ImageQuad = q.asList().map { PointD(it.x + dx, it.y) }
        .let { ImageQuad(it[0], it[1], it[2], it[3]) }

    @Test fun duplicateBoundariesPreserveAutomaticRolesAcrossVersionsAndRotations() {
        val p = perspective.asList()
        for (version in listOf(1, 3, 7)) for (r in 0..3) {
            val logical = ImageQuad(p[r], p[(r + 1) % 4], p[(r + 2) % 4], p[(r + 3) % 4])
            val image = render(version, logical)
            val shifted = shift(perspective, .5)
            val candidates = listOf(perspective, shifted, perspective)
            val result = assertNotNull(InitialGridEstimator.estimate(image, candidates))
            assertEquals(InitialGridSource.ZXING_CPP, result.source)
            assertEquals(version, result.version)
            assertEquals(FinderRole.entries.toSet(), result.autoAssignedFinders.keys)
            assertEquals(result, InitialGridEstimator.estimate(image, candidates.reversed()))
            val expected = assertNotNull(InitialGridEstimator.fromBoundary(image, perspective))
            assertEquals(expected.autoAssignedFinders, result.autoAssignedFinders)
        }
    }

    @Test fun differentStartingCornersAreNormalizedBeforeMerging() {
        val p = perspective.asList()
        val reordered = ImageQuad(p[1], p[2], p[3], p[0])
        val result = assertNotNull(InitialGridEstimator.estimate(render(3, perspective), listOf(reordered, perspective)))
        assertEquals(InitialGridSource.ZXING_CPP, result.source)
        assertEquals(3, result.autoAssignedFinders.size)
    }

    @Test fun multipleNativeSymbolsDoNotSilentlySelectTheFirst() {
        val a = ImageQuad(PointD(20.0, 20.0), PointD(165.0, 20.0), PointD(165.0, 165.0), PointD(20.0, 165.0))
        val b = shift(a, 200.0)
        val ia = render(3, a); val ib = render(3, b)
        val image = LumaImage(400, 400, IntArray(160000) { minOf(ia.pixels[it], ib.pixels[it]) })
        assertNotNull(InitialGridEstimator.fromBoundary(image, a))
        assertNotNull(InitialGridEstimator.fromBoundary(image, b))
        for (candidates in listOf(listOf(a, b), listOf(a, a, b), listOf(b, a, a))) {
            val result = InitialGridEstimator.estimate(image, candidates)
            assertTrue(result == null || result.source == InitialGridSource.PERIODICITY)
            assertTrue(result?.autoAssignedFinders.isNullOrEmpty())
        }
    }

    @Test fun dimensionOrientationOutliersAndShiftChainsDoNotMerge() {
        val q = ImageQuad(PointD(30.0, 30.0), PointD(310.0, 30.0), PointD(310.0, 310.0), PointD(30.0, 310.0))
        val a = InitialGridEstimate(3, q, InitialGridSource.ZXING_CPP)
        val p = q.asList()
        assertNull(InitialGridEstimator.mergeNativeEstimates(listOf(a, a.copy(version = 4))))
        assertNull(InitialGridEstimator.mergeNativeEstimates(listOf(a, a.copy(moduleCenterQuad = ImageQuad(p[1], p[2], p[3], p[0])))))
        val outlier = a.copy(moduleCenterQuad = q.copy(bottomRight = PointD(313.0, 310.0)))
        assertNull(InitialGridEstimator.mergeNativeEstimates(listOf(a, outlier)))
        val b = a.copy(moduleCenterQuad = shift(q, 2.0))
        val c = a.copy(moduleCenterQuad = shift(q, 4.0))
        assertNotNull(InitialGridEstimator.mergeNativeEstimates(listOf(a, b)))
        assertNotNull(InitialGridEstimator.mergeNativeEstimates(listOf(b, c)))
        assertNull(InitialGridEstimator.mergeNativeEstimates(listOf(a, b, c)))
    }

    @Test fun duplicateWeakCandidatesDoNotGainConfidenceFromRepetition() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val shifted = shift(q, 2.0)
        val result = assertNotNull(InitialGridEstimator.estimate(render(4, q), listOf(shifted, shifted)))
        assertEquals(InitialGridSource.ZXING_CPP, result.source)
        assertTrue(result.autoAssignedFinders.isEmpty())
    }

    @Test fun unverifiedExtraBoundaryIsNotSilentlyDiscarded() {
        val image = render(3, perspective)
        val invalid = perspective.copy(topLeft = PointD(Double.NaN, 0.0))
        val result = InitialGridEstimator.estimate(image, listOf(perspective, invalid))
        assertTrue(result == null || result.source == InitialGridSource.PERIODICITY)
        assertTrue(result?.autoAssignedFinders.isNullOrEmpty())
    }

    @Test fun invalidBoundariesCannotBreakImport() {
        val image = render(3, perspective)
        val p = perspective.asList()
        for (q in listOf(
            perspective.copy(topLeft = PointD(Double.NaN, 0.0)),
            perspective.copy(topLeft = PointD(-20.0, 0.0)),
            ImageQuad(p[0], p[2], p[1], p[3]),
            ImageQuad(p[0], p[0], p[0], p[0]),
            ImageQuad(p[0], p[3], p[2], p[1]),
        )) assertNull(InitialGridEstimator.fromBoundary(image, q))
    }

    @Test fun flatImageIsNotAcceptedEvenWithNativeBoundary() {
        assertNull(InitialGridEstimator.estimate(LumaImage(400, 400, IntArray(160000) { 245 }), listOf(perspective)))
    }
    @Test fun autoAssignmentUsesLogicalRolesUnderAllFourImageRotations() {
        val p = perspective.asList()
        for (version in listOf(1, 3, 7)) for (r in 0..3) {
            val logical = ImageQuad(p[r], p[(r + 1) % 4], p[(r + 2) % 4], p[(r + 3) % 4])
            val result = assertNotNull(InitialGridEstimator.estimate(render(version, logical), listOf(perspective)))
            assertEquals(FinderRole.entries.toSet(), result.autoAssignedFinders.keys, "V$version rotation $r")
            val n = 17 + 4 * version
            val h = Homography.fromCorrespondences(listOf(PointD(0.0, 0.0), PointD(n.toDouble(), 0.0),
                PointD(n.toDouble(), n.toDouble()), PointD(0.0, n.toDouble())), logical.asList())
            val expected = mapOf(FinderRole.TOP_LEFT to h.map(3.5, 3.5),
                FinderRole.TOP_RIGHT to h.map(n - 3.5, 3.5), FinderRole.BOTTOM_LEFT to h.map(3.5, n - 3.5))
            for ((role, point) in expected) assertTrue(point.distanceTo(result.autoAssignedFinders.getValue(role)) < 1e-7)
        }
    }

    @Test fun damagedFinderCanKeepGridProposalWithoutAutoAssignment() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val image = render(4, q)
        // Damage three black cells of just one Finder, leaving the remaining evidence intact.
        for (mx in 0..2) for (y in 28..34) for (x in 28 + mx * 7..34 + mx * 7) image.pixels[y * 400 + x] = 245
        val result = assertNotNull(InitialGridEstimator.estimate(image, listOf(q)))
        assertEquals(InitialGridSource.ZXING_CPP, result.source)
        assertTrue(result.autoAssignedFinders.isEmpty())
    }

    @Test fun symmetricFinderAndTimingEvidencePreventsAutoAssignment() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val image = render(4, q)
        for (my in 0..6) for (mx in 0..6) {
            val black = mx == 0 || my == 0 || mx == 6 || my == 6 || (mx in 2..4 && my in 2..4)
            for (dy in 0..6) for (dx in 0..6) image.pixels[(28 + (26 + my) * 7 + dy) * 400 + 28 + (26 + mx) * 7 + dx] = if (black) 12 else 245
        }
        // Mirror the timing tracks too, so all four logical rotations have equal evidence.
        for (i in 8..24) for ((mx, my) in listOf(26 to i, i to 26)) {
            for (dy in 0..6) for (dx in 0..6) image.pixels[(28 + my * 7 + dy) * 400 + 28 + mx * 7 + dx] = if (i % 2 == 0) 12 else 245
        }
        val result = InitialGridEstimator.estimate(image, listOf(q))
        assertTrue(result?.autoAssignedFinders.isNullOrEmpty())
    }

    @Test fun centerOnlyAgreementDoesNotAutoAssignAShiftedGrid() {
        val q = ImageQuad(PointD(28.0, 28.0), PointD(259.0, 28.0), PointD(259.0, 259.0), PointD(28.0, 259.0))
        val shifted = q.asList().map { PointD(it.x + 2.0, it.y) }
        val result = assertNotNull(InitialGridEstimator.fromBoundary(render(4, q),
            ImageQuad(shifted[0], shifted[1], shifted[2], shifted[3])))
        assertEquals(4, result.version)
        assertTrue(result.autoAssignedFinders.isEmpty())
    }

    @Test fun cropMarginDoesNotChangeTheEstimatedGrid() {
        val unit = 6
        for (version in listOf(2, 3, 5)) {
            val span = (17 + 4 * version) * unit
            // From a crop that removed the quiet zone to one with a wide clean margin.
            for (modules in listOf(0, 1, 2, 4, 8, 16, 32)) {
                val margin = modules * unit
                val size = span + 2 * margin
                val a = margin.toDouble(); val b = a + span
                val image = render(version, ImageQuad(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b)), size = size)
                val result = assertNotNull(InitialGridEstimator.estimate(image, emptyList()), "V$version margin=$modules")
                assertEquals(version, result.version, "V$version margin=$modules")
                val expected = listOf(PointD(a + unit / 2.0, a + unit / 2.0), PointD(b - unit / 2.0, a + unit / 2.0),
                    PointD(b - unit / 2.0, b - unit / 2.0), PointD(a + unit / 2.0, b - unit / 2.0))
                for ((actual, center) in result.moduleCenterQuad.asList().zip(expected))
                    assertTrue(actual.distanceTo(center) < unit * .5, "V$version margin=$modules $actual $center")
            }
        }
    }

    @Test fun lightCropMarginIsNotReadAsAnInvertedGrid() {
        val unit = 6
        val span = 29 * unit
        for (modules in listOf(4, 12, 40)) {
            val margin = modules * unit
            val size = span + 2 * margin
            val a = margin.toDouble(); val b = a + span
            val image = render(3, ImageQuad(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b)), size = size)
            val inverse = LumaImage(size, size, IntArray(size * size) { 255 - image.pixels[it] })
            // In the opposite polarity the light margin is a uniform dark surround, not a larger symbol.
            GridPeriodicityEstimator.estimate(inverse)?.let { alias ->
                assertTrue(InitialGridEstimator.hasUniformPerimeter(inverse,
                    InitialGridEstimate(alias.version, alias.moduleCenterQuad, InitialGridSource.PERIODICITY)), "margin=$modules")
                assertTrue(!PeriodicityStructure.hasTimingSupport(inverse,
                    InitialGridEstimate(alias.version, alias.moduleCenterQuad, InitialGridSource.PERIODICITY)), "margin=$modules")
            }
            val genuine = assertNotNull(GridPeriodicityEstimator.estimate(image))
            assertTrue(!InitialGridEstimator.hasUniformPerimeter(image,
                InitialGridEstimate(genuine.version, genuine.moduleCenterQuad, InitialGridSource.PERIODICITY)))
        }
    }

}
