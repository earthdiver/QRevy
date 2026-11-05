package io.github.earthdiver.qrrepair

import java.util.concurrent.Executor

/** Schedules preview work and publishes only results accepted by the editor's current requests. */
internal class PreprocessPreviewCoordinator(
    private val editor: PreprocessEditorModel,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val onChanged: () -> Unit,
) {
    var geometryDragging = false
        private set
    var samplingPending = false
        private set

    fun requestCorrection(controls: PreprocessControlSnapshot, grid: PreprocessGeometrySnapshot) {
        if (geometryDragging) return
        val request = editor.requestCorrection(controls, grid) ?: return
        executor.execute {
            val result = runCatching { editor.correction.calculate(request) }
            dispatchToUi {
                val calculation = result.getOrDefault(DistortionPreviewState.Calculation(emptyList()))
                if (editor.correction.accept(request, calculation.candidates, result.isFailure, calculation.memoryLimited)) {
                    onChanged()
                }
            }
        }
    }

    fun sample(
        controls: () -> PreprocessControlSnapshot,
        grid: () -> PreprocessGeometrySnapshot,
    ): PreprocessEditorModel.Preview? {
        editor.requestObservations(controls(), grid())?.let { request ->
            executor.execute {
                val result = runCatching { editor.calculateObservations(request) }
                dispatchToUi {
                    if (editor.acceptObservations(request, result)) onChanged()
                }
            }
        }
        // An inline completion can refresh controls before this call resumes sampling.
        return editor.sample(controls(), grid()).also { samplingPending = it == null }
    }

    fun selectCorrection(index: Int) {
        editor.correction.select(index)
        editor.invalidateSampling()
    }

    fun geometryEdited() {
        geometryDragging = true
        editor.correction.invalidate()
        editor.invalidateSampling()
    }

    fun geometryEditFinished() {
        geometryDragging = false
    }

    fun release() = editor.release()
}
