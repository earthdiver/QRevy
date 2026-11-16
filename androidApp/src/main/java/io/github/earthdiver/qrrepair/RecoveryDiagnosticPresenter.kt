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
    val discoveredCandidates: RecoveryDiscoveryCount? = null,
    val aggregatesTrials: Boolean = selectedEntry != null,
    val searchSummaryShownSeparately: Boolean = false,
    val combinationStops: Set<CombinationSearchStop> = emptySet(),
    val trialCount: Int? = null,
)

/** Builds diagnostic content without retaining an Activity, Views, or mutable UI state. */
internal class RecoveryDiagnosticPresenter(private val japanese: Boolean) {
    fun searchResultDescription(context: RecoveryDiagnosticContext): String {
        val diagnostic = searchEvidence(context)
        context.selectedEntry?.let { selected ->
            val trial = selected.attempt.searchDiagnostic ?: selected.candidate.searchDiagnostic
            return listOf(
                ui("選択候補を得た試行（表示中の設定）", "Trial that produced the selected candidate (displayed settings)"),
                assumptionText(selected.candidate.displayConditions),
                selectedTrialSummary(context),
                trial?.terminationText(selected.candidate.combinationSearch.stops) ?: missingTrialRecord(),
                if (context.trialCount != 1) ui("全条件・格子の結果一覧: ${context.candidateCount}件", "Results across all conditions and grids: ${context.candidateCount}") else "",
                context.discoveredCandidates?.let { discoveryCountText(it, japanese) }.orEmpty(),
                if (context.trialCount != 1) ui("別条件・格子を含む探索の記録は、診断情報で確認できます。", "Diagnostics contain search records including other conditions and grids.") else "",
                if (context.candidatesOmitted || diagnostic.candidatesOmitted)
                    ui("保持上限により、一部の候補は省略しています。", "Some candidates were omitted at the retention limit.") else "",
            ).filter(String::isNotBlank).joinToString("\n")
        }
        if (context.aggregatesTrials) return listOf(
            ui("全試行の累積結果（条件・格子をまとめた集計）", "Cumulative results across trial conditions and grids"),
            context.discoveredCandidates?.let { discoveryCountText(it, japanese) }.orEmpty(),
            ui("結果一覧: ${context.candidateCount}件", "Results available: ${context.candidateCount}"),
            ui("各候補に適用した条件は、候補を選択して確認できます。",
                "Select a candidate to review the conditions applied to it."),
            diagnostic.terminationText(context.combinationStops),
            if (context.candidatesOmitted || diagnostic.candidatesOmitted)
                ui("保持上限により、一部の候補は省略しています。", "Some candidates were omitted at the retention limit.") else "",
        ).filter(String::isNotBlank).joinToString("\n")
        val completeness = diagnostic.completeness
        val description = if (diagnostic.coverageComplete && diagnostic.completedSteps == 0 &&
            SearchIncompleteReason.INTERNAL_ERROR in diagnostic.incompleteReasons) {
            diagnostic.terminationText(context.combinationStops)
        } else searchResultText(context.candidateCount, completeness,
            context.candidatesOmitted || diagnostic.candidatesOmitted, "", diagnostic.terminationText(context.combinationStops))
        val count = context.discoveredCandidates ?: return description
        return buildString {
            append(discoveryCountText(count, japanese))
            if (count is RecoveryDiscoveryCount.AtLeast || count.value != context.candidateCount || context.candidatesOmitted) {
                append("\n").append(ui("結果一覧: ${context.candidateCount}件", "Results available: ${context.candidateCount}"))
            }
            append("\n").append(description)
        }
    }

    private fun assumptionText(conditions: RecoveryDisplayConditions?): String = when {
        conditions?.assumeUtf8ByteSegments == true && conditions.trustSingleSegment -> ui(
            "UTF-8と単一セグメントを仮定して得た候補です。", "This candidate was obtained assuming UTF-8 and a single segment.")
        conditions?.trustSingleSegment == true -> ui("単一セグメントを仮定して得た候補です。", "This candidate was obtained assuming a single segment.")
        conditions?.assumeUtf8ByteSegments == true -> ui("UTF-8を仮定して得た候補です。", "This candidate was obtained assuming UTF-8.")
        conditions?.dataEccLimit == 0 -> ui("入力の既知データ・ECCビットと完全に一致する条件で得た候補です。",
            "This candidate was obtained requiring exact agreement with known data/ECC bits.")
        else -> ""
    }

    private fun missingTrialRecord(): String = ui("この試行の終了理由は記録されていません。", "The termination reason for this trial was not recorded.")

    fun candidateReport(candidate: RecoveryCandidate, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val preview = QrContentPreviewParser.parse(candidate)
        val origin = context.selectedEntry?.attempt?.hypothesis
        val patternRefined = origin?.patternRefined == true && origin.geometry == geometry
        val input = initialDiagnosticSection(candidate.version, candidate.ecLevel, candidate.dataMask,
            geometry?.let { geometryDiagnosticText(it, patternRefined) }.orEmpty(), candidate.rsBitDiagnostic,
            candidate.extraction.codewords.takeIf { it.isNotEmpty() }?.sumOf { (it.unknownBits and 255).countOneBits() },
            candidate.originalErasedCodewords, candidate.conventionalRsDiagnostic, candidate.correctedInterleaved,
            conditions = candidate.displayConditions)
        val verification = buildString {
            append(if (preview?.qrStructureValid == true) ui("QRのデータ構造を確認できました。", "The QR data structure was verified.")
                else ui("QRのデータ構造を確認できませんでした。", "The QR data structure could not be verified."))
            append(ui("\n比較対象は、画像の読み取りと手動編集を反映した、修復開始時の既知の0/1です。",
                "\nComparisons use known 0/1 values at repair start, including image sampling and manual edits."))
            append('\n').append(candidate.inputComparisonText())
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
                "\n指定した単一セグメントの終端後構造の強制を適用した候補です。",
                "\nThis candidate uses the specified single-segment assumption."))
        }
        val verificationSummary = buildString {
            append(candidateChecks)
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
        ), if (context.searchSummaryShownSeparately) "" else searchOverview(context))
    }

    fun failureReport(failure: RecoveryFailure, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val input = initialDiagnosticSection(geometry?.version, failure.ecLevel, failure.dataMask,
            geometry?.let(::geometryDiagnosticText).orEmpty(), failure.rsBitDiagnostic, null,
            failure.originalErasedCodewords.takeIf { failure.rsBitDiagnostic != null || failure.failedBlockIndex != null || it > 0 },
            failure.conventionalRsDiagnostic, conditions = failure.displayConditions)
        val explanation = searchEvidence(context).failureExplanation(failure.displayConditions)
        val processing = buildString {
            append(failure.displayConditions.candidateCalculationPolicyText())
            explanation.takeIf(String::isNotBlank)?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }
        }.trim()
        val verification = buildString {
            append(referenceTarget(failure, geometry))
            append(ui("\n\n参考診断の形式と入力との比較\n", "\n\nComparison of the reference format with the input\n"))
            append(inputAgreementText(failure.formatKnownBits, failure.formatMismatches, true))
            if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                append(ui("\n採用できなかった候補と入力との比較\n", "\nComparison of the rejected candidate with the input\n"))
                append(inputAgreementText(failure.knownBitsCompared, failure.knownBitMismatches, false))
            }
        }
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
            if (failure.displayConditions?.trustSingleSegment == true)
                singleSegmentProcessingGroup(context.searchDiagnostic ?: failure.searchDiagnostic,
                    wholeSearch = context.searchDiagnostic != null) else null,
            listDecodingProcessingGroup(context.searchDiagnostic ?: failure.searchDiagnostic, hasCandidate = false,
                wholeSearch = context.searchDiagnostic != null),
            searchRecordGroup(context, context.searchDiagnostic ?: failure.searchDiagnostic),
        )
        return RecoveryDiagnosticReport(ui("修復結果の診断", "Recovery result diagnostics"), listOf(
            failure.displayConditions.runSettingsSection(),
            input,
            DiagnosticSection(DiagnosticStage.PROCESSING,
                explanation, groups),
            DiagnosticSection(DiagnosticStage.VERIFICATION,
                buildString {
                    append(ui("保持している修復候補: ${context.candidateCount}件", "Retained recovery candidates: ${context.candidateCount}"))
                    append("\n\n").append(referenceTarget(failure, geometry))
                    append(ui("\n修復開始時の入力との比較", "\nComparison with the input at repair start"))
                    append('\n').append(inputAgreementText(failure.formatKnownBits, failure.formatMismatches, true, compact = true))
                    if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                        append(ui("\n採用できなかった候補の比較結果\n", "\nComparison for the rejected candidate\n"))
                        append(inputAgreementText(failure.knownBitsCompared, failure.knownBitMismatches, false, compact = true))
                    } else append(ui("\nデータ・ECCの比較結果: 記録なし", "\nData/ECC comparison: not recorded"))
                },
                verification),
        ), if (context.searchSummaryShownSeparately) "" else searchResultDescription(context))
    }

    private fun referenceTarget(failure: RecoveryFailure, geometry: GridGeometry?): String =
        ui("参考診断の対象: ", "Reference diagnostic for: ") +
            geometry?.version?.let { version -> "V$version / " }.orEmpty() +
            "ECC ${failure.ecLevel} / " + ui("マスク${failure.dataMask}", "mask ${failure.dataMask}")

    private fun searchEvidence(context: RecoveryDiagnosticContext): RecoverySearchDiagnostic =
        context.searchDiagnostic?.let { if (context.unsearched) it.withUnsearched() else it }
            ?: RecoverySearchDiagnostic(unsearched = context.unsearched, coverageComplete = false)

    private fun searchOverview(context: RecoveryDiagnosticContext): String {
        if (context.selectedEntry != null) return searchResultDescription(context)
        val diagnostic = searchEvidence(context)
        return listOf(
            if (context.aggregatesTrials) ui("全試行の累積状況（異なる条件・格子を含む）",
                "Cumulative status across trial conditions and grids") else ui("今回の探索全体", "Search-wide status"),
            ui("保持している修復候補: ${context.candidateCount}件", "Retained recovery candidates: ${context.candidateCount}"),
            diagnostic.terminationText(context.combinationStops),
            if (context.candidatesOmitted || diagnostic.candidatesOmitted)
                ui("候補の省略: あり（保持上限）", "Candidates omitted: yes (retention limit)")
            else ui("候補の省略: なし", "Candidates omitted: no"),
        ).filter(String::isNotBlank).joinToString("\n")
    }

    private fun selectedTrialSummary(context: RecoveryDiagnosticContext): String {
        if (!context.aggregatesTrials) return ""
        val entry = context.selectedEntry ?: return ""
        val count = entry.attempt.recoveries.filter { it.isSelectableRecovery }.map { it.identity() }.distinct().size
        return listOf(
            ui("この試行の保持候補: ${count}件", "Candidates retained from this trial: $count"),
            if (entry.attempt.searchDiagnostic?.candidatesOmitted == true) ui("この試行には保持上限による候補の省略があります。",
                "Some candidates from this trial were omitted at the retention limit.") else "",
        ).filter(String::isNotBlank).joinToString("\n")
    }

    private fun searchRecordGroup(context: RecoveryDiagnosticContext, selected: RecoverySearchDiagnostic?): DiagnosticDetailGroup? {
        val aggregate = context.selectedEntry != null && context.trialCount != 1
        val diagnostic = if (aggregate) context.searchDiagnostic else selected
        val selectedTermination = context.selectedEntry?.let {
            (it.attempt.searchDiagnostic ?: it.candidate.searchDiagnostic)?.terminationText(it.candidate.combinationSearch.stops)
        }
        val text = listOf(
            if (aggregate) diagnostic?.terminationText()?.let {
                if (it == selectedTermination) ui("全試行の集計でも、選択試行と同じ終了理由が記録されています。",
                    "The aggregate records have the same termination reasons as the selected trial.") else it
            } ?: missingTrialRecord() else "",
            diagnostic?.formatTerminationDetails().orEmpty(),
            if (aggregate) listDecodingProcessingText(diagnostic, wholeSearch = true) else "",
        ).filter(String::isNotBlank).joinToString("\n\n")
        if (text.isBlank()) return null
        return DiagnosticDetailGroup(DiagnosticDetailId.TRIALS,
            if (aggregate) ui("全試行の探索記録（別条件・格子を含む）", "Search records across trials (including other conditions and grids)")
            else ui("形式ごとの探索記録", "Search records by format"),
            if (aggregate) ui(
                "選択候補を得た試行と、別条件・格子の試行を含む集計です。同じECC・マスクでも、異なる条件の記録があります。選択試行の終了状況は結果の概要に表示しています。",
                "These totals include the selected candidate's trial and trials with other conditions or grids. The same ECC/mask can have records under different conditions. The selected trial's termination is shown in the result summary.")
            else ui("結果の概要に示した終了理由について、対象のECC・マスクを確認できます。",
                "These records identify the ECC/masks affected by the termination reasons in the result summary."),
            text, collapsible = true)
    }

    private fun listDecodingIneligible(diagnostic: RecoverySearchDiagnostic?): Boolean =
        diagnostic?.listDecoding?.let { summary -> summary.trials == 0 && summary.reusedTrials == 0 &&
            ListDecodingStage.entries.all { diagnostic.listDecodingExecution?.stages?.get(it) == setOf(ListDecodingStatus.INELIGIBLE) } } == true

    private fun listDecodingProcessingText(diagnostic: RecoverySearchDiagnostic?, wholeSearch: Boolean): String {
        val summary = diagnostic?.listDecoding
        val execution = diagnostic?.listDecodingExecution
        val status = if (listDecodingIneligible(diagnostic)) ui(
            "リスト復号：未実行（適用条件を満たさないため）",
            "List decoding: not run (eligibility conditions not met)",
        ) else execution?.description()
        return listOfNotNull(summary?.description(wholeSearch), status)
            .filter(String::isNotBlank).joinToString("\n\n")
    }

    private fun combinationStopReasons(summary: CombinationSearchSummary): String? = summary.copy(stops = summary.stops.filterTo(linkedSetOf()) {
            it != CombinationSearchStop.USER_INTERRUPTED && it != CombinationSearchStop.WORKING_MEMORY_LIMIT
        }).stopReasonsText().takeIf(String::isNotBlank)?.let {
            ui("QR候補の組合せ探索の終了理由\n", "Stop reasons for QR candidate combination search\n") + it
        }

    private fun candidateProcessingSection(candidate: RecoveryCandidate, context: RecoveryDiagnosticContext): DiagnosticSection {
        val methods = candidate.groupedMethodRecords()
        val selectedDiagnostic = if (context.selectedEntry != null)
            context.selectedEntry.attempt.searchDiagnostic ?: candidate.searchDiagnostic
            else context.searchDiagnostic ?: candidate.searchDiagnostic
        return DiagnosticSection(DiagnosticStage.PROCESSING,
            candidate.compactUsedMethodsText(), listOfNotNull(
            DiagnosticDetailGroup(DiagnosticDetailId.METHODS,
                ui("この候補を得た方法", "Methods used to obtain this candidate"),
                text = listOf(candidate.processingMethodsText(), candidate.displayConditions.candidateCalculationPolicyText())
                    .filter(String::isNotBlank).joinToString("\n\n")),
            RecoveryCalculationPresenter(japanese).group(candidate, methods, context.selectedEntry,
                combinationStopReasons(candidate.combinationSearch)),
            listDecodingProcessingGroup(selectedDiagnostic,
                wholeSearch = context.selectedEntry == null && context.searchDiagnostic != null),
            searchRecordGroup(context, selectedDiagnostic),
        ))
    }

    private fun searchRecordScope(wholeSearch: Boolean, hasCandidate: Boolean): String =
        if (wholeSearch) ui("探索全体", "whole search")
        else if (hasCandidate) ui("選択候補の入力", "selected candidate's input")
        else ui("参考診断の入力", "reference diagnostic input")

    private fun searchRecordScopeDescription(wholeSearch: Boolean, hasCandidate: Boolean): String =
        if (!wholeSearch) ui("対象の入力についての集計です。別条件・格子の試行は含みません。",
            "These totals cover the identified input, excluding trials with other conditions or grids.")
        else if (hasCandidate) ui("選択中の候補に使わなかった入力・ブロックの試行も含む集計です。",
            "These totals also include input and block trials that did not produce the selected candidate.")
        else ui("探索した入力・ブロック全体の集計です。", "These totals cover all searched inputs and blocks.")

    private fun singleSegmentProcessingGroup(diagnostic: RecoverySearchDiagnostic?, wholeSearch: Boolean): DiagnosticDetailGroup {
        val scope = searchRecordScope(wholeSearch, hasCandidate = false)
        return DiagnosticDetailGroup(DiagnosticDetailId.SINGLE_SEGMENT,
            ui("単一セグメントの終端後構造の強制の適用状況（$scope）", "Single-segment terminator and padding forcing application status ($scope)"),
            searchRecordScopeDescription(wholeSearch, hasCandidate = false), diagnostic.singleSegmentProcessingText())
    }

    private fun listDecodingProcessingGroup(diagnostic: RecoverySearchDiagnostic?, hasCandidate: Boolean = true,
        wholeSearch: Boolean = true): DiagnosticDetailGroup? {
        val status = listDecodingProcessingText(diagnostic, wholeSearch)
        val details = diagnostic?.listDecoding?.calculationDetails().orEmpty()
        if (status.isBlank() && details.isBlank()) return null
        val scope = searchRecordScope(wholeSearch, hasCandidate)
        return DiagnosticDetailGroup(DiagnosticDetailId.SEARCH,
            ui("リスト復号の実行状況（$scope）", "List decoding execution status ($scope)"),
            listOf(searchRecordScopeDescription(wholeSearch, hasCandidate), status)
                .filter(String::isNotBlank).joinToString("\n\n"),
            details, collapsible = details.isNotBlank())
    }

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

    fun geometryDiagnosticText(geometry: GridGeometry, patternRefined: Boolean = false): String {
        val model = when (geometry.model) {
            GeometryModel.SIMILARITY -> ui("相似変換", "Similarity")
            GeometryModel.TWO_FINDER -> ui("2 Finder", "Two Finder patterns")
            GeometryModel.AFFINE -> ui("アフィン変換", "Affine")
            GeometryModel.PROJECTIVE -> if (patternRefined) ui("固定パターンで射影補正", "Pattern-refined projective grid") else ui("射影変換", "Projective")
            GeometryModel.MANUAL_PROJECTIVE -> ui("四隅に基づく射影変換", "Projective mapping from four corners")
            GeometryModel.THIN_PLATE_SPLINE -> ui("曲面補正を使用", "Distortion correction applied")
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
