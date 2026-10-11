package android.graphics
/** Evaluation adapter preserves source RGB for the production threshold estimator. */
class Bitmap(val width: Int, val height: Int, private val argb: IntArray) {
 fun getPixels(dst: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
  for (j in 0 until height) for (i in 0 until width) dst[offset+j*stride+i]=argb[(y+j)*this.width+x+i]
 }
}
