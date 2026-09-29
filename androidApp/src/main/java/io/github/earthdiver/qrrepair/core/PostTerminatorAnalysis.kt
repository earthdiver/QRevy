package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Diagnostic parsing only: no result here is accepted as a repaired QR. */
internal object PostTerminatorAnalysis {
    enum class Status { UNREADABLE_PREFIX, NO_TERMINATOR, STANDARD_PADDING, ADDITIONAL_SEGMENTS, UNPARSED_TAIL, LIMIT }
    enum class Source { OBSERVED, CONVENTIONAL_RS, RECOVERY }
    data class Group(val startBit: Int, val endBit: Int, val modes: List<Int>, val text: String?)
    data class CombinedContent(val mode: Int, val text: String?, val bytes: ByteArray?)
    enum class StartKind { IMMEDIATE, ZERO_MODES, BYTE_ALIGNED }
    enum class StopReason { UNKNOWN_MODE_BITS, UNSUPPORTED_MODE, INVALID_OR_INCOMPLETE_SEGMENT, NO_PAYLOAD }
    data class Boundary(val afterTerminatorBit: Int, val startBit: Int, val kind: StartKind)
    data class Stop(val bit: Int, val modeBits: String, val reason: StopReason)
    data class Report(
        val status: Status,
        val terminatorBit: Int? = null,
        val tail: String = "",
        val groups: List<Group> = emptyList(),
        val remainingStartBit: Int? = null,
        val combinedContent: CombinedContent? = null,
        val boundaries: List<Boundary> = emptyList(),
        val stop: Stop? = null,
        val paddingCandidateStartBit: Int? = null,
        val alternatives: List<Report> = emptyList(),
        val alternativesTruncated: Boolean = false,
        val trace: List<PayloadSegmentDiagnostics.Entry> = emptyList(),
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
        val rsData = conventionalData(de, conventional)
        if (rsData != null) append(Source.CONVENTIONAL_RS, rsData)
        candidate?.takeIf { it.dataBytes.size == observed.size }?.let {
            append(Source.RECOVERY, it.dataBytes, it.constraintFilledBits > 0)
        }
        return Attempt(views, conventional?.succeeded == true && conventional.settingsAccepted != true)
    }

    /** Reconstruct only a conventional RS result that satisfies the input constraints. */
    fun conventionalData(de: DeinterleavedQr, conventional: ConventionalRsDiagnostic?): ByteArray? {
        val raw = conventional?.correctedInterleaved?.takeIf { conventional.settingsAccepted == true }
        return raw?.let {
            if (it.size != de.rawLocations.size) return null
            val restored = de.blocks.map { b -> IntArray(b.codewords.size) }
            de.rawLocations.forEachIndexed { i, location ->
                val loc = requireNotNull(location)
                restored[loc.blockIndex][loc.localIndex] = it[i]
            }
            de.blocks.indices.flatMap { i -> restored[i].take(de.blocks[i].numDataCodewords) }
                .map { value -> value.toByte() }.toByteArray()
        }
    }

    fun analyze(data: ByteArray, version: Int, ecLevel: ErrorCorrectionLevel,
        unknownMasks: IntArray = IntArray(data.size)): Report {
        require(version in 1..40 && data.size <= 2956 && unknownMasks.size == data.size)
        val original = data.indices.flatMap { i -> (7 downTo 0).map { bit ->
            if (unknownMasks[i] and (1 shl bit) != 0) null else (data[i].toInt() ushr bit) and 1
        } }
        fun scan(bits: List<Int?>): StrictQrPayloadValidator.Prefix? =
            StrictQrPayloadValidator.inspectPrefix(pack(bits), version, masks(bits), bits.size)
        val main = scan(original) ?: return Report(Status.UNREADABLE_PREFIX)
        val firstTerminator = main.terminatorBit ?: return Report(Status.NO_TERMINATOR)
        val tail = original.drop(firstTerminator + 4).joinToString("") { it?.toString() ?: "?" }
        fun skipZeroModes(start: Int): Int {
            var position = start
            while (position + 4 <= original.size && (position until position + 4).all { original[it] == 0 })
                position += 4
            return position
        }
        val results = mutableListOf<Report>()
        var truncated = false
        // Bound branching as well as continuation depth. Keep the existing interpretation first.
        fun walk(working: List<Int?>, prefix: StrictQrPayloadValidator.Prefix, removed: Int,
            groups: List<Group>, boundaries: List<Boundary>, parsedText: String?,
            trace: List<PayloadSegmentDiagnostics.Entry>) {
            if (results.size >= 16) { truncated = true; return }
            fun report(status: Status, remaining: Int? = null, stop: Stop? = null,
                starts: List<Boundary> = boundaries, entries: List<PayloadSegmentDiagnostics.Entry> = trace,
                parsedGroups: List<Group> = groups, parsedPrefix: StrictQrPayloadValidator.Prefix = prefix,
                text: String? = parsedText) {
                val commonMode = parsedPrefix.modes.distinct().singleOrNull()
                val combined = if (main.modes.isNotEmpty() && parsedGroups.isNotEmpty() && commonMode != null) {
                    val bytes = if (commonMode == 4) parsedPrefix.byteSegments.flatMap { it.second.asList() }.toByteArray() else null
                    CombinedContent(commonMode, text, bytes)
                } else null
                results += Report(status, firstTerminator, tail, parsedGroups, remaining, combined, starts, stop, trace = entries)
            }
            val terminator = prefix.terminatorBit ?: run { report(Status.ADDITIONAL_SEGMENTS); return }
            val after = terminator + removed + 4
            if (canonicalFiller(original, after)) {
                report(if (trace.isEmpty()) Status.STANDARD_PADDING else Status.ADDITIONAL_SEGMENTS)
                return
            }
            if (boundaries.size >= 16) { report(Status.LIMIT, after); return }
            val direct = skipZeroModes(after)
            val choices = mutableListOf(Boundary(after, direct,
                if (direct == after) StartKind.IMMEDIATE else StartKind.ZERO_MODES))
            // Alignment is measured in the original codeword stream, never the spliced stream.
            val aligned = (after + 7) / 8 * 8
            if (aligned > after && aligned < original.size &&
                (after until aligned).all { original[it] == 0 }) {
                val alignedStart = skipZeroModes(aligned)
                if (alignedStart != direct) choices += Boundary(after, alignedStart, StartKind.BYTE_ALIGNED)
            }
            for (boundary in choices) {
                if (results.size >= 16) { truncated = true; break }
                val start = boundary.startBit
                val resume = start - removed
                val nextWorking = working.take(terminator) + working.drop(resume)
                val nextRemoved = removed + resume - terminator
                val inspection = StrictQrPayloadValidator.inspectSegments(pack(nextWorking), version,
                    masks(nextWorking), nextWorking.size)
                val next = inspection.prefix
                val interpreted = PayloadSegmentDiagnostics.interpret(nextWorking, version, ecLevel, inspection.segments)
                val additional = interpreted.filter { it.structure.startBit >= terminator && it.structure.mode != 0 &&
                    (it.structure.mode != null || it.structure.issue != null) }
                val entries = trace + additional.map { it.shifted(nextRemoved) }
                val starts = boundaries + boundary
                val completed = additional.filter { it.structure.issue == null && it.structure.mode in listOf(1, 2, 4, 8, 13) }
                fun textOf(items: List<PayloadSegmentDiagnostics.Entry>): String? =
                    if (items.any { it.text == null }) null else items.joinToString("") { it.text!! }
                val combinedText = textOf(interpreted.filter {
                    it.structure.issue == null && it.structure.mode in listOf(1, 2, 4, 8, 13)
                })
                val parsedGroups = if (completed.isEmpty()) groups else groups + Group(start,
                    next.endBit + nextRemoved, completed.map { it.structure.mode!! }, textOf(completed))
                if (!inspection.valid) {
                    val failure = additional.lastOrNull { it.structure.issue != null }?.structure
                    val failed = (failure?.startBit?.plus(nextRemoved) ?: start).coerceAtLeast(start)
                    val modeBits = original.drop(failed).take(4).joinToString("") { it?.toString() ?: "?" }
                    val reason = when {
                        '?' in modeBits -> StopReason.UNKNOWN_MODE_BITS
                        failure?.issue == StrictQrPayloadValidator.Issue.UNSUPPORTED_MODE -> StopReason.UNSUPPORTED_MODE
                        else -> StopReason.INVALID_OR_INCOMPLETE_SEGMENT
                    }
                    report(Status.UNPARSED_TAIL, failed, Stop(failed, modeBits, reason), starts, entries,
                        parsedGroups, next, combinedText)
                    continue
                }
                if (additional.isEmpty()) {
                    report(Status.UNPARSED_TAIL, start, Stop(start, "", StopReason.NO_PAYLOAD), starts, entries)
                    continue
                }
                // Control-only and zero-length payloads still have meaningful diagnostic boundaries.
                walk(nextWorking, next, nextRemoved, parsedGroups, starts, combinedText, entries)
            }
        }
        walk(original, main, 0, emptyList(), emptyList(), null, emptyList())
        // A matching suffix is evidence only; it is never removed from the raw tail.
        var suffix = original.size
        var expected: Int? = null
        for (byteIndex in data.indices.reversed()) {
            val value = data[byteIndex].toInt() and 255
            if (byteIndex * 8 < firstTerminator + 4 || unknownMasks[byteIndex] != 0 ||
                value !in listOf(0xEC, 0x11) || (expected != null && value != expected)) break
            suffix = byteIndex * 8
            expected = value xor 0xFD
        }
        if (suffix < original.size && (data[suffix / 8].toInt() and 255) == 0x11) suffix += 8
        val padding = suffix.takeIf { it < original.size }
        val reports = results.map { it.copy(paddingCandidateStartBit = padding) }
        return reports.first().copy(alternatives = reports.drop(1), alternativesTruncated = truncated)
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

    private fun pack(bits: List<Int?>): ByteArray = ByteArray((bits.size + 7) / 8) { index ->
        (0..7).fold(0) { value, bit -> (value shl 1) or (bits.getOrNull(index * 8 + bit) ?: 0) }.toByte()
    }
    private fun masks(bits: List<Int?>): IntArray = IntArray((bits.size + 7) / 8) { index ->
        (0..7).fold(0) { value, bit -> (value shl 1) or
            if (index * 8 + bit < bits.size && bits[index * 8 + bit] == null) 1 else 0 }
    }
}
