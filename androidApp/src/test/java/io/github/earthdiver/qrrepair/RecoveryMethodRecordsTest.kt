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
        processing(candidate, japanese).groups.single { it.id == DiagnosticDetailId.CALCULATIONS }

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
            assertEquals(1, section.groups.count { it.id == DiagnosticDetailId.CALCULATIONS })
            assertFalse(section.details.contains(if (ja) "処理記録2件" else "2 retained calculation records"))
            assertFalse(section.details.contains(if (ja) "補足" else "Supplement"))
        }
        assertSame(repeated, selected.alternativeDerivations.single())
    }

    @Test fun structureChecksStayInTheUnifiedCalculationDetails() {
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
            assertEquals(1, section.groups.count { it.id == DiagnosticDetailId.CALCULATIONS })
            val methods = section.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
            assertTrue(methods.collapsible)
            assertTrue(methods.text.contains(if (ja) "QR構造の確認" else "QR structure checks"))
            assertTrue(methods.text.contains(if (ja) "英数字 11文字" else "Alphanumeric 11 characters"))
            assertTrue(methods.text.contains(if (ja) "読み取り済みbit: 78bit" else "suffix structure: 78 bits"))
            assertTrue(methods.text.contains(if (ja) "構造による補完・変更はありません" else "No values were filled or changed using QR structure"))
            assertFalse(section.details.contains(if (ja) "処理記録2件" else "2 retained calculation records"))
        }
    }

    @Test fun repeatedStructureEvidenceAppearsOnceInTheCalculationDetails() = inBothLanguages { ja ->
        val first = candidate().copy(inferredMode = 2, inferredCount = 11, constraintEvidenceBits = 78)
        val selected = first.copy(alternativeDerivations = listOf(first.copy(constraintPlans = 1)))
        val section = processing(selected, ja)
        val methods = section.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
        val description = if (ja) "英数字 11文字" else "Alphanumeric 11 characters"
        assertEquals(1, methods.text.split(description).size - 1)
        assertEquals(1, section.groups.count { it.id == DiagnosticDetailId.CALCULATIONS })
    }

    @Test fun structureChecksForAnotherMethodStayWithThatMethod() = inBothLanguages { ja ->
        val first = candidate()
        val other = first.copy(actualBlockMethods = listOf(RecoveryBlockMethod(0, true, null, 5)))
        val checked = other.copy(constraintPlans = 1, inferredMode = 2, inferredCount = 11, constraintEvidenceBits = 78)
        val section = processing(first.copy(alternativeDerivations = listOf(other, checked)), ja)
        val description = if (ja) "英数字 11文字" else "Alphanumeric 11 characters"
        assertFalse(section.groups.single { it.id == DiagnosticDetailId.METHODS }.text.contains(description))
        val alternatives = section.groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
        assertTrue(alternatives.summary.contains(if (ja) "2通りの計算で同じQRが得られました。" else "2 calculation methods yielded the same QR."))
        assertTrue(alternatives.text.substringAfter(if (ja) "方法B" else "Method B").contains(description))
        assertFalse(alternatives.text.contains(if (ja) "方法C" else "Method C"))
    }

    @Test fun anOmissionNoticeRemainsVisibleWithoutDuplicatingTheMethod() = inBothLanguages { ja ->
        val first = candidate()
        val selected = first.copy(alternativeDerivations = listOf(first.copy(derivationsOmitted = true)))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "保持上限" else "retention limit"))
        assertFalse(group.summary.contains(if (ja) "方法A" else "Method A"))
        assertEquals(group(first, ja).text, group.text)
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
        val group = processing(selected, ja, entry).groups.single { it.id == DiagnosticDetailId.CALCULATIONS }
        assertTrue(group.summary.contains(if (ja) "別の読み取り試行: 1件" else "Other sampled-input attempts: 1"))
        assertFalse(group.summary.contains(if (ja) "方法A" else "Method A"))
        assertTrue(group.text.contains(if (ja) "別の読み取り試行1" else "Other sampled-input attempt 1"))
    }

    @Test fun equalCountsDoNotMergeDifferentPriorityLocationsOrValues() = inBothLanguages { ja ->
        val first = candidate().copy(priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ZERO))
        val selected = first.copy(alternativeDerivations = listOf(
            first.copy(priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ONE)),
            first.copy(priorityAssignments = mapOf(Coord(11, 10) to ModuleValue.ZERO))))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "3通りの計算で同じQRが得られました。" else "3 calculation methods yielded the same QR."))
        for (assignment in listOf("(10,10)=0", "(10,10)=1", "(11,10)=0")) assertTrue(group.text.contains(assignment))
        assertFalse(group.summary.substringBefore(if (ja) "方法A" else "Method A").contains(
            if (ja) "優先セルの仮置き" else "priority assignments"))
    }

    @Test fun equalCountsDoNotMergeDifferentCellRepairProvenance() = inBothLanguages { ja ->
        val info = CodewordRepairInfo(0, 0, 0, 128, 0, false, false,
            mapOf(0 to PayloadConstraintSolver.ConstraintKind.PAD), true, constraintValuesByBit = mapOf(0 to 0))
        val first = candidate().copy(constraintFilledBits = 1, repairInfo = listOf(info))
        val selected = first.copy(alternativeDerivations = listOf(
            first.copy(repairInfo = listOf(info.copy(constraintValuesByBit = mapOf(0 to 1)))),
            first.copy(repairInfo = listOf(info.copy(rawIndex = 1, localIndex = 1)))))
        val group = group(selected, ja)
        assertTrue(group.summary.contains(if (ja) "3通りの計算で同じQRが得られました。" else "3 calculation methods yielded the same QR."))
        assertTrue(group.text.contains(if (ja) "コード語1/bit1" else "Codeword 1/bit1"))
        assertTrue(group.text.contains(if (ja) "コード語2/bit1" else "Codeword 2/bit1"))
        assertTrue(group.text.substringBefore(if (ja) "方法B" else "Method B")
            .contains(if (ja) "補完後 0 (埋め草)" else "after filling 0 (padding)"))
        assertTrue(group.text.substringAfter(if (ja) "方法B" else "Method B").substringBefore(if (ja) "方法C" else "Method C")
            .contains(if (ja) "補完後 1 (埋め草)" else "after filling 1 (padding)"))
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
            assertTrue(group.text.contains(if (ja) "通常RS訂正余裕: 0" else "Conventional RS correction margin for this block: 0"))
            assertTrue(group.text.contains(if (ja) "通常RS訂正余裕: 2" else "Conventional RS correction margin for this block: 2"))
            assertTrue(group.summary.contains(if (ja) "方法H" else "Method H"))
        }
    }

    @Test fun groupingRetainsBlockAlternativesAndOmissionNoticesFromEveryRecord() = inBothLanguages { ja ->
        val block = PartialCodewordSolver.Derivation(listOf(0), 4, 0, emptyList(), 0, 0, true)
        val first = candidate().copy(blockDerivations = mapOf(0 to listOf(block, block.copy(guessedBits = 5))))
        val repeated = first.copy(blockDerivations = mapOf(0 to listOf(block.copy(searchPenalty = 1), block.copy(guessedBits = 6))),
            derivationsOmitted = true)
        val group = group(first.copy(alternativeDerivations = listOf(repeated)), ja)
        assertFalse(group.summary.contains(if (ja) "方法A" else "Method A"))
        assertTrue(group.summary.contains(if (ja) "保持上限" else "retention limit"))
        assertTrue(group.text.contains(if (ja) "ブロック1・同じ値を得た別の計算: 2通り" else "Block 1: 2 other calculations"))
        assertFalse(group.summary.contains(if (ja) "方法B" else "Method B"))
    }

    /** Independent synthetic calculation metadata; no source image is used. */
    private fun recordedCandidate(blocks: Int = 1): RecoveryCandidate {
        val words = blocks * 3
        val extraction = CodewordExtraction(List(words) { raw ->
            PartialCodeword(0, 255, List(8) { bit -> Coord((raw * 8 + bit) % 21, (raw * 8 + bit) / 21) })
        })
        val info = List(words) { raw -> CodewordRepairInfo(raw, raw / 3, raw % 3, 255,
            if (raw == 0) 127 else 255, true, false,
            if (raw == 0) mapOf(0 to PayloadConstraintSolver.ConstraintKind.PAD) else emptyMap(),
            bitConstraintSolved = true, constraintValuesByBit = if (raw == 0) mapOf(0 to 0) else emptyMap()) }
        return candidate().copy(extraction = extraction, correctedInterleaved = IntArray(words),
            originalErasedCodewords = words, erasedCodewords = words, correctedErrataSymbols = words,
            constraintFilledBits = 1, repairInfo = info,
            actualBlockMethods = List(blocks) { RecoveryBlockMethod(it, true, null, 0) })
    }

    private fun conventionalAlternative(first: RecoveryCandidate): RecoveryCandidate {
        val exact = PartialCodewordSolver.Derivation(listOf(0, 1, 2), 0, 0, emptyList(), 0, 0, true)
        val conventional = PartialCodewordSolver.Derivation(listOf(1, 2), 2, 1, listOf(0), 0, 0, false)
        return first.copy(
            actualBlockMethods = first.actualBlockMethods.map { RecoveryBlockMethod(it.blockIndex, false, 0, 2) },
            correctedErrataSymbols = first.actualBlockMethods.size * 2, partialGuessedBits = first.actualBlockMethods.size * 2,
            repairInfo = first.repairInfo.map { it.copy(rsErrata = it.localIndex != 0,
                partialEnumerated = it.localIndex == 0, bitConstraintSolved = false) },
            blockDerivations = first.actualBlockMethods.associate { it.blockIndex to listOf(conventional, exact) })
    }

    @Test fun sharedPreparationAndRepeatedBlockCalculationsHaveOneExplanation() = inBothLanguages { ja ->
        val first = recordedCandidate()
        val other = conventionalAlternative(first)
        val selected = first.copy(alternativeDerivations = listOf(other))
        val section = processing(selected, ja)
        assertEquals(listOf(DiagnosticDetailId.METHODS, DiagnosticDetailId.CALCULATIONS), section.groups.map { it.id })
        val record = group(selected, ja)
        val structure = if (ja) "構造補完（1bit）" else "structure filling (1 bits)"
        assertEquals(1, section.details.split(structure).size - 1)
        assertTrue(record.summary.contains(if (ja) "共通: " else "Shared: "))
        assertTrue(record.summary.contains(if (ja) "方法A［セルの説明に使用］" else "Method A [used for cell explanations]"))
        assertTrue(record.summary.contains(if (ja) "不明2bitの仮置き＋通常RS：訂正余裕0" else "Assign 2 unknown bits + Conventional RS: margin 0"))
        assertEquals(1, record.text.split(if (ja) "方法: 部分bit制約" else "Method: Partial-bit constraints").size - 1)
        assertEquals(1, record.text.split(if (ja) "方法: 通常RS" else "Method: Conventional RS").size - 1)
        assertTrue(record.text.contains(if (ja) "方法Aのブロック1と同じ計算記録" else "Same calculation record as Method A, block 1"))
        assertFalse(record.text.contains(if (ja) "代表は" else "representative:"))
        assertFalse(record.text.contains(if (ja) "算定対象外" else "not applicable"))
        assertSame(other, selected.alternativeDerivations.single())
        assertEquals(2, other.blockDerivations.getValue(0).size)
    }

    @Test fun blockReferencesKeepTheCorrectBlockAndWholeQrMethodCounts() = inBothLanguages { ja ->
        val first = recordedCandidate(2)
        val record = group(first.copy(alternativeDerivations = listOf(conventionalAlternative(first))), ja)
        assertTrue(record.summary.contains(if (ja) "2通りの計算で同じQRが得られました。" else "2 calculation methods yielded the same QR."))
        for (block in 1..2) {
            assertTrue(record.summary.contains(if (ja) "ブロック$block" else "Block $block"))
            assertTrue(record.text.contains(if (ja) "方法Aのブロック${block}と同じ計算記録"
                else "Same calculation record as Method A, block $block"))
        }
        assertFalse(record.summary.contains(if (ja) "方法C" else "Method C"))
    }

    @Test fun matchingCountsCannotHideDifferentInputsAssumptionsOrBlockTargets() = inBothLanguages { ja ->
        val first = recordedCandidate()
        val ordinary = conventionalAlternative(first)
        val variants = listOf(
            ordinary.copy(extraction = first.extraction.copy(codewords = first.extraction.codewords.mapIndexed { i, word ->
                if (i == 0) word.copy(value = 1, unknownBits = 254) else word })),
            ordinary.copy(constraintForcedByUserSetting = true),
            ordinary.copy(priorityAssignments = mapOf(Coord(10, 10) to ModuleValue.ONE)),
            ordinary.copy(repairInfo = ordinary.repairInfo.mapIndexed { i, info ->
                if (i == 0) info.copy(constraintValuesByBit = mapOf(0 to 1)) else info }),
            ordinary.copy(displayConditions = RecoveryDisplayConditions(true, true, null, null, 0)),
            ordinary.copy(blockDerivations = ordinary.blockDerivations.mapValues { (_, routes) ->
                listOf(routes.first(), routes.last().copy(errataPositions = listOf(0, 1, 3))) }),
        )
        for (variant in variants) {
            val record = group(first.copy(alternativeDerivations = listOf(variant)), ja)
            assertFalse(record.text.contains(if (ja) "同じ計算記録" else "Same calculation record"))
            assertEquals(2, record.text.split(if (ja) "方法: 部分bit制約" else "Method: Partial-bit constraints").size - 1)
        }
    }

    @Test fun differingObservedValuesAndCellLocationsAreExplainedInTheMethodDetails() = inBothLanguages { ja ->
        val first = recordedCandidate()
        val other = conventionalAlternative(first).copy(extraction = first.extraction.copy(
            codewords = first.extraction.codewords.mapIndexed { index, word ->
                if (index == 0) word.copy(value = 1, unknownBits = 254,
                    coordinates = word.coordinates.mapIndexed { bit, coord -> if (bit == 7) Coord(12, 12) else coord })
                else word
            }))
        val record = group(first.copy(alternativeDerivations = listOf(other)), ja)
        assertTrue(record.text.contains(if (ja) "計算入力の違い" else "Calculation input differences"))
        assertTrue(record.text.contains(if (ja) "方法A: (7,0)=? / この方法: (12,12)=1"
            else "Method A: (7,0)=? / This method: (12,12)=1"))
        assertFalse(record.text.contains("→"))
        assertFalse(record.text.contains(if (ja) "同じ計算記録" else "Same calculation record"))
    }

    @Test fun countsWithoutProvenanceAreNeverClaimedAsSharedStructureOrBlockCalculations() = inBothLanguages { ja ->
        val first = recordedCandidate().copy(repairInfo = emptyList())
        val other = conventionalAlternative(recordedCandidate()).copy(repairInfo = emptyList())
        val record = group(first.copy(alternativeDerivations = listOf(other)), ja)
        assertEquals(2, record.summary.split(if (ja) "構造補完（1bit）" else "structure filling (1 bits)").size - 1)
        assertFalse(record.text.contains(if (ja) "同じ計算記録" else "Same calculation record"))
        assertTrue(record.text.contains(if (ja) "位置・値は一部未記録" else "locations or values are not fully recorded"))
    }

    @Test fun methodLabelsRemainDistinctBeyondTwentySixRecords() {
        assertEquals(listOf("A", "Z", "AA", "AB"), listOf(0, 25, 26, 27).map(::recoveryMethodLabel))
    }

    @Test fun aSingleMethodHasNoLetterCountOrCellExplanationMarker() = inBothLanguages { ja ->
        val record = group(recordedCandidate(), ja)
        assertFalse(record.summary.contains(if (ja) "通りの計算" else "calculation methods yielded"))
        assertFalse((record.summary + record.text).contains(if (ja) "方法A" else "Method A"))
        assertFalse(record.summary.contains(if (ja) "セルの説明に使用" else "used for cell explanations"))
        assertTrue(record.summary.contains(if (ja) "部分bit制約：自由度0" else "Partial-bit constraints: 0 degrees of freedom"))
        assertTrue(record.text.contains(if (ja) "セル (0,0): 補完前 ? / 補完後 0" else "Cell (0,0): before filling ? / after filling 0"))
    }

    private fun withoutStructureFilling(first: RecoveryCandidate): RecoveryCandidate = first.copy(
        constraintFilledBits = 0,
        repairInfo = first.repairInfo.map { it.copy(postConstraintUnknownMask = it.originalUnknownMask,
            constraintKindsByBit = emptyMap(), constraintValuesByBit = emptyMap()) },
    )

    @Test fun equivalentUnfilledCalculationIsExplainedOnTheFillingMethodInEitherOrder() = inBothLanguages { ja ->
        val filled = recordedCandidate().copy(displayConditions = RecoveryDisplayConditions(false, true, null, null, 0))
        val unfilled = withoutStructureFilling(filled)
        val note = if (ja) "補完なしでも同じQR" else "same QR without filling"
        for (routes in listOf(listOf(filled, unfilled), listOf(unfilled, filled))) {
            val record = group(routes.first().copy(alternativeDerivations = routes.drop(1)), ja)
            val fillingLabel = if (routes.first() === filled) "A" else "B"
            val fillingSummary = record.summary.substringAfter((if (ja) "方法" else "Method ") + fillingLabel)
                .substringBefore("\n" + (if (ja) "方法B" else "Method B"))
            assertEquals(1, record.summary.split(note).size - 1)
            assertTrue(fillingSummary.contains(note))
            assertTrue(record.summary.contains(if (ja) "構造補完（なし）" else "structure filling (none)"))
            val fillingDetails = record.text.substringAfter((if (ja) "方法" else "Method ") + fillingLabel)
                .substringBefore("\n" + (if (ja) "方法B" else "Method B"))
            assertTrue(fillingDetails.contains(if (ja) "セル (0,0): 補完前 ? / 補完後 0" else "Cell (0,0): before filling ? / after filling 0"))
            assertEquals(1, record.text.split(if (ja) "構造補完の記録" else "Structure-filling record").size - 1)
            assertFalse(record.text.contains("→"))
            assertFalse(record.summary.contains(if (ja) "保持している方法" else "Retained methods"))
        }
    }

    @Test fun recoveredQrWithAndWithoutFillingKeepsItsActualInputChangesVisible() {
        val qr = Encoder.encode("HELLO", ErrorCorrectionLevel.L,
            mapOf(EncodeHintType.QR_VERSION to 1, EncodeHintType.QR_MASK_PATTERN to 0))
        val matrix = QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
        val extraction = CodewordExtractor.extract(matrix, 1, qr.maskPattern)
        // Distribute 42 unknown bits across nine words, beyond the conventional seven-erasure limit.
        for (coord in (8..16).flatMap { raw -> extraction.codewords[raw].coordinates.take(5) }.take(42))
            matrix.set(coord.x, coord.y, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        val selected = RecoveryEngine().recover(matrix, formatConstraint = QrFormatConstraint(qr.ecLevel, qr.maskPattern),
            maxKnownDataEccMismatches = 0, displayLockManualBits = true).single()
        assertEquals("HELLO", selected.text)
        val methods = selected.groupedMethodRecords().map { it.representative }
        assertTrue(methods.any { it.constraintFilledBits == 42 })
        assertTrue(methods.any { it.constraintFilledBits == 0 })
        inBothLanguages { ja ->
            val record = group(selected, ja)
            assertTrue(record.summary.contains(if (ja) "構造補完（42bit、補完なしでも同じQR）"
                else "structure filling (42 bits, same QR without filling)"))
            assertTrue(record.summary.contains(if (ja) "構造補完（なし）" else "structure filling (none)"))
            assertTrue(record.text.contains(if (ja) "補完前 ? / 補完後" else "before filling ? / after filling"))
        }
    }

    @Test fun matchingQrDoesNotImplyFillingCanBeOmittedUnderDifferentOrUnrecordedConditions() = inBothLanguages { ja ->
        val settings = RecoveryDisplayConditions(false, true, null, null, 0)
        val filled = recordedCandidate().copy(displayConditions = settings)
        val unfilled = withoutStructureFilling(filled)
        val variants = listOf(
            unfilled.copy(priorityAssignments = mapOf(Coord(0, 0) to ModuleValue.ZERO)),
            unfilled.copy(displayConditions = settings.copy(dataEccLimit = 1)),
            unfilled.copy(displayConditions = null),
            unfilled.copy(formatSelection = FormatSelection(true, false)),
            unfilled.copy(formatRoute = FormatSelectionReason.SEARCH),
            unfilled.copy(constraintForcedByUserSetting = true, singleSegmentStatus = SingleSegmentStatus.APPLIED),
            unfilled.copy(listDecodingMismatchLimit = 1),
            unfilled.copy(dataMask = 1),
            unfilled.copy(correctedInterleaved = intArrayOf(1, 0, 0)),
            unfilled.copy(repairInfo = emptyList()),
            unfilled.copy(repairInfo = filled.repairInfo),
            unfilled.copy(extraction = unfilled.extraction.copy(codewords = unfilled.extraction.codewords.mapIndexed { index, word ->
                if (index == 0) word.copy(unknownBits = 127) else word })),
            unfilled.copy(extraction = unfilled.extraction.copy(codewords = unfilled.extraction.codewords.mapIndexed { index, word ->
                if (index == 0) word.copy(coordinates = word.coordinates.map { Coord(it.x, it.y + 1) }) else word })),
        )
        val note = if (ja) "補完なしでも同じQR" else "same QR without filling"
        for ((index, other) in variants.withIndex()) {
            val record = group(filled.copy(alternativeDerivations = listOf(other)), ja)
            assertFalse("Different conditions at index $index", record.summary.contains(note))
        }
        for (missing in listOf(filled.copy(displayConditions = null),
            filled.copy(displayConditions = settings.copy(lockManualBits = null)), filled.copy(repairInfo = emptyList()))) {
            val record = group(missing.copy(alternativeDerivations = listOf(withoutStructureFilling(missing))), ja)
            assertFalse(record.summary.contains(note))
        }
    }

    @Test fun fillingDetailsDistinguishUnknownValuesKnownChangesAndMaskedPriorityAssignments() = inBothLanguages { ja ->
        val first = recordedCandidate()
        val changed = first.copy(extraction = first.extraction.copy(codewords = first.extraction.codewords.mapIndexed { index, word ->
            if (index == 0) word.copy(value = 128, unknownBits = 127) else word }))
        val priorGuess = first.copy(priorityAssignments = mapOf(Coord(0, 0) to ModuleValue.ZERO))
        assertTrue(CodewordExtractor.maskApplies(priorGuess.dataMask, 0, 0))
        for (route in listOf(changed, priorGuess)) {
            val record = group(route, ja)
            assertTrue(record.text.contains(if (ja) "セル (0,0): 補完前 1 / 補完後 0"
                else "Cell (0,0): before filling 1 / after filling 0"))
        }
    }
}
