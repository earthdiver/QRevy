package qrevy.web

import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.localizedText
import io.github.earthdiver.qrrepair.RecoveryDataDisplayState
import org.teavm.jso.JSObject

internal fun recoveryDataDisplayDefaults(hasValidatedCandidate: Boolean): Map<String, Boolean> =
    RecoveryDataDisplayState.forRecovery(hasValidatedCandidate).let {
        mapOf("after" to it.repairedStage, "unmasked" to it.unmasked, "partial" to it.partialInformation)
    }

/** Inspection uses the same candidate/extraction as recovery, never current edited input. */
internal object ResultInspection {
    private val candidates = mutableMapOf<Int, Pair<QrMatrix, RecoveryCandidate>>()
    private var sequence = 0
    private val diagnostics=mutableMapOf<Int,()->Any?>()
    private var assessmentCache: Pair<Int,Map<Coord,CellConfidence>>?=null
    private val assessmentEngine=RecoveryEngine()
    private var failed: Pair<QrMatrix,RecoveryFailure>?=null
    fun failure(matrix:QrMatrix,value:RecoveryFailure?){failed=value?.let{matrix to it}}
    fun failedTail():Any?=failed?.let{(matrix,f)->val v=(matrix.dimension-17)/4;tailResult(PostTerminatorAnalysis.forAttempt(CodewordExtractor.extract(matrix,v,f.dataMask),v,f.ecLevel,f.conventionalRsDiagnostic),v,f.ecLevel.name,f.dataMask)}
    fun clear() { candidates.clear();diagnostics.clear();failed=null;assessmentCache=null }
    fun remember(matrix: QrMatrix, candidate: RecoveryCandidate,diagnostic:(()->Any?)?=null): Int {
        val id = ++sequence
        candidates[id] = matrix to candidate
        if(diagnostic!=null)diagnostics[id]=diagnostic
        return id
    }
    fun inspect(o: JSObject): Any {
        if(o.str("action")=="resultData" && o.obj("candidate").int("inspectionId")==0)return failedData(o)
        val (matrix, c) = candidates[o.obj("candidate").int("inspectionId")]
            ?: error(localizedText("修復をもう一度実行してください。", "Run recovery again."))
        if(o.str("action")=="cellTechnical"){
            val id=o.obj("candidate").int("inspectionId");val coord=Coord(o.int("x"),o.int("y"))
            require(coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension)
            val after=RepairTraceBuilder.candidateMatrix(matrix,c,assessmentEngine)
            val traces=RepairTraceBuilder.build(matrix,after,c)
            val trace=traces[coord]
            if(trace==null)return mapOf("text" to localizedText("このセルの追加の計算記録はありません。","No additional calculation record is available for this cell."))
            val confidence=if(assessmentCache?.first==id)assessmentCache!!.second else {
                // Upstream compares only candidates from the same sampled input and caps
                // allocations at 16 matrices while retaining the selected candidate.
                val guidance=guidanceCandidates(candidates.values.filter{it.first===matrix}.map{it.second},c)
                val matrices=guidance.candidates.map{RepairTraceBuilder.candidateMatrix(matrix,it,assessmentEngine)}
                RepairGuidance.confidenceMap(matrix,after,matrices,traces,guidance.total).also{assessmentCache=id to it}
            }
            return mapOf("text" to buildString {
                append(localizedText("計算の補足","Calculation details"))
                append(trace.explanationJa(includeBasic=false))
                confidence[coord]?.let{value->append(localizedText(
                    "\n候補の評価（確率ではありません）: ${value.labelJa} (${"%.2f".format(value.score)}) — ${value.detailJa}",
                    "\nCandidate assessment (not a probability): ${value.labelEn} (${"%.2f".format(value.score)}) — ${value.detailEn}"))}
            })
        }
        if(o.str("action")=="codewordData"){
            val raw=o.int("raw");val bit=o.int("bit");require(bit in 0..7)
            val partial=c.extraction.codewords.getOrNull(raw)?:error("Invalid codeword")
            val info=c.repairInfo.getOrNull(raw)
            return mapOf("text" to (codewordCellText(raw,bit,partial.value,partial.unknownBits,
                c.correctedInterleaved.getOrNull(raw),info?.blockIndex,info?.localIndex,info?.rsErrata==true)+
                if(info?.partialEnumerated==true)localizedText("\nこのコード語はbit探索の対象です。","\nThis codeword was included in bit search.") else ""))
        }
        if(o.str("action")=="diagnostic")return diagnostics[o.obj("candidate").int("inspectionId")]?.invoke()?:error("Run recovery again.")
        if (o.str("action") == "compare") {
            val other=candidates[o.obj("other").int("inspectionId")]?.second ?: error("Run recovery again.")
            return mapOf("text" to candidatePairComparisonText(c,other,o.int("firstNumber",1),o.int("secondNumber",2)))
        }
        if (o.str("action") == "tail") {
            val attempt = PostTerminatorAnalysis.forAttempt(c.extraction,c.version,c.ecLevel,c.conventionalRsDiagnostic,c)
            return tailResult(attempt,c.version,c.ecLevel.name,c.dataMask)
        }
        val defaults=RecoveryDataDisplayState.forRecovery(c.isSelectableRecovery)
        val after=o.bool("after",defaults.repairedStage);val unmasked=o.bool("unmasked",defaults.unmasked);val partial=o.bool("partial",defaults.partialInformation)
        val text=buildString {
            append(if(after) localizedText("制約・RS修復後", "After constraints/RS") else localizedText("制約・RS修復前", "Before constraints/RS"))
            append(" / ").append(if(unmasked) localizedText("マスク解除後", "After unmasking") else localizedText("マスク解除前", "Before unmasking"))
            append(localizedText(" / V", " / Version ")).append(c.version).append(" / ECC ${c.ecLevel} / mask ${c.dataMask}")
            append(localizedText("\n? = 不明bit", "\n? = unknown bit"))
            if(partial){
                append(localizedText("\n\n部分情報\n", "\n\nPartial information\n"))
                append(when {
                    !unmasked -> localizedText("マスク解除後に解釈できます。", "Partial information is available after unmasking.")
                    after -> localizedText("この候補はデータ検証済みです。内容はHEX / Binary表示で確認できます。", "This candidate is payload-validated. Its content is available in the Hex / Binary view.")
                    else -> PartialPayloadAnalyzer.fromInterleaved(c.extraction,c.version,c.ecLevel).displayText(io.github.earthdiver.qrrepair.AppLanguageManager.isJapanese)
                })
            }else{
                append(localizedText("\n\nHEX\n", "\n\nHex\n")).append(when {
                    after && unmasked -> CodewordHex.corrected(c.correctedInterleaved)
                    after -> CodewordHex.correctedMasked(c.correctedInterleaved,c.extraction,c.dataMask)
                    unmasked -> CodewordHex.unmasked(c.extraction)
                    else -> CodewordHex.masked(matrix,c.extraction)
                })
                append("\n\nBinary\n").append(when {
                    after && unmasked -> CodewordHex.correctedBinary(c.correctedInterleaved)
                    after -> CodewordHex.correctedMaskedBinary(c.correctedInterleaved,c.extraction,c.dataMask)
                    unmasked -> CodewordHex.binary(c.extraction)
                    else -> CodewordHex.maskedBinary(matrix,c.extraction)
                })
                append(localizedText("\n\n内容\n", "\n\nContent\n")).append(when {
                    !unmasked -> localizedText("マスク解除後に解釈できます。", "Content can be interpreted after unmasking.")
                    after -> CodewordHex.interpretedRecovered(c.dataBytes,c.version,c.text)
                    else -> localizedText("検証済みデータはありません。部分情報表示へ切り替えてください。", "No payload-validated content is available. Switch to Partial information.")
                })
            }
        }
        return mapOf("text" to text)
    }

    private fun failedData(o:JSObject):Any {
        val (matrix,f)=failed?:error(localizedText("表示できるデータがありません。","No data is available."))
        val version=(matrix.dimension-17)/4
        val extraction=CodewordExtractor.extract(matrix,version,f.dataMask)
        val defaults=RecoveryDataDisplayState.forRecovery(false)
        val after=o.bool("after",defaults.repairedStage);val unmasked=o.bool("unmasked",defaults.unmasked)
        val text=buildString {
            append(localizedText("復号失敗・最良試行\n","Decode failed; best attempt\n"))
            append(if(after)localizedText("制約・RS修復後","After constraints/RS") else localizedText("制約・RS修復前","Before constraints/RS"))
            append(" / ").append(if(unmasked)localizedText("マスク解除後","After unmasking") else localizedText("マスク解除前","Before unmasking"))
            append(localizedText(" / V"," / Version ")).append(version).append(" / ECC ${f.ecLevel} / mask ${f.dataMask}")
            append(localizedText("\n? = 不明bit","\n? = unknown bit"))
            if(o.bool("partial",defaults.partialInformation)){
                append(localizedText("\n\n部分情報\n","\n\nPartial information\n"))
                append(when{
                    !unmasked->localizedText("マスク解除後に解釈できます。","Partial information is available after unmasking.")
                    after->localizedText("データ検証済みの修復後候補はありません。修復前を選択してください。","No payload-validated repaired candidate is available. Select Before.")
                    else->PartialPayloadAnalyzer.fromInterleaved(extraction,version,f.ecLevel).displayText(io.github.earthdiver.qrrepair.AppLanguageManager.isJapanese)
                })
            }else{
                append(localizedText("\n\nHEX\n","\n\nHex\n")).append(when{
                    after->localizedText("データ検証済みの修復後候補はありません。元データは「修復前」で確認できます。","No payload-validated repaired candidate is available. Select Before to inspect the original data.")
                    unmasked->CodewordHex.unmasked(extraction)
                    else->CodewordHex.masked(matrix,extraction)
                })
                append("\n\nBinary\n").append(when{after->"-";unmasked->CodewordHex.binary(extraction);else->CodewordHex.maskedBinary(matrix,extraction)})
                append(localizedText("\n\n内容\n","\n\nContent\n")).append(when{
                    !unmasked->localizedText("マスク解除後に解釈できます。","Content can be interpreted after unmasking.")
                    after->localizedText("検証済みデータはありません。","No payload-validated content is available.")
                    else->localizedText("検証済みデータはありません。部分情報表示へ切り替えてください。","No payload-validated content is available. Switch to Partial information.")
                })
            }
        }
        return mapOf("text" to text)
    }
}

/** Mirrors MainActivity.configureNextResultAction for a failed, unselected result. */
internal fun failureNextAction(matrix:QrMatrix,failure:RecoveryFailure?,diagnostic:RecoverySearchDiagnostic?):Any? {
    if(failure==null)return null
    val blocker=diagnostic?.takeIf{it.coverageComplete}?.inputBlocker
    val settings=blocker==RecoveryInputBlocker.FORMAT_LIMIT||blocker==RecoveryInputBlocker.FORMAT_SELECTION
    val recommendation=RepairGuidance.recommendFromBitSpace(matrix,failure.rsBitDiagnostic)
    val text=when {
        settings->localizedText("次に試すこと\n形式情報と、ECC・マスクの指定を確認してください。","Next step\nReview the format information and ECC/mask settings.")
        blocker==RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS->localizedText("次に試すこと\n手動で固定したセルの白黒を確認してください。","Next step\nReview the manually locked cell values.")
        recommendation!=null->localizedText("次に試すこと\nセル (${recommendation.coord.x},${recommendation.coord.y}) の白黒を確認してください（座標は0から）。","Next step\nCheck cell (${recommendation.coord.x},${recommendation.coord.y}) (zero-based coordinates).")
        else->localizedText("次に試すこと\n入力したセルの白黒を元画像と見比べてください。","Next step\nCompare the entered cell values with the source image.")
    }
    return mapOf("text" to text,"detail" to recommendation?.takeIf{!settings&&blocker!=RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS}?.explanationJa(),"target" to if(settings)"settings" else "editor","label" to if(settings)localizedText("形式設定を確認","Review format settings") else localizedText("入力画面で確認","Review in the input editor"))
}

internal fun tailResult(attempt: PostTerminatorAnalysis.Attempt, version:Int, ecc:String, mask:Int): Map<String,Any?> {
    var notice=localizedText("終端後を追加のQRセグメントと仮定する診断用の解析です。読めても、QR全体の規格適合、隠しデータの存在、修復の正しさは確定しません。本文・修復候補は変更しません。", "Diagnostic analysis assumes additional QR segments after the terminator. Readable segments do not prove overall QR validity, intentional hidden data or correct recovery. The main content and recovery candidates are unchanged.")+"\nV$version / ECC $ecc / mask $mask"
    if(attempt.rsCorrectionRejected)notice+="\n\n"+localizedText("通常RSの訂正結果は固定セルまたは誤り上限の条件を満たさないため、解析対象にしていません。","The conventional RS result conflicts with fixed cells or the error limit and is excluded from analysis.")
    return mapOf("notice" to notice,"ecc" to ecc,"mask" to mask,"initial" to attempt.views.indexOfFirst { it.source==PostTerminatorAnalysis.Source.CONVENTIONAL_RS }.coerceAtLeast(0),"views" to attempt.views.map{ view ->
        mapOf("label" to when(view.source){PostTerminatorAnalysis.Source.OBSERVED->localizedText("入力（補正前）","Input (before correction)");PostTerminatorAnalysis.Source.CONVENTIONAL_RS->localizedText("通常RSの訂正後","After conventional RS");else->localizedText("修復候補","Recovery candidate")},"reports" to (listOf(view.report)+view.report.alternatives).mapIndexed{index,report ->
            val starts=report.boundaries.joinToString(" → "){"bit ${it.startBit}"}
            mapOf("label" to localizedText("${index+1}. 開始位置：$starts ／ 読み取れた追加セグメント：${report.groups.size} 組","${index+1}. Start: $starts / Additional segments read: ${report.groups.size} groups"),"text" to tailReportText(view,report),"tail" to report.tail,"tailStart" to report.terminatorBit?.plus(4))
        })
    })
}
