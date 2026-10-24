package io.github.earthdiver.qrrepair.core

import kotlin.math.min

/**
 * Conservative QR data-stream constraint propagation.
 *
 * Plans intentionally store only compact structural metadata. Earlier versions materialized a
 * Map<Int, Forced> covering most of the remaining QR payload for every candidate plan. During
 * one-finder recovery, a wrong high-version geometry can create thousands of such plans and
 * exhaust the unit-test / app heap before RS decoding gets a chance to reject the geometry.
 *
 * This implementation computes forced bits on demand and precomputes two alternating pad-pattern
 * suffix scores, keeping memory bounded by O(totalBits + maxPlans) rather than
 * O(totalBits * maxPlans).
 */
object PayloadConstraintSolver {
    enum class ConstraintKind { MODE, COUNT, DATA_VALUE, TERMINATOR, BYTE_ALIGNMENT, PAD, STRUCTURE }

    data class FilledBit(
        val dataBitIndex: Int,
        val blockIndex: Int,
        val localByteIndex: Int,
        /** 0 is the MSB and 7 is the LSB. */
        val bitFromMsb: Int,
        val value: Int,
        val kind: ConstraintKind,
    )

    /** Compact hard condition, checked again after RS may have changed supplied bits. */
    data class TrustedStructure(val mode: Int, val count: Int, val headerBits: Int,
        val payloadEnd: Int, val totalBits: Int) {
        fun matches(data: ByteArray): Boolean {
            if (data.size * 8 != totalBits) return false
            val alignmentEnd = min(totalBits, ((min(totalBits, payloadEnd + 4) + 7) / 8) * 8)
            for (bit in 0 until totalBits) {
                val expected = when {
                    bit < 4 -> (mode shr (3 - bit)) and 1
                    bit < headerBits -> (count shr (headerBits - 1 - bit)) and 1
                    bit < payloadEnd -> continue
                    bit < alignmentEnd -> 0
                    else -> {
                        val pad = if (((bit - alignmentEnd) / 8) % 2 == 0) 0xEC else 0x11
                        (pad shr (7 - bit % 8)) and 1
                    }
                }
                if (((data[bit / 8].toInt() shr (7 - bit % 8)) and 1) != expected) return false
            }
            return true
        }
    }

    data class Result(
        val filledBits: Int,
        val viablePlans: Int,
        val inferredMode: Int?,
        val inferredCount: Int?,
        /** Matching suffix bits from the original observation when [apply] receives one. */
        val evidenceBits: Int = 0,
        val filled: List<FilledBit> = emptyList(),
        /** False when [maxPlans] stopped enumeration before every compatible structure was seen. */
        val exhaustive: Boolean = true,
        /** True when an explicitly trusted, fully observed mode/count header forced the suffix. */
        val forcedByTrustedHeader: Boolean = false,
        val trustedStructure: TrustedStructure? = null,
        /** A suffix hypothesis was withheld by the evidence or mismatch gate. */
        val suffixBlocked: Boolean = false,
    )

    private data class Ref(val blockIndex: Int, val block: RsBlockWork, val byteIndex: Int)
    private data class Forced(val value: Int, val kind: ConstraintKind)
    private data class Plan(
        val mode: Int,
        val count: Int,
        val countBits: Int,
        val headerBits: Int,
        val payloadEnd: Int,
        val mismatches: Int,
        val fillerKnownMatches: Int,
    )

    private data class DataGroup(val start: Int, val width: Int, val maxValue: Int)

    /** [evidenceInput] affects the reported evidence only, never plan selection or propagation. */
    fun apply(
        de: DeinterleavedQr,
        version: Int,
        maxPlans: Int = 4096,
        trustKnownCount: Boolean = false,
        cancellation: RecoveryCancellation = RecoveryCancellation.NONE,
        evidenceInput: DeinterleavedQr? = null,
        /** Null permits unlimited ordinary suffix errors; final validation must use original observations. */
        maxSuffixMismatches: Int? = 0,
    ): Result {
        require(maxSuffixMismatches == null || maxSuffixMismatches >= 0)
        cancellation.check()
        val refs = buildList {
            for ((blockIndex, block) in de.blocks.withIndex()) {
                for (i in 0 until block.numDataCodewords) add(Ref(blockIndex, block, i))
            }
        }
        val totalBits = refs.size * 8
        if (totalBits < 12) return Result(0, 0, null, null, 0)

        // -1 = unknown, otherwise 0/1. Build this once; candidate scoring below is allocation-free.
        val known = IntArray(totalBits) { -1 }
        for (bitIndex in 0 until totalBits) {
            val ref = refs[bitIndex / 8]
            val bit = 7 - bitIndex % 8
            if ((ref.block.unknownMasks[ref.byteIndex] and (1 shl bit)) == 0) {
                known[bitIndex] = (ref.block.codewords[ref.byteIndex] shr bit) and 1
            }
        }
        val evidenceKnown = evidenceInput?.let { input ->
            require(input.blocks.size == de.blocks.size)
            require(input.blocks.indices.all { index ->
                input.blocks[index].numDataCodewords == de.blocks[index].numDataCodewords &&
                    input.blocks[index].codewords.size == de.blocks[index].codewords.size
            })
            IntArray(totalBits) { bitIndex ->
                val ref = refs[bitIndex / 8]
                val block = input.blocks[ref.blockIndex]
                val bit = 7 - bitIndex % 8
                if ((block.unknownMasks[ref.byteIndex] and (1 shl bit)) != 0) -1
                else (block.codewords[ref.byteIndex] shr bit) and 1
            }
        } ?: known

        fun bitPatternValue(value: Int, width: Int, offset: Int): Int =
            (value shr (width - 1 - offset)) and 1

        fun modeCountBits(mode: Int): Int {
            val group = when (version) { in 1..9 -> 0; in 10..26 -> 1; else -> 2 }
            return when (mode) {
                1 -> intArrayOf(10, 12, 14)[group]      // Numeric
                2 -> intArrayOf(9, 11, 13)[group]       // Alphanumeric
                4 -> intArrayOf(8, 16, 16)[group]       // Byte
                8 -> intArrayOf(8, 10, 12)[group]       // Kanji
                else -> 0
            }
        }

        fun payloadBits(mode: Int, count: Int): Int = when (mode) {
            1 -> (count / 3) * 10 + when (count % 3) { 1 -> 4; 2 -> 7; else -> 0 }
            2 -> (count / 2) * 11 + if ((count and 1) != 0) 6 else 0
            4 -> count * 8
            8 -> count * 13
            else -> Int.MAX_VALUE
        }

        fun dataGroups(plan: Plan): List<DataGroup> = buildList {
            var cursor = plan.headerBits
            when (plan.mode) {
                1 -> {
                    repeat(plan.count / 3) { add(DataGroup(cursor, 10, 999)); cursor += 10 }
                    when (plan.count % 3) {
                        1 -> add(DataGroup(cursor, 4, 9))
                        2 -> add(DataGroup(cursor, 7, 99))
                    }
                }
                2 -> {
                    repeat(plan.count / 2) { add(DataGroup(cursor, 11, 45 * 45 - 1)); cursor += 11 }
                    if ((plan.count and 1) != 0) add(DataGroup(cursor, 6, 44))
                }
            }
        }

        fun groupHasValidValue(group: DataGroup): Boolean {
            var minimum = 0
            for (offset in 0 until group.width) {
                if (known[group.start + offset] == 1) {
                    minimum = minimum or (1 shl (group.width - 1 - offset))
                }
            }
            return minimum <= group.maxValue
        }

        fun payloadValuesCompatible(plan: Plan): Boolean =
            dataGroups(plan).all(::groupHasValidValue)

        // Two suffix tables are sufficient for the alternating EC/11 pad stream. `phase` is the
        // parity of the byte at which padding starts: that byte must be EC, the next 11, etc.
        val padMismatchSuffix = Array(2) { IntArray(totalBits + 1) }
        val padMatchSuffix = Array(2) { IntArray(totalBits + 1) }
        val evidencePadMatchSuffix = if (evidenceInput == null) padMatchSuffix
            else Array(2) { IntArray(totalBits + 1) }
        for (phase in 0..1) {
            var i = totalBits - 1
            while (i >= 0) {
                val byteIndex = i / 8
                val bitFromMsb = i % 8
                val padByte = if ((byteIndex and 1) == phase) 0xEC else 0x11
                val expected = (padByte shr (7 - bitFromMsb)) and 1
                val observed = known[i]
                padMismatchSuffix[phase][i] = padMismatchSuffix[phase][i + 1] +
                    if (observed >= 0 && observed != expected) 1 else 0
                padMatchSuffix[phase][i] = padMatchSuffix[phase][i + 1] +
                    if (observed >= 0 && observed == expected) 1 else 0
                if (evidenceInput != null) {
                    evidencePadMatchSuffix[phase][i] = evidencePadMatchSuffix[phase][i + 1] +
                        if (evidenceKnown[i] == expected) 1 else 0
                }
                i--
            }
        }

        fun fillerScore(payloadEnd: Int): Pair<Int, Int> {
            var cursor = payloadEnd
            val terminatorEnd = min(totalBits, cursor + 4)
            var mismatches = 0
            var matches = 0
            while (cursor < terminatorEnd) {
                val observed = known[cursor]
                if (observed >= 0) {
                    if (observed == 0) matches++ else mismatches++
                }
                cursor++
            }
            while (cursor < totalBits && cursor % 8 != 0) {
                val observed = known[cursor]
                if (observed >= 0) {
                    if (observed == 0) matches++ else mismatches++
                }
                cursor++
            }
            if (cursor < totalBits) {
                val phase = (cursor / 8) and 1
                mismatches += padMismatchSuffix[phase][cursor]
                matches += padMatchSuffix[phase][cursor]
            }
            return mismatches to matches
        }

        fun observedFillerMatches(payloadEnd: Int): Int {
            val alignmentEnd = min(totalBits, ((min(totalBits, payloadEnd + 4) + 7) / 8) * 8)
            var matches = 0
            for (bit in payloadEnd until alignmentEnd) if (evidenceKnown[bit] == 0) matches++
            if (alignmentEnd < totalBits) {
                matches += evidencePadMatchSuffix[(alignmentEnd / 8) and 1][alignmentEnd]
            }
            return matches
        }

        fun forcedAt(plan: Plan, bitIndex: Int): Forced? {
            if (bitIndex !in 0 until totalBits) return null
            if (bitIndex < 4) {
                return Forced(bitPatternValue(plan.mode, 4, bitIndex), ConstraintKind.MODE)
            }
            if (bitIndex < plan.headerBits) {
                val offset = bitIndex - 4
                return Forced(bitPatternValue(plan.count, plan.countBits, offset), ConstraintKind.COUNT)
            }
            if (bitIndex < plan.payloadEnd) return null

            var cursor = plan.payloadEnd
            val terminatorEnd = min(totalBits, cursor + 4)
            if (bitIndex < terminatorEnd) return Forced(0, ConstraintKind.TERMINATOR)
            cursor = terminatorEnd
            val alignmentEnd = min(totalBits, ((cursor + 7) / 8) * 8)
            if (bitIndex < alignmentEnd) return Forced(0, ConstraintKind.BYTE_ALIGNMENT)
            cursor = alignmentEnd
            if (bitIndex < cursor || cursor >= totalBits) return null

            val byteOffset = (bitIndex - cursor) / 8
            val bitFromMsb = (bitIndex - cursor) % 8
            val padByte = if ((byteOffset and 1) == 0) 0xEC else 0x11
            return Forced((padByte shr (7 - bitFromMsb)) and 1, ConstraintKind.PAD)
        }

        val plans = ArrayList<Plan>(min(maxPlans, 4096))
        var truncated = false
        val modes = intArrayOf(1, 2, 4, 8)
        loop@ for (mode in modes) {
            val countBits = modeCountBits(mode)
            val headerBits = 4 + countBits
            if (headerBits > totalBits) continue

            // Reject incompatible mode bits before enumerating counts.
            var modeCompatible = true
            for (i in 0 until 4) {
                val observed = known[i]
                val expected = bitPatternValue(mode, 4, i)
                if (observed >= 0 && observed != expected) { modeCompatible = false; break }
            }
            if (!modeCompatible) continue

            val maxCount = when (mode) {
                1 -> (totalBits - headerBits) * 3 / 10 + 2
                2 -> (totalBits - headerBits) * 2 / 11 + 1
                4 -> (totalBits - headerBits) / 8
                8 -> (totalBits - headerBits) / 13
                else -> 0
            }.coerceAtLeast(0).coerceAtMost((1 shl min(countBits, 20)) - 1)

            for (count in 0..maxCount) {
                if (count % 64 == 0) cancellation.check()
                if (plans.size >= maxPlans) {
                    truncated = true
                    break@loop
                }
                var countCompatible = true
                for (i in 0 until countBits) {
                    val observed = known[4 + i]
                    val expected = bitPatternValue(count, countBits, i)
                    if (observed >= 0 && observed != expected) { countCompatible = false; break }
                }
                if (!countCompatible) continue

                val end = headerBits + payloadBits(mode, count)
                if (end > totalBits) continue
                val (mismatches, fillerKnownMatches) = fillerScore(end)
                plans += Plan(mode, count, countBits, headerBits, end, mismatches, fillerKnownMatches)
            }
        }

        if (plans.isEmpty()) return Result(0, 0, null, null, 0)
        // Common-bit propagation is sound only over the complete compatible-plan set. The plan
        // cap is a memory/time bound, not evidence that modes and counts later in enumeration are
        // impossible. In particular, a high-version erased header can otherwise fill the cap with
        // numeric plans before byte or Kanji modes are considered and then force a false header.
        if (truncated) return Result(0, plans.size, null, null, 0, exhaustive = false)
        // A trusted, fully observed header is an explicit instruction to force the suffix. Unlike
        // ordinary conservative propagation, this plan may contradict image-observed suffix bits:
        // those contradictions are precisely the known errors this option is intended to remove
        // before RS decoding. Payload bits themselves are never overwritten here.
        // A damaged numeric/alphanumeric body may be outside its value range until RS repairs it.
        // Trust applies to the header and suffix, not to the observed body.
        val trustedHeaderPlan = if (trustKnownCount) plans.singleOrNull { plan ->
            (0 until plan.headerBits).all { known[it] >= 0 }
        } else null
        val boundedHeaderPlan = if (!trustKnownCount) plans.singleOrNull { plan ->
            (0 until plan.headerBits).all { known[it] >= 0 }
        }?.takeIf { plan ->
            plan.mismatches > 0 && (maxSuffixMismatches == null || plan.mismatches <= maxSuffixMismatches) &&
                payloadValuesCompatible(plan)
        } else null
        val zeroMismatch = if (boundedHeaderPlan != null) listOf(boundedHeaderPlan) else if (trustedHeaderPlan == null) {
            val minMismatch = plans.minOf { it.mismatches }
            // Unresolved headers cannot justify replacing observed suffix bits.
            if (minMismatch != 0) return Result(0, 0, null, null, 0, suffixBlocked = true)
            plans.filter { it.mismatches == 0 && payloadValuesCompatible(it) }
                .also { if (it.isEmpty()) return Result(0, 0, null, null, 0) }
        } else emptyList()

        // A single-segment assumption normally needs evidence from the region after the payload.
        // Without this, an erased second segment could be mistaken for terminator/padding.
        val bestEvidence = trustedHeaderPlan?.fillerKnownMatches ?: zeroMismatch.maxOf { it.fillerKnownMatches }
        val viable = trustedHeaderPlan?.let(::listOf)
            ?: zeroMismatch.filter { it.fillerKnownMatches == bestEvidence }
        // Priority assignments may support a trial's structure, but are not observed evidence.
        // Keep the trial score above for eligibility and report only the original known suffix.
        val reportedEvidence = if (evidenceInput == null) bestEvidence
            else viable.maxOf { observedFillerMatches(it.payloadEnd) }
        val inferredMode = viable.map { it.mode }.distinct().singleOrNull()
        val inferredCount = viable.map { it.count }.distinct().singleOrNull()
        if (trustedHeaderPlan == null && bestEvidence < 6) {
            return Result(0, viable.size, inferredMode, inferredCount, reportedEvidence, suffixBlocked = true)
        }

        val filledTrace = mutableListOf<FilledBit>()

        // A bit is safe to propagate only if *every* viable plan structurally forces it to the same
        // value. All plans force 0..<minHeaderBits and maxPayloadEnd..<totalBits; anything between
        // those ranges may be payload under at least one viable plan and must remain untouched.
        val minHeaderBits = viable.minOf { it.headerBits }
        val maxPayloadEnd = viable.maxOf { it.payloadEnd }
        fun tryFill(bitIndex: Int) {
            val first = forcedAt(viable[0], bitIndex) ?: return
            var kind = first.kind
            for (i in 1 until viable.size) {
                val other = forcedAt(viable[i], bitIndex) ?: return
                if (other.value != first.value) return
                if (other.kind != kind) kind = ConstraintKind.STRUCTURE
            }

            val ref = refs[bitIndex / 8]
            val bit = 7 - bitIndex % 8
            val mask = 1 shl bit
            val wasUnknown = (ref.block.unknownMasks[ref.byteIndex] and mask) != 0
            val observed = (ref.block.codewords[ref.byteIndex] shr bit) and 1
            if (!wasUnknown) {
                // Only a resolved header may justify replacing an observed suffix bit.
                // Header and payload values remain untouched. Platform-level USER pins are
                // validated separately and reject a candidate when manual-bit locking is enabled.
                val suffixPlan = trustedHeaderPlan ?: boundedHeaderPlan
                if (suffixPlan == null || bitIndex < suffixPlan.payloadEnd || observed == first.value) return
            }
            if (first.value == 1) ref.block.codewords[ref.byteIndex] = ref.block.codewords[ref.byteIndex] or mask
            else ref.block.codewords[ref.byteIndex] = ref.block.codewords[ref.byteIndex] and mask.inv()
            ref.block.unknownMasks[ref.byteIndex] = ref.block.unknownMasks[ref.byteIndex] and mask.inv()
            filledTrace += FilledBit(
                dataBitIndex = bitIndex,
                blockIndex = ref.blockIndex,
                localByteIndex = ref.byteIndex,
                bitFromMsb = bitIndex % 8,
                value = first.value,
                kind = kind,
            )
        }

        for (bitIndex in 0 until minHeaderBits) tryFill(bitIndex)
        for (bitIndex in maxPayloadEnd until totalBits) tryFill(bitIndex)

        // Numeric and alphanumeric groups have unused binary values. Only when mode and count are
        // common to every viable plan do we use those ranges, and only bits shared by every valid
        // value consistent with the observations are propagated.
        if (inferredMode != null && inferredCount != null && inferredMode in setOf(1, 2)) {
            for (group in dataGroups(viable[0])) {
                cancellation.check()
                val values = (0..group.maxValue).filter { value ->
                    (0 until group.width).all { offset ->
                        val observed = known[group.start + offset]
                        observed < 0 || observed == bitPatternValue(value, group.width, offset)
                    }
                }
                if (values.isEmpty()) continue
                for (offset in 0 until group.width) {
                    val bitIndex = group.start + offset
                    if (known[bitIndex] >= 0) continue
                    val value = bitPatternValue(values[0], group.width, offset)
                    if (values.all { bitPatternValue(it, group.width, offset) == value }) {
                        val ref = refs[bitIndex / 8]
                        val bit = 7 - bitIndex % 8
                        val mask = 1 shl bit
                        if (value == 1) ref.block.codewords[ref.byteIndex] = ref.block.codewords[ref.byteIndex] or mask
                        else ref.block.codewords[ref.byteIndex] = ref.block.codewords[ref.byteIndex] and mask.inv()
                        ref.block.unknownMasks[ref.byteIndex] = ref.block.unknownMasks[ref.byteIndex] and mask.inv()
                        filledTrace += FilledBit(
                            dataBitIndex = bitIndex,
                            blockIndex = ref.blockIndex,
                            localByteIndex = ref.byteIndex,
                            bitFromMsb = bitIndex % 8,
                            value = value,
                            kind = ConstraintKind.DATA_VALUE,
                        )
                    }
                }
            }
        }

        return Result(
            filledBits = filledTrace.size,
            viablePlans = viable.size,
            inferredMode = inferredMode,
            inferredCount = inferredCount,
            evidenceBits = reportedEvidence,
            filled = filledTrace,
            forcedByTrustedHeader = trustedHeaderPlan != null,
            trustedStructure = trustedHeaderPlan?.let {
                TrustedStructure(it.mode, it.count, it.headerBits, it.payloadEnd, totalBits)
            },
        )
    }
}
