package android.graphics
/** Browser adapter for the pixel-only API used by the upstream threshold estimator. */
class Bitmap(val width: Int, val height: Int, private val rgb: IntArray) {
    fun getPixels(dst: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        for (j in 0 until height) for (i in 0 until width) dst[offset+j*stride+i]=rgb[(y+j)*this.width+x+i] or -0x1000000
    }
}
