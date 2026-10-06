package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.InitialThresholdEstimator
import io.github.earthdiver.qrrepair.core.MatrixSampler
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.SamplingProfile
import io.github.earthdiver.qrrepair.ui.BitmapUtils

/** A saved draft; the workspace owns persistence and the retained image after handoff. */
internal data class PreprocessSave(
    val settings: PreprocessSettings,
    val cellEdits: PreRecoveryCellEditState,
    val image: BitmapUtils.Prepared,
    val events: List<PreprocessDraftEvent>,
)

/** View-free editor state retained across configuration changes without saving it to disk. */
internal data class PreprocessEditorDraft(
    val settings: PreprocessSettings,
    val cellEdits: PreRecoveryCellEditState,
    val changes: PreprocessDraftState,
    val page: PreprocessPage,
)

/** Composes the editable draft, observation cache, and temporary image ownership without Views. */
internal class PreprocessEditorModel(
    initialSettings: PreprocessSettings,
    private val source: BitmapUtils.Prepared,
    initialWorking: BitmapUtils.Prepared,
    savedCellEdits: PreRecoveryCellEditState?,
    restoredDraft: PreprocessEditorDraft?,
    private val retainsImage: (BitmapUtils.Prepared) -> Boolean,
    validateEdits: (Map<Coord, ModuleValue>) -> Boolean,
) {
    private val initial = restoredDraft?.settings ?: initialSettings
    val settings = PreprocessDialogState(initial)
    val cells = (restoredDraft?.cellEdits ?: savedCellEdits)?.copyForEditing(validateEdits)
        ?: PreRecoveryCellEditState(initial.preRecoveryEdits, initial.preRecoveryPrioritySearch,
            priorityLimit = initial.prioritySearchLimit, validateEdits = validateEdits,
            initialSoftEdits = initial.effectiveSoftEdits)
    private val changes = restoredDraft?.changes?.copyForEditing() ?: PreprocessDraftState(initial)
    private val sampler = PreRecoveryPreviewSampler()
    private var provisionalImage = initialWorking
    var working = initialWorking
        private set

    init { cells.priorityLimit = initial.prioritySearchLimit }

    fun replaceWorking(replacement: BitmapUtils.Prepared) {
        val previous = working
        working = replacement
        releaseIfTemporary(previous, replacement)
    }

    fun release() = releaseIfTemporary(working, source)

    private fun releaseIfTemporary(previous: BitmapUtils.Prepared, replacement: BitmapUtils.Prepared) {
        if (previous !== source && !retainsImage(previous) && previous.bitmap !== replacement.bitmap &&
            !previous.bitmap.isRecycled
        ) previous.bitmap.recycle()
    }

    data class Preview(
        val geometry: GridGeometry,
        val matrix: QrMatrix,
        val profile: SamplingProfile,
        val stats: MatrixSampler.SamplingStats,
    )

    fun sample(controls: PreprocessControlSnapshot, grid: PreprocessGeometrySnapshot): Preview? {
        val q = grid.quad
        val dimension = 17 + 4 * controls.version
        val pitch = ((q.topLeft.distanceTo(q.topRight) + q.topLeft.distanceTo(q.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = runCatching {
            GridGeometry(controls.version, FinderRole.TOP_LEFT, q.topLeft.x, q.topLeft.y,
                pitch, 0.0, q, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        }.getOrNull() ?: return null
        if (working !== provisionalImage) {
            settings.updateProvisionalThresholds(InitialThresholdEstimator.estimate(working.bitmap))
            provisionalImage = working
        }
        val profile = SamplingProfile(settings.previewSettings(controls), inverted = when (grid.confirmedFinderPolarity) {
            FinderPolarity.INVERTED -> true
            FinderPolarity.NORMAL -> false
            else -> controls.polarity == PolarityMode.INVERTED
        })
        val (matrix, stats) = sampler.sample(working.luma, geometry, profile, grid.logicalGridAssigned)
        return Preview(geometry, matrix, profile, stats)
    }

    fun captureSettings(controls: PreprocessControlSnapshot, grid: PreprocessGeometrySnapshot): PreprocessSettings =
        settings.captureSettings(controls, grid, cells.edits.toMap(), cells.prioritySearch.toSet(), cells.softEdits.toSet())

    fun captureDraft(current: PreprocessSettings, page: PreprocessPage): PreprocessEditorDraft =
        PreprocessEditorDraft(current, cells.copyForEditing(), changes.copyForEditing(), page)

    fun hasUnsavedChanges(current: PreprocessSettings): Boolean = changes.hasUnsavedChanges(current)

    fun noteChange(key: String, label: String, detail: String, current: PreprocessSettings) =
        changes.note(key, label, detail, current)

    /** Validation belongs to the caller; committed edits must be independent of later draft edits. */
    fun commit(current: PreprocessSettings): PreprocessSave {
        val events = mutableListOf<PreprocessDraftEvent>()
        changes.commit(current, events::add)
        return PreprocessSave(current, cells.copyForEditing(), working, events)
    }
}
