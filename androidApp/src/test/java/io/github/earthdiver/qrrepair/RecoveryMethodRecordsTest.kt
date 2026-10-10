package io.github.earthdiver.qrrepair

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryMethodRecordsTest {
    private fun candidate() = RecoveryCandidate(
        1, ErrorCorrectionLevel.L, 0, "SYNTHETIC", byteArrayOf(1), 30, 0, 2, 2, 2, 0, 192,
        intArrayOf(1, 2), CodewordExtraction(emptyList()),
        actualBlockMethods = listOf(RecoveryBlockMethod(0, true, null, 4)),
    )

    private fun processing(candidate: RecoveryCandidate, japanese: Boolean,
        entry: RecoveryResultEntry? = null): DiagnosticSection =
        RecoveryDiagnosticPresenter(japanese).candidateReport(candidate, null,
            RecoveryDiagnosticContext(1, false, false, candidate.searchDiagnostic, entry)).sections[2]

    private fun group(candidate: RecoveryCandidate, japanese: Boolean): DiagnosticDetailGroup =
        processing(candidate, japanese).groups.single { it.id == DiagnosticDetailId.DERIVATIONS }

    private fun inBothLanguages(check: (Boolean) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val previous = AppLanguageManager.current
        try {
            for (language in AppLanguage.entries) {
                AppLanguageManager.applyTo(app, language)
                check(language == AppLanguage.JAPANESE)
            }
        } finally { AppLanguageManager.applyTo(app, previous) }
    }

    @Test fun repeatedCalculationsAddNoPanelOrCountsAndPreserveTheRetainedEvidence() {
        val first = candidate().copy(constraintFilledBits = 12, constraintForcedByUserSetting = true,
            singleSegmentStatus = SingleSegmentStatus.APPLIED)
        val repeated = first.copy(dataBytes = first.dataBytes.copyOf(), correctedInterleaved = first.correctedInterleaved.copyOf(),
            extraction = first.extraction.copy())
        val selected = first.copy(alternativeDerivations = listOf(repeated))
        inBothLanguages { ja ->
            val section = processing(selected, ja)
            assertEquals(processing(first, ja), section)
            assertFalse(section.groups.any { it.id == DiagnosticDetailId.DERIVATIONS })
            assertFalse(section.details.contains(if (ja) "処理記録2件" else "2 retained calculation records"))
            assertFalse(section.details.contains(if (ja) "補足" else "Supplement"))
        }
        assertSame(repeated, selected.alternativeDerivations.single())
    }

    @Test fun structureChecksSupplementTheCandidateMethodWithoutAnAlternativePanel() {
        val qr = Encoder.encode("GS RECOVERY", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        // Four erroneous codewords exceed version 1-L's conventional three-error radius.
        for (raw in 2..5) {
            val coord = extraction.codewords[raw].coordinates.last()
            matrix.set(coord.x, coord.y, if (matrix[coord.x, coord.y].value == ModuleValue.ONE)
                ModuleValue.ZERO else ModuleValue.ONE, 1f, ModuleSource.IMAGE)
        }
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix, maxKnownDataEccMismatches = 21)
        val selected = outcome.candidates.single()
        assertEquals("GS RECOVERY", selected.text)
        assertTrue(selected.alternativeDerivations.isNotEmpty())
        assertTrue((listOf(selected) + selected.alternativeDerivations).any { it.inferredMode == 2 && it.inferredCount == 11 })
        inBothLanguages { ja ->
            val section = processing(selected, ja)
            assertFalse(section.groups.any { it.id == DiagnosticDetailId.DERIVATIONS })
            val methods = section.groups.single { it.id == DiagnosticDetailId.METHODS }
            assertFalse(methods.collapsible)
            assertTrue(methods.text.contains(if (ja) "補足: QR構造の確認" else "Supplement: QR structure checks"))
            assertTrue(methods.text.contains(if (ja) "英数字 11文字" else "Alphanumeric 11 characters"))
            assertTrue(methods.text.contains(if (ja) "読み取り済みbit: 78bit" else "suffix structure: 78 bits"))
            assertTrue(methods.text.contains(if (ja) "構造による補完・変更はありません" else "No values were filled or changed using QR structure"))
            assertFalse(section.details.contains(if (ja) "処理記録2件" else "2 retained calculation records"))
        }
    }

    @Test fun repeatedStructureEvidenceAppearsOnceInTheSupplement() = inBothLanguages { ja ->
        val first = candidate().copy(inferredMode = 2, inferredCount = 11, constraintEvidenceBits = 78)
        val selected = first.copy(alternativeDerivations = listOf(first.copy(constraintPlans = 1)))
        val section = processing(selected, ja)
        val methods = section.groups.single { it.id == DiagnosticDetailId.METHODS }
        val description = if (ja) "英数字 11文字" else "Alphanumeric 11 characters"
        assertEquals(1, methods.text.split(description).size - 1)
        assertFalse(section.groups.any { it.id == DiagnosticDetailId.DERIVATIONS })
    }

    @Test fun structureChecksForAnotherMethodStayWithThatMethod() = inBothLanguages { ja ->
        val first = candidate()
        val other = first.copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, true, null, 5)))
        val checked = other.copy(constraintPlans = 1, inferredMode = 2, inferredCount = 11, constraintEvidenceBits = 78)
        val section = processing(first.copy(alternativeDerivations = listOf(other, checked)), ja)
        val description = if (ja) "英数字 11文字" else "Alphanumeric 11 characters"
        assertFalse(section.groups.single { it.id == DiagnosticDetailId.METHODS }.text.contains(description))
        val alternatives = section.groups.single { it.id == DiagnosticDetailId.DERIVATIONS }
        assertTrue(alternatives.summary.contains(if (ja) "補完方法: 2通り" else "methods for the whole QR: 2"))
        assertTrue(alternatives.text.substringAfter(if (ja) "補完方法2" else "Method 2").contains(description))
        assertFalse(alternatives.text.contains(if (ja) "補完方法3" else "Method 3"))
    }

    @Test fun anOmissionNoticeRemainsVisibleWithoutDuplicatingTheMethod() = inBothLanguages { ja ->
        val first = candidate()
        val selected = first.copy(alternativeDerivations = listOf(first.copy(derivationsOmitted = true)))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "保持上限" else "retention limit"))
        assertFalse(group.summary.contains(if (ja) "補完方法:" else "methods for the whole QR:"))
        assertTrue(group.text.isBlank())
    }

    @Test fun anotherSampledInputRemainsVisibleWhenTheCalculationWasRepeated() = inBothLanguages { ja ->
        val first = candidate()
        val selected = first.copy(alternativeDerivations = listOf(first))
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0)
        val hypothesis = GeometryHypothesis(emptyList(), geometry, 0.0)
        val stats = MatrixSampler.SamplingStats(128.0, 255.0, 32.0)
        val attempt = GeometryAttempt(hypothesis, QrMatrix(21), stats, listOf(selected), null)
        val entry = RecoveryResultEntry(attempt, selected, null,
            listOf(RecoveryResultDerivation(hypothesis, stats, null, first)))
        val group = processing(selected, ja, entry).groups.single { it.id == DiagnosticDetailId.DERIVATIONS }
        assertTrue(group.summary.contains(if (ja) "別の読み取り試行: 1件" else "sampled-input attempts yielding the same QR: 1"))
        assertFalse(group.summary.contains(if (ja) "補完方法:" else "methods for the whole QR:"))
        assertTrue(group.text.contains(if (ja) "別の試行1" else "Another attempt yielding the same QR 1"))
    }

    @Test fun equalCountsDoNotMergeDifferentPriorityLocationsOrValues() = inBothLanguages { ja ->
        val first = candidate().copy(priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ZERO))
        val selected = first.copy(alternativeDerivations = listOf(
            first.copy(priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ONE)),
            first.copy(priorityAssignments = mapOf(Coord(11, 10) to ModuleValue.ZERO))))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "補完方法: 3通り" else "methods for the whole QR: 3"))
        assertTrue(group.text.contains(if (ja) "代表の方法との違い: 優先セルの位置・仮置きした値"
            else "Differences from the representative: prioritized cell locations or tentative values"))
    }

    @Test fun equalCountsDoNotMergeDifferentCellRepairProvenance() = inBothLanguages { ja ->
        val info = CodewordRepairInfo(0, 0, 0, 128, 0, false, false,
            mapOf(0 to PayloadConstraintSolver.ConstraintKind.PAD), true, constraintValuesByBit = mapOf(0 to 0))
        val first = candidate().copy(constraintFilledBits = 1, repairInfo = listOf(info))
        val selected = first.copy(alternativeDerivations = listOf(
            first.copy(repairInfo = listOf(info.copy(constraintValuesByBit = mapOf(0 to 1)))),
            first.copy(repairInfo = listOf(info.copy(rawIndex = 1, localIndex = 1)))))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "補完方法: 3通り" else "methods for the whole QR: 3"))
        assertTrue(group.text.contains(if (ja) "セルごとの補完・訂正の内訳" else "repairs to individual cells"))
    }

    @Test fun calculationParametersAndAppliedConditionsRemainDistinct() {
        val first = candidate().copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, false, 0, 0)))
        val list = ListDecodingDerivation(4, 0, 0, 0, 4, -1, ListDecoderMethod.WU)
        val alternatives = listOf(
            first.copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, false, 2, 0))),
            candidate(),
            first.copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, false, null, 0, list))),
            first.copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, false, null, 0, list.copy(radius = 5)))),
            first.copy(constraintForcedByUserSetting = true, singleSegmentStatus = SingleSegmentStatus.APPLIED),
            first.copy(listDecodingMismatchLimit = 11),
            first.copy(displayConditions = RecoveryDisplayConditions(false, true, null, null, 0)),
        )
        val selected = first.copy(alternativeDerivations = alternatives)
        assertEquals(8, selected.groupedMethodRecords().size)
        inBothLanguages { ja ->
            val group = group(selected, ja)
            assertTrue(group.text.contains(if (ja) "通常RS訂正余裕: 0" else "conventional RS margin: 0"))
            assertTrue(group.text.contains(if (ja) "通常RS訂正余裕: 2" else "conventional RS margin: 2"))
            assertTrue(group.text.contains(if (ja) "代表の方法との違い" else "Differences from the representative"))
        }
    }

    @Test fun groupingRetainsBlockAlternativesAndOmissionNoticesFromEveryRecord() = inBothLanguages { ja ->
        val block = PartialCodewordSolver.Derivation(listOf(0), 4, 0, emptyList(), 0, 0, true)
        val first = candidate().copy(blockDerivations = mapOf(0 to listOf(block, block.copy(guessedBits = 5))))
        val repeated = first.copy(blockDerivations = mapOf(0 to listOf(block.copy(searchPenalty = 1), block.copy(guessedBits = 6))),
            derivationsOmitted = true)
        val group = group(first.copy(alternativeDerivations = listOf(repeated)), ja)
        assertFalse(group.summary.contains(if (ja) "補完方法:" else "methods for the whole QR:"))
        assertTrue(group.summary.contains(if (ja) "保持上限" else "retention limit"))
        assertTrue(group.text.contains(if (ja) "RSブロック1: 同じ補完結果を得た方法: 3通り" else "RS block 1: 3 derivations"))
        assertFalse(group.text.contains(if (ja) "補完方法1" else "Method 1"))
    }
}
