package io.github.earthdiver.qrrepair.core

/** One geometry study's fixed decoder policy; every image sample still runs before lookup. */
internal class GeometryRecoveryCache(private val capacity: Int = 4096) {
    init { require(capacity > 0) }

    private data class Input(val expectedText: String, val dimension: Int, val cells: String)
    private val results = object : LinkedHashMap<Input, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Input, Boolean>?): Boolean = size > capacity
    }

    fun getOrPut(matrix: QrMatrix, expectedText: String, recover: () -> Boolean): Boolean {
        // Own an immutable snapshot: values alone cannot identify a recovery input.
        val input = Input(expectedText, matrix.dimension, buildString(matrix.dimension * matrix.dimension * 3) {
            for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
                val cell = matrix[x, y]
                append((cell.value.ordinal or (cell.source.ordinal shl 2) or
                    (if (cell.prioritySearch) 1 shl 15 else 0)).toChar())
                val confidence = cell.confidence.toRawBits()
                append((confidence ushr 16).toChar())
                append(confidence.toChar())
            }
        })
        return results.getOrPut(input, recover)
    }
}
