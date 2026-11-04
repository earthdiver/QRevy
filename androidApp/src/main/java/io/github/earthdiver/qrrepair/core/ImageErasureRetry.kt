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

internal fun retryImageErasures(
    block: RsBlockWork, ecc: Int, margins: IntArray, original: ConventionalRsBlockDiagnostic,
    rs: ReedSolomonErasures, cancellation: RecoveryCancellation, cached: ReedSolomonErasures.Result? = null,
): List<PartialCodewordSolver.Result> {
    val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray()
    if (original.status == ConventionalRsStatus.SUCCESS) {
        val result = cached ?: rs.decode(block.codewords, ecc, erased)
        return listOf(PartialCodewordSolver.Result(result.corrected, result.errataPositions,
            0, 0, intArrayOf(), requireNotNull(original.margin)))
    }
    val room = ecc - erased.size
    if (room <= 0) return emptyList()
    val pool = mutableListOf<Int>()
    for (group in margins.indices.filter { margins[it] >= 0 }.groupBy { margins[it] }.toSortedMap().values) {
        if (pool.size + group.size > 4) break
        pool += group
    }
    val results = mutableListOf<PartialCodewordSolver.Result>()
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
    if (room >= 2 && pool.size >= 2) {
        for (i in pool.indices) for (j in 0 until i) attempt(listOf(pool[j], pool[i]))
    } else for (position in pool) attempt(listOf(position))
    return PartialCodewordSolver.mergeCandidates(results)
}

internal fun imageErasureDescription(positions: List<Int>): String {
    val words = positions.joinToString { (it + 1).toString() }
    return localizedText("画像判定による一時消失と通常RS（ブロック内のコード語: $words）",
        "Conventional RS with temporary image erasures (codewords in block: $words)")
}
