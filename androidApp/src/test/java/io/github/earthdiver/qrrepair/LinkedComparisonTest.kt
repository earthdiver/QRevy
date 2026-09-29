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

    @Test fun unmaskingChangesOnlyDisplayedDataAndKeepsUnknownsAndManualCells() {
        for (version in listOf(1, 7)) {
            val dimension = 17 + 4 * version
            val before = QrMatrix(dimension)
            val after = QrMatrix(dimension)
            for (y in 0 until dimension) for (x in 0 until dimension) {
                before.set(x, y, ModuleValue.ZERO, .7f, ModuleSource.USER)
                after.set(x, y, ModuleValue.ONE, .9f, ModuleSource.RECOVERED)
            }
            before.set(11, 10, ModuleValue.ERASED, 0f, ModuleSource.USER)
            before.setPrioritySearch(11, 10, true)
            val originalBefore = before.copy()
            val originalAfter = after.copy()
            val view = QrResultInspectorView(RuntimeEnvironment.getApplication())
            val paneWidth = (dimension + 4) * 8
            val height = paneWidth + 24
            val masksAtTenTen = listOf(true, true, false, false, true, false, false, false)
            fun bitmap() = render(view, paneWidth * 2, height)
            fun pixel(bitmap: Bitmap, x: Int, y: Int, right: Boolean = false) =
                bitmap.getPixel((if (right) paneWidth else 0) + (x + 2) * 8 + 4, 24 + (y + 2) * 8 + 4)
            for (mask in 0..7) {
                view.setDocument(before, after, emptyMap(), dataMask = mask)
                view.setUnmasked(false)
                val masked = bitmap()
                view.setUnmasked(true)
                val unmasked = bitmap()
                assertEquals("Version $version, mask $mask", if (masksAtTenTen[mask]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    pixel(unmasked, 10, 10))
                assertEquals("Version $version, mask $mask", if (masksAtTenTen[mask]) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
                    pixel(unmasked, 10, 10, true))
                assertEquals(pixel(masked, 11, 10), pixel(unmasked, 11, 10))
                val functionCells = listOf(Coord(0, 0), Coord(6, 10), Coord(8, 8), Coord(8, dimension - 8)) +
                    if (version == 7) listOf(Coord(22, 22), Coord(dimension - 10, 2)) else emptyList()
                for (coord in functionCells) for (right in listOf(false, true)) {
                    assertEquals(pixel(masked, coord.x, coord.y, right), pixel(unmasked, coord.x, coord.y, right))
                }
                view.setUnmasked(false)
                val restored = bitmap()
                assertTrue(masked.sameAs(restored))
                listOf(masked, unmasked, restored).forEach(Bitmap::recycle)
            }
            for (y in 0 until dimension) for (x in 0 until dimension) {
                assertEquals(originalBefore[x, y], before[x, y])
                assertEquals(originalAfter[x, y], after[x, y])
            }
        }
    }

    @Test fun failedAttemptsCanBeUnmaskedWithoutRsOrPayloadSuccessInBothLanguagesAndStyles() {
        for (style in AppStyle.entries) for (language in listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE)) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            AppLanguageManager.save(app, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val state = MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }
                    .get(activity) as RecoveryUiState
                for ((mask, stage) in listOf(0 to RecoveryFailureStage.REED_SOLOMON, 3 to RecoveryFailureStage.PAYLOAD_VALIDATION)) {
                    val matrix = QrMatrix(21)
                    for (y in 0 until 21) for (x in 0 until 21)
                        matrix.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.USER)
                    matrix.set(11, 10, ModuleValue.ERASED, 0f, ModuleSource.USER)
                    val failure = RecoveryFailure(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
                        mask, 0, 0, 0, stage)
                    state.showAttempt(0, GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0),
                        matrix, emptyList(), failure, -1)
                    MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
                    val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                    val decor = dialog.window!!.decorView
                    layout(decor)
                    val view = children(decor).filterIsInstance<QrResultInspectorView>().single()
                    val checkbox = children(decor).filterIsInstance<android.widget.CheckBox>().single()
                    assertTrue(checkbox.isEnabled)
                    assertFalse(checkbox.isChecked)
                    assertTrue(children(decor).filterIsInstance<TextView>().any {
                        it.text.toString() == if (language == AppLanguage.JAPANESE)
                            "使用マスク: $mask（失敗した試行・未検証）" else "Display mask: $mask (failed attempt; unverified)"
                    })
                    val modes = children(children(decor).filterIsInstance<android.widget.RadioGroup>().last())
                        .filterIsInstance<android.widget.RadioButton>()
                    for (mode in modes) {
                        mode.performClick()
                        val masked = render(view, 400, 300)
                        checkbox.performClick()
                        val unmasked = render(view, 400, 300)
                        assertFalse("$style/$language/$stage/${mode.text}", masked.sameAs(unmasked))
                        checkbox.performClick()
                        val restored = render(view, 400, 300)
                        assertTrue(masked.sameAs(restored))
                        listOf(masked, unmasked, restored).forEach(Bitmap::recycle)
                    }
                    assertNull(state.selectedRecovery)
                    assertSame(failure, state.failure)
                    for (y in 0 until 21) for (x in 0 until 21) {
                        assertEquals(matrix[x, y], state.sourceMatrix!![x, y])
                        assertEquals(matrix[x, y], state.workingMatrix!![x, y])
                    }
                    dialog.dismiss()
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun missingMaskClearsTheDisplayToggleBeforeAnotherDocumentIsLoaded() {
        val matrix = QrMatrix(21)
        for (y in 0 until 21) for (x in 0 until 21)
            matrix.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        val view = QrResultInspectorView(RuntimeEnvironment.getApplication())
        view.setDocument(matrix, matrix, emptyMap(), dataMask = 0)
        val masked = render(view)
        view.setUnmasked(true)
        val unmasked = render(view)
        assertFalse(masked.sameAs(unmasked))
        view.setDocument(matrix, matrix, emptyMap())
        val withoutMask = render(view)
        assertTrue(masked.sameAs(withoutMask))
        view.setDocument(matrix, matrix, emptyMap(), dataMask = 0)
        val next = render(view)
        assertTrue("Unmask must stay off after an unavailable mask", masked.sameAs(next))
        listOf(masked, unmasked, withoutMask, next).forEach(Bitmap::recycle)
    }

    @Test fun checkboxControlsSingleAndPairedViewsAndFollowsCandidateSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            val state = MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }
                .get(activity) as RecoveryUiState
            val fixtures = listOf(0, 2).map { mask ->
                val encoded = com.google.zxing.qrcode.encoder.Encoder.encode("UNMASK",
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                    mapOf(com.google.zxing.EncodeHintType.QR_MASK_PATTERN to mask))
                val matrix = QrMatrix(encoded.matrix.width)
                for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension)
                    matrix.set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                        1f, ModuleSource.IMAGE)
                matrix to RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(encoded.ecLevel, mask))
                    .first { it.text == "UNMASK" }
            }
            state.sourceMatrix = fixtures.first().first.copy()
            state.workingMatrix = fixtures.first().first.copy()
            state.recoveries = fixtures.map { it.second }
            state.selectRecovery(0)
            state.failure = RecoveryFailure(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                7, 0, 0, 0, RecoveryFailureStage.PAYLOAD_VALIDATION)
            MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val decor = dialog.window!!.decorView
            layout(decor)
            val view = children(decor).filterIsInstance<QrResultInspectorView>().single()
            val checkbox = children(decor).filterIsInstance<android.widget.CheckBox>().single()
            val radios = children(decor).filterIsInstance<android.widget.RadioGroup>()
            val modes = children(radios.last()).filterIsInstance<android.widget.RadioButton>()
            assertTrue(checkbox.isEnabled)
            assertFalse(checkbox.isChecked)
            fun displaysCandidateMask(mask: Int) = children(decor).filterIsInstance<TextView>().any {
                it.text.toString() in listOf("使用マスク: $mask（選択候補）", "Display mask: $mask (selected candidate)")
            }
            assertTrue("The selected candidate takes precedence over the failure mask", displaysCandidateMask(0))
            assertTrue(checkbox.left >= radios.last().right)
            for (mode in modes) {
                mode.performClick()
                val masked = render(view, 400, 300)
                checkbox.performClick()
                val unmasked = render(view, 400, 300)
                assertFalse(masked.sameAs(unmasked))
                checkbox.performClick()
                val restored = render(view, 400, 300)
                assertTrue(masked.sameAs(restored))
                listOf(masked, unmasked, restored).forEach(Bitmap::recycle)
            }
            checkbox.performClick()
            children(radios.first()).filterIsInstance<android.widget.RadioButton>()[1].performClick()
            assertTrue(checkbox.isChecked)
            assertEquals(2, state.selectedRecovery!!.dataMask)
            assertTrue(displaysCandidateMask(2))
            assertFalse(displaysCandidateMask(0))
            val expected = QrResultInspectorView(activity).apply {
                setDocument(state.sourceMatrix!!, state.inspectorMatrix ?: state.workingMatrix!!, state.traces, dataMask = 2)
                setUnmasked(true)
            }
            layout(view, 0, 0)
            val actualBitmap = render(view, 400, 300)
            val expectedBitmap = render(expected, 400, 300)
            assertTrue("Candidate selection must update the display mask", actualBitmap.sameAs(expectedBitmap))
            actualBitmap.recycle(); expectedBitmap.recycle()
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
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
                val unmask = children(decor).filterIsInstance<android.widget.CheckBox>().single()
                assertEquals(if (language == AppLanguage.JAPANESE) "マスク解除" else "Unmask", unmask.text.toString())
                assertFalse("Neither a selected candidate nor a failed attempt provides a mask", unmask.isEnabled)
                assertFalse(unmask.isChecked)
                assertTrue(children(decor).filterIsInstance<TextView>().any {
                    it.text.toString() == if (language == AppLanguage.JAPANESE)
                        "マスク番号が取得できないため、マスク解除は使用できません。"
                    else "Unmask is unavailable because no mask number is available."
                })
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
