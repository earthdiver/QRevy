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

    /** The callback distinguishes this user assumption from an invalid QR structure. */
    fun checkRecoveryData(data: ByteArray, version: Int, prefix: Boolean, assumeUtf8: Boolean,
        onInvalidUtf8: () -> Unit = {}, onAssumedSegment: () -> Unit = {}): Boolean =
        validate(data, version, prefix, assumeUtf8 = assumeUtf8, onInvalidUtf8 = onInvalidUtf8,
            onAssumedSegment = onAssumedSegment)

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
        bitLength: Int = data.size * 8, onFailure: ((Int) -> Unit)? = null): Prefix? {
        require(version in 1..40 && unknownMasks.size == data.size && bitLength in 0..data.size * 8)
        val modes = mutableListOf<Int>()
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        var terminator: Int? = null
        var endBit = 0
        var modeStart = 0
        val valid = validate(data, version, false, modes = modes, byteSegments = segments, unknownMasks = unknownMasks,
            bitLength = bitLength, onTerminator = { terminator = it }, onPayloadEnd = { endBit = it }, onMode = { modeStart = it }, validateExplicitUtf8 = false)
        return if (valid) Prefix(terminator, endBit, modes, segments) else {
            onFailure?.invoke(modeStart)
            null
        }
    }

    enum class Issue { INVALID_CONTEXT, INVALID_VALUE, INVALID_ECI, UNSUPPORTED_MODE, INCOMPLETE, UNKNOWN_BITS }
    /** All offsets are half-open and refer to the supplied bit stream. */
    data class SegmentTrace(
        val startBit: Int, val endBit: Int, val mode: Int?, val count: Int? = null,
        val eci: Int? = null, val requiredBits: Int? = null, val availableBits: Int? = null,
        val issue: Issue? = null, val problemStartBit: Int? = null, val problemEndBit: Int? = null,
    )
    data class Inspection(val prefix: Prefix, val segments: List<SegmentTrace>, val valid: Boolean)

    /** Retains completed segments and the failing field without relaxing ordinary validation. */
    fun inspectSegments(data: ByteArray, version: Int, unknownMasks: IntArray, bitLength: Int): Inspection {
        require(version in 1..40 && unknownMasks.size == data.size && bitLength in 0..data.size * 8)
        val trace = Trace()
        val modes = mutableListOf<Int>()
        val bytes = mutableListOf<Pair<Int, ByteArray>>()
        var terminator: Int? = null
        var end = 0
        val valid = validate(data, version, false, modes, bytes, unknownMasks, bitLength,
            onTerminator = { terminator = it }, onPayloadEnd = { end = it }, trace = trace, validateExplicitUtf8 = false)
        val completed = trace.segments.filter { it.issue == null && it.mode in listOf(1, 2, 4, 8, 13) }
        return Inspection(Prefix(terminator, if (valid) end else trace.segments.lastOrNull { it.issue == null }?.endBit ?: 0,
            completed.map { it.mode!! }, bytes), trace.segments, valid)
    }

    private class Trace {
        val segments = mutableListOf<SegmentTrace>()
        var current: SegmentTrace? = null
        var readStart = 0
        var readEnd = 0
        fun begin(position: Int) { current = SegmentTrace(position, position, null) }
        fun finish(position: Int, success: Boolean) {
            current?.let {
                segments += it.copy(endBit = position, issue = if (success) null else it.issue ?: Issue.INVALID_VALUE,
                    problemStartBit = if (success) null else readStart,
                    problemEndBit = if (success) null else readEnd)
            }
            current = null
        }
        fun issue(value: Issue) { current = current?.copy(issue = value) }
        fun count(value: Int, mode: Int, available: Int): Int {
            val required = requireNotNull(QrPayloadLayout.payloadBits(mode, value))
            current = current?.copy(count = value, requiredBits = required, availableBits = available)
            return value
        }
    }

    private fun validate(data: ByteArray, version: Int, allowIncomplete: Boolean,
        modes: MutableList<Int>? = null, byteSegments: MutableList<Pair<Int, ByteArray>>? = null,
        unknownMasks: IntArray? = null, bitLength: Int = data.size * 8,
        onTerminator: ((Int) -> Unit)? = null, onPayloadEnd: ((Int) -> Unit)? = null, onMode: ((Int) -> Unit)? = null,
        trace: Trace? = null, assumeUtf8: Boolean = false, validateExplicitUtf8: Boolean = true,
        onInvalidUtf8: () -> Unit = {}, onAssumedSegment: () -> Unit = {},
    ): Boolean = try {
        val bits = Bits(data, unknownMasks, bitLength, trace)
        var currentEci: Int? = null
        var explicitUtf8States = 1 shl Utf8State.BOUNDARY.ordinal
        var continuesInNextSymbol = false
        fun finishExplicitUtf8(endOfSymbol: Boolean = false) {
            if (endOfSymbol && continuesInNextSymbol) return
            if (validateExplicitUtf8 && currentEci == 26 && explicitUtf8States and 1 == 0) {
                onInvalidUtf8()
                error("Incomplete explicit UTF-8 character")
            }
        }
        fun explicitByte(low: Int, high: Int = low) {
            if (!validateExplicitUtf8) return
            if (currentEci != 26) {
                explicitUtf8States = 1
                return
            }
            var next = 0
            // ECI spans compaction segments and may continue across Structured Append symbols.
            for (state in Utf8State.entries) if (explicitUtf8States and (1 shl state.ordinal) != 0) {
                state.transitions(low, high, 3) { _, _, target -> next = next or (1 shl target.ordinal) }
            }
            if (next == 0) {
                onInvalidUtf8()
                error("Invalid explicit UTF-8 byte")
            }
            explicitUtf8States = next
        }
        var structuredAppendSeen = false
        var fnc1Seen = false
        var modeSeenAfterStructuredAppend = false
        while (bits.available >= 4) {
            onMode?.invoke(bits.position)
            trace?.begin(bits.position)
            var completed = false
            try {
                val mode = bits.read(4)
                trace?.current = trace.current?.copy(mode = mode)
                if (mode == 1 || mode == 2 || mode == 4 || mode == 8 || mode == 13) modes?.add(mode)
                when (mode) {
                    0 -> {
                        finishExplicitUtf8(endOfSymbol = true)
                        if (onTerminator == null) return validateFiller(bits)
                        onPayloadEnd?.invoke(bits.position - 4)
                        onTerminator(bits.position - 4)
                        completed = true
                        return true
                    }
                    1, 2 -> {
                        modeSeenAfterStructuredAppend = true
                        val count = bits.readCount(mode, version)
                        // Both modes expand to ASCII bytes, incompatible with a pending continuation.
                        if (count > 0) {
                            finishExplicitUtf8()
                            explicitUtf8States = 1
                        }
                        if (mode == 1) validateNumeric(bits, count) else validateAlphanumeric(bits, count)
                    }
                    3 -> {
                        // Structured Append, when present, is the first mode and occurs only once.
                        if (structuredAppendSeen || modeSeenAfterStructuredAppend || fnc1Seen) {
                            trace?.issue(Issue.INVALID_CONTEXT)
                            return false
                        }
                        structuredAppendSeen = true
                        val sequence = bits.read(8)
                        val index = sequence ushr 4
                        val totalMinusOne = sequence and 15
                        if (totalMinusOne == 0 || index > totalMinusOne) return false
                        bits.skip(8) // Parity belongs to the complete set; one symbol cannot verify it.
                        continuesInNextSymbol = index < totalMinusOne
                        if (index > 0) explicitUtf8States = (1 shl Utf8State.entries.size) - 1
                    }
                    4 -> {
                        modeSeenAfterStructuredAppend = true
                        val count = bits.readCount(mode, version)
                        if (count > 0 && currentEci != 26) explicitUtf8States = 1
                        val assumedUtf8 = assumeUtf8 && currentEci == null
                        // Diagnostic scans retain malformed text for segment-level reporting.
                        val explicitUtf8 = currentEci == 26 && validateExplicitUtf8
                        if (assumedUtf8 || explicitUtf8) {
                            if (assumedUtf8) onAssumedSegment()
                            val captured = if (byteSegments != null) {
                                bits.requireAvailable(count * 8)
                                ByteArray(count)
                            } else null
                            if (explicitUtf8) {
                                repeat(count) { index ->
                                    val width = minOf(8, bits.available)
                                    val low = bits.read(width) shl (8 - width)
                                    explicitByte(low, low + (1 shl (8 - width)) - 1)
                                    if (width < 8) throw IncompletePayload
                                    captured?.set(index, low.toByte())
                                }
                            } else if (!validateUtf8(bits, count, captured)) {
                                onInvalidUtf8()
                                return false
                            }
                            if (captured != null) byteSegments!!.add(modes!!.size to captured)
                        } else if (byteSegments == null) bits.skip(count * 8)
                        else {
                            bits.requireAvailable(count * 8)
                            byteSegments.add(modes!!.size to ByteArray(count) { bits.read(8).toByte() })
                        }
                    } // Byte
                    5 -> { // FNC1 first position
                        if (fnc1Seen || modeSeenAfterStructuredAppend) {
                            trace?.issue(Issue.INVALID_CONTEXT)
                            return false
                        }
                        fnc1Seen = true
                        modeSeenAfterStructuredAppend = true
                    }
                    7 -> {
                        modeSeenAfterStructuredAppend = true
                        val eci = validateEci(bits)
                        if (eci != currentEci) {
                            finishExplicitUtf8()
                            if (currentEci != null) explicitUtf8States = 1
                        }
                        currentEci = eci
                        trace?.current = trace.current?.copy(eci = eci)
                    }
                    8 -> {
                        modeSeenAfterStructuredAppend = true
                        validateCharacters(bits, bits.readCount(mode, version), kanjiValues) { value ->
                            val assembled = ((value / 0xC0) shl 8) or (value % 0xC0)
                            val code = assembled + if (assembled < 0x1F00) 0x8140 else 0xC140
                            explicitByte(code ushr 8)
                            explicitByte(code and 255)
                        }
                    }
                    9 -> { // FNC1 second position application indicator
                        if (fnc1Seen || modeSeenAfterStructuredAppend) {
                            trace?.issue(Issue.INVALID_CONTEXT)
                            return false
                        }
                        fnc1Seen = true
                        modeSeenAfterStructuredAppend = true
                        bits.skip(8)
                    }
                    13 -> {
                        modeSeenAfterStructuredAppend = true
                        if (bits.read(4) != 1) return false // QR supports the GB2312 subset only.
                        validateCharacters(bits, bits.readCount(mode, version), hanziValues) { value ->
                            val assembled = ((value / 0x60) shl 8) or (value % 0x60)
                            val code = assembled + if (assembled < 0xA00) 0xA1A1 else 0xA6A1
                            explicitByte(code ushr 8)
                            explicitByte(code and 255)
                        }
                    }
                    else -> { trace?.issue(Issue.UNSUPPORTED_MODE); return false }
                }
                completed = true
            } finally { trace?.finish(bits.position, completed) }
        }
        onPayloadEnd?.invoke(bits.position)
        // A prefix may end inside the next mode indicator. Only a complete stream must
        // interpret these remaining bits as a shortened terminator.
        if (!allowIncomplete) {
            finishExplicitUtf8(endOfSymbol = true)
            if (bits.available > 0) onMode?.invoke(bits.position)
            if (bits.available > 0) {
                trace?.begin(bits.position)
                var completed = false
                try {
                    while (bits.available > 0) if (bits.read(1) != 0) return false
                    completed = true
                } finally { trace?.finish(bits.position, completed) }
            }
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

    private fun validateCharacters(bits: Bits, count: Int, validValues: BooleanArray, onValue: (Int) -> Unit = {}) {
        repeat(count) {
            val value = bits.read(13)
            if (!validValues[value]) error("Invalid QR character code")
            onValue(value)
        }
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

    private fun validateUtf8(bits: Bits, count: Int, captured: ByteArray? = null): Boolean {
        var state = Utf8State.BOUNDARY
        repeat(count) { index ->
            // BYTE bodies need not be codeword-aligned. Even the high nibble at a
            // block boundary can already contradict every possible continuation.
            val width = minOf(8, bits.available)
            val low = bits.read(width) shl (8 - width)
            val high = low + (1 shl (8 - width)) - 1
            var next: Utf8State? = null
            state.transitions(low, high, count - index - 1) { _, _, target -> next = target }
            state = next ?: return false
            if (width < 8) throw IncompletePayload
            captured?.set(index, low.toByte())
        }
        return true
    }

    private fun validateEci(bits: Bits): Int {
        val first = bits.read(8)
        val assignment = when {
            (first and 0x80) == 0 -> first
            (first and 0xC0) == 0x80 -> ((first and 0x3F) shl 8) or bits.read(8)
            (first and 0xE0) == 0xC0 -> ((first and 0x1F) shl 16) or bits.read(16)
            else -> { bits.invalidEci(); error("Invalid ECI designator") }
        }
        if (assignment > 999999) {
            bits.invalidEci()
            error("ECI assignment is out of range")
        }
        return assignment
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

    private fun countBits(mode: Int, version: Int): Int =
        QrPayloadLayout.characterCountBits(mode, version) ?: error("Mode has no character count")

    private class Bits(private val bytes: ByteArray, private val unknownMasks: IntArray?, private val bitLength: Int, private val trace: Trace? = null) {
        var position = 0
            private set
        val available: Int get() = bitLength - position
        val bitOffset: Int get() = position and 7

        fun readCount(mode: Int, version: Int): Int {
            val count = read(countBits(mode, version))
            return trace?.count(count, mode, available) ?: count
        }
        fun invalidEci() { trace?.issue(Issue.INVALID_ECI) }
        fun requireAvailable(width: Int) {
            if (width > available) {
                trace?.readStart = position
                trace?.readEnd = bitLength
                trace?.issue(Issue.INCOMPLETE)
                throw IncompletePayload
            }
        }
        fun read(width: Int): Int {
            require(width in 0..31)
            trace?.readStart = position
            trace?.readEnd = (position + width).coerceAtMost(bitLength)
            requireAvailable(width)
            var value = 0
            repeat(width) {
                if (unknownMasks != null && (unknownMasks[position / 8] and (1 shl (7 - position % 8))) != 0) {
                    trace?.readStart = position
                    trace?.readEnd = position + 1
                    trace?.issue(Issue.UNKNOWN_BITS)
                    error("Unknown input bit")
                }
                value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }

        fun skip(width: Int) {
            require(width >= 0)
            trace?.readStart = position
            trace?.readEnd = (position + width).coerceAtMost(bitLength)
            requireAvailable(width)
            if (unknownMasks == null) position += width
            else repeat(width) { read(1) }
        }
    }

    private object IncompletePayload : RuntimeException()
}
