package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticHistoryResumeTest {
    @Test fun emptyAutomaticTrialsRetainTheirConditionsAcrossSessionSavingAndRepeatedResume() {
        for (assumption in AutomaticScanAssumption.entries) {
            val extension = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS) 1 else 2
            for (withFailure in listOf(false, true)) {
                val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
                try {
                    val activity = controller.get()
                    ShadowDialog.getLatestDialog()?.dismiss()
                    val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0).asProjective()
                    val saved = PreprocessSettings(manualQuad = geometry.imageQuad, lmCorrectionEnabled = false,
                        assumeUtf8ByteSegments = false, trustKnownPayloadCount = false, knownBitErrorLimit = 2,
                        formatMismatchLimitMode = ErrorLimitMode.UNLIMITED,
                        dataEccMismatchLimitMode = ErrorLimitMode.UNLIMITED)
                    val actual = QuickAutoRecovery.executionSettings(saved, assumption, extension)
                    val matrix = QrMatrix(21).apply {
                        for (y in 0 until dimension) for (x in 0 until dimension)
                            set(x, y, ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
                    }
                    val policy = if (assumption == AutomaticScanAssumption.EXACT_KNOWN_BITS)
                        RecoveryExecutionPolicy.AUTOMATIC_EXACT else RecoveryExecutionPolicy.AUTOMATIC_EXTENDED
                    val outcome = RecoveryEngine().recoverWithDiagnostics(matrix,
                        RecoveryRequest.fromSettings(actual).copy(executionPolicy = policy))
                    assertTrue(outcome.candidates.isEmpty())
                    assertNotNull(outcome.failure)
                    val source = BitmapUtils.prepare(Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(Color.WHITE) })
                    field("prepared").set(activity, source)
                    field("analysisPrepared").set(activity, source)
                    field("preprocessSettings").set(activity, saved)
                    val attempt = GeometryAttempt(GeometryHypothesis(emptyList(), geometry, 0.0), matrix,
                        MatrixSampler.SamplingStats(255.0, 0.0, 0.0), emptyList(),
                        outcome.failure.takeIf { withFailure }, outcome.searchDiagnostic)
                    val result = QuickAutoRecoveryResult(AutoRecoveryResult(emptyList(), listOf(attempt),
                        SamplingProfile(actual, true)), failureReason = QuickAutoFailureReason.NO_CANDIDATE)
                    MainActivity::class.java.getDeclaredMethod("retainAutomaticResult", QuickAutoRecoveryResult::class.java)
                        .apply { isAccessible = true }.invoke(activity, result)
                    val history = activity.workspaceSessionForTest().history.current!!
                    assertEquals(localizedText("簡易自動修復", "Quick automatic repair"), history.label)
                    val payload = RecoverySessionCodec.Payload("empty-automatic", 1L, UiMode.ANALYSIS, listOf(history), 0)
                    var restored = RecoverySessionCodec.decode(RecoverySessionCodec.encode(payload)).historyEntries.single()
                    repeat(2) {
                        assertEquals(assumption, restored.snapshot.automaticScanAssumption)
                        assertEquals(extension, restored.snapshot.automaticScanListExtension)
                        assertEquals(saved, restored.snapshot.preprocessSettings)
                        assertEquals(true, restored.snapshot.samplingInverted)
                        assertNull(restored.snapshot.selectedQr)
                        MainActivity::class.java.getDeclaredMethod("restoreSnapshot", RecoveryHistoryEntry::class.java,
                            String::class.java, Function0::class.java)
                            .apply { isAccessible = true }.invoke(activity, restored, "Restored", null)
                        (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
                        shadowOf(Looper.getMainLooper()).idle()
                        val state = field("recoveryState").get(activity) as RecoveryUiState
                        assertTrue(state.quickAutomatic)
                        assertNull(state.selectedRecovery)
                        assertEquals(outcome.failure!!.displayConditions, state.failure!!.displayConditions)
                        assertEquals(saved, field("preprocessSettings").get(activity))
                        val snapshot = MainActivity::class.java.getDeclaredMethod("currentSnapshot")
                            .apply { isAccessible = true }.invoke(activity) as RecoverySnapshot
                        restored = restored.copy(snapshot = snapshot)
                    }
                    val state = field("recoveryState").get(activity) as RecoveryUiState
                    state.clear()
                    assertFalse(state.quickAutomatic)
                    state.restoreAttempt(geometry, matrix, matrix.copy(), SamplingProfile(saved, false))
                    val ordinary = MainActivity::class.java.getDeclaredMethod("currentSnapshot")
                        .apply { isAccessible = true }.invoke(activity) as RecoverySnapshot
                    assertNull(ordinary.automaticScanAssumption)
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test fun automaticHistoryWithoutExecutionConditionsPreservesInputWithoutStartingDetailedRepair() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                ShadowDialog.getLatestDialog()?.dismiss()
                val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 30.0, 30.0, 5.0, 0.0)
                val matrix = QrMatrix(21).apply { set(10, 10, ModuleValue.ONE, 1f, ModuleSource.USER) }
                val settings = PreprocessSettings(knownBitErrorLimit = 2, trustKnownPayloadCount = true)
                val entry = RecoveryHistoryEntry(kind = HistoryEventKind.AUTO_RECOVERY, label = "Automatic scan",
                    snapshot = RecoverySnapshot(geometry, matrix.copy(), matrix, -1, settings))
                var restored = false
                MainActivity::class.java.getDeclaredMethod("restoreSnapshot", RecoveryHistoryEntry::class.java,
                    String::class.java, Function0::class.java).apply { isAccessible = true }
                    .invoke(activity, entry, "Restored", { restored = true })
                (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val state = field("recoveryState").get(activity) as RecoveryUiState
                assertTrue(restored)
                assertNull(state.failure)
                assertNull(state.searchDiagnostic)
                assertNull(state.samplingProfile)
                assertTrue(state.recoveries.isEmpty())
                assertEquals(matrix.get(10, 10), state.workingMatrix!!.get(10, 10))
                assertEquals(settings, field("preprocessSettings").get(activity))
                assertTrue((field("statusText").get(activity) as TextView).text.contains(localizedText(
                    "実行条件が保存されていないため、候補を再計算していません",
                    "Candidates were not recalculated")))
                assertTrue((field("guideText").get(activity) as TextView).text.contains(localizedText(
                    "保存済みの入力は保持しています", "The saved input is retained")))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun savedLegacyAutomaticLabelsUseTheCurrentProductNameInEitherLanguage() {
        for (language in AppLanguage.entries) {
            AppLanguageManager.save(RuntimeEnvironment.getApplication(), language)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                val activity = controller.get()
                val label = MainActivity::class.java.getDeclaredMethod("historyDisplayLabel", String::class.java)
                    .apply { isAccessible = true }
                val expected = if (language == AppLanguage.JAPANESE) "簡易自動修復" else "Quick automatic repair"
                for (old in listOf("フルオート解析", "Automatic scan")) assertEquals(expected, label.invoke(activity, old))
                assertEquals("Saved manual work", label.invoke(activity, "Saved manual work"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
