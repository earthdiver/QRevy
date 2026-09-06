package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryRetentionTest {
    @Test fun version40AmbiguityUsesDeferredExplanationsAndReportsRetentionLimit() {
        // Independent synthetic Byte payload fills data capacity; ambiguity in the last block
        // lets prefix traversal reach thousands of complete valid streams.
        val qr = Encoder.encode("a".repeat(2953), ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 40))
        val matrix = QrMatrix(177).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 40, qr.maskPattern)
        val de = QrBlockInterleaver.deinterleave(extraction, 40, qr.ecLevel)
        val last = de.blocks.lastIndex
        de.rawLocations.forEachIndexed { raw, location ->
            val loc = requireNotNull(location)
            val count = when {
                loc.blockIndex != last -> 0
                loc.localIndex >= de.blocks[last].numDataCodewords -> 8
                loc.localIndex < 3 -> 4
                else -> 0
            }
            extraction.codewords[raw].coordinates.takeLast(count).forEach {
                matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
        }
        val erased = matrix.erasureCount()
        val budget = RecoveryRetentionBudget(512 * 1024)
        val results = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 0, retentionBudget = budget)
        assertTrue(results.isNotEmpty())
        assertTrue(results.size < 4096)
        assertTrue(budget.exhausted)
        assertTrue(budget.retainedBytes <= budget.limitBytes)
        assertTrue(results.all { it.payloadParsed && CombinationSearchStop.RETENTION_LIMIT in it.combinationSearch.stops })
        val result = results.first()
        assertTrue(result.repairInfo is DeferredRepairInfo)
        assertEquals(3706, result.repairInfo.size)
        assertEquals(result.repairInfo[0], result.repairInfo[0])
        assertNotSame(result.repairInfo[0], result.repairInfo[0])
        assertEquals(erased, matrix.erasureCount())
    }

    @Test fun emptyRetentionBudgetReportsFailureWithoutPublishingUnvalidatedValues() {
        val qr = Encoder.encode("hello", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val engine = RecoveryEngine()
        assertTrue(engine.recover(matrix, retentionBudget = RecoveryRetentionBudget(0)).isEmpty())
        assertEquals(RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT, engine.lastFailure?.reason)
        assertTrue(CombinationSearchStop.RETENTION_LIMIT in engine.lastFailure!!.combinationSearch.stops)
    }

    @Test fun deferredExplanationsDoNotAllocateUntilRead() {
        var reads = 0
        val list = DeferredRepairInfo(3706) {
            reads++
            CodewordRepairInfo(it, 0, it, 0, 0, false, false)
        }
        assertEquals(3706, list.size)
        assertEquals(0, reads)
        assertEquals(3705, list.last().rawIndex)
        assertEquals(1, reads)
    }
}
