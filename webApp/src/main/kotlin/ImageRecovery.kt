package qrevy.web
import io.github.earthdiver.qrrepair.core.*
import org.teavm.jso.JSObject

internal object ImageRecovery {
    /** Same native reader and verified Java fallback as Android. */
    fun boundaries(o: JSObject, img: LumaImage, cancellation: RecoveryCancellation = RecoveryCancellation.NONE): List<ImageQuad> {
        val w=o.int("width");val h=o.int("height");val pixels=o.obj("pixels")
        require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=8_000_000 && Interop.length(pixels)==w*h)
        val points=Interop.nativeScan(o,pixels,w,h).obj("boundaries")
        require(Interop.length(points)%8==0 && Interop.length(points)<=128)
        if(Interop.length(points)==0)return ZxingJavaGridDetector.detect(img, cancellation)
        return (0 until Interop.length(points)/8).map { i ->
            fun p(j:Int):PointD {
                val x=Interop.item(points,i*8+j*2);val y=Interop.item(points,i*8+j*2+1)
                require(x.isFinite() && y.isFinite())
                return PointD(x,y)
            }
            ImageQuad(p(0),p(1),p(2),p(3))
        }
    }

    /** Try native reading first, then the same Java fallback as Android. */
    fun ordinaryRead(o: JSObject, allowFallback: () -> Boolean = { true }): Map<String,Any?>? {
        val w=o.int("width");val h=o.int("height");val pixels=o.obj("pixels")
        require(w in 1..4096 && h in 1..4096 && w.toLong()*h<=8_000_000 && Interop.length(pixels)==w*h)
        val native=Interop.nativeScan(o,pixels,w,h).obj("decoded")
        if(native.str("reader").isNotEmpty()) {
            val bytes=native.obj("bytes")
            return mapOf("text" to native.str("text"),"payloadBytes" to (0 until Interop.length(bytes)).map{Interop.item(bytes,it).toInt()},"reader" to native.str("reader"))
        }
        if(!allowFallback())return null
        val argb=IntArray(w*h){Interop.item(pixels,it).toInt()}
        val decoded=StandardQrDecoder.decode(w,h,argb)?:return null
        return mapOf("text" to decoded.text,"dataCodewords" to decoded.rawBytes?.map{it.toInt() and 255},"reader" to "ZXing Java")
    }

    fun geometryWithWarp(s: PreprocessSettings,o: JSObject): GridGeometry {
        val w=o.obj("warp");val size=Interop.length(w);val g=geometry(if(size>0)quad(o.obj("previewQuad"))?.let { s.copy(manualQuad=it) }?:s else s)
        if(size==0)return g
        require(size in 15..320 && size%5==0)
        val landmarks=(0 until size/5).map { i -> WarpLandmark(PointD(Interop.item(w,i*5),Interop.item(w,i*5+1)),PointD(Interop.item(w,i*5+2),Interop.item(w,i*5+3)),Interop.item(w,i*5+4)) }
        return g.copy(model=GeometryModel.THIN_PLATE_SPLINE,warp=ThinPlateWarp(landmarks)).reanchored()
    }
    fun grid(g: GridGeometry)=(0 until g.dimension*g.dimension).map { i -> g.moduleToImage((i%g.dimension).toDouble(),(i/g.dimension).toDouble()).let { listOf(it.x,it.y) } }
    fun warp(g: GridGeometry)=g.warp?.landmarks?.flatMap { listOf(it.position.x,it.position.y,it.displacement.x,it.displacement.y,it.smoothing) }
    fun curve(o: JSObject): Any {
        val img=image(o);val s=settings(o.obj("settings"));val g=geometry(s)
        val initialProfile=SamplingProfile(s,samplingInverted(s));val profile=if(s.automaticThresholds)initialProfile.copy(settings=s.copy(blackMax=initialProfile.provisionalThresholds.blackMax,whiteMin=initialProfile.provisionalThresholds.whiteMin))else initialProfile;val detector=CurvedGridDetector(img,profile,maxContourWorkingBytes=16L*1024*1024)
        var candidates=detector.candidates(g).distinct().sortedBy { quickGeometryScore(img,it,profile) }.sortedBy { geometryRankingUnknownCount(img,it,profile){Interop.stopped(o.obj("stop"))} }
        if(s.lmCorrectionEnabled){val first=candidates.firstOrNull()?:g;jointlyRefinedGeometry(img,first,profile){Interop.stopped(o.obj("stop"))}?.let { candidates=(listOf(it)+candidates.drop(1)).distinct() }}
        return mapOf("memoryLimited" to detector.contourMemoryLimited,"candidates" to candidates.mapIndexed { index,it -> curveJson(img,it,profile)+( "refined" to (index==0&&s.lmCorrectionEnabled)) })
    }
    private fun curveJson(img:LumaImage,g:GridGeometry,p:SamplingProfile):Map<String,Any?> = mapOf("quad" to quadJson(g.imageQuad ?: g.moduleCenterQuad()),"warp" to warp(g),"grid" to grid(g),"unknownCount" to geometryRankingUnknownCount(img,g,p){false},"score" to quickGeometryScore(img,g,p))
    fun refineCurve(o:JSObject):Any {
        val img=image(o);val s=settings(o.obj("settings"));val g=geometryWithWarp(s,o.obj("settings"));val p=SamplingProfile(s,samplingInverted(s))
        val refined=jointlyRefinedGeometry(img,g,p){Interop.stopped(o.obj("stop"))}?:g
        return curveJson(img,refined,p)+("refined" to true)
    }
    fun recover(o: JSObject): Any {
        val img=image(o);val input=readMatrix(o.obj("matrix"));val base=settings(o.obj("settings"));require(base.logicalGridAssigned)
        Editor.validate(input,base)
        val edits=mutableMapOf<Coord,ModuleValue>();val soft=mutableSetOf<Coord>();val priority=mutableSetOf<Coord>()
        for(y in 0 until input.dimension) for(x in 0 until input.dimension) {
            val c=input[x,y];if(c.source.isUserEdit){edits[Coord(x,y)]=c.value;if(c.source==ModuleSource.USER_SOFT)soft+=Coord(x,y)}
            if(c.prioritySearch)priority+=Coord(x,y)
        }
        val g=geometryWithWarp(base,o.obj("settings"))
        val s=base.copy(preRecoveryEdits=edits,preRecoverySoftEdits=soft,preRecoveryPrioritySearch=priority,
            previewGeometry=g.takeIf { base.distortionCorrection || it.warp!=null },lmGeometry=quad(o.obj("settings").obj("lmAppliedQuad"))?.takeIf { o.obj("settings").bool("lmEnabled") }?.let { geometry(base.copy(manualQuad=it)) },manualFinders=base.manualFinders)
        var countText=discoveryProgressText(RecoveryDiscoveryCount.Exact(0));var listText="";var priorityText=ordinaryPriorityText(priority.isNotEmpty());var phaseText=io.github.earthdiver.qrrepair.localizedText("復元を準備中","Preparing repair")
        fun progress(){Interop.progress(listOf(listText.ifBlank { phaseText },countText,priorityText.takeIf { listText.isBlank() }.orEmpty()).filter{it.isNotBlank()}.joinToString("\n"))}
        val retention=RecoveryRetentionBudget()
        val accumulator=RecoveryResultAccumulator(retention)
        val discovery=RecoveryDiscoveryCounter(onChanged={count->countText=discoveryProgressText(count);progress()})
        val listBudget=ListDecodingBudget(onProgress={elapsed,limit->listText=io.github.earthdiver.qrrepair.localizedText("リスト復号による追加探索: 累計${elapsed/1000} / ${limit/1000}秒","List-decoding fallback: ${elapsed/1000} / ${limit/1000}s cumulative");progress()},onIdle={listText="";progress()})
        val knownBudget=KnownBitErrorBudget()
        for(profile in s.variants()) {
            if(Interop.stopped(o.obj("stop"))){accumulator.markIncomplete(SearchIncompleteReason.USER_INTERRUPTED);break}
            AutoRecoveryEngine().run(img,profile=profile,retentionBudget=retention,resultAccumulator=accumulator,
                knownBitErrorBudget=knownBudget,
            stopRequested={Interop.stopped(o.obj("stop"))},onProgress={done,total,message -> phaseText="$done / $total · $message";progress()},
            discoveryCounter=discovery,
            onPriorityProgress={done,total->priorityText=if(total>0)io.github.earthdiver.qrrepair.localizedText("現在の格子の*探索: $done / ${total}通り","* combinations completed for current grid: $done / $total") else ordinaryPriorityText(priority.isNotEmpty());progress()},
            listDecodingBudget=listBudget)
        }
        val r=accumulator.result().copy(discoveredCandidates=discovery.snapshot,interrupted=Interop.stopped(o.obj("stop")))
        return formatResult(r,input,s,o)
    }
    fun formatResult(r:AutoRecoveryResult,input:QrMatrix,s:PreprocessSettings,o:JSObject,automatic:QuickAutoRecoveryResult?=null):Any {
        fun formatted():Any {
            val best=r.bestAttempt
            ResultInspection.failure(best?.matrix?:input,r.bestFailure)
            val presenter=io.github.earthdiver.qrrepair.RecoveryDiagnosticPresenter(io.github.earthdiver.qrrepair.AppLanguageManager.isJapanese)
            val selectedIndex=r.entries.indexOfFirst{it.candidate===automatic?.selected}.coerceAtLeast(0)
            val selectedEntry=r.entries.getOrNull(selectedIndex)
            val context=io.github.earthdiver.qrrepair.RecoveryDiagnosticContext(r.entries.size,r.searchDiagnostic?.coverageComplete!=true,r.candidatesTruncated,r.searchDiagnostic,selectedEntry,selectedCandidateNumber=selectedIndex+1,discoveredCandidates=r.discoveredCandidates,aggregatesTrials=true,trialCount=r.attemptedGeometries,searchSummaryShownSeparately=true)
            val diagnosticReport=selectedEntry?.let{presenter.candidateReport(it.candidate,it.attempt.hypothesis.geometry,context)}?:r.bestFailure?.let{presenter.failureReport(it,best?.hypothesis?.geometry,context)}
            val diagnostic=diagnosticReport?.fullText().orEmpty()
            return mapOf("candidates" to r.entries.mapIndexed { i,entry -> Bridge.candidate(entry.attempt.matrix,entry.candidate) { val selectedContext=context.copy(selectedEntry=entry,selectedCandidateNumber=i+1);val report=presenter.candidateReport(entry.candidate,entry.attempt.hypothesis.geometry,selectedContext);val selectedSearch=presenter.searchResultDescription(selectedContext);mapOf("retryAvailable" to (r.searchDiagnostic?.singleSegmentRetryAvailable==true),"summary" to resultSummary(r.entries.map { it.candidate },selectedSearch,entry.candidate),"diagnosticReport" to diagnosticJson(report),"diagnostic" to report.fullText()) }+mapOf("input" to matrixJson(entry.attempt.matrix),"quad" to quadJson(entry.attempt.hypothesis.geometry.moduleCenterQuad()),"grid" to grid(entry.attempt.hypothesis.geometry)) + if(automatic!=null)QuickRecovery.historyState(s,entry.attempt,entry.profile)else emptyMap() },
                "nextAction" to if(r.entries.isEmpty())failureNextAction(best?.matrix?:input,r.bestFailure,r.searchDiagnostic) else null,"retryAvailable" to (r.searchDiagnostic?.singleSegmentRetryAvailable==true),"retryPromptEligible" to retryPromptEligible(s,r.searchDiagnostic,Interop.stopped(o.obj("stop"))),"summary" to resultSummary(r.entries.map { it.candidate },presenter.searchResultDescription(context),selectedEntry?.candidate),"diagnosticReport" to diagnosticJson(diagnosticReport),"tailAvailable" to tailAvailable(r.entries.isNotEmpty(),r.bestFailure,r.searchDiagnostic),"diagnostic" to diagnostic,"failure" to unverifiedBody(best?.matrix?:input,r.bestFailure),"failureFormat" to r.bestFailure?.let{mapOf("dataDisplay" to recoveryDataDisplayDefaults(false),"version" to ((best?.matrix?:input).dimension-17)/4,"ecc" to it.ecLevel.name,"mask" to it.dataMask)},"formatAttempts" to r.attemptedGeometries) + if(automatic==null)emptyMap()else mapOf("automatic" to mapOf("selectedIndex" to r.entries.indexOfFirst{it.candidate===automatic.selected},"failureReason" to automatic.failureReason?.name,"report" to automatic.diagnosticReport,"timeLimitsEnabled" to !o.bool("unlimited")),"input" to matrixJson(best?.matrix?:input),"settings" to settingsJson(s)) + (best?.let { QuickRecovery.historyState(s,it,r.profile) }?:emptyMap())
        }
        Bridge.resultFormatter=::formatted
        return formatted()
    }
}
