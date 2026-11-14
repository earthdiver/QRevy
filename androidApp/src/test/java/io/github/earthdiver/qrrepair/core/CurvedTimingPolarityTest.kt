package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic image regressions for timing phase ambiguity and global image polarity. */
class CurvedTimingPolarityTest {
    private val text="Joint surface study 0123456789"
    private val encoded=Encoder.encode(text,ErrorCorrectionLevel.M,mapOf(EncodeHintType.QR_VERSION to 4,
        EncodeHintType.QR_MASK_PATTERN to 2)).matrix
    private val n=encoded.width
    private val last=n-1.0
    private val pitch=6.0
    private val size=280
    private val logical=listOf(PointD(0.0,0.0),PointD(last,0.0),PointD(last,last),PointD(0.0,last))
    private val quad=ImageQuad(PointD(37.0,35.0),PointD(235.0,42.0),PointD(224.0,237.0),PointD(31.0,225.0))
    private val projective=Homography.fromCorrespondences(logical,quad.asList())
    private val inverse=projective.inverse()
    private data class Shape(val name: String,val amplitude: Double)
    private val shapes=listOf(Shape("flat",0.0),Shape("sine",1.4),Shape("sine",2.8),
        Shape("bowl",1.4),Shape("cross",1.4),Shape("double",1.4))
    private fun dy(shape: Shape,x: Double): Double = shape.amplitude*when(shape.name) {
        "bowl" -> 4*(x/last)*(1-x/last)
        "double" -> sin(2*PI*x/last)
        else -> sin(PI*x/last)
    }
    private fun dx(shape: Shape,y: Double)=if(shape.name=="cross") .45*shape.amplitude*sin(PI*y/last) else 0.0
    private fun truth(shape: Shape,x: Double,y: Double): PointD {
        val u=x+dx(shape,y)
        return projective.map(u,y+dy(shape,u))
    }
    private fun render(shape: Shape,damage: String): LumaImage {
        // Supersampling avoids locking the fixture to the optimizer's bilinear interpolator.
        val pixels=IntArray(size*size) { index ->
            var sum=0.0
            for(sy in 0..1) for(sx in 0..1) {
                val p=inverse.map(index%size+(sx-.5)*.5,index/size+(sy-.5)*.5)
                val y=p.y-dy(shape,p.x); val x=p.x-dx(shape,y)
                val mx=floor(x+.5).toInt(); val my=floor(y+.5).toInt()
                val inside=mx in 0 until n && my in 0 until n
                val timingDamage=(my==6 && mx in 10..22) || (mx==6 && my in 10..22)
                val value=if(damage=="timing-erased" && timingDamage) 128
                    else if(damage=="timing-phase-flip" && timingDamage) {
                        if(encoded[mx,my].toInt()==1) 235 else 20
                    }
                    else if(damage=="missing-finder" && mx>=n-8 && mx<n && my in 0..7) 128
                    else if(inside && encoded[mx,my].toInt()==1) 20 else 235
                sum+=value
            }
            (sum/4).roundToInt()
        }
        if(damage!="blur-noise") return LumaImage(size,size,pixels)
        val blurred=IntArray(pixels.size) { index ->
            val x=index%size; val y=index/size
            var sum=0
            for(j in -1..1) for(i in -1..1) sum+=pixels[(y+j).coerceIn(0,size-1)*size+(x+i).coerceIn(0,size-1)]
            val noise=((index.toLong()*1103515245+12345) ushr 16).toInt().and(15)-7
            (sum/9+noise).coerceIn(0,255)
        }
        return LumaImage(size,size,blurred)
    }
    private fun seed(shift: Double): GridGeometry {
        val offsets=listOf(PointD(.8,-.4),PointD(-.5,.7),PointD(.4,-.8),PointD(-.7,-.3))
        val p=quad.asList().mapIndexed { i,v -> v+offsets[i]*(shift*pitch) }
        return GridGeometry(4,FinderRole.TOP_LEFT,55.0,55.0,pitch,0.0,
            ImageQuad(p[0],p[1],p[2],p[3]),GeometryModel.MANUAL_PROJECTIVE).reanchored()
    }
    @Test fun locallyReversedTimingDoesNotBecomeAOneModuleShift() {
        for(shape in listOf(shapes[1],shapes[3],shapes[5])) for(inverted in listOf(false,true)) {
            val normal=render(shape,"timing-phase-flip")
            val image=if(inverted) LumaImage(size,size,IntArray(size*size) { 255-normal.pixels[it] }) else normal
            val base=seed(0.0)
            val profile=SamplingProfile(PreprocessSettings(version=4,manualQuad=base.imageQuad,
                distortionCorrection=true,blackMax=80,whiteMin=175),inverted)
            val lm=projectivePatternHypothesis(image,profile,
                listOf(GeometryHypothesis(emptyList(),base,quickGeometryScore(image,base,profile)))) { false }?.geometry ?: base
            val candidates=CurvedGridDetector(image,profile).candidates(lm)
            assertTrue("Finder/alignment candidates must remain available",candidates.isNotEmpty())
            val falseAnchors=candidates.flatMap { g ->
                val planar=g.copy(model=GeometryModel.PROJECTIVE,warp=null)
                g.warp!!.landmarks.filter { it.smoothing==1e-3 }.filter { a ->
                    val x=a.position.x*last; val y=a.position.y*last
                    (planar.moduleToImage(x,y)+a.displacement).distanceTo(truth(shape,x,y))>pitch*.5
                }
            }
            assertTrue("Locally reversed timing must not create shifted anchors: ${shape.name}, inverted=$inverted, count=${falseAnchors.size}",falseAnchors.isEmpty())
        }
    }

    @Test fun unambiguousTimingStillImprovesCurvedSamplingInEitherImagePolarity() {
        val shape=shapes[4]
        for(inverted in listOf(false,true)) {
            val normal=render(shape,"blur-noise")
            val image=if(inverted) LumaImage(size,size,IntArray(size*size) { 255-normal.pixels[it] }) else normal
            val base=seed(0.0)
            val profile=SamplingProfile(PreprocessSettings(version=4,manualQuad=base.imageQuad,
                distortionCorrection=true,blackMax=80,whiteMin=175),inverted)
            val lm=projectivePatternHypothesis(image,profile,
                listOf(GeometryHypothesis(emptyList(),base,quickGeometryScore(image,base,profile)))) { false }?.geometry ?: base
            val candidates=CurvedGridDetector(image,profile).candidates(lm)
            val selected=candidates.minBy { quickGeometryScore(image,it,profile) }
            assertTrue("Useful timing observations must remain available",selected.warp!!.landmarks.any { it.smoothing==1e-3 })
            val recovered=RecoveryEngine().recover(MatrixSampler(image,profile).sample(selected).first,
                listDecodingBudget=ListDecodingBudget(0)).filter { it.payloadParsed }
            assertEquals("The exact payload must remain first",text,
                recovered.minWithOrNull(recoveryCandidateComparator)?.text)
        }
    }

    @Test fun jointReplacementRejectsMoreUnknownsDespiteImprovedPatternResiduals() {
        var rejected = 0
        var accepted = 0
        for (inverted in listOf(false, true)) {
            val normal = render(shapes[1], "blur-noise")
            val image = if (inverted) LumaImage(size, size, IntArray(size * size) { 255 - normal.pixels[it] }) else normal
            val base = seed(0.0)
            val profile = SamplingProfile(PreprocessSettings(version = 4, manualQuad = base.imageQuad,
                distortionCorrection = true, automaticThresholds = true), inverted)
            for (candidate in CurvedGridDetector(image, profile).candidates(base)) {
                val fit = JointPatternRefiner.refine(image, candidate, intArrayOf(6, 26), inverted) ?: continue
                assertTrue(fit.residualSumSquares < fit.initialResidualSumSquares)
                val before = geometryRankingUnknownCount(image, candidate, profile) { false }
                val after = geometryRankingUnknownCount(image, fit.geometry, profile) { false }
                val selected = jointlyRefinedGeometry(image, candidate, profile) { false }
                if (after > before) {
                    rejected++
                    assertNull("Unknowns increased from $before to $after", selected)
                } else {
                    accepted++
                    assertEquals(fit.geometry, selected)
                }
            }
        }
        assertTrue("The synthetic fixture must exercise rejection", rejected > 0)
        assertTrue("The synthetic fixture must retain useful refinements", accepted > 0)
    }

    @Test fun convergedJointFitImprovesTheSameImageResidualsInEitherPolarity() {
        for (inverted in listOf(false, true)) {
            val normal = render(shapes[1], "blur-noise")
            val image = if (inverted) LumaImage(size, size, IntArray(size * size) { 255 - normal.pixels[it] }) else normal
            val base = seed(0.0)
            val profile = SamplingProfile(PreprocessSettings(version = 4, manualQuad = base.imageQuad,
                distortionCorrection = true, blackMax = 80, whiteMin = 175), inverted)
            val lm = projectivePatternHypothesis(image, profile,
                listOf(GeometryHypothesis(emptyList(), base, quickGeometryScore(image, base, profile)))) { false }?.geometry ?: base
            val initial = CurvedGridDetector(image, profile).candidates(lm).minByOrNull {
                quickGeometryScore(image, it, profile)
            } ?: lm
            val result = JointPatternRefiner.refine(image, initial, intArrayOf(6, 26), inverted)
            assertNotNull("A supported curved image must converge: inverted=$inverted", result)
            result!!
            val state = io.github.earthdiver.qrrepair.DistortionPreviewState()
            val input = io.github.earthdiver.qrrepair.DistortionPreviewState.Input(image, lm, profile)
            val original = state.calculate(state.request(input)!!).candidates
            val replacement = state.calculate(state.request(input.copy(jointRefinement = true))!!).candidates
            assertEquals(original.size, replacement.size)
            val selectedFit = jointlyRefinedGeometry(image, original.first(), profile) { false }
            assertEquals(selectedFit ?: original.first(), replacement.first())
            assertEquals(original.drop(1), replacement.drop(1))
            assertTrue(result.residualSumSquares < result.initialResidualSumSquares)
            assertTrue(result.geometry.hasUsableWarp())
            assertEquals(initial.version, result.geometry.version)
            assertEquals(initial.role, result.geometry.role)
            val recovered = RecoveryEngine().recover(MatrixSampler(image, profile).sample(result.geometry).first,
                listDecodingBudget = ListDecodingBudget(0)).filter { it.payloadParsed }
            assertEquals(text, recovered.minWithOrNull(recoveryCandidateComparator)?.text)
            assertNull(JointPatternRefiner.refine(image, initial, intArrayOf(6, 26), inverted,
                maxIterationsPerScale = 1))
            var checks = 0
            assertNull(JointPatternRefiner.refine(image, initial, intArrayOf(6, 26), inverted,
                stopped = { ++checks > 20 }))
            assertTrue(checks > 20)
        }
    }

}
