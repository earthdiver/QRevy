package io.github.earthdiver.qrrepair.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.*
import org.junit.Test

class QuickAutoRecoveryTest {
    @Test fun erasedOuterColumnsRecoverFromAnAutomaticallyAnchoredGrid() {
        val payload = "GRID ANCHOR 42"
        val qr = Encoder.encode(payload, ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 3))
        val n = qr.matrix.width
        val pitch = 8; val margin = 12; val size = n * pitch + margin * 2
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val px = i % size - margin; val py = i / size - margin
            val x = px / pitch; val y = py / pitch
            when {
                px < 0 || py < 0 || x >= n || y >= n -> 245
                x < 2 || (x < 8 && (y < 8 || y >= n - 8)) -> 120
                qr.matrix.get(x, y).toInt() == 1 -> 12
                else -> 245
            }
        })
        val initial = requireNotNull(InitialGridEstimator.estimate(image, emptyList()))
        assertEquals(3, initial.version)
        assertEquals(InitialGridSource.PERIODICITY, initial.source)
        val settings = PreprocessSettings(automaticThresholds = true, photographicSource = true)
            .withInitialGrid(initial).withInitialErrorLimits()
        val result = QuickAutoRecovery().run(image, settings, AutomaticScanBudget(clockMillis = { 0 }))
        assertEquals(payload, result.selected?.text)
        assertEquals(RecoveryDiscoveryCount.Exact(1), result.recovery.discoveredCandidates)
        assertFalse(result.recovery.searchDiagnostic!!.unresolvedCalculations)
    }

    private fun matrix(): QrMatrix {
        val qr = Encoder.encode("AUTO 42", ErrorCorrectionLevel.H, mapOf(EncodeHintType.QR_VERSION to 1))
        return QrMatrix(qr.matrix.width).apply {
            for (y in 0 until dimension) for (x in 0 until dimension)
                set(x, y, if (qr.matrix.get(x, y).toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        }
    }

    @Test fun automaticAndDetailedDefaultAllowancesAreFivePercent() {
        assertEquals(listOf(11, 18, 28, 40), (1..4).map(ErrorLimitPolicy::automaticScanData))
        assertEquals(listOf(11, 18, 28, 40), (1..4).map { PreprocessSettings(version = it).withInitialErrorLimits().effectiveDataEccMismatchLimit })
        for (version in 1..40) assertEquals(ErrorLimitPolicy.listDecodingData(version), ErrorLimitPolicy.automaticScanData(version))
    }

    @Test fun eightOrientationsRecoverObservedErrorsAboveTheOldDefaultAndPreserveSavedLm() {
        val observed = matrix()
        val original = RecoveryEngine().recover(observed).first { it.text == "AUTO 42" }
        original.extraction.codewords.first().coordinates.forEach { c ->
            observed.set(c.x, c.y, if (observed[c.x, c.y].value == ModuleValue.ONE) ModuleValue.ZERO else ModuleValue.ONE,
                1f, ModuleSource.IMAGE)
        }
        val size = (observed.dimension + 8) * 4
        val image = LumaImage(size, size, IntArray(size * size) { i ->
            val x = i % size / 4 - 4; val y = i / size / 4 - 4
            if (x in 0 until observed.dimension && y in 0 until observed.dimension && observed[x, y].value == ModuleValue.ONE) 0 else 255
        })
        val quad = ImageQuad(PointD(17.5, 17.5), PointD(97.5, 17.5), PointD(97.5, 97.5), PointD(17.5, 97.5))
        val savedLm = GridGeometry(1, FinderRole.TOP_LEFT, 30.0, 30.0, 4.0, 0.0, quad, GeometryModel.MANUAL_PROJECTIVE)
        val orientations = QuickAutoRecovery.orientations(quad)
        assertEquals(8, orientations.toSet().size)
        for (inputQuad in orientations) {
            val saved = PreprocessSettings(manualQuad = inputQuad, logicalGridAssigned = false,
                automaticThresholds = true, photographicSource = true, lmCorrectionEnabled = false,
                lmGeometry = savedLm, knownBitErrorLimit = 2).withInitialErrorLimits()
            val result = QuickAutoRecovery().run(image, saved, AutomaticScanBudget(clockMillis = { 0 }))
            assertEquals("AUTO 42", result.selected?.text)
            assertEquals(AutomaticScanAssumption.NONE, result.assumption)
            assertEquals(RecoveryDiscoveryCount.Exact(1), result.recovery.discoveredCandidates)
            assertEquals(11, result.selected?.displayConditions?.dataEccLimit)
            assertEquals(0, result.selected?.displayConditions?.partialBitSearchLimit)
            assertFalse(result.recovery.searchDiagnostic!!.knownBitSearchAttempted)
            assertEquals(0L, result.recovery.searchDiagnostic.knownBitTrials)
            assertEquals(2, saved.knownBitErrorLimit)
            assertFalse(saved.lmCorrectionEnabled)
            assertSame(savedLm, saved.lmGeometry)
            assertFalse(saved.distortionCorrection)
            assertEquals(11, saved.effectiveDataEccMismatchLimit)
        }
    }

    @Test fun uniqueMeansOneValidatedQrInACompletedTrialNotOneRetainedItem() {
        val outcome = RecoveryEngine().recoverWithDiagnostics(matrix(), executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_BASIC)
        assertTrue(QuickAutoRecovery.canReport(outcome))
        assertFalse(QuickAutoRecovery.canReport(outcome.copy(discoveredCandidates = RecoveryDiscoveryCount.Exact(2))))
        assertFalse(QuickAutoRecovery.canReport(outcome.copy(discoveredCandidates = RecoveryDiscoveryCount.AtLeast(1))))
        for (diagnostic in listOf(outcome.searchDiagnostic.copy(coverageComplete = false),
            outcome.searchDiagnostic.copy(candidatesOmitted = true),
            outcome.searchDiagnostic.copy(unsearched = true),
            outcome.searchDiagnostic.copy(incompleteReasons = setOf(SearchIncompleteReason.INTERNAL_ERROR)))) {
            assertFalse(QuickAutoRecovery.canReport(outcome.copy(searchDiagnostic = diagnostic)))
        }
        for (stop in CombinationSearchStop.entries) {
            assertFalse(QuickAutoRecovery.canReport(outcome.copy(candidates = outcome.candidates.map {
                it.copy(combinationSearch = CombinationSearchSummary(1, setOf(stop)))
            })))
        }
    }

    @Test fun counterKeepsEarlierCandidatesAcrossGeometryAndDecoderTrials() {
        val counter = RecoveryDiscoveryCounter()
        val engine = RecoveryEngine()
        val first = engine.recoverWithDiagnostics(matrix(), discoveryCounter = counter,
            executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_BASIC)
        val candidate = first.candidates.single { it.isSelectableRecovery }
        counter.record(candidate.copy(dataMask = (candidate.dataMask + 1) % 8))
        val next = engine.recoverWithDiagnostics(matrix(), discoveryCounter = counter,
            executionPolicy = RecoveryExecutionPolicy.AUTOMATIC_EXTENDED)
        assertEquals(RecoveryDiscoveryCount.Exact(2), next.discoveredCandidates)
        assertFalse(QuickAutoRecovery.canReport(next))
    }

    @Test fun slicesAndAllStagesShareOneDeadlineAndStopRequest() {
        var now = 100L
        var stop = false
        val budget = AutomaticScanBudget(stopRequested = { stop }, clockMillis = { now })
        now += 2_900 // Earlier repair trials consumed this part of the allowance.
        val first = budget.slice(200)
        assertFalse(first())
        now += 100
        assertTrue(first())
        assertTrue(budget.slice(200)())
        now = 100
        stop = true
        assertTrue(budget.stopped())
    }

    @Test fun missingAlignmentVerifiesTheOriginalAfterCurvesAndBeforeLm() {
        val image = LumaImage(29, 29, IntArray(29 * 29) { 128 })
        val settings = PreprocessSettings(manualQuad = ImageQuad(PointD(4.0, 4.0), PointD(24.0, 4.0),
            PointD(24.0, 24.0), PointD(4.0, 24.0)), blackMax = 80, whiteMin = 175,
            automaticThresholds = false, lmCorrectionEnabled = false)
        val trace = AutomaticScanTrace()
        // End at the deferred LM stage; the test does not depend on machine speed.
        val result = QuickAutoRecovery().run(image, settings, AutomaticScanBudget(clockMillis = { 0L },
            trace = trace, stopRequested = { trace.report().contains("LM: START") }))
        val report = result.diagnosticReport!!
        assertTrue(report, report.contains("DECODE: DEFER reason=IMAGE_CORRECTION_FIRST"))
        assertTrue(report, report.indexOf("CURVE_GENERATION: START") >= 0)
        assertTrue(report, report.indexOf("CURVE_GENERATION: START") < report.indexOf("LM: START"))
        assertTrue(report, report.indexOf("CURVE_GENERATION: START") < report.indexOf("UNCORRECTED_GRID: VERIFY"))
        assertTrue(report, report.indexOf("UNCORRECTED_GRID: VERIFY") < report.indexOf("DECODE: START"))
        assertTrue(report, report.indexOf("DECODE: START") < report.indexOf("LM: START"))
        assertNull(result.selected)
        assertFalse(settings.lmCorrectionEnabled)
    }

    @Test fun automaticAssumptionsDisableKnownBitSearchWithoutChangingDetailedSettings() {
        for (savedLimit in 0..2) for (assumption in AutomaticScanAssumption.entries) {
            val saved = PreprocessSettings(knownBitErrorLimit = savedLimit)
            assertEquals(0, QuickAutoRecovery.executionSettings(saved, assumption).knownBitErrorLimit)
            assertEquals(savedLimit, saved.knownBitErrorLimit)
        }
    }

    @Test fun automaticPhasesNeverRunTentativeRsAssignedListsOrKnownBitSearch() {
        for (policy in RecoveryExecutionPolicy.entries.filter { it != RecoveryExecutionPolicy.DETAILED }) {
            for (knownBitLimit in 0..2) {
                val run = RecoveryRunState(QrMatrix(21), RecoveryRetentionBudget(), RecoveryCancellation.NONE, 0,
                    ListDecodingBudget(clockMillis = { 0 }), knownBitErrorLimit = knownBitLimit)
                val phases = mutableListOf<RecoveryPhase>()
                RecoveryPhaseRunner(run, 11, RecoveryCancellation.NONE, { false },
                    searchPhase = { phases += run.phase }, finish = { it }, executionPolicy = policy).execute()
                assertFalse(RecoveryPhase.PARTIAL_RS in phases)
                assertFalse(RecoveryPhase.ADDITIONAL_LIST in phases)
                assertEquals(if (policy == RecoveryExecutionPolicy.AUTOMATIC_EXACT) ListDecodingStatus.ZERO_ERROR_LIMIT
                    else ListDecodingStatus.DISABLED, run.listDecodingStatuses[ListDecodingStage.ADDITIONAL])
                assertFalse(RecoveryPhase.KNOWN_BIT_ERRORS in phases)
                assertFalse(run.searchDiagnostic().knownBitSearchAttempted)
                assertEquals(0L, run.searchDiagnostic().knownBitTrials)
            }
        }
    }

    @Test fun detailedPhasesStillHonorKnownBitSearchSetting() {
        for (knownBitLimit in 0..2) {
            val run = RecoveryRunState(QrMatrix(21), RecoveryRetentionBudget(), RecoveryCancellation.NONE, 0,
                ListDecodingBudget(clockMillis = { 0 }), knownBitErrorLimit = knownBitLimit)
            val phases = mutableListOf<RecoveryPhase>()
            RecoveryPhaseRunner(run, 11, RecoveryCancellation.NONE, { false },
                searchPhase = { phases += run.phase }, finish = { it }).execute()
            assertEquals(knownBitLimit > 0, RecoveryPhase.KNOWN_BIT_ERRORS in phases)
            assertEquals(knownBitLimit > 0, run.searchDiagnostic().knownBitSearchAttempted)
        }
    }
}
