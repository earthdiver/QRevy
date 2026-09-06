package io.github.earthdiver.qrrepair.core

data class RsBlockWork(
    val numDataCodewords: Int,
    val codewords: IntArray,
    val unknownMasks: IntArray,
) {
    val erased: BooleanArray get() = BooleanArray(codewords.size) { unknownMasks[it] != 0 }
    fun copyDeep(): RsBlockWork = RsBlockWork(numDataCodewords, codewords.copyOf(), unknownMasks.copyOf())
}

data class RawCodewordLocation(val blockIndex: Int, val localIndex: Int)

data class DeinterleavedQr(
    val blocks: List<RsBlockWork>,
    val rawLocations: Array<RawCodewordLocation?>,
    val ecSymbolsPerBlock: Int,
) {
    fun copyDeep(): DeinterleavedQr = copy(blocks = blocks.map { it.copyDeep() })
}

/** Minimum bit decisions needed to reduce all unresolved RS symbols to erasure capacity. */
fun minimumRsShortfallBits(blocks: List<RsBlockWork>, ecSymbolsPerBlock: Int): Int = blocks.sumOf { block ->
    val unresolvedBitCounts = block.unknownMasks.filter { it != 0 }.map { mask ->
        var value = mask and 0xFF
        var count = 0
        while (value != 0) {
            count += value and 1
            value = value ushr 1
        }
        count
    }.sorted()
    val excessSymbols = (unresolvedBitCounts.size - ecSymbolsPerBlock).coerceAtLeast(0)
    unresolvedBitCounts.take(excessSymbols).sum()
}
