package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.*

/** Search-wide evidence and the selected origin are captured separately from editable settings. */
internal data class RecoveryDiagnosticContext(
    val candidateCount: Int,
    val unsearched: Boolean,
    val candidatesOmitted: Boolean,
    val searchDiagnostic: RecoverySearchDiagnostic?,
    val selectedEntry: RecoveryResultEntry?,
)

/** Builds diagnostic content without retaining an Activity, Views, or mutable UI state. */
internal class RecoveryDiagnosticPresenter(private val japanese: Boolean) {
    fun searchResultDescription(context: RecoveryDiagnosticContext): String {
        val completeness = if (context.unsearched) SearchCompleteness.UNSEARCHED
            else context.searchDiagnostic?.completeness ?: SearchCompleteness.UNKNOWN
        val diagnostic = context.searchDiagnostic ?: RecoverySearchDiagnostic(
            unsearched = context.unsearched, coverageComplete = false)
        val reasons = diagnostic.completionReasonsText()
        if (diagnostic.coverageComplete && diagnostic.completedSteps == 0 &&
            SearchIncompleteReason.INTERNAL_ERROR in diagnostic.incompleteReasons) {
            return completeness.description() + "\n" + reasons
        }
        return searchResultText(context.candidateCount, completeness, context.candidatesOmitted, reasons)
    }

    fun candidateReport(candidate: RecoveryCandidate, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val preview = QrContentPreviewParser.parse(candidate)
        val listDecoding = (context.searchDiagnostic ?: candidate.searchDiagnostic)?.listDecoding
        val input = initialDiagnosticSection(candidate.version, candidate.ecLevel, candidate.dataMask,
            geometry?.let(::geometryDiagnosticText).orEmpty(), candidate.rsBitDiagnostic,
            candidate.extraction.codewords.takeIf { it.isNotEmpty() }?.sumOf { (it.unknownBits and 255).countOneBits() },
            candidate.originalErasedCodewords, candidate.conventionalRsDiagnostic, candidate.correctedInterleaved,
            candidate.actualBlockMethods.any { it.bitConstraintSolved } || candidate.repairInfo.any { it.bitConstraintSolved },
            conditions = candidate.displayConditions)
        val verification = buildString {
            append(if (preview?.qrStructureValid == true) ui("QRのデータ構造を確認できました。", "The QR data structure was verified.")
                else ui("QRのデータ構造を確認できませんでした。", "The QR data structure could not be verified."))
            append('\n').append(candidate.inputComparisonText())
            append(ui("\n\n今回の探索全体\n", "\n\nSearch-wide results\n"))
            append(searchResultDescription(context))
        }
        val processing = listOfNotNull(candidate.displayConditions.candidateCalculationPolicyText(),
            candidate.usedMethodsText(),
            (context.searchDiagnostic ?: candidate.searchDiagnostic)?.listDecodingExecution?.description(),
            listDecoding?.description())
            .filter(String::isNotBlank).joinToString("\n\n")
        val stopReasons = candidate.combinationSearch.copy(stops = candidate.combinationSearch.stops.filterTo(linkedSetOf()) {
            it != CombinationSearchStop.USER_INTERRUPTED && it != CombinationSearchStop.WORKING_MEMORY_LIMIT
        }).stopReasonsText().takeIf(String::isNotBlank)?.let {
            ui("QR候補の組合せ探索の終了理由\n", "Stop reasons for QR candidate combination search\n") + it
        }
        val processingDetails = listOfNotNull(candidateProcessingText(candidate, context.selectedEntry),
            listDecoding?.calculationDetails(), stopReasons)
            .filter(String::isNotBlank).joinToString("\n\n")
        return RecoveryDiagnosticReport(ui("選択中の候補の診断", "Diagnostics for the selected candidate"), listOf(
            DiagnosticSection(DiagnosticStage.SETTINGS, candidate.displayConditions.runSettingsText()),
            input,
            DiagnosticSection(DiagnosticStage.PROCESSING, processing, processingDetails),
            DiagnosticSection(DiagnosticStage.VERIFICATION, verification, preview?.toDisplayText().orEmpty()),
        ))
    }

    fun failureReport(failure: RecoveryFailure, geometry: GridGeometry?, context: RecoveryDiagnosticContext): RecoveryDiagnosticReport {
        val listDecoding = (context.searchDiagnostic ?: failure.searchDiagnostic)?.listDecoding
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
            if (isEmpty()) append(ui("指定条件のもとで修復候補を探索しました。", "Recovery candidates were searched under the specified conditions."))
            if (failure.displayConditions?.trustSingleSegment == true) {
                append("\n\n").append((context.searchDiagnostic ?: failure.searchDiagnostic).singleSegmentProcessingText())
            }
            listDecoding?.let {
                if (isNotEmpty()) append("\n\n")
                append(it.description())
            }
            (context.searchDiagnostic ?: failure.searchDiagnostic)?.listDecodingExecution?.let {
                if (isNotEmpty()) append("\n\n")
                append(it.description())
            }
        }
        val verification = buildString {
            append(searchResultDescription(context))
            append(ui("\n\n参考診断の形式と入力との比較\n", "\n\nComparison of the reference format with the input\n"))
            append(inputAgreementText(failure.formatKnownBits, failure.formatMismatches, true))
            if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                append(ui("\n採用できなかった候補と入力との比較\n", "\nComparison of the rejected candidate with the input\n"))
                append(inputAgreementText(failure.knownBitsCompared, failure.knownBitMismatches, false))
            }
        }
        val processingDetails = listOfNotNull(
            failureProcessingText(failure).takeIf(String::isNotBlank)?.let {
                ui("参考：補完・探索後の計算記録（", "Reference: calculation after filling/search (") +
                    (geometry?.version?.let { version -> "V$version / " }.orEmpty()) +
                    "ECC ${failure.ecLevel} / " + ui("マスク${failure.dataMask}）\n", "mask ${failure.dataMask})\n") + it
            }, listDecoding?.calculationDetails(),
        ).joinToString("\n\n")
        return RecoveryDiagnosticReport(ui("修復結果の診断", "Recovery result diagnostics"), listOf(
            DiagnosticSection(DiagnosticStage.SETTINGS, failure.displayConditions.runSettingsText()),
            input,
            DiagnosticSection(DiagnosticStage.PROCESSING, processing, processingDetails),
            DiagnosticSection(DiagnosticStage.VERIFICATION, verification),
        ))
    }

    private fun candidateProcessingText(candidate: RecoveryCandidate, entry: RecoveryResultEntry?): String {
        val processing = buildString {
            candidate.listDecodingMismatchLimit?.let {
                append(ui("この候補のリスト復号の既知ビットの不一致上限: ${it}ビット",
                    "Known-bit mismatch limit for this candidate's list decoding: $it bits"))
            }
            if (candidate.constraintFilledBits > 0) {
                append(if (candidate.constraintForcedByUserSetting) ui("\n指定した仮定に基づく補完・変更: ", "\nFilling/changes under the specified assumption: ")
                    else ui("\nQRの構造に基づく補完・変更: ", "\nFilling/changes using QR structure: "))
                    .append(candidate.constraintFilledBits).append(ui("bit（終端・埋め草など）", " bits (terminator, padding, etc.)"))
                append(ui("\n仮定した末尾構造と一致する読み取り済みbit: ", "\nObserved bits matching the assumed suffix structure: "))
                    .append(candidate.constraintEvidenceBits).append("bit")
            }
            if (candidate.priorityGuessedBits > 0) {
                append(ui("\n優先指定した不明セルの白黒を仮置きして探索: ", "\nSearch with tentative values for prioritized unknown cells: "))
                    .append(candidate.priorityGuessedBits).append(ui("bit（入力時は不明）", " bits (unknown in the input)"))
            }
            append(ui("\n構造補完・優先セルの仮置きを反映した不明コード語数（全ブロック合計）: ", "\nUnknown codewords after structure filling and priority assignments (all blocks): "))
                .append(candidate.erasedCodewords)

            append(ui("\n補完・訂正の対象コード語数: ", "\nCodewords targeted for filling/correction: ")).append(candidate.correctedErrataSymbols)
            if (candidate.repairInfo.any { it.bitConstraintSolved }) {
                append(ui("\n補完方法: 既知bitと誤り訂正の条件から不明部分を計算", "\nMethod: solve unknown bits using known bits and error-correction constraints"))
            } else if (candidate.actualBlockMethods.none { it.listDecoding != null }) {
                append(ui("\n通常RSの計算値 ECC−消失数−2×誤りコード語数（ブロック最小）: ",
                    "\nConventional RS calculation, ECC−erasures−2×errors (minimum across blocks): ")).append(candidate.minRsMargin)
                append(ui("（この補完方法での値。元入力の残り訂正能力や正しさを示しません）",
                    " (for this derivation; not remaining input correction capacity or proof of correctness)"))
            }
            if (candidate.actualBlockMethods.isNotEmpty()) {
                append(ui("\nこの候補のブロック別処理", "\nBlock methods for this candidate"))
                candidate.actualBlockMethods.forEach { method ->
                    append('\n').append(method.searchDescription())
                    if (!method.bitConstraintSolved && method.listDecoding == null) append(ui("／この計算の余裕: ${method.rsMargin ?: "—"}",
                        " / margin for this calculation: ${method.rsMargin ?: "—"}"))
                }
            }
            val qrRoutes = listOf(candidate) + candidate.alternativeDerivations
            qrRoutes.forEachIndexed { qrIndex, route ->
                route.blockDerivations.forEach { (block, derivations) ->
                    append(ui("\n補完方法${qrIndex + 1}・RSブロック${block + 1}: 同じ補完結果を得た方法: ${derivations.size}通り（代表は1）",
                        "\nRoute ${qrIndex + 1}, RS block ${block + 1}: ${derivations.size} derivations of the same values (representative: 1)"))
                    derivations.forEachIndexed { index, derivation ->
                        append("\n  ${index + 1}: ").append(derivation.description())
                    }
                }
            }
            if (candidate.alternativeDerivations.isNotEmpty()) {
                val routes = listOf(candidate) + candidate.alternativeDerivations
                append(ui("\n異なる補完方法で同じ復元結果が得られました: ${routes.size}通り（セルの説明は方法1）", "\nDerivations of the same QR: ${routes.size} (cell reasons use route 1)"))
                routes.forEachIndexed { index, route ->
                    append(ui("\n補完方法${index + 1}: 構造による補完 ${route.constraintFilledBits}bit / 優先探索 ${route.priorityGuessedBits}bit",
                        "\nRoute ${index + 1}: structure ${route.constraintFilledBits} bits / priority ${route.priorityGuessedBits} bits"))
                    route.actualBlockMethods.forEach { append("\n  ").append(it.searchDescription()) }
                }
            }
            val selectedEntry = entry?.takeIf { it.candidate === candidate }
            selectedEntry?.alternativeDerivations?.forEachIndexed { index, route ->
                append(ui("\n同じQRを得た別の試行${index + 1}: ", "\nAnother attempt yielding the same QR ${index + 1}: "))
                append(route.profile?.label.orEmpty()).append(" / ").append(geometryDiagnosticText(route.hypothesis.geometry))
                append("\n").append(route.candidate.usedMethodsText())
                route.samplingStats.thresholds?.let { append(ui("\n閾値: ${it.blackMax} / ${it.whiteMin}",
                    "\nThresholds: ${it.blackMax} / ${it.whiteMin}")) }
            }
            if (candidate.derivationsOmitted || selectedEntry?.derivationsOmitted == true) append(ui(
                "\n保持上限により、同じQRを得た一部の別経路の詳細を省略しています。",
                "\nSome alternative derivation details for this QR were omitted at the retention limit."))
        }.trim()
        return processing
    }


    private fun failureProcessingText(failure: RecoveryFailure): String {
        val processing = buildString {
            if (failure.failedBlockIndex != null) {
                if (isNotEmpty()) append('\n')
                append(ui("最初に失敗したRSブロック: ", "First failing RS block: ")).append(failure.failedBlockIndex + 1)
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
