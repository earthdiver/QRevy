package android.graphics

/** JVM study adapter for exactly the width/height/getPixels API used by InitialThresholdEstimator.
 * Stores synthetic grayscale values as opaque RGB. No Android algorithm is reimplemented. */
class Bitmap(val width:Int,val height:Int,private val gray:IntArray) {
    fun getPixels(dst:IntArray,offset:Int,stride:Int,x:Int,y:Int,width:Int,height:Int) {
        for(j in 0 until height) for(i in 0 until width) {
            val v=gray[(y+j)*this.width+x+i]
            dst[offset+j*stride+i]=(0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
    }
}
