package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** UI-thread state; workers only read the current immutable request for cancellation. */
internal class DistortionPreviewState {
    data class Input(val image: LumaImage, val base: GridGeometry, val profile: SamplingProfile)
    class Request(val input: Input)
    data class Calculation(val candidates: List<GridGeometry>, val memoryLimited: Boolean = false)
    @Volatile private var current: Request? = null
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
    // The profile on the request ranks newly generated candidates once. Subsequent cell
    // classifications use their own current profile on the same selected geometry.
    fun matches(input: Input): Boolean = current?.input?.let {
        it.image === input.image && it.base == input.base && it.profile.inverted == input.profile.inverted
    } == true
    fun invalidate() {
        current = null
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
        return true
    }
    fun select(index: Int) {
        require(index in candidates.indices)
        selectedIndex = index
    }

    fun calculate(request: Request): Calculation {
        val (image, base, profile) = request.input
        val detector = CurvedGridDetector(image, profile, stopRequested = { !isCurrent(request) })
        val candidates = detector.candidates(base).distinct().sortedBy { quickGeometryScore(image, it, profile) }
        return Calculation(candidates, detector.contourMemoryLimited)
    }
}
