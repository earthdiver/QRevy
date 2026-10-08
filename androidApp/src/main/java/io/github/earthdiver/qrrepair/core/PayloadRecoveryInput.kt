package io.github.earthdiver.qrrepair.core

/** Independent inputs derived from the same observation, with matching repair provenance. */
internal data class PayloadRecoveryInput(
    val blocks: DeinterleavedQr,
    val constraints: PayloadConstraintSolver.Result,
) {
    companion object {
        fun variants(
            observed: DeinterleavedQr,
            inferred: DeinterleavedQr,
            constraints: PayloadConstraintSolver.Result,
            preserveObservations: Boolean,
            trustKnownCount: Boolean,
        ): List<PayloadRecoveryInput> {
            if (preserveObservations || constraints.filledBits == 0) {
                return listOf(PayloadRecoveryInput(observed, constraints.copy(filledBits = 0, filled = emptyList())))
            }
            return buildList {
                val structure = constraints.filled.filter { it.kind != PayloadConstraintSolver.ConstraintKind.DATA_VALUE }
                if (structure.size != constraints.filled.size) {
                    val blocks = observed.copyDeep()
                    for (bit in structure) {
                        val block = blocks.blocks[bit.blockIndex]
                        val mask = 1 shl (7 - bit.bitFromMsb)
                        val value = block.codewords[bit.localByteIndex]
                        block.codewords[bit.localByteIndex] = if (bit.value == 1) value or mask else value and mask.inv()
                        block.unknownMasks[bit.localByteIndex] = block.unknownMasks[bit.localByteIndex] and mask.inv()
                    }
                    add(PayloadRecoveryInput(blocks, constraints.copy(filledBits = structure.size, filled = structure)))
                }
                add(PayloadRecoveryInput(inferred, constraints))
                // Without an explicit condition, inferred structure cannot exclude other segments.
                if (!trustKnownCount && none { it.constraints.filledBits == 0 }) {
                    add(PayloadRecoveryInput(observed, PayloadConstraintSolver.Result(0, 0, null, null)))
                }
            }
        }
    }
}
