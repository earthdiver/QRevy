package io.github.earthdiver.qrrepair.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryWorkflowTest {
    private val image = LumaImage(100, 100, IntArray(10000) { 255 })
    private val settings = PreprocessSettings(version = 1, polarity = PolarityMode.AUTO,
        logicalGridAssigned = true, searchQuadNeighborhood = false,
        dataEccMismatchLimit = 0, manualQuad = ImageQuad(
            PointD(10.0, 10.0), PointD(90.0, 10.0), PointD(90.0, 90.0), PointD(10.0, 90.0)))

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
}
