package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnmaskedCellEvidenceTest {
    private val changed = Coord(10, 10)

    private fun children(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()

    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun withActivity(language: AppLanguage, action: (MainActivity, RecoveryUiState) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val previousLanguage = AppLanguageManager.current
        val previousSelection = AppLanguageManager.selected(app)
        AppLanguageManager.save(app, language)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            val state = MainActivity::class.java.getDeclaredField("recoveryState")
                .apply { isAccessible = true }.get(activity) as RecoveryUiState
            action(activity, state)
        } finally {
            try { controller.pause().stop().destroy() } finally {
                // Robolectric reuses app statics across tests. Restore both language choices
                // without changing the default Locale or the application's configuration.
                AppLanguageManager.save(app, previousLanguage)
                AppLanguageManager.wrap(app)
                AppLanguageManager.save(app, previousSelection)
            }
        }
    }

    private fun fixture(mask: Int, damaged: Boolean = false): Pair<QrMatrix, RecoveryCandidate> {
        val encoded = Encoder.encode("HELLO", ErrorCorrectionLevel.M,
            mapOf(EncodeHintType.QR_MASK_PATTERN to mask))
        val matrix = QrMatrix(encoded.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        if (damaged) {
            assertEquals(ModuleValue.ONE, matrix[changed.x, changed.y].value)
            matrix.set(changed.x, changed.y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            matrix.set(12, 10, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        return matrix to RecoveryEngine().recover(matrix,
            formatConstraint = QrFormatConstraint(encoded.ecLevel, mask)).first { it.text == "HELLO" }
    }

    private data class Inspector(val dialog: AlertDialog, val view: QrResultInspectorView, val unmask: CheckBox) {
        val decor: View get() = dialog.window!!.decorView
    }

    private fun open(activity: MainActivity, state: RecoveryUiState, input: QrMatrix,
        candidates: List<RecoveryCandidate> = emptyList(), failureMask: Int? = null): Inspector {
        state.geometry = GridGeometry(1, FinderRole.TOP_LEFT, 0.0, 0.0, 1.0, 0.0)
        state.sourceMatrix = input.copy()
        state.workingMatrix = input.copy()
        state.recoveries = candidates
        state.selectRecovery(if (candidates.isEmpty()) -1 else 0)
        state.failure = failureMask?.let { RecoveryFailure(ErrorCorrectionLevel.M, it, 0, 0, 0,
            RecoveryFailureStage.REED_SOLOMON) }
        invoke(activity, "refreshInspector")
        invoke(activity, "showZoomableResult")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val views = children(dialog.window!!.decorView)
        return Inspector(dialog, views.filterIsInstance<QrResultInspectorView>().single(),
            views.filterIsInstance<CheckBox>().single())
    }

    private fun click(inspector: Inspector, label: String) = children(inspector.decor)
        .filterIsInstance<TextView>().single { it.text.toString() == label }.performClick()

    private fun evidence(inspector: Inspector, coord: Coord, japanese: Boolean): String = children(inspector.decor)
        .filterIsInstance<TextView>().single { it.text.startsWith(
            if (japanese) "セル (${coord.x},${coord.y})" else "Cell (${coord.x},${coord.y})") }.text.toString()

    /** Sample cell centers in the actual native-rendered before/after panes. */
    private fun pixels(inspector: Inspector, coord: Coord, compare: Boolean = false): List<Int> {
        val width = if (compare) 500 else 250
        val height = if (compare) 274 else 250
        val view = inspector.view
        view.layout(0, 0, 0, 0)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            val x = (coord.x + 2) * 10 + 5
            val y = (coord.y + 2) * 10 + 5 + if (compare) 24 else 0
            if (compare) listOf(bitmap.getPixel(x, y), bitmap.getPixel(x + 250, y))
            else listOf(bitmap.getPixel(x, y))
        } finally { bitmap.recycle() }
    }

    @Test fun selectedCellEvidenceFollowsUnmaskInBeforeAfterAndCompareViews() {
        val (input, candidate) = fixture(0, damaged = true)
        for (language in AppLanguage.entries) withActivity(language) { activity, state ->
            val ja = language == AppLanguage.JAPANESE
            val inspector = open(activity, state, input, listOf(candidate))
            inspector.view.onCellSelected!!.invoke(changed.x, changed.y)
            click(inspector, if (ja) "計算の説明を表示／閉じる" else "Show/hide calculation details")
            for (mode in 0..2) {
                click(inspector, (if (ja) listOf("修復後", "修復前", "左右で比較")
                    else listOf("After", "Before", "Compare"))[mode])
                for (unmasked in listOf(false, true, false)) {
                    inspector.unmask.isChecked = unmasked
                    val text = evidence(inspector, changed, ja)
                    val before = if (unmasked) Color.BLACK else Color.WHITE
                    val after = if (unmasked) Color.WHITE else Color.BLACK
                    val values = if (ja) {
                        if (unmasked) "黒（1） → 白（0）" else "白（0） → 黒（1）"
                    } else if (unmasked) "Black (1) → White (0)" else "White (0) → Black (1)"
                    assertTrue(text, text.contains(values))
                    assertFalse(text.contains(if (ja) "画面の白黒にはQRのマスクを適用" else "The QR mask is applied to the displayed cells"))
                    assertTrue(text.contains(if (ja) "マスク解除後：" else "After removing the QR mask:"))
                    assertEquals(when (mode) { 0 -> listOf(after); 1 -> listOf(before); else -> listOf(before, after) },
                        pixels(inspector, changed, compare = mode == 2))
                }
            }
            // The codeword-highlight path has its own evidence renderer.
            click(inspector, if (ja) "同じコード語を表示" else "Show same codeword")
            inspector.unmask.isChecked = true
            assertTrue(evidence(inspector, changed, ja).contains(if (ja) "黒（1） → 白（0）" else "Black (1) → White (0)"))
            for (y in 0 until input.dimension) for (x in 0 until input.dimension) {
                assertEquals(input[x, y], state.sourceMatrix!![x, y])
                assertEquals(input[x, y], state.workingMatrix!![x, y])
            }
            inspector.dialog.dismiss()
        }
    }

    @Test fun candidateSwitchRefreshesUnmaskedEvidenceForTheSelectedCell() {
        val (input, first) = fixture(0, damaged = true)
        val second = fixture(2).second
        withActivity(AppLanguage.ENGLISH) { activity, state ->
            val inspector = open(activity, state, input, listOf(first, second))
            inspector.view.onCellSelected!!.invoke(changed.x, changed.y)
            click(inspector, "Show/hide calculation details")
            click(inspector, "Show same codeword")
            click(inspector, "Compare")
            inspector.unmask.isChecked = true
            assertTrue(evidence(inspector, changed, false).contains("Black (1) → White (0)"))
            assertEquals(listOf(Color.BLACK, Color.WHITE), pixels(inspector, changed, compare = true))
            children(inspector.decor).filterIsInstance<RadioButton>()
                .single { it.text.toString() == activity.getString(R.string.result_tab, 2) }.performClick()
            assertTrue(inspector.unmask.isChecked)
            assertEquals(2, state.selectedRecovery!!.dataMask)
            assertTrue(evidence(inspector, changed, false).contains("Unchanged: White (0)"))
            assertEquals(listOf(Color.WHITE, Color.WHITE), pixels(inspector, changed, compare = true))
            inspector.dialog.dismiss()
        }
    }

    @Test fun failedAttemptUnmaskingPreservesUnknownAndFunctionCellEvidence() {
        val input = QrMatrix(21)
        val functions = listOf(Coord(0, 0), Coord(6, 10), Coord(8, 8))
        functions.forEach { input.set(it.x, it.y, ModuleValue.ONE, 1f, ModuleSource.IMAGE) }
        withActivity(AppLanguage.ENGLISH) { activity, state ->
            val inspector = open(activity, state, input, failureMask = 0)
            inspector.view.onCellSelected!!.invoke(changed.x, changed.y)
            assertTrue(evidence(inspector, changed, false).contains("Unchanged: Unknown (?)"))
            val unknownPixel = pixels(inspector, changed)
            inspector.unmask.isChecked = true
            assertTrue(evidence(inspector, changed, false).contains("Unchanged: Unknown (?)"))
            assertEquals(unknownPixel, pixels(inspector, changed))
            for (coord in functions) {
                inspector.view.onCellSelected!!.invoke(coord.x, coord.y)
                val functionEvidence = evidence(inspector, coord, false)
                for (unmasked in listOf(false, true)) {
                    inspector.unmask.isChecked = unmasked
                    assertEquals(functionEvidence, evidence(inspector, coord, false))
                    assertTrue(functionEvidence.contains("Unchanged: Black (1)"))
                    assertEquals(listOf(Color.BLACK), pixels(inspector, coord))
                }
            }
            inspector.dialog.dismiss()
        }
    }

    @Test fun priorityTrialValuesStateTheirMaskedBasisWhileDisplayIsUnmasked() {
        val encoded = Encoder.encode("STAR-SEARCH", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_MASK_PATTERN to 0))
        val input = QrMatrix(encoded.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (encoded.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO,
                    1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(input, 1, 0)
        extraction.codewords.take(4).forEach { word ->
            val coord = word.coordinates.first()
            input.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.USER)
            input.setPrioritySearch(coord.x, coord.y, true)
        }
        extraction.codewords.slice(4..8).forEach { word ->
            word.coordinates.forEach { input.set(it.x, it.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE) }
        }
        val error = extraction.codewords[12].coordinates.first()
        input.set(error.x, error.y, if (input[error.x, error.y].value == ModuleValue.ONE) ModuleValue.ZERO
            else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        val candidate = RecoveryEngine().recover(input,
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)).first { it.text == "STAR-SEARCH" }
        val (coord, assigned) = candidate.priorityAssignments.entries.first {
            CodewordExtractor.maskApplies(0, it.key.x, it.key.y)
        }
        val storedBit = if (assigned == ModuleValue.ONE) "1" else "0"
        for (language in AppLanguage.entries) withActivity(language) { activity, state ->
            val ja = language == AppLanguage.JAPANESE
            val inspector = open(activity, state, input, listOf(candidate))
            inspector.view.onCellSelected!!.invoke(coord.x, coord.y)
            click(inspector, if (ja) "計算の説明を表示／閉じる" else "Show/hide calculation details")
            inspector.unmask.isChecked = true
            val displayedValue = if (assigned == ModuleValue.ONE) {
                if (ja) "白（0）" else "White (0)"
            } else if (ja) "黒（1）" else "Black (1)"
            val text = evidence(inspector, coord, ja)
            assertTrue(text, text.contains("→ $displayedValue"))
            assertTrue(text, text.contains(if (ja) "${storedBit}を仮置きしました（マスク解除前の値）"
                else "assigned $storedBit to this cell (before removing the QR mask)"))
            assertEquals(listOf(if (assigned == ModuleValue.ONE) Color.WHITE else Color.BLACK),
                pixels(inspector, coord))
            inspector.dialog.dismiss()
        }
    }

    @Test fun missingMaskKeepsTheDisplayedAndDescribedStoredValues() {
        val input = QrMatrix(21).apply { set(changed.x, changed.y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE) }
        withActivity(AppLanguage.ENGLISH) { activity, state ->
            val inspector = open(activity, state, input)
            assertFalse(inspector.unmask.isEnabled)
            assertFalse(inspector.unmask.isChecked)
            inspector.view.onCellSelected!!.invoke(changed.x, changed.y)
            val text = evidence(inspector, changed, false)
            assertTrue(text, text.contains("Unchanged: White (0)"))
            assertFalse(text.contains("Values shown after removing"))
            assertEquals(listOf(Color.WHITE), pixels(inspector, changed))
            inspector.dialog.dismiss()
        }
    }
}
