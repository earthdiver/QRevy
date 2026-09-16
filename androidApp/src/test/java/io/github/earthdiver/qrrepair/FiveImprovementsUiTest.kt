package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView
import io.github.earthdiver.qrrepair.ui.ResultActionRow
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
class FiveImprovementsUiTest {
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T
    private fun call(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a)
    private fun measure(v: View, width: Int = 360, height: Int = 640) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
    }
    private fun candidates(): Pair<QrMatrix, List<RecoveryCandidate>> {
        var source: QrMatrix? = null
        val results = listOf("ABC-123", "ABC-128", "ABD-128").map { text ->
            val qr = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 2))
            val matrix = QrMatrix(qr.matrix.width).apply {
                for (y in 0 until dimension) for (x in 0 until dimension)
                    set(x, y, if (qr.matrix.get(x,y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
            }
            if (source == null) source = matrix
            RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 2)).first { it.text == text }
        }
        // Deliberately install three independently generated candidates to exercise navigation only.
        return source!! to results
    }
    @Test fun candidateComparisonAndReadingPositionSurviveSwitchesInBothLanguagesAndStyles() {
        val (matrix, results) = candidates()
        for (style in AppStyle.entries) for (lang in AppLanguage.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style); AppLanguageManager.save(app, lang)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a = controller.get()
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val state = field<RecoveryUiState>(a, "recoveryState")
                state.sourceMatrix = matrix.copy(); state.workingMatrix = matrix.copy()
                state.geometry = GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
                state.recoveries = results; state.selectRecovery(0)
                call(a, "refreshInspector")
                MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply { isAccessible = true }.invoke(a, null)
                val root = a.window.decorView
                measure(root)
                val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
                assertFalse(labels.any { it == "データを確認（HEX / Binary）" || it == "View data (HEX / Binary)" })
                assertTrue(labels.contains(a.getString(R.string.data)))
                val upper = field<LinearLayout>(a, "upperResultSelector")
                descendants(upper).filterIsInstance<TextView>().first { it.text.startsWith(if (lang == AppLanguage.JAPANESE) "比較相手" else "Compare with:") }.performClick()
                val chooser = ShadowDialog.getLatestDialog() as AlertDialog
                chooser.listView.performItemClick(chooser.listView.getChildAt(1), 1, 1)
                assertTrue(descendants(upper).filterIsInstance<TextView>().any {
                    it.text.contains(if (lang == AppLanguage.JAPANESE) "候補1と候補3の比較" else "Compare candidates 1 and 3") })
                val diagnostic = field<TextView>(a, "diagnosticDetailsText")
                (diagnostic.text as Spanned).getSpans(0, diagnostic.text.length, ClickableSpan::class.java).first().onClick(diagnostic)
                val openStages = field<MutableSet<DiagnosticStage>>(a, "expandedDiagnosticStages").toSet()
                assertTrue(openStages.isNotEmpty())
                call(a, "showZoomableResult")
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val decor = dialog.window!!.decorView
                measure(decor)
                val view = descendants(decor).filterIsInstance<QrResultInspectorView>().single()
                view.onCellSelected!!.invoke(12, 8)
                measure(decor)
                view.focusCell(12, 8)
                val technical = descendants(decor).filterIsInstance<TextView>().first { it.text.toString() == if (lang == AppLanguage.JAPANESE) "計算の説明を表示／閉じる" else "Show/hide calculation details" }
                val word = descendants(decor).filterIsInstance<TextView>().first { it.text.toString() == if (lang == AppLanguage.JAPANESE) "同じコード語を表示" else "Show same codeword" }
                assertEquals(View.GONE, word.visibility)
                technical.performClick()
                assertEquals(View.VISIBLE, word.visibility)
                measure(decor)
                fun transform() = listOf("scale", "offsetX", "offsetY").map { name ->
                    QrResultInspectorView::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(view) }
                val position = transform()
                word.performClick()
                fun highlighted() = QrResultInspectorView::class.java.getDeclaredField("highlightedCodeword")
                    .apply { isAccessible = true }.get(view) as Set<*>
                assertEquals(8, highlighted().size)
                val tabs = descendants(decor).filterIsInstance<RadioButton>().filter { it.text.toString() == a.getString(R.string.result_tab, 2) }
                tabs.single().performClick()
                measure(decor)
                assertEquals(position, transform())
                assertEquals(8, highlighted().size)
                val evidence = descendants(decor).filterIsInstance<TextView>().single {
                    it.text.startsWith(if (lang == AppLanguage.JAPANESE) "セル (12,8)" else "Cell (12,8)") }
                val maskLabel = if (lang == AppLanguage.JAPANESE) "マスク解除後" else "After removing the QR mask"
                assertEquals(1, evidence.text.toString().split(maskLabel).size - 1)
                val raw = results[1].extraction.codewords.indexOfFirst { Coord(12,8) in it.coordinates }
                val bit = results[1].extraction.codewords[raw].coordinates.indexOf(Coord(12,8))
                val expected = MainActivity::class.java.getDeclaredMethod("codewordDetailText",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(a, raw, bit) as String
                assertTrue(evidence.text.endsWith(expected))
                technical.performClick()
                assertTrue(highlighted().isEmpty())
                assertFalse(evidence.text.contains(maskLabel))
                assertEquals(View.GONE, word.visibility)
                technical.performClick()
                assertEquals(View.VISIBLE, word.visibility)
                assertEquals(Coord(12,8), QrResultInspectorView::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(view))
                assertEquals(openStages, field<MutableSet<DiagnosticStage>>(a, "expandedDiagnosticStages").toSet())
                assertTrue(descendants(decor).filterIsInstance<TextView>().any { it.text.contains(if (lang == AppLanguage.JAPANESE) "セル (12,8)" else "Cell (12,8)") })
                assertFalse(descendants(decor).filterIsInstance<TextView>().any { it.text.toString() in listOf("入力画面で編集", "Edit in input screen") })
                assertTrue(view.height >= 160)
                render(decor, "five-detail-${style.name}-${lang.tag}")
                state.geometry = state.geometry!!.copy(angleRad = 1.0)
                descendants(decor).filterIsInstance<RadioButton>().first { it.text.toString() == a.getString(R.string.result_tab, 3) }.performClick()
                assertNull(QrResultInspectorView::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(view))
                assertTrue(field<MutableSet<DiagnosticStage>>(a, "expandedDiagnosticStages").isEmpty())
                dialog.dismiss()
                measure(root)
                render(root, "five-result-${style.name}-${lang.tag}")
            } finally { controller.pause().stop().destroy() }
        }
    }
    @Test fun actionLabelsStackInsteadOfBeingSqueezedAtLargeTextSizes() {
        val app = RuntimeEnvironment.getApplication()
        val row = ResultActionRow(app)
        repeat(2) { row.addView(TextView(app).apply { text = "Copy full content"; textSize = 28f }) }
        measure(row, 320, 300)
        assertEquals(LinearLayout.VERTICAL, row.orientation)
        measure(row, 1000, 300)
        assertEquals(LinearLayout.HORIZONTAL, row.orientation)
    }
    @Test fun cellEvidenceSeparatesUnchangedBitFromCodewordRepairInBothLanguages() {
        val (source, candidates) = candidates()
        for (lang in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), lang)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val a = controller.get()
                val state = field<RecoveryUiState>(a, "recoveryState")
                val coord = Coord(12, 12)
                val matrix = source.copy().apply { set(12, 12, ModuleValue.ONE, 1f, ModuleSource.RECOVERED) }
                state.sourceMatrix = matrix.copy()
                state.recoveries = candidates; state.selectRecovery(0)
                val trace = ModuleRepairTrace(12, 12, ModuleValue.ONE, ModuleValue.ONE,
                    ModuleSource.IMAGE, ModuleSource.RECOVERED, RepairReasonKind.RS_ERROR_CORRECTION,
                    codewordIndex = 5, bitIndex = 2, rsBlockIndex = 0, rsLocalIndex = 3,
                    originalCodewordValue = 0xa6, correctedCodewordValue = 0xa7, rsErrata = true,
                    alternativeRsDerivations = listOf(PartialCodewordSolver.Derivation(listOf(2), 0, 0, emptyList(), 4, 0, false)))
                state.traces = mapOf(coord to trace)
                state.confidenceMap = mapOf(coord to CellConfidence(.8f, "中", "", "Medium", "", 2, 3))
                fun text(method: String): String = MainActivity::class.java.getDeclaredMethod(method,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, QrMatrix::class.java)
                    .apply { isAccessible = true }.invoke(a, 12, 12, matrix) as String
                val basic = text("resultCellDetailText")
                assertTrue(basic.contains(if (lang == AppLanguage.JAPANESE) "黒（1）のまま" else "Unchanged: Black (1)"))
                assertFalse(basic.contains(if (lang == AppLanguage.JAPANESE) "白黒を変更" else "changed the value"))
                assertTrue(basic.contains(if (lang == AppLanguage.JAPANESE) "3候補中2候補" else "2 of 3"))
                assertFalse(basic.contains(if (lang == AppLanguage.JAPANESE) "同じRSブロック" else "same RS block"))
                val extra = text("resultCellTechnicalText")
                assertFalse(extra.contains(if (lang == AppLanguage.JAPANESE) "セル (12,12)" else "Cell (12,12)"))
                assertTrue(extra.contains(if (lang == AppLanguage.JAPANESE) "同じRSブロックの別の計算" else "Other calculations for the same RS block"))
                assertTrue(extra.contains(if (lang == AppLanguage.JAPANESE) "このコード語は訂正対象に含みません" else "does not target this codeword"))
                state.traces = mapOf(coord to trace.copy(originalValue = ModuleValue.ERASED,
                    primaryReason = RepairReasonKind.RS_ERASURE_RECOVERY, bitConstraintSolved = true))
                assertTrue(text("resultCellDetailText").contains(if (lang == AppLanguage.JAPANESE) "部分bit制約で補いました" else "Partial-bit constraints filled"))
            } finally { controller.pause().stop().destroy() }
        }
    }
    private fun render(v: View, name: String) {
        System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let {
            val dir = File(it).apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
    }
}
