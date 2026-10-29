package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.earthdiver.qrrepair.core.QrCellRegions
import io.github.earthdiver.qrrepair.core.RepairReasonKind
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.SamplingProfile
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.FormatEditConflict
import io.github.earthdiver.qrrepair.core.RecoveryEngine
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.ui.EditorTool
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the same draft alive while the result page is visible. */
internal class PreprocessEditorSession(
    val open: (PreprocessPage) -> Unit,
    val close: () -> Unit,
    val captureDraft: () -> PreprocessEditorDraft,
)

/** Session effects owned outside the dialog. Unsaved edits remain local to the dialog. */
internal interface PreprocessDialogHost {
    fun retains(image: BitmapUtils.Prepared): Boolean
    fun save(draft: PreprocessSave)
    fun showSavedStatus()
    fun startRecovery()
    fun canShowResult(): Boolean = false
    fun showResult() = Unit
    fun showHome() = Unit
    fun editorOpened(session: PreprocessEditorSession) = Unit
    fun editorClosed(session: PreprocessEditorSession) = Unit
    fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean)
}

/** Owns the pre-repair pages, their interactions, and temporary transformed images. */
internal class PreprocessDialogController(
    private val activity: Activity,
    private val preprocessSettings: PreprocessSettings,
    private val source: BitmapUtils.Prepared,
    private val initialWorking: BitmapUtils.Prepared,
    private val savedCellEditState: PreRecoveryCellEditState?,
    private val executor: java.util.concurrent.Executor,
    private val finderSearchGeneration: AtomicInteger,
    private val recoveryEngine: RecoveryEngine,
    private val host: PreprocessDialogHost,
    private val restoredDraft: PreprocessEditorDraft? = null,
) {
    fun show(initialPage: PreprocessPage = PreprocessPage.BASIC) = with(activity) {
        val preprocessSettings = restoredDraft?.settings ?: this@PreprocessDialogController.preprocessSettings
        val modernStyle = AppStylePreferences.isModern(this)
        var validateCellEdits: (Map<Coord, ModuleValue>) -> Boolean = { true }
        val editor = PreprocessEditorModel(preprocessSettings, source, initialWorking,
            savedCellEditState, restoredDraft, host::retains) { validateCellEdits(it) }
        val dialogState = editor.settings
        val searchSettings = dialogState.search
        val cellEditState = editor.cells
        var navigatePreprocessPage: (PreprocessPage) -> Unit = {}
        var savePreprocess: () -> Unit = {}
        var submitPreprocess: () -> Unit = {}
        var cancelPreprocess: () -> Unit = {}
        var noteDraftChange: (String, String, String) -> Unit = { _, _, _ -> }
        var updatePageLayout: (PreprocessPage) -> Unit = {}
        var manualFocus = false
        var workflowNavigation: ModernWorkflowNavigation? = null
        var showResultPage: () -> Unit = {}
        var preEditStatus: TextView? = null
        var preEditCountStatus: TextView? = null
        var largeCellEditor: PreprocessLargeCellEditor? = null
        var classificationLegend: TextView? = null
        var preEditSourceControl: CellSourceControl? = null
        var refreshSourceControls: () -> Unit = {}
        var refreshSelectedCellStatus: (QrMatrix?) -> Unit = {}
        var refreshPriorityControl: () -> Unit = {}
        fun updatePreEditCountStatus() {
            refreshPriorityControl()
            preEditCountStatus?.text = cellEditState.compactCountLabel
            largeCellEditor?.countStatus?.text = cellEditState.countLabel
            refreshSourceControls()
        }
        val overlay = PreprocessOverlayView(this).apply {
            setMirroredInput(preprocessSettings.mirroredInput)
            setBitmap(editor.working.bitmap)
            setVersion(preprocessSettings.version)
            preprocessSettings.manualQuad?.let {
                if (preprocessSettings.logicalGridAssigned) setImageQuad(it) else setPhysicalImageQuad(it)
            } ?: if (preprocessSettings.logicalGridAssigned) setImageQuad(imageQuad()) else Unit
            setFinderPoints(preprocessSettings.manualFinders)
            setConfirmedFinderPolarity(preprocessSettings.confirmedFinderPolarity)
            onClassificationSummaryChanged = { summary -> classificationLegend?.text = summary }
            val fixedHeight = (resources.displayMetrics.heightPixels * .42).toInt().coerceIn(dp(240), dp(420))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, fixedHeight)
        }
        var userGeometryEdited = false
        overlay.onGeometryEdited = { userGeometryEdited = true }
        val polarityModes = listOf(PolarityMode.AUTO, PolarityMode.NORMAL, PolarityMode.INVERTED)
        val polarity = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(ui("自動", "Auto"), ui("通常", "Normal"), ui("反転", "Inverted")).forEachIndexed { index, label ->
                addView(RadioButton(activity).apply {
                    id = View.generateViewId(); text = label; tag = polarityModes[index]
                }, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val selected = polarityModes.indexOf(preprocessSettings.polarity).coerceAtLeast(0)
            check(getChildAt(selected).id)
        }
        fun selectedPolarity(): PolarityMode =
            polarity.findViewById<RadioButton>(polarity.checkedRadioButtonId)?.tag as? PolarityMode ?: PolarityMode.AUTO
        var updatingThresholdControls = false
        val automaticThresholds = CheckBox(this).apply {
            text = ui("閾値を自動設定", "Set thresholds automatically")
            isChecked = preprocessSettings.automaticThresholds
        }
        val useUnknownColors = CheckBox(this).apply {
            text = ui("自動検出された色クラスタを不明領域（?）として扱う", "Treat detected color clusters as unknown (?)")
            isChecked = preprocessSettings.useUnknownColorClusters && preprocessSettings.unknownColorClusters.isNotEmpty()
            isEnabled = preprocessSettings.unknownColorClusters.isNotEmpty()
        }
        val searchQuadNeighborhood = CheckBox(this).apply {
            text = ui("サンプリング位置を自動微調整する", "Automatically fine-tune sampling position")
            isChecked = preprocessSettings.searchQuadNeighborhood
        }
        val trustKnownPayloadCount = CheckBox(this).apply {
            text = ui("単一セグメントの終端後構造を強制する", "Force single-segment terminator and padding")
            isChecked = preprocessSettings.trustKnownPayloadCount
        }
        val allowFixedCorrections = CheckBox(this).apply {
            setText(R.string.allow_fixed_corrections)
            isChecked = !preprocessSettings.lockManualBits
        }
        val blackLabel = TextView(this)
        val black = SeekBar(this).apply { max = 254; progress = preprocessSettings.blackMax }
        val whiteLabel = TextView(this)
        val white = SeekBar(this).apply { max = 254; progress = preprocessSettings.whiteMin - 1 }
        val version = SeekBar(this).apply { max = 39; progress = preprocessSettings.version - 1 }
        var cachedCellRegions = QrCellRegions(version.progress + 1)
        fun cellRegions(): QrCellRegions {
            if (cachedCellRegions.version != version.progress + 1) cachedCellRegions = QrCellRegions(version.progress + 1)
            return cachedCellRegions
        }
        fun cellRegionLabel(coord: Coord): String {
            val regions = cellRegions()
            if (!regions.contains(coord)) return getString(R.string.cell_region_outside)
            if (coord in regions.codewordCoordinates) return getString(R.string.cell_region_codeword)
            return getString(when (regions.structureAt(coord)) {
                RepairReasonKind.FINDER_PATTERN -> R.string.cell_region_finder
                RepairReasonKind.SEPARATOR -> R.string.cell_region_separator
                RepairReasonKind.TIMING_PATTERN -> R.string.cell_region_timing
                RepairReasonKind.ALIGNMENT_PATTERN -> R.string.cell_region_alignment
                RepairReasonKind.FORMAT_INFORMATION -> R.string.cell_region_format
                RepairReasonKind.VERSION_INFORMATION -> R.string.cell_region_version
                RepairReasonKind.FIXED_DARK_MODULE -> R.string.cell_region_dark
                null -> R.string.cell_region_remainder
                else -> R.string.cell_region_structure
            })
        }
        var versionButton: Button? = null
        var activePreviewProfile: SamplingProfile? = null
        var committedVersion = preprocessSettings.version
        var versionConfirmationShowing = false
        var checkVersionErrorLimit: () -> Unit = {}
        var refreshClassificationMatrix: () -> Unit = {}
        var refreshPreRecoveryDataPage: () -> Unit = {}
        fun invalidateSamplingPreview() {
            finderSearchGeneration.incrementAndGet()
            overlay.clearCornerSelection()
        }
        fun revalidatePrioritySearch() {
            val removed = cellEditState.revalidatePrioritySearch { it in cellRegions().codewordCoordinates }
            if (removed.isEmpty()) return
            updatePreEditCountStatus()
            val coordinates = removed.joinToString(" ") { "(${it.x},${it.y})" }
            val message = getString(R.string.priority_revalidated, coordinates)
            AlertDialog.Builder(activity).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            noteDraftChange("priority-validation", getString(R.string.priority_revalidation_title), coordinates)
        }
        fun controlSnapshot() = PreprocessControlSnapshot(
            polarity = selectedPolarity(),
            automaticThresholds = automaticThresholds.isChecked,
            blackProgress = black.progress,
            whiteProgress = white.progress,
            useUnknownColors = useUnknownColors.isChecked,
            version = version.progress + 1,
            lockCorners = true,
            lockVersion = true,
            searchQuadNeighborhood = searchQuadNeighborhood.isChecked,
            trustKnownPayloadCount = trustKnownPayloadCount.isChecked,
            lockManualBits = !allowFixedCorrections.isChecked,
            prioritySearchLimit = cellEditState.priorityLimit,
        )
        fun previewInverted(): Boolean = when (overlay.confirmedFinderPolarity()) {
            io.github.earthdiver.qrrepair.core.FinderPolarity.INVERTED -> true
            io.github.earthdiver.qrrepair.core.FinderPolarity.NORMAL -> false
            else -> selectedPolarity() == PolarityMode.INVERTED
        }
        fun updateThresholdLabels() {
            val (blackText, whiteText) = polarityThresholdLabels(previewInverted(), black.progress, white.progress + 1, isJapaneseUi())
            blackLabel.text = blackText
            whiteLabel.text = whiteText
        }
        fun refreshLabels() {
            updateThresholdLabels()
            val selectedVersion = version.progress + 1
            val selectedDimension = 17 + 4 * selectedVersion
            versionButton?.text = ui("QRバージョン: $selectedVersion / $selectedDimension×${selectedDimension}セル", "QR Version: $selectedVersion / $selectedDimension modules")
            val profile = SamplingProfile(
                dialogState.previewSettings(controlSnapshot()),
                inverted = previewInverted(),
            )
            activePreviewProfile = profile
            refreshClassificationMatrix()
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingThresholdControls) return
                updatingThresholdControls = true
                if (fromUser) automaticThresholds.isChecked = false
                if (black.progress >= white.progress + 1) {
                    if (seekBar === black) white.progress = black.progress
                    else black.progress = white.progress
                }
                updatingThresholdControls = false
                if (fromUser) invalidateSamplingPreview()
                refreshLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                overlay.clearCornerSelection()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                when (seekBar) {
                    black -> noteDraftChange("black", ui("QRの黒の閾値を変更", "Changed QR black threshold"), black.progress.toString())
                    white -> noteDraftChange("white", ui("QRの白の閾値を変更", "Changed QR white threshold"), (white.progress + 1).toString())
                }
            }
        }
        black.setOnSeekBarChangeListener(listener); white.setOnSeekBarChangeListener(listener)
        fun commitVersion(requested: Int) {
            val nextVersion = requested.coerceIn(1, 40)
            val perspectiveQuad = dialogState.perspectiveCorrectionQuad
            // Build before changing the draft so allocation/transform failure cannot leave
            // settings pointing to an image prepared for a different Version.
            val nextWorking = perspectiveQuad?.let {
                BitmapUtils.forSettings(source, preprocessSettings.copy(
                    rotationDegrees = dialogState.rotationDegrees,
                    perspectiveCorrectionQuad = it,
                    imageAdjustment = dialogState.imageAdjustment,
                    version = nextVersion,
                ))
            }
            if (nextWorking != null) {
                val before = BitmapUtils.rectifiedGrid(perspectiveQuad, committedVersion)
                val after = BitmapUtils.rectifiedGrid(perspectiveQuad, nextVersion)
                overlay.remapRectifiedImage(nextWorking.bitmap, before, after)
                editor.replaceWorking(nextWorking)
            }
            // Even an empty edit map can have Undo/Redo entries in the old coordinates.
            cellEditState.clearAll()
            overlay.clearCellEditing()
            updatePreEditCountStatus()
            invalidateSamplingPreview()
            committedVersion = nextVersion
            version.progress = committedVersion - 1
            overlay.setVersion(committedVersion)
            if (!overlay.isLogicalGridAssigned()) overlay.setPhysicalImageQuad(overlay.imageQuad())
            revalidatePrioritySearch()
            refreshLabels()
            noteDraftChange("version", ui("QRバージョンを変更", "Changed QR Version"), committedVersion.toString())
            checkVersionErrorLimit()
        }
        fun requestVersion(requested: Int) {
            overlay.clearCornerSelection()
            if (versionConfirmationShowing) return
            if (requested == committedVersion) {
                if (!overlay.isLogicalGridAssigned()) overlay.setPhysicalImageQuad(overlay.imageQuad())
                refreshLabels()
                return
            }
            if (cellEditState.edits.isNotEmpty()) {
                versionConfirmationShowing = true
                AlertDialog.Builder(activity)
                    .setTitle(ui("バージョンを変更しますか？", "Change Version?"))
                    .setMessage(ui("セル編集済みです。バージョンを変更するとセル座標が変わるため、現在のセル編集はすべて解除されます。", "Cells have been edited. Changing Version changes their coordinates and clears all current cell edits."))
                    .setNegativeButton(ui("変更しない", "Keep current")) { _, _ -> versionConfirmationShowing = false }
                    .setPositiveButton(ui("編集を解除して変更", "Clear edits and change")) { _, _ ->
                        versionConfirmationShowing = false
                        commitVersion(requested)
                    }
                    .setOnCancelListener { versionConfirmationShowing = false }
                    .show()
            } else commitVersion(requested)
        }
        versionButton = button(ui("QRバージョン: $committedVersion / ${17 + 4 * committedVersion}×${17 + 4 * committedVersion}セル", "QR Version: $committedVersion / ${17 + 4 * committedVersion} modules")) {
            overlay.clearCornerSelection()
            val labels = (1..40).map { value -> ui("V$value / ${17 + 4 * value}×${17 + 4 * value}セル", "Version $value / ${17 + 4 * value} modules") }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle(ui("QRバージョンを選択", "Select QR Version"))
                .setSingleChoiceItems(labels, committedVersion - 1) { selectionDialog, which ->
                    selectionDialog.dismiss()
                    requestVersion(which + 1)
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        polarity.setOnCheckedChangeListener { group, checkedId ->
            invalidateSamplingPreview()
            val label = group.findViewById<RadioButton>(checkedId)?.text?.toString().orEmpty()
            noteDraftChange("polarity", ui("白黒極性を変更", "Changed polarity"), label)
            refreshLabels()
        }
        automaticThresholds.setOnCheckedChangeListener { _, checked ->
            if (updatingThresholdControls) return@setOnCheckedChangeListener
            invalidateSamplingPreview(); refreshLabels()
            noteDraftChange("threshold-auto", ui("閾値を自動設定", "Set thresholds automatically"), if (checked) "ON" else "OFF")
        }
        useUnknownColors.setOnCheckedChangeListener { _, checked ->
            invalidateSamplingPreview(); refreshLabels()
            noteDraftChange("unknown-colors", ui("色付き不明領域を変更", "Changed colored unknown regions"), if (checked) "ON" else "OFF")
        }
        allowFixedCorrections.setOnCheckedChangeListener { _, checked ->
            refreshClassificationMatrix()
            noteDraftChange("fixed-corrections", getString(R.string.allow_fixed_corrections), if (checked) "ON" else "OFF")
        }
        searchQuadNeighborhood.setOnCheckedChangeListener { _, checked ->
            if (checked && cellEditState.edits.isNotEmpty()) {
                Toast.makeText(this, ui("近傍探索では編集セルが参照する画像位置が移動する場合があります。", "Nearby geometry search may move the image positions referenced by edited cells."), Toast.LENGTH_LONG).show()
            }
            noteDraftChange("quad-neighborhood", ui("四隅近傍探索を変更", "Changed nearby geometry search"), if (checked) "ON" else "OFF")
        }
        refreshLabels()
        overlay.onLogicalGridAssigned = { quarterTurns ->
            if (quarterTurns != 0) {
                cellEditState.rotateCoordinates(17 + 4 * (version.progress + 1), quarterTurns)
            }
            revalidatePrioritySearch()
            refreshClassificationMatrix()
        }
        overlay.onFinderPlacementRejected = {
            Toast.makeText(
                this,
                ui(
                    "指定済みFinderから推定される位置と離れすぎています。格子またはFinder役割を確認してください。",
                    "This point is too far from the position implied by the confirmed Finder. Check the grid or Finder roles.",
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        overlay.onFinderHintPlaced = finderHint@{ role, hint ->
            val profile = activePreviewProfile ?: return@finderHint
            val finderToken = finderSearchGeneration.incrementAndGet()
            val finderWorking = editor.working
            val finderProfile = profile.copy(settings = profile.settings.copy(
                manualQuad = overlay.imageQuad().takeIf { overlay.isLogicalGridAssigned() },
                confirmedFinderPolarity = overlay.confirmedFinderPolarity(),
            ))
            Toast.makeText(this, ui("指定位置の周辺からFinderを精密探索中…", "Refining the Finder near the selected position…"), Toast.LENGTH_SHORT).show()
            executor.execute {
                val refined = io.github.earthdiver.qrrepair.core.refineFinder(finderWorking.luma, finderProfile.settings, hint)
                runOnUiThread {
                    if (finderToken != finderSearchGeneration.get() || editor.working !== finderWorking) return@runOnUiThread
                    if (refined == null) {
                        Toast.makeText(this, ui("周辺でFinderを確定できませんでした。指定点をヒントとして解析します。", "The Finder could not be confirmed nearby. The selected point will be used as a hint."), Toast.LENGTH_LONG).show()
                    } else {
                        overlay.setVerifiedFinder(role, refined)
                        refreshClassificationMatrix()
                        Toast.makeText(this, ui("Finder位置を自動補正しました（モジュール幅 ${"%.1f".format(refined.moduleSize)}px）", "Finder position refined (module width ${"%.1f".format(refined.moduleSize)} px)"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        overlay.onFinderRemoved = { role ->
            // A refinement launched when this Finder was placed may still be running. Do not
            // allow its delayed result to confirm the Finder again after the user removed it.
            finderSearchGeneration.incrementAndGet()
            val label = when (role) {
                FinderRole.TOP_LEFT -> getString(R.string.top_left)
                FinderRole.TOP_RIGHT -> getString(R.string.top_right)
                FinderRole.BOTTOM_LEFT -> getString(R.string.bottom_left)
            }
            userGeometryEdited = true
            refreshClassificationMatrix()
            noteDraftChange("finder-$role", ui("$label Finderを解除", "Removed $label Finder"), role.name)
            Toast.makeText(this, ui("$label のFinder確定を解除しました。", "The $label Finder was unconfirmed."), Toast.LENGTH_SHORT).show()
        }
        fun imageSettings() = preprocessSettings.copy(
            rotationDegrees = dialogState.rotationDegrees,
            perspectiveCorrectionQuad = dialogState.perspectiveCorrectionQuad,
            version = version.progress + 1,
            imageAdjustment = dialogState.imageAdjustment,
        )
        fun replaceRotatedImage(before: PreprocessSettings) {
            val quad = BitmapUtils.gridAfterRotation(source.bitmap, overlay.imageQuad(), before, dialogState.rotationDegrees)
            editor.replaceWorking(BitmapUtils.forSettings(source, imageSettings()))
            overlay.remapRotatedImage(editor.working.bitmap, quad)
        }
        fun rotateBy(delta: Float) {
            finderSearchGeneration.incrementAndGet()
            val before = imageSettings()
            dialogState.rotateBy(delta)
            replaceRotatedImage(before)
            userGeometryEdited = true
            noteDraftChange("rotation", ui("画像を回転", "Rotated image"), "${"%.1f".format(dialogState.rotationDegrees)}°")
            refreshLabels()
            Toast.makeText(activity, ui("回転 ${"%.1f".format(dialogState.rotationDegrees)}°。四隅とFinder役割を確認してください。", "Rotation ${"%.1f".format(dialogState.rotationDegrees)}°. Check the corners and Finder roles."), Toast.LENGTH_SHORT).show()
        }
        fun resetRotation() {
            finderSearchGeneration.incrementAndGet()
            val before = imageSettings()
            dialogState.resetRotation()
            replaceRotatedImage(before)
            userGeometryEdited = true
            refreshLabels()
            noteDraftChange("rotation", ui("画像回転をリセット", "Reset image rotation"), "0°")
        }
        val searchControls = PreprocessSearchControls(
            activity, searchSettings, cellEditState, { version.progress + 1 },
            refreshLabels = { refreshLabels() },
            updatePreEditCountStatus = ::updatePreEditCountStatus,
            noteDraftChange = { key, label, detail -> noteDraftChange(key, label, detail) },
        )
        checkVersionErrorLimit = searchControls::refreshForVersionChange
        refreshPriorityControl = searchControls::refreshPriorityLimit
        val mirroredInput = CheckBox(this).apply {
            setText(R.string.mirrored_input)
            isChecked = dialogState.mirroredInput
            setOnCheckedChangeListener { _, checked ->
                finderSearchGeneration.incrementAndGet()
                dialogState.mirroredInput = checked
                overlay.setMirroredInput(checked)
                cellEditState.transposeCoordinates()
                userGeometryEdited = true
                revalidatePrioritySearch()
                refreshLabels()
                cellEditState.selectedCells.firstOrNull()?.let(overlay::selectEditCell)
                overlay.setCellSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
                refreshSelectedCellStatus(null)
                noteDraftChange("mirrored-input", getString(R.string.mirrored_input), if (checked) "ON" else "OFF")
            }
        }
        val detailsPage = PreRecoveryDetailedSettingsPage(
            this,
            buttonFactory = ::button,
            scrollRow = { views -> scrollRow(*views) },
            polarity = polarity,
            mirroredInput = mirroredInput,
            searchQuadNeighborhood = searchQuadNeighborhood,
            trustKnownPayloadCount = trustKnownPayloadCount,
            allowFixedCorrections = allowFixedCorrections,
            formatMismatchLimitControl = searchControls.formatMismatchLimitControl,
            dataEccMismatchLimitControl = searchControls.dataEccMismatchLimitControl,
            prioritySearchControl = searchControls.priorityLimitButton,
            partialBitSearchControl = searchControls.partialBitSearchControl,
            listDecodingControl = searchControls.listDecodingControl,
            actions = DetailedSettingsPageActions(
                rotateBy = ::rotateBy,
                resetRotation = ::resetRotation,
                saveDiagnosticInput = {
                    val diagnostic = BitmapUtils.forSettings(source, imageSettings(), quietModules = 4.0).bitmap
                    host.saveDiagnosticInput(diagnostic, owned = diagnostic !== source.bitmap)
                },
            ),
        ).view
        fun geometrySnapshot() = PreprocessGeometrySnapshot(
            logicalGridAssigned = overlay.isLogicalGridAssigned(),
            quad = overlay.imageQuad(),
            finders = overlay.finderPoints(),
            confirmedFinderPolarity = overlay.confirmedFinderPolarity(),
        )
        fun sampleImageMatrix(): Pair<GridGeometry, QrMatrix>? {
            val preview = editor.sample(controlSnapshot(), geometrySnapshot()) ?: return null
            preview.stats.thresholds?.let { pair ->
                updatingThresholdControls = true
                black.progress = pair.blackMax; white.progress = pair.whiteMin - 1
                updatingThresholdControls = false
                updateThresholdLabels()
            }
            activePreviewProfile = preview.profile.copy(settings = preview.profile.settings.copy(
                blackMax = black.progress, whiteMin = white.progress + 1,
            ))
            return preview.geometry to preview.matrix
        }
        fun refreshPreRecoveryMatrix(): Pair<GridGeometry, QrMatrix>? {
            val (g, matrix) = sampleImageMatrix() ?: return null
            cellEditState.applyTo(matrix, allowFixedCorrections.isChecked)
            // Cell edits (including *, undo and redo) must update eligibility immediately.
            val headerComplete = recoveryEngine.hasTrustablePayloadHeader(
                matrix, searchSettings.formatConstraint, searchSettings.effectiveFormatMismatchLimit(), manualEdits = cellEditState.fixedEdits)
            trustKnownPayloadCount.isEnabled = headerComplete || trustKnownPayloadCount.isChecked
            return g to matrix
        }
        fun setManualFocus(enabled: Boolean) {
            manualFocus = enabled
            overlay.highlightManualOnly = enabled
            largeCellEditor?.editorView?.highlightManualOnly = enabled
            preEditSourceControl?.setManualFocus(enabled)
            largeCellEditor?.sourceControl?.setManualFocus(enabled)
        }
        refreshSourceControls = {
            val baseline = if (cellEditState.selectedCells.isNotEmpty()) sampleImageMatrix()?.second else null
            preEditSourceControl?.update(cellEditState, baseline, allowFixedCorrections.isChecked)
            largeCellEditor?.sourceControl?.update(cellEditState, baseline, allowFixedCorrections.isChecked)
        }
        refreshClassificationMatrix = {
            refreshSourceControls()
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                overlay.setCellEditPreview(geometry, matrix)
                overlay.setCellSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
                largeCellEditor?.editorView?.setDocument(editor.working.bitmap, geometry, matrix, keepViewport = true)
                largeCellEditor?.editorView?.setSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
            }
        }
        val refreshDraggedGrid = Runnable { if (overlay.isAttachedToWindow) refreshClassificationMatrix() }
        overlay.onGeometryEdited = {
            userGeometryEdited = true
            overlay.removeCallbacks(refreshDraggedGrid)
            overlay.postDelayed(refreshDraggedGrid, 120L)
        }
        fun selectPreEditCell(x: Int, y: Int) {
            overlay.selectEditCell(Coord(x, y))
            cellEditState.select(setOf(Coord(x, y)))
            val refreshed = refreshPreRecoveryMatrix()
            refreshed?.let { (geometry, matrix) ->
                overlay.setCellEditing(geometry, matrix, Coord(x, y))
                largeCellEditor?.editorView?.setDocument(editor.working.bitmap, geometry, matrix, keepViewport = true)
                largeCellEditor?.editorView?.setSelected(x, y)
            }
            refreshSelectedCellStatus(refreshed?.second)
            refreshSourceControls()
        }
        refreshSelectedCellStatus = { matrix ->
            val coord = overlay.selectedEditCell()
            if (coord != null) {
                val (x, y) = coord
                val value = (matrix ?: refreshPreRecoveryMatrix()?.second)?.get(x, y)?.value
                val kind = cellRegionLabel(Coord(x, y))
                val label = if (coord in cellEditState.prioritySearch) "*" else when (value) { ModuleValue.ZERO -> "0"; ModuleValue.ONE -> "1"; else -> "?" }
                preEditStatus?.text = ui("選択: ($x,$y) / $kind / 値=$label", "Selected: ($x,$y) / $kind / value=$label")
                largeCellEditor?.status?.text = preEditStatus?.text
                largeCellEditor?.editorView?.setSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
            }
        }
        overlay.onEditCellSelected = { x, y -> selectPreEditCell(x, y) }
        fun movePreEditCell(dx: Int, dy: Int) {
            val current = overlay.selectedEditCell() ?: return
            val dimension = 17 + 4 * (version.progress + 1)
            selectPreEditCell((current.x + dx).coerceIn(0, dimension - 1), (current.y + dy).coerceIn(0, dimension - 1))
        }
        validateCellEdits = { edits ->
            val conflict = searchSettings.formatConflict(version.progress + 1, edits)
            if (conflict != null) showFormatEditError(conflict)
            conflict == null
        }
        val cellCommands = PreprocessCellEditController(this, cellEditState,
            object : PreprocessCellEditController.Host {
                override fun observedMatrix(): QrMatrix? = sampleImageMatrix()?.second
                override fun hasLogicalGrid() = overlay.isLogicalGridAssigned()
                override fun regions(): QrCellRegions = cellRegions()
                override fun regionLabel(coord: Coord): String = cellRegionLabel(coord)
                override fun refreshSelection() {
                    val selection = cellEditState.selection
                    overlay.setCellSelection(selection.cells, selection.isRectangle, selection.focus)
                    largeCellEditor?.editorView?.setSelection(selection.cells, selection.isRectangle, selection.focus)
                    refreshSourceControls()
                }
                override fun refreshEditedCells(changed: Set<Coord>) {
                    refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                        overlay.setCellEditing(geometry, matrix, cellEditState.selection.focus)
                        largeCellEditor?.editorView?.setDocument(editor.working.bitmap, geometry, matrix, keepViewport = true)
                    }
                    refreshSelection()
                }
                override fun showStatus(text: String) {
                    preEditStatus?.text = text
                    largeCellEditor?.status?.text = preEditStatus?.text
                    updatePreEditCountStatus()
                }
                override fun recordChange(key: String, label: String, detail: String) =
                    noteDraftChange(key, label, detail)
            })
        fun isStamp(tool: EditorTool) = tool in setOf(EditorTool.STAMP_ERASURE_SMALL,
            EditorTool.STAMP_ERASURE_MEDIUM, EditorTool.STAMP_ERASURE_LARGE)
        overlay.onCellBrushApplied = { coords, value ->
            cellCommands.setValues(coords, value, asRectangle = isStamp(overlay.cellEditorTool))
        }
        overlay.onCellRangeSelected = { coords ->
            cellEditState.select(coords, asRectangle = true)
            overlay.setCellSelection(coords, asRectangle = true)
            largeCellEditor?.editorView?.setSelection(coords, asRectangle = true)
            preEditStatus?.text = ui("矩形選択 ${coords.size}セル。0/1/?で一括変更できます。", "Selected ${coords.size} cells. Use 0/1/? to change them together.")
            refreshSourceControls()
        }
        fun showSingleCellEditor() {
            cellEditState.clearSelection()
            refreshSourceControls()
            overlay.visibility = View.VISIBLE
            overlay.cellEditorTool = EditorTool.NAVIGATE
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                overlay.setCellEditing(geometry, matrix, null)
                overlay.setCellSelection(emptySet())
                preEditStatus?.text = getString(R.string.tap_cell)
            }
        }
        fun showBulkEditor(tool: EditorTool) {
            overlay.visibility = View.VISIBLE
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                overlay.setCellEditing(geometry, matrix, null)
            }
            overlay.cellEditorTool = tool
            preEditStatus?.text = ui("ブラシでなぞるか、矩形選択後に0/1/?を押して一括変更します。", "Paint with a brush, or select a rectangle and use 0/1/? to edit it.")
        }
        fun showDedicatedCellEditor() {
            val initial = refreshPreRecoveryMatrix() ?: return
            cellEditState.clearSelection()
            refreshSourceControls()
            overlay.setCellEditing(initial.first, initial.second, null)
            overlay.setCellSelection(emptySet())
            val expanded = PreprocessLargeCellEditor(
                activity, editor.working.bitmap, initial.first, initial.second,
                version = { version.progress + 1 },
                cellEditState = cellEditState,
                cellCommands = cellCommands,
                cellRegionLabel = ::cellRegionLabel,
                currentMatrix = { refreshPreRecoveryMatrix()?.second },
                onSelectionChanged = {
                    overlay.setCellSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
                    refreshSourceControls()
                },
                onDismiss = {
                    largeCellEditor = null
                    refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                        overlay.setCellEditing(geometry, matrix, overlay.selectedEditCell())
                        overlay.setCellSelection(cellEditState.selectedCells, cellEditState.selection.isRectangle, cellEditState.selection.focus)
                    }
                    updatePreEditCountStatus()
                },
            )
            largeCellEditor = expanded
            expanded.sourceControl.onManualFocusChanged = ::setManualFocus
            setManualFocus(manualFocus)
            updatePreEditCountStatus()
            expanded.show()
        }
        val cellEditPage = PreRecoveryCellEditPage(
            this,
            buttonFactory = ::button,
            compactRow = { views -> compactRow(*views) },
            actions = CellEditPageActions(
                move = ::movePreEditCell,
                setValue = { value -> cellCommands.setValues(cellEditState.selectedCells, value) },
                setPriority = { cellCommands.setPriority(cellEditState.selectedCells) },
                changeSource = cellCommands::changeSource,
                undo = cellCommands::undo,
                redo = cellCommands::redo,
                selectMode = { tool ->
                    if (tool == EditorTool.NAVIGATE) showSingleCellEditor() else showBulkEditor(tool)
                },
                openLargeEditor = ::showDedicatedCellEditor,
            ),
        )
        preEditSourceControl = cellEditPage.sourceControl
        cellEditPage.sourceControl.onManualFocusChanged = ::setManualFocus
        preEditStatus = cellEditPage.status
        preEditCountStatus = cellEditPage.countStatus
        updatePreEditCountStatus()
        val cellPage = cellEditPage.view
        fun createDataPage() = PreRecoveryDataPage(
            this,
            inputProvider = {
                refreshPreRecoveryMatrix()?.second?.let { matrix ->
                    PreRecoveryDataInput(matrix, version.progress + 1)
                }
            },
            formatConstraintProvider = { searchSettings.formatConstraint },
        )
        val preRecoveryDataPage = createDataPage()
        refreshPreRecoveryDataPage = preRecoveryDataPage::refresh
        val hexPage = preRecoveryDataPage.view
        var eccConstraintButton: Button? = null
        var maskConstraintButton: Button? = null
        val formatSearchCount = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun refreshFormatConstraintControls() {
            eccConstraintButton?.text = searchSettings.formatConstraint?.ecLevel?.let { "ECC: $it" }
                ?: ui("ECC: 自動", "ECC: Auto")
            maskConstraintButton?.text = searchSettings.formatConstraint?.dataMask?.let { "mask: $it" }
                ?: ui("mask: 自動", "Mask: Auto")
            val count = searchSettings.formatConstraint?.candidateCount ?: 32
            formatSearchCount.text = ui("探索対象: ${count}形式", "Formats to search: $count")
            searchControls.refreshFormatMismatchLimitControl()
        }
        fun updateFormatConstraint(ecLevel: ErrorCorrectionLevel?, dataMask: Int?) {
            searchSettings.updateFormatConstraint(version.progress + 1, ecLevel, dataMask, cellEditState.fixedEdits)?.let {
                showFormatEditError(it)
                return
            }
            refreshFormatConstraintControls()
            refreshClassificationMatrix()
            refreshPreRecoveryDataPage()
            noteDraftChange(
                "format",
                ui("形式情報の探索条件を変更", "Changed format search constraint"),
                "ECC=${ecLevel ?: ui("自動", "Auto")}, mask=${dataMask ?: ui("自動", "Auto")}",
            )
        }
        eccConstraintButton = button(ui("ECC: 自動", "ECC: Auto")) {
            val choices = listOf<ErrorCorrectionLevel?>(
                null,
                ErrorCorrectionLevel.L,
                ErrorCorrectionLevel.M,
                ErrorCorrectionLevel.Q,
                ErrorCorrectionLevel.H,
            )
            val labels = choices.map { it?.let { level -> "ECC: $level" } ?: ui("ECC: 自動", "ECC: Auto") }.toTypedArray()
            val selected = choices.indexOf(searchSettings.formatConstraint?.ecLevel).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(ui("誤り訂正レベルを指定", "Select ECC level"))
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    updateFormatConstraint(choices[which], searchSettings.formatConstraint?.dataMask)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        maskConstraintButton = button(ui("mask: 自動", "Mask: Auto")) {
            val choices = listOf<Int?>(null) + (0..7)
            val labels = choices.map { it?.let { mask -> "mask: $mask" } ?: ui("mask: 自動", "Mask: Auto") }.toTypedArray()
            val selected = choices.indexOf(searchSettings.formatConstraint?.dataMask).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(ui("マスクを指定", "Select mask"))
                .setSingleChoiceItems(labels, selected) { dialog, which ->
                    updateFormatConstraint(searchSettings.formatConstraint?.ecLevel, choices[which])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        val formatConstraintFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(activity).apply {
                text = ui("形式情報（修復探索）", "Format information (recovery search)")
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(eccConstraintButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(maskConstraintButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(formatSearchCount)
        }
        refreshFormatConstraintControls()
        val basicPage = PreRecoveryBasicSettingsPage(
            this,
            buttonFactory = ::button,
            compactRow = { views -> compactRow(*views) },
            versionButton = requireNotNull(versionButton),
            automaticThresholds = automaticThresholds,
            blackLabel = blackLabel,
            blackSeekBar = black,
            whiteLabel = whiteLabel,
            whiteSeekBar = white,
            useUnknownColors = useUnknownColors,
            scrollContentFooter = formatConstraintFooter,
            actions = BasicSettingsPageActions(
                openDetails = { navigatePreprocessPage(PreprocessPage.DETAILS) },
                openCellEditor = { navigatePreprocessPage(PreprocessPage.CELL_EDITOR) },
                nudgeCorner = { dx, dy, step ->
                    overlay.nudgeSelectedCorner(dx * step, dy * step)
                },
                applyPerspectiveCorrection = applyPerspective@{
                    finderSearchGeneration.incrementAndGet()
                    if (dialogState.perspectiveCorrectionQuad != null) {
                        Toast.makeText(this, ui("台形補正は適用済みです。再適用する場合は画像を読み込み直してください。", "Perspective correction is already applied. Re-import the image to apply it again."), Toast.LENGTH_LONG).show()
                        return@applyPerspective
                    }
                    // Rectification preserves image handedness; mirror interpretation stays in the grid.
                    val sourceQuad = overlay.imageQuad().let { if (dialogState.mirroredInput) it.transposed() else it }
                    val rectified = runCatching {
                        val unadjusted = BitmapUtils.forSettings(source, imageSettings().copy(imageAdjustment = ImageAdjustment()))
                        try {
                            BitmapUtils.rectify(unadjusted.bitmap, sourceQuad, version.progress + 1, quietModules = 1.0)
                        } finally {
                            if (unadjusted.bitmap !== source.bitmap) unadjusted.bitmap.recycle()
                        }
                    }.getOrElse {
                        Toast.makeText(this, ui("台形補正を適用できませんでした。四隅を確認してください。", "Could not rectify the image. Check the four corners."), Toast.LENGTH_LONG).show()
                        return@applyPerspective
                    }
                    val adjusted = try {
                        BitmapUtils.adjust(rectified.prepared, dialogState.imageAdjustment)
                    } catch (error: Throwable) {
                        rectified.prepared.bitmap.recycle()
                        throw error
                    }
                    if (adjusted !== rectified.prepared) rectified.prepared.bitmap.recycle()
                    dialogState.applyPerspectiveCorrection(sourceQuad)
                    editor.replaceWorking(adjusted)
                    overlay.setBitmap(editor.working.bitmap)
                    overlay.setPhysicalImageQuad(if (dialogState.mirroredInput) rectified.qrQuad.transposed() else rectified.qrQuad)
                    overlay.clearFinders()
                    userGeometryEdited = true
                    refreshLabels()
                    noteDraftChange("perspective", ui("台形補正を適用", "Applied perspective correction"), "${editor.working.bitmap.width}x${editor.working.bitmap.height}")
                    Toast.makeText(this, R.string.perspective_applied, Toast.LENGTH_LONG).show()
                },
                beginFinderPlacement = { role ->
                    overlay.clearCornerSelection()
                    overlay.finderPlacementRole = role
                },
                beginFinderRemoval = overlay::beginFinderRemoval,
            ),
        ).view
        val pages = mapOf(
            PreprocessPage.BASIC to basicPage,
            PreprocessPage.DETAILS to detailsPage,
            PreprocessPage.CELL_EDITOR to cellPage,
            PreprocessPage.DATA to hexPage,
        )
        val pageHost = PreprocessPageHost(
            this,
            pages,
            buttonFactory = ::button,
            basicActions = buildList {
                if (modernStyle) {
                    add(PreprocessPageHost.BasicAction(getString(R.string.home_navigation), home = true) { cancelPreprocess() })
                    add(PreprocessPageHost.BasicAction(ui("保存", "Save")) { savePreprocess() })
                    add(PreprocessPageHost.BasicAction(getString(R.string.repair_short)) { submitPreprocess() })
                } else {
                    add(PreprocessPageHost.BasicAction(getString(R.string.cancel)) { cancelPreprocess() })
                    add(PreprocessPageHost.BasicAction(ui("保存", "Save")) { savePreprocess() })
                    add(PreprocessPageHost.BasicAction(ui("設定を保存して修復", "Save & repair"), weight = 2f) { submitPreprocess() })
                }
            },
            onDataRequested = {
                val data = createDataPage()
                data.refresh()
                showDataDialog(activity, data.view)
            },
            onPageShown = { page ->
                overlay.clearCornerSelection()
                overlay.highlightManualOnly = manualFocus && page == PreprocessPage.CELL_EDITOR
                if (page == PreprocessPage.CELL_EDITOR) {
                    showSingleCellEditor()
                } else {
                    refreshPreRecoveryMatrix()?.let { (geometry, matrix) -> overlay.setCellEditPreview(geometry, matrix) }
                    overlay.visibility = View.VISIBLE
                }
                workflowNavigation?.update(if (page == PreprocessPage.CELL_EDITOR) 1 else 0, canShowResult = host.canShowResult())
                updatePageLayout(page)
                if (page == PreprocessPage.DATA) preRecoveryDataPage.refresh()
            },
        )
        navigatePreprocessPage = pageHost::navigateTo
        if (modernStyle) {
            workflowNavigation = ModernWorkflowNavigation(this,
                { pageHost.navigateTo(PreprocessPage.BASIC) },
                { pageHost.navigateTo(PreprocessPage.CELL_EDITOR) },
                {
                    if (host.canShowResult()) {
                        Toast.makeText(activity, R.string.previous_result_notice, Toast.LENGTH_LONG).show()
                        showResultPage()
                    }
                })
            workflowNavigation.view.contentDescription = getString(R.string.workflow_help)
        }
        val scaffold = PreprocessDialogScaffold(this, overlay, pageHost.view, workflowNavigation?.view)
        updatePageLayout = { page -> scaffold.showPage(page) }
        classificationLegend = scaffold.classificationLegend
        val root = scaffold.view
        val dialog = object : AlertDialog(this) {
            // Both system Back (including gesture navigation) and outside touches call cancel.
            // Explicit dismissals are reserved for confirmed exits and lifecycle cleanup.
            override fun cancel() = cancelPreprocess()
        }.apply {
            if (!modernStyle) setView(root)
        }
        lateinit var captureEditorDraft: () -> PreprocessEditorDraft
        val session = PreprocessEditorSession(
            open = { page -> dialog.show(); pageHost.navigateTo(page) },
            close = { dialog.dismiss() },
            captureDraft = { captureEditorDraft() },
        )
        showResultPage = {
            dialog.hide()
            host.showResult()
        }
        dialog.setOnDismissListener {
            host.editorClosed(session)
            finderSearchGeneration.incrementAndGet()
            editor.release()
        }
        cancelPreprocess = { dialog.dismiss() }
        dialog.setOnShowListener {
            // AlertDialog's wrap-content custom panel otherwise shrinks on shorter pages,
            // pulling the footer upward instead of giving the page the available space.
            if (modernStyle) dialog.window?.apply {
                root.setPadding(dp(16), dp(8), dp(16), dp(8))
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(PrecisionStyle.BACKGROUND))
                setContentView(root)
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            val availableHeight = (resources.displayMetrics.heightPixels * .98f).toInt()
            dialog.window?.setLayout(
                if (modernStyle) android.view.WindowManager.LayoutParams.MATCH_PARENT
                    else (resources.displayMetrics.widthPixels * .98f).toInt(),
                if (modernStyle) android.view.WindowManager.LayoutParams.MATCH_PARENT else availableHeight,
            )
            fun capturePreprocessSettings(): PreprocessSettings =
                editor.captureSettings(controlSnapshot(), geometrySnapshot())
            captureEditorDraft = {
                editor.captureDraft(capturePreprocessSettings(), pageHost.currentPage())
            }
            host.editorOpened(session)
            fun leaveEditor() {
                dialog.dismiss()
                if (modernStyle) host.showHome()
            }
            cancelPreprocess = cancel@{
                overlay.clearCornerSelection()
                val hasUnsavedChanges = editor.hasUnsavedChanges(capturePreprocessSettings())
                if (!hasUnsavedChanges) {
                    leaveEditor()
                    return@cancel
                }
                AlertDialog.Builder(activity)
                    .setTitle(ui("未保存の変更があります", "Unsaved changes"))
                    .setMessage(ui("変更を保存せずに修復前設定を閉じますか？", "Close the pre-repair settings without saving changes?"))
                    .setPositiveButton(ui("保存せず閉じる", "Close without saving")) { _, _ -> leaveEditor() }
                    .setNegativeButton(R.string.back, null)
                    .show()
            }
            noteDraftChange = { key, label, detail ->
                editor.noteChange(key, label, detail, capturePreprocessSettings())
            }
            overlay.onGeometryEditFinished = {
                overlay.removeCallbacks(refreshDraggedGrid)
                refreshClassificationMatrix()
                noteDraftChange("quad", ui("四隅を調整", "Adjusted corners"), ui("ドラッグまたは矢印で調整", "Adjusted by drag or arrow keys"))
            }
            fun persistPreprocessDraft(): Boolean {
                if (!searchControls.requestValidDataEccLimit()) return false
                val captured = capturePreprocessSettings()
                searchSettings.formatConflict(captured.version, captured.fixedPreRecoveryEdits)?.let { showFormatEditError(it); return false }
                host.save(editor.commit(captured))
                workflowNavigation?.update(if (pageHost.currentPage() == PreprocessPage.CELL_EDITOR) 1 else 0,
                    canShowResult = host.canShowResult())
                return true
            }
            savePreprocess = save@{
                if (!persistPreprocessDraft()) return@save
                host.showSavedStatus()
                Toast.makeText(activity, ui("修復設定を保存しました。", "Repair settings saved."), Toast.LENGTH_SHORT).show()
            }
            submitPreprocess = submit@{
                if (!persistPreprocessDraft()) return@submit
                dialog.dismiss(); host.startRecovery()
            }
        }
        dialog.show()
        pageHost.navigateTo(initialPage)
    }

    private val dialogViews by lazy { DialogViewFactory(activity) }

    private fun button(label: String, onClick: () -> Unit): Button = dialogViews.button(label, onClick)

    private fun compactRow(vararg children: View): LinearLayout = dialogViews.compactRow(*children)

    private fun scrollRow(vararg children: View): HorizontalScrollView = dialogViews.scrollRow(*children)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun isJapaneseUi(): Boolean =
        AppLanguageManager.isJapanese

    private fun ui(japanese: String, english: String): String =
        localizedText(japanese, english)

    private fun showFormatEditError(conflict: FormatEditConflict) {
        AlertDialog.Builder(activity)
            .setTitle(ui("形式情報の指定エラー", "Format selection error"))
            .setMessage(conflict.message(true))
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
