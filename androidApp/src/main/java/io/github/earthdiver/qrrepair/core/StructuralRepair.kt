package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version

/** Writes QR modules that are determined by the specification rather than payload data. */
object StructuralRepair {
    fun apply(
        matrix: QrMatrix,
        ecLevel: ErrorCorrectionLevel,
        dataMask: Int,
        preserveUserEdits: Boolean = true,
    ) {
        val versionNumber = (matrix.dimension - 17) / 4
        val d = matrix.dimension

        fun set(x: Int, y: Int, black: Boolean) {
            if (x !in 0 until d || y !in 0 until d) return
            val cell = matrix[x, y]
            if (preserveUserEdits && cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) return
            matrix.set(x, y, if (black) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.STRUCTURAL)
        }

        fun finder(startX: Int, startY: Int) {
            for (y in 0..6) for (x in 0..6) {
                val black = x == 0 || x == 6 || y == 0 || y == 6 || (x in 2..4 && y in 2..4)
                set(startX + x, startY + y, black)
            }
        }

        finder(0, 0)
        finder(d - 7, 0)
        finder(0, d - 7)

        // Separators.
        for (i in 0..7) {
            set(i, 7, false); set(7, i, false)
            set(d - 8, i, false); set(d - 8 + i.coerceAtMost(7), 7, false)
            set(i, d - 8, false); set(7, d - 8 + i.coerceAtMost(7), false)
        }

        // Timing patterns. Even coordinate is dark.
        for (i in 8..(d - 9)) {
            set(i, 6, (i and 1) == 0)
            set(6, i, (i and 1) == 0)
        }

        // Alignment patterns.
        val centers = Version.getVersionForNumber(versionNumber).alignmentPatternCenters
        for (ix in centers.indices) for (iy in centers.indices) {
            val skippedNearFinder =
                (ix == 0 && (iy == 0 || iy == centers.lastIndex)) ||
                (ix == centers.lastIndex && iy == 0)
            if (skippedNearFinder) continue
            val cx = centers[iy]
            val cy = centers[ix]
            for (dy in -2..2) for (dx in -2..2) {
                val r = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(cx + dx, cy + dy, r == 2 || r == 0)
            }
        }

        // Fixed dark module.
        set(8, d - 8, true)

        writeFormat(matrix, ecLevel, dataMask, preserveUserEdits)
        if (versionNumber >= 7) writeVersion(matrix, versionNumber, preserveUserEdits)
    }

    private fun writeFormat(
        matrix: QrMatrix,
        ecLevel: ErrorCorrectionLevel,
        dataMask: Int,
        preserveUserEdits: Boolean,
    ) {
        val d = matrix.dimension
        val ecBits = when (ecLevel) {
            ErrorCorrectionLevel.M -> 0
            ErrorCorrectionLevel.L -> 1
            ErrorCorrectionLevel.H -> 2
            ErrorCorrectionLevel.Q -> 3
        }
        val code = FormatSolver.formatCodeword((ecBits shl 3) or dataMask)
        val copies = listOf(
            listOf(
                0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8,
                7 to 8, 8 to 8, 8 to 7,
                8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
            ),
            buildList {
                for (y in d - 1 downTo d - 7) add(8 to y)
                for (x in d - 8 until d) add(x to 8)
            },
        )
        for (copy in copies) for (i in 0 until 15) {
            val (x, y) = copy[i]
            val cell = matrix[x, y]
            if (preserveUserEdits && cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) continue
            val bit = (code shr (14 - i)) and 1
            matrix.set(x, y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.STRUCTURAL)
        }
    }

    private fun writeVersion(matrix: QrMatrix, version: Int, preserveUserEdits: Boolean) {
        val d = matrix.dimension
        val code = versionCodeword(version)
        val copy1 = buildList {
            for (y in 5 downTo 0) for (x in d - 9 downTo d - 11) add(x to y)
        }
        val copy2 = buildList {
            for (x in 5 downTo 0) for (y in d - 9 downTo d - 11) add(x to y)
        }
        for (coords in listOf(copy1, copy2)) for (i in 0 until 18) {
            val (x, y) = coords[i]
            val cell = matrix[x, y]
            if (preserveUserEdits && cell.source == ModuleSource.USER && cell.value != ModuleValue.ERASED) continue
            val bit = (code shr (17 - i)) and 1
            matrix.set(x, y, if (bit == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.STRUCTURAL)
        }
    }

    fun versionCodeword(version: Int): Int {
        require(version in 7..40)
        var value = version shl 12
        val generator = 0x1F25
        while (bitLength(value) >= bitLength(generator)) {
            value = value xor (generator shl (bitLength(value) - bitLength(generator)))
        }
        return (version shl 12) or value
    }

    private fun bitLength(v: Int): Int = if (v == 0) 0 else 32 - Integer.numberOfLeadingZeros(v)
}
