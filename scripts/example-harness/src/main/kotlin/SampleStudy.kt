package evaluation
import com.google.gson.*
import com.google.zxing.*
import com.google.zxing.common.*
import com.google.zxing.qrcode.detector.Detector
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private val gson=GsonBuilder().serializeNulls().create()
private class Raw(val im:LumaImage):LuminanceSource(im.width,im.height) {
 override fun getRow(y:Int,row:ByteArray?):ByteArray=(row?.takeIf{it.size>=width}?:ByteArray(width)).also{for(x in 0 until width)it[x]=im.pixels[y*width+x].toByte()}
 override fun getMatrix()=ByteArray(width*height){im.pixels[it].toByte()}
}
private data class Loaded(val image:LumaImage,val argb:IntArray)
private fun load(path:String):Loaded {
 val b=ImageIO.read(File(path))?:error("Unsupported image")
 val rgb=b.getRGB(0,0,b.width,b.height,null,0,b.width)
 val gray=IntArray(rgb.size){val p=rgb[it];(299*((p ushr 16)and 255)+587*((p ushr 8)and 255)+114*(p and 255)+500)/1000}
 return Loaded(LumaImage(b.width,b.height,gray),rgb)
}
private fun boundary(im:LumaImage):List<ImageQuad> {
 val d=try{Detector(HybridBinarizer(Raw(im)).blackMatrix).detect(mapOf(DecodeHintType.TRY_HARDER to true))}catch(_:ReaderException){return emptyList()}
 val bl=d.points[0];val tl=d.points[1];val tr=d.points[2];val a=d.points.getOrNull(3);val n=d.bits.width
 val brx=a?.x?:(tr.x-tl.x+bl.x);val bry=a?.y?:(tr.y-tl.y+bl.y)
 val h=PerspectiveTransform.quadrilateralToQuadrilateral(3.5f,3.5f,n-3.5f,3.5f,if(a==null)n-3.5f else n-6.5f,if(a==null)n-3.5f else n-6.5f,3.5f,n-3.5f,tl.x,tl.y,tr.x,tr.y,brx,bry,bl.x,bl.y)
 val q=floatArrayOf(0f,0f,n.toFloat(),0f,n.toFloat(),n.toFloat(),0f,n.toFloat());h.transformPoints(q)
 val p=(0..3).map{PointD(q[it*2].toDouble(),q[it*2+1].toDouble())}
 return listOf(ImageQuad(p[0],p[1],p[2],p[3]))
}
private fun JsonObject.int(k:String,default:Int)=get(k)?.takeUnless{it.isJsonNull}?.asInt?:default
private fun JsonObject.bool(k:String,default:Boolean)=get(k)?.takeUnless{it.isJsonNull}?.asBoolean?:default
private fun quad(j:JsonArray):ImageQuad {val p=j.map{PointD(it.asJsonArray[0].asDouble,it.asJsonArray[1].asDouble)};return ImageQuad(p[0],p[1],p[2],p[3])}
private fun writeMatrix(m:QrMatrix,file:File) {
 file.parentFile.mkdirs()
 file.writeText((0 until m.dimension).joinToString("\n"){y->(0 until m.dimension).map{x->when(m[x,y].value){ModuleValue.ONE->'1';ModuleValue.ZERO->'0';else->'?'}}.joinToString("")}+"\n")
 val b=BufferedImage(m.dimension,m.dimension,BufferedImage.TYPE_INT_RGB)
 for(y in 0 until m.dimension)for(x in 0 until m.dimension)b.setRGB(x,y,when(m[x,y].value){ModuleValue.ONE->0;ModuleValue.ZERO->0xffffff;else->0x8888ff})
 ImageIO.write(b,"png",File(file.parentFile,file.nameWithoutExtension+".png"))
}
private fun prepare(input:File,out:File) {
 out.mkdirs();val cases=JsonParser.parseString(input.readText()).asJsonArray
 File(out,"inspection.jsonl").bufferedWriter().use{w->
 for(item in cases){val c=item.asJsonObject;val id=c["id"].asString;val path=c["input"].asString
  if(c["kind"].asString=="matrix") {val p=QrTextMatrixParser.parse(File(path).readText());writeMatrix(p.matrix,File(out,"$id.txt"));w.appendLine(gson.toJson(mapOf("id" to id,"version" to p.version,"blackMax" to 96,"whiteMin" to 160)));w.flush();continue}
  val loaded=load(path);val im=loaded.image;val start=System.nanoTime()
  val external=c.getAsJsonArray("externalQuads")?.map{quad(it.asJsonArray)}?:boundary(im);val e=InitialGridEstimator.estimate(im,external)
  val t=InitialThresholdEstimator.estimate(android.graphics.Bitmap(im.width,im.height,loaded.argb))
  val settings=PreprocessSettings(blackMax=t?.blackMax?:96,whiteMin=t?.whiteMin?:160,automaticThresholds=true).withInitialGrid(e).withInitialErrorLimits()
  if(e!=null){val q=e.moduleCenterQuad;val n=17+4*e.version;val pitch=(q.topLeft.distanceTo(q.topRight)+q.topLeft.distanceTo(q.bottomLeft))/(2*(n-1))
   val g=GridGeometry(e.version,FinderRole.TOP_LEFT,0.0,0.0,pitch,0.0,q,GeometryModel.MANUAL_PROJECTIVE).reanchored()
   writeMatrix(MatrixSampler(im,SamplingProfile(settings,e.confirmedPolarity==FinderPolarity.INVERTED)).sample(g).first,File(out,"$id.txt"))
   val b=ImageIO.read(File(path));val gg=b.createGraphics();gg.color=java.awt.Color.RED;gg.stroke=java.awt.BasicStroke(2f)
   q.asList().forEachIndexed{i,p->val next=q.asList()[(i+1)%4];gg.drawLine(p.x.toInt(),p.y.toInt(),next.x.toInt(),next.y.toInt());gg.drawString(i.toString(),p.x.toInt(),p.y.toInt())};gg.dispose();ImageIO.write(b,"png",File(out,"$id-overlay.png"))
  }
  w.appendLine(gson.toJson(mapOf("id" to id,"width" to im.width,"height" to im.height,"version" to e?.version,"quad" to e?.moduleCenterQuad?.asList()?.map{listOf(it.x,it.y)},"source" to e?.source,"inverted" to (e?.confirmedPolarity==FinderPolarity.INVERTED),"logicalAssigned" to (e?.autoAssignedFinders?.isNotEmpty()==true),"blackMax" to settings.blackMax,"whiteMin" to settings.whiteMin,"ms" to (System.nanoTime()-start)/1e6,"boundaryAdapter" to (c.get("boundaryAdapter")?.asString?:"desktop ZXing Java geometry only; Android JNI not evaluated"))));w.flush();println("prepared $id")
 }
 }
}
private fun candidate(c:RecoveryCandidate):Map<String,Any?> = mapOf("version" to c.version,"ecc" to c.ecLevel.toString(),"mask" to c.dataMask,"text" to c.text,"score" to c.score,"codewords" to c.correctedInterleaved.toList(),"dataBytes" to c.dataBytes.map{it.toInt()and 255},"knownMismatches" to c.knownBitMismatchesAfterCorrection,"knownCompared" to c.knownBitsCompared,"partialGuessedBits" to c.partialGuessedBits,"constraintFilledBits" to c.constraintFilledBits,"singleSegmentStatus" to c.singleSegmentStatus,"constraintForcedByUserSetting" to c.constraintForcedByUserSetting,"priorityAssignments" to c.priorityAssignments.map{(p,v)->listOf(p.x,p.y,if(v==ModuleValue.ONE) "1" else "0")},"conventionalRs" to c.conventionalRsDiagnostic,"actualBlockMethods" to c.actualBlockMethods,"searchDiagnostic" to c.searchDiagnostic)
private fun run(input:File,out:File) {
 out.parentFile.mkdirs()
 out.bufferedWriter().use{w->input.forEachLine{line->val req=JsonParser.parseString(line).asJsonObject;val start=System.nanoTime();var checks=0
  val ms=req.int("budgetMs",1000);val maxChecks=req.int("maxChecks",50000)
  val result=linkedMapOf<String,Any?>("id" to req["id"].asString,"request" to req,"status" to "completed")
  try {
   val cancellation=RecoveryCancellation{++checks>maxChecks || (System.nanoTime()-start)/1000000>ms}
   val matrixOnly=req["kind"].asString=="matrix"
   val m=if(matrixOnly)QrTextMatrixParser.parse(File(req["input"].asString).readText()).matrix else null
   val v=if(m!=null)(m.dimension-17)/4 else req.int("version",1)
   val mode=ErrorLimitMode.valueOf(req["limit"].asString)
   val black=req.int("blackMax",96);val white=req.int("whiteMin",160)
   val editMap=linkedMapOf<Coord,ModuleValue>();val soft=linkedSetOf<Coord>()
   req.getAsJsonArray("edits")?.forEach{j->val a=j.asJsonArray;val p=Coord(a[0].asInt,a[1].asInt);editMap[p]=when(a[2].asString){"1"->ModuleValue.ONE;"0"->ModuleValue.ZERO;else->ModuleValue.ERASED};if(a.size()>3 && a[3].asBoolean)soft+=p}
   val budget=RecoveryRetentionBudget(maxCandidates=256)
   val listBudget=if(req.bool("listDecoding",true)) ListDecodingBudget() else ListDecodingBudget(0)
   val trust=req.bool("single",false)
   if(m!=null){editMap.forEach{(p,value)->m.set(p.x,p.y,value,1f,if(value==ModuleValue.ERASED || p in soft)ModuleSource.USER_SOFT else ModuleSource.USER)}
    val o=RecoveryEngine().recoverWithDiagnostics(m,trustKnownPayloadCount=trust,maxFormatMismatches=6,maxKnownDataEccMismatches=ErrorLimitPolicy.data(v,mode,req.get("dataCap")?.takeUnless{it.isJsonNull}?.asInt),manualEdits=editMap.filter{it.value!=ModuleValue.ERASED && it.key !in soft},cancellation=cancellation,retentionBudget=budget,partialBitSearchLimit=req.int("partialBits",12),listDecodingBudget=listBudget)
    result["candidates"]=o.candidates.sortedWith(recoveryCandidateComparator).map(::candidate);result["searchDiagnostic"]=o.searchDiagnostic;result["failure"]=o.failure;result["formatAttempts"]=o.formatAttempts
   }else if(!req.has("quad") || req["quad"].isJsonNull){result["status"]="no_geometry";result["candidates"]=emptyList<Any>()}else{
    val priority=req.getAsJsonArray("priority")?.map{j->val a=j.asJsonArray;Coord(a[0].asInt,a[1].asInt)}?.toSet().orEmpty()
    priority.forEach{editMap[it]=ModuleValue.ERASED}
    val loaded=load(req["input"].asString);val settings=PreprocessSettings(version=v,manualQuad=quad(req.getAsJsonArray("quad")),logicalGridAssigned=req.bool("logicalAssigned",true),blackMax=black,whiteMin=white,automaticThresholds=req.bool("automaticThresholds",true),searchQuadNeighborhood=req.bool("fine",false),trustKnownPayloadCount=trust,dataEccMismatchLimitMode=mode,dataEccMismatchLimit=req.get("dataCap")?.takeUnless{it.isJsonNull}?.asInt,formatMismatchLimitMode=ErrorLimitMode.AUTOMATIC,preRecoveryEdits=editMap,preRecoverySoftEdits=soft,partialBitSearchLimit=req.int("partialBits",12))
    val profile=SamplingProfile(settings.copy(preRecoveryPrioritySearch=priority),req.bool("inverted",false))
    val o=AutoRecoveryEngine().run(loaded.image,profile=profile,retentionBudget=budget,cancellation=cancellation,listDecodingBudget=listBudget)
    val cs=o.entries.map{it.candidate}.distinctBy{Triple(it.version,it.ecLevel to it.dataMask,it.correctedInterleaved.toList())}.sortedWith(recoveryCandidateComparator)
    result["candidates"]=cs.mapIndexed{index,candidate->
     val entry=o.entries.first{it.candidate==candidate}
     val relative="candidate-matrices/"+req["id"].asString+"-"+req["action"].asString+"-"+index+".txt"
     writeMatrix(entry.attempt.matrix,File(out.parentFile,relative))
     candidate(candidate)+mapOf("inputMatrix" to relative,"inputQuad" to entry.attempt.hypothesis.geometry.imageQuad?.asList()?.map{p->listOf(p.x,p.y)})
    };result["searchDiagnostic"]=o.searchDiagnostic;result["failure"]=o.bestFailure;result["attemptedGeometries"]=o.attemptedGeometries
    o.bestAttempt?.let{writeMatrix(it.matrix,File(out.parentFile,"matrices/"+req["id"].asString+"-"+req["action"].asString+".txt"));result["sampledQuad"]=it.hypothesis.geometry.imageQuad?.asList()?.map{p->listOf(p.x,p.y)}}
   }
  }catch(_:RecoveryCancelledException){result["status"]="budget_exhausted";result["candidates"]=emptyList<Any>()}catch(e:Exception){result["status"]="error";result["error"]=e.toString();result["candidates"]=emptyList<Any>()}
  result["elapsedMs"]=(System.nanoTime()-start)/1e6;result["cancellationChecks"]=checks;w.appendLine(gson.toJson(result));w.flush();println(req["id"].asString+" "+req["action"].asString+" "+result["status"])
 }}
}
fun main(args:Array<String>){require(args.size==3);when(args[0]){"prepare"->prepare(File(args[1]),File(args[2]));"run"->run(File(args[1]),File(args[2]));else->error("Unknown mode")}}
