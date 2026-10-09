package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w412dp-h892dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResultScreenTrialTest {
    private fun candidate(text: String): Pair<QrMatrix, RecoveryCandidate> {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.M)
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        return matrix to RecoveryEngine().recover(matrix).first { it.payloadParsed && it.text == text }
    }

    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun refresh(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("refreshInspector").apply { isAccessible = true }.invoke(activity)
        MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply { isAccessible = true }.invoke(activity, null)
    }

    private fun install(activity: MainActivity, matrix: QrMatrix, results: List<RecoveryCandidate>) {
        field<RecoveryUiState>(activity, "recoveryState").apply {
            sourceMatrix = matrix.copy()
            workingMatrix = matrix.copy()
            geometry = GridGeometry((matrix.dimension - 17) / 4, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
            recoveries = results
            selectRecovery(0)
        }
        refresh(activity)
    }

    private fun withActivity(style: AppStyle, language: AppLanguage, action: (MainActivity) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        AppStylePreferences.save(app, style)
        AppLanguageManager.save(app, language)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            action(controller.get())
        } finally { controller.pause().stop().destroy() }
    }

    private fun expandDiagnosticDetails(activity: MainActivity) {
        val view = field<TextView>(activity, "diagnosticDetailsText")
        (view.text as android.text.Spanned).getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java)
            .forEach { it.onClick(view) }
    }

    @Test fun resultOrderAndClipboardPreserveFullContentInBothAppearancesAndLanguages() {
        val payload = "ABC-123 " + "1234567890".repeat(24)
        val (matrix, result) = candidate(payload)
        for (style in AppStyle.entries) for (language in AppLanguage.entries) withActivity(style, language) { activity ->
            install(activity, matrix, listOf(result))
            val preview = field<TextView>(activity, "contentPreviewText")
            val explanation = field<TextView>(activity, "resultExplanationText")
            val actions = field<View>(activity, "resultActions")
            val savePng = field<View>(activity, "resultSavePngButton")
            val diagnostics = field<TextView>(activity, "diagnosticDetailsText")
            val parent = preview.parent as ViewGroup
            assertTrue(preview.text.contains(payload))
            val styled = preview.text as android.text.Spanned
            val highlights = styled.getSpans(0, styled.length, android.text.style.BackgroundColorSpan::class.java)
            assertEquals(1, highlights.size)
            assertEquals(payload, styled.subSequence(styled.getSpanStart(highlights.single()), styled.getSpanEnd(highlights.single())).toString())
            assertFalse(preview.text.contains("HEX"))
            assertTrue(parent.indexOfChild(preview) < parent.indexOfChild(explanation))
            assertTrue(parent.indexOfChild(explanation) < parent.indexOfChild(actions))
            assertTrue(parent.indexOfChild(actions) < parent.indexOfChild(diagnostics))
            assertEquals(View.VISIBLE, savePng.visibility)
            assertEquals(View.GONE, diagnostics.visibility)
            field<Button>(activity, "resultCopyButton").performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(payload, clipboard.primaryClip!!.getItemAt(0).text.toString())
            field<Button>(activity, "diagnosticToggle").performClick()
            assertEquals(View.VISIBLE, diagnostics.visibility)
            expandDiagnosticDetails(activity)
            assertTrue(diagnostics.text.contains(if (language == AppLanguage.JAPANESE) "符号化モード" else "Encoding mode"))
        }
    }

    @Test fun recoveredTextRemainsExactInComparisonOverviewDiagnosticsAndClipboard() {
        val password = "trial-secret-7351"
        val payload = "CONFIG:T:TYPE;S:Trial;P:$password;;"
        val (matrix, result) = candidate(payload)
        val (_, other) = candidate("CONFIG:T:TYPE;S:Trial2;P:other-value;;")
        withActivity(AppStyle.MODERN, AppLanguage.JAPANESE) { activity ->
            install(activity, matrix, listOf(result, other))
            expandDiagnosticDetails(activity)
            for (name in listOf("contentPreviewText", "diagnosticDetailsText")) {
                val text = field<TextView>(activity, name).text.toString()
                assertTrue(text.contains(payload))
                assertFalse(text.contains("パスワード: "))
                assertFalse(text.contains("••••••••"))
            }
            assertTrue(candidatePairComparisonText(result, other, 1, 2).contains(password))
            field<Button>(activity, "resultCopyButton").performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(payload, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun comparisonExplainsTheDifferenceForAnySelectedPair() {
        val (_, first) = candidate("ABC-123")
        val (_, second) = candidate("ABC-128")
        withActivity(AppStyle.MODERN, AppLanguage.JAPANESE) {
            val comparison = candidatePairComparisonText(first, second, 3, 256)
            assertTrue(comparison.contains("候補3と候補256の比較"))
            assertTrue(comparison.contains("ABC-123"))
            assertTrue(comparison.contains("ABC-128"))
            assertTrue(comparison.contains("最初の違い"))
            assertFalse(comparison.contains("候補1:"))
        }
    }


    @Test fun candidateSelectionAcrossAttemptsUpdatesInputCopyPngAndSavedIdentity() {
        val (firstMatrix, first) = candidate("ABC-123")
        val (secondMatrix, second) = candidate("ABC-128")
        fun attempt(matrix: QrMatrix, candidate: RecoveryCandidate, origin: Double) = GeometryAttempt(
            GeometryHypothesis(emptyList(), GridGeometry(candidate.version, FinderRole.TOP_LEFT, origin, 12.0, 2.0, 0.0), origin),
            matrix, MatrixSampler.SamplingStats(128.0, 255.0, 0.0), listOf(candidate),
        )
        val normal = SamplingProfile(PreprocessSettings(), false)
        val inverted = normal.copy(inverted = true)
        val accumulator = RecoveryResultAccumulator()
        accumulator.add(attempt(firstMatrix, first, 10.0), normal)
        accumulator.add(attempt(secondMatrix, second, 50.0), inverted)
        withActivity(AppStyle.MODERN, AppLanguage.JAPANESE) { activity ->
            val state = field<RecoveryUiState>(activity, "recoveryState")
            state.showResults(accumulator.result())
            refresh(activity)
            assertEquals(2, state.selectableEntries.size)
            assertTrue(field<TextView>(activity, "statusText").text.contains("2件"))
            val targetIndex = state.recoveries.indexOfFirst { it.text == "ABC-128" }
            fun radios(view: View): List<android.widget.RadioButton> = when (view) {
                is android.widget.RadioButton -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { radios(view.getChildAt(it)) }
                else -> emptyList()
            }
            radios(field(activity, "upperResultSelector"))[targetIndex].performClick()
            assertEquals("ABC-128", state.selectedRecovery!!.text)
            assertEquals(50.0, state.geometry!!.finderCenterX, 0.0)
            assertTrue(state.samplingProfile!!.inverted)
            assertEquals(listOf(state.selectedRecovery), state.guidanceRecoveries)
            for (y in 0 until secondMatrix.dimension) for (x in 0 until secondMatrix.dimension)
                assertEquals(secondMatrix[x, y].value, state.sourceMatrix!![x, y].value)
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains("ABC-128"))
            field<Button>(activity, "resultCopyButton").performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("ABC-128", clipboard.primaryClip!!.getItemAt(0).text.toString())
            val png = MainActivity::class.java.getDeclaredMethod("matrixForPng").apply { isAccessible = true }.invoke(activity) as QrMatrix
            for (y in 0 until png.dimension) for (x in 0 until png.dimension)
                assertEquals(secondMatrix[x, y].value, png[x, y].value)
            val snapshot = MainActivity::class.java.getDeclaredMethod("currentSnapshot").apply { isAccessible = true }
                .invoke(activity) as RecoverySnapshot
            assertTrue(snapshot.selectedQr!!.matches(second))
            assertFalse(snapshot.selectedQr.matches(first))
            assertEquals(true, snapshot.samplingInverted)
            assertEquals(1, field<RecoverySessionHistory>(activity, "sessionHistory").entries.size)
        }
    }

    @Test fun candidateContentsListPreservesFullTextAndSelectsFromBothResultViews() {
        val payload = "https://example.org/?secret=" + "abcd".repeat(80)
        val (matrix, first) = candidate(payload)
        val (_, second) = candidate("byte payload")
        val displayedFirst = first.copy(formatMismatches = 2, knownBitMismatchesAfterCorrection = 7,
            manualDataEccMismatches = 1, correctedErrataSymbols = 3)
        for (style in AppStyle.entries) for (language in AppLanguage.entries) withActivity(style, language) { activity ->
            install(activity, matrix, listOf(first.copy(text = null, dataBytes = byteArrayOf(1)), displayedFirst, second.copy(text = null)))
            field<RecoveryUiState>(activity, "recoveryState").selectRecovery(1)
            refresh(activity)
            fun openList(root: View): AlertDialog {
                descendants(root).filterIsInstance<Button>().first {
                    it.text.toString() == if (language == AppLanguage.JAPANESE) "候補の内容一覧" else "Candidate contents"
                }.performClick()
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            val dialog = openList(field(activity, "upperResultSelector"))
            val list = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single()
            assertEquals(2, list.adapter.count)
            val firstRow = list.adapter.getView(0, null, list) as android.widget.RadioButton
            val firstMetrics = if (language == AppLanguage.JAPANESE)
                "  ―  データ・ECCの不一致: 7bit"
                else "  ―  Data/ECC mismatches: 7bit"
            assertEquals(activity.getString(R.string.result_tab, 1) + firstMetrics + "\n" + payload, firstRow.text.toString())
            assertTrue(firstRow.isChecked)
            val overview = field<TextView>(activity, "contentPreviewText").text as android.text.Spanned
            val overviewBackground = overview.getSpans(0, overview.length, android.text.style.BackgroundColorSpan::class.java).single()
            val overviewForeground = overview.getSpans(0, overview.length, android.text.style.ForegroundColorSpan::class.java).single()
            fun assertPayloadHighlight(row: TextView, expected: String) {
                val styled = row.text as android.text.Spanned
                val background = styled.getSpans(0, styled.length, android.text.style.BackgroundColorSpan::class.java).single()
                val foreground = styled.getSpans(0, styled.length, android.text.style.ForegroundColorSpan::class.java).single()
                val contentStart = styled.toString().indexOf('\n') + 1
                for (span in listOf(background, foreground)) {
                    assertEquals(contentStart, styled.getSpanStart(span))
                    assertEquals(styled.length, styled.getSpanEnd(span))
                    assertEquals(expected, styled.subSequence(styled.getSpanStart(span), styled.getSpanEnd(span)).toString())
                }
                assertEquals(overviewBackground.backgroundColor, background.backgroundColor)
                assertEquals(overviewForeground.foregroundColor, foreground.foregroundColor)
            }
            assertPayloadHighlight(firstRow, payload)
            val binaryRow = list.adapter.getView(1, firstRow, list) as TextView
            val secondMetrics = if (language == AppLanguage.JAPANESE)
                "  ―  データ・ECCの不一致: 0bit"
                else "  ―  Data/ECC mismatches: 0bit"
            assertEquals(activity.getString(R.string.result_tab, 2) + secondMetrics + "\nbyte payload", binaryRow.text.toString())
            assertPayloadHighlight(binaryRow, "byte payload")
            list.performItemClick(binaryRow, 1, list.adapter.getItemId(1))
            assertFalse(dialog.isShowing)
            assertEquals(2, field<RecoveryUiState>(activity, "recoveryState").recoveryIndex)
            MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
            val enlarged = ShadowDialog.getLatestDialog() as AlertDialog
            val contentDialog = openList(enlarged.window!!.decorView)
            val contents = descendants(contentDialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single()
            assertTrue((contents.adapter.getView(1, null, contents) as android.widget.RadioButton).isChecked)
            contents.performItemClick(contents.adapter.getView(0, null, contents), 0, contents.adapter.getItemId(0))
            assertEquals(1, field<RecoveryUiState>(activity, "recoveryState").recoveryIndex)
            val selectedTab = descendants(enlarged.window!!.decorView).filterIsInstance<android.widget.RadioButton>()
                .first { it.text.toString() == activity.getString(R.string.result_tab, 1) }
            assertTrue(selectedTab.isChecked)
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains(payload))
            enlarged.dismiss()
        }
    }

    @Test fun candidateContentsListCapsAt256AndRetainsDuplicateTextAndOriginalIndices() {
        val (_, result) = candidate("same text")
        withActivity(AppStyle.MODERN, AppLanguage.ENGLISH) { activity ->
            var selected = -1
            CandidateContentsDialog(activity, (0..256).map { (it * 2 + 1) to result }, 511) {
                selected = it
            }.show()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val list = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ListView>().single()
            assertEquals(256, list.adapter.count)
            val row = list.adapter.getView(255, null, list) as android.widget.RadioButton
            assertEquals(activity.getString(R.string.result_tab, 256) +
                "  ―  Data/ECC mismatches: 0bit\nsame text", row.text.toString())
            assertTrue(row.isChecked)
            list.performItemClick(row, 255, list.adapter.getItemId(255))
            assertEquals(511, selected)
        }
    }

    @Test fun binaryAndRsOnlyCandidatesHaveDifferentStatesAndNoTextCopy() {
        val (matrix, result) = candidate("ABC-123")
        withActivity(AppStyle.MODERN, AppLanguage.JAPANESE) { activity ->
            install(activity, matrix, listOf(result.copy(text = null)))
            assertTrue(field<TextView>(activity, "statusText").text.contains("QRのデータ形式を確認しました"))
            assertTrue(field<TextView>(activity, "diagnosticDetailsText").text.contains("指定条件を満たす修復候補が1件見つかりました。"))
            assertEquals(View.GONE, field<View>(activity, "resultCopyButton").visibility)
            assertEquals(View.VISIBLE, field<View>(activity, "resultSavePngButton").visibility)
            install(activity, matrix, listOf(result.copy(text = null, dataBytes = byteArrayOf(0x01))))
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains("誤り訂正の条件は満たしましたが"))
            assertTrue(field<TextView>(activity, "diagnosticDetailsText").text.contains("調べた範囲では修復候補は見つかりませんでした。"))
            assertEquals(View.GONE, field<View>(activity, "resultCopyButton").visibility)
            assertEquals(View.GONE, field<View>(activity, "resultSavePngButton").visibility)
            MainActivity::class.java.getDeclaredMethod("startSavePng").apply { isAccessible = true }.invoke(activity)
            assertTrue(field<TextView>(activity, "statusText").text.contains("検証済みの修復結果がない"))
        }
    }

    @Test fun startingAnotherRepairClearsPreviousResultsBeforeWorkerRuns() {
        val (matrix, result) = candidate("PREVIOUS-RESULT")
        for (style in AppStyle.entries) for (importAnotherImage in listOf(false, true)) withActivity(style, AppLanguage.ENGLISH) { activity ->
            var image = BitmapUtils.prepare(
                Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888))
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.set(activity, image)
            install(activity, matrix, listOf(result))
            assertEquals(View.VISIBLE, field<View>(activity, "resultExplanationText").visibility)
            val executor = field<ExecutorService>(activity, "executor")
            MainActivity::class.java.getDeclaredMethod("showStartMenu").apply { isAccessible = true }.invoke(activity)
            if (importAnotherImage) {
                val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
                MainActivity::class.java.getDeclaredMethod("acceptDecodedImage", Bitmap::class.java,
                    File::class.java, ImageAdjustment::class.java).apply { isAccessible = true }
                    .invoke(activity, bitmap, null, ImageAdjustment())
                repeat(2) {
                    executor.submit {}.get(30, TimeUnit.SECONDS)
                    shadowOf(Looper.getMainLooper()).idle()
                    field<ExecutorService>(activity, "ioExecutor")
                        .submit {}.get(30, TimeUnit.SECONDS)
                }
                ShadowDialog.getLatestDialog()?.dismiss()
                image = field(activity, "prepared")
                assertSame(bitmap, image.bitmap)
            }
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            executor.execute { started.countDown(); release.await() }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            try {
                MainActivity::class.java.getDeclaredMethod("startAutoRecovery").apply { isAccessible = true }.invoke(activity)
                assertTrue(ShadowDialog.getLatestDialog()!!.isShowing)
                assertFalse(field<TextView>(activity, "contentPreviewText").text.contains("PREVIOUS-RESULT"))
                for (name in listOf("resultActions", "resultNextAction", "resultExplanationText", "diagnosticToggle",
                    "upperResultSelector", "mainResultSelector")) {
                    assertEquals(name, View.GONE, field<View>(activity, name).visibility)
                }
                assertEquals(View.VISIBLE, field<View>(activity, "guideText").visibility)
                if (style == AppStyle.MODERN) {
                    val preview = field<View>(activity, "workspacePreview")
                    assertNull(preview.javaClass.getDeclaredField("matrix").apply { isAccessible = true }.get(preview))
                    assertSame(image.bitmap, preview.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(preview))
                }
            } finally {
                field<AtomicInteger>(activity, "workGeneration").incrementAndGet()
                release.countDown()
                executor.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun earlyStopShowsActionAndClearingAnAttemptHidesOldControls() {
        val (matrix, result) = candidate("ABC-123")
        for (style in AppStyle.entries) withActivity(style, AppLanguage.JAPANESE) { activity ->
            install(activity, matrix, listOf(result.copy(combinationSearch = CombinationSearchSummary(1, setOf(CombinationSearchStop.WORKING_MEMORY_LIMIT)))))
            assertTrue(field<TextView>(activity, "resultExplanationText").text.contains("未確認の組合せ"))
            val state = field<RecoveryUiState>(activity, "recoveryState")
            state.recoveries = emptyList()
            state.selectRecovery(-1)
            state.failure = RecoveryFailure(ErrorCorrectionLevel.M, 0, 0, 0, 0, RecoveryFailureStage.REED_SOLOMON,
                reason = RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT,
                searchDiagnostic = RecoverySearchDiagnostic(unsearched = true))
            refresh(activity)
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains("未探索の組合せ"))
            assertFalse(field<TextView>(activity, "contentPreviewText").text.contains("上限に達しました"))
            assertTrue(field<TextView>(activity, "guideText").text.startsWith("次に試すこと"))
            val next = field<View>(activity, "resultNextAction")
            assertEquals(View.VISIBLE, next.visibility)
            assertEquals(View.GONE, field<View>(activity, "resultSavePngButton").visibility)
            if (style == AppStyle.MODERN) {
                val panel = next.parent as ViewGroup
                assertTrue(panel.indexOfChild(next) < panel.indexOfChild(field<View>(activity, "workspacePreview")))
            }
            state.failure = null
            refresh(activity)
            for (name in listOf("resultActions", "resultNextAction", "resultExplanationText", "diagnosticToggle"))
                assertEquals(name, View.GONE, field<View>(activity, name).visibility)
        }
    }

    @Test fun renderSyntheticSuccessComparisonFailureAndCellEvidence() {
        val (matrix, result) = candidate("ABC-123")
        val (_, other) = candidate("ABC-128")
        withActivity(AppStyle.MODERN, AppLanguage.JAPANESE) { activity ->
            install(activity, matrix, listOf(result))
            render(activity.window.decorView, "trial-success")
            install(activity, matrix, listOf(result, other))
            render(activity.window.decorView, "trial-candidates")
            MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val inspector = descendants(dialog.window!!.decorView).filterIsInstance<io.github.earthdiver.qrrepair.ui.QrResultInspectorView>().single()
            inspector.focusCell(10, 10)
            inspector.onCellSelected?.invoke(10, 10)
            render(dialog.window!!.decorView, "trial-cell")
            dialog.dismiss()
            val state = field<RecoveryUiState>(activity, "recoveryState")
            state.recoveries = emptyList()
            state.selectRecovery(-1)
            state.failure = RecoveryFailure(ErrorCorrectionLevel.M, 0, 0, 0, 0, RecoveryFailureStage.REED_SOLOMON,
                reason = RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT)
            refresh(activity)
            render(activity.window.decorView, "trial-failure")
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 412, 892)
        val bitmap = Bitmap.createBitmap(412, 892, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let { path ->
            val directory = File(path).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }
}
