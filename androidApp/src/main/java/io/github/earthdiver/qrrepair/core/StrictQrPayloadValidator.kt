package io.github.earthdiver.qrrepair.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Validates the complete QR data-codeword bit stream, including its canonical filler suffix. */
internal object StrictQrPayloadValidator {
    fun isValid(data: ByteArray, version: Int): Boolean = validate(data, version, allowIncomplete = false)

    /** True unless the available prefix already proves that no valid complete payload can follow. */
    fun canBePrefix(data: ByteArray, version: Int): Boolean = validate(data, version, allowIncomplete = true)

    /** Payload modes in segment order; control prefixes (ECI/FNC1/etc.) are not payload modes. */
    fun encodingModes(data: ByteArray, version: Int): List<Int>? {
        val modes = mutableListOf<Int>()
        return if (validate(data, version, allowIncomplete = false, modes = modes)) modes else null
    }

    /** Raw BYTE payloads, indexed by payload segment (control prefixes do not count). */
    fun byteSegments(data: ByteArray, version: Int): List<Pair<Int, ByteArray>>? {
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        return if (validate(data, version, allowIncomplete = false,
                modes = mutableListOf(), byteSegments = segments)) segments else null
    }

    /** A diagnostic boundary scan. It never makes a noncanonical suffix a valid QR payload. */
    internal data class Prefix(val terminatorBit: Int?, val endBit: Int, val modes: List<Int>, val byteSegments: List<Pair<Int, ByteArray>>)

    internal fun inspectPrefix(data: ByteArray, version: Int, unknownMasks: IntArray = IntArray(data.size),
        bitLength: Int = data.size * 8): Prefix? {
        require(version in 1..40 && unknownMasks.size == data.size && bitLength in 0..data.size * 8)
        val modes = mutableListOf<Int>()
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        var terminator: Int? = null
        var endBit = 0
        val valid = validate(data, version, false, modes = modes, byteSegments = segments, unknownMasks = unknownMasks,
            bitLength = bitLength, onTerminator = { terminator = it }, onPayloadEnd = { endBit = it })
        return if (valid) Prefix(terminator, endBit, modes, segments) else null
    }

    private fun validate(data: ByteArray, version: Int, allowIncomplete: Boolean,
        modes: MutableList<Int>? = null, byteSegments: MutableList<Pair<Int, ByteArray>>? = null,
        unknownMasks: IntArray? = null, bitLength: Int = data.size * 8,
        onTerminator: ((Int) -> Unit)? = null, onPayloadEnd: ((Int) -> Unit)? = null,
    ): Boolean = try {
        val bits = Bits(data, unknownMasks, bitLength)
        var structuredAppendSeen = false
        var fnc1Seen = false
        var modeSeenAfterStructuredAppend = false
        while (bits.available >= 4) {
            val mode = bits.read(4)
            if (mode == 1 || mode == 2 || mode == 4 || mode == 8 || mode == 13) modes?.add(mode)
            when (mode) {
                0 -> {
                    if (onTerminator == null) return validateFiller(bits)
                    onPayloadEnd?.invoke(bits.position - 4)
                    onTerminator(bits.position - 4)
                    return true
                }
                1 -> { modeSeenAfterStructuredAppend = true; validateNumeric(bits, bits.read(countBits(mode, version))) }
                2 -> { modeSeenAfterStructuredAppend = true; validateAlphanumeric(bits, bits.read(countBits(mode, version))) }
                3 -> {
                    // Structured Append, when present, is the first mode and occurs only once.
                    if (structuredAppendSeen || modeSeenAfterStructuredAppend || fnc1Seen) return false
                    structuredAppendSeen = true
                    val sequence = bits.read(8)
                    val index = sequence ushr 4
                    val totalMinusOne = sequence and 15
                    if (totalMinusOne == 0 || index > totalMinusOne) return false
                    bits.skip(8) // Parity belongs to the complete set; one symbol cannot verify it.
                }
                4 -> {
                    modeSeenAfterStructuredAppend = true
                    val count = bits.read(countBits(mode, version))
                    if (byteSegments == null) bits.skip(count * 8)
                    else {
                        if (count > bits.available / 8) throw IncompletePayload
                        byteSegments.add(modes!!.size to ByteArray(count) { bits.read(8).toByte() })
                    }
                } // Byte
                5 -> { // FNC1 first position
                    if (fnc1Seen || modeSeenAfterStructuredAppend) return false
                    fnc1Seen = true
                    modeSeenAfterStructuredAppend = true
                }
                7 -> { modeSeenAfterStructuredAppend = true; validateEci(bits) }
                8 -> { modeSeenAfterStructuredAppend = true; validateCharacters(bits, bits.read(countBits(mode, version)), kanjiValues) }
                9 -> { // FNC1 second position application indicator
                    if (fnc1Seen || modeSeenAfterStructuredAppend) return false
                    fnc1Seen = true
                    modeSeenAfterStructuredAppend = true
                    bits.skip(8)
                }
                13 -> {
                    modeSeenAfterStructuredAppend = true
                    if (bits.read(4) != 1) return false // QR supports the GB2312 subset only.
                    validateCharacters(bits, bits.read(countBits(mode, version)), hanziValues)
                }
                else -> return false
            }
        }
        onPayloadEnd?.invoke(bits.position)
        // A prefix may end inside the next mode indicator. Only a complete stream must
        // interpret these remaining bits as a shortened terminator.
        if (!allowIncomplete) {
            while (bits.available > 0) if (bits.read(1) != 0) return false
        }
        true
    } catch (_: IncompletePayload) {
        allowIncomplete
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    // Cache all 13-bit values once; prefix pruning must not repeatedly allocate charset decoders.
    // String(byte[], charset) silently replaces malformed/unassigned codes, which is not validation.
    private val kanjiValues by lazy {
        characterTable("Shift_JIS") { value ->
            val assembled = ((value / 0xC0) shl 8) or (value % 0xC0)
            val code = assembled + if (assembled < 0x1F00) 0x8140 else 0xC140
            code.takeIf {
                (it in 0x8140..0x9FFC || it in 0xE040..0xEBBF) &&
                    (it and 0xFF) in 0x40..0xFC && (it and 0xFF) != 0x7F
            }
        }
    }
    private val hanziValues by lazy {
        characterTable("GB2312") { value ->
            val assembled = ((value / 0x60) shl 8) or (value % 0x60)
            val code = assembled + if (assembled < 0xA00) 0xA1A1 else 0xA6A1
            code.takeIf {
                (it in 0xA1A1..0xAAFE || it in 0xB0A1..0xFAFE) && (it and 0xFF) in 0xA1..0xFE
            }
        }
    }

    private fun characterTable(charset: String, codeFor: (Int) -> Int?): BooleanArray {
        val decoder = Charset.forName(charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return BooleanArray(1 shl 13) { value ->
            codeFor(value)?.let { code ->
                try {
                    decoder.decode(ByteBuffer.wrap(byteArrayOf((code ushr 8).toByte(), code.toByte())))
                    true
                } catch (_: CharacterCodingException) {
                    false
                }
            } ?: false
        }
    }

    private fun validateCharacters(bits: Bits, count: Int, validValues: BooleanArray) {
        repeat(count) { if (!validValues[bits.read(13)]) error("Invalid QR character code") }
    }

    private fun validateNumeric(bits: Bits, count: Int) {
        repeat(count / 3) { if (bits.read(10) >= 1000) error("Invalid numeric triplet") }
        when (count % 3) {
            1 -> if (bits.read(4) >= 10) error("Invalid numeric digit")
            2 -> if (bits.read(7) >= 100) error("Invalid numeric pair")
        }
    }

    private fun validateAlphanumeric(bits: Bits, count: Int) {
        repeat(count / 2) { if (bits.read(11) >= 45 * 45) error("Invalid alphanumeric pair") }
        if ((count and 1) != 0 && bits.read(6) >= 45) error("Invalid alphanumeric character")
    }

    private fun validateEci(bits: Bits) {
        val first = bits.read(8)
        when {
            (first and 0x80) == 0 -> Unit
            (first and 0xC0) == 0x80 -> bits.skip(8)
            (first and 0xE0) == 0xC0 -> bits.skip(16)
            else -> error("Invalid ECI designator")
        }
    }

    private fun validateFiller(bits: Bits): Boolean {
        while (bits.bitOffset != 0) if (bits.read(1) != 0) return false
        var expected = 0xEC
        while (bits.available >= 8) {
            if (bits.read(8) != expected) return false
            expected = if (expected == 0xEC) 0x11 else 0xEC
        }
        return bits.available == 0
    }

    private fun countBits(mode: Int, version: Int): Int {
        val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
        return when (mode) {
            1 -> intArrayOf(10, 12, 14)[group]
            2 -> intArrayOf(9, 11, 13)[group]
            4 -> intArrayOf(8, 16, 16)[group]
            8 -> intArrayOf(8, 10, 12)[group]
            13 -> intArrayOf(8, 10, 12)[group]
            else -> error("Mode has no character count")
        }
    }

    private class Bits(private val bytes: ByteArray, private val unknownMasks: IntArray?, private val bitLength: Int) {
        var position = 0
            private set
        val available: Int get() = bitLength - position
        val bitOffset: Int get() = position and 7

        fun read(width: Int): Int {
            require(width in 0..31)
            if (width > available) throw IncompletePayload
            var value = 0
            repeat(width) {
                if (unknownMasks != null && (unknownMasks[position / 8] and (1 shl (7 - position % 8))) != 0)
                    error("Unknown input bit")
                value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }

        fun skip(width: Int) {
            require(width >= 0)
            if (width > available) throw IncompletePayload
            if (unknownMasks == null) position += width
            else repeat(width) { read(1) }
        }
    }

    private object IncompletePayload : RuntimeException()
}
