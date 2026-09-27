package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.RecoveryCancellation
import io.github.earthdiver.qrrepair.core.RecoveryCancelledException
import java.util.concurrent.Executor

/** Runs recovery work against the generation shared with image imports and settings changes. */
internal class RecoveryTaskRunner(
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Int,
) {
    /**
     * Cancellation discards obsolete work; a user's stop request remains part of the task so that
     * it can return retained candidates. Cleanup runs on the UI thread even for obsolete work.
     * Without an error callback, unexpected exceptions still propagate on the worker thread.
     */
    fun <T> execute(
        generation: Int,
        task: (RecoveryCancellation) -> T,
        onResult: (T) -> Unit,
        onFinished: () -> Unit,
        onError: ((Exception) -> Unit)? = null,
    ) {
        val cancellation = RecoveryCancellation {
            generation != currentGeneration() || Thread.currentThread().isInterrupted
        }
        executor.execute {
            var completion: (() -> Unit)? = null
            try {
                cancellation.check()
                val result = task(cancellation)
                completion = { onResult(result) }
            } catch (_: RecoveryCancelledException) {
                // Superseded work must not publish a failure or replace the current result.
            } catch (error: Exception) {
                if (onError == null) throw error
                completion = { onError(error) }
            } finally {
                val deliver = completion
                dispatchToUi {
                    onFinished()
                    // The generation may have changed while this callback waited in the UI queue.
                    if (generation == currentGeneration()) deliver?.invoke()
                }
            }
        }
    }
}
