package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryWorkflowTest {
    private val image = LumaImage(100, 100, IntArray(10000) { 255 })
    private val settings = PreprocessSettings(version = 1, polarity = PolarityMode.AUTO,
        logicalGridAssigned = true, searchQuadNeighborhood = false,
        dataEccMismatchLimit = 0, manualQuad = ImageQuad(
            PointD(10.0, 10.0), PointD(90.0, 10.0), PointD(90.0, 90.0), PointD(10.0, 90.0)))

    @Test fun userInterruptionReturnsAnIncompleteResultInsteadOfFailure() {
        val result = RecoveryWorkflow(RecoveryEngine()).run(image, settings, RecoveryRetentionBudget(),
            stopRequested = { true })
        assertTrue(result.interrupted)
        assertTrue(result.searchDiagnostic!!.unsearched)
        assertTrue(result.searchDiagnostic!!.coverageComplete)
        assertEquals(setOf(SearchIncompleteReason.USER_INTERRUPTED), result.searchDiagnostic!!.incompleteReasons)
        assertEquals(0, result.attemptedGeometries)
    }

    @Test fun aLoweredLimitBlocksRepairWithoutDeletingMarks() {
        val coords = (0 until 5).map { Coord(it, 12) }.toSet()
        val overLimit = settings.copy(prioritySearchLimit = 4,
            preRecoveryEdits = coords.associateWith { ModuleValue.ERASED }, preRecoveryPrioritySearch = coords)
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryWorkflow(RecoveryEngine()).run(image, overLimit, RecoveryRetentionBudget())
        }
        assertEquals(coords, overLimit.preRecoveryPrioritySearch)
    }

    @Test fun bothPolaritiesContributeToTheSearchAndKeepTheSelectedVersion() {
        val progress = mutableListOf<RecoveryProgress>()
        val result = RecoveryWorkflow(RecoveryEngine()).run(image, settings, RecoveryRetentionBudget(),
            onProgress = progress::add)
        assertEquals(listOf(false, true), progress.map { it.profile.inverted }.distinct())
        assertEquals(listOf(0, 1), progress.map { it.variantIndex }.distinct())
        assertTrue(progress.all { it.variantCount == 2 && it.profile.settings.version == 1 })
        assertEquals(2, result.attemptedGeometries)
        assertNotNull(result.searchDiagnostic)
    }

    @Test fun cancellationDuringTheFirstProfileStopsTheWorkflowBeforeTheSecond() {
        var cancelled = false
        val progress = mutableListOf<RecoveryProgress>()
        assertThrows(RecoveryCancelledException::class.java) {
            RecoveryWorkflow(RecoveryEngine()).run(image, settings, RecoveryRetentionBudget(),
                cancellation = RecoveryCancellation { cancelled },
                onProgress = { progress += it; cancelled = true })
        }
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { it.variantIndex == 0 })
    }
    @Test fun lateStopAfterFinalProfileDoesNotChangeCompletedResult() {
        var stopped = false
        val result = RecoveryWorkflow(RecoveryEngine()).run(image, settings, RecoveryRetentionBudget(),
            stopRequested = { stopped }, onProgress = {
                if (it.variantIndex == it.variantCount - 1 && it.done == it.total) stopped = true
            })
        assertTrue(stopped)
        assertFalse(result.interrupted)
        assertEquals(SearchCompleteness.COMPLETE, result.searchDiagnostic!!.completeness)
    }

    @Test fun stoppingBetweenProfilesReportsTheUnperformedProfile() {
        var stopped = false
        val result = RecoveryWorkflow(RecoveryEngine()).run(image, settings, RecoveryRetentionBudget(),
            stopRequested = { stopped }, onProgress = { if (it.done == it.total) stopped = true })
        assertTrue(result.interrupted)
        assertEquals(1, result.attemptedGeometries)
        assertEquals(setOf(SearchIncompleteReason.USER_INTERRUPTED), result.searchDiagnostic!!.incompleteReasons)
    }

    @Test fun workflowPublishesDiscoveryBeforeTheGridFinishesAndCarriesItAcrossProfiles() {
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode("DISCOVERY", com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)
        val d = qr.matrix.width
        val width = (d + 8) * 8
        val pixels = IntArray(width * width) { i ->
            val x = i % width / 8 - 4
            val y = i / width / 8 - 4
            if (x in 0 until d && y in 0 until d && qr.matrix.get(x, y).toInt() == 1) 0 else 255
        }
        val start = 35.5
        val end = start + (d - 1) * 8
        val input = settings.copy(version = (d - 17) / 4,
            formatConstraint = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, qr.maskPattern),
            manualQuad = ImageQuad(PointD(start, start), PointD(end, start), PointD(end, end), PointD(start, end)))
        val events = mutableListOf<RecoveryDiscoveryCount>()
        var completedGrids = 0
        val counter = RecoveryDiscoveryCounter(onChanged = {
            assertEquals("Discovery must precede grid completion", 0, completedGrids)
            events += it
        })
        val result = RecoveryWorkflow(RecoveryEngine()).run(LumaImage(width, width, pixels), input,
            RecoveryRetentionBudget(), discoveryCounter = counter,
            onProgress = { if (it.done == it.total) completedGrids++ })
        assertEquals(2, result.attemptedGeometries)
        assertTrue(result.entries.any { it.candidate.text == "DISCOVERY" })
        assertEquals(listOf(RecoveryDiscoveryCount.Exact(1)), events)
        assertEquals(events.last(), result.discoveredCandidates)
    }

    @Test fun repeatedRepairReusesReadingsButAppliesCurrentAssumptionsAndFixedEdits() {
        val qr = com.google.zxing.qrcode.encoder.Encoder.encode("CACHE", com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)
        val d = qr.matrix.width
        val width = (d + 8) * 8
        val source = LumaImage(width, width, IntArray(width * width) { i ->
            val x = i % width / 8 - 4; val y = i / width / 8 - 4
            if (x in 0 until d && y in 0 until d && qr.matrix[x, y].toInt() == 1) 0 else 255
        })
        val first = 35.5; val last = first + (d - 1) * 8
        val original = settings.copy(polarity = PolarityMode.NORMAL, listDecodingAdditionalErrors = 0,
            formatConstraint = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, qr.maskPattern),
            manualQuad = ImageQuad(PointD(first, first), PointD(last, first), PointD(last, last), PointD(first, last)))
        var reads = 0
        val workflow = RecoveryWorkflow(RecoveryEngine(), RecoveryObservationCache { input, grid, inverted, stopped ->
            reads++; GridThresholdEstimator.observe(input, grid, inverted, stopped)
        })
        fun run(input: PreprocessSettings) = workflow.run(source, input, RecoveryRetentionBudget())
        assertEquals("CACHE", run(original).entries.single().candidate.text)
        val constrained = original.copy(assumeUtf8ByteSegments = true, trustKnownPayloadCount = true)
        val reused = run(constrained)
        val fresh = RecoveryWorkflow(RecoveryEngine()).run(source, constrained, RecoveryRetentionBudget())
        assertEquals(fresh.entries.map { it.candidate.identity() }, reused.entries.map { it.candidate.identity() })
        assertEquals(fresh.searchDiagnostic!!.formatOutcomes, reused.searchDiagnostic!!.formatOutcomes)
        assertEquals(fresh.searchDiagnostic!!.incompleteReasons, reused.searchDiagnostic!!.incompleteReasons)
        assertEquals(fresh.testedHypotheses, reused.testedHypotheses)
        val coord = QrCellRegions(1).codewordCoordinates.first()
        val wrong = if (qr.matrix[coord.x, coord.y].toInt() == 1) ModuleValue.ZERO else ModuleValue.ONE
        val blocked = run(constrained.copy(preRecoveryEdits = mapOf(coord to wrong), lockManualBits = true))
        assertTrue(blocked.entries.isEmpty())
        assertEquals(ModuleSource.USER, blocked.bestAttempt!!.matrix[coord.x, coord.y].source)
        assertEquals("CACHE", run(original).entries.single().candidate.text)
        assertEquals(1, reads)
    }

}
