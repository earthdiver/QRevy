package io.github.earthdiver.qrrepair.core

/** Completed, unfiltered decoder lists owned by one recovery invocation. */
class ListDecodingTrialCache(val limitBytes: Long) {
    init { require(limitBytes >= 0) }

    internal class Key(block: RsBlockWork, parameters: IntArray = intArrayOf()) {
        private val dataLength = block.numDataCodewords
        private val received = block.codewords.copyOf()
        private val masks = block.unknownMasks.copyOf()
        private val parameters = parameters.copyOf()
        val workingBytes = 256L + (received.size + masks.size + parameters.size) * 4L
        override fun equals(other: Any?): Boolean = other is Key && dataLength == other.dataLength &&
            received.contentEquals(other.received) && masks.contentEquals(other.masks) && parameters.contentEquals(other.parameters)
        override fun hashCode(): Int = 31 * (31 * (31 * dataLength + received.contentHashCode()) + masks.contentHashCode()) + parameters.contentHashCode()
    }

    internal data class Candidate(val corrected: IntArray, val derivation: ListDecodingDerivation?) {
        val workingBytes: Long get() = 256L + corrected.size * 4L
    }

    private val entries = mutableMapOf<Key, List<Candidate>>()
    var workingBytes: Long = 0
        private set

    internal operator fun get(key: Key): List<Candidate>? = entries[key]

    internal fun collect(key: Key): Collector = Collector(key)

    /** Optional storage yields to live decoding and is published only after normal completion. */
    internal inner class Collector(private val key: Key) {
        private val candidates = mutableListOf<Candidate>()
        private var bytes = key.workingBytes
        private var eligible = true

        fun fit(availableBytes: Long) {
            if (bytes > minOf(limitBytes - workingBytes, availableBytes)) {
                eligible = false
                candidates.clear()
                bytes = 0
            }
        }

        fun add(corrected: IntArray, derivation: ListDecodingDerivation?, availableBytes: Long) {
            if (!eligible) return
            val candidate = Candidate(corrected, derivation)
            bytes += candidate.workingBytes
            fit(availableBytes)
            if (eligible) candidates += candidate
        }

        fun complete(availableBytes: Long) {
            fit(availableBytes)
            if (eligible && key !in entries) {
                entries[key] = candidates.toList()
                workingBytes += bytes
            }
        }
    }
}
