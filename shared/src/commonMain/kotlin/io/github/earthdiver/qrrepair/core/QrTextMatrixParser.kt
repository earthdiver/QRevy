package io.github.earthdiver.qrrepair.core

data class ParsedQrTextMatrix(
    val matrix: QrMatrix,
    val version: Int,
)

/** Strict interchange format containing only an N x N grid of 0, 1, ?, and * cells. */
object QrTextMatrixParser {
    const val MAX_PRIORITY_CELLS = 14
    const val MAX_TEXT_CHARS = 64 * 1024

    fun parse(text: String): ParsedQrTextMatrix {
        require(text.length <= MAX_TEXT_CHARS) { "Text matrix is too large" }
        require(text.isNotEmpty()) { "Text matrix is empty" }

        val normalized = text.replace("\r\n", "\n")
        require('\r' !in normalized) { "Only LF or CRLF line endings are supported" }
        val body = normalized.removeSingleTrailingLineFeed()
        require(body.isNotEmpty()) { "Text matrix is empty" }
        val rows = body.split('\n')
        require(rows.none { it.isEmpty() }) { "Text matrix contains an empty row" }

        val dimension = rows.size
        require(rows.all { it.length == dimension }) {
            "Text matrix must be square: found $dimension rows with row lengths ${rows.map { it.length }.distinct()}"
        }
        require(dimension in 21..177 && (dimension - 17) % 4 == 0) {
            "Matrix size $dimension does not correspond to QR Code Model 2 Version 1-40"
        }

        val matrix = QrMatrix(dimension)
        var priorityCells = 0
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, symbol ->
                val value = when (symbol) {
                    '0' -> ModuleValue.ZERO
                    '1' -> ModuleValue.ONE
                    '?', '*' -> ModuleValue.ERASED
                    else -> throw IllegalArgumentException(
                        "Invalid character at row ${y + 1}, column ${x + 1}: '$symbol'",
                    )
                }
                matrix.set(
                    x, y, value,
                    confidence = if (value == ModuleValue.ERASED) 0f else 1f,
                    source = ModuleSource.IMAGE,
                )
                if (symbol == '*') {
                    priorityCells++
                    require(priorityCells <= MAX_PRIORITY_CELLS) {
                        "At most $MAX_PRIORITY_CELLS priority-search (*) cells are supported"
                    }
                    matrix.setPrioritySearch(x, y, true)
                }
            }
        }
        return ParsedQrTextMatrix(matrix, (dimension - 17) / 4)
    }

    private fun String.removeSingleTrailingLineFeed(): String =
        if (endsWith('\n')) dropLast(1) else this
}
