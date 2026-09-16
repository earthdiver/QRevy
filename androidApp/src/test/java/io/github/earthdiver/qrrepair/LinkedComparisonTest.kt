package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkedComparisonTest {
    private fun children(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun cell(v: QrResultInspectorView, x: Float, y: Float): Coord? =
        QrResultInspectorView::class.java.getDeclaredMethod("pointToCell", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(v, x, y) as Coord?
    private fun layout(v: View, w: Int = 360, h: Int = 640) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun render(v: View, w: Int = 240, h: Int = 240): Bitmap {
        layout(v, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    @Test fun beforeViewIsTheRawMatrixWithoutAnyEvidenceOverlays() {
        val app = RuntimeEnvironment.getApplication()
        val before = QrMatrix(21)
        val after = QrMatrix(21)
        for (y in 0 until 21) for (x in 0 until 21) {
            before.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            after.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.RECOVERED)
        }
        val coord = Coord(10, 10)
        val decorated = QrResultInspectorView(app).apply {
            setDocument(before, after, emptyMap())
            setCandidateDiff(setOf(coord))
            setHighlightedCodeword(setOf(coord))
            setSelected(coord.x, coord.y)
            setHeatmap(true, mapOf(coord to CellConfidence(.2f, "Low", "test")))
            setBeforeFraction(1f)
        }
        val plain = QrResultInspectorView(app).apply {
            setDocument(before, after, emptyMap())
            setBeforeFraction(1f)
        }
        val decoratedBefore = render(decorated)
        val plainBefore = render(plain)
        assertTrue("Before view must not contain evidence overlays", decoratedBefore.sameAs(plainBefore))

        decorated.setComparisonEnabled(false)
        plain.setComparisonEnabled(false)
        val decoratedAfter = render(decorated)
        val plainAfter = render(plain)
        assertFalse("After view must retain evidence overlays", decoratedAfter.sameAs(plainAfter))
        listOf(decoratedBefore, plainBefore, decoratedAfter, plainAfter).forEach(Bitmap::recycle)
    }

    @Test fun bothPanesReferToSameCellAfterPanAndZoomAndDrawTheirOwnValues() {
        val v = QrResultInspectorView(RuntimeEnvironment.getApplication())
        val before = QrMatrix(21)
        val after = before.copy()
        for (y in 0..20) for (x in 0..20) {
            before.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            after.set(x, y, ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        }
        v.setDocument(before, after, emptyMap())
        layout(v, 360, 300)
        v.focusCell(10, 10)
        assertEquals(Coord(10, 10), cell(v, 90f, 162f))
        assertEquals(cell(v, 90f, 162f), cell(v, 270f, 162f))
        val bitmap = Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(90, 162))
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(270, 162))
        bitmap.recycle()
        fun event(action: Int, x: Float, y: Float, time: Long) {
            MotionEvent.obtain(if (time >= 1000) 1000 else 0, time, action, x, y, 0).also { v.onTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 270f, 162f, 0)
        event(MotionEvent.ACTION_MOVE, 318f, 186f, 30)
        event(MotionEvent.ACTION_UP, 318f, 186f, 60)
        assertEquals(cell(v, 100f, 160f), cell(v, 280f, 160f))
        assertNotEquals(Coord(10, 10), cell(v, 90f, 162f))
        // Pinch within the right pane, then verify the common viewport on both sides.
        val scaleField = QrResultInspectorView::class.java.getDeclaredField("scale").apply { isAccessible = true }
        val oldScale = scaleField.getFloat(v)
        fun pinch(action: Int, span: Float, time: Long) {
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = 270f
                y = 162f + if (index == 0) -span / 2 else span / 2; pressure = 1f; size = 1f; touchMajor = 20f; touchMinor = 20f
            } }
            MotionEvent.obtain(1000, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                .also { v.onTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 245f, 162f, 1000)
        pinch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50f, 1010)
        pinch(MotionEvent.ACTION_MOVE, 220f, 1400)
        pinch(MotionEvent.ACTION_MOVE, 280f, 1700)
        pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 280f, 1900)
        event(MotionEvent.ACTION_UP, 195f, 162f, 2000)
        assertTrue("Scale before=$oldScale after=${scaleField.getFloat(v)}", scaleField.getFloat(v) > oldScale)
        assertEquals(cell(v, 90f, 162f), cell(v, 270f, 162f))
        // Resizing for the evidence panel retains the centered logical position.
        val center = cell(v, 90f, 162f)
        layout(v, 360, 220)
        assertEquals(center, cell(v, 90f, 122f))
        assertEquals(cell(v, 90f, 122f), cell(v, 270f, 122f))
    }
    @Test fun evidenceAndLegendKeepQrVisibleInBothLanguagesAndStyles() {
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style); AppLanguageManager.save(app, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val state = MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }.get(activity) as RecoveryUiState
                val qr = com.google.zxing.qrcode.encoder.Encoder.encode("LINKED COMPARISON", com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).matrix
                val matrix = QrMatrix(qr.width)
                for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension)
                    matrix.set(x, y, if (qr.get(x,y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                state.sourceMatrix = matrix.copy(); state.workingMatrix = matrix
                MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val decor = dialog.window!!.decorView
                layout(decor)
                val inspector = children(decor).filterIsInstance<QrResultInspectorView>().single()
                children(decor).filterIsInstance<TextView>().first { it.text.toString() == if (language == AppLanguage.JAPANESE) "左右で比較" else "Compare" }.performClick()
                inspector.onCellSelected?.invoke(10, 10)
                layout(decor)
                assertTrue("$style/$language QR height=${inspector.height}", inspector.height >= 160)
                val oldHeight = inspector.height
                children(decor).filterIsInstance<TextView>().first { it.text.toString() == if (language == AppLanguage.JAPANESE) "色と記号の説明" else "Colors and symbols" }.performClick()
                layout(decor)
                assertEquals(oldHeight, inspector.height)
                System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let {
                    val directory = File(it).apply { mkdirs() }
                    val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    File(directory, "linked-${style.name}-${language.tag}.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    bitmap.recycle()
                }
                dialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        }
    }
}
