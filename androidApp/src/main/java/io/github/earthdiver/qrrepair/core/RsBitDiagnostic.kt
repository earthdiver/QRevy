package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

/** Compact summary of the original input, before optional payload-structure forcing. */
data class RsBitDiagnostic(
    val unknownBits: Int,
    val rank: Int,
    val dimension: Int?,
    val fixedBits: Int,
    val independentCells: List<Coord>,
) {
    fun description(): String = listOfNotNull(inputAnalysisDescription(), commonBitsDescription()).joinToString("\n")

    /** A null dimension means inconsistency; rank is not a whole-input rank in that case. */
    fun inputAnalysisDescription(): String = if (dimension == null) localizedText(
        "入力済みの白黒をすべて保つ、RSの条件に合う組合せはありません。",
        "No combination satisfies the RS constraints while preserving all entered values.",
    ) else localizedText(
        "不明${unknownBits}bit − 独立な条件${rank}個 = 自由度${dimension}（${combinationsText()}通り）",
        "$unknownBits unknown bits − $rank independent constraints = $dimension free bits (${combinationsText()} combinations)",
    )

    fun combinationsText(): String? = dimension?.let {
        if (it in 0..16) (1L shl it).toString() else localizedText("2の${it}乗", "2^$it")
    }

    fun commonBitsDescription(): String? = if (dimension == null) null else localizedText(
        "この形式で入力済みの白黒を保つ組合せのすべてで、同じ値になる不明bit: ${fixedBits}bit",
        "Unknown bits with the same value in every combination preserving the entered values for this format: $fixedBits bits",
    )

    companion object {
        fun build(
            observed: QrMatrix,
            extraction: CodewordExtraction,
            de: DeinterleavedQr,
            spaces: List<RsBitConstraintSolver.Space?>,
            cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        ): RsBitDiagnostic {
            val unknown = de.blocks.sumOf { block -> block.unknownMasks.sumOf { it.countOneBits() } }
            if (spaces.any { it == null }) return RsBitDiagnostic(unknown, spaces.filterNotNull().sumOf { it.rank }, null, 0, emptyList())
            val coordinates = de.rawLocations.indices.associate { raw ->
                val loc = requireNotNull(de.rawLocations[raw])
                (loc.blockIndex to loc.localIndex) to extraction.codewords[raw].coordinates
            }
            val cells = spaces.flatMapIndexed { block, nullable ->
                val space = requireNotNull(nullable)
                fun coord(bit: RsBitConstraintSolver.Bit) = coordinates.getValue(block to bit.codeword)[7 - bit.bit]
                // All nonconstant bits halve the space; prefer the more readable source cell.
                space.independentBits(space.unknownBits.sortedByDescending { bit ->
                    val c = coord(bit)
                    observed[c.x, c.y].confidence
                }, cancellation).map(::coord)
            }.sortedByDescending { observed[it.x, it.y].confidence }
            return RsBitDiagnostic(unknown, spaces.sumOf { it!!.rank }, spaces.sumOf { it!!.dimension },
                spaces.sumOf { it!!.fixedBits.size }, cells)
        }
    }
}
