package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
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
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class QuickAutoFailureUiTest {
    @Test fun everyFailureHasALocalizedReasonAndOnlyTimeoutOffersAnExtensionWithoutLogs() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val source = BitmapUtils.prepare(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
                field("prepared").set(activity, source)
                field("analysisPrepared").set(activity, source)
                val session = AutomaticRepairDiagnosticSession(source.luma, PreprocessSettings(), "")
                field("automaticDiagnostic").set(activity, session)
                for (stage in 0..2) {
                    for (reason in QuickAutoFailureReason.entries) {
                        show(activity, reason)
                        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                        assertTrue(shadowOf(dialog).message.toString().contains(reason.message()))
                        val buttonLabels = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                            .map { dialog.getButton(it) }.filter { it.visibility == View.VISIBLE }.map { it.text.toString() }
                        assertFalse(buttonLabels.contains("診断をコピー"))
                        assertFalse(buttonLabels.contains("Copy diagnostics"))
                        val offered = reason == QuickAutoFailureReason.TIME_LIMIT && stage < 2
                        assertEquals(offered,
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility == View.VISIBLE)
                        if (offered) assertEquals(when {
                            stage == 0 && language == AppLanguage.JAPANESE -> "時間枠を広げて再試行（10秒）"
                            stage == 0 -> "Retry with more time (10 seconds)"
                            language == AppLanguage.JAPANESE -> "時間制限なしで再試行"
                            else -> "Retry without time limits"
                        }, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
                        assertEquals(if (language == AppLanguage.JAPANESE) "詳細解析へ" else "Detailed analysis",
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                        dialog.dismiss()
                    }
                    if (stage < 2) session.extend(AutomaticScanTrace(), { false },
                        estimateGrid = { _, _, _, _ -> null }, recover = { _, _, _ ->
                            QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()),
                                failureReason = QuickAutoFailureReason.TIME_LIMIT)
                        })
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun extensionSuccessUsesTheNormalResultAndPreservesDetailedSettings() {
        AppLanguageManager.save(RuntimeEnvironment.getApplication(), AppLanguage.ENGLISH)
        for (unlimited in listOf(false, true)) {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val encoded = Encoder.encode("extended retry", ErrorCorrectionLevel.L).matrix
                val matrix = QrMatrix(encoded.width).apply {
                    for (y in 0 until dimension) for (x in 0 until dimension)
                        set(x, y, if (encoded[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                }
                val source = BitmapUtils.prepare(BitmapUtils.renderTextMatrixSource(matrix))
                val a = 35.5; val b = a + (matrix.dimension - 1) * 8
                val saved = PreprocessSettings(version = (matrix.dimension - 17) / 4, automaticThresholds = false,
                    blackMax = 64, whiteMin = 192, lmCorrectionEnabled = false,
                    manualQuad = ImageQuad(PointD(a, a), PointD(b, a), PointD(b, b), PointD(a, b))).withInitialErrorLimits()
                field("prepared").set(activity, source)
                field("analysisPrepared").set(activity, source)
                field("preprocessSettings").set(activity, saved)
                val session = AutomaticRepairDiagnosticSession(source.luma, saved, "", textMatrix = true)
                field("automaticDiagnostic").set(activity, session)
                if (unlimited) session.extend(AutomaticScanTrace(), { false }, recover = { _, _, _ ->
                    QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList()), failureReason = QuickAutoFailureReason.TIME_LIMIT)
                })
                show(activity, QuickAutoFailureReason.TIME_LIMIT)
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                repeat(3) {
                    (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
                    shadowOf(Looper.getMainLooper()).idle()
                }
                assertEquals(saved, field("preprocessSettings").get(activity))
                assertEquals("extended retry", (field("recoveryState").get(activity) as RecoveryUiState).selectedRecovery?.text)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(shadowOf(dialog).message.toString().contains("extended retry"))
                assertEquals("Copy content", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
                assertEquals(if (unlimited) null else QuickAutoRetryMode.UNLIMITED, session.nextRetry)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun failureDialogExplainsTheRecordedCalculationInsteadOfOnlySayingUnresolved() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val diagnostic = RecoverySearchDiagnostic(unresolvedCalculations = true,
                    formatOutcomes = listOf(RecoveryFormatOutcome(ErrorCorrectionLevel.M, 1,
                        RecoveryFormatState.UNRESOLVED, false, "BIT_SPACE_LIMIT")))
                show(controller.get(), QuickAutoFailureReason.UNRESOLVED, diagnostic)
                val message = shadowOf(ShadowDialog.getLatestDialog() as AlertDialog).message.toString()
                assertTrue(message, message.contains(if (language == AppLanguage.JAPANESE)
                    "不明ビットの組合せが、この計算で列挙する範囲を超えました" else "Unknown-bit combinations exceeded this calculation's enumeration range"))
                assertFalse(message.contains(if (language == AppLanguage.JAPANESE) "未解決の処理" else "unresolved processing"))
                assertFalse(message.contains(SearchCompleteness.COMPLETE.description()))
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun show(activity: MainActivity, reason: QuickAutoFailureReason, diagnostic: RecoverySearchDiagnostic? = null) {
        MainActivity::class.java.getDeclaredMethod("showAutomaticResult", QuickAutoRecoveryResult::class.java)
            .apply { isAccessible = true }.invoke(activity,
                QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), emptyList(), searchDiagnostic = diagnostic), failureReason = reason))
    }
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
