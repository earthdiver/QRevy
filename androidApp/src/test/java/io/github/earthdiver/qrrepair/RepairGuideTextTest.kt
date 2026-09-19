package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.CodewordExtraction
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.RecoveryFailureStage
import io.github.earthdiver.qrrepair.core.RsBitDiagnostic
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertFalse
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
            "内容を確認して、コピーやQR画像の保存ができます。",
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
            "Review the content, then copy it or save the QR image.",
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
            "案内: データコード語列が異なる候補があります。表示内容が同じ場合もあります。各候補をご確認ください。",
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
        assertTrue(text.contains("内容検証前"))
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

    @Test
    @Config(sdk = [35])
    fun inconsistentKnownBitsOverrideBothZeroAndPositiveShortfallInBothLanguages() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            for (shortfall in listOf(0, 8)) {
                setFailure(activity, shortfall, RsBitDiagnostic(70, 56, null, 0, emptyList()))
                val text = guide(activity).text.toString()
                assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                    "読み取り済みセルをすべて正しいとした場合に整合する復元結果が見つかりませんでした" else
                    "no consistent recovery was found assuming all observed cells are correct"))
                assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                    "誤っているセルや設定は特定できていません" else
                    "The incorrect cells or settings have not been identified"))
                assertFalse(text.contains("$shortfall"))
            }
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun zeroShortfallDoesNotImplySuccessfulRepairWithAbsentOrConsistentDiagnostic() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            for (diagnostic in listOf(null, RsBitDiagnostic(56, 56, 0, 56, emptyList()))) {
                setFailure(activity, 0, diagnostic)
                val text = guide(activity).text.toString()
                assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                    "欠損量だけを見ると誤り訂正の容量内ですが、修復には至りませんでした" else
                    "within error-correction capacity, but repair was unsuccessful"))
                assertFalse(text.contains("0"))
                assertFalse(text.contains(if (language == AppLanguage.JAPANESE)
                    "解が見つかりませんでした" else "No error-correction solution"))
            }
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun positiveShortfallIsQualifiedAsAnEstimateInBothLanguages() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            setFailure(activity, 8, null)
            val text = guide(activity).text.toString()
            assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                "目安: 8bit" else "missing data: 8 bits"))
            assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                "既知bitと形式設定が正しい" else "known bits and format settings are correct"))
            assertTrue(text.contains(if (language == AppLanguage.JAPANESE)
                "修復成功を保証するものではありません" else "does not guarantee successful repair"))
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun failureDiagnosticsSeparateBlockAndTotalCountsAndExplainUnknownErrorCost() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            // Synthetic single-block and multi-block cases with the same failing-block counts.
            for (blocks in listOf(1, 2)) {
                val failure = RecoveryFailure(
                    ErrorCorrectionLevel.H, 0, 14, 0, 14, RecoveryFailureStage.REED_SOLOMON,
                    failedBlockIndex = 0, rsBlockCount = blocks, ecSymbolsPerBlock = 17,
                    failedBlockErasures = 14, erasedCodewords = 14 * blocks,
                    originalErasedCodewords = 15 * blocks,
                )
                val text = MainActivity::class.java.getDeclaredMethod(
                    "failureDiagnosticText", RecoveryFailure::class.java,
                    io.github.earthdiver.qrrepair.core.GridGeometry::class.java,
                ).apply { isAccessible = true }.invoke(activity, failure, null) as String
                val expected = if (language == AppLanguage.JAPANESE) listOf(
                    "失敗ブロックのECCシンボル数: 17",
                    "失敗ブロックの未知bitを含むコード語数（探索前）: 14",
                    "構造補完・優先セルの仮置きを反映した不明コード語数（全ブロック合計）: ${14 * blocks}",
                    "差は、残りの訂正能力を示しません", "消失数 + 2 × 誤りコード語数 ≤ ECC数",
                    "誤り数は、この失敗診断では確定していません",
                ) else listOf(
                    "ECC symbols in the failing block: 17",
                    "Erased codewords in the failing block (before search): 14",
                    "Unknown codewords after structure filling and priority assignments (all blocks): ${14 * blocks}",
                    "does not indicate remaining correction capacity",
                    "erasures + 2 × erroneous codewords ≤ ECC symbols",
                    "has not established the number of erroneous observed codewords",
                )
                expected.forEach { assertTrue("Missing: $it", text.contains(it)) }
            }
            val text = MainActivity::class.java.getDeclaredMethod(
                "failureDiagnosticText", RecoveryFailure::class.java,
                io.github.earthdiver.qrrepair.core.GridGeometry::class.java,
            ).apply { isAccessible = true }.invoke(activity,
                RecoveryFailure(ErrorCorrectionLevel.H, 0, 0, 0, 0, RecoveryFailureStage.REED_SOLOMON), null) as String
            assertFalse(text.contains("ECC symbols in the failing block:"))
            assertFalse(text.contains("失敗ブロックのECCシンボル数:"))
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun inconsistentFailureClearsNumericPanelsAndRestoresThemForOtherResults() {
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val activity = activity(language, style)
            recoveryState(activity).geometry = io.github.earthdiver.qrrepair.core.GridGeometry(
                1, io.github.earthdiver.qrrepair.core.FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
            fun view(name: String) = MainActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(activity) as TextView
            fun summary() {
                MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
                    isAccessible = true
                    invoke(activity, null)
                }
                updateGuide(activity)
            }
            // Populate and expand a previous result so stale numbers cannot survive the transition.
            setFailure(activity, 8, null)
            recoveryState(activity).failure = recoveryState(activity).failure!!.copy(
                failedBlockIndex = 0, ecSymbolsPerBlock = 17, failedBlockErasures = 14,
                originalErasedCodewords = 14, erasedCodewords = 14)
            summary()
            view("diagnosticToggle").performClick()
            assertEquals(View.VISIBLE, view("diagnosticDetailsText").visibility)

            setFailure(activity, 0, RsBitDiagnostic(70, 56, null, 0, emptyList()))
            summary()
            MainActivity::class.java.getDeclaredMethod("showWorkspace").apply {
                isAccessible = true
                invoke(activity)
            }
            assertEquals(if (language == AppLanguage.JAPANESE) "修復できませんでした" else "Could not repair",
                view("statusText").text.toString())
            assertEquals(View.VISIBLE, view("contentPreviewText").visibility)
            assertTrue(view("contentPreviewText").text.contains(if (language == AppLanguage.JAPANESE)
                "どのセルや設定が誤っているかは特定できていません" else "have not been identified"))
            assertFalse(view("diagnosticDetailsText").text.contains(if (language == AppLanguage.JAPANESE)
                "ECCシンボル数: 17" else "ECC symbols in the failing block: 17"))
            assertEquals(View.VISIBLE, view("diagnosticToggle").visibility)
            assertFalse(guide(activity).text.any { it.isDigit() })
            assertFalse(guide(activity).text.contains("RS"))
            assertFalse(guide(activity).text.contains("bit"))

            // Even a retained failure must not suppress a subsequently selected candidate.
            setCandidates(activity, listOf(candidate("decoded", byteArrayOf(1))))
            summary()
            assertEquals(View.VISIBLE, view("contentPreviewText").visibility)
            assertEquals(View.VISIBLE, view("diagnosticToggle").visibility)
            assertTrue(view("diagnosticDetailsText").text.isNotEmpty())
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun candidateHeadingsAndConditionsAreHonestInBothLanguages() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            fun text(name: String) = (MainActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(activity) as TextView).text.toString()
            fun summary(candidates: List<RecoveryCandidate>) {
                setCandidates(activity, candidates)
                recoveryState(activity).geometry = io.github.earthdiver.qrrepair.core.GridGeometry(
                    1, io.github.earthdiver.qrrepair.core.FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
                MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
                    isAccessible = true
                    invoke(activity, null)
                }
            }
            for (trust in listOf(false, true)) for (locked in listOf(false, true)) for (limit in listOf(0, 2, null)) {
                val c = candidate("decoded", byteArrayOf(1)).copy(formatMatches = 0, formatMismatches = 0,
                    displayConditions = io.github.earthdiver.qrrepair.core.RecoveryDisplayConditions(trust, locked,
                        io.github.earthdiver.qrrepair.core.QrFormatConstraint(ErrorCorrectionLevel.L, 3), 0, limit),
                    manualDataEccMismatches = 0, constraintForcedByUserSetting = trust, constraintFilledBits = if (trust) 12 else 0)
                summary(listOf(c))
                val status = text("statusText")
                assertFalse(status.contains("成功"))
                assertFalse(status.contains("succeeded"))
                assertTrue(status.contains("1"))
                val content = text("diagnosticDetailsText")
                assertTrue(content.contains(if (language == AppLanguage.JAPANESE) "一致を確認できません" else "agreement cannot be checked"))
                assertTrue(content.contains(if (language == AppLanguage.JAPANESE) "データ・ECC領域" else "Data/ECC region"))
                assertTrue(content.contains(if (language == AppLanguage.JAPANESE) "適用しません" else "not applied"))
                assertTrue(content.contains(if (language == AppLanguage.JAPANESE) "固定セルの0/1と矛盾する復元候補を許容する（仮指定セルと同じ扱い）: ${if (locked) "OFF" else "ON"}" else "Allow recovery candidates that conflict with fixed 0/1 values (as for tentative cells): ${if (locked) "OFF" else "ON"}"))
                if (!locked && limit == 0) assertTrue(content.contains(if (language == AppLanguage.JAPANESE)
                    "この領域の入力済み0/1は変更していません" else "in that region"))
                if (trust) assertTrue(text("diagnosticDetailsText").contains(if (language == AppLanguage.JAPANESE)
                    "指定した仮定に基づく構造補完・変更: 12bit" else "Structure-based filling/changes under the specified assumption: 12 bits"))
            }
            summary(listOf(candidate("one", byteArrayOf(1)), candidate("one", byteArrayOf(1))))
            assertTrue(text("statusText").contains(if (language == AppLanguage.JAPANESE) "データコード語列はすべて同じ" else "all have the same data codeword sequence"))
            summary(listOf(candidate("one", byteArrayOf(1)), candidate("one", byteArrayOf(2))))
            assertTrue(text("statusText").contains(if (language == AppLanguage.JAPANESE) "データコード語列は2種類" else "2 distinct data codeword sequences"))
            for (stop in io.github.earthdiver.qrrepair.core.CombinationSearchStop.entries) {
                val message = io.github.earthdiver.qrrepair.core.CombinationSearchSummary(1, setOf(stop)).description(16)
                assertTrue(message.contains(if (language == AppLanguage.JAPANESE) "まだ調べていない組合せ" else "Some combinations remain unchecked"))
                assertFalse(message.contains("探索窓"))
                assertFalse(message.contains("展開ノード"))
            }
            val message = io.github.earthdiver.qrrepair.core.CombinationSearchSummary(1).description(16)
            assertTrue(message.contains(if (language == AppLanguage.JAPANESE) "探索した結果、16件の候補が見つかりました" else "found 16 candidates"))
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun diagnosticOrderAndOriginalInputScopeAreClearInBothLanguages() {
        for (language in AppLanguage.entries) {
            val activity = activity(language)
            val c = candidate("decoded", byteArrayOf(1)).copy(originalErasedCodewords = 14,
                erasedCodewords = 13, correctedErrataSymbols = 13,
                constraintFilledBits = 12, constraintForcedByUserSetting = true, partialGuessedBits = 4,
                combinationSearch = io.github.earthdiver.qrrepair.core.CombinationSearchSummary(1),
                rsBitDiagnostic = RsBitDiagnostic(72, 56, 16, 0, emptyList()))
            setCandidates(activity, listOf(c, c.copy(dataMask = 1)))
            val text = MainActivity::class.java.getDeclaredMethod("candidateDiagnosticText",
                RecoveryCandidate::class.java, io.github.earthdiver.qrrepair.core.GridGeometry::class.java)
                .apply { isAccessible = true }.invoke(activity, c, null) as String
            val labels = io.github.earthdiver.qrrepair.core.DiagnosticStage.entries.map { it.title() }
            val positions = labels.map { text.indexOf(it) }
            assertTrue(positions.zipWithNext().all { (a, b) -> a >= 0 && a < b })
            val input = text.substring(positions[1], positions[2])
            val processing = text.substring(positions[2], positions[3])
            assertTrue(input.contains(if (language == AppLanguage.JAPANESE)
                "不明なbitを含むコード語（全ブロック合計）: 14" else "Codewords containing unknown bits (all blocks): 14"))
            assertTrue(input.contains(if (language == AppLanguage.JAPANESE)
                "この形式で入力済みの白黒を保つ組合せのすべて" else "Unknown bits with the same value"))
            assertTrue(processing.contains(if (language == AppLanguage.JAPANESE)
                "不明コード語数（全ブロック合計）: 13" else "Unknown codewords after structure filling and priority assignments (all blocks): 13"))
            assertTrue(text.substring(positions[3]).contains(if (language == AppLanguage.JAPANESE)
                "得られた候補: 2" else "Candidates obtained: 2"))
            activity.finish()
        }
    }

    @Test
    @Config(sdk = [35])
    fun failedResultsLeadWithReasonAndUseTheSameDiagnosticOrderInBothStylesAndLanguages() {
        for (style in AppStyle.entries) for (language in AppLanguage.entries) {
            val activity = activity(language, style)
            val geometry = io.github.earthdiver.qrrepair.core.GridGeometry(
                4, io.github.earthdiver.qrrepair.core.FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
            fun text(name: String) = (MainActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(activity) as TextView).text.toString()
            for (stage in RecoveryFailureStage.entries) {
                recoveryState(activity).apply {
                    workingMatrix = QrMatrix(33)
                    this.geometry = geometry
                    recoveries = emptyList()
                    failure = RecoveryFailure(ErrorCorrectionLevel.L, 3, 30, 0, 30, stage,
                        reason = if (stage == RecoveryFailureStage.REED_SOLOMON)
                            io.github.earthdiver.qrrepair.core.RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT else null,
                        failedBlockIndex = 0, rsBlockCount = 1, ecSymbolsPerBlock = 20,
                        failedBlockErasures = 42, originalErasedCodewords = 43, erasedCodewords = 42,
                        rsBitDiagnostic = RsBitDiagnostic(244, 160, 84, 0, emptyList()),
                        combinationSearch = io.github.earthdiver.qrrepair.core.CombinationSearchSummary(1))
                }
                MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
                    isAccessible = true
                    invoke(activity, null)
                }
                val preview = text("contentPreviewText")
                val expectedReason = if (language == AppLanguage.JAPANESE) when (stage) {
                    RecoveryFailureStage.EXTRACTION -> "QR行列からデータを取り出せませんでした"
                    RecoveryFailureStage.DEINTERLEAVE -> "抽出したデータをQRの誤り訂正ブロックとして解釈できませんでした"
                    RecoveryFailureStage.REED_SOLOMON -> "探索上限までに候補が見つかりませんでした"
                    RecoveryFailureStage.PAYLOAD_VALIDATION -> "誤り訂正候補は得られましたが"
                } else when (stage) {
                    RecoveryFailureStage.EXTRACTION -> "Data could not be extracted"
                    RecoveryFailureStage.DEINTERLEAVE -> "The extracted data could not be interpreted"
                    RecoveryFailureStage.REED_SOLOMON -> "No candidate was found within the search limit"
                    RecoveryFailureStage.PAYLOAD_VALIDATION -> "An error-correction candidate was obtained"
                }
                assertTrue("$style/$language/$stage: $preview", preview.startsWith(if (language == AppLanguage.JAPANESE) "今回分かったこと\n" else "What this attempt established\n"))
                assertTrue(preview.contains(expectedReason))
                assertFalse(preview.contains(if (language == AppLanguage.JAPANESE) "誤り訂正レベル:" else "Error correction level:"))
                val report = MainActivity::class.java.getDeclaredField("diagnosticReport")
                    .apply { isAccessible = true }.get(activity) as io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport
                assertEquals(io.github.earthdiver.qrrepair.core.DiagnosticStage.entries, report.sections.map { it.stage })
                assertTrue(report.sections[1].details.contains(if (language == AppLanguage.JAPANESE)
                    "この形式で入力済みの白黒を保つ組合せのすべて" else "Unknown bits with the same value"))
                assertFalse(text("diagnosticDetailsText").contains(report.sections[1].details))

            }
            // Missing geometry/analysis must not leave empty headings or blank paragraphs.
            val minimal = MainActivity::class.java.getDeclaredMethod("failureDiagnosticText",
                RecoveryFailure::class.java, io.github.earthdiver.qrrepair.core.GridGeometry::class.java)
                .apply { isAccessible = true }.invoke(activity,
                    RecoveryFailure(ErrorCorrectionLevel.L, 3, 0, 0, 0, RecoveryFailureStage.EXTRACTION), null) as String
            assertTrue(minimal.contains(io.github.earthdiver.qrrepair.core.DiagnosticStage.SETTINGS.title()))
            assertFalse(minimal.contains("失敗ブロックのECCシンボル数:"))
            activity.finish()
        }
    }

    private fun setFailure(activity: MainActivity, shortfall: Int, diagnostic: RsBitDiagnostic?) {
        recoveryState(activity).apply {
            sourceMatrix = QrMatrix(21)
            workingMatrix = sourceMatrix!!.copy()
            recoveries = emptyList()
            failure = RecoveryFailure(ErrorCorrectionLevel.L, 0, 14, 0, 14, RecoveryFailureStage.REED_SOLOMON,
                rsShortfallBits = shortfall, rsBitDiagnostic = diagnostic)
        }
        MainActivity::class.java.getDeclaredMethod("refreshInspector").apply {
            isAccessible = true
            invoke(activity)
        }
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

    private fun activity(language: AppLanguage, style: AppStyle = AppStyle.CLASSIC): MainActivity {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_language", Activity.MODE_PRIVATE).edit().clear().commit()
        AppLanguageManager.save(app, language)
        val intent = android.content.Intent(app, MainActivity::class.java)
            .putExtra(AppStylePreferences.EXTRA_ACTIVE_STYLE, style.name)
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().get().also {
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        }
    }

    @Test
    @Config(sdk = [35])
    fun unparsedCandidateGuideDoesNotClaimQrStructureValidationFailed() {
        for (language in listOf(AppLanguage.JAPANESE, AppLanguage.ENGLISH)) {
            val activity = activity(language)
            // Valid byte-mode QR data for A, with no available text conversion.
            setCandidates(activity, listOf(candidate("A", byteArrayOf(0x40, 0x14, 0x10)).copy(text = null)))
            updateGuide(activity)
            val message = guide(activity).text.toString()
            assertTrue(message.contains(if (language == AppLanguage.JAPANESE) "HEX／Binary" else "Hex / Binary"))
            assertFalse(message.contains("検証できませんでした"))
            assertFalse(message.contains("could not be validated"))
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
