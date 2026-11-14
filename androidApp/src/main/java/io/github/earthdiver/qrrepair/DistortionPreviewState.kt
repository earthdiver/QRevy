package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** UI-thread state; workers only read the current immutable request for cancellation. */
internal class DistortionPreviewState(private val lmOnly: Boolean = false) {
    data class Input(val image: LumaImage, val base: GridGeometry, val profile: SamplingProfile, val jointRefinement: Boolean = false)
    class Request(val input: Input)
    /** Geometry only: no source image, sampling observations, or worker state. */
    data class Snapshot(val base: GridGeometry, val inverted: Boolean, val jointRefinement: Boolean,
        val candidates: List<GridGeometry>, val selectedIndex: Int, val memoryLimited: Boolean,
        val refinedIndices: Set<Int> = emptySet())
    fun snapshot(): Snapshot? {
        val input = current?.input ?: return null
        if (!complete || failed || selected == null) return null
        return Snapshot(input.base, input.profile.inverted, input.jointRefinement,
            candidates.toList(), selectedIndex, memoryLimited, refinedIndices.toSet())
    }
    fun restoreCompleted(request: Request, snapshot: Snapshot): Boolean {
        val input = request.input
        if (!isCurrent(request) || input.base != snapshot.base || input.profile.inverted != snapshot.inverted ||
            input.jointRefinement != snapshot.jointRefinement || snapshot.selectedIndex !in snapshot.candidates.indices) return false
        candidates = snapshot.candidates.toList()
        selectedIndex = snapshot.selectedIndex
        memoryLimited = snapshot.memoryLimited
        refinedIndices.addAll(snapshot.refinedIndices)
        complete = true
        failed = false
        return true
    }
    data class Calculation(val candidates: List<GridGeometry>, val memoryLimited: Boolean = false)
    @Volatile private var current: Request? = null
    class RefinementRequest(val parent: Request, val index: Int, val geometry: GridGeometry,
        val candidates: List<GridGeometry>)
    data class RefinementCalculation(val geometry: GridGeometry, val order: List<Int>)
    @Volatile private var refinement: RefinementRequest? = null
    private val refinedIndices = mutableSetOf<Int>()
    val refinementReady: Boolean get() = current?.input?.jointRefinement != true || selectedIndex in refinedIndices

    fun enableJointRefinement(): Boolean {
        val request = current ?: return false
        if (!complete || selected == null) return false
        current = Request(request.input.copy(jointRefinement = true))
        refinement = null
        refinedIndices.clear()
        return true
    }
    fun requestSelectedRefinement(): RefinementRequest? {
        val request = current ?: return null
        if (!complete || !request.input.jointRefinement || refinementReady || refinement != null) return null
        return RefinementRequest(request, selectedIndex, selected ?: return null, candidates.toList()).also { refinement = it }
    }
    fun calculateRefinement(request: RefinementRequest): RefinementCalculation = request.parent.input.let { input ->
        val stopped = { refinement !== request || !isCurrent(request.parent) }
        val geometry = jointlyRefinedGeometry(input.image, request.geometry, input.profile, stopped) ?: request.geometry
        // Rank on the worker; keep old indices so selection and completed attempts follow their grids.
        val order = request.candidates.indices.map { index ->
            val candidate = if (index == request.index) geometry else request.candidates[index]
            index to geometryRankingUnknownCount(input.image, candidate, input.profile, stopped)
        }.sortedBy { it.second }.map { it.first }
        RefinementCalculation(geometry, order)
    }
    fun acceptRefinement(request: RefinementRequest, result: Result<RefinementCalculation>): Boolean {
        if (refinement !== request || !isCurrent(request.parent) || selectedIndex != request.index) return false
        val calculation = result.getOrNull()
        refinedIndices += request.index
        if (calculation != null) {
            val replaced = candidates.toMutableList().also { it[request.index] = calculation.geometry }
            val completed = calculation.order.mapIndexedNotNull { newIndex, oldIndex ->
                newIndex.takeIf { oldIndex in refinedIndices }
            }
            candidates = calculation.order.map { replaced[it] }
            selectedIndex = calculation.order.indexOf(request.index)
            refinedIndices.clear()
            refinedIndices.addAll(completed)
        }
        refinement = null
        return true
    }

    var candidates: List<GridGeometry> = emptyList()
        private set
    var selectedIndex = 0
        private set
    var complete = false
        private set
    var failed = false
        private set
    var memoryLimited = false
        private set
    val selected: GridGeometry? get() = candidates.getOrNull(selectedIndex)

    fun request(input: Input): Request? {
        if (matches(input)) return null
        invalidate()
        return Request(input).also { current = it }
    }
    fun isCurrent(request: Request): Boolean = current === request
    // Candidate ranking and re-ranking use the same request profile. Subsequent cell
    // classifications use their own current profile on the same selected geometry.
    fun matches(input: Input): Boolean = current?.input?.let {
        it.image === input.image && it.base == input.base && it.profile.inverted == input.profile.inverted &&
            it.jointRefinement == input.jointRefinement
    } == true
    fun invalidate() {
        current = null
        refinement = null
        refinedIndices.clear()
        candidates = emptyList()
        selectedIndex = 0
        complete = false
        failed = false
        memoryLimited = false
    }
    fun restore(request: Request, geometry: GridGeometry) {
        if (isCurrent(request)) candidates = listOf(geometry)
    }
    fun accept(request: Request, result: List<GridGeometry>, failed: Boolean = false, memoryLimited: Boolean = false): Boolean {
        if (!isCurrent(request)) return false
        this.failed = failed
        this.memoryLimited = memoryLimited
        val retained = selected
        candidates = (result + listOfNotNull(retained)).distinct().ifEmpty { listOf(request.input.base) }
        selectedIndex = candidates.indexOf(retained).coerceAtLeast(0)
        complete = true
        if (request.input.jointRefinement && result.isNotEmpty()) refinedIndices += 0
        return true
    }
    fun select(index: Int) {
        require(index in candidates.indices)
        if (selectedIndex != index) refinement = null
        selectedIndex = index
    }

    internal fun rankingUnknownCount(matrix: QrMatrix, version: Int): Int =
        imageUnknownCountForGeometryRanking(matrix, version)

    fun calculate(request: Request): Calculation {
        val (image, base, profile) = request.input
        if (lmOnly) {
            val fit = projectivePatternHypothesis(image, profile,
                listOf(GeometryHypothesis(emptyList(), base, quickGeometryScore(image, base, profile)))) { !isCurrent(request) }
            return Calculation(listOfNotNull(fit?.geometry))
        }
        val detector = CurvedGridDetector(image, profile, stopRequested = { !isCurrent(request) })
        val candidates = detector.candidates(base).distinct().sortedBy { quickGeometryScore(image, it, profile) }
            .map { geometry ->
                val observations = GridThresholdEstimator.observe(image, geometry, profile.inverted) { !isCurrent(request) }
                val matrix = MatrixSampler(image, profile).sampleObserved(geometry, profile, observations).first
                geometry to rankingUnknownCount(matrix, geometry.version)
            }.sortedBy { it.second }.map { it.first }
        if (!request.input.jointRefinement || !isCurrent(request)) return Calculation(candidates, detector.contourMemoryLimited)
        val initial = candidates.firstOrNull() ?: base
        val refined = jointlyRefinedGeometry(image, initial, profile) { !isCurrent(request) }
        // The best candidate remains first because replacement cannot increase its unknown count.
        val selected = if (refined == null) candidates else listOf(refined) + candidates.drop(1)
        return Calculation(selected.distinct(), detector.contourMemoryLimited)
    }
}
