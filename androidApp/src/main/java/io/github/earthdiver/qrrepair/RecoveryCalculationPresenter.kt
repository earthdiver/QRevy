package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Present QR methods once, keeping block alternatives and sampled inputs attached to their own records. */
internal class RecoveryCalculationPresenter(private val japanese: Boolean) {
    fun group(candidate: RecoveryCandidate, methods: List<RecoveryMethodRecords>, entry: RecoveryResultEntry?,
        stopReasons: String?): DiagnosticDetailGroup {
        val selectedEntry = entry?.takeIf { it.candidate === candidate }
        val selectedBlocks = methods.map { it.representative.selectedBlockDerivations() }
        val matchingInputs = Array(methods.size) { arrayOfNulls<Boolean>(it) }
        val multipleMethods = methods.size > 1
        val commonStructure = methods.size == 1 || methods.all { candidate.sameStructurePreparation(it.representative) }
        val commonPriority = methods.all { candidate.samePriorityPreparation(it.representative) }
        val withoutFilling = methods.map { it.representative }.filter { it.constraintFilledBits == 0 }
        val omitted = methods.any { it.records.any { record -> record.derivationsOmitted } } ||
            selectedEntry?.derivationsOmitted == true
        val summary = buildList {
            if (multipleMethods) add(ui("${methods.size}通りの計算で同じQRが得られました。",
                "${methods.size} calculation methods yielded the same QR."))
            preparation(candidate, commonStructure, commonPriority).takeIf(String::isNotBlank)?.let {
                add((if (multipleMethods) ui("共通: ", "Shared: ") else "") + it)
            }
            methods.forEachIndexed { index, method ->
                if (multipleMethods) add("\n" + methodName(index) +
                    if (index == 0) ui("［セルの説明に使用］", " [used for cell explanations]") else "")
                preparation(method.representative, !commonStructure, !commonPriority,
                    withoutFilling.any { method.representative.sameQrWithoutStructureFilling(it) })
                    .takeIf(String::isNotBlank)?.let(::add)
                val blocks = method.representative.actualBlockMethods
                if (blocks.isEmpty()) add(method.representative.compactUsedMethodsText())
                else blocks.forEach { add(compactBlock(it, blocks.size > 1)) }
            }
            selectedEntry?.alternativeDerivations?.size?.takeIf { it > 0 }?.let {
                add(ui("\n別の読み取り試行: ${it}件", "\nOther sampled-input attempts: $it"))
            }
            if (omitted) add(ui("\n保持上限により、一部の計算記録を省略しています。",
                "\nSome calculation records were omitted at the retention limit."))
        }.joinToString("\n")

        val text = buildList {
            add(ui("コード語は8bitのまとまりです。ブロックは誤り訂正を計算する単位です。",
                "A codeword contains 8 bits. A block is a unit of error-correction calculation."))
            if (methods.any { it.representative.actualBlockMethods.any { block -> block.bitConstraintSolved } ||
                    it.blockDerivations.values.flatten().any { route -> route.bitConstraintSolved } }) add(ui(
                "自由度は、その計算条件で独立に選べる不明bitの数です。構造補完や仮置きした値も条件に含まれます。",
                "Degrees of freedom count unknown bits that can be chosen independently under that calculation's conditions, including structure filling and tentative assignments."))
            add(ui("方法や試行の件数は修復候補の件数ではありません。複数の方法の一致は、元の内容と同一であることの保証にはなりません。",
                "Method and trial counts are not candidate counts. Agreement between methods does not establish that the original content was recovered."))
            if (commonPriority && candidate.priorityAssignments.isNotEmpty()) add(priorityDetails(candidate))
            if (commonStructure) structureDetails(candidate, shared = multipleMethods).takeIf(String::isNotBlank)?.let(::add)
            methods.forEachIndexed { index, method ->
                val route = method.representative
                if (multipleMethods) add(methodName(index))
                if (!commonPriority && route.priorityAssignments.isNotEmpty()) add(priorityDetails(route))
                if (route.constraintFilledBits > 0 && route.repairInfo.sumOf { it.constraintValuesByBit.size } != route.constraintFilledBits)
                    add(ui("構造補完の位置・値は一部未記録です。", "Structure-fill locations or values are not fully recorded."))
                if (index > 0 && route.extraction != candidate.extraction)
                    inputDifferences(candidate, route).takeIf(String::isNotBlank)?.let(::add)
                if (!commonStructure) structureDetails(route).takeIf(String::isNotBlank)?.let(::add)
                if (index > 0 && (route.displayConditions != candidate.displayConditions ||
                        route.constraintForcedByUserSetting != candidate.constraintForcedByUserSetting ||
                        route.singleSegmentStatus != candidate.singleSegmentStatus)) {
                    add(route.conditionText())
                    if (route.displayConditions == null && candidate.displayConditions != null)
                        add(ui("この方法の実行時点の設定: 記録なし", "Run settings for this method: not recorded"))
                    route.singleSegmentStatus?.description()?.let(::add)
                }
                if (index > 0 && (route.formatSelection != candidate.formatSelection || route.formatRoute != candidate.formatRoute ||
                        route.version != candidate.version || route.ecLevel != candidate.ecLevel || route.dataMask != candidate.dataMask))
                    add(formatDetails(route))
                val checks = method.structureChecks.filter { it.recorded }.map(::structureCheckText).distinct()
                if (checks.isNotEmpty()) {
                    add(ui("QR構造の確認: ", "QR structure checks: ") + checks.joinToString("\n"))
                    if (route.constraintFilledBits == 0) add(ui("構造による補完・変更はありません。",
                        "No values were filled or changed using QR structure."))
                }
                route.listDecodingMismatchLimit?.let {
                    add(ui("この方法のリスト復号の不一致上限: ${it}bit", "List-decoding mismatch limit for this method: $it bits"))
                }
                add(ui("構造補完・優先セルの仮置きを反映した不明コード語数（全ブロック合計）: ${route.erasedCodewords}",
                    "Unknown codewords after structure filling and priority assignments (all blocks): ${route.erasedCodewords}"))
                add(ui("補完・訂正対象（全ブロック合計）: ${route.correctedErrataSymbols}コード語",
                    "Target codewords (all blocks): ${route.correctedErrataSymbols}"))
                if (route.actualBlockMethods.isEmpty() && route.repairInfo.none { it.bitConstraintSolved || it.listDecoding != null })
                    add(ui("通常RS訂正余裕（ブロック最小）: ${route.minRsMargin}",
                        "Conventional RS margin (minimum across blocks): ${route.minRsMargin}"))
                route.actualBlockMethods.forEach { block ->
                    val selected = selectedBlocks[index][block.blockIndex]
                    add(blockCalculationText(block, selected?.errataPositions?.size))
                    if (index > 0 && selected != null) {
                        val original = selectedBlocks.first()[block.blockIndex]
                        if (original != null && original.errataPositions != selected.errataPositions)
                            add(positionsText("補完・訂正対象", "Target", selected.errataPositions))
                        if (selected.guessedPositions.isNotEmpty() && original?.guessedPositions != selected.guessedPositions)
                            add(positionsText("仮置き対象", "Tentatively assigned", selected.guessedPositions))
                    }
                }
                method.blockDerivations.forEach { (block, derivations) ->
                    val selected = selectedBlocks[index][block]
                    val additional = derivations.filterNot { it.copy(searchPenalty = 0) == selected }
                    if (additional.isNotEmpty()) {
                        add(ui("ブロック${block + 1}・同じ値を得た別の計算: ${additional.size}通り",
                            "Block ${block + 1}: ${additional.size} other calculations yielding the same values"))
                        additional.forEach { derivation ->
                            val reference = (0 until index).firstOrNull { earlier ->
                                val other = methods[earlier].representative
                                val sameInput = matchingInputs[index][earlier]
                                    ?: route.sameBlockCalculationInput(other).also { matchingInputs[index][earlier] = it }
                                sameInput && selectedBlocks[earlier][block] == derivation.copy(searchPenalty = 0)
                            }
                            if (reference != null) add(ui("${methodName(reference)}のブロック${block + 1}と同じ計算記録",
                                "Same calculation record as ${methodName(reference)}, block ${block + 1}"))
                            else {
                                add(blockCalculationText(RecoveryBlockMethod(block, derivation.bitConstraintSolved,
                                    derivation.rsMargin.takeUnless { derivation.bitConstraintSolved || derivation.listDecoding != null },
                                    derivation.guessedBits, derivation.listDecoding, derivation.knownBitErrors.takeIf { derivation.knownBitSearch }), derivation.errataPositions.size))
                                if (selected?.errataPositions != derivation.errataPositions)
                                    add(positionsText("補完・訂正対象", "Target", derivation.errataPositions))
                                if (derivation.guessedPositions.isNotEmpty())
                                    add(positionsText("仮置き対象", "Tentatively assigned", derivation.guessedPositions))
                            }
                        }
                    }
                }
            }
            if (methods.any { it.representative.actualBlockMethods.any { block -> !block.bitConstraintSolved && block.listDecoding == null && block.knownBitErrors == null } })
                add(ui("通常RS訂正余裕 = ECC数 − 消失数 − 2 × 誤りコード語数。その計算での値であり、元入力の残り訂正能力や正しさを示しません。",
                    "Conventional RS margin = ECC − erasures − 2 × errors. This describes that calculation, not remaining input correction capacity or proof of correctness."))
            stopReasons?.let(::add)
            selectedEntry?.alternativeDerivations?.forEachIndexed { index, route ->
                add(ui("別の読み取り試行${index + 1}", "Other sampled-input attempt ${index + 1}"))
                add(listOf(route.profile?.label.orEmpty(), RecoveryDiagnosticPresenter(japanese).geometryDiagnosticText(route.hypothesis.geometry))
                    .filter(String::isNotBlank).joinToString(" / "))
                add(route.candidate.usedMethodsText())
                route.samplingStats.thresholds?.let { add(ui("閾値: ${it.blackMax} / ${it.whiteMin}", "Thresholds: ${it.blackMax} / ${it.whiteMin}")) }
            }
        }.filter(String::isNotBlank).joinToString("\n\n")
        return DiagnosticDetailGroup(DiagnosticDetailId.CALCULATIONS,
            ui("このQRを得た計算方法", "Calculation methods yielding this QR"), summary, text, collapsible = true)
    }

    private fun methodName(index: Int): String = ui("方法", "Method ") + recoveryMethodLabel(index)

    private fun preparation(route: RecoveryCandidate, structure: Boolean, priority: Boolean,
        sameWithoutFilling: Boolean = false): String = buildList {
        if (structure) {
            val amount = if (route.constraintFilledBits == 0) ui("なし", "none")
                else ui("${route.constraintFilledBits}bit", "${route.constraintFilledBits} bits")
            val equivalent = if (sameWithoutFilling) ui("、補完なしでも同じQR", ", same QR without filling") else ""
            add(ui("構造補完（$amount$equivalent）", "structure filling ($amount$equivalent)") +
                if (route.constraintForcedByUserSetting) ui("（指定した仮定）", " (specified assumption)") else "")
        }
        if (priority) add(if (route.priorityGuessedBits == 0) ui("優先セルの探索なし", "no priority-cell search")
            else ui("優先セルの仮置き${route.priorityGuessedBits}bit", "priority assignments: ${route.priorityGuessedBits} bits"))
    }.joinToString(ui("、", ", "))

    private fun compactBlock(method: RecoveryBlockMethod, showBlock: Boolean): String =
        (if (showBlock) ui("ブロック${method.blockIndex + 1}: ", "Block ${method.blockIndex + 1}: ") else "") + when {
            method.listDecoding != null -> method.listDecoding.method.displayName() + ui("によるリスト復号", " list decoding")
            method.knownBitErrors != null -> ui("既知bit誤り探索：${method.knownBitErrors.size}bit変更", "Known-bit error search: ${method.knownBitErrors.size} changed bits")
            method.bitConstraintSolved -> ui("部分bit制約：自由度${method.guessedBits}", "Partial-bit constraints: ${method.guessedBits} degrees of freedom")
            else -> (if (method.guessedBits == 0) "" else ui("不明${method.guessedBits}bitの仮置き＋", "Assign ${method.guessedBits} unknown bits + ")) +
                ui("通常RS：訂正余裕${method.rsMargin ?: "—"}", "Conventional RS: margin ${method.rsMargin ?: "—"}")
        }

    private fun priorityDetails(route: RecoveryCandidate): String =
        ui("優先セルへの仮置き（座標は0から）: ", "Tentative priority assignments (zero-based coordinates): ") +
            route.priorityAssignments.entries.sortedWith(compareBy({ it.key.y }, { it.key.x })).joinToString(", ") {
                (coord, value) -> "(${coord.x},${coord.y})=${value.bitOrNull() ?: "?"}"
            }

    private fun positionsText(ja: String, en: String, positions: List<Int>): String =
        ui("$ja コード語（ブロック内、1から）: ", "$en codeword positions (within the block, one-based): ") +
            if (positions.isEmpty()) ui("なし", "none") else positions.sorted().joinToString { (it + 1).toString() }

    private fun inputDifferences(base: RecoveryCandidate, route: RecoveryCandidate): String = buildList {
        fun position(word: PartialCodeword?, raw: Int, bit: Int): String =
            word?.coordinates?.getOrNull(bit)?.let { "(${it.x},${it.y})" }
                ?: ui("コード語${raw + 1}/bit${bit + 1}", "codeword ${raw + 1}/bit${bit + 1}")
        for (raw in 0 until maxOf(base.extraction.codewords.size, route.extraction.codewords.size)) {
            val before = base.extraction.codewords.getOrNull(raw)
            val after = route.extraction.codewords.getOrNull(raw)
            for (bit in 0..7) if (bitValue(before, bit) != bitValue(after, bit) ||
                position(before, raw, bit) != position(after, raw, bit))
                add(ui("方法A: ", "Method A: ") + "${position(before, raw, bit)}=${bitValue(before, bit)} / " +
                    ui("この方法: ", "This method: ") + "${position(after, raw, bit)}=${bitValue(after, bit)}")
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = ui(
        "計算入力の違い（方法Aとの比較、座標は0から、マスク解除後）\n",
        "Calculation input differences (compared with Method A, zero-based coordinates, unmasked)\n")).orEmpty()

    private fun bitValue(word: PartialCodeword?, bit: Int): String {
        if (word == null) return ui("記録なし", "not recorded")
        val mask = 1 shl (7 - bit)
        return if (word.unknownBits and mask != 0) "?" else if (word.value and mask != 0) "1" else "0"
    }

    private fun structureDetails(route: RecoveryCandidate, shared: Boolean = false): String = buildList {
        for (info in route.repairInfo.sortedBy { it.rawIndex }) {
            val word = route.extraction.codewords.getOrNull(info.rawIndex)
            for (bit in (info.constraintKindsByBit.keys + info.constraintValuesByBit.keys).sorted()) {
                val coord = word?.coordinates?.getOrNull(bit)
                val position = coord?.let { ui("セル (${it.x},${it.y})", "Cell (${it.x},${it.y})") }
                    ?: ui("コード語${info.rawIndex + 1}/bit${bit + 1}", "Codeword ${info.rawIndex + 1}/bit${bit + 1}")
                // Priority values are stored masked; the fill trace and extracted words are unmasked.
                val assigned = coord?.let { route.priorityAssignments[it]?.bitOrNull() }
                val before = if (coord != null && assigned != null) (assigned xor
                    if (CodewordExtractor.maskApplies(route.dataMask, coord.x, coord.y)) 1 else 0).toString()
                    else bitValue(word, bit)
                val after = info.constraintValuesByBit[bit]?.toString() ?: ui("記録なし", "not recorded")
                add(ui("$position: 補完前 $before / 補完後 $after", "$position: before filling $before / after filling $after") +
                    " (${constraintKind(info.constraintKindsByBit[bit])})")
            }
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix =
        (if (shared) ui("共通の構造補完の記録", "Shared structure-filling record")
            else ui("構造補完の記録", "Structure-filling record")) +
            ui("（座標は0から、マスク解除後）\n", " (zero-based coordinates, unmasked)\n")).orEmpty()

    private fun constraintKind(kind: PayloadConstraintSolver.ConstraintKind?): String = when (kind) {
        PayloadConstraintSolver.ConstraintKind.MODE -> ui("モード", "mode")
        PayloadConstraintSolver.ConstraintKind.COUNT -> ui("長さ", "length")
        PayloadConstraintSolver.ConstraintKind.DATA_VALUE -> ui("データ値", "data value")
        PayloadConstraintSolver.ConstraintKind.TERMINATOR -> ui("終端", "terminator")
        PayloadConstraintSolver.ConstraintKind.BYTE_ALIGNMENT -> ui("バイト境界", "byte alignment")
        PayloadConstraintSolver.ConstraintKind.PAD -> ui("埋め草", "padding")
        PayloadConstraintSolver.ConstraintKind.STRUCTURE -> ui("構造", "structure")
        null -> ui("根拠の記録なし", "reason not recorded")
    }

    private fun formatDetails(route: RecoveryCandidate): String =
        "V${route.version} / ECC ${route.ecLevel} / " + ui("マスク${route.dataMask}", "mask ${route.dataMask}") +
            ui("\n形式の選択: ", "\nFormat selection: ") + when (route.formatRoute) {
                FormatSelectionReason.USER -> ui("ユーザー指定", "user-specified")
                FormatSelectionReason.OBSERVED -> ui("読み取った形式情報", "observed format information")
                FormatSelectionReason.SEARCH -> ui("形式の探索", "format search")
                null -> ui("記録なし", "not recorded")
            } + (route.formatSelection?.let {
                ui("\nECC: ${if (it.eccSpecified) "指定" else "自動"} / マスク: ${if (it.maskSpecified) "指定" else "自動"}",
                    "\nECC: ${if (it.eccSpecified) "specified" else "automatic"} / mask: ${if (it.maskSpecified) "specified" else "automatic"}")
            } ?: "")

    private fun blockCalculationText(method: RecoveryBlockMethod, targetCount: Int? = null): String = buildString {
        append(ui("ブロック${method.blockIndex + 1}", "Block ${method.blockIndex + 1}"))
        targetCount?.let { append(ui("\n補完・訂正対象: $it コード語", "\nTarget codewords: $it")) }
        val list = method.listDecoding
        append(ui("\n方法: ", "\nMethod: ")).append(when {
            list != null -> list.method.displayName() + ui("によるリスト復号", " list decoding")
            method.knownBitErrors != null -> ui("既知bit誤り探索：${method.knownBitErrors.size}bit変更", "Known-bit error search: ${method.knownBitErrors.size} changed bits")
            method.bitConstraintSolved -> ui("部分bit制約", "Partial-bit constraints")
            else -> ui("通常RS", "Conventional RS")
        })
        append(if (method.bitConstraintSolved) ui("\nこのブロックの自由度: ", "\nDegrees of freedom for this block: ")
            else ui("\n計算前に仮置きした不明bit: ", "\nUnknown bits assigned before calculation: ")).append(method.guessedBits)
        if (list != null) {
            if (list.method == ListDecoderMethod.KOETTER_VARDY) {
                append(ui("\n一致スコア: ${list.score}\n必要スコア: ${list.scoreThreshold}",
                    "\nAgreement score: ${list.score}\nRequired score: ${list.scoreThreshold}"))
            } else {
                append(ui("\n訂正半径: ${list.radius}コード語\n残存消失: ${list.remainingErasures}コード語",
                    "\nCorrection radius: ${list.radius} codewords\nRemaining erasures: ${list.remainingErasures} codewords"))
            }
            if (list.method != ListDecoderMethod.WU) {
                append(if (list.method == ListDecoderMethod.KOETTER_VARDY) ui("\n多重度の基準: ", "\nBase multiplicity: ")
                    else ui("\n多重度: ", "\nMultiplicity: ")).append(list.multiplicity)
                append(ui("\nY次数: ", "\nY degree: ")).append(list.listDegree)
            }
        } else if (method.knownBitErrors != null) {
            append(ui("\n変更位置（ブロック内コード語、LSB=0のbit）: ", "\nChanged positions (block codeword, LSB=0 bit): "))
            append(method.knownBitErrors.joinToString { "${it.codeword + 1}:${it.bit}" })
        } else if (!method.bitConstraintSolved) append(ui("\nこのブロックの通常RS訂正余裕: ${method.rsMargin ?: "—"}",
            "\nConventional RS correction margin for this block: ${method.rsMargin ?: "—"}"))
    }

    private fun structureCheckText(check: RecoveryStructureCheck): String = buildList {
        check.mode?.let { mode ->
            val name = when (mode) {
                1 -> ui("数字", "Numeric")
                2 -> ui("英数字", "Alphanumeric")
                4 -> ui("バイト", "Byte")
                8 -> ui("漢字", "Kanji")
                else -> ui("モード$mode", "Mode $mode")
            }
            add(name + (check.count?.let { count ->
                if (mode == 4) ui(" ${count}バイト", " $count bytes") else ui(" ${count}文字", " $count characters")
            } ?: ""))
        }
        if (check.plans > 1) add(ui("構造の候補: ${check.plans}通り", "Possible structures: ${check.plans}"))
        add(ui("仮定した末尾構造と一致する読み取り済みbit: ${check.evidenceBits}bit",
            "Observed bits matching the assumed suffix structure: ${check.evidenceBits} bits"))
    }.joinToString(" / ")

    private fun ui(japanese: String, english: String): String = if (this.japanese) japanese else english
}
