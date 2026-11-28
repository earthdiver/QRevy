package qrevy.web
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.*
import org.teavm.jso.JSObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

internal fun settingsJson(s: PreprocessSettings) = mapOf(
    "previewGeometryVersion" to s.previewGeometry?.let { 1 },
    "previewQuad" to s.previewGeometry?.imageQuad?.let(::quadJson),"warp" to s.previewGeometry?.warp?.landmarks?.flatMap { listOf(it.position.x,it.position.y,it.displacement.x,it.displacement.y,it.smoothing) },
    "finders" to s.manualFinders.flatMap { (r,p) -> listOf(when(r){FinderRole.TOP_LEFT->0;FinderRole.TOP_RIGHT->1;FinderRole.BOTTOM_LEFT->3},p.x,p.y) },"priorityLimit" to s.prioritySearchLimit,"quietZone" to s.quietZoneCorrection,
    "lmEnabled" to s.lmCorrectionEnabled,"lmBaseQuad" to s.manualQuad?.let(::quadJson),"lmAppliedQuad" to s.lmGeometry?.moduleCenterQuad()?.let(::quadJson),"version" to s.version,"quad" to (s.lmGeometry?.moduleCenterQuad()?:s.manualQuad)?.let(::quadJson),"assigned" to s.logicalGridAssigned,"visibleFinderRole" to s.visibleFinderRole?.let { when(it){FinderRole.TOP_LEFT->0;FinderRole.TOP_RIGHT->1;FinderRole.BOTTOM_LEFT->3} },
    "black" to s.blackMax,"white" to s.whiteMin,"provisionalBlack" to s.provisionalBlackMax,"provisionalWhite" to s.provisionalWhiteMin,"automatic" to s.automaticThresholds,
    "polarity" to s.polarity.name,"confirmedPolarity" to s.confirmedFinderPolarity.name,"ecc" to (s.formatConstraint?.ecLevel?.name ?: ""),"mask" to (s.formatConstraint?.dataMask ?: -1),
    "lockManual" to s.lockManualBits,"single" to s.trustKnownPayloadCount,"utf8" to s.assumeUtf8ByteSegments,
    "formatLimitMode" to (if(s.formatMismatchLimitMode==ErrorLimitMode.MANUAL && s.formatMismatchLimit==null) "UNLIMITED" else s.formatMismatchLimitMode.name),"dataLimitMode" to (if(s.dataEccMismatchLimitMode==ErrorLimitMode.MANUAL && s.dataEccMismatchLimit==null) "UNLIMITED" else s.dataEccMismatchLimitMode.name),
    "formatLimit" to (s.formatMismatchLimit ?: 6),"dataLimit" to (s.dataEccMismatchLimit ?: 20),
    "partial" to s.partialBitSearchLimit,"list" to s.listDecodingAdditionalErrors,"known" to s.knownBitErrorLimit,
    "colorUnknown" to s.useUnknownColorClusters,"colorClusters" to s.unknownColorClusters.flatMap { listOf(it.l100,it.a100,it.b100,it.maxDeltaE100) },"curved" to s.distortionCorrection,"neighborhood" to s.searchQuadNeighborhood,
    "brightness" to s.imageAdjustment.brightness,"contrast" to s.imageAdjustment.contrast,"toneInvert" to s.imageAdjustment.inverted,"photographicSource" to s.photographicSource,"orientation" to 0,
    "imageOps" to listOf(mapOf("type" to "source", "angle" to s.rotationDegrees, "quad" to s.perspectiveCorrectionQuad?.let(::quadJson), "version" to s.version, "legacyAngle" to s.legacyImageRotationDegrees) + (if(s.legacyImageRotationDegrees!=null) mapOf("legacyRaster" to "android") else emptyMap())))

internal object Sessions {
    fun decode(o: JSObject): Any {
        val bytes=Base64.getDecoder().decode(o.str("raw"));require(bytes.size<=RecoverySessionCodec.MAX_SESSION_PAYLOAD_BYTES)
        val version=o.int("version");require(version in 23..RecoverySessionCodec.CURRENT_VERSION)
        val p=RecoverySessionPayloadCodec.read(bytes,version)
        return mapOf("id" to p.id,"updated" to p.updatedAtMs.toDouble(),"name" to "QRR", "cursor" to p.historyCursor,
            "history" to p.historyEntries.mapIndexed { index,h ->
                val s=h.snapshot;val originalMatrix=s.workingMatrix ?: s.sourceMatrix
                val m=originalMatrix ?: QrMatrix(17+4*(s.preprocessSettings?.version ?: s.geometry?.version ?: 1))
                val settings=s.preprocessSettings ?: PreprocessSettings(version=(m.dimension-17)/4)
                val observations=m.copy()
                if(originalMatrix==null)io.github.earthdiver.qrrepair.PreRecoveryCellEditState(settings.preRecoveryEdits,settings.preRecoveryPrioritySearch,settings.prioritySearchLimit,initialSoftEdits=settings.preRecoverySoftEdits).applyTo(m,false)
                mapOf("timestamp" to h.timestampMs.toDouble(),"kind" to h.kind.name,"detail" to h.detail,"selection" to s.selectedQr?.let { mapOf("version" to it.version,"ecc" to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.entries[it.ecLevel].name,"mask" to it.mask,"codewords" to it.codewordBytes().map { b -> b.toInt() and 255 }) },"imageOnly" to (originalMatrix==null),"oneTimeSingleSegment" to s.oneTimeSingleSegment,"automaticScanAssumption" to s.automaticScanAssumption?.name,"automaticScanListExtension" to s.automaticScanListExtension,"samplingInverted" to s.samplingInverted,"qrrIndex" to index,"label" to h.label,"matrix" to matrixJson(m),"source" to matrixJson(s.sourceMatrix ?: observations),"settings" to settingsJson(settings))
            },"image" to null,"imageHash" to null,
            "qrrRaw" to o.str("raw"),"qrrVersion" to version)
    }
    fun encode(o: JSObject): Any {
        val raw=o.str("history");val list=Interop.parse(raw);val size=Interop.length(list);require(size in 1..40)
        val original=o.str("qrrRaw").takeIf { it.isNotEmpty() }?.let {
            val version=o.int("qrrVersion");require(version in 23..RecoverySessionCodec.CURRENT_VERSION)
            RecoverySessionPayloadCodec.read(Base64.getDecoder().decode(it),version)
        }
        val entries=(0 until size).map { i ->
            val h=Interop.`object`(list,i.toString());val m=readMatrix(h.obj("matrix"));val source=readMatrix(h.obj("source"))
            val inputSettings=settings(h.obj("settings"))
            val manual=mutableMapOf<Coord,ModuleValue>();val soft=mutableSetOf<Coord>();val priority=mutableSetOf<Coord>()
            for(y in 0 until m.dimension)for(x in 0 until m.dimension){val c=m[x,y];val coord=Coord(x,y);if(c.source.isUserEdit||c.prioritySearch){manual[coord]=c.value;if(c.source==ModuleSource.USER_SOFT&&!c.prioritySearch)soft+=coord};if(c.prioritySearch)priority+=coord}
            val ui=h.obj("settings");val lm=quad(ui.obj("lmAppliedQuad"))?.takeIf { ui.bool("lmEnabled") && inputSettings.logicalQuad!=null }?.let { geometry(inputSettings.copy(manualQuad=it)) }
            val edits=inputSettings.copy(lmGeometry=lm,manualQuad=if(lm!=null)quad(ui.obj("lmBaseQuad"))?:inputSettings.manualQuad else inputSettings.manualQuad,preRecoveryEdits=manual,preRecoverySoftEdits=soft,preRecoveryPrioritySearch=priority)
            require(edits.version==(m.dimension-17)/4)
            val prior=original?.historyEntries?.getOrNull(h.int("qrrIndex",-1))
            val timestamp=h.num("timestamp",prior?.timestampMs?.toDouble()?:System.currentTimeMillis().toDouble()).toLong()
            val kind=runCatching{HistoryEventKind.valueOf(h.str("kind",prior?.kind?.name?:"USER_EDIT"))}.getOrDefault(HistoryEventKind.USER_EDIT)
            val label=h.str("label").take(400);val detail=h.str("detail",prior?.detail?:"").take(1600)
            val base=prior?.snapshot?.preprocessSettings
            // Edited PWA snapshots have a fixed grid, including neighborhood-search trials.
            val requiresLockedGrid=priority.isNotEmpty() || edits.searchQuadNeighborhood
            val s=if(base==null) edits else base.copy(lockCorners=requiresLockedGrid || base.lockCorners,lockVersion=requiresLockedGrid || base.lockVersion,lmCorrectionEnabled=edits.lmCorrectionEnabled,lmGeometry=edits.lmGeometry,previewGeometry=edits.previewGeometry,preRecoveryEdits=edits.preRecoveryEdits,preRecoverySoftEdits=edits.preRecoverySoftEdits,preRecoveryPrioritySearch=edits.preRecoveryPrioritySearch,manualFinders=edits.manualFinders,visibleFinderRole=edits.visibleFinderRole,imageAdjustment=edits.imageAdjustment,rotationDegrees=edits.rotationDegrees,perspectiveCorrectionQuad=edits.perspectiveCorrectionQuad,legacyImageRotationDegrees=edits.legacyImageRotationDegrees,prioritySearchLimit=edits.prioritySearchLimit,quietZoneCorrection=edits.quietZoneCorrection,version=edits.version,manualQuad=edits.manualQuad,
                logicalGridAssigned=edits.logicalGridAssigned,blackMax=edits.blackMax,whiteMin=edits.whiteMin,provisionalBlackMax=edits.provisionalBlackMax,provisionalWhiteMin=edits.provisionalWhiteMin,
                automaticThresholds=edits.automaticThresholds,polarity=edits.polarity,confirmedFinderPolarity=edits.confirmedFinderPolarity,formatConstraint=edits.formatConstraint,
                lockManualBits=edits.lockManualBits,trustKnownPayloadCount=edits.trustKnownPayloadCount,
                assumeUtf8ByteSegments=edits.assumeUtf8ByteSegments,formatMismatchLimitMode=edits.formatMismatchLimitMode,
                dataEccMismatchLimitMode=edits.dataEccMismatchLimitMode,formatMismatchLimit=edits.formatMismatchLimit,
                dataEccMismatchLimit=edits.dataEccMismatchLimit,partialBitSearchLimit=edits.partialBitSearchLimit,
                listDecodingAdditionalErrors=edits.listDecodingAdditionalErrors,knownBitErrorLimit=edits.knownBitErrorLimit,
                distortionCorrection=edits.distortionCorrection,searchQuadNeighborhood=edits.searchQuadNeighborhood,
                useUnknownColorClusters=edits.useUnknownColorClusters,unknownColorClusters=edits.unknownColorClusters,photographicSource=edits.photographicSource)
            val selected=h.obj("selection").let { selection ->
                if(selection.int("version")==0) null else {
                    val words=selection.obj("codewords");val data=ByteArray(Interop.length(words)) { index ->
                        val value=Interop.item(words,index);require(value in 0.0..255.0 && value==value.toInt().toDouble());value.toInt().toByte()
                    }
                    RecoverySelectionIdentity(selection.int("version"),com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.valueOf(selection.str("ecc")).ordinal,selection.int("mask"),data)
                }
            }
            val auto=h.str("automaticScanAssumption").takeIf{it.isNotEmpty()}?.let(AutomaticScanAssumption::valueOf)
            val autoList=h.int("automaticScanListExtension",1)
            val g=s.manualQuad?.let { ImageRecovery.geometryWithWarp(s,h.obj("settings")) }
            if(prior!=null && h.bool("imageOnly")) prior.copy(timestampMs=timestamp,kind=kind,label=label,detail=detail,snapshot=prior.snapshot.copy(preprocessSettings=s,oneTimeSingleSegment=h.bool("oneTimeSingleSegment"),automaticScanAssumption=auto,automaticScanListExtension=autoList,samplingInverted=h.bool("samplingInverted")))
            else if(prior!=null && matrixJson(prior.snapshot.workingMatrix ?: prior.snapshot.sourceMatrix ?: m)==matrixJson(m) &&
                matrixJson(prior.snapshot.sourceMatrix ?: m)==matrixJson(source) && prior.snapshot.oneTimeSingleSegment==h.bool("oneTimeSingleSegment") &&
                prior.snapshot.automaticScanAssumption==auto && prior.snapshot.automaticScanListExtension==autoList && base!=null && settingsJson(base)==settingsJson(s) && prior.snapshot.selectedQr==selected) prior.copy(timestampMs=timestamp,kind=kind,label=label,detail=detail)
            else RecoveryHistoryEntry(timestampMs=timestamp,kind=kind,label=label,detail=detail,
                snapshot=RecoverySnapshot(geometry=g,sourceMatrix=source,workingMatrix=m,recoveryIndex=if(selected==null) -1 else 0,selectedQr=selected,preprocessSettings=s,oneTimeSingleSegment=h.bool("oneTimeSingleSegment"),automaticScanAssumption=auto,automaticScanListExtension=autoList,samplingInverted=h.bool("samplingInverted")))
        }
        val p=RecoverySessionCodec.fitHistoryToPayloadLimit(RecoverySessionCodec.Payload(o.str("id"),System.currentTimeMillis(),UiMode.ANALYSIS,entries,o.int("cursor")))
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use { RecoverySessionPayloadCodec.write(it,p) }
        return mapOf("raw" to Base64.getEncoder().encodeToString(bytes.toByteArray()),"version" to RecoverySessionCodec.CURRENT_VERSION)
    }
}
