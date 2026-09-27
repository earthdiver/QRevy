package io.github.earthdiver.qrrepair

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.FormatEditPolicy
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.QrFormatConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessSearchSettingsStateTest {
    @Test fun conflictingFormatSelectionPreservesPreviousSelectionAndCanBeRetried() {
        val previous = QrFormatConstraint(ErrorCorrectionLevel.L)
        val pins = FormatEditPolicy.fixedValues(1, QrFormatConstraint(ErrorCorrectionLevel.L, 0))
        val state = PreprocessSearchSettingsState(PreprocessSettings(formatConstraint = previous))

        val conflict = state.updateFormatConstraint(1, ErrorCorrectionLevel.M, 1, pins)

        assertNotNull(conflict)
        assertTrue(conflict!!.cells.all { it.actual == pins[it.coord] })
        assertEquals(previous, state.formatConstraint)
        assertNull(state.updateFormatConstraint(1, ErrorCorrectionLevel.L, 0, pins))
        assertEquals(QrFormatConstraint(ErrorCorrectionLevel.L, 0), state.formatConstraint)
        assertNull(state.formatConflict(1, pins))
    }

    @Test fun restoredConflictAllowsStepwiseReturnToAutomaticSelection() {
        val pins = FormatEditPolicy.fixedValues(1, QrFormatConstraint(ErrorCorrectionLevel.L, 0))
        val state = PreprocessSearchSettingsState(PreprocessSettings(
            formatConstraint = QrFormatConstraint(ErrorCorrectionLevel.M, 0),
        ))
        assertNotNull(state.formatConflict(1, pins))

        assertNull(state.updateFormatConstraint(1, ErrorCorrectionLevel.M, null, pins))
        assertEquals(QrFormatConstraint(ErrorCorrectionLevel.M), state.formatConstraint)
        // Returning one field to Auto is allowed, but remaining conflicts still block saving.
        assertNotNull(state.formatConflict(1, pins))
        assertNull(state.updateFormatConstraint(1, null, null, pins))
        assertNull(state.formatConstraint)
        assertNull(state.formatConflict(1, pins))
    }

    @Test fun changingVersionRevalidatesManualLimitWithoutDiscardingIt() {
        val state = PreprocessSearchSettingsState(PreprocessSettings())
        state.setDataEccMismatchLimit(ErrorLimitMode.MANUAL, 400)
        assertTrue(state.dataEccLimitIsValid(5))

        assertFalse(state.dataEccLimitIsValid(1))
        assertEquals(400, state.effectiveDataEccMismatchLimit(1))
        assertEquals(400, state.dataEccMismatchLimit)
        assertTrue(state.dataEccLimitIsValid(5))

        state.setDataEccMismatchLimit(ErrorLimitMode.UNLIMITED, state.dataEccMismatchLimit)
        assertTrue(state.dataEccLimitIsValid(1))
        assertNull(state.effectiveDataEccMismatchLimit(1))
        assertEquals(400, state.dataEccMismatchLimit)
        state.setDataEccMismatchLimit(ErrorLimitMode.MANUAL, 208)
        assertTrue(state.dataEccLimitIsValid(1))
        state.setDataEccMismatchLimit(ErrorLimitMode.MANUAL, 209)
        assertFalse(state.dataEccLimitIsValid(1))
    }

    @Test fun automaticDataLimitFollowsVersionWithoutOverwritingRememberedManualValue() {
        val state = PreprocessSearchSettingsState(PreprocessSettings(
            dataEccMismatchLimitMode = ErrorLimitMode.AUTOMATIC,
            dataEccMismatchLimit = 400,
        ))

        assertEquals(7, state.effectiveDataEccMismatchLimit(4))
        assertEquals(108, state.effectiveDataEccMismatchLimit(5))
        assertTrue(state.dataEccLimitIsValid(4))
        assertTrue(state.dataEccLimitIsValid(5))
        assertEquals(400, state.dataEccMismatchLimit)
    }
}
