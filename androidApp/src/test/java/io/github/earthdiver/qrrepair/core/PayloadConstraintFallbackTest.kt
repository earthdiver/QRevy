package io.github.earthdiver.qrrepair.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadConstraintFallbackTest {
    @Test fun anyConstraintMutationKeepsTheUnconstrainedRoute() {
        assertTrue(shouldRetryWithoutPayloadConstraints(allowPayloadConstraints = true, filledBits = 1))
        assertFalse(shouldRetryWithoutPayloadConstraints(allowPayloadConstraints = true, filledBits = 0))
        assertFalse(shouldRetryWithoutPayloadConstraints(allowPayloadConstraints = false, filledBits = 8))
    }
}
