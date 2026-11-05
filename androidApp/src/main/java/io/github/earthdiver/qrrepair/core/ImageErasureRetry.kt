package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

internal fun imageWordMargins(de: DeinterleavedQr, extraction: CodewordExtraction, matrix: QrMatrix): List<IntArray> {
    val margins = de.blocks.map { IntArray(it.codewords.size) { -1 } }
    extraction.codewords.forEachIndexed { raw, word ->
        if (!word.erased) {
            val location = requireNotNull(de.rawLocations[raw])
            margins[location.blockIndex][location.localIndex] = word.coordinates.minOf { coord ->
                matrix.imageMargin(coord.x, coord.y)
            }
        }
    }
    return margins
}

internal class ImageErasurePlan(margins: IntArray, room: Int) {
    val pool = if (room <= 0) emptyList() else margins.indices
        .filter { margins[it] >= 0 }
        .sortedWith(compareBy<Int> { margins[it] }.thenBy { it })
        .take(8)
    val trialSizes = buildList {
        if (pool.isEmpty()) return@buildList
        val odd = room % 2 != 0
        add(if (odd || pool.size == 1) 1 else 2)
        val extraSize = if (odd) 3 else 4
        if (extraSize <= room && extraSize <= pool.size) add(extraSize)
    }
    val trialCount: Int get() = trialSizes.sumOf { size ->
        (1..size).fold(1) { count, i -> count * (pool.size - i + 1) / i }
    }
}

internal fun retryImageErasures(
    block: RsBlockWork, ecc: Int, plan: ImageErasurePlan, original: ConventionalRsBlockDiagnostic,
    rs: ReedSolomonErasures, cancellation: RecoveryCancellation, cached: ReedSolomonErasures.Result? = null,
): List<PartialCodewordSolver.Result> {
    val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray()
    val results = mutableListOf<PartialCodewordSolver.Result>()
    if (original.status == ConventionalRsStatus.SUCCESS) {
        val result = cached ?: rs.decode(block.codewords, ecc, erased)
        results += PartialCodewordSolver.Result(result.corrected, result.errataPositions,
            0, 0, intArrayOf(), requireNotNull(original.margin))
    }
    fun attempt(extra: List<Int>) {
        cancellation.check()
        val positions = erased + extra.toIntArray()
        val result = try {
            rs.decode(block.codewords, ecc, positions)
        } catch (_: ReedSolomonErasures.DecodeException) {
            return
        }
        val errors = result.errataPositions.size - positions.size
        results.add(PartialCodewordSolver.Result(result.corrected, result.errataPositions, 0, 0, intArrayOf(),
            ecc - positions.size - 2 * errors, imageErasures = extra))
    }
    // Enumerate each subset once, trying smaller erasure counts first.
    for (size in plan.trialSizes) {
        val positions = IntArray(size)
        fun choose(remaining: Int, end: Int) {
            if (remaining == 0) {
                attempt(positions.toList())
                return
            }
            for (i in remaining - 1 until end) {
                positions[remaining - 1] = plan.pool[i]
                choose(remaining - 1, i)
            }
        }
        choose(size, plan.pool.size)
    }
    return PartialCodewordSolver.mergeCandidates(results)
}

internal fun imageErasureDescription(positions: List<Int>): String {
    val words = positions.joinToString { (it + 1).toString() }
    return localizedText("画像判定による一時消失と通常RS（ブロック内のコード語: $words）",
        "Conventional RS with temporary image erasures (codewords in block: $words)")
}
