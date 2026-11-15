package io.github.earthdiver.qrrepair

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class QuickAutoStartupStateTest {
    @Test fun concurrentImportsShareOneStartupAllowance() {
        val state = QuickAutoStartupState()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val claims = (1..20).map { workers.submit<Boolean> { state.claim() } }
            assertEquals(1, claims.count { it.get() })
            assertFalse(state.claim())
            assertTrue(QuickAutoStartupState().claim())
        } finally {
            workers.shutdownNow()
        }
    }
}
