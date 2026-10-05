package io.github.earthdiver.qrrepair.core

/** The same deterministic unknown-bit assignments feed conventional RS and list decoding. */
internal class PartialBitTrialPlan(
    private val block: RsBlockWork,
    maxBits: Int,
    maxUnknownBitsPerByte: Int = 4,
    nonEnumerablePositions: Set<Int> = emptySet(),
) {
    val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray()
    val selected: IntArray
    val bitCount: Int
    val remainingErasures: IntArray
    init {
        require(maxBits in 0..20)
        var bits = 0
        selected = erased.filter { it !in nonEnumerablePositions }
            .map { it to block.unknownMasks[it].countOneBits() }
            .filter { it.second in 1..maxUnknownBitsPerByte }.sortedBy { it.second }
            .filter { (_, count) -> if (bits + count <= maxBits) { bits += count; true } else false }
            .map { it.first }.toIntArray()
        bitCount = bits
        val chosen = selected.toSet()
        remainingErasures = erased.filterNot { it in chosen }.toIntArray()
    }

    fun assignment(index: Int): IntArray {
        require(index in 0 until (1 shl bitCount))
        val guessed = block.codewords.copyOf()
        var cursor = 0
        for (position in selected) for (bit in 0 until 8) {
            val mask = 1 shl bit
            if (block.unknownMasks[position] and mask == 0) continue
            guessed[position] = if ((index ushr cursor++) and 1 == 1) guessed[position] or mask
                else guessed[position] and mask.inv()
        }
        return guessed
    }
}
