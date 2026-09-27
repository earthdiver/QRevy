package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class RecoveryRetentionTest {
    @Test fun version40AmbiguityBoundsRetainedDetailsWithoutStoppingTheSearchWindow() {
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
        val budget = RecoveryRetentionBudget(4L * 1024 * 1024)
        val results = RecoveryEngine().recover(matrix, maxKnownDataEccMismatches = 0, retentionBudget = budget)
        assertTrue(results.isNotEmpty())
        assertTrue(results.size < 4096)
        assertTrue(budget.retainedBytes <= budget.limitBytes)
        assertTrue(results.all { it.payloadParsed && it.searchDiagnostic?.candidatesOmitted == true })
        assertTrue(results.all { it.searchDiagnostic?.completeness == SearchCompleteness.COMPLETE })
        val result = results.first()
        assertTrue(result.repairInfo is DeferredRepairInfo)
        assertEquals(3706, result.repairInfo.size)
        assertEquals(result.repairInfo[0], result.repairInfo[0])
        assertNotSame(result.repairInfo[0], result.repairInfo[0])
        assertEquals(erased, matrix.erasureCount())
    }

    @Test fun emptyRetentionBudgetReportsOmittedValidatedResultWithoutClaimingUnsearchedBranches() {
        val qr = Encoder.encode("hello", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, retentionBudget = RecoveryRetentionBudget(0))
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.acceptedCandidate)
        assertTrue(outcome.searchDiagnostic.candidatesOmitted)
        assertFalse(outcome.searchDiagnostic.unsearched)
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

    @Test fun laterBetterDistinctCandidatesReplaceEarlierCandidatesAtTheCountLimit() {
        val prototype = candidate()
        val budget = RecoveryRetentionBudget(maxCandidates = 256)
        val set = BoundedRecoveryCandidates(budget)
        repeat(300) { index ->
            set.add(prototype.copy(correctedInterleaved = intArrayOf(index),
                knownBitMismatchesAfterCorrection = 300 - index))
        }
        val results = set.results()
        assertEquals(256, results.size)
        assertEquals(299, results.first().correctedInterleaved.single())
        assertEquals(44, results.last().correctedInterleaved.single())
        assertTrue(set.candidatesOmitted)
        assertTrue(budget.retainedBytes <= budget.limitBytes)
        set.clear()
        assertEquals(0L, budget.retainedBytes)
    }

    @Test fun replacementAndFailedAdmissionPreserveDiscoveryOrderForEqualRanks() {
        val prototype = candidate()
        val budget = RecoveryRetentionBudget(limitBytes = 256 * 1024, maxCandidates = 2)
        val set = BoundedRecoveryCandidates(budget)
        val first = prototype.copy(correctedInterleaved = intArrayOf(1))
        val second = prototype.copy(correctedInterleaved = intArrayOf(2))
        set.add(first)
        set.add(second)
        set.add(prototype.copy(correctedInterleaved = intArrayOf(3), minRsMargin = 100,
            dataBytes = ByteArray(256 * 1024)))
        assertEquals(listOf(1, 2), set.results().map { it.correctedInterleaved.single() })
        set.add(prototype.copy(correctedInterleaved = intArrayOf(4), minRsMargin = 100))
        assertEquals(listOf(4, 1), set.results().map { it.correctedInterleaved.single() })
    }

    @Test fun equivalentRoutesKeepTheBestExplanationAndReleaseDiscardedDetail() {
        val prototype = candidate()
        val budget = RecoveryRetentionBudget(maxAlternativeDerivations = 2)
        val set = BoundedRecoveryCandidates(budget)
        repeat(12) { index -> set.add(prototype.copy(
            correctedInterleaved = prototype.correctedInterleaved.copyOf(), minRsMargin = index)) }
        val result = set.results().single()
        assertEquals(11, result.minRsMargin)
        assertEquals(listOf(10, 9), result.alternativeDerivations.map { it.minRsMargin })
        assertTrue(result.derivationsOmitted)
        assertTrue(set.derivationsOmitted)
        val expected = budget.fresh()
        assertTrue(expected.tryRetain(result))
        assertEquals(expected.retainedBytes, budget.retainedBytes)
        budget.releaseCandidate(result.copy(text = "annotation"))
        assertEquals(0L, budget.retainedBytes)
    }

    @Test fun replacingRepresentativeDoesNotPinTheDiscardedCodewordArrayInItsIdentityKey() {
        val prototype = candidate()
        val set = BoundedRecoveryCandidates(RecoveryRetentionBudget(maxAlternativeDerivations = 0))
        set.add(prototype)
        val replacement = prototype.copy(correctedInterleaved = prototype.correctedInterleaved.copyOf(),
            minRsMargin = prototype.minRsMargin + 1)
        set.add(replacement)
        assertSame(replacement.correctedInterleaved, set.results().single().correctedInterleaved)
        // Inspect the retained reference deterministically; a forced-GC assertion is unreliable.
        val entriesField = BoundedRecoveryCandidates::class.java.getDeclaredField("entries").apply { isAccessible = true }
        val identity = (entriesField.get(set) as Map<*, *>).keys.single() as RecoveryIdentity
        val arrayField = CodewordIdentity::class.java.getDeclaredField("codewords").apply { isAccessible = true }
        assertSame(replacement.correctedInterleaved, arrayField.get(identity.codewords))
    }

    @Test fun failedAtomicAdmissionPreservesPreviouslyRetainedSharedRoutes() {
        val prototype = candidate()
        val budget = RecoveryRetentionBudget()
        assertTrue(budget.tryRetain(prototype))
        val before = budget.retainedBytes
        val freshRoute = prototype.copy(correctedInterleaved = prototype.correctedInterleaved.copyOf())
        assertFalse(budget.tryRetain(prototype.copy(alternativeDerivations = listOf(freshRoute)), before))
        assertEquals(before, budget.retainedBytes)
        budget.releaseCandidate(prototype)
        assertEquals(0L, budget.retainedBytes)
    }

    @Test fun sharedCorrectedArrayDoesNotHideADifferentExtractionContextOrMask() {
        val prototype = candidate()
        val otherContext = prototype.copy(extraction = prototype.extraction.copy())
        val otherMask = prototype.copy(dataMask = (prototype.dataMask + 1) % 8)
        val budget = RecoveryRetentionBudget()
        assertTrue(budget.tryRetain(prototype))
        val originalBytes = budget.retainedBytes
        assertTrue(budget.tryRetain(otherContext))
        assertTrue(budget.tryRetain(otherMask))
        assertTrue(budget.retainedBytes > originalBytes)
        budget.releaseCandidate(otherContext)
        budget.releaseCandidate(otherMask)
        assertEquals(originalBytes, budget.retainedBytes)
        budget.releaseCandidate(prototype)
        assertEquals(0L, budget.retainedBytes)
    }

    @Test fun workingAllowanceStopsBeforeMaterializingAnUnaffordableBlockList() {
        val qr = Encoder.encode("hello", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, retentionBudget = RecoveryRetentionBudget(workingLimitBytes = 0))
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.unsearched)
        assertTrue(CombinationSearchStop.WORKING_MEMORY_LIMIT in outcome.failure!!.combinationSearch.stops)
        assertFalse(outcome.searchDiagnostic.candidatesOmitted)
    }

    @Test fun version40AmbiguityInEveryBlockCanProduceResultsWithinA128MiBWorkingAllowance() {
        val matrix = allBlocksAmbiguousVersion40()
        val engine = RecoveryEngine()
        val candidates = engine.recover(matrix, maxKnownDataEccMismatches = 0,
            retentionBudget = RecoveryRetentionBudget(16L * 1024 * 1024, workingLimitBytes = 128L * 1024 * 1024))
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.isSelectableRecovery })
        assertTrue(candidates.size <= 256)
    }

    @Test fun version40AmbiguityInEveryBlockReportsInsufficientWorkingAllowance() {
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(allBlocksAmbiguousVersion40(), maxKnownDataEccMismatches = 0,
            retentionBudget = RecoveryRetentionBudget(workingLimitBytes = 1024 * 1024))
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.searchDiagnostic.unsearched)
        assertTrue(CombinationSearchStop.WORKING_MEMORY_LIMIT in outcome.failure!!.combinationSearch.stops)
    }

    @Test fun version40ComfortableCorrectionsInEveryBlockDoNotReserveUnusedPartialSearchLists() {
        for (includeObservedErrors in listOf(false, true)) {
            val engine = RecoveryEngine()
            val outcome = engine.recoverWithDiagnostics(allBlocksAmbiguousVersion40(
                eraseEcc = false, includeObservedErrors = includeObservedErrors))
            val candidates = outcome.candidates
            assertEquals("a".repeat(2953), candidates.first().text)
            assertTrue(candidates.all { CombinationSearchStop.WORKING_MEMORY_LIMIT !in it.combinationSearch.stops })
            assertFalse(outcome.searchDiagnostic.unsearched)
        }
    }

    private fun allBlocksAmbiguousVersion40(eraseEcc: Boolean = true, includeObservedErrors: Boolean = false): QrMatrix {
        val qr = Encoder.encode("a".repeat(2953), ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 40))
        val matrix = QrMatrix(177).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 40, qr.maskPattern)
        val de = QrBlockInterleaver.deinterleave(extraction, 40, qr.ecLevel)
        de.rawLocations.forEachIndexed { raw, location ->
            val loc = requireNotNull(location)
            val bits = when {
                eraseEcc && loc.localIndex >= de.blocks[loc.blockIndex].numDataCodewords -> 8
                loc.localIndex in 3..5 -> 4
                else -> 0
            }
            extraction.codewords[raw].coordinates.takeLast(bits).forEach {
                matrix.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
            }
            if (includeObservedErrors && loc.localIndex == 7) {
                val coord = extraction.codewords[raw].coordinates.first()
                matrix.set(coord.x, coord.y,
                    if (matrix[coord.x, coord.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                    1f, ModuleSource.IMAGE)
            }
        }
        return matrix
    }

    private fun candidate(): RecoveryCandidate {
        val qr = Encoder.encode("retention", ErrorCorrectionLevel.L)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension) set(x, y,
                if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return RecoveryEngine().recover(matrix).first()
    }
}
