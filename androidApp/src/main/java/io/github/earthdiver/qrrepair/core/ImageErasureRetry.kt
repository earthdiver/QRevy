package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Cumulative margins of the weakest one, two and three image-read cells. */
internal data class ImageWordMargins(val s1: Int, val s2: Int, val s3: Int)

internal fun imageWordMargins(
    de: DeinterleavedQr, extraction: CodewordExtraction, matrix: QrMatrix,
): List<List<ImageWordMargins?>> {
    val margins = de.blocks.map { MutableList<ImageWordMargins?>(it.codewords.size) { null } }
    extraction.codewords.forEachIndexed { raw, word ->
        if (!word.erased) {
            val values = word.coordinates.map { matrix.imageMargin(it.x, it.y) }.sorted()
            // All eight cells must have optical evidence, including a measured margin of zero.
            if (values.first() >= 0) {
                val location = requireNotNull(de.rawLocations[raw])
                margins[location.blockIndex][location.localIndex] = ImageWordMargins(
                    values[0], values[0] + values[1], values[0] + values[1] + values[2],
                )
            }
        }
    }
    return margins
}

internal class ImageErasurePlan(margins: List<ImageWordMargins?>, room: Int) {
    val pool = buildList {
        if (room <= 0) return@buildList
        val eligible = margins.indices.filter { margins[it] != null }
        fun rank(score: (ImageWordMargins) -> Int) =
            eligible.sortedWith(compareBy<Int> { score(requireNotNull(margins[it])) }.thenBy { it })
        val rankings = listOf(rank { it.s1 }, rank { it.s2 }, rank { it.s3 })
        val cursors = IntArray(3)
        val selected = BooleanArray(margins.size)
        val limit = minOf(8, eligible.size)
        while (size < limit) {
            for (k in rankings.indices) {
                if (size == limit) break
                // Skip duplicates within this ranking's turn, without advancing to the next ranking.
                while (selected[rankings[k][cursors[k]]]) cursors[k]++
                val word = rankings[k][cursors[k]++]
                selected[word] = true
                add(word)
            }
        }
    }
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
