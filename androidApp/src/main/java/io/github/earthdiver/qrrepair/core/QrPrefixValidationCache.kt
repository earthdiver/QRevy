package io.github.earthdiver.qrrepair.core

/** Invocation-local memoization of a pure predicate; eviction always permits recomputation. */
internal class QrPrefixValidationCache(
    private val version: Int,
    private val maximumBytes: Long,
    private val validate: (ByteArray, Int) -> Boolean = StrictQrPayloadValidator::canBePrefix,
) {
    init { require(maximumBytes >= 0) }
    private class Entry(val data: ByteArray, val valid: Boolean) {
        val bytes: Long get() = 128L + data.size * 2L
    }
    private val entries = ArrayDeque<Entry>()
    var workingBytes: Long = 0
        private set

    fun canBePrefix(data: ByteArray): Boolean {
        entries.firstOrNull { it.data.contentEquals(data) }?.let { return it.valid }
        val valid = validate(data, version)
        val bytes = 128L + data.size * 2L
        if (bytes > maximumBytes) return valid
        while (entries.isNotEmpty() && (entries.size >= 128 || workingBytes + bytes > maximumBytes))
            workingBytes -= entries.removeFirst().bytes
        entries.addLast(Entry(data.copyOf(), valid))
        workingBytes += bytes
        return valid
    }
}
