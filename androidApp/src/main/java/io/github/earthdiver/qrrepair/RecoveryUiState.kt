package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.AutoRecoveryResult
import io.github.earthdiver.qrrepair.core.CellConfidence
import io.github.earthdiver.qrrepair.core.CellRecommendation
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleRepairTrace
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.RecoverySearchDiagnostic

/**
 * Mutable state for one displayed recovery attempt.
 *
 * Keeping these values together makes transitions explicit and prevents an old candidate,
 * inspector matrix, or recommendation from leaking into a newly selected geometry.
 */
internal class RecoveryUiState {
    var autoResult: AutoRecoveryResult? = null
    var attemptIndex: Int = -1
    var geometry: GridGeometry? = null
    var sourceMatrix: QrMatrix? = null
    var workingMatrix: QrMatrix? = null
    var inspectorMatrix: QrMatrix? = null
    var traces: Map<Coord, ModuleRepairTrace> = emptyMap()
    var selectedRecovery: RecoveryCandidate? = null
    var recoveries: List<RecoveryCandidate> = emptyList()
    var failure: RecoveryFailure? = null
    var recoveryIndex: Int = -1
    var confidenceMap: Map<Coord, CellConfidence> = emptyMap()
    var recommendation: CellRecommendation? = null
    val searchDiagnostic: RecoverySearchDiagnostic?
        get() = if (autoResult != null) autoResult?.searchDiagnostic
            else if (recoveries.isNotEmpty()) selectedRecovery?.searchDiagnostic ?: recoveries.firstOrNull()?.searchDiagnostic
            else failure?.searchDiagnostic

    fun clear() {
        autoResult = null
        attemptIndex = -1
        geometry = null
        sourceMatrix = null
        workingMatrix = null
        inspectorMatrix = null
        traces = emptyMap()
        selectedRecovery = null
        recoveries = emptyList()
        failure = null
        recoveryIndex = -1
        confidenceMap = emptyMap()
        recommendation = null
    }

    fun clearAttempt() {
        geometry = null
        sourceMatrix = null
        workingMatrix = null
        inspectorMatrix = null
        traces = emptyMap()
        selectedRecovery = null
        recoveries = emptyList()
        failure = null
        recoveryIndex = -1
        confidenceMap = emptyMap()
        recommendation = null
    }

    fun selectRecovery(index: Int) {
        recoveryIndex = index
        selectedRecovery = recoveries.getOrNull(index)
        inspectorMatrix = null
        traces = emptyMap()
        confidenceMap = emptyMap()
        recommendation = null
    }

    fun showAttempt(
        index: Int,
        geometry: GridGeometry,
        matrix: QrMatrix,
        candidates: List<RecoveryCandidate>,
        attemptFailure: RecoveryFailure?,
        preferredIndex: Int,
    ) {
        attemptIndex = index
        this.geometry = geometry
        sourceMatrix = matrix.copy()
        workingMatrix = matrix.copy()
        recoveries = candidates
        failure = attemptFailure
        selectRecovery(preferredIndex)
    }

    fun restoreAttempt(geometry: GridGeometry, source: QrMatrix, working: QrMatrix) {
        autoResult = null
        clearAttempt()
        this.geometry = geometry
        attemptIndex = 0
        sourceMatrix = source
        workingMatrix = working
    }

    fun updateRecoveries(
        candidates: List<RecoveryCandidate>,
        preferredIndex: Int,
        attemptFailure: RecoveryFailure?,
    ) {
        recoveries = candidates
        failure = attemptFailure
        selectRecovery(preferredIndex)
    }
}
