package io.github.earthdiver.qrrepair

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RecoveryDataDisplayStateTest {
    @Test
    fun repairedAndUnmaskedStagesAreSelectedInitially() {
        val state = RecoveryDataDisplayState()
        assertTrue(state.repairedStage)
        assertTrue(state.unmasked)
        assertFalse(state.partialInformation)
    }
}
