package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class ManualCellConflict(val coord: Coord, val actual: ModuleValue, val expected: ModuleValue?)

data class FormatEditConflict(val cells: List<ManualCellConflict>) {
    fun message(changeRejected: Boolean = true): String = buildString {
        append(localizedText(
            "手動0/1とECC・maskの指定が矛盾しています。\n",
            "Manual 0/1 values conflict with the ECC/mask selection.\n",
        ))
        if (changeRejected) append(localizedText("変更は適用されません。\n", "The change was not applied.\n"))
        if (cells.all { it.expected == null }) append(localizedText(
            "次の手動値を同時に満たす形式候補がありません。\n",
            "No permitted format matches all of these manual values together.\n",
        ))
        cells.forEach { cell ->
            append("(${cell.coord.x},${cell.coord.y}): ${cell.actual.bitOrNull()}")
            cell.expected?.let { append(localizedText(" / 設定では ", " / selection requires ")).append(it.bitOrNull()) }
            append('\n')
        }
        append(localizedText(
            "該当セルを「?」にするか、ECC・maskを「自動」に戻してください。",
            "Set the affected cells to ? or set ECC/mask back to Auto.",
        ))
    }
}

/** Selection/edit consistency is independent of manual locking and image observations. */
object FormatEditPolicy {
    fun coordinates(version: Int): Set<Coord> =
        FormatSolver.copies(17 + 4 * version).flatten().mapTo(linkedSetOf()) { Coord(it.first, it.second) }

    private fun codewords(constraint: QrFormatConstraint): List<Int> = buildList {
        for (ec in ErrorCorrectionLevel.entries) for (mask in 0..7) {
            if (constraint.permits(ec, mask)) add(FormatSolver.formatCodeword((ec.bits shl 3) or mask))
        }
    }

    private fun values(version: Int, codeword: Int): Map<Coord, ModuleValue> = buildMap {
        for (copy in FormatSolver.copies(17 + 4 * version)) copy.forEachIndexed { bit, (x, y) ->
            put(Coord(x, y), if ((codeword and (1 shl (14 - bit))) != 0) ModuleValue.ONE else ModuleValue.ZERO)
        }
    }

    /** Only the explicit ECC/mask selection determines these bits; manual values do not. */
    fun fixedValues(version: Int, constraint: QrFormatConstraint?): Map<Coord, ModuleValue> {
        if (constraint == null) return emptyMap()
        val formats = codewords(constraint).map { values(version, it) }
        return formats.first().filter { (coord, value) -> formats.all { it[coord] == value } }
    }

    fun ignoredComparisonCoords(
        version: Int, constraint: QrFormatConstraint?, edits: Map<Coord, ModuleValue>,
    ): Set<Coord> = fixedValues(version, constraint).keys.filterTo(hashSetOf()) {
        edits[it]?.bitOrNull() != null
    }

    fun conflict(version: Int, constraint: QrFormatConstraint?, edits: Map<Coord, ModuleValue>): FormatEditConflict? {
        if (constraint == null) return null
        val formats = codewords(constraint).map { values(version, it) }
        val pins = edits.filter { (coord, value) -> coord in formats.first() && value != ModuleValue.ERASED }
        fun compatible(subset: Map<Coord, ModuleValue>) = formats.any { format -> subset.all { (c, v) -> format[c] == v } }
        if (compatible(pins)) return null
        val fixed = fixedValues(version, constraint)
        val direct = pins.mapNotNull { (coord, actual) ->
            fixed[coord]?.takeIf { it != actual }?.let { ManualCellConflict(coord, actual, it) }
        }
        if (direct.isNotEmpty()) return FormatEditConflict(direct)
        // Reduce a collective conflict to an irreducible set; do not label each bit individually wrong.
        val conflicting = pins.toMutableMap()
        for (coord in pins.keys) {
            val smaller = conflicting - coord
            if (!compatible(smaller)) conflicting.remove(coord)
        }
        return FormatEditConflict(conflicting.map { (c, v) -> ManualCellConflict(c, v, null) })
    }

    fun returnsToAuto(old: QrFormatConstraint?, next: QrFormatConstraint?): Boolean = old != null &&
        (next == null || ((next.ecLevel == null || next.ecLevel == old.ecLevel) &&
            (next.dataMask == null || next.dataMask == old.dataMask)))

    fun manualValues(matrix: QrMatrix): Map<Coord, ModuleValue> = buildMap {
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val cell = matrix[x, y]
            if (cell.source == ModuleSource.USER) put(Coord(x, y), cell.value)
        }
    }
}

/** Checks fixed manual modules outside RS, without adding them to either data/ECC count. */
internal fun nonRsManualConflicts(
    matrix: QrMatrix, version: Int, ecLevel: ErrorCorrectionLevel, mask: Int,
): List<ManualCellConflict> {
    val pins = FormatEditPolicy.manualValues(matrix).filterValues { it != ModuleValue.ERASED }
    if (pins.isEmpty()) return emptyList()
    val codewordCoords = CodewordExtractor.extract(matrix, version, mask).codewords
        .flatMapTo(hashSetOf()) { it.coordinates }
    val nonRsPins = pins.filterKeys { it !in codewordCoords }
    if (nonRsPins.isEmpty()) return emptyList()
    val structure = QrMatrix(matrix.dimension)
    StructuralRepair.apply(structure, ecLevel, mask, preserveUserEdits = false)
    return nonRsPins.mapNotNull { (coord, actual) ->
        val expected = if (QrFunctionMask.isFunction(version, coord.x, coord.y)) structure[coord.x, coord.y].value
        else if (CodewordExtractor.maskApplies(mask, coord.x, coord.y)) ModuleValue.ONE else ModuleValue.ZERO
        if (actual == expected) null else ManualCellConflict(coord, actual, expected)
    }
}
