package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.QrFormatConstraint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreprocessDialogStateTest {
    private val quad = ImageQuad(PointD(1.0, 2.0), PointD(22.0, 2.0), PointD(22.0, 23.0), PointD(1.0, 23.0))

    @Test fun selectedPartialBitLimitSurvivesCaptureAndSamplingProfiles() {
        for (limit in 12..14) {
            val state = PreprocessDialogState(PreprocessSettings())
            state.search.partialBitSearchLimit = limit
            val captured = state.captureSettings(controls(),
                geometry(), emptyMap(), emptySet())
            assertEquals(limit, captured.partialBitSearchLimit)
            captured.variants().forEach { assertEquals(limit, it.settings.partialBitSearchLimit) }
            assertEquals(limit, captured.recoverySnapshot().partialBitSearchLimit)
        }
    }

    @Test fun selectedListExtensionSurvivesCaptureAndSamplingProfiles() {
        for (limit in 0..3) {
            val state = PreprocessDialogState(PreprocessSettings())
            state.search.listDecodingAdditionalErrors = limit
            val captured = state.captureSettings(controls(),
                geometry(), emptyMap(), emptySet())
            assertEquals(limit, captured.listDecodingAdditionalErrors)
            captured.variants().forEach { assertEquals(limit, it.settings.listDecodingAdditionalErrors) }
            assertEquals(limit, captured.recoverySnapshot().listDecodingAdditionalErrors)
        }
    }

    @Test fun toneChangesSurviveGeometryChangesAndPreserveManualEdits() {
        val tones = io.github.earthdiver.qrrepair.core.ImageAdjustment(25, -10, true)
        val state = PreprocessDialogState(PreprocessSettings(imageAdjustment = tones))
        state.rotateBy(90f)
        state.applyPerspectiveCorrection(quad)
        val edits = mapOf(Coord(12, 12) to ModuleValue.ONE)
        val captured = state.captureSettings(controls().copy(lockManualBits = true), geometry(), edits, emptySet())
        assertEquals(tones, captured.imageAdjustment)
        assertEquals(edits, captured.fixedPreRecoveryEdits)
        state.imageAdjustment = io.github.earthdiver.qrrepair.core.ImageAdjustment()
        val reset = state.captureSettings(controls().copy(lockManualBits = true), geometry(), edits, emptySet())
        assertEquals(quad, reset.perspectiveCorrectionQuad)
        assertEquals(90f, reset.rotationDegrees)
        assertEquals(edits, reset.fixedPreRecoveryEdits)
    }

    @Test
    fun previewAndSavedSettingsUseTheSameNormalizedThresholds() {
        val state = PreprocessDialogState(PreprocessSettings())
        val controls = controls(blackProgress = 220, whiteProgress = 100)

        val preview = state.previewSettings(controls)
        val saved = state.captureSettings(controls, geometry(), emptyMap(), emptySet())

        assertEquals(220, preview.blackMax)
        assertEquals(221, preview.whiteMin)
        assertEquals(preview.blackMax, saved.blackMax)
        assertEquals(preview.whiteMin, saved.whiteMin)
    }

    @Test
    fun geometryChangeNormalizesRotationAndClearsPerspectiveCorrection() {
        val state = PreprocessDialogState(PreprocessSettings())

        state.rotateBy(270f)

        assertEquals(-90f, state.rotationDegrees)
        assertNull(state.perspectiveCorrectionQuad)
    }

    @Test
    fun captureAlwaysUsesFixedGeometryAndKeepsNeighborhoodChoice() {
        val state = PreprocessDialogState(PreprocessSettings())
        val saved = state.captureSettings(
            controls(lockCorners = true, lockVersion = false, search = true),
            geometry(),
            emptyMap(),
            emptySet(),
        )

        assertEquals(true, saved.lockCorners)
        assertEquals(true, saved.lockVersion)
        assertEquals(true, saved.searchQuadNeighborhood)
        assertEquals(FinderPolarity.NORMAL, saved.confirmedFinderPolarity)
    }

    @Test
    fun capturePreservesValidatedPriorityEditsWhenGeometryIsLocked() {
        val state = PreprocessDialogState(PreprocessSettings())
        val coordinate = Coord(10, 10)

        val saved = state.captureSettings(
            controls(),
            geometry(),
            mapOf(coordinate to ModuleValue.ERASED),
            setOf(coordinate),
        )

        assertEquals(setOf(coordinate), saved.preRecoveryPrioritySearch)
    }

    @Test
    fun legacyDisabledFinderMarginCorrectionIsPreservedWithoutAUiControl() {
        val state = PreprocessDialogState(PreprocessSettings(quietZoneCorrection = false))

        val saved = state.captureSettings(controls(), geometry(), emptyMap(), emptySet())

        assertEquals(false, saved.quietZoneCorrection)
    }

    @Test
    fun capturePreservesTheSelectedRecoveryFormat() {
        val selected = QrFormatConstraint(ecLevel = ErrorCorrectionLevel.Q)
        val state = PreprocessDialogState(PreprocessSettings())

        assertNull(state.search.updateFormatConstraint(1, selected.ecLevel, selected.dataMask, emptyMap()))
        val saved = state.captureSettings(
            controls(),
            geometry(),
            emptyMap(),
            emptySet(),
        )

        assertEquals(selected, saved.formatConstraint)
    }

    @Test
    fun capturePreservesTheFormatInformationErrorLimit() {
        val state = PreprocessDialogState(PreprocessSettings())

        state.search.setFormatMismatchLimit(ErrorLimitMode.MANUAL, 0)
        val saved = state.captureSettings(
            controls(),
            geometry(),
            emptyMap(),
            emptySet(),
        )

        assertEquals(0, saved.formatMismatchLimit)
    }

    @Test
    fun capturePreservesTheDataEccErrorLimit() {
        val state = PreprocessDialogState(PreprocessSettings())

        state.search.setDataEccMismatchLimit(ErrorLimitMode.MANUAL, 7)
        val saved = state.captureSettings(
            controls(),
            geometry(),
            emptyMap(),
            emptySet(),
        )

        assertEquals(7, saved.dataEccMismatchLimit)
    }

    @Test fun capturedSearchSettingsRemainIndependentAndCanRestoreAnEditorDraft() {
        val initial = PreprocessSettings()
        val state = PreprocessDialogState(initial)
        state.search.setFormatMismatchLimit(ErrorLimitMode.EXACT, 5)
        state.search.setDataEccMismatchLimit(ErrorLimitMode.AUTOMATIC, 400)
        state.search.partialBitSearchLimit = 14
        assertNull(state.search.updateFormatConstraint(1, ErrorCorrectionLevel.Q, 3, emptyMap()))
        val saved = state.captureSettings(controls(), geometry(), emptyMap(), emptySet())

        state.search.setFormatMismatchLimit(ErrorLimitMode.MANUAL, 9)
        state.search.setDataEccMismatchLimit(ErrorLimitMode.UNLIMITED, 10)
        state.search.partialBitSearchLimit = 12
        assertNull(state.search.updateFormatConstraint(1, null, null, emptyMap()))

        assertEquals(PreprocessSettings(), initial)
        assertEquals(QrFormatConstraint(ErrorCorrectionLevel.Q, 3), saved.formatConstraint)
        assertEquals(ErrorLimitMode.EXACT, saved.formatMismatchLimitMode)
        assertEquals(5, saved.formatMismatchLimit)
        assertEquals(0, saved.effectiveFormatMismatchLimit)
        assertEquals(ErrorLimitMode.AUTOMATIC, saved.dataEccMismatchLimitMode)
        assertEquals(400, saved.dataEccMismatchLimit)
        assertEquals(14, saved.partialBitSearchLimit)
        val restored = PreprocessDialogState(saved)
        assertEquals(saved, restored.captureSettings(controls(), geometry(), emptyMap(), emptySet()))
    }

    private fun controls(
        blackProgress: Int = 96,
        whiteProgress: Int = 159,
        lockCorners: Boolean = true,
        lockVersion: Boolean = true,
        search: Boolean = false,
    ) = PreprocessControlSnapshot(
        polarity = PolarityMode.AUTO,
        automaticThresholds = true,
        blackProgress = blackProgress,
        whiteProgress = whiteProgress,
        useUnknownColors = false,
        version = 1,
        lockCorners = lockCorners,
        lockVersion = lockVersion,
        searchQuadNeighborhood = search,
    )

    private fun geometry() = PreprocessGeometrySnapshot(
        logicalGridAssigned = true,
        quad = quad,
        finders = mapOf(FinderRole.TOP_LEFT to PointD(4.0, 5.0)),
        confirmedFinderPolarity = FinderPolarity.NORMAL,
    )
}
