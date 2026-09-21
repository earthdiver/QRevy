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
import android.widget.Button
import android.widget.FrameLayout
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
import io.github.earthdiver.qrrepair.ui.ImageAdjustmentEditor
import io.github.earthdiver.qrrepair.core.ImageAdjustment
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
            if (!compress(output)) throw IOException("Image compression failed")
            output.flush()
            output.fd.sync()
        }
        if (tmp.length() <= 0L) throw IOException("Image compression produced an empty file")
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
        const val EXTRA_CONTRAST = "capture_contrast"
        const val EXTRA_BRIGHTNESS = "capture_brightness"
        const val EXTRA_INVERTED = "capture_inverted"
        private const val STATE_EDITOR = "camera.editor"
        private const val STATE_CAPTURE_PATH = "camera.capture_path"
        private const val STATE_CAPTURE_HANDED_OFF = "camera.capture_handed_off"
    }

    private var cropEditor: ImageAdjustmentEditor? = null
    private var cropOriginal: Bitmap? = null
    private var restoredEditor: Bundle? = null
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
        restoredEditor = savedInstanceState?.getBundle(STATE_EDITOR)
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
        cropEditor?.let { outState.putBundle(STATE_EDITOR, it.saveState()) }
        super.onSaveInstanceState(outState)
    }

    private fun showCameraScreen() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
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
        // starts. Completion disposes of the independent handoff if this Activity was destroyed.
        imageIoExecutor.shutdown()
        if (isFinishing && !captureHandedOff) {
            currentCaptureFile?.delete()
            currentCaptureFile = null
        }
        releaseCropEditor()
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
        releaseCropEditor()
        cropOriginal = decoded
        lateinit var editor: ImageAdjustmentEditor
        editor = ImageAdjustmentEditor(this, decoded, cancelLabel = R.string.retake,
            onCancel = {
                releaseCropEditor()
                captureFile.delete()
                if (currentCaptureFile == captureFile) currentCaptureFile = null
                showCameraScreen()
                startCamera()
            },
            onApply = { adjustment -> finishWithCrop(editor, captureFile, adjustment) })
        cropEditor = editor
        restoredEditor?.let { editor.restoreState(it) }
        restoredEditor = null
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(editor)
    }

    private fun releaseCropEditor() {
        cropEditor?.dispose()
        cropEditor = null
        cropOriginal?.takeIf { !it.isRecycled }?.recycle()
        cropOriginal = null
    }

    private fun finishWithCrop(editor: ImageAdjustmentEditor, captureFile: File, adjustment: ImageAdjustment) {
        val cropped = runCatching { editor.createCrop() }.getOrElse { error ->
            editor.showError(error)
            return
        }
        editor.setBusy(true)
        val handoff = File(cacheDir, "qr-capture-${UUID.randomUUID()}.png")
        imageIoExecutor.execute {
            try {
                replaceWithCompressedCapture(handoff) { output ->
                    // Preserve the unadjusted pixels exactly through the private camera handoff.
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                runOnUiThread {
                    if (isDestroyed) {
                        handoff.delete()
                        cleanupCompletedCaptureForDestroyedActivity(captureFile, isFinishing)
                        return@runOnUiThread
                    }
                    if (isFinishing) {
                        handoff.delete()
                        captureFile.delete()
                        return@runOnUiThread
                    }
                    captureFile.delete()
                    currentCaptureFile = null
                    captureHandedOff = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CAPTURE_PATH, handoff.absolutePath)
                        .putExtra(EXTRA_CONTRAST, adjustment.contrast)
                        .putExtra(EXTRA_BRIGHTNESS, adjustment.brightness)
                        .putExtra(EXTRA_INVERTED, adjustment.inverted))
                    finish()
                }
            } catch (error: Throwable) {
                handoff.delete()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        editor.setBusy(false)
                        editor.showError(error)
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
