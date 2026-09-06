package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairGuidanceTest {
    @Test
    fun noCandidateMeansNoRecommendedCell() {
        val observed = QrMatrix(21)
        val confidence = RepairGuidance.confidenceMap(observed, observed.copy(), emptyList(), emptyMap())

        assertEquals(null, RepairGuidance.recommendNextCell(observed, emptyList(), confidence))
    }

    @Test
    fun recommendation_prefers_balancedCandidateSplit() {
        val observed = QrMatrix(21)
        for (y in 0 until 21) for (x in 0 until 21) observed.set(x, y, ModuleValue.ZERO, 0.9f, ModuleSource.IMAGE)
        observed.set(10, 10, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        observed.set(11, 10, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)

        val candidates = List(4) { i ->
            observed.copy().apply {
                set(10, 10, if (i < 2) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.RECOVERED)
                set(11, 10, if (i == 0) ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.RECOVERED)
            }
        }
        val confidence = RepairGuidance.confidenceMap(observed, candidates[0], candidates, emptyMap())
        assertTrue(confidence.getValue(Coord(10, 10)).detailJa.contains("候補一致: 2 / 比較対象4候補（検証済み全4候補）"))
        val recommendation = RepairGuidance.recommendNextCell(observed, candidates, confidence)
        assertNotNull(recommendation)
        assertEquals(Coord(10, 10), recommendation!!.coord)
        assertEquals(2, recommendation.zeroCandidates)
        assertEquals(2, recommendation.oneCandidates)
        assertTrue(recommendation.informationGainBits > 0.99)
    }

    @Test
    fun manuallyEditedCell_isHighConfidenceAndNotRecommended() {
        val source = QrMatrix(21)
        val after = QrMatrix(21)
        source.set(8, 8, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        after.set(8, 8, ModuleValue.ONE, 1f, ModuleSource.USER)
        val confidence = RepairGuidance.confidenceMap(source, after, listOf(after), emptyMap())
        assertEquals(1f, confidence.getValue(Coord(8, 8)).score)
        val recommendation = RepairGuidance.recommendNextCell(source, listOf(after), confidence)
        assertTrue(recommendation?.coord != Coord(8, 8))
    }

    @Test
    fun finderAndOtherFunctionModulesAreNeverRecommended() {
        val observed = QrMatrix(21)
        val candidate = observed.copy()
        val confidence = RepairGuidance.confidenceMap(observed, candidate, listOf(candidate), emptyMap())

        val recommendation = RepairGuidance.recommendNextCell(observed, listOf(candidate), confidence)

        assertNotNull(recommendation)
        assertTrue(recommendation!!.coord != Coord(0, 0))
        assertTrue(!QrFunctionMask.isFunction(1, recommendation.coord.x, recommendation.coord.y))
        val codewordCoordinates = CodewordExtractor.extract(observed, 1, 0).codewords
            .flatMapTo(hashSetOf()) { it.coordinates }
        assertTrue(recommendation.coord in codewordCoordinates)
    }
}
