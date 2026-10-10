package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Search-wide evidence and the selected origin are captured separately from editable settings. */
internal data class RecoveryDiagnosticContext(
    val candidateCount: Int,
    val unsearched: Boolean,
    val candidatesOmitted: Boolean,
    val searchDiagnostic: RecoverySearchDiagnostic?,
    val selectedEntry: RecoveryResultEntry?,
    val selectedCandidateNumber: Int? = null,
)

/** Builds diagnostic content without retaining an Activity, Views, or mutable UI state. */
internal class RecoveryDiagnosticPresenter(private val japanese: Boolean) {
    fun searchResultDescription(context: RecoveryDiagnosticContext): String {
        val diagnostic = searchEvidence(context)
        val completeness = diagnostic.completeness
        val reasons = diagnostic.completionReasonsText()
        if (diagnostic.coverageComplete && diagnostic.completedSteps == 0 &&
            SearchIncompleteReason.INTERNAL_ERROR in diagnostic.incompleteReasons) {
            return completeness.description() + "\n" + reasons
        }
        return searchResultText(context.candidateCount, completeness,
            context.candidatesOmitted || diagnostic.candidatesOmitted, reasons)
    }

    fun candidateReport(candidate: RecoveryCandidate, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val preview = QrContentPreviewParser.parse(candidate)
        val input = initialDiagnosticSection(candidate.version, candidate.ecLevel, candidate.dataMask,
            geometry?.let(::geometryDiagnosticText).orEmpty(), candidate.rsBitDiagnostic,
            candidate.extraction.codewords.takeIf { it.isNotEmpty() }?.sumOf { (it.unknownBits and 255).countOneBits() },
            candidate.originalErasedCodewords, candidate.conventionalRsDiagnostic, candidate.correctedInterleaved,
            conditions = candidate.displayConditions)
        val verification = buildString {
            append(if (preview?.qrStructureValid == true) ui("QRのデータ構造を確認できました。", "The QR data structure was verified.")
                else ui("QRのデータ構造を確認できませんでした。", "The QR data structure could not be verified."))
            append(ui("\n比較対象は、画像の読み取りと手動編集を反映した、修復開始時の既知の0/1です。",
                "\nComparisons use known 0/1 values at repair start, including image sampling and manual edits."))
            append('\n').append(candidate.inputComparisonText())
            append(ui("\n\n今回の探索全体\n", "\n\nSearch-wide results\n"))
            append(searchResultDescription(context))
        }
        val scope = context.selectedCandidateNumber?.let { number ->
            ui("候補${number}の診断（保持中${context.candidateCount}件）",
                "Diagnostics for candidate $number (${context.candidateCount} retained)")
        } ?: ui("選択中の候補の診断", "Diagnostics for the selected candidate")
        val candidateChecks = buildString {
            append(if (preview?.qrStructureValid == true) ui("QR構造: 確認済み", "QR structure: verified")
                else ui("QR構造: 確認できませんでした", "QR structure: could not be verified"))
            append(if (candidate.payloadParsed) ui("\n最終復号: 成功", "\nFinal decoding: succeeded")
                else ui("\n最終復号: 成功していません", "\nFinal decoding: not successful"))
            if (!candidate.payloadParsed && preview?.qrStructureValid == true) append(ui(
                "\nテキストとしての復号には成功していません。詳細結果のデータ画面で確認できます。",
                "\nDecoding as text has not succeeded. Open Data in the detailed results to inspect the recovered data."))
            append(ui("\n修復開始時の入力との比較", "\nComparison with the input at repair start"))
            append('\n').append(inputAgreementText(candidate.formatMatches + candidate.formatMismatches, candidate.formatMismatches, true, compact = true))
            append('\n').append(inputAgreementText(candidate.knownBitsCompared, candidate.knownBitMismatchesAfterCorrection, false, compact = true))
            if (candidate.constraintForcedByUserSetting) append(ui(
                "\n指定した単一セグメントの仮定を適用した候補です。",
                "\nThis candidate uses the specified single-segment assumption."))
        }
        val verificationSummary = candidateChecks + "\n\n" + searchOverview(context)
        val overview = buildString {
            append(verificationSummary)
            append(ui("\n元の内容と同一であることを保証するものではありません。",
                "\nThis does not establish that the original content was recovered."))
        }
        return RecoveryDiagnosticReport(scope, listOf(
            candidate.displayConditions.runSettingsSection(),
            input,
            candidateProcessingSection(candidate, context),
            DiagnosticSection(DiagnosticStage.VERIFICATION,
                verificationSummary,
                listOf(verification, preview?.toDisplayText().orEmpty()).filter(String::isNotBlank).joinToString("\n\n")),
        ), overview)
    }

    fun failureReport(failure: RecoveryFailure, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val input = initialDiagnosticSection(geometry?.version, failure.ecLevel, failure.dataMask,
            geometry?.let(::geometryDiagnosticText).orEmpty(), failure.rsBitDiagnostic, null,
            failure.originalErasedCodewords.takeIf { failure.rsBitDiagnostic != null || failure.failedBlockIndex != null || it > 0 },
            failure.conventionalRsDiagnostic, conditions = failure.displayConditions)
        val processing = buildString {
            append(failure.displayConditions.candidateCalculationPolicyText())
            context.searchDiagnostic?.failureExplanation(failure.displayConditions)?.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.trim()
        val verification = buildString {
            append(searchResultDescription(context))
            append("\n\n").append(referenceTarget(failure, geometry))
            append(ui("\n\n参考診断の形式と入力との比較\n", "\n\nComparison of the reference format with the input\n"))
            append(inputAgreementText(failure.formatKnownBits, failure.formatMismatches, true))
            if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                append(ui("\n採用できなかった候補と入力との比較\n", "\nComparison of the rejected candidate with the input\n"))
                append(inputAgreementText(failure.knownBitsCompared, failure.knownBitMismatches, false))
            }
        }
        val explanation = context.searchDiagnostic?.failureExplanation(failure.displayConditions).orEmpty()
        val reference = listOfNotNull(failureProcessingText(failure), combinationStopReasons(failure.combinationSearch))
            .filter(String::isNotBlank).joinToString("\n\n")
        val groups = listOfNotNull(
            processing.takeIf(String::isNotBlank)?.let { DiagnosticDetailGroup(DiagnosticDetailId.METHODS,
                ui("探索で確認できたこと", "What the search established"), text = it) },
            reference.takeIf(String::isNotBlank)?.let { DiagnosticDetailGroup(DiagnosticDetailId.CALCULATIONS,
                ui("参考計算の記録", "Reference calculation record"),
                referenceTarget(failure, geometry) +
                    ui("\nこの参考計算の記録です。探索全体の失敗原因を示すものではありません。",
                        "\nThis is one reference calculation, not a diagnosis of why the whole search failed."),
                it, collapsible = true) },
            searchProcessingGroup(context.searchDiagnostic ?: failure.searchDiagnostic,
                if (failure.displayConditions?.trustSingleSegment == true)
                    (context.searchDiagnostic ?: failure.searchDiagnostic).singleSegmentProcessingText() else "", hasCandidate = false,
                wholeSearch = context.searchDiagnostic != null),
        )
        return RecoveryDiagnosticReport(ui("修復結果の診断", "Recovery result diagnostics"), listOf(
            failure.displayConditions.runSettingsSection(),
            input,
            DiagnosticSection(DiagnosticStage.PROCESSING,
                explanation.ifBlank {
                    when {
                        failure.failedBlockIndex != null -> referenceTarget(failure, geometry) + ui(
                            "\n参考計算では、RSブロック${failure.failedBlockIndex + 1}で結果を得られませんでした。",
                            "\nThe reference calculation obtained no result for RS block ${failure.failedBlockIndex + 1}.")
                        processing.isNotBlank() -> processing
                        failure.originalErasedCodewords > 0 || failure.erasedCodewords > 0 -> referenceTarget(failure, geometry) + ui(
                            "\n補完・仮置きを反映した不明コード語: ${failure.erasedCodewords}個",
                            "\nUnknown codewords after filling and tentative assignments: ${failure.erasedCodewords}")
                        groups.isEmpty() -> ui("計算方法と適用状況の記録はありません。",
                            "Calculation methods and their applicability were not recorded.")
                        else -> ui("記録されている内容: ", "Available records: ") + groups.joinToString(ui("、", ", ")) { it.title }
                    }
                }, groups),
            DiagnosticSection(DiagnosticStage.VERIFICATION,
                buildString {
                    append(ui("保持している修復候補: ${context.candidateCount}件", "Retained recovery candidates: ${context.candidateCount}"))
                    append('\n').append(searchOverview(context))
                    append("\n\n").append(referenceTarget(failure, geometry))
                    append(ui("\n修復開始時の入力との比較", "\nComparison with the input at repair start"))
                    append('\n').append(inputAgreementText(failure.formatKnownBits, failure.formatMismatches, true, compact = true))
                    if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                        append(ui("\n採用できなかった候補の比較結果\n", "\nComparison for the rejected candidate\n"))
                        append(inputAgreementText(failure.knownBitsCompared, failure.knownBitMismatches, false, compact = true))
                    } else append(ui("\nデータ・ECCの比較結果: 記録なし", "\nData/ECC comparison: not recorded"))
                },
                verification),
        ), listOf(searchResultDescription(context), explanation).filter(String::isNotBlank).joinToString("\n"))
    }

    private fun referenceTarget(failure: RecoveryFailure, geometry: GridGeometry?): String =
        ui("参考診断の対象: ", "Reference diagnostic for: ") +
            geometry?.version?.let { version -> "V$version / " }.orEmpty() +
            "ECC ${failure.ecLevel} / " + ui("マスク${failure.dataMask}", "mask ${failure.dataMask}")

    private fun searchEvidence(context: RecoveryDiagnosticContext): RecoverySearchDiagnostic =
        context.searchDiagnostic?.let { if (context.unsearched) it.withUnsearched() else it }
            ?: RecoverySearchDiagnostic(unsearched = context.unsearched, coverageComplete = false)

    private fun searchOverview(context: RecoveryDiagnosticContext): String {
        val diagnostic = searchEvidence(context)
        return listOf(
            ui("今回の探索全体", "Search-wide status"),
            diagnostic.completeness.description(),
            diagnostic.completionReasonsText(),
            if (context.candidatesOmitted || diagnostic.candidatesOmitted)
                ui("候補の省略: あり（保持上限）", "Candidates omitted: yes (retention limit)")
            else ui("候補の省略: なし", "Candidates omitted: no"),
        ).filter(String::isNotBlank).joinToString("\n")
    }

    private fun listDecodingProcessingText(diagnostic: RecoverySearchDiagnostic?, wholeSearch: Boolean): String {
        val summary = diagnostic?.listDecoding
        val execution = diagnostic?.listDecodingExecution
        val ineligible = summary?.trials == 0 && summary.reusedTrials == 0 &&
            ListDecodingStage.entries.all { execution?.stages?.get(it) == setOf(ListDecodingStatus.INELIGIBLE) }
        val status = if (ineligible) ui(
            "適用条件を満たさないため、リスト復号の計算は実行しませんでした。",
            "List-decoding calculations were not run because the eligibility conditions were not met.",
        ) else execution?.description()
        return listOfNotNull(summary?.description(wholeSearch), status).filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun combinationStopReasons(summary: CombinationSearchSummary): String? = summary.copy(stops = summary.stops.filterTo(linkedSetOf()) {
            it != CombinationSearchStop.USER_INTERRUPTED && it != CombinationSearchStop.WORKING_MEMORY_LIMIT
        }).stopReasonsText().takeIf(String::isNotBlank)?.let {
            ui("QR候補の組合せ探索の終了理由\n", "Stop reasons for QR candidate combination search\n") + it
        }

    private fun candidateProcessingSection(candidate: RecoveryCandidate, context: RecoveryDiagnosticContext): DiagnosticSection {
        val methods = candidate.groupedMethodRecords()
        return DiagnosticSection(DiagnosticStage.PROCESSING, candidate.compactUsedMethodsText(), listOfNotNull(
            DiagnosticDetailGroup(DiagnosticDetailId.METHODS,
                ui("この候補を得た方法", "Methods used to obtain this candidate"),
                text = listOf(candidate.processingMethodsText(), candidate.displayConditions.candidateCalculationPolicyText(),
                    structureCheckSupplement(methods.first()))
                    .filter(String::isNotBlank).joinToString("\n\n")),
            DiagnosticDetailGroup(DiagnosticDetailId.CALCULATIONS,
                ui("この候補の計算記録", "Calculation record for this candidate"),
                text = listOfNotNull(candidateCalculationText(candidate), combinationStopReasons(candidate.combinationSearch))
                    .filter(String::isNotBlank).joinToString("\n\n"), collapsible = true),
            alternativeDerivationsGroup(candidate, methods, context.selectedEntry),
            searchProcessingGroup(context.searchDiagnostic ?: candidate.searchDiagnostic, wholeSearch = context.searchDiagnostic != null),
        ))
    }

    private fun searchProcessingGroup(diagnostic: RecoverySearchDiagnostic?, assumptions: String = "", hasCandidate: Boolean = true,
        wholeSearch: Boolean = true): DiagnosticDetailGroup? {
        val text = listOfNotNull(assumptions, listDecodingProcessingText(diagnostic, wholeSearch), diagnostic?.listDecoding?.calculationDetails(wholeSearch))
            .filter(String::isNotBlank).joinToString("\n\n")
        return text.takeIf(String::isNotBlank)?.let { DiagnosticDetailGroup(DiagnosticDetailId.SEARCH,
            if (wholeSearch) ui("探索全体の記録", "Search-wide records")
            else if (hasCandidate) ui("選択候補を得た入力の探索記録", "Search records for the selected candidate's input")
            else ui("参考診断の入力の探索記録", "Search records for the reference input"),
            if (!wholeSearch) ui("対象の入力についての集計です。他の読み取り入力も含む探索全体の集計は記録されていません。",
                "These totals cover the identified input. Totals across all sampled inputs were not recorded.")
            else if (hasCandidate) ui("選択中の候補に使わなかった入力・ブロックの試行も含む集計です。",
                "These totals also include input and block trials that did not produce the selected candidate.")
            else ui("探索した入力・ブロック全体の集計です。", "These totals cover all searched inputs and blocks."), it) }
    }

    private fun candidateCalculationText(candidate: RecoveryCandidate): String = buildString {
        append(ui("コード語は8bitのまとまりです。ブロックは誤り訂正を計算する単位です。",
            "A codeword contains 8 bits. A block is a unit of error-correction calculation."))
        if (candidate.actualBlockMethods.any { it.bitConstraintSolved }) append(ui(
            "\n自由度は、誤り訂正の条件を満たす組合せの中で、独立に選べる不明bitの数です。",
            "\nDegrees of freedom count unknown bits that can be chosen independently among combinations satisfying the error-correction conditions."))
        candidate.listDecodingMismatchLimit?.let {
            append(ui("\nこの候補のリスト復号の既知ビットの不一致上限: ${it}ビット",
                "\nKnown-bit mismatch limit for this candidate's list decoding: $it bits"))
        }
        append(ui("\n構造補完・優先セルの仮置きを反映した不明コード語数（全ブロック合計）: ", "\nUnknown codewords after structure filling and priority assignments (all blocks): "))
            .append(candidate.erasedCodewords)

        append(ui("\n補完・訂正の対象コード語数: ", "\nCodewords targeted for filling/correction: ")).append(candidate.correctedErrataSymbols)
        if (candidate.repairInfo.none { it.bitConstraintSolved || it.listDecoding != null } &&
            candidate.actualBlockMethods.none { it.bitConstraintSolved || it.listDecoding != null }) {
            append(ui("\n通常RSの計算値 ECC−消失数−2×誤りコード語数（ブロック最小）: ",
                "\nConventional RS calculation, ECC−erasures−2×errors (minimum across blocks): ")).append(candidate.minRsMargin)
            append(ui("（この補完方法での値。元入力の残り訂正能力や正しさを示しません）",
                " (for this derivation; not remaining input correction capacity or proof of correctness)"))
        }
        candidate.actualBlockMethods.forEach { method ->
            append("\n\n").append(blockCalculationText(method))
        }
    }

    private fun blockCalculationText(method: RecoveryBlockMethod): String = buildString {
        append(ui("ブロック${method.blockIndex + 1}", "Block ${method.blockIndex + 1}"))
        val list = method.listDecoding
        append(ui("\n方法: ", "\nMethod: ")).append(when {
            list != null -> list.method.displayName() + ui("によるリスト復号", " list decoding")
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
        } else if (!method.bitConstraintSolved) append(ui("\nこのブロックの通常RS訂正余裕: ${method.rsMargin ?: "—"}",
            "\nConventional RS correction margin for this block: ${method.rsMargin ?: "—"}"))
    }

    private fun alternativeDerivationsGroup(candidate: RecoveryCandidate, methods: List<RecoveryMethodRecords>,
        entry: RecoveryResultEntry?): DiagnosticDetailGroup? {
        val selectedEntry = entry?.takeIf { it.candidate === candidate }
        val omitted = methods.any { group -> group.records.any { it.derivationsOmitted } } || selectedEntry?.derivationsOmitted == true
        val summary = buildList {
            if (methods.any { it.blockDerivations.isNotEmpty() }) add(ui(
                "同じブロック値を得た別の方法の記録があります。", "Alternative methods yielding the same block values were recorded."))
            if (methods.size > 1) add(ui(
                "保持したQR全体の補完方法: ${methods.size}通り（代表を含む）",
                "Retained methods for the whole QR: ${methods.size} (including the representative)"))
            selectedEntry?.alternativeDerivations?.size?.takeIf { it > 0 }?.let { count -> add(ui(
                "同じQRを得た別の読み取り試行: ${count}件", "Other sampled-input attempts yielding the same QR: $count")) }
            if (isNotEmpty()) add(ui(
                "セルの説明には代表の方法を使います。別経路の数は別候補の数ではありません。",
                "Cell explanations use the representative method. Alternative routes are not additional candidates."))
            if (omitted) add(ui("保持上限により、同じQRを得た一部の別経路の詳細を省略しています。",
                "Some alternative derivation details for this QR were omitted at the retention limit."))
        }.joinToString("\n")
        if (summary.isBlank()) return null
        val text = buildString {
            methods.forEachIndexed { qrIndex, method ->
                val route = method.representative
                if (methods.size > 1) {
                    append(ui("\n補完方法${qrIndex + 1}", "\nMethod ${qrIndex + 1}"))
                    if (qrIndex == 0) append(ui("（代表）", " (representative)"))
                    append(ui(": 構造による補完 ${route.constraintFilledBits}bit / 優先探索 ${route.priorityGuessedBits}bit",
                        ": structure ${route.constraintFilledBits} bits / priority ${route.priorityGuessedBits} bits"))
                    route.actualBlockMethods.forEach {
                        append("\n  ").append(it.searchDescription())
                        if (!it.bitConstraintSolved && it.listDecoding == null) append(ui(
                            "／通常RS訂正余裕: ${it.rsMargin ?: "—"}", " / conventional RS margin: ${it.rsMargin ?: "—"}"))
                    }
                    if (route.priorityAssignments.isNotEmpty()) append(ui("\n  優先セルへの仮置き（座標は0から）: ",
                        "\n  Tentative values for prioritized cells (zero-based coordinates): "))
                        .append(route.priorityAssignments.entries.sortedWith(compareBy({ it.key.y }, { it.key.x }))
                            .joinToString(", ") { (coord, value) -> "(${coord.x},${coord.y})=${value.bitOrNull() ?: "?"}" })
                    if (qrIndex > 0) append(ui("\n  代表の方法との違い: ", "\n  Differences from the representative: "))
                        .append(candidate.methodDifferences(route).joinToString(ui("、", ", ")) { it.description() })
                }
                if (qrIndex > 0) structureCheckSupplement(method).takeIf(String::isNotBlank)?.let {
                    append("\n  ").append(it.replace("\n", "\n  "))
                }
                method.blockDerivations.forEach { (block, derivations) ->
                    append('\n')
                    if (methods.size > 1) append(ui("補完方法${qrIndex + 1}・", "Method ${qrIndex + 1}, "))
                    append(ui("RSブロック${block + 1}: 同じ補完結果を得た方法: ${derivations.size}通り（代表は1）",
                        "RS block ${block + 1}: ${derivations.size} derivations of the same values (representative: 1)"))
                    derivations.forEachIndexed { index, derivation ->
                        append("\n  ${index + 1}: ").append(derivation.description())
                    }
                }
            }
            selectedEntry?.alternativeDerivations?.forEachIndexed { index, route ->
                append(ui("\n同じQRを得た別の試行${index + 1}: ", "\nAnother attempt yielding the same QR ${index + 1}: "))
                append(route.profile?.label.orEmpty()).append(" / ").append(geometryDiagnosticText(route.hypothesis.geometry))
                append("\n").append(route.candidate.usedMethodsText())
                route.samplingStats.thresholds?.let { append(ui("\n閾値: ${it.blackMax} / ${it.whiteMin}",
                    "\nThresholds: ${it.blackMax} / ${it.whiteMin}")) }
            }
        }.trim()
        return DiagnosticDetailGroup(DiagnosticDetailId.DERIVATIONS,
            ui("同じQRを得た処理の記録", "Records yielding the same QR"), summary, text, collapsible = true)
    }

    private fun structureCheckSupplement(method: RecoveryMethodRecords): String {
        val checks = method.structureChecks
        if (checks.size <= 1) return ""
        return buildList {
            add(ui("補足: QR構造の確認", "Supplement: QR structure checks"))
            addAll(checks.filter { it.recorded }.map(::structureCheckText).distinct())
            if (method.representative.constraintFilledBits == 0) add(ui(
                "構造による補完・変更はありません。", "No values were filled or changed using QR structure."))
        }.joinToString("\n")
    }

    private fun RecoveryMethodDifference.description(): String = when (this) {
        RecoveryMethodDifference.INPUT -> ui("計算に使った入力・QRの形式", "calculation input or QR format")
        RecoveryMethodDifference.STRUCTURE -> ui("構造による補完・その前提", "structure filling or its assumptions")
        RecoveryMethodDifference.PRIORITY -> ui("優先セルの位置・仮置きした値", "prioritized cell locations or tentative values")
        RecoveryMethodDifference.BLOCKS -> ui("ブロックの計算方法・条件", "block calculation methods or parameters")
        RecoveryMethodDifference.CELL_REPAIRS -> ui("セルごとの補完・訂正の内訳（位置・値・方法）", "repairs to individual cells (locations, values or methods)")
        RecoveryMethodDifference.ASSUMPTION -> ui("単一セグメントの仮定の適用", "application of the single-segment assumption")
        RecoveryMethodDifference.LIMIT -> ui("リスト復号の不一致上限", "list-decoding mismatch limit")
        RecoveryMethodDifference.SETTINGS -> ui("適用した設定・形式の選択", "applied settings or format selection")
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

    private fun failureProcessingText(failure: RecoveryFailure): String {
        val processing = buildString {
            if (failure.manualConflicts.isNotEmpty()) {
                fun value(value: ModuleValue) = when (value) {
                    ModuleValue.ZERO -> ui("白（0）", "white (0)")
                    ModuleValue.ONE -> ui("黒（1）", "black (1)")
                    ModuleValue.ERASED -> ui("不明（?）", "unknown (?)")
                }
                append(ui("固定セルとの矛盾（この参考計算・座標は0から）",
                    "Fixed-cell conflict details for this reference calculation (zero-based coordinates)"))
                if (failure.manualConflicts.any { it.expected == null }) append(ui(
                    "\n固定値の組合せが形式の条件と矛盾しています。必要な値が未確定のセルを、個別に誤りと判定したわけではありません。",
                    "\nThe combination of fixed values conflicts with the format conditions. Cells with no determined required value have not individually been identified as incorrect."))
                failure.manualConflicts.forEach { conflict ->
                    append(ui("\nセル (${conflict.coord.x},${conflict.coord.y}): 固定値 ${value(conflict.actual)}",
                        "\nCell (${conflict.coord.x},${conflict.coord.y}): fixed value ${value(conflict.actual)}"))
                    append(conflict.expected?.let { expected -> ui(
                        " / この参考計算で必要な値: ${value(expected)}",
                        " / required by this reference calculation: ${value(expected)}") }
                        ?: ui(" / 必要な値: 未確定", " / required value: not determined"))
                }
            }
            if (failure.failedBlockIndex != null) {
                if (isNotEmpty()) append('\n')
                append(ui("この参考計算で最初に結果を得られなかったRSブロック: ",
                    "First RS block without a result in this reference calculation: ")).append(failure.failedBlockIndex + 1)
                failure.rsBlockCount?.let { append(ui(" / 全${it}ブロック", " / $it total")) }
                failure.ecSymbolsPerBlock?.let {
                    append(ui("\n失敗ブロックのECCシンボル数: ", "\nECC symbols in the failing block: ")).append(it)
                }
                failure.failedBlockErasures?.let {
                    append(ui("\n失敗ブロックの未知bitを含むコード語数（探索前）: ", "\nErased codewords in the failing block (before search): ")).append(it)
                }
            }
            if (failure.originalErasedCodewords > 0 || failure.erasedCodewords > 0) {
                if (isNotEmpty()) append('\n')
                append(ui("構造補完・優先セルの仮置きを反映した不明コード語数（全ブロック合計）: ", "Unknown codewords after structure filling and priority assignments (all blocks): "))
                    .append(failure.erasedCodewords)

            }
            if (failure.ecSymbolsPerBlock != null || failure.originalErasedCodewords > 0 || failure.erasedCodewords > 0) {
                if (isNotEmpty()) append('\n')
                append(ui(
                    "ECC数と消失数の差は、残りの訂正能力を示しません。通常のRS訂正では、各ブロックで「消失数 + 2 × 誤りコード語数 ≤ ECC数」が必要です。読み取り済みコード語の誤り数は、この失敗診断では確定していません。",
                    "ECC count minus erasure count does not indicate remaining correction capacity. Conventional RS correction requires erasures + 2 × erroneous codewords ≤ ECC symbols in each block. This failure diagnostic has not established the number of erroneous observed codewords.",
                ))
            }
        }.trim()
        val shortfall = failure.rsShortfallBits?.let { bits -> ui(
            "構造補完後の欠損量から見た追加確認の目安: ${bits}bit\n入力済みの白黒と形式設定が正しいという前提です。読み取り済みセルの誤りは含めていません。",
            "Additional checks estimated from missing data after structure filling: $bits bits\nAssumes the entered values and format are correct; errors in observed cells are not included.") }.orEmpty()
        return listOf(processing, shortfall).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun geometryDiagnosticText(geometry: GridGeometry): String {
        val model = when (geometry.model) {
            GeometryModel.SIMILARITY -> ui("相似変換", "Similarity")
            GeometryModel.TWO_FINDER -> ui("2 Finder", "Two Finder patterns")
            GeometryModel.AFFINE -> ui("アフィン変換", "Affine")
            GeometryModel.PROJECTIVE -> ui("射影変換", "Projective")
            GeometryModel.MANUAL_PROJECTIVE -> ui("手動射影", "Manual projective")
        }
        val role = when (geometry.role) {
            FinderRole.TOP_LEFT -> ui("左上基準", "top-left reference")
            FinderRole.TOP_RIGHT -> ui("右上基準", "top-right reference")
            FinderRole.BOTTOM_LEFT -> ui("左下基準", "bottom-left reference")
        }
        val quad = geometry.moduleCenterQuad()
        val xAxis = quad.topRight - quad.topLeft
        val yAxis = quad.bottomLeft - quad.topLeft
        val mirrored = xAxis.x * yAxis.y - xAxis.y * yAxis.x < 0.0
        return ui("QRの位置・向き: $model / V${geometry.version} / $role", "QR position/orientation: $model / V${geometry.version} / $role") +
            if (mirrored) ui(" / 鏡像補正あり", " / mirrored input") else ""
    }

    private fun ui(japanese: String, english: String): String = if (this.japanese) japanese else english
}
