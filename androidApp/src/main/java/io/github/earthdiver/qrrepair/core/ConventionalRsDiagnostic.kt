package io.github.earthdiver.qrrepair.core

/** A separate conventional RS calculation on the original input, never a recovery route. */
enum class ConventionalRsStatus { SUCCESS, ERASURE_CAPACITY_EXCEEDED, CORRECTION_FAILED }

data class ConventionalRsBlockDiagnostic(
    val blockIndex: Int,
    val totalSymbols: Int,
    val dataSymbols: Int,
    val eccSymbols: Int,
    val erasures: Int,
    val errors: Int?,
    val margin: Int?,
    val status: ConventionalRsStatus,
)

data class ConventionalRsDiagnostic(
    val blocks: List<ConventionalRsBlockDiagnostic>,
    val correctedInterleaved: IntArray?,
    val knownBitMismatches: Int? = null,
    val knownBitMismatchLimit: Int? = null,
    val manualConflicts: List<ManualCellConflict> = emptyList(),
    val qrStructureValid: Boolean? = null,
) {
    val totalSymbols: Int get() = blocks.sumOf { it.totalSymbols }
    val dataSymbols: Int get() = blocks.sumOf { it.dataSymbols }
    val eccSymbols: Int get() = blocks.sumOf { it.eccSymbols }
    val succeeded: Boolean get() = blocks.isNotEmpty() && blocks.all { it.status == ConventionalRsStatus.SUCCESS }
    val minMargin: Int? get() = if (succeeded) blocks.mapNotNull { it.margin }.minOrNull() else null
    /** Input locks and known-bit mismatch limit only; QR content is checked separately. */
    val settingsAccepted: Boolean? get() = if (!succeeded) null else manualConflicts.isEmpty() &&
        (knownBitMismatchLimit == null || (knownBitMismatches != null && knownBitMismatches <= knownBitMismatchLimit))
    fun matches(raw: IntArray): Boolean? = correctedInterleaved?.contentEquals(raw)

    companion object {
        fun analyze(de: DeinterleavedQr, rs: ReedSolomonErasures = ReedSolomonErasures(),
            cancellation: RecoveryCancellation = RecoveryCancellation.NONE): ConventionalRsDiagnostic {
            val corrected = mutableListOf<IntArray>()
            val blocks = de.blocks.mapIndexed { index, original ->
                cancellation.check()
                val block = original.copyDeep()
                val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray()
                val result = if (erased.size > de.ecSymbolsPerBlock) null else try {
                    rs.decode(block.codewords, de.ecSymbolsPerBlock, erased)
                } catch (_: ReedSolomonErasures.DecodeException) { null }
                val status = when {
                    erased.size > de.ecSymbolsPerBlock -> ConventionalRsStatus.ERASURE_CAPACITY_EXCEEDED
                    result == null -> ConventionalRsStatus.CORRECTION_FAILED
                    else -> ConventionalRsStatus.SUCCESS
                }
                val erasedSet = erased.toSet()
                val errors = result?.errataPositions?.count { it !in erasedSet }
                result?.let { corrected += it.corrected }
                ConventionalRsBlockDiagnostic(index, block.codewords.size, block.numDataCodewords,
                    de.ecSymbolsPerBlock, erased.size, errors,
                    errors?.let { de.ecSymbolsPerBlock - erased.size - 2 * it }, status)
            }
            return ConventionalRsDiagnostic(blocks,
                if (corrected.size == de.blocks.size) QrBlockInterleaver.reinterleave(de, corrected) else null)
        }
    }
}

/** Actual representative route for every block; bit-constraint routes have no conventional margin. */
data class RecoveryBlockMethod(
    val blockIndex: Int,
    val bitConstraintSolved: Boolean,
    val rsMargin: Int?,
    val guessedBits: Int,
)
