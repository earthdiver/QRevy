package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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
import io.github.earthdiver.qrrepair.core.ErrorLimitMode
import io.github.earthdiver.qrrepair.core.ErrorLimitPolicy
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.SamplingProfile
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.FormatEditConflict
import io.github.earthdiver.qrrepair.core.QrFunctionMask
import io.github.earthdiver.qrrepair.core.RecoveryEngine
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.ui.EditorTool
import io.github.earthdiver.qrrepair.ui.CodewordOverlayKind
import io.github.earthdiver.qrrepair.ui.QrEditorView
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.atomic.AtomicInteger

/** A saved dialog draft; the Activity owns persistence and the retained image after handoff. */
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
        val dialogState = PreprocessDialogState(preprocessSettings)
        val searchSettings = dialogState.search
        var working = initialWorking
        fun releaseWorkingIfTemporary(previous: BitmapUtils.Prepared, replacement: BitmapUtils.Prepared) {
            if (previous !== source && !host.retains(previous) && previous.bitmap !== replacement.bitmap &&
                !previous.bitmap.isRecycled
            ) {
                previous.bitmap.recycle()
            }
        }
        var navigatePreprocessPage: (PreprocessPage) -> Unit = {}
        var savePreprocess: () -> Unit = {}
        var submitPreprocess: () -> Unit = {}
        var cancelPreprocess: () -> Unit = {}
        val draftState = restoredDraft?.changes?.copyForEditing() ?: PreprocessDraftState(preprocessSettings)
        var noteDraftChange: (String, String, String) -> Unit = { _, _, _ -> }
        var validateCellEdits: (Map<Coord, ModuleValue>) -> Boolean = { true }
        val cellEditState = (restoredDraft?.cellEdits ?: savedCellEditState)?.copyForEditing { validateCellEdits(it) } ?: PreRecoveryCellEditState(
            preprocessSettings.preRecoveryEdits,
            preprocessSettings.preRecoveryPrioritySearch,
            priorityLimit = preprocessSettings.prioritySearchLimit,
            validateEdits = { validateCellEdits(it) },
            initialSoftEdits = preprocessSettings.effectiveSoftEdits,
        )
        cellEditState.priorityLimit = preprocessSettings.prioritySearchLimit
        var updatePageLayout: (PreprocessPage) -> Unit = {}
        var manualFocus = false
        var workflowNavigation: ModernWorkflowNavigation? = null
        var showResultPage: () -> Unit = {}
        var preEditStatus: TextView? = null
        var preEditCountStatus: TextView? = null
        var dedicatedEditor: QrEditorView? = null
        var dedicatedEditorDialog: AlertDialog? = null
        var dedicatedEditStatus: TextView? = null
        var dedicatedEditCountStatus: TextView? = null
        var classificationLegend: TextView? = null
        var preEditSourceControl: CellSourceControl? = null
        var dedicatedSourceControl: CellSourceControl? = null
        var refreshSourceControls: () -> Unit = {}
        var refreshSelectedCellStatus: (QrMatrix?) -> Unit = {}
        var refreshPriorityControl: () -> Unit = {}
        fun updatePreEditCountStatus() {
            refreshPriorityControl()
            preEditCountStatus?.text = cellEditState.compactCountLabel
            dedicatedEditCountStatus?.text = cellEditState.countLabel
            refreshSourceControls()
        }
        val overlay = PreprocessOverlayView(this).apply {
            setMirroredInput(preprocessSettings.mirroredInput)
            setBitmap(working.bitmap)
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
            text = ui("種類・文字数／バイト数を読み取り、または * の試行で仮定できる場合に、単一セグメントを仮定して後続構造を強制する。", "When the mode and character/byte count are read or assigned by * trials, assume a single segment and force the following structure.")
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
            // Even an empty edit map can have Undo/Redo entries in the old coordinates.
            cellEditState.clearAll()
            overlay.clearCellEditing()
            updatePreEditCountStatus()
            invalidateSamplingPreview()
            committedVersion = requested.coerceIn(1, 40)
            version.progress = committedVersion - 1
            overlay.setVersion(committedVersion)
            revalidatePrioritySearch()
            refreshLabels()
            noteDraftChange("version", ui("QRバージョンを変更", "Changed QR Version"), committedVersion.toString())
            checkVersionErrorLimit()
        }
        fun requestVersion(requested: Int) {
            overlay.clearCornerSelection()
            if (requested == committedVersion || versionConfirmationShowing) return
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
            val finderWorking = working
            val finderProfile = profile.copy(settings = profile.settings.copy(
                manualQuad = overlay.imageQuad().takeIf { overlay.isLogicalGridAssigned() },
                confirmedFinderPolarity = overlay.confirmedFinderPolarity(),
            ))
            Toast.makeText(this, ui("指定位置の周辺からFinderを精密探索中…", "Refining the Finder near the selected position…"), Toast.LENGTH_SHORT).show()
            executor.execute {
                val refined = io.github.earthdiver.qrrepair.core.refineFinder(finderWorking.luma, finderProfile.settings, hint)
                runOnUiThread {
                    if (finderToken != finderSearchGeneration.get() || working !== finderWorking) return@runOnUiThread
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
        fun rotateBy(delta: Float) {
            finderSearchGeneration.incrementAndGet()
            overlay.clearCornerSelection()
            dialogState.rotateBy(delta)
            val previous = working
            working = BitmapUtils.forSettings(source, imageSettings())
            releaseWorkingIfTemporary(previous, working)
            overlay.setBitmap(working.bitmap)
            overlay.setVersion(version.progress + 1)
            overlay.clearFinders()
            userGeometryEdited = true
            noteDraftChange("rotation", ui("画像を回転", "Rotated image"), "${"%.1f".format(dialogState.rotationDegrees)}°")
            refreshLabels()
            Toast.makeText(activity, ui("回転 ${"%.1f".format(dialogState.rotationDegrees)}°。四隅とFinder役割を確認してください。", "Rotation ${"%.1f".format(dialogState.rotationDegrees)}°. Check the corners and Finder roles."), Toast.LENGTH_SHORT).show()
        }
        fun resetRotation() {
            finderSearchGeneration.incrementAndGet()
            dialogState.resetRotation()
            val previous = working
            working = BitmapUtils.forSettings(source, imageSettings())
            releaseWorkingIfTemporary(previous, working)
            overlay.setBitmap(working.bitmap)
            overlay.setVersion(version.progress + 1)
            overlay.clearFinders()
            userGeometryEdited = true
            refreshLabels()
            noteDraftChange("rotation", ui("画像回転をリセット", "Reset image rotation"), "0°")
        }
        val formatMismatchLimitNote = TextView(this).apply { textSize = 12f }
        lateinit var formatMismatchLimitButton: Button
        fun errorLimitModeLabel(mode: ErrorLimitMode, value: Int?, valueLabel: String): String = when (mode) {
            ErrorLimitMode.AUTOMATIC -> getString(R.string.error_limit_automatic_value, valueLabel)
            ErrorLimitMode.STRICT -> getString(R.string.error_limit_strict_value, valueLabel)
            ErrorLimitMode.RELAXED -> getString(R.string.error_limit_relaxed_value, valueLabel)
            ErrorLimitMode.MANUAL -> if (value == null) valueLabel else getString(R.string.error_limit_manual_value, valueLabel)
            ErrorLimitMode.EXACT -> getString(R.string.error_limit_exact)
            ErrorLimitMode.UNLIMITED -> valueLabel
        }
        fun effectiveFormatMismatchLimit() = searchSettings.effectiveFormatMismatchLimit()
        fun formatMismatchLimitValueLabel(value: Int?): String = when (value) {
            null -> getString(R.string.format_mismatch_limit_unlimited)
            0 -> getString(R.string.format_mismatch_limit_exact)
            else -> resources.getQuantityString(R.plurals.format_mismatch_limit_bits, value, value)
        }
        fun refreshFormatMismatchLimitControl() {
            val effective = effectiveFormatMismatchLimit()
            formatMismatchLimitButton.text = errorLimitModeLabel(searchSettings.formatMismatchLimitMode, effective,
                formatMismatchLimitValueLabel(effective))
            formatMismatchLimitButton.isEnabled = true
            formatMismatchLimitNote.setText(R.string.format_mismatch_limit_note)
        }
        formatMismatchLimitButton = button("", {})
        formatMismatchLimitButton.setOnClickListener {
            showBitErrorLimitDialog(this, getString(R.string.format_mismatch_limit),
                currentMode = searchSettings.formatMismatchLimitMode,
                currentManualValue = searchSettings.formatMismatchLimit,
                maximum = 30,
                automaticLimit = ErrorLimitPolicy.format(ErrorLimitMode.AUTOMATIC, null),
                strictLimit = ErrorLimitPolicy.STRICT_FORMAT,
                relaxedLimit = ErrorLimitPolicy.RELAXED_FORMAT,
            ) { mode, value ->
                searchSettings.setFormatMismatchLimit(mode, value)
                refreshFormatMismatchLimitControl()
                refreshLabels()
                noteDraftChange(
                    "formatMismatchLimit",
                    ui("形式情報の誤り上限を変更", "Changed format-information error limit"),
                    formatMismatchLimitButton.text.toString(),
                )
            }
        }
        val formatMismatchLimitControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { setText(R.string.format_mismatch_limit) })
            addView(formatMismatchLimitButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(formatMismatchLimitNote)
        }
        refreshFormatMismatchLimitControl()
        lateinit var dataEccMismatchLimitButton: Button
        fun effectiveDataEccMismatchLimit() = searchSettings.effectiveDataEccMismatchLimit(version.progress + 1)
        fun dataEccMismatchLimitValueLabel(value: Int?): String = when (value) {
            null -> getString(R.string.data_ecc_mismatch_limit_unlimited)
            0 -> getString(R.string.data_ecc_mismatch_limit_exact)
            else -> resources.getQuantityString(R.plurals.data_ecc_mismatch_limit_bits, value, value)
        }
        fun refreshDataEccMismatchLimitControl() {
            val effective = effectiveDataEccMismatchLimit()
            dataEccMismatchLimitButton.text = errorLimitModeLabel(searchSettings.dataEccMismatchLimitMode, effective,
                dataEccMismatchLimitValueLabel(effective))
        }
        dataEccMismatchLimitButton = button("", {})
        fun dataEccMaximum() = searchSettings.dataEccMaximum(version.progress + 1)
        fun editDataEccLimit(notice: String? = null) {
            showBitErrorLimitDialog(this, getString(R.string.data_ecc_mismatch_limit),
                currentMode = searchSettings.dataEccMismatchLimitMode,
                currentManualValue = searchSettings.dataEccMismatchLimit,
                maximum = dataEccMaximum(),
                automaticLimit = ErrorLimitPolicy.data(version.progress + 1, ErrorLimitMode.AUTOMATIC, null),
                strictLimit = ErrorLimitPolicy.strictData(version.progress + 1),
                relaxedLimit = ErrorLimitPolicy.relaxedData(version.progress + 1),
                notice = notice,
            ) { mode, value ->
                searchSettings.setDataEccMismatchLimit(mode, value)
                refreshDataEccMismatchLimitControl()
                noteDraftChange(
                    "dataEccMismatchLimit",
                    ui("データ・ECCの誤り上限を変更", "Changed data/ECC error limit"),
                    dataEccMismatchLimitButton.text.toString(),
                )
            }
        }
        fun requestValidDataEccLimit(): Boolean {
            if (searchSettings.dataEccLimitIsValid(version.progress + 1)) return true
            editDataEccLimit(ui(
                "現在の上限はVersion ${version.progress + 1}のデータ・ECC総bit数を超えています。上限を再指定するか「制限なし」を選択してください。",
                "The current limit exceeds the total data/ECC bits for Version ${version.progress + 1}. Enter a new limit or select Unlimited.",
            ))
            return false
        }
        checkVersionErrorLimit = {
            refreshDataEccMismatchLimitControl()
            requestValidDataEccLimit()
        }
        dataEccMismatchLimitButton.setOnClickListener { editDataEccLimit() }
        val dataEccMismatchLimitControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { setText(R.string.data_ecc_mismatch_limit) })
            addView(dataEccMismatchLimitButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(TextView(activity).apply {
                setText(R.string.data_ecc_mismatch_limit_note)
                textSize = 12f
            })
        }
        refreshDataEccMismatchLimitControl()
        val partialBitLimitButton = button("", {}).apply {
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
        }
        fun refreshPartialBitLimit() {
            partialBitLimitButton.text = getString(R.string.partial_bit_search_limit, searchSettings.partialBitSearchLimit)
        }
        refreshPartialBitLimit()
        partialBitLimitButton.setOnClickListener {
            val choices = io.github.earthdiver.qrrepair.core.PartialBitSearchLimit.supported.toList()
            AlertDialog.Builder(this).setTitle(R.string.partial_bit_search_limit_title)
                .setSingleChoiceItems(choices.map { getString(R.string.partial_bit_search_limit_choice, it) }.toTypedArray(),
                    choices.indexOf(searchSettings.partialBitSearchLimit)) { dialog, which ->
                    searchSettings.partialBitSearchLimit = choices[which]
                    refreshPartialBitLimit()
                    noteDraftChange("partial-bit-limit", getString(R.string.partial_bit_search_limit_title),
                        getString(R.string.partial_bit_search_limit_choice, searchSettings.partialBitSearchLimit))
                    dialog.dismiss()
                }.setNegativeButton(R.string.close, null).show()
        }
        val partialBitSearchControl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(partialBitLimitButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(activity).apply {
                setText(R.string.partial_bit_search_limit_note)
                textSize = 12f
            })
        }
        val priorityLimitButton = button("", {}).apply {
            setSingleLine(false)
            maxLines = Int.MAX_VALUE
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun refreshPriorityLimit() {
            val count = cellEditState.prioritySearch.size
            priorityLimitButton.text = SpannableString(ui(
                " * （優先探索）の上限: ${cellEditState.priorityLimit}個\n現在${count}個・${1 shl count}通り",
                " *  (priority search) limit: ${cellEditState.priorityLimit} cells\n$count selected · ${1 shl count} combinations",
            )).apply {
                setSpan(BackgroundColorSpan(if (modernStyle) PrecisionStyle.OUTLINE else 0xFFE0E8E3.toInt()),
                    0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        refreshPriorityLimit()
        refreshPriorityControl = ::refreshPriorityLimit
        priorityLimitButton.setOnClickListener {
            val choices = (4..14).toList()
            AlertDialog.Builder(this).setTitle(ui("優先探索のセル数上限", "Priority search cell limit"))
                .setSingleChoiceItems(choices.map { "$it" }.toTypedArray(), cellEditState.priorityLimit - 4) { dialog, which ->
                    cellEditState.priorityLimit = choices[which]
                    refreshPriorityLimit()
                    updatePreEditCountStatus()
                    noteDraftChange("priority-limit", ui("優先探索の上限を変更", "Changed priority search limit"), "${choices[which]}")
                    dialog.dismiss()
                    if (cellEditState.prioritySearch.size > choices[which]) {
                        AlertDialog.Builder(this).setMessage(ui(
                            "既存の*は保持しました。修復前に上限を戻すか、*の指定数を減らしてください。",
                            "Existing * marks were preserved. Raise the limit or remove some marks before repair."))
                            .setPositiveButton(R.string.close, null).show()
                    }
                }.setNegativeButton(R.string.close, null).show()
        }
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
                overlay.setCellSelection(cellEditState.selectedCells)
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
            formatMismatchLimitControl = formatMismatchLimitControl,
            dataEccMismatchLimitControl = dataEccMismatchLimitControl,
            prioritySearchControl = priorityLimitButton,
            partialBitSearchControl = partialBitSearchControl,
            actions = DetailedSettingsPageActions(
                rotateBy = ::rotateBy,
                resetRotation = ::resetRotation,
                saveDiagnosticInput = {
                    val diagnostic = BitmapUtils.forSettings(source, imageSettings(), quietModules = 4.0).bitmap
                    host.saveDiagnosticInput(diagnostic, owned = diagnostic !== source.bitmap)
                },
            ),
        ).view
        var singleCellEditMode = true
        val previewSampler = PreRecoveryPreviewSampler()
        var provisionalImage = initialWorking
        fun sampleImageMatrix(): Pair<GridGeometry, QrMatrix>? {
            val q = overlay.imageQuad()
            val dimension = 17 + 4 * (version.progress + 1)
            val pitch = ((q.topLeft.distanceTo(q.topRight) + q.topLeft.distanceTo(q.bottomLeft)) / (2.0 * (dimension - 1))).coerceAtLeast(.25)
            val g = runCatching { GridGeometry(
                version.progress + 1, FinderRole.TOP_LEFT, q.topLeft.x, q.topLeft.y,
                pitch, 0.0, q, io.github.earthdiver.qrrepair.core.GeometryModel.MANUAL_PROJECTIVE,
            ).reanchored() }.getOrNull() ?: return null
            if (working !== provisionalImage) {
                dialogState.updateProvisionalThresholds(io.github.earthdiver.qrrepair.core.InitialThresholdEstimator.estimate(working.bitmap))
                provisionalImage = working
            }
            val requested = SamplingProfile(dialogState.previewSettings(controlSnapshot()),
                inverted = when (overlay.confirmedFinderPolarity()) {
                    io.github.earthdiver.qrrepair.core.FinderPolarity.INVERTED -> true
                    io.github.earthdiver.qrrepair.core.FinderPolarity.NORMAL -> false
                    else -> selectedPolarity() == PolarityMode.INVERTED
                })
            val (matrix, stats) = previewSampler.sample(working.luma, g, requested, overlay.isLogicalGridAssigned())
            stats.thresholds?.let { pair ->
                updatingThresholdControls = true
                black.progress = pair.blackMax; white.progress = pair.whiteMin - 1
                updatingThresholdControls = false
                updateThresholdLabels()
            }
            activePreviewProfile = requested.copy(settings = requested.settings.copy(
                blackMax = black.progress, whiteMin = white.progress + 1,
            ))
            return g to matrix
        }
        fun refreshPreRecoveryMatrix(): Pair<GridGeometry, QrMatrix>? {
            val (g, matrix) = sampleImageMatrix() ?: return null
            cellEditState.applyTo(matrix, allowFixedCorrections.isChecked)
            // Cell edits (including *, undo and redo) must update eligibility immediately.
            val headerComplete = recoveryEngine.hasTrustablePayloadHeader(
                matrix, searchSettings.formatConstraint, effectiveFormatMismatchLimit(), manualEdits = cellEditState.fixedEdits)
            trustKnownPayloadCount.isEnabled = headerComplete || trustKnownPayloadCount.isChecked
            return g to matrix
        }
        fun setManualFocus(enabled: Boolean) {
            manualFocus = enabled
            overlay.highlightManualOnly = enabled
            dedicatedEditor?.highlightManualOnly = enabled
            preEditSourceControl?.setManualFocus(enabled)
            dedicatedSourceControl?.setManualFocus(enabled)
        }
        refreshSourceControls = {
            val baseline = if (cellEditState.selectedCells.isNotEmpty()) sampleImageMatrix()?.second else null
            preEditSourceControl?.update(cellEditState, baseline, allowFixedCorrections.isChecked)
            dedicatedSourceControl?.update(cellEditState, baseline, allowFixedCorrections.isChecked)
        }
        refreshClassificationMatrix = {
            refreshSourceControls()
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                overlay.setCellEditPreview(geometry, matrix)
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
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
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelected(x, y)
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
                dedicatedEditStatus?.text = preEditStatus?.text
                dedicatedEditor?.setSelection(cellEditState.selectedCells)
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
        fun setPreEditValues(coords: Collection<Coord>, value: ModuleValue) {
            val original = sampleImageMatrix()?.second ?: return
            val changed = cellEditState.applyValues(coords, value) { coord ->
                original.takeIf { coord.x in 0 until it.dimension && coord.y in 0 until it.dimension }
                    ?.get(coord.x, coord.y)?.value
            }
            if (changed.isEmpty()) return
            val first = changed.first()
            refreshPreRecoveryMatrix()?.let { (updatedGeometry, matrix) ->
                overlay.setCellEditing(updatedGeometry, matrix, if (singleCellEditMode) first else overlay.selectedEditCell() ?: first)
                overlay.setCellSelection(changed)
                dedicatedEditor?.setDocument(working.bitmap, updatedGeometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelection(changed)
            }
            preEditStatus?.text = ui("${changed.size}セルを変更しました。", "Changed ${changed.size} cell(s).")
            dedicatedEditStatus?.text = preEditStatus?.text
            updatePreEditCountStatus()
            noteDraftChange("cell-edit", ui("修復前セルを編集", "Edited pre-repair cells"), ui("${changed.size}セル", "${changed.size} cell(s)"))
        }
        fun changeCellSource(source: CellEditSource) {
            val original = sampleImageMatrix()?.second ?: return
            val selected = cellEditState.selectedCells
            if (selected.isEmpty()) return
            val changed = cellEditState.setSource(selected, source) { coord ->
                original.takeIf { coord.x in 0 until it.dimension && coord.y in 0 until it.dimension }
                    ?.get(coord.x, coord.y)?.value
            }
            if (changed.isEmpty()) return
            // Recompute classification first; it clears overlay selection. Restore focus below
            // so switching provenance keeps the 5x magnifier open on the selected cell.
            refreshClassificationMatrix()
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                val focus = if (singleCellEditMode) changed.first() else overlay.selectedEditCell() ?: changed.first()
                overlay.setCellEditing(geometry, matrix, focus)
                overlay.setCellSelection(changed)
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelection(changed)
            }
            val label = getString(when (source) {
                CellEditSource.IMAGE -> R.string.cell_source_image
                CellEditSource.TENTATIVE -> R.string.cell_source_tentative
                CellEditSource.FIXED -> R.string.cell_source_fixed
            })
            preEditStatus?.text = ui("${changed.size}セル: $label", "${changed.size} cell(s): $label")
            dedicatedEditStatus?.text = preEditStatus?.text
            updatePreEditCountStatus()
            noteDraftChange("cell-source", label, ui("${changed.size}セル", "${changed.size} cell(s)"))
        }
        fun setPrioritySearch(coords: Collection<Coord>) {
            val distinct = coords.distinct()
            if (distinct.isEmpty()) return
            if (!overlay.isLogicalGridAssigned()) {
                Toast.makeText(this, R.string.priority_requires_geometry, Toast.LENGTH_LONG).show()
                return
            }
            val invalid = distinct.filter { it !in cellRegions().codewordCoordinates }
            if (invalid.isNotEmpty()) {
                val kinds = invalid.map(::cellRegionLabel).distinct().joinToString(" / ")
                val message = getString(R.string.priority_invalid_position, kinds) + "\n" +
                    invalid.joinToString(" ") { "(${it.x},${it.y})" }
                AlertDialog.Builder(activity).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                return
            }
            when (cellEditState.setPrioritySearch(distinct)) {
                PriorityEditResult.LIMIT_EXCEEDED -> {
                    Toast.makeText(this, ui("*は最大${cellEditState.priorityLimit}セルです。詳細設定で変更できます。", "Up to ${cellEditState.priorityLimit} cells can be marked *. Change this in advanced settings."), Toast.LENGTH_SHORT).show(); return
                }
                PriorityEditResult.UNCHANGED -> return
                PriorityEditResult.CHANGED -> Unit
            }
            val changed = cellEditState.selectedCells
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                overlay.setCellEditing(geometry, matrix, overlay.selectedEditCell() ?: changed.first())
                overlay.setCellSelection(changed)
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelection(changed)
            }
            preEditStatus?.text = ui("${changed.size}セルを*に指定しました。", "Marked ${changed.size} cell(s) as *.")
            dedicatedEditStatus?.text = preEditStatus?.text
            updatePreEditCountStatus()
            noteDraftChange("cell-edit-priority", ui("優先探索セルを指定", "Marked priority-search cells"), ui("${changed.size}セル", "${changed.size} cell(s)"))
        }
        fun undoPreEdit() {
            val changed = cellEditState.undo()
            if (changed.isEmpty()) return
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                val focus = if (singleCellEditMode) changed.firstOrNull() else overlay.selectedEditCell() ?: changed.firstOrNull()
                overlay.setCellEditing(geometry, matrix, focus)
                overlay.setCellSelection(changed)
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelection(changed)
            }
            preEditStatus?.text = ui("${changed.size}セルの編集を戻しました。", "Undid edits to ${changed.size} cell(s).")
            dedicatedEditStatus?.text = preEditStatus?.text
            updatePreEditCountStatus()
            noteDraftChange("cell-edit", ui("修復前セル編集をUndo", "Undid pre-repair cell edits"), ui("${changed.size}セル", "${changed.size} cell(s)"))
        }
        fun redoPreEdit() {
            val changed = cellEditState.redo()
            if (changed.isEmpty()) return
            refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                val focus = if (singleCellEditMode) changed.firstOrNull() else overlay.selectedEditCell() ?: changed.firstOrNull()
                overlay.setCellEditing(geometry, matrix, focus)
                overlay.setCellSelection(changed)
                dedicatedEditor?.setDocument(working.bitmap, geometry, matrix, keepViewport = true)
                dedicatedEditor?.setSelection(changed)
            }
            preEditStatus?.text = ui("${changed.size}セルの編集をやり直しました。", "Redid edits to ${changed.size} cell(s).")
            dedicatedEditStatus?.text = preEditStatus?.text
            updatePreEditCountStatus()
            noteDraftChange("cell-edit", ui("修復前セル編集をRedo", "Redid pre-repair cell edits"), ui("${changed.size}セル", "${changed.size} cell(s)"))
        }
        overlay.onCellBrushApplied = { coords, value -> setPreEditValues(coords, value) }
        overlay.onCellRangeSelected = { coords ->
            cellEditState.select(coords)
            overlay.setCellSelection(coords, asRectangle = true)
            preEditStatus?.text = ui("矩形選択 ${coords.size}セル。0/1/?で一括変更できます。", "Selected ${coords.size} cells. Use 0/1/? to change them together.")
            refreshSourceControls()
        }
        fun showSingleCellEditor() {
            singleCellEditMode = true
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
            singleCellEditMode = false
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
            val largeEditor = QrEditorView(activity).apply {
                setDocument(working.bitmap, initial.first, initial.second)
                setCodewordOverlayVersion(version.progress + 1)
                setSelection(emptySet())
                cornerEditEnabled = false
                showQrStructureOverlay = true
            }
            dedicatedEditor = largeEditor
            lateinit var largePage: PreRecoveryLargeCellEditPage
            fun largeEditorToolStatus(tool: EditorTool): CharSequence = when (tool) {
                EditorTool.NAVIGATE -> getString(R.string.pan_zoom_status)
                EditorTool.BRUSH_ZERO -> ui("0ブラシ：画像位置を固定してなぞります。2本目の指が触れると操作をキャンセルします。", "0 brush: paint while the image is locked. A second touch cancels the gesture.")
                EditorTool.BRUSH_ONE -> ui("1ブラシ：画像位置を固定してなぞります。2本目の指が触れると操作をキャンセルします。", "1 brush: paint while the image is locked. A second touch cancels the gesture.")
                EditorTool.BRUSH_ERASURE -> ui("?ブラシ：画像位置を固定してなぞります。2本目の指が触れると操作をキャンセルします。", "? brush: paint while the image is locked. A second touch cancels the gesture.")
                EditorTool.BRUSH_ERASURE_WIDE -> ui("?ブラシ3：3モジュール幅でなぞります。", "? brush 3: paint with a three-module-wide brush.")
                EditorTool.RECT_SELECT -> ui("矩形選択：画像位置を固定してドラッグします。選択後に0/1/?を指定します。", "Rectangle: drag while the image is locked, then assign 0/1/?.")
                EditorTool.STAMP_ERASURE_SMALL -> ui("?スタンプ小：タップ位置を中心に3×3を?にします。", "Small ? stamp: tap to erase a 3×3 area.")
                EditorTool.STAMP_ERASURE_MEDIUM -> ui("?スタンプ中：タップ位置を中心に5×5を?にします。", "Medium ? stamp: tap to erase a 5×5 area.")
                EditorTool.STAMP_ERASURE_LARGE -> ui("?スタンプ大：タップ位置を中心に7×7を?にします。", "Large ? stamp: tap to erase a 7×7 area.")
            }
            fun setLargeEditorTool(tool: EditorTool) {
                largeEditor.editorTool = tool
                largePage.status.text = largeEditorToolStatus(tool)
            }
            largeEditor.onCellSelected = { x, y ->
                cellEditState.select(setOf(Coord(x, y)))
                val matrix = refreshPreRecoveryMatrix()?.second
                val value = matrix?.get(x, y)?.value
                val coord = Coord(x, y)
                val codeword = largeEditor.codewordCellAt(coord)
                val kind = when {
                    QrFunctionMask.isFunction(version.progress + 1, x, y) ->
                        cellRegionLabel(coord)
                    codeword == null -> cellRegionLabel(coord)
                    codeword.entry.kind == CodewordOverlayKind.UNCLASSIFIED ->
                        ui("コード語 #${codeword.entry.rawIndex} / 分類未確定 / bit ${codeword.bitIndex}",
                            "Codeword #${codeword.entry.rawIndex} / unclassified / bit ${codeword.bitIndex}")
                    else -> {
                        val role = if (codeword.entry.kind == CodewordOverlayKind.DATA) "Data" else "ECC"
                        "Codeword #${codeword.entry.rawIndex} / Block ${(codeword.entry.blockIndex ?: 0) + 1} / $role #${codeword.entry.kindIndex} / bit ${codeword.bitIndex}"
                    }
                }
                val label = if (Coord(x, y) in cellEditState.prioritySearch) "*" else when (value) { ModuleValue.ZERO -> "0"; ModuleValue.ONE -> "1"; else -> "?" }
                largePage.status.text = ui("選択: ($x,$y) / $kind / 値=$label", "Selected: ($x,$y) / $kind / value=$label")
                overlay.setCellSelection(cellEditState.selectedCells)
                refreshSourceControls()
            }
            largeEditor.onBrushApplied = { coords, value -> setPreEditValues(coords, value) }
            largeEditor.onRangeSelected = { coords ->
                cellEditState.select(coords)
                largeEditor.setSelection(coords, asRectangle = true)
                overlay.setCellSelection(coords, asRectangle = true)
                largePage.status.text = ui("矩形選択 ${coords.size}セル。0/1/?で一括変更できます。", "Selected ${coords.size} cells. Use 0/1/? to change them together.")
                refreshSourceControls()
            }
            largePage = PreRecoveryLargeCellEditPage(
                this,
                editor = largeEditor,
                buttonFactory = ::button,
                compactRow = { views -> compactRow(*views) },
                scrollRow = { views -> scrollRow(*views) },
                actions = LargeCellEditPageActions(
                    setTool = ::setLargeEditorTool,
                    setValue = { value -> setPreEditValues(cellEditState.selectedCells, value) },
                    setPriority = { setPrioritySearch(cellEditState.selectedCells) },
                    changeSource = ::changeCellSource,
                    undo = ::undoPreEdit,
                    redo = ::redoPreEdit,
                    showCodewords = { checked ->
                        largeEditor.showCodewordOverlay = checked
                        largePage.status.text = if (checked) getString(R.string.codeword_overlay_legend)
                        else largeEditorToolStatus(largeEditor.editorTool)
                    },
                    close = { dedicatedEditorDialog?.dismiss() },
                ),
            )
            dedicatedSourceControl = largePage.sourceControl
            largePage.sourceControl.onManualFocusChanged = ::setManualFocus
            setManualFocus(manualFocus)
            dedicatedEditStatus = largePage.status
            dedicatedEditCountStatus = largePage.countStatus
            updatePreEditCountStatus()
            val dialog = AlertDialog.Builder(this)
                .setView(largePage.view)
                .create()
            dedicatedEditorDialog = dialog
            dialog.setOnShowListener {
                dialog.window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            dialog.setOnDismissListener {
                dedicatedSourceControl = null
                dedicatedEditor = null
                dedicatedEditorDialog = null
                dedicatedEditStatus = null
                dedicatedEditCountStatus = null
                refreshPreRecoveryMatrix()?.let { (geometry, matrix) ->
                    overlay.setCellEditing(geometry, matrix, overlay.selectedEditCell())
                    overlay.setCellSelection(cellEditState.selectedCells)
                }
                updatePreEditCountStatus()
            }
            dialog.show()
        }
        val cellEditPage = PreRecoveryCellEditPage(
            this,
            buttonFactory = ::button,
            compactRow = { views -> compactRow(*views) },
            actions = CellEditPageActions(
                move = ::movePreEditCell,
                setValue = { value -> setPreEditValues(cellEditState.selectedCells, value) },
                setPriority = { setPrioritySearch(cellEditState.selectedCells) },
                changeSource = ::changeCellSource,
                undo = ::undoPreEdit,
                redo = ::redoPreEdit,
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
            refreshFormatMismatchLimitControl()
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
                    val previous = working
                    working = adjusted
                    releaseWorkingIfTemporary(previous, working)
                    overlay.setBitmap(working.bitmap)
                    overlay.setPhysicalImageQuad(if (dialogState.mirroredInput) rectified.qrQuad.transposed() else rectified.qrQuad)
                    overlay.clearFinders()
                    userGeometryEdited = true
                    refreshLabels()
                    noteDraftChange("perspective", ui("台形補正を適用", "Applied perspective correction"), "${working.bitmap.width}x${working.bitmap.height}")
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
            releaseWorkingIfTemporary(working, source)
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
            fun capturePreprocessSettings(): PreprocessSettings {
                return dialogState.captureSettings(
                    controls = controlSnapshot(),
                    geometry = PreprocessGeometrySnapshot(
                        logicalGridAssigned = overlay.isLogicalGridAssigned(),
                        quad = overlay.imageQuad(),
                        finders = overlay.finderPoints(),
                        confirmedFinderPolarity = overlay.confirmedFinderPolarity(),
                    ),
                    edits = cellEditState.edits.toMap(),
                    prioritySearch = cellEditState.prioritySearch.toSet(),
                    softEdits = cellEditState.softEdits.toSet(),
                )
            }
            captureEditorDraft = {
                PreprocessEditorDraft(capturePreprocessSettings(), cellEditState.copyForEditing(),
                    draftState.copyForEditing(), pageHost.currentPage())
            }
            host.editorOpened(session)
            fun leaveEditor() {
                dialog.dismiss()
                if (modernStyle) host.showHome()
            }
            cancelPreprocess = cancel@{
                overlay.clearCornerSelection()
                val hasUnsavedChanges = draftState.hasUnsavedChanges(capturePreprocessSettings())
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
                draftState.note(key, label, detail, capturePreprocessSettings())
            }
            overlay.onGeometryEditFinished = {
                overlay.removeCallbacks(refreshDraggedGrid)
                refreshClassificationMatrix()
                noteDraftChange("quad", ui("四隅を調整", "Adjusted corners"), ui("ドラッグまたは矢印で調整", "Adjusted by drag or arrow keys"))
            }
            fun persistPreprocessDraft(): Boolean {
                if (!requestValidDataEccLimit()) return false
                val captured = capturePreprocessSettings()
                searchSettings.formatConflict(captured.version, captured.fixedPreRecoveryEdits)?.let { showFormatEditError(it); return false }
                val events = mutableListOf<PreprocessDraftEvent>()
                draftState.commit(captured, events::add)
                host.save(PreprocessSave(captured, cellEditState.copyForEditing(), working, events))
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
        activity.resources.configuration.locales[0]?.language == "ja"

    private fun ui(japanese: String, english: String): String =
        if (isJapaneseUi()) japanese else english

    private fun showFormatEditError(conflict: FormatEditConflict) {
        AlertDialog.Builder(activity)
            .setTitle(ui("形式情報の指定エラー", "Format selection error"))
            .setMessage(conflict.message(true))
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
