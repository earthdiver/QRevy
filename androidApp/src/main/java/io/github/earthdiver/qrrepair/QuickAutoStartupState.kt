package io.github.earthdiver.qrrepair

import java.util.concurrent.atomic.AtomicBoolean

/** One startup allowance per process, shared by image, camera, and TXT imports. */
internal class QuickAutoStartupState {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)

    companion object {
        val process = QuickAutoStartupState()
    }
}
