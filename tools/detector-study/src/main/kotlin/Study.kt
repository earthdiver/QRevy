package study

import boofcv.factory.fiducial.FactoryFiducial
import boofcv.struct.image.GrayU8
import boofcv.alg.fiducial.qrcode.QrCode
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.zxing.LuminanceSource
import com.google.zxing.DecodeHintType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.detector.Detector
import io.github.earthdiver.qrrepair.core.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

val gson = Gson()
data class Candidate(val quad: List<List<Double>>, val n: Int? = null, val stage: String = "detected")
data class Detection(val id: String, val engine: String, val ms: Double, val times: List<Double>, val candidates: List<Candidate>)
class RawLuma(val image: LumaImage): LuminanceSource(image.width,image.height) {
    override fun getRow(y: Int,row: ByteArray?): ByteArray = (row?.takeIf { it.size>=width } ?: ByteArray(width)).also {
        for(x in 0 until width) it[x]=image.pixels[y*width+x].toByte()
    }
    override fun getMatrix()=ByteArray(width*height) { image.pixels[it].toByte() }
}
fun load(file:File):LumaImage {
    val im=ImageIO.read(file)
    require(im.raster.numBands==1) { "Study accepts synthetic grayscale PNG only" }
    return LumaImage(im.width,im.height,im.raster.getSamples(0,0,im.width,im.height,0,null as IntArray?))
}
fun periodic(image:LumaImage):List<Candidate> {
    val est=GridPeriodicityEstimator.estimate(image) ?: return emptyList()
    val n=17+4*est.version
    val h=Homography.fromCorrespondences(listOf(PointD(.5,.5),PointD(n-.5,.5),PointD(n-.5,n-.5),PointD(.5,n-.5)),est.moduleCenterQuad.asList())
    return listOf(Candidate(listOf(0.0 to 0.0,n.toDouble() to 0.0,n.toDouble() to n.toDouble(),0.0 to n.toDouble()).map{(x,y)->h.map(x,y).let{listOf(it.x,it.y)}},n))
}
fun zxing(image:LumaImage):List<Candidate> {
    val result=try { Detector(HybridBinarizer(RawLuma(image)).blackMatrix).detect(mapOf(DecodeHintType.TRY_HARDER to true)) }
        catch(_:com.google.zxing.ReaderException){ return emptyList() }
    val points=result.points; val bl=points[0];val tl=points[1];val tr=points[2];val n=result.bits.width
    val align=points.getOrNull(3);val brx=align?.x ?: (tr.x-tl.x+bl.x);val bry=align?.y ?: (tr.y-tl.y+bl.y)
    val logicalBr=if(align==null) n-3.5f else n-6.5f
    val h=PerspectiveTransform.quadrilateralToQuadrilateral(3.5f,3.5f,n-3.5f,3.5f,logicalBr,logicalBr,3.5f,n-3.5f,
        tl.x,tl.y,tr.x,tr.y,brx,bry,bl.x,bl.y)
    val q=floatArrayOf(0f,0f,n.toFloat(),0f,n.toFloat(),n.toFloat(),0f,n.toFloat()); h.transformPoints(q)
    return listOf(Candidate((0..3).map{listOf(q[2*it].toDouble(),q[2*it+1].toDouble())},n))
}
fun boofDetector():(LumaImage)->List<Candidate> {
    val detector=FactoryFiducial.qrcode(null,GrayU8::class.java)
    return { image ->
        val gray=GrayU8(image.width,image.height)
        for(i in image.pixels.indices) gray.data[i]=image.pixels[i].toByte()
        detector.process(gray)
        (detector.detections+detector.failures).mapNotNull { qr ->
            val n=if(qr.version in 1..40) 17+4*qr.version else null
            val hasH=n!=null && qr.failureCause !in listOf(QrCode.Failure.FORMAT,QrCode.Failure.VERSION,QrCode.Failure.ALIGNMENT)
            val q=if(hasH) {
                val h=qr.Hinv
                listOf(0.0 to 0.0,n.toDouble() to 0.0,n.toDouble() to n.toDouble(),0.0 to n.toDouble()).map { (x,y)->
                    val z=h.a31*x+h.a32*y+h.a33
                    listOf((h.a11*x+h.a12*y+h.a13)/z,(h.a21*x+h.a22*y+h.a23)/z)
                }
            } else (0..3).map { qr.bounds.get(it).let { p-> listOf(p.x,p.y) } }
            if(q.flatten().all{it.isFinite()}) Candidate(q,n,qr.failureCause.toString()) else null
        }
    }
}
fun detect(engine:String,corpus:File,out:File) {
    val detector=when(engine){"baseline"->::periodic;"zxing_java"->::zxing;"boofcv"->boofDetector();else->error(engine)}
    val manifest=JsonParser.parseString(File(corpus,"manifest.json").readText()).asJsonArray
    val clean=load(File(corpus,"v1-s0-clean.png"));repeat(10){detector(clean)}
    out.bufferedWriter().use { writer ->
        manifest.forEachIndexed { idx, obj ->
            val c=obj.asJsonObject;val image=load(File(corpus,c["path"].asString))
            val times=mutableListOf<Double>();var candidates=listOf<Candidate>()
            repeat(3){ val t=System.nanoTime();candidates=detector(image);times+=(System.nanoTime()-t)/1e6 }
            writer.appendLine(gson.toJson(Detection(c["id"].asString,engine,times.sorted()[1],times,candidates)));writer.flush()
            if(idx%40==0) println("$engine $idx/${manifest.size()}")
        }
    }
}
fun main(args:Array<String>) {
    if (args.firstOrNull() == "inspect-image") {
        require(args.size in 2..3) { "inspect-image IMAGE [BOUNDARIES_JSON] (explicit opt-in)" }
        val im = load(File(args[1]))
        val finders = FinderDetector(im).detect()
        val boundaries = if (args.size == 3) gson.fromJson(args[2], Array<Candidate>::class.java).map { c ->
            val p = c.quad.map { PointD(it[0], it[1]) }
            ImageQuad(p[0], p[1], p[2], p[3])
        } else emptyList()
        println(gson.toJson(mapOf("width" to im.width, "height" to im.height,
            "periodicity" to GridPeriodicityEstimator.estimate(im),
            "initial" to InitialGridEstimator.estimate(im, boundaries), "finders" to finders,
            "pairHypotheses" to GeometryFitter.twoFinderHypotheses(finders).size)))
        return
    }
    require(args.size==4) { "detect ENGINE CORPUS OUTPUT" }
    when(args[0]) { "initial-grid"->initialGrid(File(args[1]),File(args[2]),File(args[3])); "detect"->detect(args[1],File(args[2]),File(args[3])); "recover"->recover(File(args[1]),File(args[2]),File(args[3])); "recover-fixed"->recover(File(args[1]),File(args[2]),File(args[3]),false); else->error(args[0]) }
}

// All engines use the same downstream sampler and repair engine. Ground truth is never read here.
data class Ranked(val candidate:Candidate,val geometry:GridGeometry,val score:Double)
fun ranked(image:LumaImage,candidates:List<Candidate>):List<Ranked> {
    val out=mutableListOf<Ranked>()
    for(c in candidates.take(16)) {
        val versions=c.n?.let { if(it in 21..177 && (it-17)%4==0) listOf((it-17)/4) else emptyList() } ?: (1..40).toList()
        for(v in versions) for(rotation in 0..3) runCatching {
            val n=17+4*v
            val q=(0..3).map{ c.quad[(it+rotation)%4].let{p->PointD(p[0],p[1])} }
            val h=Homography.fromCorrespondences(listOf(PointD(0.0,0.0),PointD(n.toDouble(),0.0),PointD(n.toDouble(),n.toDouble()),PointD(0.0,n.toDouble())),q)
            val centers=listOf(.5 to .5,n-.5 to .5,n-.5 to n-.5,.5 to n-.5).map{(x,y)->h.map(x,y)}
            val quad=ImageQuad(centers[0],centers[1],centers[2],centers[3])
            val pitch=(q[0].distanceTo(q[1])+q[0].distanceTo(q[3]))/(2*n)
            if(pitch<.75) return@runCatching
            val g=GridGeometry(v,FinderRole.TOP_LEFT,0.0,0.0,pitch,0.0,quad,GeometryModel.MANUAL_PROJECTIVE).reanchored()
            fun mismatch(x:Int,y:Int,black:Boolean):Double {
                val p=h.map(x+.5,y+.5);val value=image.sampleMean(p.x,p.y,0.0) ?: return 1.0
                return if((value<=image.threshold)==black) 0.0 else 1.0
            }
            var finder=0.0
            for((sx,sy) in listOf(0 to 0,n-7 to 0,0 to n-7)) for(y in 0..6) for(x in 0..6)
                finder+=mismatch(sx+x,sy+y,x==0 || y==0 || x==6 || y==6 || (x in 2..4 && y in 2..4))
            var timing=0.0
            for(i in 8..n-9) { timing+=mismatch(i,6,i%2==0);timing+=mismatch(6,i,i%2==0) }
            val score=finder/147*.55+timing/(2*(n-16))*.45
            out+=Ranked(Candidate(q.map{listOf(it.x,it.y)},n,c.stage),g,score)
        }
    }
    return out.sortedBy{it.score}.distinctBy{it.candidate}.take(4)
}
fun recover(corpus:File,input:File,out:File,useAutoThreshold:Boolean=true) {
    val manifest=JsonParser.parseString(File(corpus,"manifest.json").readText()).asJsonArray.associate {
        val c=it.asJsonObject;c["id"].asString to c["path"].asString
    }
    out.bufferedWriter().use { writer ->
        input.useLines { lines -> lines.forEachIndexed { idx,line ->
            val d=gson.fromJson(line,Detection::class.java);val image=load(File(corpus,manifest.getValue(d.id)))
            val start=System.nanoTime();val ranking=ranked(image,d.candidates)
            val thresholds=if(useAutoThreshold) InitialThresholdEstimator.estimate(android.graphics.Bitmap(image.width,image.height,image.pixels)) else null
            val standardText=if(d.engine=="baseline") StandardQrDecoder.decode(image.width,image.height,IntArray(image.pixels.size) {
                val v=image.pixels[it]; (0xff shl 24) or (v shl 16) or (v shl 8) or v
            })?.text else null
            var selected:String?=null;var selectedCandidate:Candidate?=null
            var timeout=0;var tried=0;var returned=0
            for(r in ranking) {
                tried++
                val profile=SamplingProfile(PreprocessSettings(version=r.geometry.version,manualQuad=r.geometry.imageQuad,blackMax=thresholds?.blackMax ?: 96,whiteMin=thresholds?.whiteMin ?: 160),false)
                val matrix=MatrixSampler(image,profile).sample(r.geometry).first
                var checks=0
                try {
                    val result=RecoveryEngine().recover(matrix,cancellation=RecoveryCancellation { ++checks>10_000 })
                        .sortedWith(recoveryCandidateComparator)
                    returned+=result.size
                    val parsed=result.firstOrNull{it.payloadParsed}
                    if(parsed!=null) {selected=parsed.text;selectedCandidate=r.candidate;break}
                } catch(_:RecoveryCancelledException) {timeout++}
            }
            writer.appendLine(gson.toJson(mapOf("id" to d.id,"engine" to d.engine,"selected_text" to selected,
                "threshold_profile" to if(useAutoThreshold) "automatic" else "fixed_96_160","selected_candidate" to selectedCandidate,"standard_text" to standardText,"black_max" to thresholds?.blackMax,"white_min" to thresholds?.whiteMin,"ranked" to ranking.map{it.candidate},"scores" to ranking.map{it.score},
                "recovery_ms" to (System.nanoTime()-start)/1e6,"budget_exhausted" to timeout,"tried" to tried,"returned" to returned)))
            writer.flush();if(idx%40==0) println("recover ${d.engine} $idx")
        } }
    }
}

// Replays recorded detector positions through the production initial-grid selection policy.
fun initialGrid(corpus:File,input:File,out:File) {
    val paths=JsonParser.parseString(File(corpus,"manifest.json").readText()).asJsonArray.associate {
        val c=it.asJsonObject;c["id"].asString to c["path"].asString
    }
    out.bufferedWriter().use { writer ->
        input.forEachLine { line ->
            val d=gson.fromJson(line,Detection::class.java)
            val image=load(File(corpus,paths.getValue(d.id)))
            val boundaries=d.candidates.map { c ->
                val p=c.quad.map { PointD(it[0],it[1]) }
                ImageQuad(p[0],p[1],p[2],p[3])
            }
            val estimate=InitialGridEstimator.estimate(image,boundaries)
            val candidates=estimate?.let { e ->
                val n=17+4*e.version
                val h=Homography.fromCorrespondences(listOf(PointD(.5,.5),PointD(n-.5,.5),PointD(n-.5,n-.5),PointD(.5,n-.5)),e.moduleCenterQuad.asList())
                listOf(Candidate(listOf(h.map(0.0,0.0),h.map(n.toDouble(),0.0),h.map(n.toDouble(),n.toDouble()),h.map(0.0,n.toDouble())).map{listOf(it.x,it.y)},n,e.source.name))
            } ?: emptyList()
            writer.appendLine(gson.toJson(mapOf("id" to d.id, "engine" to "initial_grid", "ms" to 0.0,
                "times" to emptyList<Double>(), "candidates" to candidates,
                "auto_assigned_finders" to estimate?.autoAssignedFinders.orEmpty().mapValues { (_, p) -> listOf(p.x, p.y) })))
        }
    }
}
