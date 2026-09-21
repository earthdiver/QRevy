package io.github.earthdiver.qrrepair.core

import com.google.zxing.qrcode.decoder.Version
import org.junit.Assert.*
import org.junit.Test

class ErrorLimitPolicyTest {
    @Test fun newImportsFollowTheVersionBoundaryAndKeepFormatDefaultStable() {
        val initial = PreprocessSettings(version = 4).withInitialErrorLimits()
        assertEquals(6, initial.effectiveFormatMismatchLimit)
        assertEquals(7, initial.effectiveDataEccMismatchLimit)
        val larger = initial.copy(version = 5)
        assertEquals(6, larger.effectiveFormatMismatchLimit)
        assertEquals(108, larger.effectiveDataEccMismatchLimit)
        assertEquals(7, larger.copy(version = 4).effectiveDataEccMismatchLimit)
    }

    @Test fun explicitPresetsPersistAcrossTheVersionBoundary() {
        val strict = PreprocessSettings(version = 5, dataEccMismatchLimitMode = ErrorLimitMode.STRICT)
        assertEquals(10, strict.effectiveDataEccMismatchLimit)
        val relaxed = strict.copy(version = 4, dataEccMismatchLimitMode = ErrorLimitMode.RELAXED)
        assertEquals(80, relaxed.effectiveDataEccMismatchLimit)
        assertEquals(9, relaxed.copy(formatMismatchLimitMode = ErrorLimitMode.RELAXED).effectiveFormatMismatchLimit)
    }

    @Test fun exactMatchStaysZeroAcrossVersionsWithoutOverwritingManualValues() {
        val settings = PreprocessSettings(formatMismatchLimit = 7, dataEccMismatchLimit = 19,
            formatMismatchLimitMode = ErrorLimitMode.EXACT, dataEccMismatchLimitMode = ErrorLimitMode.EXACT)
        for (version in 1..40) {
            val changed = settings.copy(version = version)
            assertEquals(0, changed.effectiveFormatMismatchLimit)
            assertEquals(0, changed.effectiveDataEccMismatchLimit)
            assertEquals(7, changed.formatMismatchLimit)
            assertEquals(19, changed.dataEccMismatchLimit)
        }
    }

    @Test fun manualZeroAndUnlimitedRemainExplicitAcrossVersionChanges() {
        val manual = PreprocessSettings(formatMismatchLimit = 0, dataEccMismatchLimit = 0)
        assertEquals(0, manual.copy(version = 40).effectiveDataEccMismatchLimit)
        assertEquals(0, manual.effectiveFormatMismatchLimit)
        val unlimited = manual.copy(formatMismatchLimitMode = ErrorLimitMode.UNLIMITED,
            dataEccMismatchLimitMode = ErrorLimitMode.UNLIMITED, version = 40)
        assertNull(unlimited.effectiveFormatMismatchLimit)
        assertNull(unlimited.effectiveDataEccMismatchLimit)
        assertEquals(0, unlimited.dataEccMismatchLimit)
        assertNull(PreprocessSettings().effectiveFormatMismatchLimit)
        assertNull(PreprocessSettings().effectiveDataEccMismatchLimit)
    }

    @Test fun presetsRoundUpWithinEverySupportedVersionWithoutDependingOnEcc() {
        for (version in 1..40) {
            val bits = Version.getVersionForNumber(version).totalCodewords * 8
            val strict = ErrorLimitPolicy.strictData(version)
            val relaxed = ErrorLimitPolicy.relaxedData(version)
            assertTrue(strict in 1..bits)
            assertTrue(relaxed in strict..bits)
            assertTrue(strict * 800 >= bits * 7)
            assertTrue((strict - 1) * 800 < bits * 7)
            assertTrue(relaxed * 10 >= bits)
            assertTrue((relaxed - 1) * 10 < bits)
        }
    }
}
