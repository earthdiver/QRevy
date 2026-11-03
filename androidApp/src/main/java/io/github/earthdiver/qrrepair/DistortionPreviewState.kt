package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** UI-thread state; workers only read the current immutable request for cancellation. */
internal class DistortionPreviewState {
    data class Input(val image: LumaImage, val base: GridGeometry, val profile: SamplingProfile)
    class Request(val input: Input)
    @Volatile private var current: Request? = null
    var candidates: List<GridGeometry> = emptyList()
        private set
    var selectedIndex = 0
        private set
    var complete = false
        private set
    var failed = false
        private set
    val selected: GridGeometry? get() = candidates.getOrNull(selectedIndex)

    fun request(input: Input): Request? {
        if (current?.input == input) return null
        invalidate()
        return Request(input).also { current = it }
    }
    fun isCurrent(request: Request): Boolean = current === request
    fun matches(input: Input): Boolean = current?.input == input
    fun invalidate() {
        current = null
        candidates = emptyList()
        selectedIndex = 0
        complete = false
        failed = false
    }
    fun restore(request: Request, geometry: GridGeometry) {
        if (isCurrent(request)) candidates = listOf(geometry)
    }
    fun accept(request: Request, result: List<GridGeometry>, failed: Boolean = false): Boolean {
        if (!isCurrent(request)) return false
        this.failed = failed
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

    fun calculate(request: Request): List<GridGeometry> {
        val (image, base, profile) = request.input
        return CurvedGridDetector(image, profile, stopRequested = { !isCurrent(request) })
            .candidates(base).distinct().sortedBy { quickGeometryScore(image, it, profile) }
    }
}
