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
    fun description(): String = if (dimension == null) localizedText(
        "RS部分bit制約: 既知データ・ECCとの完全一致解なし（入力bitまたは形式等の前提を確認してください）",
        "RS partial-bit constraints: no exact known-data/ECC solution (check input bits and format assumptions)",
    ) else localizedText(
        "RS部分bit制約: 未知${unknownBits}bit・独立制約${rank}bit・自由度${dimension}bit\n全解共通で確定: ${fixedBits}bit（指定形式と既知データ・ECCが正しいと仮定。構造制約適用前、ペイロード未検証）",
        "RS partial-bit constraints: $unknownBits unknown bits; rank $rank; $dimension free bits\nFixed across all solutions: $fixedBits bits (assuming this format and known data/ECC are correct; before structure constraints and payload validation)",
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
