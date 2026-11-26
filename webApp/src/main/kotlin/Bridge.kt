package qrevy.web

import org.teavm.jso.JSExport
import org.teavm.jso.JSObject
import io.github.earthdiver.qrrepair.core.*
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import io.github.earthdiver.qrrepair.AppLanguageManager
import io.github.earthdiver.qrrepair.AppLanguage

internal fun JSObject.num(k: String, d: Double = 0.0) = Interop.number(this,k,d)
internal fun JSObject.int(k: String, d: Int = 0) = num(k,d.toDouble()).toInt()
internal fun JSObject.str(k: String, d: String = "") = Interop.string(this,k,d)
internal fun JSObject.bool(k: String, d: Boolean = false) = Interop.bool(this,k,d)
internal fun JSObject.obj(k: String) = Interop.`object`(this,k)
internal fun json(v: Any?): String = when(v) {
    null -> "null"
    is String -> "\"" + buildString { v.forEach { c -> when(c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if(c.code < 32) append("\\u" + c.code.toString(16).padStart(4,'0')) else append(c)
    } } } + "\""
    is Number, is Boolean -> v.toString()
    is Map<*,*> -> v.entries.joinToString(",","{","}") { json(it.key.toString())+":"+json(it.value) }
    is Iterable<*> -> v.joinToString(",","[","]") { json(it) }
    else -> json(v.toString())
}
internal fun matrixText(m: QrMatrix) = (0 until m.dimension).joinToString("\n") { y -> (0 until m.dimension).joinToString("") { x ->
    val c=m[x,y]; if(c.prioritySearch) "*" else when(c.value) { ModuleValue.ZERO -> "0"; ModuleValue.ONE -> "1"; else -> "?" }
} }
internal fun matrixJson(m: QrMatrix): Map<String,Any?> = mapOf("text" to matrixText(m),"sources" to buildString {
    for(y in 0 until m.dimension) for(x in 0 until m.dimension) append(m[x,y].source.ordinal)
}, "confidences" to (0 until m.dimension*m.dimension).map { m[it%m.dimension,it/m.dimension].confidence }, "margins" to (0 until m.dimension*m.dimension).map { m.imageMargin(it%m.dimension,it/m.dimension) })
internal fun readMatrix(o: JSObject): QrMatrix {
    val m=QrTextMatrixParser.parse(o.str("text"),enforcePriorityLimit=false).matrix
    val sources=o.str("sources")
    require(sources.isEmpty() || sources.length==m.dimension*m.dimension) { "Invalid provenance length" }
    val margins=o.obj("margins");val confidences=o.obj("confidences")
    for(y in 0 until m.dimension) for(x in 0 until m.dimension) {
        val i=y*m.dimension+x
        if(Interop.length(confidences)==m.dimension*m.dimension){val c=Interop.item(confidences,i).toFloat();require(c.isFinite() && c in 0f..1f);m[x,y].confidence=c}
        if(sources.isNotEmpty()) { val s=sources[i].digitToInt(); require(s in ModuleSource.entries.indices); m[x,y].source=ModuleSource.entries[s] }
        if(Interop.length(margins)==m.dimension*m.dimension) {
            val margin=Interop.item(margins,i).toInt()
            if(margin>=0 && m[x,y].source==ModuleSource.IMAGE && m[x,y].value!=ModuleValue.ERASED) m.setImageMargin(x,y,margin)
        }
    }
    return m
}
internal fun quad(o: JSObject): ImageQuad? {
    if(Interop.length(o)!=8) return null
    val p=(0..3).map { PointD(Interop.item(o,it*2),Interop.item(o,it*2+1)) }
    return ImageQuad(p[0],p[1],p[2],p[3])
}
internal fun quadJson(q: ImageQuad) = q.asList().flatMap { listOf(it.x,it.y) }
internal fun geometry(s: PreprocessSettings): GridGeometry {
    val q=requireNotNull(s.manualQuad) { "Set the four corners first" }
    return GridGeometry(s.version,FinderRole.TOP_LEFT,q.topLeft.x,q.topLeft.y,
        (q.topLeft.distanceTo(q.topRight)+q.topLeft.distanceTo(q.bottomLeft))/(2*(16+4*s.version)),0.0,q,GeometryModel.MANUAL_PROJECTIVE).reanchored()
}
internal fun samplingInverted(s:PreprocessSettings)=when(s.confirmedFinderPolarity){FinderPolarity.INVERTED->true;FinderPolarity.NORMAL->false;else->s.polarity==PolarityMode.INVERTED}
internal fun settings(o: JSObject): PreprocessSettings {
    val ecc=o.str("ecc"); val mask=o.int("mask",-1)
    val v=o.int("version",1); require(v in 1..40)
    val fm=ErrorLimitMode.valueOf(o.str("formatLimitMode","AUTOMATIC"))
    val dm=ErrorLimitMode.valueOf(o.str("dataLimitMode","AUTOMATIC"))
    val ops=o.obj("imageOps");var rotation=0f;var perspective: ImageQuad?=null;var legacy: Float?=null;var sourceTransform=false
    for(i in 0 until Interop.length(ops)){val op=Interop.`object`(ops,i.toString());if(op.str("type")=="rotate")rotation=op.num("angle").toFloat() else if(op.str("type")=="rectify")perspective=quad(op.obj("quad")) else if(op.str("type")=="source"){sourceTransform=true;rotation=op.num("angle").toFloat();perspective=quad(op.obj("quad"));legacy=op.num("legacyAngle",Double.NaN).takeIf { it.isFinite() }?.toFloat()}}
    if(!sourceTransform && (perspective!=null || rotation!=0f))legacy=rotation
    return PreprocessSettings(imageAdjustment=ImageAdjustment(o.int("contrast"),o.int("brightness"),o.bool("toneInvert")),rotationDegrees=rotation,perspectiveCorrectionQuad=perspective,legacyImageRotationDegrees=legacy,version=v, lmCorrectionEnabled=o.bool("lmEnabled",true), manualQuad=quad(o.obj("quad")),photographicSource=o.bool("photographicSource",quad(o.obj("quad"))!=null),
        blackMax=o.int("black",96),whiteMin=o.int("white",160),provisionalBlackMax=o.int("provisionalBlack",o.int("black",96)),provisionalWhiteMin=o.int("provisionalWhite",o.int("white",160)),automaticThresholds=o.bool("automatic",true),
        polarity=PolarityMode.valueOf(o.str("polarity","AUTO")),confirmedFinderPolarity=FinderPolarity.valueOf(o.str("confirmedPolarity","UNKNOWN")),logicalGridAssigned=o.bool("assigned",true),visibleFinderRole=when(o.int("visibleFinderRole",-1)){-1->null;0->FinderRole.TOP_LEFT;1->FinderRole.TOP_RIGHT;3->FinderRole.BOTTOM_LEFT;else->error("Invalid unmeasured Finder role")},
        manualFinders=run { val a=o.obj("finders");require(Interop.length(a)%3==0 && Interop.length(a)<=9);(0 until Interop.length(a)/3).associate { i -> val role=when(Interop.item(a,i*3).toInt()){0->FinderRole.TOP_LEFT;1->FinderRole.TOP_RIGHT;3->FinderRole.BOTTOM_LEFT;else->error("Invalid Finder role")};role to PointD(Interop.item(a,i*3+1),Interop.item(a,i*3+2)) } },
        lockManualBits=o.bool("lockManual",true),trustKnownPayloadCount=o.bool("single"),assumeUtf8ByteSegments=o.bool("utf8"),
        formatConstraint=if(ecc.isEmpty() && mask<0) null else QrFormatConstraint(if(ecc.isEmpty()) null else ErrorCorrectionLevel.valueOf(ecc),mask.takeIf { it>=0 }),
        formatMismatchLimitMode=fm,dataEccMismatchLimitMode=dm,formatMismatchLimit=o.int("formatLimit",6),dataEccMismatchLimit=o.int("dataLimit",20),
        partialBitSearchLimit=o.int("partial",12),listDecodingAdditionalErrors=o.int("list",1),knownBitErrorLimit=o.int("known",0),
        prioritySearchLimit=o.int("priorityLimit",4),quietZoneCorrection=o.bool("quietZone",true),distortionCorrection=o.bool("curved"),searchQuadNeighborhood=o.bool("neighborhood"),
        useUnknownColorClusters=o.bool("colorUnknown"),unknownColorClusters=run {
            val a=o.obj("colorClusters");require(Interop.length(a)<=16 && Interop.length(a)%4==0)
            (0 until Interop.length(a)/4).map { i -> UnknownColorCluster(Interop.item(a,i*4).toInt(),Interop.item(a,i*4+1).toInt(),Interop.item(a,i*4+2).toInt(),Interop.item(a,i*4+3).toInt()) }
        }).let { s -> if(s.manualQuad!=null && Interop.length(o.obj("warp"))>0) s.copy(previewGeometry=ImageRecovery.geometryWithWarp(s,o)) else s }
}
internal fun adjustedBitmap(o:JSObject):android.graphics.Bitmap {
    val w=o.int("width");val h=o.int("height");require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=8_000_000)
    val a=o.obj("pixels");require(Interop.length(a)==w*h)
    return android.graphics.Bitmap(w,h,IntArray(w*h){Interop.item(a,it).toInt()})
}
internal fun image(o: JSObject): LumaImage {
    val w=o.int("width");val h=o.int("height");require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=8_000_000)
    val a=o.obj("pixels");require(Interop.length(a)==w*h)
    val rgb=IntArray(w*h) { Interop.item(a,it).toInt() }
    val original=o.obj("originalPixels");val colors=if(Interop.length(original)==w*h)IntArray(w*h){Interop.item(original,it).toInt()} else rgb
    return LumaImage(w,h,IntArray(w*h) { val p=rgb[it]; ((p shr 16 and 255)*299+(p shr 8 and 255)*587+(p and 255)*114+500)/1000 },colors)
}
object Bridge {
    private val engine=RecoveryEngine()
    internal var resultFormatter: (() -> Any?)? = null
    @JvmStatic @JSExport fun version(): String = "QRevy 0.21.1"
    @JvmStatic @JSExport fun execute(o: JSObject): String = try {
        AppLanguageManager.current=if(o.str("language","ja")=="ja") AppLanguage.JAPANESE else AppLanguage.ENGLISH
        if(o.str("action") in listOf("recover","recoverImage","quickRecover","quickRecoverImage")) ResultInspection.clear()
        json(dispatch(o))
    } catch(e: Exception) { json(mapOf("error" to (e.message ?: e.toString()))) }
    private fun dispatch(o: JSObject): Any? = when(o.str("action")) {
        "uiState" -> {
            val m=readMatrix(o.obj("matrix"));val s=settings(o.obj("settings"));val pins=mutableMapOf<Coord,ModuleValue>()
            if(s.lockManualBits)for(y in 0 until m.dimension)for(x in 0 until m.dimension)if(m[x,y].source==ModuleSource.USER || m[x,y].source==ModuleSource.USER_UNLOCKED)pins[Coord(x,y)]=m[x,y].value
            mapOf("single" to engine.hasTrustablePayloadHeader(m,s.formatConstraint,s.effectiveFormatMismatchLimit,pins))
        }
        "inputData" -> { val m=readMatrix(o.obj("matrix"));val s=settings(o.obj("settings"));io.github.earthdiver.qrrepair.buildPreRecoveryDataContent(io.github.earthdiver.qrrepair.PreRecoveryDataInput(m,s.version),s.formatConstraint,o.bool("unmasked",true),o.bool("partial"),strings=io.github.earthdiver.qrrepair.preRecoveryDataStrings()).let { mapOf("text" to it.text,"needsFormat" to it.needsManualFormat) } }
        "localizeResult" -> resultFormatter?.invoke() ?: error("Run recovery again.")
        "resultData", "codewordData", "cellTechnical", "compare", "diagnostic" -> ResultInspection.inspect(o)
        "overlay" -> {
            val m=readMatrix(o.obj("matrix"));val model=io.github.earthdiver.qrrepair.ui.buildCodewordOverlay(m,(m.dimension-17)/4)
            mapOf("cells" to model.cells.entries.associate{(coord,cell)-> "${coord.x},${coord.y}" to mapOf("raw" to cell.entry.rawIndex,"kind" to cell.entry.kind.name,"bit" to cell.bitIndex,"block" to cell.entry.blockIndex,"local" to cell.entry.localIndex,"kindIndex" to cell.entry.kindIndex)})
        }
        "edit", "regions", "validate" -> Editor.run(o)
        "qrrDecode" -> Sessions.decode(o)
        "qrrEncode" -> Sessions.encode(o)
        "parse" -> {
            val m=QrTextMatrixParser.parse(o.str("text"),enforcePriorityLimit=o.bool("enforcePriorityLimit",true)).matrix
            val orientation=QrTextMatrixOrientationEstimator.estimate(m)
            matrixJson(m)+mapOf("quarterTurns" to orientation?.quarterTurns,"assigned" to (orientation!=null))
        }
        "textImageGeometry" -> {
            val m=readMatrix(o.obj("matrix"))
            val orientation=QrTextMatrixOrientationEstimator.estimate(m)
            // Same module centers as native renderTextMatrixSource: 8 pixels,
            // four quiet modules, and centers at (size - 1) / 2.
            val center=35.5;val last=center+(m.dimension-1)*8
            val corners=listOf(PointD(center,center),PointD(last,center),PointD(last,last),PointD(center,last))
            val turns=orientation?.quarterTurns?:0
            mapOf("quarterTurns" to turns,"assigned" to (orientation!=null),
                "quad" to List(4){corners[(it+turns)%4]}.flatMap{listOf(it.x,it.y)},
                "finders" to orientation?.finderCenters.orEmpty().flatMap{(role,cell)->listOf(
                    when(role){FinderRole.TOP_LEFT->0;FinderRole.TOP_RIGHT->1;FinderRole.BOTTOM_LEFT->3},
                    center+cell.x*8,center+cell.y*8)})
        }
        "demo" -> {
            val qr=Encoder.encode(o.str("text","QRevy · Repair with evidence"),ErrorCorrectionLevel.H,mapOf(EncodeHintType.CHARACTER_SET to o.str("encoding","UTF-8")))
            val b=qr.matrix; val m=QrMatrix(b.width)
            for(y in 0 until b.height) for(x in 0 until b.width) m.set(x,y,if(b[x,y].toInt()==1) ModuleValue.ONE else ModuleValue.ZERO,1f,ModuleSource.IMAGE)
            if(o.bool("damage",true)) for(y in 9..13) for(x in 9..16) m.set(x,y,ModuleValue.ERASED,0f,ModuleSource.IMAGE)
            matrixJson(m)
        }
        "symbolExtent" -> SymbolExtent.initialCrop(image(o))?.toList()
        "detect" -> {
            val img=image(o);val e=BrowserInitialGridEstimator.estimate(img,ImageRecovery.boundaries(img))
            val f=FinderDetector(img).detect()
            val thresholds=InitialThresholdEstimator.estimate(adjustedBitmap(o))
            val colorEstimate=InitialThresholdEstimator.estimate(android.graphics.Bitmap(img.width,img.height,requireNotNull(img.rgbPixels)))
            mapOf("black" to thresholds?.blackMax,"white" to thresholds?.whiteMin,"colorClusters" to colorEstimate?.unknownColorClusters?.flatMap { listOf(it.l100,it.a100,it.b100,it.maxDeltaE100) },"version" to e?.version,"quad" to e?.moduleCenterQuad?.let(::quadJson),"assigned" to (e?.autoAssignedFinders?.isNotEmpty()==true),
                "autoAssignedFinders" to e?.autoAssignedFinders.orEmpty().flatMap { (role,point) -> listOf(when(role){FinderRole.TOP_LEFT->0;FinderRole.TOP_RIGHT->1;FinderRole.BOTTOM_LEFT->3},point.x,point.y) },
                "confirmedPolarity" to (e?.confirmedPolarity?.name?:"UNKNOWN"),"finders" to f.map { listOf(it.centerX,it.centerY,it.moduleSize,it.angleRad) },"polarity" to when(e?.confirmedPolarity){FinderPolarity.INVERTED->"INVERTED";FinderPolarity.NORMAL->"NORMAL";else->"AUTO"})
        }
        "finder" -> run finder@{
            val s=settings(o.obj("settings"));val img=image(o);val hint=PointD(o.num("x"),o.num("y"))
            val role=if(o.bool("alignGrid")) when(o.int("role",-1)){0->FinderRole.TOP_LEFT;1->FinderRole.TOP_RIGHT;3->FinderRole.BOTTOM_LEFT;else->error("Invalid Finder role")} else null
            // Only a grid assigned before this tap places the role's Finder; a provisional one does not.
            val physical=quad(o.obj("physicalGrid"))
            val refinement=refineFinderPlacement(img,s.copy(logicalGridAssigned=o.bool("gridAssigned")),hint,role?.takeIf { o.bool("gridAssigned") },physical.takeIf { !o.bool("gridAssigned") });val f=refinement.candidate
            val point=PointD(f?.centerX?:hint.x,f?.centerY?:hint.y)
            val aligned = if (f != null && role != null) {
                try {
                    quadJson(alignQuadToFinders(requireNotNull(s.manualQuad), s.version, s.manualFinders + (role to point)))
                } catch (_: IllegalArgumentException) {
                    return@finder mapOf("confirmed" to false, "quad" to null, "placementRejected" to true)
                }
            } else null
            // A detection from a tap outside the Finder is confirmed in the UI before it is used. Without
            // a detection the tap only names the role, so no grid is aligned to it.
            mapOf("x" to point.x,"y" to point.y,"confirmed" to (f!=null),"confirmedPolarity" to (f?.polarity?.name?:"UNKNOWN"),"quad" to aligned,
                "outcome" to refinement.outcome.name,"needsConfirmation" to refinement.needsConfirmation)
        }
        "sample", "refine" -> {
            val img=image(o);val raw=settings(o.obj("settings"));val estimate=if(o.bool("refreshPolarity"))InitialThresholdEstimator.estimate(adjustedBitmap(o))else null;val s=if(o.bool("refreshPolarity"))raw.copy(confirmedFinderPolarity=run {
                val probe=raw.copy(confirmedFinderPolarity=FinderPolarity.UNKNOWN,polarity=PolarityMode.AUTO)
                val local=raw.manualFinders.values.mapNotNull { refineFinder(img,probe,it)?.polarity }.filter { it!=FinderPolarity.UNKNOWN }.distinct()
                local.singleOrNull()?:BrowserInitialGridEstimator.estimate(img,ImageRecovery.boundaries(img))?.confirmedPolarity?:FinderPolarity.UNKNOWN
            },provisionalBlackMax=estimate?.blackMax?:raw.provisionalBlackMax,provisionalWhiteMin=estimate?.whiteMin?:raw.provisionalWhiteMin)else raw; var g=ImageRecovery.geometryWithWarp(s,o.obj("settings"))
            var refined=false
            if(o.str("action")=="refine") {
                val profile=SamplingProfile(s,samplingInverted(s))
                projectivePatternHypothesis(img,profile,listOf(GeometryHypothesis(emptyList(),g,quickGeometryScore(img,g,profile)))) { false }?.let { g=it.geometry;refined=true }
            }
            val (m,stats)=MatrixSampler(img,SamplingProfile(s,samplingInverted(s))).sample(g)
            mapOf("matrix" to matrixJson(m),"quad" to quadJson(g.moduleCenterQuad()),"grid" to ImageRecovery.grid(g),"refined" to refined,
                "provisionalBlack" to s.provisionalBlackMax,"provisionalWhite" to s.provisionalWhiteMin,"confirmedPolarity" to s.confirmedFinderPolarity.name,"black" to stats.thresholds?.blackMax,"white" to stats.thresholds?.whiteMin)
        }
        "tail" -> if (o.obj("candidate").int("inspectionId") > 0) ResultInspection.inspect(o) else ResultInspection.failedTail() ?: run {
            val m=readMatrix(o.obj("matrix"));val v=(m.dimension-17)/4
            val ranked=FormatSolver.ranked(m).firstOrNull() ?: error("No format available")
            val ecc=o.str("ecc").takeIf { it.isNotEmpty() }?.let { ErrorCorrectionLevel.valueOf(it) } ?: ranked.ecLevel
            val mask=o.int("mask",-1).takeIf { it>=0 } ?: ranked.dataMask
            val extraction=CodewordExtractor.extract(m,v,mask)
            val attempt=PostTerminatorAnalysis.forAttempt(extraction,v,ecc,null)
            tailResult(attempt,v,ecc.name,mask)
        }
        "quickRecover", "quickRecoverImage" -> QuickRecovery.run(o)
        "recoverImage" -> ImageRecovery.recover(o)
        "refineCurve" -> ImageRecovery.refineCurve(o)
        "curve" -> ImageRecovery.curve(o)
        "recover" -> recover(o)
        else -> error("Unknown action")
    }
    private fun recover(o: JSObject): Any {
        val m=readMatrix(o.obj("matrix"));val saved=settings(o.obj("settings"));val assumption=o.str("automaticScanAssumption").takeIf{it.isNotEmpty()}?.let(AutomaticScanAssumption::valueOf)
        val s=if(assumption==null)saved else QuickAutoRecovery.executionSettings(saved,assumption,o.int("automaticScanListExtension",1));require(m.dimension==17+4*s.version)
        if(assumption==null)Editor.validate(m,s)
        require(s.logicalGridAssigned) { "Assign a Finder role before recovery" }
        val pins=mutableMapOf<Coord,ModuleValue>()
        for(y in 0 until m.dimension) for(x in 0 until m.dimension) {
            if((m[x,y].source==ModuleSource.USER || m[x,y].source==ModuleSource.USER_UNLOCKED) && s.lockManualBits){pins[Coord(x,y)]=m[x,y].value;m[x,y].source=ModuleSource.USER}
            if(m[x,y].source==ModuleSource.USER && !s.lockManualBits) m[x,y].source=ModuleSource.USER_UNLOCKED
        }
        var countText=discoveryProgressText(RecoveryDiscoveryCount.Exact(0));var listText="";var priorityText=ordinaryPriorityText((0 until m.dimension).any { y -> (0 until m.dimension).any { x -> m[x,y].prioritySearch } })
        fun progress(){Interop.progress(listOf(listText.ifBlank { io.github.earthdiver.qrrepair.localizedText("復元を準備中","Preparing repair") },countText,priorityText.takeIf { listText.isBlank() }.orEmpty()).filter{it.isNotBlank()}.joinToString("\n"))}
        val autoBudget=assumption?.let{AutomaticScanBudget(stopRequested={Interop.stopped(o.obj("stop"))})}
        val result=engine.recoverWithDiagnostics(m,executionPolicy=if(assumption==AutomaticScanAssumption.EXACT_KNOWN_BITS)RecoveryExecutionPolicy.AUTOMATIC_EXACT else if(assumption!=null)RecoveryExecutionPolicy.AUTOMATIC_EXTENDED else RecoveryExecutionPolicy.DETAILED,allowAutomaticPrioritySearch=assumption!=null&&!s.photographicSource,stopIsTimeLimit={autoBudget?.stopIsTimeLimit()==true},trustKnownPayloadCount=s.trustKnownPayloadCount,
            formatConstraint=s.formatConstraint,maxFormatMismatches=s.effectiveFormatMismatchLimit,
            maxKnownDataEccMismatches=s.effectiveDataEccMismatchLimit,manualEdits=pins,displayLockManualBits=s.lockManualBits,
            partialBitSearchLimit=s.partialBitSearchLimit,listDecodingAdditionalErrors=s.listDecodingAdditionalErrors,
            knownBitErrorLimit=s.knownBitErrorLimit,assumeUtf8ByteSegments=s.assumeUtf8ByteSegments,
            stopRequested={ Interop.stopped(o.obj("stop")) || autoBudget?.stopped()==true },
            discoveryCounter=RecoveryDiscoveryCounter(onChanged={ count -> countText=discoveryProgressText(count);progress() }),
            listDecodingBudget=ListDecodingBudget(onProgress={elapsed,limit -> listText=io.github.earthdiver.qrrepair.localizedText("リスト復号による追加探索: 累計${elapsed/1000} / ${limit/1000}秒","List-decoding fallback: ${elapsed/1000} / ${limit/1000}s cumulative");progress()},onIdle={listText="";progress()}),
            onPriorityProgress={done,total -> priorityText=if(total>0)io.github.earthdiver.qrrepair.localizedText("現在の格子の*探索: $done / ${total}通り","* combinations completed for current grid: $done / $total") else ordinaryPriorityText((0 until m.dimension).any { y -> (0 until m.dimension).any { x -> m[x,y].prioritySearch } });progress()})
        fun formatted():Any {
        ResultInspection.failure(m,result.failure)
        val presenter=io.github.earthdiver.qrrepair.RecoveryDiagnosticPresenter(AppLanguageManager.isJapanese)
        val context=io.github.earthdiver.qrrepair.RecoveryDiagnosticContext(result.candidates.count { it.isSelectableRecovery },!result.searchDiagnostic.coverageComplete,result.searchDiagnostic.candidatesOmitted,result.searchDiagnostic,null,selectedCandidateNumber=1,discoveredCandidates=result.discoveredCandidates,searchSummaryShownSeparately=true)
        val diagnosticReport=result.candidates.firstOrNull { it.isSelectableRecovery }?.let{presenter.candidateReport(it,null,context)}?:result.failure?.let{presenter.failureReport(it,null,context)}
        val diagnostic=diagnosticReport?.fullText().orEmpty()
        return mapOf("candidates" to result.candidates.filter { it.isSelectableRecovery }.mapIndexed { i,c -> candidate(m,c) { val report=presenter.candidateReport(c,null,context.copy(selectedCandidateNumber=i+1));mapOf("summary" to resultSummary(result.candidates.filter { it.isSelectableRecovery },presenter.searchResultDescription(context),c),"diagnosticReport" to diagnosticJson(report),"diagnostic" to report.fullText()) } },
            "nextAction" to if(result.candidates.none{it.isSelectableRecovery})failureNextAction(m,result.failure,result.searchDiagnostic) else null,"retryPromptEligible" to retryPromptEligible(s,result.searchDiagnostic,Interop.stopped(o.obj("stop"))),"summary" to resultSummary(result.candidates.filter { it.isSelectableRecovery },presenter.searchResultDescription(context)),"diagnosticReport" to diagnosticJson(diagnosticReport),"tailAvailable" to tailAvailable(result.candidates.any { it.isSelectableRecovery },result.failure,result.searchDiagnostic),"diagnostic" to diagnostic,"failure" to unverifiedBody(m,result.failure),"failureFormat" to result.failure?.let { mapOf("dataDisplay" to recoveryDataDisplayDefaults(false),"version" to (m.dimension-17)/4,"ecc" to it.ecLevel.name,"mask" to it.dataMask) },"retryAvailable" to result.searchDiagnostic.singleSegmentRetryAvailable,"formatAttempts" to result.formatAttempts)        }
        resultFormatter=::formatted
        return formatted()
    }

    internal fun candidate(m: QrMatrix,c: RecoveryCandidate,diagnostic:(()->Any?)?=null): Map<String,Any?> {
        val repaired=RepairTraceBuilder.candidateMatrix(m,c,engine)
        val traces=RepairTraceBuilder.build(m,repaired,c)
        val codewordMap=IntArray(m.dimension*m.dimension){-1}
        c.extraction.codewords.forEachIndexed{raw,word->word.coordinates.forEachIndexed{bit,coord->codewordMap[coord.y*m.dimension+coord.x]=raw*8+bit}}
        val formatCells=FormatSolver.copies(m.dimension).flatten().associate{ (x,y)->
            val group=FormatCellGroup.at(m.dimension,x,y)!!
            "$x,$y" to mapOf("title" to group.cellText(),"detail" to formatGroupDetailText(group,m,repaired),"formatRole" to group.role.name,"formatDetail" to formatGroupDetailText(group,m,repaired))
        }
        return mapOf("dataDisplay" to recoveryDataDisplayDefaults(c.isSelectableRecovery),"inspectionId" to ResultInspection.remember(m,c,diagnostic),"content" to c.candidateContentText(),"text" to c.text,"version" to c.version,"ecc" to c.ecLevel.toString(),"mask" to c.dataMask,
            "matrix" to matrixJson(repaired),"bytes" to c.dataBytes.map { it.toInt() and 255 },
            "codewordMap" to codewordMap.toList(),"formatCells" to formatCells,"codewords" to c.correctedInterleaved.toList(),"formatMismatches" to c.formatMismatches,
            "mismatches" to c.knownBitMismatchesAfterCorrection,"compared" to c.knownBitsCompared,
            "formatCompared" to c.formatMatches + c.formatMismatches,
            "formatAgreement" to inputAgreementText(c.formatMatches + c.formatMismatches, c.formatMismatches, true),
            "dataAgreement" to inputAgreementText(c.knownBitsCompared, c.knownBitMismatchesAfterCorrection, false),
            "filled" to c.originalErasedCodewords,"corrected" to c.correctedErrataSymbols,"margin" to c.minRsMargin,
            "contentPreview" to resultContentPresentation(c),
            "methods" to c.usedMethodsText(),"explanation" to c.resultExplanationText(includeSearchTermination=false),
            "trace" to traces.entries.associate { (coord,t) -> "${coord.x},${coord.y}" to mapOf("reason" to t.primaryReason.name,"bitConstraintSolved" to t.bitConstraintSolved,"title" to t.titleJa(),"detail" to t.explanationJa(),"codeword" to t.codewordIndex,"bit" to t.bitIndex,"formatRole" to FormatCellGroup.at(m.dimension,coord.x,coord.y)?.role?.name,"formatDetail" to FormatCellGroup.at(m.dimension,coord.x,coord.y)?.let { formatGroupDetailText(it,m,repaired) }) })
    }
}

internal fun unverifiedBody(matrix:QrMatrix,failure:RecoveryFailure?):String? {
    val f=failure?:return null
    if(f.stage!=RecoveryFailureStage.PAYLOAD_VALIDATION||f.reason!=null||f.conventionalRsDiagnostic?.settingsAccepted!=true)return null
    val version=(matrix.dimension-17)/4
    val extraction=CodewordExtractor.extract(matrix,version,f.dataMask)
    val bytes=PostTerminatorAnalysis.conventionalData(QrBlockInterleaver.deinterleave(extraction,version,f.ecLevel),f.conventionalRsDiagnostic)?:return null
    val body=QrContentPreviewParser.parseUnverifiedPrefix(bytes,version,f.ecLevel)?:return null
    return io.github.earthdiver.qrrepair.localizedText("RS訂正後の本文（未検証の候補）","Body after RS correction (unverified candidate)")+"\n"+body.resultContentText(body.recoveredText)+if(body.byteSegments.isEmpty())"" else "\n\n"+CodewordHex.payloadByteSegments(body.byteSegments)
}
