package io.github.earthdiver.qrrepair.core

import com.google.zxing.FormatException
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge

/** A bounded feasibility probe. Its result is never admitted as a normal recovery candidate. */
internal object SingleSegmentRetryProbe {
    fun canDecode(
        original: DeinterleavedQr,
        version: Int,
        format: FormatCandidate,
        matrix: QrMatrix,
        diagnosticInput: QrMatrix,
        extraction: CodewordExtraction,
        evidenceExtraction: CodewordExtraction,
        mismatchLimit: Int?,
        cancellation: RecoveryCancellation,
    ): Boolean {
        val forced = original.copyDeep()
        val structure = PayloadConstraintSolver.apply(forced, version, trustKnownCount = true,
            cancellation = cancellation).trustedStructure ?: return false
        val corrected = solveBlocks(forced, cancellation) ?: return false
        val data = forced.blocks.indices.flatMap { index ->
            corrected[index].take(forced.blocks[index].numDataCodewords)
        }.map(Int::toByte).toByteArray()
        if (!structure.matches(data) || !StrictQrPayloadValidator.isValid(data, version)) return false
        val raw = QrBlockInterleaver.reinterleave(forced, corrected)
        if (RecoveryCandidateEvidence.userPinConflicts(matrix, extraction, raw, format.dataMask).isNotEmpty()) return false
        val (_, mismatches) = RecoveryCandidateEvidence.countKnownBitMismatches(
            diagnosticInput, evidenceExtraction, raw, format.dataMask)
        if (mismatchLimit != null && mismatches > mismatchLimit) return false
        cancellation.check()
        return try {
            ZxingPayloadBridge.decodeData(data, version, format.ecLevel, null)?.text != null
        } catch (_: FormatException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Every block needs positive conventional margin or a unique consistent bit solution. */
    internal fun solveBlocks(de: DeinterleavedQr, cancellation: RecoveryCancellation): List<IntArray>? {
        val rs = ReedSolomonErasures()
        val bitSolver = RsBitConstraintSolver()
        return de.blocks.map { block ->
            cancellation.check()
            val erased = block.unknownMasks.indices.filter { block.unknownMasks[it] != 0 }.toIntArray()
            val result = if (erased.size > de.ecSymbolsPerBlock) null else try {
                rs.decode(block.codewords.copyOf(), de.ecSymbolsPerBlock, erased)
            } catch (_: ReedSolomonErasures.DecodeException) { null }
            val errors = result?.errataPositions?.count { it !in erased }
            if (result != null && de.ecSymbolsPerBlock - erased.size - 2 * requireNotNull(errors) > 0) {
                result.corrected
            } else {
                val space = bitSolver.analyze(block, de.ecSymbolsPerBlock, cancellation) ?: return null
                if (space.dimension != 0) return null
                space.assignments(maxDimension = 0, cancellation = cancellation).single()
            }
        }
    }
}
