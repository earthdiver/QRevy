package io.github.earthdiver.qrrepair.core

/** Region membership is independent of sampled values, mask and ECC level. */
internal class QrCellRegions(val version: Int) {
    private val dimension = 17 + 4 * version
    val codewordCoordinates: Set<Coord> = CodewordExtractor.extract(QrMatrix(dimension), version, 0)
        .codewords.flatMapTo(hashSetOf()) { it.coordinates }

    fun contains(coord: Coord): Boolean = coord.x in 0 until dimension && coord.y in 0 until dimension

    fun structureAt(coord: Coord): RepairReasonKind? =
        if (contains(coord) && QrFunctionMask.isFunction(version, coord.x, coord.y))
            RepairTraceBuilder.structuralReason(dimension, coord.x, coord.y) else null
}
