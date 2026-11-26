package qrevy.web

import io.github.earthdiver.qrrepair.core.*
import org.teavm.jso.JSObject

/** Browser entry points use the same bounded automatic trials as the Android importer. */
internal object QuickRecovery {
    fun run(o: JSObject): Any {
        val trace=if(o.bool("diagnostics"))AutomaticScanTrace()else null
        val stopped={Interop.stopped(o.obj("stop"))}
        val unlimited=o.bool("unlimited")
        val budget=if(unlimited)AutomaticScanBudget(limitMillis=10_000,stopRequested=stopped,timeLimitsEnabled=false,trace=trace,sliceMultiplier=3)
            else if(o.num("timeLimitMillis")>0)AutomaticScanBudget(o.num("timeLimitMillis").toLong().coerceIn(1,10_000),stopped,trace=trace,sliceMultiplier=3)
            else AutomaticScanBudget.forImport(o.bool("firstRun"),stopped,trace=trace)
        val text=o.str("action")=="quickRecover"
        var s=settings(o.obj("settings"))
        val img:LumaImage
        val initial:QrMatrix
        if(text){
            initial=readMatrix(o.obj("matrix"))
            val n=initial.dimension;val size=(n+8)*8
            img=LumaImage(size,size,IntArray(size*size){i->
                val x=i%size/8-4;val y=i/size/8-4
                if(x !in 0 until n||y !in 0 until n)255 else when(initial[x,y].value){ModuleValue.ZERO->255;ModuleValue.ONE->0;else->128}
            })
            val first=35.5;val last=first+(n-1)*8
            val priority=initial.prioritySearchCoordinates().map{Coord(it.first,it.second)}.toSet()
            s=s.copy(version=(n-17)/4,photographicSource=false,automaticThresholds=false,
                manualQuad=ImageQuad(PointD(first,first),PointD(last,first),PointD(last,last),PointD(first,last)),
                logicalGridAssigned=true,blackMax=96,whiteMin=160,polarity=PolarityMode.NORMAL,
                confirmedFinderPolarity=FinderPolarity.NORMAL,preRecoveryEdits=priority.associateWith{ModuleValue.ERASED},preRecoverySoftEdits=emptySet(),
                preRecoveryPrioritySearch=priority)
        }else{
            img=image(o)
            if(s.manualQuad==null){
                val estimate=try{BrowserInitialGridEstimator.estimate(img,ImageRecovery.boundaries(img),RecoveryCancellation{budget.stopped()},trace?.gridObserver())}catch(_:RecoveryCancelledException){null}
                val tones=InitialThresholdEstimator.estimate(adjustedBitmap(o))
                val colors=InitialThresholdEstimator.estimate(android.graphics.Bitmap(img.width,img.height,requireNotNull(img.rgbPixels)))
                s=s.copy(version=estimate?.version?:s.version,manualQuad=estimate?.moduleCenterQuad,
                    logicalGridAssigned=estimate?.autoAssignedFinders?.isNotEmpty()==true,
                    manualFinders=estimate?.autoAssignedFinders.orEmpty(),confirmedFinderPolarity=estimate?.confirmedPolarity?:FinderPolarity.UNKNOWN,
                    provisionalBlackMax=tones?.blackMax?:s.provisionalBlackMax,provisionalWhiteMin=tones?.whiteMin?:s.provisionalWhiteMin,
                    blackMax=tones?.blackMax?:s.blackMax,whiteMin=tones?.whiteMin?:s.whiteMin,
                    unknownColorClusters=colors?.unknownColorClusters.orEmpty(),photographicSource=true)
            }
            initial=s.manualQuad?.let{MatrixSampler(img,SamplingProfile(s,samplingInverted(s))).sample(geometry(s)).first}?:QrMatrix(17+4*s.version)
        }
        val result=if(text)QuickAutoRecovery().runTextMatrix(img,s,budget)
            else runPreparedImage(img,s,budget,separateRepairBudget=!unlimited&&o.num("timeLimitMillis")<=0)
        return ImageRecovery.formatResult(result.recovery,initial,s,o,result)
    }

    /** Preserve a missing grid's preparation timeout when repair receives a fresh allowance. */
    internal fun runPreparedImage(image:LumaImage,settings:PreprocessSettings,
        preparationBudget:AutomaticScanBudget,separateRepairBudget:Boolean):QuickAutoRecoveryResult {
        val repairBudget=if(separateRepairBudget)preparationBudget.repairAfterPreparation()else preparationBudget
        val result=QuickAutoRecovery().run(image,settings,repairBudget)
        return if(result.selected==null&&settings.manualQuad==null&&preparationBudget.stopReason()=="TOTAL_TIME_LIMIT"&&
            result.failureReason in setOf(null,QuickAutoFailureReason.NO_INITIAL_GRID,QuickAutoFailureReason.TIME_LIMIT)) {
            val trace=preparationBudget.trace
            trace?.event("IMPORT_FINISH","failure=TIME_LIMIT stage=INITIAL_GRID")
            result.copy(failureReason=QuickAutoFailureReason.TIME_LIMIT,diagnosticReport=trace?.report())
        }else result
    }

    fun historyState(saved:PreprocessSettings,attempt:GeometryAttempt,profile:SamplingProfile?):Map<String,Any?> {
        val g=attempt.hypothesis.geometry
        val actual=profile?.settings?:saved
        val assumption=when {
            actual.assumeUtf8ByteSegments&&actual.trustKnownPayloadCount->AutomaticScanAssumption.UTF8_SINGLE_SEGMENT
            actual.assumeUtf8ByteSegments->AutomaticScanAssumption.UTF8
            actual.trustKnownPayloadCount->AutomaticScanAssumption.SINGLE_SEGMENT
            actual.effectiveDataEccMismatchLimit==0->AutomaticScanAssumption.EXACT_KNOWN_BITS
            else->AutomaticScanAssumption.NONE
        }
        val selected=saved.copy(version=g.version,manualQuad=g.moduleCenterQuad(),logicalGridAssigned=true,
            previewGeometry=g.takeIf{it.warp!=null},distortionCorrection=saved.distortionCorrection||g.warp!=null,lmGeometry=null,
            blackMax=attempt.samplingStats.thresholds?.blackMax?:actual.blackMax,
            whiteMin=attempt.samplingStats.thresholds?.whiteMin?:actual.whiteMin,
            confirmedFinderPolarity=if(profile?.inverted==true)FinderPolarity.INVERTED else FinderPolarity.NORMAL)
        return mapOf("settings" to (settingsJson(selected)+mapOf("grid" to ImageRecovery.grid(g))),"input" to matrixJson(attempt.matrix),
            "automaticScanAssumption" to assumption.name,"automaticScanListExtension" to if(actual.listDecodingAdditionalErrors==2)2 else 1,
            "samplingInverted" to (profile?.inverted?:false))
    }
}
