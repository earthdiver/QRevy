package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText
import com.google.zxing.qrcode.decoder.Version
import kotlin.math.abs

enum class RepairReasonKind {
    USER_OVERRIDE,
    RS_ERASURE_RECOVERY,
    RS_ERROR_CORRECTION,
    FINDER_PATTERN,
    SEPARATOR,
    TIMING_PATTERN,
    ALIGNMENT_PATTERN,
    FORMAT_INFORMATION,
    VERSION_INFORMATION,
    FIXED_DARK_MODULE,
    REMAINDER_BITS,
    PAYLOAD_MODE,
    PAYLOAD_COUNT,
    PAYLOAD_DATA_VALUE,
    PAYLOAD_TERMINATOR,
    PAYLOAD_BYTE_ALIGNMENT,
    PAYLOAD_PADDING,
    PAYLOAD_STRUCTURE,
    PARTIAL_BIT_SEARCH,
    OTHER,
}

enum class RepairFilter {
    ALL_CHANGED,
    RS,
    QR_STRUCTURE,
    PAYLOAD_CONSTRAINT,
    USER,
    CANDIDATE_DIFF,
}

data class ModuleRepairTrace(
    val x: Int,
    val y: Int,
    val originalValue: ModuleValue,
    val finalValue: ModuleValue,
    val originalSource: ModuleSource,
    val finalSource: ModuleSource,
    val primaryReason: RepairReasonKind,
    val supportingReasons: List<RepairReasonKind> = emptyList(),
    val codewordIndex: Int? = null,
    val bitIndex: Int? = null,
    val rsBlockIndex: Int? = null,
    val rsLocalIndex: Int? = null,
    val originalCodewordValue: Int? = null,
    val originalUnknownMask: Int? = null,
    val correctedCodewordValue: Int? = null,
    val rsErrata: Boolean = false,
    val partialEnumerated: Boolean = false,
    /** Confidence stored on the input cell, including manual edits; never a correction-assigned value. */
    val originalConfidence: Float = 0f,
    val bitConstraintSolved: Boolean = false,
    val listDecoding: ListDecodingDerivation? = null,
    /** Present only for this coordinate when an explicit priority trial assigned it. */
    val priorityAssignment: ModuleValue? = null,
    /** A trial constraint overturned by RS, not evidence supporting the final bit. */
    val overturnedConstraint: RepairReasonKind? = null,
    val overturnedConstraintValue: Int? = null,
    val alternativeRsDerivations: List<PartialCodewordSolver.Derivation> = emptyList(),
) {
    val valueChanged: Boolean get() = originalValue != finalValue
    val provenanceChanged: Boolean get() = originalSource != finalSource
    val changed: Boolean get() = valueChanged || provenanceChanged

    fun matches(filter: RepairFilter): Boolean = when (filter) {
        RepairFilter.ALL_CHANGED -> changed
        RepairFilter.RS -> primaryReason in setOf(RepairReasonKind.RS_ERASURE_RECOVERY, RepairReasonKind.RS_ERROR_CORRECTION) ||
            supportingReasons.any { it in setOf(RepairReasonKind.RS_ERASURE_RECOVERY, RepairReasonKind.RS_ERROR_CORRECTION) }
        RepairFilter.QR_STRUCTURE -> primaryReason in STRUCTURAL_REASONS
        RepairFilter.PAYLOAD_CONSTRAINT -> primaryReason in PAYLOAD_REASONS ||
            primaryReason == RepairReasonKind.PARTIAL_BIT_SEARCH || supportingReasons.any { it in PAYLOAD_REASONS || it == RepairReasonKind.PARTIAL_BIT_SEARCH }
        RepairFilter.USER -> primaryReason == RepairReasonKind.USER_OVERRIDE
        RepairFilter.CANDIDATE_DIFF -> false // supplied independently by the inspector
    }

    fun titleJa(): String = if (io.github.earthdiver.qrrepair.AppLanguageManager.isJapanese) when (primaryReason) {
        RepairReasonKind.USER_OVERRIDE -> "ユーザー指定"
        RepairReasonKind.RS_ERASURE_RECOVERY -> "RS消失復元"
        RepairReasonKind.RS_ERROR_CORRECTION -> "RS誤り訂正"
        RepairReasonKind.FINDER_PATTERN -> "位置検出パターン"
        RepairReasonKind.SEPARATOR -> "分離パターン"
        RepairReasonKind.TIMING_PATTERN -> "タイミングパターン"
        RepairReasonKind.ALIGNMENT_PATTERN -> "位置合せパターン"
        RepairReasonKind.FORMAT_INFORMATION -> "形式情報"
        RepairReasonKind.VERSION_INFORMATION -> "型番情報"
        RepairReasonKind.FIXED_DARK_MODULE -> "固定暗モジュール"
        RepairReasonKind.REMAINDER_BITS -> "残余ビット（マスク規則から確定）"
        RepairReasonKind.PAYLOAD_MODE -> "データモード制約"
        RepairReasonKind.PAYLOAD_COUNT -> "データ長制約"
        RepairReasonKind.PAYLOAD_DATA_VALUE -> "データ値域制約"
        RepairReasonKind.PAYLOAD_TERMINATOR -> "終端パターン制約"
        RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT -> "バイト境界制約"
        RepairReasonKind.PAYLOAD_PADDING -> "埋め草コード語制約"
        RepairReasonKind.PAYLOAD_STRUCTURE -> "データ構造制約"
        RepairReasonKind.PARTIAL_BIT_SEARCH -> "ビット単位候補探索"
        RepairReasonKind.OTHER -> "修復"
    } else when (primaryReason) {
        RepairReasonKind.USER_OVERRIDE -> "User-defined"
        RepairReasonKind.RS_ERASURE_RECOVERY -> "RS erasure recovery"
        RepairReasonKind.RS_ERROR_CORRECTION -> "RS error correction"
        RepairReasonKind.FINDER_PATTERN -> "Finder pattern"
        RepairReasonKind.SEPARATOR -> "Separator"
        RepairReasonKind.TIMING_PATTERN -> "Timing pattern"
        RepairReasonKind.ALIGNMENT_PATTERN -> "Alignment pattern"
        RepairReasonKind.FORMAT_INFORMATION -> "Format information"
        RepairReasonKind.VERSION_INFORMATION -> "Version information"
        RepairReasonKind.FIXED_DARK_MODULE -> "Fixed dark module"
        RepairReasonKind.REMAINDER_BITS -> "QR remainder bit (from mask rule)"
        RepairReasonKind.PAYLOAD_MODE -> "Payload mode constraint"
        RepairReasonKind.PAYLOAD_COUNT -> "Payload count constraint"
        RepairReasonKind.PAYLOAD_DATA_VALUE -> "Payload value-range constraint"
        RepairReasonKind.PAYLOAD_TERMINATOR -> "Terminator constraint"
        RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT -> "Byte-alignment constraint"
        RepairReasonKind.PAYLOAD_PADDING -> "Pad codeword constraint"
        RepairReasonKind.PAYLOAD_STRUCTURE -> "Payload structure constraint"
        RepairReasonKind.PARTIAL_BIT_SEARCH -> "Bit-level candidate search"
        RepairReasonKind.OTHER -> "Repair"
    }

    fun explanationJa(includeBasic: Boolean = true): String = buildString {
        if (includeBasic) append(localizedText("セル ($x,$y): ${originalValue.symbol()} → ${finalValue.symbol()}\n", "Cell ($x,$y): ${originalValue.symbol()} → ${finalValue.symbol()}\n"))
        if (includeBasic) append(localizedText("理由: ${titleJa()}", "Reason: ${titleJa()}"))
        if (codewordIndex != null && bitIndex != null && originalCodewordValue != null) {
            append('\n').append(codewordCellText(codewordIndex, bitIndex, originalCodewordValue,
                originalUnknownMask ?: 0, correctedCodewordValue, rsBlockIndex, rsLocalIndex, false))
        }
        if (rsErrata) append(localizedText("\nRS処理でこのコード語を訂正対象としました。", "\nRS processing treated this codeword as a repair target."))
        listDecoding?.let { append(localizedText(
            "\n${it.method.displayName()}でこのブロックを復号しました（${it.calculationDescription()}）。",
            "\nThis block used ${it.method.displayName()} list decoding (${it.calculationDescription()}).")) }
        if (bitConstraintSolved) append(localizedText(
            "\nこのブロックは、RS部分bit制約で誤り訂正の条件を満たす値を求めました。構造補完・変更や仮置きがある場合は、その値も条件に含みます。",
            "\nThis block used RS partial-bit constraints to find values satisfying the error-correction conditions. Any values filled, changed or tentatively assigned before this calculation are included in those conditions.",
        ))
        priorityAssignment?.let { value ->
            append(localizedText(
                "\n優先0/1探索でこのセルに${value.symbol()}を仮置きしました。元入力の既知bitではありません。",
                "\nPriority 0/1 search assigned ${value.symbol()} to this cell. It was not a known input bit.",
            ))
        }
        if (partialEnumerated) append(localizedText("\n未知bitの限定列挙を行い、RS整合する候補を探索しました。", "\nUnknown bits were enumerated within a limit to find RS-consistent candidates."))
        overturnedConstraint?.let {
            append(localizedText(
                "\n途中の${reasonLabelJa(it)}が設定した非マスクbit=$overturnedConstraintValue はRSで訂正されました。最終値の補助根拠には含めません。",
                "\nThe trial ${reasonLabelJa(it)} assigned unmasked bit=$overturnedConstraintValue, which RS later corrected. It is not supporting evidence for the final value.",
            ))
        }
        if (supportingReasons.isNotEmpty()) {
            append(localizedText("\n補助根拠: ", "\nSupporting evidence: "))
            append(supportingReasons.distinct().joinToString(" / ") { reasonLabelJa(it) })
        }
        if (alternativeRsDerivations.isNotEmpty()) append(localizedText("\n\n同じRSブロックの別の計算", "\n\nOther calculations for the same RS block"))
        alternativeRsDerivations.forEach { route ->
            append(localizedText("\n同じブロック訂正値への別経路: ", "\nAlternative route to the same block values: "))
                .append(route.description())
            if (rsLocalIndex in route.errataPositions) append(localizedText("（このコード語を訂正対象に含む）", " (targets this codeword)"))
            if (rsLocalIndex !in route.errataPositions) append(localizedText("（このコード語は訂正対象に含みません）", " (does not target this codeword for correction)"))
            if (rsLocalIndex in route.guessedPositions) append(localizedText("（このコード語を列挙対象に含む）", " (enumerates this codeword)"))
        }
        append(localizedText("\n\n評価の補足", "\n\nAssessment details"))
        append(localizedText("\n入力の評価（確率ではありません）=", "\nInput-cell relative confidence (not a probability)=")).append("%.2f".format(originalConfidence))
    }

    companion object {
        val STRUCTURAL_REASONS = setOf(
            RepairReasonKind.FINDER_PATTERN,
            RepairReasonKind.SEPARATOR,
            RepairReasonKind.TIMING_PATTERN,
            RepairReasonKind.ALIGNMENT_PATTERN,
            RepairReasonKind.FORMAT_INFORMATION,
            RepairReasonKind.VERSION_INFORMATION,
            RepairReasonKind.FIXED_DARK_MODULE,
            RepairReasonKind.REMAINDER_BITS,
        )
        val PAYLOAD_REASONS = setOf(
            RepairReasonKind.PAYLOAD_MODE,
            RepairReasonKind.PAYLOAD_COUNT,
            RepairReasonKind.PAYLOAD_DATA_VALUE,
            RepairReasonKind.PAYLOAD_TERMINATOR,
            RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT,
            RepairReasonKind.PAYLOAD_PADDING,
            RepairReasonKind.PAYLOAD_STRUCTURE,
        )
    }
}

private fun ModuleValue.symbol(): String = when (this) {
    ModuleValue.ZERO -> "0"
    ModuleValue.ONE -> "1"
    ModuleValue.ERASED -> "?"
}

private fun formatPartialByte(value: Int, unknownMask: Int): String = buildString {
    for (bit in 7 downTo 0) append(if ((unknownMask and (1 shl bit)) != 0) '?' else if ((value and (1 shl bit)) != 0) '1' else '0')
}

private fun reasonLabelJa(kind: RepairReasonKind): String = when (kind) {
    RepairReasonKind.PARTIAL_BIT_SEARCH -> localizedText("ビット単位探索", "bit-level search")
    RepairReasonKind.RS_ERASURE_RECOVERY -> localizedText("RS消失復元", "RS erasure")
    RepairReasonKind.RS_ERROR_CORRECTION -> localizedText("RS誤り訂正", "RS error")
    RepairReasonKind.PAYLOAD_MODE -> "mode"
    RepairReasonKind.PAYLOAD_COUNT -> "count"
    RepairReasonKind.PAYLOAD_DATA_VALUE -> localizedText("data値域", "data value range")
    RepairReasonKind.PAYLOAD_TERMINATOR -> "terminator"
    RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT -> "byte alignment"
    RepairReasonKind.PAYLOAD_PADDING -> "padding"
    RepairReasonKind.PAYLOAD_STRUCTURE -> localizedText("データ構造", "payload structure")
    else -> kind.name
}

object RepairTraceBuilder {
    fun build(source: QrMatrix, current: QrMatrix, candidate: RecoveryCandidate?): Map<Coord, ModuleRepairTrace> {
        require(source.dimension == current.dimension)
        val coordToCodeword = HashMap<Coord, Pair<Int, Int>>()
        candidate?.extraction?.codewords?.forEachIndexed { cw, partial ->
            partial.coordinates.forEachIndexed { bit, coord -> coordToCodeword[coord] = cw to bit }
        }
        val repairInfo = candidate?.repairInfo?.associateBy { it.rawIndex }.orEmpty()
        val out = LinkedHashMap<Coord, ModuleRepairTrace>()
        for (y in 0 until current.dimension) for (x in 0 until current.dimension) {
            val original = source[x, y]
            val final = current[x, y]
            // The inspector is primarily a value-diff view. StructuralRepair rewrites provenance even
            // when the observed bit was already correct, so do not mark those cells as "changed".
            // Preserve an explicit USER pin even if the user confirmed the same bit value.
            if (original.value == final.value && !final.source.isUserEdit) continue
            val coord = Coord(x, y)
            val cwBit = coordToCodeword[coord]
            val info = cwBit?.first?.let(repairInfo::get)
            val attemptedConstraint = cwBit?.second?.let { info?.constraintKindsByBit?.get(it) }
            val attemptedValue = cwBit?.second?.let { info?.constraintValuesByBit?.get(it) }
            val correctedBit = cwBit?.let { (raw, bit) ->
                candidate?.correctedInterleaved?.getOrNull(raw)?.let { (it shr (7 - bit)) and 1 }
            }
            val overturned = attemptedConstraint != null && attemptedValue != null && attemptedValue != correctedBit
            val constraintKind = attemptedConstraint.takeUnless { overturned }
            val priorityAssignment = candidate?.priorityAssignments?.get(coord)
            val supporting = mutableListOf<RepairReasonKind>()
            if (info?.partialEnumerated == true || priorityAssignment != null) supporting += RepairReasonKind.PARTIAL_BIT_SEARCH
            constraintKind?.let { supporting += it.toReasonKind() }
            if (info?.rsErrata == true) {
                supporting += if ((info.originalUnknownMask and (1 shl (7 - cwBit.second))) != 0) {
                    RepairReasonKind.RS_ERASURE_RECOVERY
                } else RepairReasonKind.RS_ERROR_CORRECTION
            }

            val primary = when (final.source) {
                ModuleSource.USER, ModuleSource.USER_SOFT, ModuleSource.USER_UNLOCKED -> RepairReasonKind.USER_OVERRIDE
                ModuleSource.STRUCTURAL -> structuralReason(current.dimension, x, y).let {
                    if (it == RepairReasonKind.OTHER && cwBit == null) RepairReasonKind.REMAINDER_BITS else it
                }
                ModuleSource.CONSTRAINT -> constraintKind?.toReasonKind() ?: RepairReasonKind.PAYLOAD_STRUCTURE
                ModuleSource.RECOVERED -> if (priorityAssignment != null) {
                    RepairReasonKind.PARTIAL_BIT_SEARCH
                } else constraintKind?.toReasonKind() ?: if (info?.partialEnumerated == true) {
                    RepairReasonKind.PARTIAL_BIT_SEARCH
                } else if (original.value == ModuleValue.ERASED) {
                    RepairReasonKind.RS_ERASURE_RECOVERY
                } else {
                    RepairReasonKind.RS_ERROR_CORRECTION
                }
                ModuleSource.IMAGE -> RepairReasonKind.OTHER
            }
            out[coord] = ModuleRepairTrace(
                x = x,
                y = y,
                originalValue = original.value,
                finalValue = final.value,
                originalSource = original.source,
                finalSource = final.source,
                primaryReason = primary,
                supportingReasons = supporting.filter { it != primary }.distinct(),
                codewordIndex = cwBit?.first,
                bitIndex = cwBit?.second,
                rsBlockIndex = info?.blockIndex,
                rsLocalIndex = info?.localIndex,
                originalCodewordValue = cwBit?.first?.let { candidate?.extraction?.codewords?.getOrNull(it)?.value },
                originalUnknownMask = info?.originalUnknownMask,
                correctedCodewordValue = cwBit?.first?.let { candidate?.correctedInterleaved?.getOrNull(it) },
                rsErrata = info?.rsErrata == true,
                partialEnumerated = info?.partialEnumerated == true,
                originalConfidence = original.confidence,
                bitConstraintSolved = info?.bitConstraintSolved == true,
                listDecoding = info?.listDecoding,
                priorityAssignment = priorityAssignment,
                overturnedConstraint = attemptedConstraint?.takeIf { overturned }?.toReasonKind(),
                overturnedConstraintValue = attemptedValue.takeIf { overturned },
                alternativeRsDerivations = info?.blockIndex?.let { candidate?.blockDerivations?.get(it)?.drop(1) }.orEmpty(),
            )
        }
        return out
    }

    fun candidateMatrix(base: QrMatrix, candidate: RecoveryCandidate, recoveryEngine: RecoveryEngine): QrMatrix {
        val out = base.copy()
        recoveryEngine.applyCorrection(out, candidate, overwriteKnownErrors = true)
        StructuralRepair.apply(out, candidate.ecLevel, candidate.dataMask, preserveUserEdits = true)
        return out
    }

    fun diffCoordinates(a: QrMatrix, b: QrMatrix): Set<Coord> {
        require(a.dimension == b.dimension)
        return buildSet {
            for (y in 0 until a.dimension) for (x in 0 until a.dimension) {
                if (a[x, y].value != b[x, y].value) add(Coord(x, y))
            }
        }
    }

    private fun PayloadConstraintSolver.ConstraintKind.toReasonKind(): RepairReasonKind = when (this) {
        PayloadConstraintSolver.ConstraintKind.MODE -> RepairReasonKind.PAYLOAD_MODE
        PayloadConstraintSolver.ConstraintKind.COUNT -> RepairReasonKind.PAYLOAD_COUNT
        PayloadConstraintSolver.ConstraintKind.DATA_VALUE -> RepairReasonKind.PAYLOAD_DATA_VALUE
        PayloadConstraintSolver.ConstraintKind.TERMINATOR -> RepairReasonKind.PAYLOAD_TERMINATOR
        PayloadConstraintSolver.ConstraintKind.BYTE_ALIGNMENT -> RepairReasonKind.PAYLOAD_BYTE_ALIGNMENT
        PayloadConstraintSolver.ConstraintKind.PAD -> RepairReasonKind.PAYLOAD_PADDING
        PayloadConstraintSolver.ConstraintKind.STRUCTURE -> RepairReasonKind.PAYLOAD_STRUCTURE
    }

    internal fun structuralReason(dimension: Int, x: Int, y: Int): RepairReasonKind {
        val version = (dimension - 17) / 4
        if (isFinder(x, y, dimension)) return RepairReasonKind.FINDER_PATTERN
        if (isSeparator(x, y, dimension)) return RepairReasonKind.SEPARATOR
        if (x == 8 && y == dimension - 8) return RepairReasonKind.FIXED_DARK_MODULE
        if (isFormat(x, y, dimension)) return RepairReasonKind.FORMAT_INFORMATION
        if (version >= 7 && isVersionInfo(x, y, dimension)) return RepairReasonKind.VERSION_INFORMATION
        // Alignment patterns overwrite timing modules in StructuralRepair. Match that precedence.
        if (isAlignment(version, x, y)) return RepairReasonKind.ALIGNMENT_PATTERN
        if ((x == 6 && y in 8..(dimension - 9)) || (y == 6 && x in 8..(dimension - 9))) return RepairReasonKind.TIMING_PATTERN
        return RepairReasonKind.OTHER
    }

    private fun isFinder(x: Int, y: Int, d: Int): Boolean =
        (x in 0..6 && y in 0..6) ||
            (x in d - 7 until d && y in 0..6) ||
            (x in 0..6 && y in d - 7 until d)

    private fun isSeparator(x: Int, y: Int, d: Int): Boolean =
        ((x in 0..7 && y == 7) || (x == 7 && y in 0..7)) ||
            ((x in d - 8 until d && y == 7) || (x == d - 8 && y in 0..7)) ||
            ((x in 0..7 && y == d - 8) || (x == 7 && y in d - 8 until d))

    fun isFormat(x: Int, y: Int, d: Int): Boolean {
        val copy1 = setOf(
            0 to 8, 1 to 8, 2 to 8, 3 to 8, 4 to 8, 5 to 8, 7 to 8, 8 to 8, 8 to 7,
            8 to 5, 8 to 4, 8 to 3, 8 to 2, 8 to 1, 8 to 0,
        )
        if ((x to y) in copy1) return true
        if (x == 8 && y in d - 7 until d) return true
        if (y == 8 && x in d - 8 until d) return true
        return false
    }

    private fun isVersionInfo(x: Int, y: Int, d: Int): Boolean =
        (x in d - 11..d - 9 && y in 0..5) || (x in 0..5 && y in d - 11..d - 9)

    private fun isAlignment(versionNumber: Int, x: Int, y: Int): Boolean {
        val centers = Version.getVersionForNumber(versionNumber).alignmentPatternCenters
        for (ix in centers.indices) for (iy in centers.indices) {
            val skippedNearFinder =
                (ix == 0 && (iy == 0 || iy == centers.lastIndex)) ||
                    (ix == centers.lastIndex && iy == 0)
            if (skippedNearFinder) continue
            if (abs(x - centers[iy]) <= 2 && abs(y - centers[ix]) <= 2) return true
        }
        return false
    }
}
