package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.R
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAdjustmentRenderingTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun render(view: View, width: Int, height: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    @Test fun renderedPreviewUsesTheSameTonesAsAnalysisWithoutChangingSource() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70, 110, 160)) }
        val source = BitmapUtils.prepare(original)
        val view = CropOverlayView(activity).apply { cropEnabled = false; setBitmap(original) }
        for (settings in listOf(ImageAdjustment(), ImageAdjustment(25, 10), ImageAdjustment(-40, -30, true))) {
            view.setAdjustment(settings)
            val rendered = render(view, 90, 90)
            val expected = BitmapUtils.adjust(source, settings).bitmap.getPixel(15, 15)
            val actual = rendered.getPixel(45, 45)
            for (channel in listOf(Color::red, Color::green, Color::blue)) {
                assertTrue("Preview and sampled channel disagree", kotlin.math.abs(channel(expected) - channel(actual)) <= 1)
            }
            assertEquals(Color.rgb(70, 110, 160), original.getPixel(15, 15))
        }
    }

    @Test fun JapaneseEditorKeepsImageAreaAcrossToolsAndProducesOptionalSyntheticPreviews() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.ModernAppTheme)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode("QR REPAIR SYNTHETIC PREVIEW",
            com.google.zxing.BarcodeFormat.QR_CODE, 360, 360)
        val original = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        for (y in 0 until 360) for (x in 0 until 360) {
            val gray = if (matrix[x, y]) 95 else 200
            original.setPixel(x, y, Color.rgb(gray, gray, gray))
        }
        val editor = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = {}).apply {
            setBackgroundColor(0xff101312.toInt())
        }
        activity.setContentView(editor)
        val before = render(editor, 360, 800)
        val height = editor.preview.height
        views(editor).filterIsInstance<TextView>().first { it.text == activity.getString(R.string.image_adjustment) }.performClick()
        editor.setAdjustment(ImageAdjustment(contrast = 25))
        val after = render(editor, 360, 800)
        assertEquals(height, editor.preview.height)
        assertTrue(editor.preview.height >= 580)
        System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let { path ->
            val folder = File(path).apply { mkdirs() }
            File(folder, "crop-normal.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(folder, "crop-adjusting.png").outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
