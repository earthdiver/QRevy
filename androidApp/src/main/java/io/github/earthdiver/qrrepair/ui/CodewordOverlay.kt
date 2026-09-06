package io.github.earthdiver.qrrepair.ui

import io.github.earthdiver.qrrepair.core.CodewordExtractor
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FormatSolver
import io.github.earthdiver.qrrepair.core.QrBlockInterleaver
import io.github.earthdiver.qrrepair.core.QrMatrix

enum class CodewordOverlayKind {
    DATA,
    ECC,
    UNCLASSIFIED,
}

data class CodewordOverlayEntry(
    val rawIndex: Int,
    val kind: CodewordOverlayKind,
    val blockIndex: Int?,
    val localIndex: Int?,
    val kindIndex: Int?,
    val coordinates: List<Coord>,
)

data class CodewordOverlayCell(
    val entry: CodewordOverlayEntry,
    val bitIndex: Int,
)

data class CodewordOverlayModel(
    val entries: List<CodewordOverlayEntry>,
    val cells: Map<Coord, CodewordOverlayCell>,
    val classifiedEcLevel: String?,
)

/** Builds an explanatory overlay. Data/ECC is classified only from trusted format information. */
fun buildCodewordOverlay(matrix: QrMatrix, version: Int): CodewordOverlayModel {
    val extraction = CodewordExtractor.extract(matrix, version, dataMask = 0)
    val trustedFormat = FormatSolver.trustedCandidate(matrix)
    val deinterleaved = trustedFormat?.let { format ->
        runCatching { QrBlockInterleaver.deinterleave(extraction, version, format.ecLevel) }.getOrNull()
    }
    val entries = extraction.codewords.mapIndexed { rawIndex, codeword ->
        val location = deinterleaved?.rawLocations?.getOrNull(rawIndex)
        val block = location?.let { deinterleaved.blocks.getOrNull(it.blockIndex) }
        val isData = location != null && block != null && location.localIndex < block.numDataCodewords
        val kind = when {
            location == null || block == null -> CodewordOverlayKind.UNCLASSIFIED
            isData -> CodewordOverlayKind.DATA
            else -> CodewordOverlayKind.ECC
        }
        CodewordOverlayEntry(
            rawIndex = rawIndex,
            kind = kind,
            blockIndex = location?.blockIndex,
            localIndex = location?.localIndex,
            kindIndex = when (kind) {
                CodewordOverlayKind.DATA -> location?.localIndex
                CodewordOverlayKind.ECC -> location?.localIndex?.minus(block?.numDataCodewords ?: 0)
                CodewordOverlayKind.UNCLASSIFIED -> null
            },
            coordinates = codeword.coordinates,
        )
    }
    val cells = buildMap {
        entries.forEach { entry ->
            entry.coordinates.forEachIndexed { bitIndex, coord ->
                put(coord, CodewordOverlayCell(entry, bitIndex))
            }
        }
    }
    return CodewordOverlayModel(entries, cells, trustedFormat?.ecLevel?.toString())
}
