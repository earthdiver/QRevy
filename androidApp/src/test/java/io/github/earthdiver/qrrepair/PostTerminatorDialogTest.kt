package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostTerminatorDialogTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T
    private fun refresh(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("updateContentPreview")
        .apply { isAccessible = true }.invoke(activity)

    @Test fun traceExplainsUnsupportedEciBeforeTheSeparateSyntaxStopInBothLanguages() {
        val data = PayloadBits().apply {
            byteSegment("A".repeat(43)); put(0, 4); put(7, 4); put(51, 8); put(3, 4)
        }.finish(55)
        val report = PostTerminatorAnalysis.analyze(data, 3, ErrorCorrectionLevel.L)
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE)) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            AppLanguageManager.wrap(RuntimeEnvironment.getApplication())
            val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
            try {
                val dialog = showPostTerminatorDialog(controller.get(), PostTerminatorAnalysis.Attempt(listOf(
                    PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.OBSERVED, report)), false), 3, "L", 0)
                val text = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
                val japanese = language == AppLanguage.JAPANESE
                assertTrue(text.contains(if (japanese) "追加のQRセグメントと仮定" else "assumes additional QR segments"))
                assertTrue(text.contains(if (japanese) "最初の未対応指定: bit 360" else "First unsupported designation: bit 360"))
                assertTrue(text.contains("bit 360–371 / ECI / 0111"))
                assertTrue(text.contains(if (japanese) "指定値 51" else "assignment 51"))
                assertTrue(text.contains(if (japanese) "構文解析の停止位置（項目の先頭）: bit 372" else "Syntax parsing stopped at item starting at bit 372"))
                assertTrue(text.contains(if (japanese) "この位置では使用できない制御指定" else "Control header is not allowed in this context"))
                assertTrue(text.contains("bit 372–375 / Structured Append / 0011"))
                assertFalse(text.contains(if (japanese) "読み取れなかったセグメントの開始位置" else "Unreadable segment starts"))
                dialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun partiallyDecodedGroupKeepsIndividualTextAndRawBytesSelectable() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        AppLanguageManager.wrap(RuntimeEnvironment.getApplication())
        val data = PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(7, 4); put(51, 8); byteSegment("B")
            put(7, 4); put(26, 8); byteSegment("人間")
        }.finish(40)
        val report = PostTerminatorAnalysis.analyze(data, 1, ErrorCorrectionLevel.L)
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val dialog = showPostTerminatorDialog(controller.get(), PostTerminatorAnalysis.Attempt(listOf(
                PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.OBSERVED, report)), false), 1, "L", 0)
            val texts = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
            assertTrue(texts.single { it.text.toString() == "人間" }.isTextSelectable)
            assertTrue(texts.single { it.text.toString() == "HEX: 42" }.isTextSelectable)
            assertFalse(texts.any { it.text.toString() == "B人間" || it.text.toString() == "AB人間" })
            assertTrue(texts.any { it.text.contains("unsupported ECI is active") })
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun resultActionShowsHiddenTextForFailedAttemptsInBothStylesAndLanguages() {
        val secret = "tail\u0000text"
        val data = PayloadBits().apply { byteSegment("A"); put(0, 4); byteSegment(secret) }.finish()
        val matrix = postTerminatorMatrix(data)
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        val failure = requireNotNull(outcome.failure)
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            AppLanguageManager.save(app, language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val activity = controller.get()
                val state = field<RecoveryUiState>(activity, "recoveryState")
                state.showAttempt(0, GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), matrix,
                    emptyList(), failure, -1)
                refresh(activity)
                val body = field<TextView>(activity, "contentPreviewText").text as android.text.Spanned
                val bodyHighlights = body.getSpans(0, body.length, android.text.style.BackgroundColorSpan::class.java)
                assertEquals(1, bodyHighlights.size)
                assertEquals("A", body.subSequence(body.getSpanStart(bodyHighlights.single()), body.getSpanEnd(bodyHighlights.single())).toString())
                assertTrue(body.contains("HEX"))
                assertTrue(body.contains("41"))
                assertFalse(body.contains(secret))
                val actions = field<View>(activity, "resultActions")
                assertEquals(View.VISIBLE, actions.visibility)
                val button = field<Button>(activity, "resultTailAnalysisButton")
                assertEquals(activity.getString(R.string.analyze_post_terminator), button.text)
                assertEquals(View.VISIBLE, button.visibility)
                val panel = button.parent as ViewGroup
                assertTrue(panel.indexOfChild(field<View>(activity, "diagnosticToggle")) < panel.indexOfChild(button))
                assertTrue(panel.indexOfChild(field<View>(activity, "diagnosticView")) < panel.indexOfChild(button))
                assertFalse(button.parent === actions)
                button.performClick()
                field<ExecutorService>(activity, "ioExecutor").submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                val views = descendants(dialog.window!!.decorView)
                val extracted = views.filterIsInstance<TextView>().single { it.text.toString() == secret }
                val styled = extracted.text as android.text.Spanned
                val highlights = styled.getSpans(0, styled.length, android.text.style.BackgroundColorSpan::class.java)
                assertEquals(1, highlights.size)
                assertEquals(0, styled.getSpanStart(highlights.single()))
                assertEquals(secret.length, styled.getSpanEnd(highlights.single()))
                val combined = views.filterIsInstance<TextView>().single { it.text.toString() == "A" + secret }
                assertTrue(combined.isTextSelectable)
                val expectedHex = ("A" + secret).toByteArray().joinToString(" ") { "%02X".format(it.toInt() and 255) }
                assertTrue(views.filterIsInstance<TextView>().single { it.text.toString() == expectedHex }.isTextSelectable)
                val radios = views.filterIsInstance<RadioButton>()
                assertEquals(2, radios.size)
                assertTrue(radios[1].isChecked)
                radios[0].performClick()
                assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == secret })
                assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "A" + secret })
                assertNull(state.selectedRecovery)
                assertSame(failure, state.failure)
                assertEquals(View.GONE, field<View>(activity, "resultSavePngButton").visibility)
                dialog.dismiss()
                state.failure = failure.copy(conventionalRsDiagnostic = failure.conventionalRsDiagnostic!!.copy(
                    knownBitMismatches = 1, knownBitMismatchLimit = 0))
                refresh(activity)
                assertFalse(field<TextView>(activity, "contentPreviewText").text.contains("HEX"))
                state.clearAttempt()
                refresh(activity)
                assertEquals(View.GONE, actions.visibility)
                assertEquals(View.GONE, button.visibility)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun switchesInterpretationsAndRawOffsetsWithoutMixingTextOrFillingPartialBytes() {
        for (language in listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE)) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            AppLanguageManager.wrap(RuntimeEnvironment.getApplication())
            val data = PayloadBits().apply {
                put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1); byteSegment("A@")
            }.finish()
            val original = data.copyOf()
            val report = PostTerminatorAnalysis.analyze(data, 1, ErrorCorrectionLevel.L)
            val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
            try {
                val dialog = showPostTerminatorDialog(controller.get(), PostTerminatorAnalysis.Attempt(listOf(
                    PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.OBSERVED, report)), false), 1, "L", 0)
                fun views() = descendants(dialog.window!!.decorView)
                fun values() = views().filterIsInstance<TextView>().map { it.text.toString() }
                val japanese = language == AppLanguage.JAPANESE
                assertTrue(values().any { it.contains(if (japanese)
                    "追加セグメントの開始位置を選択してください" else "Choose the starting position of the additional segments") })
                assertTrue(values().any { it.contains(if (japanese)
                    "本文のデータの先頭を bit 0" else "bit 0 of the main payload data") })
                val choices = views().filterIsInstance<android.widget.Spinner>().first().adapter
                for (i in 0 until choices.count) {
                    val candidate = (listOf(report) + report.alternatives)[i]
                    assertTrue(choices.getItem(i).toString().contains("bit ${candidate.boundaries.first().startBit}"))
                }
                assertTrue("BR" in values())
                assertFalse("A@" in values())
                views().filterIsInstance<android.widget.Spinner>().first().setSelection(1)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("A@" in values())
                assertFalse("BR" in values())
                assertFalse("ABR" in values())
                views().filterIsInstance<Button>().single {
                    it.text == if (japanese) "終端後の生データを表示／閉じる" else "Show/hide raw tail bits and HEX"
                }.performClick()
                val basis = views().filterIsInstance<android.widget.Spinner>().last()
                basis.setSelection(1)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(values().any { it.contains("24: 01000000  40") })
                basis.setSelection(2)
                shadowOf(Looper.getMainLooper()).idle()
                val offset = views().filterIsInstance<android.widget.EditText>().single()
                val show = views().filterIsInstance<Button>().single {
                    it.text == if (japanese) "指定位置から表示" else "Show from starting bit"
                }
                assertTrue(offset.isEnabled)
                val keyboard = controller.get().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                fun openKeyboard() {
                    offset.requestFocus()
                    keyboard.showSoftInput(offset, 0)
                    assertTrue(shadowOf(keyboard).isSoftInputVisible)
                }
                openKeyboard()
                offset.setText("36")
                offset.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                assertFalse(shadowOf(keyboard).isSoftInputVisible)
                assertTrue(values().any { it.contains("36: 01000001  41") && it.contains("148: 0001  --") })
                openKeyboard()
                offset.setText("40")
                offset.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                offset.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
                assertFalse(shadowOf(keyboard).isSoftInputVisible)
                assertTrue(values().any { it.contains("40: ") })
                openKeyboard()
                offset.setText("22")
                offset.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                assertNotNull(offset.error)
                assertTrue(shadowOf(keyboard).isSoftInputVisible)
                assertTrue(values().any { it.contains("40: ") })
                offset.setText("36"); show.performClick()
                assertFalse(shadowOf(keyboard).isSoftInputVisible)
                offset.setText("22"); show.performClick()
                assertNotNull(offset.error)
                assertTrue(values().any { it.contains("36: 01000001  41") })
                basis.setSelection(0)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(values().any { it.contains("23: 00100000  20") })
                assertArrayEquals(original, data)
                dialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun showsOpaqueModeAndPaddingEvidenceAndPreservesUnknownRawBits() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        AppLanguageManager.wrap(RuntimeEnvironment.getApplication())
        val data = PayloadBits().apply {
            put(2, 4); put(1, 9); put(10, 6); put(0, 4); put(0, 1)
            put(0, 4); put(0, 4); put(12, 4); put(0x55, 8); put(0xA6, 8)
        }.finish()
        val unknown = IntArray(data.size).apply { this[5] = 1 }
        val report = PostTerminatorAnalysis.analyze(data, 1, ErrorCorrectionLevel.L, unknown)
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val dialog = showPostTerminatorDialog(controller.get(), PostTerminatorAnalysis.Attempt(listOf(
                PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.OBSERVED, report)), false), 1, "L", 0)
            fun views() = descendants(dialog.window!!.decorView)
            fun values() = views().filterIsInstance<TextView>().map { it.text.toString() }
            views().filterIsInstance<android.widget.Spinner>().first().setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(values().any { it.contains("bit 32 / Mode (data format): 1100") && it.contains("Unsupported") })
            assertTrue(values().any { it.contains("From the next byte at bit 24, skipping any consecutive 0000 sequences") })
            assertTrue(values().any { it.contains("bits 23–31 / 9 bits (all confirmed zero; retained in raw data)") })
            val skippedIndex = values().indexOfFirst { it.startsWith("Skipped during parsing:") }
            val startIndex = values().indexOfFirst { it.startsWith("Additional segment starting position:") }
            assertTrue(skippedIndex >= 0 && skippedIndex < startIndex)
            assertTrue(values().any { it.contains("alternating EC / 11 from bit 56") })
            views().filterIsInstance<Button>().single { it.text == "Show/hide raw tail bits and HEX" }.performClick()
            val basis = views().filterIsInstance<android.widget.Spinner>().last()
            basis.setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(values().any { it.contains("40: 0101101?  --") })
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun selectingDifferentSourcesHidesIneligibleCombinedContent() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        val same = PostTerminatorAnalysis.analyze(PayloadBits().apply {
            byteSegment("A"); put(0, 4); byteSegment("B")
        }.finish(), 1, ErrorCorrectionLevel.L)
        val mixed = PostTerminatorAnalysis.analyze(PayloadBits().apply {
            byteSegment("A"); put(0, 4); put(1, 4); put(3, 10); put(123, 10)
        }.finish(), 1, ErrorCorrectionLevel.L)
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val dialog = showPostTerminatorDialog(controller.get(), PostTerminatorAnalysis.Attempt(listOf(
                PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.OBSERVED, mixed),
                PostTerminatorAnalysis.View(PostTerminatorAnalysis.Source.CONVENTIONAL_RS, same),
            ), false), 1, "L", 0)
            fun values() = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("AB" in values())
            assertTrue("41 42" in values())
            descendants(dialog.window!!.decorView).filterIsInstance<RadioButton>().first().performClick()
            assertFalse("AB" in values())
            assertFalse("41 42" in values())
            assertTrue("123" in values())
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun beforeRepairPartialDataShowsEciBodyWhileAfterRepairRemainsRestricted() {
        val matrix = postTerminatorMatrix(PayloadBits().apply {
            put(7, 4); put(26, 8); byteSegment("人間"); put(0, 4)
            byteSegment("万事"); put(0, 4); byteSegment("塞翁が馬")
        }.finish(34), version = 2)
        val engine = RecoveryEngine()
        val outcome = engine.recoverWithDiagnostics(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0),
            maxKnownDataEccMismatches = 0)
        assertTrue(outcome.candidates.isEmpty())
        val app = RuntimeEnvironment.getApplication()
        AppLanguageManager.save(app, AppLanguage.JAPANESE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            val activity = controller.get()
            field<RecoveryUiState>(activity, "recoveryState").showAttempt(0,
                GridGeometry(2, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), matrix,
                emptyList(), requireNotNull(outcome.failure), -1)
            MainActivity::class.java.getDeclaredMethod("showCodewordHex").apply { isAccessible = true }.invoke(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val toggles = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.ToggleButton>()
            toggles.single { it.textOff == activity.getString(R.string.raw_data_view) }.isChecked = true
            fun content() = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .map { it.text.toString() }.first { it.contains("未検証の診断情報") || it.contains("修復後候補はありません") }
            assertTrue(content().contains("Text: 人間"))
            assertTrue(content().contains("HEX: E4 BA BA E9 96 93"))
            assertFalse(content().contains("万事"))
            toggles.single { it.textOff == "制約・RS修復前" }.isChecked = true
            assertTrue(content().contains("修復後候補はありません"))
            assertFalse(content().contains("Text: 人間"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun analysisActionIsHiddenForSuccessAndOtherFailureReasons() {
        val matrix = postTerminatorMatrix(PayloadBits().apply { byteSegment("VISIBLE") }.finish())
        val candidate = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.L, 0)).first()
        for (style in AppStyle.entries) {
            val app = RuntimeEnvironment.getApplication()
            AppStylePreferences.save(app, style)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
                val activity = controller.get()
                val state = field<RecoveryUiState>(activity, "recoveryState")
                val structureFailure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 0, 0, 0, RecoveryFailureStage.PAYLOAD_VALIDATION)
                state.showAttempt(0, GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0), matrix,
                    emptyList(), structureFailure, -1)
                refresh(activity)
                val button = field<Button>(activity, "resultTailAnalysisButton")
                assertEquals(View.VISIBLE, button.visibility)
                field<Button>(activity, "diagnosticToggle").performClick()
                assertEquals(View.VISIBLE, button.visibility)
                for (stage in RecoveryFailureStage.entries.filter { it != RecoveryFailureStage.PAYLOAD_VALIDATION }) {
                    state.failure = structureFailure.copy(stage = stage)
                    refresh(activity)
                    assertEquals(stage.name, View.GONE, button.visibility)
                }
                for (reason in RecoveryFailureReason.entries) {
                    state.failure = structureFailure.copy(reason = reason)
                    refresh(activity)
                    assertEquals(reason.name, View.GONE, button.visibility)
                }
                state.failure = structureFailure
                state.recoveries = listOf(candidate)
                state.selectRecovery(0)
                refresh(activity)
                assertEquals(View.GONE, button.visibility)
                state.selectRecovery(-1)
                refresh(activity)
                assertEquals(View.VISIBLE, button.visibility)
                state.clearAttempt()
                refresh(activity)
                assertEquals(View.GONE, button.visibility)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
