package io.github.earthdiver.qrrepair

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import io.github.earthdiver.qrrepair.ui.CropOverlayView
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors

internal fun replaceWithCompressedCapture(target: File, compress: (OutputStream) -> Boolean) {
    val tmp = File(target.parentFile, "${target.name}.crop-${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(tmp).use { output ->
            if (!compress(output)) throw IOException("JPEG compression failed")
            output.flush()
            output.fd.sync()
        }
        if (tmp.length() <= 0L) throw IOException("JPEG compression produced an empty file")
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        tmp.delete()
    }
}

internal fun cleanupCompletedCaptureForDestroyedActivity(capture: File, finishing: Boolean) {
    // A replacement Activity restores this same path after a configuration change. Only a real
    // finish relinquishes ownership; deleting on every destruction races the replacement UI.
    if (finishing) capture.delete()
}

internal class CaptureRequestGate {
    private var activeToken: String? = null

    fun tryStart(token: String): Boolean {
        if (activeToken != null) return false
        activeToken = token
        return true
    }

    fun finish(token: String): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }
}

internal fun boundedBitmapSampleSize(width: Int, height: Int, maxDimension: Int = 2_600): Int {
    require(width > 0 && height > 0 && maxDimension > 0)
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / sample > maxDimension && sample <= Int.MAX_VALUE / 2) sample *= 2
    return sample
}

internal fun createIndependentCrop(
    source: Bitmap,
    rect: RectF,
    createBitmap: (Bitmap, Int, Int, Int, Int) -> Bitmap = { bitmap, x, y, width, height ->
        Bitmap.createBitmap(bitmap, x, y, width, height)
    },
): Result<Bitmap> = runCatching {
    val left = rect.left.toInt().coerceIn(0, source.width - 1)
    val top = rect.top.toInt().coerceIn(0, source.height - 1)
    val right = rect.right.toInt().coerceIn(left + 1, source.width)
    val bottom = rect.bottom.toInt().coerceIn(top + 1, source.height)
    val selection = createBitmap(source, left, top, right - left, bottom - top)
    // Bitmap.createBitmap may return the source for a full-frame crop. The worker must own an
    // independent immutable snapshot because the displayed bitmap can be recycled separately.
    if (selection === source) {
        source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Could not copy the cropped bitmap")
    } else {
        selection
    }
}

/** CameraX preview and an explicit crop step before returning the image. */
class CameraActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CAPTURE_PATH = "capture_path"
        private const val STATE_CAPTURE_PATH = "camera.capture_path"
        private const val STATE_CAPTURE_HANDED_OFF = "camera.capture_handed_off"
    }

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private var captureButton: Button? = null
    private var imageCapture: ImageCapture? = null
    private var currentCaptureFile: File? = null
    private var captureHandedOff = false
    private val captureRequestGate = CaptureRequestGate()
    private val imageIoExecutor = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.setText(R.string.camera_permission_required)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppStylePreferences.parse(intent.getStringExtra(AppStylePreferences.EXTRA_ACTIVE_STYLE)).theme)
        super.onCreate(savedInstanceState)
        currentCaptureFile = savedInstanceState?.getString(STATE_CAPTURE_PATH)?.let(::File)
        captureHandedOff = savedInstanceState?.getBoolean(STATE_CAPTURE_HANDED_OFF, false) == true
        currentCaptureFile?.takeIf { it.isFile && !captureHandedOff }?.let {
            showCropScreen(it)
            return
        }
        currentCaptureFile = null
        captureHandedOff = false
        showCameraScreen()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentCaptureFile?.let { outState.putString(STATE_CAPTURE_PATH, it.absolutePath) }
        outState.putBoolean(STATE_CAPTURE_HANDED_OFF, captureHandedOff)
        super.onSaveInstanceState(outState)
    }

    private fun showCameraScreen() {
        // Camera controls overlay the preview. The vertically split layout is only for the
        // post-capture crop screen; using it here lets MATCH_PARENT preview consume the button.
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            setText(R.string.starting_camera)
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x88000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val capture = Button(this).apply {
            setText(R.string.capture); isAllCaps = false; setOnClickListener { captureFrame() }
        }
        captureButton = capture
        root.addView(capture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        setContentView(root)
    }

    override fun onDestroy() {
        // Let an accepted crop finish. shutdownNow() can strand its owned Bitmap before the task
        // starts, and an atomic replacement is safe to complete across configuration recreation.
        imageIoExecutor.shutdown()
        if (isFinishing && !captureHandedOff) {
            currentCaptureFile?.delete()
            currentCaptureFile = null
        }
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                status.setText(R.string.camera_ready)
            } catch (t: Throwable) {
                status.text = getString(R.string.camera_start_failed, t.message ?: t.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFrame() {
        val capture = imageCapture ?: return
        val token = UUID.randomUUID().toString()
        if (!captureRequestGate.tryStart(token)) return
        val file = File(cacheDir, "qr-capture-$token.jpg")
        currentCaptureFile = file
        captureHandedOff = false
        captureButton?.isEnabled = false
        status.setText(R.string.capturing)
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (!captureRequestGate.finish(token) || currentCaptureFile != file || isFinishing || isDestroyed) {
                        file.delete()
                        return
                    }
                    showCropScreen(file)
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    if (!captureRequestGate.finish(token) || currentCaptureFile != file || isFinishing || isDestroyed) return
                    currentCaptureFile = null
                    captureButton?.isEnabled = true
                    status.text = getString(R.string.capture_failed, exception.message)
                }
            })
    }

    private fun showCropScreen(captureFile: File) {
        val decoded = decodeOrientedBitmap(captureFile)
        if (decoded == null) {
            captureFile.delete()
            if (currentCaptureFile == captureFile) currentCaptureFile = null
            showCameraScreen()
            status.setText(R.string.capture_read_failed)
            startCamera()
            return
        }
        var bitmap: Bitmap = decoded
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cropView = CropOverlayView(this).apply { setBitmap(bitmap) }
        val cropStatus = TextView(this).apply {
            setText(R.string.crop_help)
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(cropStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val rotationButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(Button(this@CameraActivity).apply {
                setText(R.string.rotate_left_90)
                setOnClickListener {
                    val old = bitmap
                    bitmap = BitmapUtils.rotate(old, -90f).bitmap
                    if (bitmap !== old) old.recycle()
                    cropView.setBitmap(bitmap)
                }
            })
            fun rotateBy(degrees: Float) {
                val old = bitmap
                val rotated = BitmapUtils.rotate(old, degrees).bitmap
                if (rotated !== old) old.recycle()
                bitmap = rotated
                cropView.setBitmap(rotated)
            }
            addView(Button(this@CameraActivity).apply { text = "-1°"; setOnClickListener { rotateBy(-1f) } })
            addView(Button(this@CameraActivity).apply { text = "-0.1°"; setOnClickListener { rotateBy(-.1f) } })
            addView(Button(this@CameraActivity).apply { text = "+0.1°"; setOnClickListener { rotateBy(.1f) } })
            addView(Button(this@CameraActivity).apply { text = "+1°"; setOnClickListener { rotateBy(1f) } })
            addView(Button(this@CameraActivity).apply {
                setText(R.string.rotate_right_90)
                setOnClickListener {
                    val old = bitmap
                    bitmap = BitmapUtils.rotate(old, 90f).bitmap
                    if (bitmap !== old) old.recycle()
                    cropView.setBitmap(bitmap)
                }
            })
        }
        root.addView(HorizontalScrollView(this).apply { addView(rotationButtons) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        lateinit var actions: LinearLayout
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(8))
            addView(Button(this@CameraActivity).apply {
                setText(R.string.retake); isAllCaps = false
                setOnClickListener {
                    bitmap.recycle()
                    captureFile.delete()
                    if (currentCaptureFile == captureFile) currentCaptureFile = null
                    showCameraScreen()
                    startCamera()
                }
            })
            addView(Button(this@CameraActivity).apply {
                setText(R.string.crop_and_analyze); isAllCaps = false
                setOnClickListener {
                    val source = bitmap
                    val controls = buildList<View> {
                        for (index in 0 until rotationButtons.childCount) add(rotationButtons.getChildAt(index))
                        for (index in 0 until actions.childCount) add(actions.getChildAt(index))
                    }
                    controls.forEach { it.isEnabled = false }
                    finishWithCrop(source, cropView, captureFile, controls, cropStatus)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun finishWithCrop(
        source: Bitmap,
        cropView: CropOverlayView,
        captureFile: File,
        controls: List<View>,
        cropStatus: TextView,
    ) {
        val cropped = createIndependentCrop(source, cropView.cropRect()).getOrElse { error ->
            controls.forEach { it.isEnabled = true }
            cropStatus.text = getString(R.string.capture_failed, error.message ?: error.javaClass.simpleName)
            return
        }
        imageIoExecutor.execute {
            try {
                replaceWithCompressedCapture(captureFile) { output ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                runOnUiThread {
                    if (isDestroyed) {
                        cleanupCompletedCaptureForDestroyedActivity(captureFile, isFinishing)
                        return@runOnUiThread
                    }
                    if (isFinishing) {
                        captureFile.delete()
                        return@runOnUiThread
                    }
                    captureHandedOff = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CAPTURE_PATH, captureFile.absolutePath))
                    finish()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        controls.forEach { it.isEnabled = true }
                        cropStatus.text = getString(R.string.capture_failed, error.message ?: error.javaClass.simpleName)
                    }
                }
            } finally {
                cropped.recycle()
            }
        }
    }

    private fun decodeOrientedBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = boundedBitmapSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return source
        }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true).also {
                if (it !== source) source.recycle()
            }
        } catch (t: Throwable) {
            source.recycle()
            throw t
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
