package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.ZxingPayloadBridge

/** Diagnostic parsing only: no result here is accepted as a repaired QR. */
internal object PostTerminatorAnalysis {
    enum class Status { UNREADABLE_PREFIX, NO_TERMINATOR, STANDARD_PADDING, ADDITIONAL_SEGMENTS, UNPARSED_TAIL, LIMIT }
    enum class Source { OBSERVED, CONVENTIONAL_RS, RECOVERY }
    data class Group(val startBit: Int, val endBit: Int, val modes: List<Int>, val text: String?)
    data class Report(
        val status: Status,
        val terminatorBit: Int? = null,
        val tail: String = "",
        val groups: List<Group> = emptyList(),
        val remainingStartBit: Int? = null,
    )
    data class View(
        val source: Source,
        val report: Report,
        val changedTailBits: Int = 0,
        val filledTailBits: Int = 0,
        val structureForced: Boolean = false,
    )
    data class Attempt(val views: List<View>, val rsCorrectionRejected: Boolean)

    /** Keeps input observations even when a conventional RS result conflicts with user constraints. */
    fun forAttempt(extraction: CodewordExtraction, version: Int, ecLevel: ErrorCorrectionLevel,
        conventional: ConventionalRsDiagnostic?, candidate: RecoveryCandidate? = null): Attempt {
        val de = QrBlockInterleaver.deinterleave(extraction, version, ecLevel)
        val observed = de.blocks.flatMap { b -> b.codewords.take(b.numDataCodewords) }.map { it.toByte() }.toByteArray()
        val unknown = de.blocks.flatMap { b -> b.unknownMasks.take(b.numDataCodewords) }.toIntArray()
        val views = mutableListOf(View(Source.OBSERVED, analyze(observed, version, ecLevel, unknown)))
        fun append(source: Source, bytes: ByteArray, forced: Boolean = false) {
            val report = analyze(bytes, version, ecLevel)
            val start = report.terminatorBit?.plus(4) ?: bytes.size * 8
            var changed = 0
            var filled = 0
            for (bit in start until bytes.size * 8) {
                val mask = 1 shl (7 - bit % 8)
                if (unknown[bit / 8] and mask != 0) filled++
                else if ((observed[bit / 8].toInt() xor bytes[bit / 8].toInt()) and mask != 0) changed++
            }
            views += View(source, report, changed, filled, forced)
        }
        val raw = conventional?.correctedInterleaved?.takeIf { conventional.settingsAccepted == true }
        val rsData = raw?.let {
            require(it.size == extraction.codewords.size)
            val restored = de.blocks.map { b -> IntArray(b.codewords.size) }
            de.rawLocations.forEachIndexed { i, location ->
                val loc = requireNotNull(location)
                restored[loc.blockIndex][loc.localIndex] = it[i]
            }
            de.blocks.indices.flatMap { i -> restored[i].take(de.blocks[i].numDataCodewords) }
                .map { value -> value.toByte() }.toByteArray()
        }
        if (rsData != null) append(Source.CONVENTIONAL_RS, rsData)
        candidate?.takeIf { it.dataBytes.size == observed.size }?.let {
            append(Source.RECOVERY, it.dataBytes, it.constraintFilledBits > 0)
        }
        return Attempt(views, conventional?.succeeded == true && conventional.settingsAccepted != true)
    }

    fun analyze(data: ByteArray, version: Int, ecLevel: ErrorCorrectionLevel,
        unknownMasks: IntArray = IntArray(data.size)): Report {
        require(version in 1..40 && data.size <= 2956 && unknownMasks.size == data.size)
        val original = data.indices.flatMap { i -> (7 downTo 0).map { bit ->
            if (unknownMasks[i] and (1 shl bit) != 0) null else (data[i].toInt() ushr bit) and 1
        } }
        fun scan(bits: List<Int?>): StrictQrPayloadValidator.Prefix? =
            StrictQrPayloadValidator.inspectPrefix(pack(bits), version, masks(bits), bits.size)
        var working = original
        var prefix = scan(working) ?: return Report(Status.UNREADABLE_PREFIX)
        val firstTerminator = prefix.terminatorBit ?: return Report(Status.NO_TERMINATOR)
        val tail = original.drop(firstTerminator + 4).joinToString("") { it?.toString() ?: "?" }
        val groups = mutableListOf<Group>()
        fun report(status: Status, remaining: Int? = null) = Report(status, firstTerminator, tail, groups.toList(), remaining)
        var removed = 0
        repeat(16) {
            val terminator = prefix.terminatorBit ?: return report(Status.ADDITIONAL_SEGMENTS)
            val originalStart = terminator + removed + 4
            if (canonicalFiller(original, originalStart))
                return report(if (groups.isEmpty()) Status.STANDARD_PADDING else Status.ADDITIONAL_SEGMENTS)
            val beforeText = decode(working.take(terminator), version, ecLevel)
            // Remove only the structurally located terminator in a private decoding view.
            // ECI and FNC1 state from earlier segments consequently remains in effect.
            working = working.take(terminator) + working.drop(terminator + 4)
            removed += 4
            val next = scan(working) ?: return report(Status.UNPARSED_TAIL, originalStart)
            val newModes = next.modes.drop(prefix.modes.size)
            if (newModes.isEmpty()) return report(Status.UNPARSED_TAIL, originalStart)
            val end = next.endBit
            val combinedText = decode(working.take(end), version, ecLevel)
            val text = if (beforeText != null && combinedText?.startsWith(beforeText) == true)
                combinedText.substring(beforeText.length) else null
            groups += Group(originalStart, end + removed, newModes, text)
            prefix = next
            if (next.terminatorBit == null) return report(Status.ADDITIONAL_SEGMENTS)
        }
        val remaining = prefix.endBit + removed + 4
        return if (canonicalFiller(original, remaining)) report(Status.ADDITIONAL_SEGMENTS)
            else report(Status.LIMIT, remaining)
    }

    private fun canonicalFiller(bits: List<Int?>, start: Int): Boolean {
        var position = start
        while (position % 8 != 0 && position < bits.size) if (bits[position++] != 0) return false
        var expected = 0xEC
        while (position < bits.size) {
            if (bits.size - position < 8) return false
            for (bit in 7 downTo 0) if (bits[position++] != ((expected ushr bit) and 1)) return false
            expected = expected xor 0xFD
        }
        return true
    }

    private fun decode(bits: List<Int?>, version: Int, ecLevel: ErrorCorrectionLevel): String? {
        if (bits.any { it == null }) return null
        return try { ZxingPayloadBridge.decodeData(pack(bits + listOf(0, 0, 0, 0)), version, ecLevel, null).text }
        catch (_: com.google.zxing.FormatException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    private fun pack(bits: List<Int?>): ByteArray = ByteArray((bits.size + 7) / 8) { index ->
        (0..7).fold(0) { value, bit -> (value shl 1) or (bits.getOrNull(index * 8 + bit) ?: 0) }.toByte()
    }
    private fun masks(bits: List<Int?>): IntArray = IntArray((bits.size + 7) / 8) { index ->
        (0..7).fold(0) { value, bit -> (value shl 1) or
            if (index * 8 + bit < bits.size && bits[index * 8 + bit] == null) 1 else 0 }
    }
}
