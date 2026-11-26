package qrevy.web
import io.github.earthdiver.qrrepair.core.*
internal fun diagnosticJson(report:RecoveryDiagnosticReport?):Any? = report?.let { mapOf(
    "scope" to it.scope,"overview" to it.overview,
    "sections" to it.sections.map { section -> mapOf("id" to section.stage.name,"title" to section.stage.title(),"label" to section.stage.label(),"summary" to section.summary,
        "groups" to section.groups.map { group -> mapOf("id" to group.id.name,"title" to group.title,"summary" to group.summary,"text" to group.text,"collapsible" to group.collapsible) }) }) }
internal fun tailAvailable(selected:Boolean,failure:RecoveryFailure?,search:RecoverySearchDiagnostic?):Boolean = !selected && failure?.stage==RecoveryFailureStage.PAYLOAD_VALIDATION && failure.reason==null && !(search?.inputBlocker==RecoveryInputBlocker.ZERO_MISMATCH && search.coverageComplete)

internal fun resultSummary(candidates:List<RecoveryCandidate>,search:String,selected:RecoveryCandidate?=candidates.firstOrNull()):String {
    // Count every result the candidate picker offers, including valid QR data that is not text.
    val decoded=candidates.filter { it.isSelectableRecovery }
    val distinct=decoded.map { it.dataBytes.toList() }.distinct().size
    return when {
        selected!=null && !selected.payloadParsed && selected.isSelectableRecovery -> search+io.github.earthdiver.qrrepair.localizedText("\nQRのデータ形式を確認しました\n文字として表示できないデータです。","\nThe QR data format was verified\nThe data cannot be displayed as text.")
        decoded.size==1 -> search
        decoded.size>1 && distinct==1 -> search+io.github.earthdiver.qrrepair.localizedText("\n表示中の読み取り結果のデータコード語列はすべて同じです。","\nCandidates for the displayed reading all have the same data codeword sequence.")
        decoded.size>1 -> search+io.github.earthdiver.qrrepair.localizedText("\n表示中の読み取り結果のデータコード語列は${distinct}種類です。","\nCandidates for the displayed reading have $distinct distinct data codeword sequences.")
        selected!=null && QrContentPreviewParser.parse(selected)?.qrStructureValid==true -> search+"\n"+io.github.earthdiver.qrrepair.localizedText("QRのデータ形式を確認しました\n文字として表示できないデータです。","The QR data format was verified\nThe data cannot be displayed as text.")
        selected!=null -> search+"\n"+io.github.earthdiver.qrrepair.localizedText("誤り訂正の条件を満たす候補はありますが、内容を読み取れませんでした","Error-correction candidates exist, but their content could not be read")
        else -> search
    }
}

internal fun retryPromptEligible(settings:PreprocessSettings,diagnostic:RecoverySearchDiagnostic?,interrupted:Boolean):Boolean =
    !settings.trustKnownPayloadCount && !interrupted && diagnostic!=null && diagnostic.coverageComplete && !diagnostic.decodedCandidate && diagnostic.singleSegmentRetryAvailable && SearchIncompleteReason.USER_INTERRUPTED !in diagnostic.incompleteReasons && SearchIncompleteReason.INTERNAL_ERROR !in diagnostic.incompleteReasons
internal fun discoveryProgressText(count:RecoveryDiscoveryCount):String = io.github.earthdiver.qrrepair.discoveryCountText(count,io.github.earthdiver.qrrepair.AppLanguageManager.isJapanese)+(if(count is RecoveryDiscoveryCount.AtLeast) io.github.earthdiver.qrrepair.localizedText("\n件数の集計上限に達しました。探索は継続します。","\nThe counting limit was reached. Search can continue.") else "")
internal fun ordinaryPriorityText(hasPriority:Boolean):String=if(hasPriority)io.github.earthdiver.qrrepair.localizedText("通常処理を実行中（必要な場合に*探索を行います）","Running ordinary recovery; * search runs when needed")else ""
