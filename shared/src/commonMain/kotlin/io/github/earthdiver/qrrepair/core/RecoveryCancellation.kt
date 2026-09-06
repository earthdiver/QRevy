package io.github.earthdiver.qrrepair.core

/** Platform-independent cooperative cancellation; callers may check generations or interrupts. */
fun interface RecoveryCancellation {
    fun isCancelled(): Boolean

    fun check() {
        if (isCancelled()) throw RecoveryCancelledException()
    }

    companion object {
        val NONE = RecoveryCancellation { false }
    }
}

/** Cancellation is not a failed QR hypothesis and must escape fallback/error handling. */
class RecoveryCancelledException : RuntimeException("Recovery cancelled")
