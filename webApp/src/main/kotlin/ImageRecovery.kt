package qrevy.web
import io.github.earthdiver.qrrepair.core.*
import org.teavm.jso.JSObject

internal object ImageRecovery {
    /** ZXing Java detector replaces the Android JNI boundary detector; the image verification gate remains upstream. */
    fun boundaries(img: LumaImage): List<ImageQuad> = listOf(false,true).flatMap { inverted -> boundary(img,inverted) }
    private fun boundary(img: LumaImage, inverted: Boolean): List<ImageQuad> {
        val bits = com.google.zxing.common.BitMatrix(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width)
            if ((img.luma(x,y) <= img.threshold) != inverted) bits.set(x,y)
        fun affine(version: Int, points: List<PointD>): GridGeometry {
            fun finder(i: Int) = FinderCandidate(points[i].x, points[i].y, 1.0, 0.0, 1.0, 1)
            return GridGeometry.fromAffine(version, FinderRole.TOP_LEFT, mapOf(
                FinderRole.TOP_LEFT to finder(1), FinderRole.TOP_RIGHT to finder(2), FinderRole.BOTTOM_LEFT to finder(0)))
        }
        fun outer(g: GridGeometry): ImageQuad {
            val last = g.dimension - .5
            return ImageQuad(g.moduleToImage(-.5,-.5), g.moduleToImage(last,-.5),
                g.moduleToImage(last,last), g.moduleToImage(-.5,last))
        }
        val detected = try {
            com.google.zxing.qrcode.detector.Detector(bits).detect(mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true))
        } catch (_: Exception) { null }
        if (detected != null) {
            val points = detected.points.map { PointD(it.x.toDouble(), it.y.toDouble()) }
            val n = detected.bits.width
            val version = (n - 17) / 4
            if (version in 1..40 && points.size >= 3) {
                val base = affine(version, points)
                val projected = if (points.size >= 4) try { outer(GridGeometry.fromFourCorrespondences(base,
                    listOf(PointD(3.0,3.0), PointD(n-4.0,3.0), PointD(3.0,n-4.0), PointD(n-7.0,n-7.0)),
                    listOf(points[1],points[2],points[0],points[3]))) } catch (_: IllegalArgumentException) { null } else null
                // An apparent alignment pattern inside damage must not override verified Finders.
                if (projected != null && InitialGridEstimator.fromBoundary(img, projected) != null) return listOf(projected)
                val simple = outer(base)
                val verified = InitialGridEstimator.fromBoundary(img, simple)
                if (verified != null && (points.size == 3 || verified.autoAssignedFinders.size == 3)) return listOf(simple)
            }
        }
        // ZXing's full detector can throw while sampling an invalid alignment fit.
        // Recover the observed centers independently; every proposed version still
        // has to pass the upstream Finder, separator and timing-pattern checks.
        val info = try { com.google.zxing.qrcode.detector.FinderGeometry.find(bits) }
            catch (_: Exception) { return emptyList() }
        val finders = listOf(info.bottomLeft, info.topLeft, info.topRight)
        val points = finders.map { PointD(it.x.toDouble(), it.y.toDouble()) }
        val pitch = finders.map { it.estimatedModuleSize.toDouble() }.average()
        if (!pitch.isFinite() || pitch <= 0) return emptyList()
        val dimension = (points[1].distanceTo(points[0]) + points[1].distanceTo(points[2])) / (2 * pitch) + 7
        val version = kotlin.math.round((dimension - 17) / 4).toInt()
        return (version-1..version+1).filter { it in 1..40 }.map { outer(affine(it, points)) }
            .filter { InitialGridEstimator.fromBoundary(img, it)?.autoAssignedFinders?.size == 3 }
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
