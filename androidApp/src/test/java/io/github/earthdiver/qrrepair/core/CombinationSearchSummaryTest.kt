package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class CombinationSearchSummaryTest {
    @Test fun exactBoundaryExhaustionIsNotTruncation() {
        var truncated = false
        assertEquals(listOf(0, 1), (0..1).asSequence()
            .takeInExpandingWindows(2, 4, { false }, { truncated = true }).toList())
        assertFalse(truncated)
    }

    @Test fun nodeLimitRetainsSuccessfulResultsAndReportsUnexploredCombinations() {
        val stops = linkedSetOf<CombinationSearchStop>()
        val results = bestFirstCartesianProductSequence(listOf(listOf(0, 1), listOf(0, 1)), { it },
            maxExpandedNodes = 2, onLimitReached = { stops += CombinationSearchStop.NODE_LIMIT }).toList()
        assertEquals(listOf(listOf(0, 0)), results)
        val summary = CombinationSearchSummary().afterPass(stops).afterPass(emptySet())
        assertEquals(2, summary.passes)
        assertEquals(setOf(CombinationSearchStop.NODE_LIMIT), summary.stops)
    }

    @Test fun successfulWindowRetainsTruncationNotification() {
        var stopped = false
        val results = (0..4).asSequence().takeInExpandingWindows(2, 4, { false }, { stopped = true }).toList()
        assertEquals(listOf(0, 1), results)
        assertTrue(stopped)
    }
    @Test fun recoveryPublishesSuccessfulWindowStatusAndResetsItForNextInput() {
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode("abcdefghijklmnopqrstuvwx",
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q,
            mapOf(com.google.zxing.EncodeHintType.QR_VERSION to 3))
        val source = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val matrix = source.copy()
        val extraction = CodewordExtractor.extract(matrix, 3, qr.maskPattern)
        val de = QrBlockInterleaver.deinterleave(extraction, 3, qr.ecLevel)
        assertEquals(2, de.blocks.size)
        de.rawLocations.forEachIndexed { raw, nullable ->
            val loc = requireNotNull(nullable)
            val count = when {
                loc.localIndex >= de.blocks[loc.blockIndex].numDataCodewords -> 8
                loc.blockIndex == 0 && loc.localIndex == 5 -> 7
                loc.blockIndex == 1 && loc.localIndex == 1 -> 6
                else -> 0
            }
            extraction.codewords[raw].coordinates.takeLast(count).forEach {
                matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        val engine = RecoveryEngine()
        val results = engine.recover(matrix, maxKnownDataEccMismatches = 0)
        assertEquals(4096, results.size)
        assertTrue(results.all { it.payloadParsed && CombinationSearchStop.SUCCESS_WINDOW in it.combinationSearch.stops })
        val clean = engine.recover(source, maxKnownDataEccMismatches = 0).single()
        assertTrue(clean.combinationSearch.stops.isEmpty())
        assertEquals(1, clean.combinationSearch.passes)
    }
}
