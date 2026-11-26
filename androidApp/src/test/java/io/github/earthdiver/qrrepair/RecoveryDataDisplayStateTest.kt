package io.github.earthdiver.qrrepair

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RecoveryDataDisplayStateTest {
    @Test
    fun repairedAndUnmaskedStagesAreSelectedInitially() {
        val state = RecoveryDataDisplayState.forRecovery(hasValidatedCandidate = true)
        assertTrue(state.repairedStage)
        assertTrue(state.unmasked)
        assertFalse(state.partialInformation)
    }

    @Test
    fun failedRecoveryStartsWithUnmaskedPartialInput() {
        val state = RecoveryDataDisplayState.forRecovery(hasValidatedCandidate = false)
        assertFalse(state.repairedStage)
        assertTrue(state.unmasked)
        assertTrue(state.partialInformation)
    }
}
