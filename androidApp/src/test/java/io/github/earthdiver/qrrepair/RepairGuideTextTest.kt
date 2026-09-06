package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.CodewordExtraction
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.RecoveryFailureStage
import io.github.earthdiver.qrrepair.core.RsBitDiagnostic
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class RepairGuideTextTest {
    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP")
    fun uniqueDecodedPayloadIsReportedAsSuccessfulRepairInJapanese() {
        val activity = activity(AppLanguage.JAPANESE)
        setCandidates(activity, listOf(candidate("復号内容", byteArrayOf(1))))

        updateGuide(activity)

        assertEquals(
            "案内: 重要な内容については、表示された結果をご確認ください。",
            guide(activity).text.toString(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS")
    fun uniqueDecodedPayloadIsReportedAsSuccessfulRepairInEnglish() {
        val activity = activity(AppLanguage.ENGLISH)
        setCandidates(activity, listOf(candidate("decoded", byteArrayOf(1))))

        updateGuide(activity)

        assertEquals(
            "Guide: Please verify the displayed result if the content is important.",
            guide(activity).text.toString(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP")
    fun differentDecodedPayloadsAreReportedAsUnresolved() {
        val activity = activity(AppLanguage.JAPANESE)
        setCandidates(activity, listOf(candidate("候補1", byteArrayOf(1)), candidate("候補2", byteArrayOf(2))))

        updateGuide(activity)

        assertEquals(
            "案内: 内容が異なるため正しい結果を確定できません。各候補の内容をご確認ください。",
            guide(activity).text.toString(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "ja-rJP")
    fun symbolicRecommendationExplainsItsAssumptionInJapanese() {
        val activity = activity(AppLanguage.JAPANESE)
        setSymbolicSpace(activity)
        val text = guide(activity).text.toString()
        assertTrue(text.contains("14bit → 13bit"))
        assertTrue(text.contains("既知データ・ECCが正しいと仮定"))
        assertTrue(text.contains("ペイロード検証前"))
        assertTrue(text.contains("(10,10)"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS")
    fun symbolicRecommendationExplainsItsAssumptionInEnglish() {
        val activity = activity(AppLanguage.ENGLISH)
        setSymbolicSpace(activity)
        val text = guide(activity).text.toString()
        assertTrue(text.contains("14 → 13"))
        assertTrue(text.contains("assuming this attempt's format and known data/ECC are correct"))
        assertTrue(text.contains("before payload validation"))
        assertTrue(text.contains("(10,10)"))
    }

    @Test
    @Config(sdk = [35])
    fun structuredAppendIsExplicitInGuideSuccessAndPreviewInBothLanguages() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            val metadata = io.github.earthdiver.qrrepair.core.StructuredAppendInfo(1, 3, 165)
            setCandidates(activity, listOf(candidate("A", byteArrayOf(1)).copy(
                structuredAppend = metadata, fnc1ApplicationIndicator = 65)))
            recoveryState(activity).geometry = io.github.earthdiver.qrrepair.core.GridGeometry(
                1, io.github.earthdiver.qrrepair.core.FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
            updateGuide(activity)
            MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
                isAccessible = true
                invoke(activity, null)
            }
            assertEquals(metadata.description(), guide(activity).text.toString())
            for (field in listOf("statusText", "contentPreviewText")) {
                val text = (MainActivity::class.java.getDeclaredField(field).apply { isAccessible = true }
                    .get(activity) as TextView).text.toString()
                assertTrue(text.contains(metadata.description()))
            }
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS")
    fun repairedCellExplanationUsesInputConfidenceInEnglish() {
        val activity = activity(AppLanguage.ENGLISH)
        val source = QrMatrix(21).apply {
            set(10, 10, io.github.earthdiver.qrrepair.core.ModuleValue.ERASED, .2f, io.github.earthdiver.qrrepair.core.ModuleSource.IMAGE)
        }
        val after = source.copy().apply {
            set(10, 10, io.github.earthdiver.qrrepair.core.ModuleValue.ONE, 1f, io.github.earthdiver.qrrepair.core.ModuleSource.RECOVERED)
        }
        val trace = io.github.earthdiver.qrrepair.core.RepairTraceBuilder.build(source, after, null).getValue(Coord(10, 10))
        assertTrue(trace.explanationJa().contains("Input-cell relative confidence (not a probability)=0.20"))
        activity.finish()
    }

    private fun setSymbolicSpace(activity: MainActivity) {
        recoveryState(activity).apply {
            sourceMatrix = QrMatrix(21)
            workingMatrix = sourceMatrix!!.copy()
            recoveries = emptyList()
            failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 30, 0, 30, RecoveryFailureStage.REED_SOLOMON,
                rsBitDiagnostic = RsBitDiagnostic(70, 56, 14, 0, listOf(Coord(10, 10))))
        }
        MainActivity::class.java.getDeclaredMethod("refreshInspector").apply {
            isAccessible = true
            invoke(activity)
        }
    }

    private fun activity(language: AppLanguage): MainActivity {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_language", Activity.MODE_PRIVATE).edit().clear().commit()
        AppLanguageManager.save(app, language)
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        }
    }

    private fun setCandidates(activity: MainActivity, candidates: List<RecoveryCandidate>) {
        recoveryState(activity).apply {
            workingMatrix = QrMatrix(21)
            recoveries = candidates
            selectRecovery(0)
        }
    }

    private fun updateGuide(activity: MainActivity) {
        MainActivity::class.java.getDeclaredMethod("updateGuideText").apply {
            isAccessible = true
            invoke(activity)
        }
    }

    private fun guide(activity: MainActivity): TextView =
        MainActivity::class.java.getDeclaredField("guideText").apply { isAccessible = true }.get(activity) as TextView

    private fun recoveryState(activity: MainActivity): RecoveryUiState =
        MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }
            .get(activity) as RecoveryUiState

    private fun candidate(text: String, data: ByteArray) = RecoveryCandidate(
        1, ErrorCorrectionLevel.M, 0, text, data,
        15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
    )
}
