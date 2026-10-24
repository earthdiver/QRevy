package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCount
import io.github.earthdiver.qrrepair.core.AutoRecoveryResult
import io.github.earthdiver.qrrepair.core.CellConfidence
import io.github.earthdiver.qrrepair.core.CellRecommendation
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleRepairTrace
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.SamplingProfile
import io.github.earthdiver.qrrepair.core.isSelectableRecovery
import io.github.earthdiver.qrrepair.core.RecoverySearchDiagnostic

/**
 * Mutable selection over retained results from every recovery attempt.
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
        set(value) {
            field = value
            selectableEntries = value.mapIndexedNotNull { index, candidate ->
                (index to candidate).takeIf { candidate.isSelectableRecovery }
            }
        }
    var selectableEntries: List<Pair<Int, RecoveryCandidate>> = emptyList()
        private set
    var samplingProfile: SamplingProfile? = null
        private set
    val candidatesOmitted: Boolean
        get() = autoResult?.candidatesTruncated == true || searchDiagnostic?.candidatesOmitted == true

    /** Consensus is meaningful only for candidates derived from the same sampled input. */
    val guidanceRecoveries: List<RecoveryCandidate>
        get() {
            val entries = autoResult?.entries ?: return recoveries
            val selected = entries.getOrNull(recoveryIndex) ?: return recoveries
            return entries.filter { it.attempt.matrix === selected.attempt.matrix }.map { it.candidate }
        }
    var failure: RecoveryFailure? = null
    var recoveryIndex: Int = -1
    var confidenceMap: Map<Coord, CellConfidence> = emptyMap()
    var recommendation: CellRecommendation? = null
    private var directDiscoveredCandidates: RecoveryDiscoveryCount? = null
    val discoveredCandidates: RecoveryDiscoveryCount?
        get() = autoResult?.discoveredCandidates ?: directDiscoveredCandidates
    private var directSearchDiagnostic: RecoverySearchDiagnostic? = null
    val searchDiagnostic: RecoverySearchDiagnostic?
        get() = if (autoResult != null) autoResult?.searchDiagnostic
            else if (directSearchDiagnostic != null) directSearchDiagnostic
            else if (recoveries.isNotEmpty()) selectedRecovery?.searchDiagnostic ?: recoveries.firstOrNull()?.searchDiagnostic
            else failure?.searchDiagnostic

    fun clear() {
        directSearchDiagnostic = null
        directDiscoveredCandidates = null
        autoResult = null
        attemptIndex = -1
        samplingProfile = null
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
        directSearchDiagnostic = null
        directDiscoveredCandidates = null
        samplingProfile = null
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
        val entries = autoResult?.entries.orEmpty()
        val origin = entries.getOrNull(index)?.takeIf { it.candidate === recoveries.getOrNull(index) }
        if (origin != null) {
            val previous = entries.getOrNull(recoveryIndex)
            if (sourceMatrix == null || previous?.attempt?.matrix !== origin.attempt.matrix) {
                sourceMatrix = origin.attempt.matrix.copy()
                workingMatrix = origin.attempt.matrix.copy()
            }
            geometry = origin.attempt.hypothesis.geometry
            attemptIndex = autoResult!!.attempts.indexOfFirst { it.matrix === origin.attempt.matrix }
            samplingProfile = origin.profile
            failure = origin.attempt.failure
        }
        recoveryIndex = index
        selectedRecovery = recoveries.getOrNull(index)
        inspectorMatrix = null
        traces = emptyMap()
        confidenceMap = emptyMap()
        recommendation = null
    }

    fun showResults(result: AutoRecoveryResult, preferredIndex: Int = 0) {
        clearAttempt()
        autoResult = result
        recoveries = result.entries.map { it.candidate }
        selectRecovery(preferredIndex.takeIf { it in recoveries.indices } ?: -1)
    }

    fun showAttempt(
        index: Int,
        geometry: GridGeometry,
        matrix: QrMatrix,
        candidates: List<RecoveryCandidate>,
        attemptFailure: RecoveryFailure?,
        preferredIndex: Int,
    ) {
        directSearchDiagnostic = null
        directDiscoveredCandidates = null
        attemptIndex = index
        samplingProfile = autoResult?.profile
        this.geometry = geometry
        sourceMatrix = matrix.copy()
        workingMatrix = matrix.copy()
        recoveries = candidates
        failure = attemptFailure
        selectRecovery(preferredIndex)
    }

    fun restoreAttempt(geometry: GridGeometry, source: QrMatrix, working: QrMatrix, profile: SamplingProfile? = null) {
        autoResult = null
        clearAttempt()
        this.geometry = geometry
        samplingProfile = profile
        attemptIndex = 0
        sourceMatrix = source
        workingMatrix = working
    }

    fun updateRecoveries(
        candidates: List<RecoveryCandidate>,
        preferredIndex: Int,
        attemptFailure: RecoveryFailure?,
        diagnostic: RecoverySearchDiagnostic? = null,
        discoveredCandidates: RecoveryDiscoveryCount? = null,
    ) {
        directSearchDiagnostic = diagnostic
        directDiscoveredCandidates = discoveredCandidates
        recoveries = candidates
        failure = attemptFailure
        selectRecovery(preferredIndex)
    }
}
