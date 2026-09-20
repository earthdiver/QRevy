package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.CodewordExtraction
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.AutoRecoveryResult
import io.github.earthdiver.qrrepair.core.RecoveryInputBlocker
import io.github.earthdiver.qrrepair.core.RecoverySearchDiagnostic
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryUiStateTest {
    @Test
    fun selectingRecoveryInvalidatesDerivedDisplayState() {
        val state = RecoveryUiState()
        val candidates = listOf(candidate(0), candidate(1))
        state.recoveries = candidates
        state.inspectorMatrix = QrMatrix(21)

        state.selectRecovery(1)

        assertEquals(1, state.recoveryIndex)
        assertEquals(candidates[1], state.selectedRecovery)
        assertNull(state.inspectorMatrix)
        assertEquals(emptyMap<Any, Any>(), state.traces)
        assertEquals(emptyMap<Any, Any>(), state.confidenceMap)
        assertNull(state.recommendation)
    }

    @Test
    fun restoringAttemptCannotRetainCandidateFromPreviousGeometry() {
        val state = RecoveryUiState().apply {
            recoveries = listOf(candidate(0))
            selectRecovery(0)
            autoResult = AutoRecoveryResult(emptyList(), emptyList(), searchDiagnostic =
                RecoverySearchDiagnostic(inputBlocker = RecoveryInputBlocker.ZERO_MISMATCH))
        }
        val source = QrMatrix(21)
        val working = QrMatrix(21)
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 12.0, 2.0, 0.0)

        state.restoreAttempt(geometry, source, working)

        assertEquals(0, state.attemptIndex)
        assertEquals(geometry, state.geometry)
        assertEquals(source, state.sourceMatrix)
        assertEquals(working, state.workingMatrix)
        assertEquals(emptyList<RecoveryCandidate>(), state.recoveries)
        assertNull(state.selectedRecovery)
        assertNull(state.autoResult)
        assertNull(state.searchDiagnostic)
        assertEquals(-1, state.recoveryIndex)
    }

    private fun candidate(mask: Int) = RecoveryCandidate(
        1, ErrorCorrectionLevel.M, mask, "decoded-$mask", byteArrayOf(),
        15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
    )
}
