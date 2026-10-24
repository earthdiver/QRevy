package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.FormatCellGroup
import io.github.earthdiver.qrrepair.core.formatGroupDetailText
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.resultContentText
import io.github.earthdiver.qrrepair.core.resultExplanationText
import io.github.earthdiver.qrrepair.core.candidatePairComparisonText
import io.github.earthdiver.qrrepair.ui.ResultActionRow
import io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport
import io.github.earthdiver.qrrepair.core.codewordCellText
import io.github.earthdiver.qrrepair.core.displayedModuleValue
import io.github.earthdiver.qrrepair.core.RecoveryInputBlocker
import io.github.earthdiver.qrrepair.core.identity
import io.github.earthdiver.qrrepair.core.isSelectableRecovery
import io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCounter
import io.github.earthdiver.qrrepair.core.RecoveryMemoryPolicy
import io.github.earthdiver.qrrepair.core.ListDecodingBudget
import io.github.earthdiver.qrrepair.core.RecoveryRetentionBudget
import io.github.earthdiver.qrrepair.core.RecoverySelectionIdentity
import io.github.earthdiver.qrrepair.core.SamplingProfile

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Resources
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import io.github.earthdiver.qrrepair.core.CodewordHex
import io.github.earthdiver.qrrepair.core.CodewordExtractor
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.FinderPolarity
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.FormatSolver
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.GeometryModel
import io.github.earthdiver.qrrepair.core.MatrixSampler
import io.github.earthdiver.qrrepair.core.ModuleValue
import com.google.zxing.qrcode.decoder.Version
import io.github.earthdiver.qrrepair.core.PolarityMode
import io.github.earthdiver.qrrepair.core.PartialPayloadAnalyzer
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.StandardQrDecoder
import io.github.earthdiver.qrrepair.core.searchSummary
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.QrFunctionMask
import io.github.earthdiver.qrrepair.core.FormatEditPolicy
import io.github.earthdiver.qrrepair.core.FormatEditConflict
import io.github.earthdiver.qrrepair.core.QrTextMatrixParser
import io.github.earthdiver.qrrepair.core.QrTextMatrixOrientationEstimator
import io.github.earthdiver.qrrepair.core.QrCellRegions
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.SearchCompleteness
import io.github.earthdiver.qrrepair.core.RecoveryEngine
import io.github.earthdiver.qrrepair.core.RecoveryWorkflow
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySessionHistory
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.historyCellChangeCount
import io.github.earthdiver.qrrepair.core.InitialThresholdEstimator
import io.github.earthdiver.qrrepair.core.QrContentPreviewParser
import io.github.earthdiver.qrrepair.core.QrContentPreview
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.core.RepairGuidance
import io.github.earthdiver.qrrepair.core.RepairTraceBuilder
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.RepairPreviewView
import io.github.earthdiver.qrrepair.ui.ImageAdjustmentEditor
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec
import io.github.earthdiver.qrrepair.session.SessionSaveQueue
import io.github.earthdiver.qrrepair.session.StoredSession
import com.google.zxing.Result
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.util.concurrent.Executors
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val CAMERA_CAPTURE_STALE_AFTER_MS = 24L * 60L * 60L * 1000L
internal const val MAX_DISPLAYED_RECOVERY_CANDIDATES = 256

internal fun purgeStaleCameraCaptures(
    cacheDir: File,
    nowMs: Long = System.currentTimeMillis(),
    staleAfterMs: Long = CAMERA_CAPTURE_STALE_AFTER_MS,
) {
    require(staleAfterMs >= 0L)
    val incompleteCrop = Regex("qr-capture-.+\\.(?:jpg|png)\\.crop-.+\\.tmp", RegexOption.IGNORE_CASE)
    cacheDir.listFiles()
        ?.filter {
            it.isFile &&
                it.name.startsWith("qr-capture-") &&
                (it.name.endsWith(".jpg", ignoreCase = true) || it.name.endsWith(".png", ignoreCase = true) ||
                    incompleteCrop.matches(it.name)) &&
                nowMs - it.lastModified() >= staleAfterMs
        }
        ?.forEach { it.delete() }
}

internal fun replaceOwnedBitmap(
    source: Bitmap,
    transform: (Bitmap) -> Bitmap,
): kotlin.Result<Bitmap> = runCatching { transform(source) }
    .onSuccess { transformed ->
        if (transformed !== source) source.recycle()
    }

class MainActivity : Activity() {
    companion object {
        private const val STATE_SESSION_ID = "main.session_id"
        private const val REQ_OPEN_IMAGE = 1001
        private const val REQ_SAVE_PNG = 1002
        private const val REQ_CAMERA = 1003
        private const val REQ_SAVE_DIAGNOSTIC_INPUT = 1004
        private const val REQ_OPEN_TEXT_MATRIX = 1005
    }

    private lateinit var activeStyle: AppStyle
    private val modernStyle: Boolean get() = activeStyle == AppStyle.MODERN
    private data class RetainedActivityState(
        val style: AppStyle,
        val history: RetainedCellHistory?,
        val editorWork: RetainedEditorWork?,
        val imageImport: RetainedImageImport? = null,
        val immediateResult: ImmediateQrResult? = null,
    )
    private data class RetainedImageImport(val source: Bitmap, val state: Bundle)
    private var inputProgressDialog: AlertDialog? = null
    private var inputProgressGeneration: Int? = null
    private var importEditorDialog: AlertDialog? = null
    private var importEditor: ImageAdjustmentEditor? = null
    private var importSource: Bitmap? = null
    private var retainImportSource = false

    private data class RetainedEditorWork(
        val sessionId: String?,
        val source: BitmapUtils.Prepared,
        val savedSettings: PreprocessSettings,
        val savedEdits: PreRecoveryCellEditState?,
        val history: List<RecoveryHistoryEntry>,
        val cursor: Int,
        val draft: PreprocessEditorDraft,
    )

    private data class ImmediateQrResult(
        val sessionId: String?,
        val entryId: String?,
        val settings: PreprocessSettings,
        val text: String,
        val rawBytes: ByteArray?,
    )
    private var immediateResult: ImmediateQrResult? = null
    private var pendingImmediateResult: ImmediateQrResult? = null
    private var immediateResultShown = false
        set(value) {
            field = value
            if (!value) immediateResult = null
        }
    private lateinit var modernHeader: View
    private var preprocessEditor: PreprocessEditorSession? = null
    private lateinit var homePanel: LinearLayout
    private lateinit var workspacePanel: LinearLayout
    private lateinit var workspacePreview: RepairPreviewView
    private lateinit var homeActions: LinearLayout
    private lateinit var workspaceActions: LinearLayout
    private lateinit var workspaceAdjustAction: Button
    private lateinit var workspaceAction: Button
    private lateinit var workspaceTitle: TextView
    private lateinit var workspaceNavigation: ModernWorkflowNavigation
    private lateinit var workspaceStep: TextView
    private lateinit var resumeButton: Button
    private lateinit var statusText: TextView
    private lateinit var guideText: TextView
    private lateinit var contentPreviewText: TextView
    private lateinit var diagnosticToggle: Button
    private lateinit var diagnosticView: RecoveryDiagnosticView
    private var diagnosticDetailsExpanded = false
    private var diagnosticReport: RecoveryDiagnosticReport? = null
    private var diagnosticResults: Any? = null
    private var comparisonCandidate: RecoveryCandidate? = null
    private lateinit var resultExplanationText: TextView
    private lateinit var resultNextAction: Button
    private lateinit var resultActions: LinearLayout
    private lateinit var resultCopyButton: Button
    private lateinit var resultTailAnalysisButton: Button
    private lateinit var resultSavePngButton: Button
    private lateinit var resultImageLabel: TextView
    private lateinit var mainResultSelector: LinearLayout
    private lateinit var upperResultSelector: LinearLayout


    private val executor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val workGeneration = AtomicInteger(0)
    private val recoveryTasks = RecoveryTaskRunner(executor, { runOnUiThread(it) }, workGeneration::get)
    private var recoveryProgressDialog: RecoveryProgressDialog? = null
    private val sessionSaves = SessionSaveQueue(
        ioExecutor,
        save = { id, entries, cursor -> sessionStore.save(id, UiMode.SIMPLE, entries, cursor) },
        onError = { reportPersistenceFailure(ui("セッションの自動保存に失敗", "Automatic session save failed"), it) },
    )
    private val finderSearchGeneration = AtomicInteger(0)
    @Volatile private var lastPersistenceWarningAtMs = 0L
    private val recoveryEngine = RecoveryEngine()
    private lateinit var sessionStore: RecoverySessionStore
    private val sessionHistory = RecoverySessionHistory(maxEntries = 40)
    private var sessionId: String? = null
    /** Generation of the one history snapshot currently being recalculated, if any. */
    private var historyRestoreToken: Int? = null

    private var prepared: BitmapUtils.Prepared? = null
        set(value) {
            if (field !== value) closePreprocessEditor()
            field = value
        }

    private fun closePreprocessEditor() {
        val editor = preprocessEditor
        preprocessEditor = null
        editor?.close?.invoke()
    }
    /** Source transformed according to the active reproducible preprocessing settings. */
    private var analysisPrepared: BitmapUtils.Prepared? = null
    private var pendingDiagnosticBitmap: PendingDiagnosticBitmap? = null
    private var preprocessSettings = PreprocessSettings().withInitialErrorLimits()
    // In-memory checkpoint for the active work only. Dialogs edit independent copies.
    private var savedCellEditState: PreRecoveryCellEditState? = null
    private data class RetainedCellHistory(
        val sessionId: String,
        val settings: PreprocessSettings,
        val state: PreRecoveryCellEditState,
    )
    private var pendingRetainedCellHistory: RetainedCellHistory? = null
    private val recoveryState = RecoveryUiState()

    override fun getResources(): Resources = AppLanguageManager.localizedResources(super.getResources())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Only configuration recreation inherits the active style. A new launch (including
        // process restoration) reads the saved choice before any themed views are inflated.
        val retained = lastNonConfigurationInstance as? RetainedActivityState
        activeStyle = retained?.style ?: AppStylePreferences.selected(this)
        setTheme(activeStyle.theme)
        super.onCreate(savedInstanceState)
        sessionStore = RecoverySessionStore(this)
        purgeStaleCameraCaptures()
        setContentView(buildUi())
        retained?.imageImport?.let { image ->
            showImportedImageAdjustment(image.source)
            importEditor?.restoreState(image.state)
            return
        }
        retained?.editorWork?.let { work ->
            sessionId = work.sessionId
            prepared = work.source
            analysisPrepared = work.source
            preprocessSettings = work.savedSettings
            savedCellEditState = work.savedEdits
            sessionHistory.replace(work.history, work.cursor)
            retained.immediateResult?.takeIf { it.sessionId == work.sessionId &&
                it.entryId == sessionHistory.current?.id && it.settings == work.savedSettings }?.let {
                renderImmediateQrResult(it, showNotice = false)
            }
            showPreprocessPage(work.draft.page, work.draft)
            return
        }
        val restoredSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
        if (restoredSessionId != null && RecoverySessionCodec.isSafeSessionId(restoredSessionId)) {
            pendingRetainedCellHistory = retained?.history
            pendingImmediateResult = retained?.immediateResult
            restoreSessionById(restoredSessionId)
        } else {
            showStartMenu()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessionId?.let { outState.putString(STATE_SESSION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any {
        val id = sessionId
        val state = savedCellEditState
        val history = if (id != null && state != null) {
            RetainedCellHistory(id, preprocessSettings, state.copyForEditing())
        } else null
        val editorWork = prepared?.let { source ->
            preprocessEditor?.let { editor ->
                RetainedEditorWork(id, source, preprocessSettings, state?.copyForEditing(),
                    sessionHistory.entries.toList(), sessionHistory.cursor, editor.captureDraft())
            }
        }
        val imageImport = importSource?.let { image ->
            importEditor?.let { RetainedImageImport(image, it.saveState()) }
        }
        retainImportSource = imageImport != null
        return RetainedActivityState(activeStyle, history, editorWork, imageImport, immediateResult)
    }

    private fun purgeStaleCameraCaptures() {
        runCatching { purgeStaleCameraCaptures(cacheDir) }
    }

    override fun onDestroy() {
        inputProgressDialog?.dismiss()
        inputProgressDialog = null
        inputProgressGeneration = null
        importEditorDialog?.dismiss()
        closePreprocessEditor()
        workGeneration.incrementAndGet()
        finderSearchGeneration.incrementAndGet()
        releasePendingDiagnosticBitmap()
        executor.shutdownNow()
        // Session writes are small, bounded and atomic. Let the newest queued snapshot finish so
        // configuration changes cannot discard it immediately before the replacement Activity
        // reloads the same session id. Never wait indefinitely on the main thread.
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) ioExecutor.shutdownNow()
        } catch (_: InterruptedException) {
            ioExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        recoveryProgressDialog?.close()
        recoveryProgressDialog = null
        super.onDestroy()
    }

    private fun showLicensesAndPrivacy() {
        fun rawText(id: Int): String = resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val text = buildString {
            append(rawText(R.raw.privacy))
            append("\n\n────────────────────────\n\n")
            append(rawText(R.raw.third_party_notices))
            append("\n\n────────────────────────\nApache License 2.0\n\n")
            append(rawText(R.raw.apache_2_0))
        }
        val body = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            this.text = text
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(R.string.licenses_privacy)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showVersionInfo() {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = info.versionCode
        AlertDialog.Builder(this)
            .setTitle(R.string.version_info)
            .setMessage(getString(R.string.version_info_format, info.versionName, versionCode))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun buildUi(): View = (if (modernStyle) buildModernUi() else buildClassicUi()).also {
        configureResultLayout()
    }

    /** Both appearances use the same reading order and actions. */
    private fun configureResultLayout() {
        val panel = contentPreviewText.parent as LinearLayout
        val ordered = listOf(statusText, upperResultSelector, contentPreviewText, guideText,
            mainResultSelector, diagnosticToggle, diagnosticView)
        ordered.forEach { panel.removeView(it) }
        if (modernStyle) panel.removeView(workspacePreview)
        panel.addView(statusText)
        panel.addView(upperResultSelector)
        panel.addView(contentPreviewText)
        if (modernStyle) {
            resultImageLabel = TextView(this).apply {
                textSize = 12f
                setPadding(dp(8), dp(8), dp(8), dp(4))
                visibility = View.GONE
            }
            panel.addView(resultImageLabel)
            panel.addView(workspacePreview)
        }
        resultExplanationText = TextView(this).apply {
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(8))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        panel.addView(resultExplanationText, panel.indexOfChild(contentPreviewText) + 1)
        panel.addView(guideText)
        resultNextAction = button(ui("入力を確認", "Review input")) { showCellEditor() }.apply { visibility = View.GONE }
        panel.addView(resultNextAction)
        resultCopyButton = button(ui("内容をコピー", "Copy content")) {
            val candidate = recoveryState.selectedRecovery?.takeIf { it.payloadParsed } ?: return@button
            val value = candidate.text ?: return@button
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("QRevy", value))
            Toast.makeText(this, ui("内容をコピーしました。", "Content copied."), Toast.LENGTH_SHORT).show()
        }
        resultSavePngButton = button(getString(R.string.save_png)) { startSavePng() }.apply {
            visibility = View.GONE
        }
        resultTailAnalysisButton = button(getString(R.string.analyze_post_terminator)) { showPostTerminatorAnalysis() }.apply {
            visibility = View.GONE
            val background = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            backgroundTintList = null
            setBackgroundResource(background.resourceId)
            if (modernStyle) setTextColor(PrecisionStyle.MUTED)
            else {
                val colors = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
                try { colors.getColorStateList(0)?.let { setTextColor(it) } } finally { colors.recycle() }
            }
            minHeight = dp(48)
            minimumHeight = dp(48)
        }
        resultActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ResultActionRow(this@MainActivity).apply {
                addView(resultCopyButton)
                addView(resultSavePngButton)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(button(getString(R.string.show_detailed_result)) { showZoomableResult() })
            visibility = View.GONE
        }
        panel.addView(resultActions)
        panel.addView(mainResultSelector)
        panel.addView(diagnosticToggle)
        panel.addView(diagnosticView)
        panel.addView(resultTailAnalysisButton, LinearLayout.LayoutParams(-2, -2))
    }

    private fun buildClassicUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(compactRow(
            button(getString(R.string.menu)) { showAppMenu() },
            button(getString(R.string.history)) { showHistoryPanel() },
            button(getString(R.string.exit)) { finishAffinity() },
        ))

        root.addView(compactRow(
            button(getString(R.string.camera)) { openCamera() },
            button(getString(R.string.select_image)) { chooseImage() },
            button(getString(R.string.import_text_matrix)) { chooseTextMatrix() },
            button(getString(R.string.repair)) { showPreprocessDialog() },
        ))
        val resultContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainResultSelector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        upperResultSelector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        statusText = TextView(this).apply {
            text = getString(R.string.select_image_prompt)
            textSize = 14f
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        resultContent.addView(statusText)
        resultContent.addView(upperResultSelector)

        guideText = TextView(this).apply {
            text = getString(R.string.initial_guide)
            textSize = 13f
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        resultContent.addView(guideText)

        resultContent.addView(mainResultSelector)

        contentPreviewText = TextView(this).apply {
            text = getString(R.string.no_content_preview)
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
            setBackgroundColor(0xFFF4F4F4.toInt())
        }
        resultContent.addView(contentPreviewText)

        diagnosticToggle = button(getString(R.string.show_diagnostic_details)) {
            diagnosticDetailsExpanded = !diagnosticDetailsExpanded
            refreshDiagnosticDetailsVisibility()
        }.apply { visibility = View.GONE }
        resultContent.addView(diagnosticToggle)

        diagnosticView = RecoveryDiagnosticView(this).apply { visibility = View.GONE }
        resultContent.addView(diagnosticView)

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(
                resultContent,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        root.addView(button(getString(R.string.data)) { showCodewordHex() }, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun buildModernUi(): View {
        fun label(value: String, size: Float = 14f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(PrecisionStyle.MUTED)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        fun action(label: String, primary: Boolean = false, run: () -> Unit) = button(label, run).apply {
            textSize = 15f
            PrecisionStyle.button(this, primary)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(PrecisionStyle.ACTION_TOP_MARGIN_DP)
                bottomMargin = dp(PrecisionStyle.ACTION_BOTTOM_MARGIN_DP)
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PrecisionStyle.BACKGROUND)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        modernHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.app_name), 32f).apply {
                PrecisionStyle.brand(this)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(getString(R.string.menu)) { showAppMenu() })
        }
        root.addView(modernHeader)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(ui("●  端末内で処理", "●  Processed on your device"), 12f).apply {
                setTextColor(PrecisionStyle.MUTED)
            })
            addView(label(ui("QRコードの救世主", "A lifesaver for QR codes"), 22f).apply {
                setTextColor(PrecisionStyle.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(RepairPreviewView(this@MainActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(112)))
            addView(action(getString(R.string.camera), true) { openCamera() })
            addView(action(getString(R.string.select_image), true) { chooseImage() })
            resumeButton = action(getString(R.string.resume_work)) { resumeLatestWork() }
            addView(resumeButton)
            addView(label(ui("その他の読み込み・保存した作業", "More import options and saved work"), 13f))
            addView(action(getString(R.string.import_text_matrix_long)) { chooseTextMatrix() })
            addView(action(getString(R.string.saved_work)) { showRecentSessions() })
        }
        workspacePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        workspaceNavigation = ModernWorkflowNavigation(this, ::showPreprocessDialog, ::showCellEditor, ::showWorkspace)
        root.addView(workspaceNavigation.view)
        workspaceStep = label(ui("範囲・濃淡を確認してセル編集へ", "Check the image, then edit cells"), 12f)
        workspacePanel.addView(workspaceStep)
        workspaceTitle = label(ui("画像を調整", "Adjust image")).apply { PrecisionStyle.heading(this) }
        workspacePanel.addView(workspaceTitle)
        workspacePreview = RepairPreviewView(this, square = true).apply {
            contentDescription = ui("QRプレビュー。修復根拠は「結果表示」から確認できます。", "QR preview. Use Show result to inspect repair evidence.")
            PrecisionStyle.panel(this)
        }
        workspacePanel.addView(workspacePreview, LinearLayout.LayoutParams(-1, -2))
        workspaceAction = action(getString(R.string.repair), true) { showPreprocessDialog() }.apply {
            visibility = View.GONE
        }
        mainResultSelector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        upperResultSelector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        statusText = label(getString(R.string.select_image_prompt), 16f).apply { setTextColor(PrecisionStyle.TEXT) }
        guideText = label(getString(R.string.initial_guide), 13f)

        contentPreviewText = label(getString(R.string.no_content_preview)).apply {
            setTextColor(PrecisionStyle.TEXT)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            PrecisionStyle.panel(this)
        }
        // Put the candidate count before the selected candidate and the QR image.
        workspacePanel.addView(statusText, workspacePanel.indexOfChild(workspacePreview))
        workspacePanel.addView(upperResultSelector, workspacePanel.indexOfChild(workspacePreview))
        workspacePanel.addView(contentPreviewText, workspacePanel.indexOfChild(workspacePreview))
        workspacePanel.addView(mainResultSelector)
        workspacePanel.addView(guideText)
        diagnosticToggle = button(getString(R.string.show_diagnostic_details)) {
            diagnosticDetailsExpanded = !diagnosticDetailsExpanded
            refreshDiagnosticDetailsVisibility()
        }.apply { visibility = View.GONE }
        workspacePanel.addView(diagnosticToggle)
        diagnosticView = RecoveryDiagnosticView(this).apply { visibility = View.GONE }
        workspacePanel.addView(diagnosticView)
        content.addView(homePanel)
        content.addView(workspacePanel)
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        workspaceAdjustAction = action(getString(R.string.repair_settings_navigation), true) { showPreprocessDialog() }
        workspaceActions = compactRow(
            button(getString(R.string.home_navigation)) { showStartMenu() }.apply { PrecisionStyle.button(this, home = true) },
            button(getString(R.string.data)) { showCodewordHex() },
            workspaceAdjustAction, workspaceAction)
        root.addView(workspaceActions)
        homeActions = compactRow(
            button(getString(R.string.workspace_navigation)) { openWorkspace() }.apply { PrecisionStyle.button(this, home = true) },
            button(getString(R.string.operation_history)) { showHistoryPanel() },
            button(getString(R.string.exit)) { finishAffinity() }.apply { PrecisionStyle.button(this, exit = true) },
        )
        root.addView(homeActions)
        updateResumeLabel()
        return root
    }

    private fun openWorkspace() {
        if (prepared != null || recoveryState.workingMatrix != null || immediateResultShown) {
            val hasResult = immediateResultShown || recoveryState.selectedRecovery != null || recoveryState.failure != null
            if (modernStyle && prepared != null && !hasResult) showPreprocessDialog() else showWorkspace()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_before_work)
            .setItems(arrayOf(getString(R.string.camera), getString(R.string.select_image),
                getString(R.string.import_text_matrix_long))) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> chooseImage()
                    2 -> chooseTextMatrix()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWorkspace() {
        if (::resultActions.isInitialized) {
            val hasAttempt = recoveryState.selectedRecovery != null || recoveryState.failure != null
            resultActions.visibility = if (hasAttempt) View.VISIBLE else View.GONE
            refreshTailAnalysisVisibility()
            if (!hasAttempt) {
                resultExplanationText.visibility = View.GONE
                resultNextAction.visibility = View.GONE
                clearDiagnosticDetails()
                if (::resultImageLabel.isInitialized) resultImageLabel.visibility = View.GONE
            }
        }
        if (!modernStyle) return
        modernHeader.visibility = View.GONE
        workspaceNavigation.view.visibility = View.VISIBLE
        homePanel.visibility = View.GONE
        workspacePanel.visibility = View.VISIBLE
        homeActions.visibility = View.GONE
        workspaceActions.visibility = View.VISIBLE
        workspaceAction.visibility = View.VISIBLE
        val hasResult = recoveryState.selectedRecovery != null || recoveryState.failure != null
        workspaceTitle.text = when {
            hasResult -> ui("修復結果", "Repair result")
            immediateResultShown -> ui("読み取り結果", "Scan result")
            else -> ui("画像を調整", "Adjust image")
        }
        workspaceStep.text = if (hasResult || immediateResultShown) ui("03 結果  /  内容と根拠を確認", "03 Result  /  Review content and evidence")
            else ui("02 調整  /  範囲・濃淡・セル", "02 Adjust  /  Region, contrast, cells")
        val resultVisible = hasResult || immediateResultShown
        workspaceNavigation.update(if (resultVisible) 2 else 0, prepared != null, resultVisible)
        contentPreviewText.visibility = if (resultVisible) View.VISIBLE else View.GONE
        workspaceAdjustAction.visibility = View.VISIBLE
        workspaceAdjustAction.isEnabled = prepared != null
        workspaceAction.isEnabled = prepared != null
        workspaceAction.text = getString(R.string.cell_edit_tap)
        workspaceAction.setOnClickListener { showCellEditor() }
        workspacePreview.setDocument(recoveryState.inspectorMatrix ?: recoveryState.workingMatrix, prepared?.bitmap)
    }

    private fun updateResumeLabel() {
        resumeButton.text = if (prepared != null || recoveryState.workingMatrix != null) {
            ui("現在の作業に戻る", "Return to current work")
        } else getString(R.string.resume_work)
    }

    private fun resumeLatestWork() {
        if (prepared != null || recoveryState.workingMatrix != null) {
            openWorkspace()
            return
        }
        resumeButton.isEnabled = false
        val generation = workGeneration.get()
        ioExecutor.execute {
            val recent = runCatching { sessionStore.listRecent().firstOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                resumeButton.isEnabled = true
                if (generation != workGeneration.get()) return@runOnUiThread
                val entry = recent.getOrNull()
                if (entry != null) restoreSessionById(entry.id)
                else Toast.makeText(this, if (recent.isFailure) {
                    ui("保存した作業を読み込めませんでした。作業一覧から確認してください。", "Could not load saved work. Check the saved work list.")
                } else ui("保存した作業はありません。カメラまたは画像の選択から始めてください。", "No saved work. Start with the camera or choose an image."), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearDiagnosticDetails() {
        if (!::diagnosticToggle.isInitialized || !::diagnosticView.isInitialized) return
        diagnosticReport = null
        diagnosticResults = null
        diagnosticView.clear()
        diagnosticToggle.visibility = View.GONE
        refreshDiagnosticDetailsVisibility()
    }

    private fun setDiagnosticReport(report: RecoveryDiagnosticReport) {
        // Candidate selection may change the sampled input without starting a new run.
        val results = recoveryState.recoveries.takeIf { it.isNotEmpty() }
            ?: recoveryState.failure ?: recoveryState.autoResult
        diagnosticView.showReport(report, resetExpanded = diagnosticResults !== results)
        diagnosticResults = results
        diagnosticReport = report
        diagnosticToggle.visibility = View.VISIBLE
        refreshDiagnosticDetailsVisibility()
    }

    private fun refreshDiagnosticDetailsVisibility() {
        if (!::diagnosticToggle.isInitialized || !::diagnosticView.isInitialized) return
        diagnosticToggle.text = getString(
            if (diagnosticDetailsExpanded) R.string.hide_diagnostic_details
            else R.string.show_diagnostic_details,
        )
        diagnosticToggle.minHeight = dp(48)
        diagnosticToggle.minimumHeight = dp(48)
        androidx.core.view.ViewCompat.setStateDescription(diagnosticToggle,
            getString(if (diagnosticDetailsExpanded) R.string.diagnostic_expanded else R.string.diagnostic_collapsed))
        diagnosticView.visibility = if (diagnosticReport != null && diagnosticDetailsExpanded) View.VISIBLE else View.GONE
        refreshTailAnalysisVisibility()
    }

    private fun refreshTailAnalysisVisibility() {
        if (!::resultTailAnalysisButton.isInitialized) return
        val failure = recoveryState.failure
        val structureFailure = recoveryState.selectedRecovery == null &&
            failure?.stage == io.github.earthdiver.qrrepair.core.RecoveryFailureStage.PAYLOAD_VALIDATION &&
            failure.reason == null && !hasInconsistentInputFailure()
        resultTailAnalysisButton.visibility = if (structureFailure && diagnosticToggle.visibility == View.VISIBLE)
            View.VISIBLE else View.GONE
    }

    private fun showAppMenu() {
        val labels = arrayOf(
            getString(if (modernStyle) R.string.saved_work else R.string.recent_work),
            getString(R.string.version_info),
            getString(R.string.licenses_privacy),
            getString(R.string.language),
            getString(R.string.appearance),
        )
        AlertDialog.Builder(this).setTitle(R.string.menu).setItems(labels) { _, which ->
            when (which) {
                0 -> showRecentSessions()
                1 -> showVersionInfo()
                2 -> showLicensesAndPrivacy()
                3 -> showLanguageDialog()
                4 -> showStyleDialog()
            }
        }.show()
    }

    private fun showStyleDialog() {
        val styles = arrayOf(AppStyle.CLASSIC, AppStyle.MODERN)
        val labels = styles.map { getString(it.label) }.toTypedArray()
        val current = AppStylePreferences.selected(this)
        var selected = current
        AlertDialog.Builder(this)
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(labels, styles.indexOf(current)) { _, which -> selected = styles[which] }
            .setPositiveButton(R.string.style_save) { _, _ ->
                AppStylePreferences.save(this, selected)
                AlertDialog.Builder(this)
                    .setMessage(R.string.style_next_launch)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(getString(R.string.language_english), getString(R.string.language_japanese))
        val current = AppLanguageManager.current
        AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(languages, if (current == AppLanguage.ENGLISH) 0 else 1) { dialog, which ->
                val selected = if (which == 0) AppLanguage.ENGLISH else AppLanguage.JAPANESE
                dialog.dismiss()
                if (selected != current) {
                    applyLanguage(selected)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun applyLanguage(language: AppLanguage) {
        // These texts may contain a decoded payload that is not otherwise retained by the main
        // view (for example, an intact QR decoded immediately after import).
        val wasWorkspaceVisible = modernStyle && workspacePanel.visibility == View.VISIBLE
        val previousStatus = statusText.text
        val previousGuide = guideText.text
        val previousContent = contentPreviewText.text
        closePreprocessEditor()
        AppLanguageManager.applyTo(this, language)
        setContentView(buildUi())

        statusText.text = previousStatus
        guideText.text = previousGuide
        contentPreviewText.text = previousContent
        if (recoveryState.geometry != null && recoveryState.workingMatrix != null) {
            updateSummary()
            updateGuideText()
        } else if (recoveryState.selectedRecovery != null) {
            updateContentPreview()
        }
        if (modernStyle) {
            if (wasWorkspaceVisible) showWorkspace() else showStartMenu()
        }
    }

    private fun showStartMenu() {
        if (!modernStyle) {
            showClassicStartMenu()
            return
        }
        modernHeader.visibility = View.VISIBLE
        workspaceNavigation.view.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        workspacePanel.visibility = View.GONE
        homeActions.visibility = View.VISIBLE
        workspaceActions.visibility = View.GONE
        workspaceAction.visibility = View.GONE
        updateResumeLabel()
    }

    private fun showClassicStartMenu() {
        lateinit var dialog: AlertDialog
        val choiceStyle = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val choiceBackground = choiceStyle.getResourceId(0, 0)
        choiceStyle.recycle()
        fun choice(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            if (choiceBackground != 0) setBackgroundResource(choiceBackground)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                action()
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.start_message)
                textSize = 14f
                setPadding(dp(24), 0, dp(24), dp(8))
            })
            addView(choice(getString(R.string.take_photo), ::openCamera))
            addView(choice(getString(R.string.import_image), ::chooseImage))
            addView(choice(getString(R.string.import_text_matrix_long), ::chooseTextMatrix))
            addView(choice(getString(R.string.recent_work), ::showRecentSessions))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.start_title)
            .setView(panel)
            .create()
        dialog.show()
    }

    private fun showPostTerminatorAnalysis() {
        if (resultTailAnalysisButton.visibility != View.VISIBLE) return
        val candidate = recoveryState.selectedRecovery
        val failure = recoveryState.failure
        val matrix = recoveryState.workingMatrix?.copy() ?: return
        val version = candidate?.version ?: (matrix.dimension - 17) / 4
        val ecc = candidate?.ecLevel ?: failure?.ecLevel ?: return
        val mask = candidate?.dataMask ?: failure?.dataMask ?: return
        val diagnostic = if (candidate != null) candidate.conventionalRsDiagnostic else failure?.conventionalRsDiagnostic
        val token = workGeneration.get()
        resultTailAnalysisButton.isEnabled = false
        ioExecutor.execute {
            val attempt = runCatching {
                val extraction = candidate?.extraction ?: CodewordExtractor.extract(matrix, version, mask)
                io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.forAttempt(extraction, version, ecc, diagnostic, candidate)
            }
            runOnUiThread {
                resultTailAnalysisButton.isEnabled = true
                if (isFinishing || isDestroyed || token != workGeneration.get() ||
                    recoveryState.selectedRecovery !== candidate || recoveryState.failure !== failure) return@runOnUiThread
                attempt.onSuccess { showPostTerminatorDialog(this, it, version, ecc.toString(), mask) }
                    .onFailure { Toast.makeText(this, ui("終端後データを解析できませんでした。", "Could not analyze post-terminator data."), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun resultDocument(): RecoveryResultDocument? {
        return RecoveryResultDocument(
            before = recoveryState.sourceMatrix ?: return null,
            after = recoveryState.inspectorMatrix ?: recoveryState.workingMatrix ?: return null,
            geometry = recoveryState.geometry,
            traces = recoveryState.traces,
            candidate = recoveryState.selectedRecovery,
            candidateIndex = recoveryState.recoveryIndex,
            failureMask = recoveryState.failure?.dataMask,
        )
    }

    private fun showZoomableResult() {
        if (recoveryState.sourceMatrix == null) {
            Toast.makeText(this, ui("表示できる結果がありません。", "No result is available."), Toast.LENGTH_SHORT).show()
            return
        }
        RecoveryResultDialog(
            activity = this,
            modernStyle = modernStyle,
            initialDocument = resultDocument() ?: return,
            candidates = displayedDecodedRecoveryEntries(),
            selectCandidate = { index ->
                selectRecoveryCandidate(index, recordSelection = true)
                resultDocument()
            },
            cellDescription = ::resultCellDetailText,
            cellTechnicalDescription = ::resultCellTechnicalText,
            codewordDescription = ::codewordDetailText,
        ).show()
    }

    private fun selectRecoveryCandidate(index: Int, recordSelection: Boolean) {
        val candidate = recoveryState.recoveries.getOrNull(index) ?: return
        recoveryState.selectRecovery(index)
        updateSelectedSamplingSettings()
        refreshInspector()
        updateSummary()
        if (recordSelection) recordHistory(
            HistoryEventKind.CANDIDATE,
            ui("候補${displayedDecodedRecoveryEntries().indexOfFirst { it.first == index } + 1}を選択", "Selected candidate ${displayedDecodedRecoveryEntries().indexOfFirst { it.first == index } + 1}"),
            "score=${candidate.score}, ECC=${candidate.ecLevel}, mask=${candidate.dataMask}",
        )
    }

    private fun decodedRecoveryEntries(): List<Pair<Int, RecoveryCandidate>> =
        recoveryState.selectableEntries

    private fun displayedDecodedRecoveryEntries(): List<Pair<Int, RecoveryCandidate>> =
        decodedRecoveryEntries().take(MAX_DISPLAYED_RECOVERY_CANDIDATES)

    private fun candidateSelectionLabel(total: Int): String =
        if (recoveryState.candidatesOmitted) {
            ui("優先度の高い${total}候補を表示（ほかの候補は省略）", "Showing $total highest-ranked candidates (others omitted)")
        } else if (total > MAX_DISPLAYED_RECOVERY_CANDIDATES) {
            getString(R.string.select_result_truncated, MAX_DISPLAYED_RECOVERY_CANDIDATES, total)
        } else {
            getString(R.string.select_result_limited, MAX_DISPLAYED_RECOVERY_CANDIDATES)
        }

    private fun refreshMainResultSelector() {
        if (!::mainResultSelector.isInitialized) return
        val allResults = decodedRecoveryEntries()
        val results = allResults.take(MAX_DISPLAYED_RECOVERY_CANDIDATES)
        if (results.size <= 1) comparisonCandidate = null
        for (selector in listOf(upperResultSelector, mainResultSelector)) {
            selector.removeAllViews()
            selector.visibility = if (results.size > 1) View.VISIBLE else View.GONE
            if (results.size <= 1) continue
            selector.addView(TextView(this).apply {
                text = candidateSelectionLabel(allResults.size)
                textSize = 12f
                setPadding(dp(8), dp(4), dp(8), 0)
            })
            selector.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(resultRadioGroup(results) { renderMainResultSelection() })
            })
            selector.addView(button(ui("候補の内容一覧", "Candidate contents")) {
                CandidateContentsDialog(this, results, recoveryState.recoveryIndex,
                    candidateSelectionLabel(allResults.size)) { index ->
                    selectRecoveryCandidate(index, recordSelection = true)
                }.show()
            })
            if (selector === upperResultSelector) {
                val current = results.firstOrNull { it.first == recoveryState.recoveryIndex } ?: results.first()
                val others = results.filter { it.first != current.first }
                val partner = others.firstOrNull { it.second === comparisonCandidate } ?: others.first()
                comparisonCandidate = partner.second
                if (others.size > 1) selector.addView(button(ui(
                    "比較相手：候補${results.indexOf(partner) + 1}", "Compare with: candidate ${results.indexOf(partner) + 1}")) {
                    AlertDialog.Builder(this)
                        .setTitle(ui("比較相手を選択", "Choose a candidate to compare"))
                        .setSingleChoiceItems(others.map { ui("候補${results.indexOf(it) + 1}", "Candidate ${results.indexOf(it) + 1}") }.toTypedArray(),
                            others.indexOf(partner)) { dialog, which ->
                            comparisonCandidate = others[which].second
                            refreshMainResultSelector()
                            dialog.dismiss()
                        }.setNegativeButton(R.string.cancel, null).show()
                })
                selector.addView(TextView(this).apply {
                    text = candidatePairComparisonText(current.second, partner.second,
                        results.indexOf(current) + 1, results.indexOf(partner) + 1)
                    textSize = 14f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
            }
        }
    }

    private fun renderMainResultSelection() {
        refreshMainResultSelector()
    }

    private fun resultRadioGroup(
        results: List<Pair<Int, RecoveryCandidate>> = displayedDecodedRecoveryEntries(),
        afterSelection: () -> Unit = {},
    ) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        results.forEachIndexed { resultIndex, (candidateIndex, _) ->
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = getString(R.string.result_tab, resultIndex + 1)
                isChecked = candidateIndex == recoveryState.recoveryIndex
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener {
                    if (candidateIndex != recoveryState.recoveryIndex) {
                        selectRecoveryCandidate(candidateIndex, recordSelection = true)
                        afterSelection()
                    }
                }
            })
        }
    }

    private fun resultCellDetailText(x: Int, y: Int, matrix: QrMatrix, maskToRemove: Int? = null): String {
        fun value(v: ModuleValue) = when (v) {
            ModuleValue.ZERO -> ui("白（0）", "White (0)")
            ModuleValue.ONE -> ui("黒（1）", "Black (1)")
            ModuleValue.ERASED -> ui("不明（?）", "Unknown (?)")
        }
        val trace = recoveryState.traces[Coord(x, y)]
        val cell = matrix[x, y]
        val before = trace?.originalValue ?: recoveryState.sourceMatrix?.get(x, y)?.value ?: cell.value
        val version = (matrix.dimension - 17) / 4
        val displayedBefore = displayedModuleValue(before, version, x, y, maskToRemove)
        val displayedAfter = displayedModuleValue(cell.value, version, x, y, maskToRemove)
        val reason = when {
            cell.source == ModuleSource.USER_UNLOCKED -> ui("固定指定した値です。修正可の設定により復元時の訂正を許可しています。", "This fixed user value allows correction under the current option.")
            cell.source == ModuleSource.USER_SOFT -> ui("仮指定した値です。復元時に訂正されることがあります。", "This tentative user value may be corrected during recovery.")
            cell.source == ModuleSource.USER -> ui("手動で指定した値です。", "This is a manually entered value.")
            before == cell.value -> ui("この候補では値を変更していません。", "The value is unchanged in this candidate.")
            trace?.bitConstraintSolved == true && trace.primaryReason in setOf(
                io.github.earthdiver.qrrepair.core.RepairReasonKind.RS_ERASURE_RECOVERY,
                io.github.earthdiver.qrrepair.core.RepairReasonKind.RS_ERROR_CORRECTION) ->
                ui("部分bit制約で補いました。", "Partial-bit constraints filled this value.")
            trace == null -> ui("この候補の値を表示しています。", "Showing the value in this candidate.")
            trace.primaryReason == io.github.earthdiver.qrrepair.core.RepairReasonKind.RS_ERASURE_RECOVERY ->
                ui("RS誤り訂正で不明な値を補いました。", "RS error correction filled the unknown value.")
            trace.primaryReason == io.github.earthdiver.qrrepair.core.RepairReasonKind.RS_ERROR_CORRECTION ->
                ui("RS誤り訂正で白黒を変更しました。", "RS error correction changed the value.")
            else -> trace.titleJa()
        }
        return buildString {
            append(ui("セル ($x,$y)（座標は0から）", "Cell ($x,$y) (zero-based coordinates)"))
            append('\n').append(if (displayedBefore == displayedAfter) ui("${value(displayedAfter)}のまま", "Unchanged: ${value(displayedAfter)}")
                else "${value(displayedBefore)} → ${value(displayedAfter)}")
            if (maskToRemove != null && !QrFunctionMask.isFunction(version, x, y))
                append(ui("\nマスク解除後の表示値です。", "\nValues shown after removing the QR mask."))
            FormatCellGroup.at(matrix.dimension, x, y)?.let { append('\n').append(it.cellText()) }
            append('\n').append(reason)
            val source = trace?.originalSource ?: recoveryState.sourceMatrix?.get(x, y)?.source
            if (source == ModuleSource.IMAGE) append(ui("\n入力：画像から判別した結果", "\nInput: image classification result"))
            else if (source == ModuleSource.USER_UNLOCKED) append(ui("\n入力：手動で指定した値（固定あり、訂正可）", "\nInput: manually entered value (fixed, correction allowed)"))
            else if (source == ModuleSource.USER_SOFT) append(ui("\n入力：手動で仮指定した値（固定なし、訂正可）", "\nInput: manually entered tentative value (not fixed, correction allowed)"))
            else if (source == ModuleSource.USER) append(ui("\n入力：手動で指定した値（固定あり、訂正不可）", "\nInput: manually entered value (fixed, correction not allowed)"))
            recoveryState.confidenceMap[Coord(x, y)]?.let { c ->
                if (c.comparedCandidates != null) append(ui(
                    "\n比較した${c.comparedCandidates}候補中${c.agreeingCandidates}候補で、このセルの白黒が一致しています。",
                    "\nThis cell agrees in ${c.agreeingCandidates} of ${c.comparedCandidates} compared candidates."))
            }
            if (cell.value == ModuleValue.ERASED) append(ui("\nこのセルの白黒はまだ決まっていません。", "\nThis cell remains unresolved."))
        }
    }

    private fun resultCellTechnicalText(x: Int, y: Int, matrix: QrMatrix): String {
        val coord = Coord(x, y)
        val trace = recoveryState.traces[coord]
        val confidence = recoveryState.confidenceMap[coord]
        val formatGroup = FormatCellGroup.at(matrix.dimension, x, y)
        if (formatGroup != null) {
            return io.github.earthdiver.qrrepair.core.formatCellDescription(x, y,
                trace?.originalValue ?: recoveryState.sourceMatrix?.get(x, y)?.value ?: matrix[x, y].value,
                matrix[x, y].value, recoveryState.selectedRecovery, matrix[x, y].source == ModuleSource.USER, includeBasic = false) + "\n\n" +
                formatGroupDetailText(formatGroup, recoveryState.sourceMatrix ?: matrix, matrix)
        }
        if (trace != null) return buildString {
            append(ui("計算の補足", "Calculation details"))
            append(trace.explanationJa(includeBasic = false))
            confidence?.let { append(ui("\n候補の評価（確率ではありません）: ${it.labelJa} (${"%.2f".format(it.score)}) — ${it.detailJa}", "\nCandidate assessment (not a probability): ${it.labelEn} (${"%.2f".format(it.score)}) — ${it.detailEn}")) }
        }
        return ui("このセルの追加の計算記録はありません。", "No additional calculation record is available for this cell.")
    }

    private fun codewordDetailText(codewordIndex: Int, bitIndex: Int): String {
        val candidate = recoveryState.selectedRecovery ?: return ""
        val partial = candidate.extraction.codewords.getOrNull(codewordIndex) ?: return ""
        val info = candidate.repairInfo.getOrNull(codewordIndex)
        return codewordCellText(codewordIndex, bitIndex, partial.value, partial.unknownBits,
            candidate.correctedInterleaved.getOrNull(codewordIndex), info?.blockIndex, info?.localIndex,
            info?.rsErrata == true) + if (info?.partialEnumerated == true)
                ui("\nこのコード語はbit探索の対象です。", "\nThis codeword was included in bit search.") else ""
    }

    private fun showCodewordHex() {
        val attemptedCandidate = recoveryState.selectedRecovery
        val savedData = if (attemptedCandidate == null && recoveryState.failure != null && recoveryState.workingMatrix != null) {
            val matrix = recoveryState.workingMatrix!!
            val failure = recoveryState.failure!!
            val version = (matrix.dimension - 17) / 4
            runCatching {
                SavedRawData(matrix, CodewordExtractor.extract(matrix, version, failure.dataMask), version, failure.dataMask, failure.ecLevel)
            }.getOrNull()
        } else null
        val preRecoveryData = if (attemptedCandidate == null && recoveryState.failure == null) savedPreRecoveryData() else null
        val fallbackMatrix = recoveryState.workingMatrix?.takeIf { attemptedCandidate != null } ?: savedData?.matrix
            ?: preRecoveryData?.matrix
            ?: run { Toast.makeText(this, ui("表示できるデータがありません。", "No data is available."), Toast.LENGTH_SHORT).show(); return }
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val displayState = RecoveryDataDisplayState(repairedStage = recoveryState.selectedRecovery?.isSelectableRecovery == true)
        fun render() {
            if (preRecoveryData != null) {
                text.text = if (displayState.repairedStage) {
                    ui("制約・RS修復後", "After constraints/RS") + "\n\n" +
                        ui("データ検証済みの修復後候補はありません。修復前を選択してください。", "No payload-validated repaired candidate is available. Select Before.")
                } else {
                    ui("制約・RS修復前", "Before constraints/RS") + " / " + buildPreRecoveryDataContent(
                        preRecoveryData, preprocessSettings.formatConstraint, displayState.unmasked,
                        displayState.partialInformation, preRecoveryDataStrings(isJapaneseUi()),
                    ).text
                }
                return
            }
            val candidate = recoveryState.selectedRecovery
            val matrix = recoveryState.workingMatrix ?: fallbackMatrix
            val validatedCandidate = candidate?.takeIf { it.isSelectableRecovery }
            val version = candidate?.version ?: savedData!!.version
            val mask = candidate?.dataMask ?: savedData!!.mask
            val extraction = candidate?.extraction ?: savedData!!.extraction
            text.text = buildString {
                if (candidate != null && validatedCandidate == null) {
                    append(ui("復号失敗・最良試行（RS整合・データ未成立）\n", "Decode failed; best attempt (RS-consistent; payload invalid)\n"))
                } else if (candidate == null && recoveryState.failure != null) {
                    append(ui("復号失敗・最良試行\n", "Decode failed; best attempt\n"))
                }
                append(if (displayState.repairedStage) ui("制約・RS修復後", "After constraints/RS") else ui("制約・RS修復前", "Before constraints/RS"))
                    .append(" / ").append(if (displayState.unmasked) ui("マスク解除後", "After unmasking") else ui("マスク解除前", "Before unmasking"))
                    .append(ui(" / V", " / Version ")).append(version)
                (candidate?.ecLevel ?: recoveryState.failure?.ecLevel)?.let { append(" / ECC ").append(it) }
                append(" / mask ").append(mask).append(ui("\n? = 不明bit", "\n? = unknown bit"))
                if (!displayState.partialInformation) {
                    append(ui("\n\nHEX\n", "\n\nHex\n"))
                    append(when {
                        displayState.repairedStage && validatedCandidate == null -> ui("データ検証済みの修復後候補はありません。元データは「修復前」で確認できます。", "No payload-validated repaired candidate is available. Select Before to inspect the original data.")
                        displayState.repairedStage && displayState.unmasked -> CodewordHex.corrected(validatedCandidate!!.correctedInterleaved)
                        displayState.repairedStage -> CodewordHex.correctedMasked(validatedCandidate!!.correctedInterleaved, extraction, mask)
                        displayState.unmasked -> CodewordHex.unmasked(extraction)
                        else -> CodewordHex.masked(matrix, extraction)
                    })
                    append("\n\nBinary\n")
                    append(when {
                        displayState.repairedStage && validatedCandidate == null -> "-"
                        displayState.repairedStage && displayState.unmasked -> CodewordHex.correctedBinary(validatedCandidate!!.correctedInterleaved)
                        displayState.repairedStage -> CodewordHex.correctedMaskedBinary(validatedCandidate!!.correctedInterleaved, extraction, mask)
                        displayState.unmasked -> CodewordHex.binary(extraction)
                        else -> CodewordHex.maskedBinary(matrix, extraction)
                    })
                    append(ui("\n\n内容\n", "\n\nContent\n"))
                    append(
                        if (!displayState.unmasked) {
                            ui("マスク解除後に解釈できます。", "Content can be interpreted after unmasking.")
                        } else if (displayState.repairedStage && validatedCandidate != null) {
                            CodewordHex.interpretedRecovered(validatedCandidate.dataBytes, version, validatedCandidate.text)
                        } else if (displayState.repairedStage) {
                            ui("検証済みデータはありません。", "No payload-validated content is available.")
                        } else {
                            ui("検証済みデータはありません。部分情報表示へ切り替えてください。", "No payload-validated content is available. Switch to Partial information.")
                        },
                    )
                } else {
                    append(ui("\n\n部分情報\n", "\n\nPartial information\n"))
                    append(when {
                        !displayState.unmasked -> ui("マスク解除後に解釈できます。", "Partial information is available after unmasking.")
                        displayState.repairedStage && validatedCandidate != null -> ui("この候補はデータ検証済みです。内容はHEX / Binary表示で確認できます。", "This candidate is payload-validated. Its content is available in the Hex / Binary view.")
                        displayState.repairedStage -> ui("データ検証済みの修復後候補はありません。修復前を選択してください。", "No payload-validated repaired candidate is available. Select Before.")
                        else -> {
                            val ecLevel = candidate?.ecLevel ?: savedData?.ecLevel
                            if (ecLevel == null) {
                                ui("誤り訂正レベルが未確定のため、データコード語を安全に並べ替えられません。", "The ECC level is unknown, so data codewords cannot be safely reordered.")
                            } else runCatching {
                                PartialPayloadAnalyzer.fromInterleaved(extraction, version, ecLevel)
                                    .displayText(isJapaneseUi())
                            }.getOrElse {
                                ui("部分情報を抽出できませんでした。", "Could not extract partial information.")
                            }
                        }
                    })
                }
            }
        }
        val displayedResults = displayedDecodedRecoveryEntries()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (displayedResults.size > 1) addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(resultRadioGroup(displayedResults, afterSelection = ::render))
            })
            addView(compactRow(
                android.widget.ToggleButton(this@MainActivity).apply { textOff = ui("制約・RS修復前", "Before constraints/RS"); textOn = ui("制約・RS修復後", "After constraints/RS"); isChecked = displayState.repairedStage; setOnCheckedChangeListener { _, checked -> displayState.repairedStage = checked; render() } },
                android.widget.ToggleButton(this@MainActivity).apply { textOff = getString(R.string.mask_before); textOn = getString(R.string.mask_after); isChecked = displayState.unmasked; setOnCheckedChangeListener { _, checked -> displayState.unmasked = checked; render() } },
                android.widget.ToggleButton(this@MainActivity).apply { textOff = getString(R.string.raw_data_view); textOn = getString(R.string.partial_info_view); isChecked = displayState.partialInformation; setOnCheckedChangeListener { _, checked -> displayState.partialInformation = checked; render() } },
            ))
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(text) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        render()
        showDataDialog(this, content)
    }

    private data class SavedRawData(
        val matrix: QrMatrix,
        val extraction: io.github.earthdiver.qrrepair.core.CodewordExtraction,
        val version: Int,
        val mask: Int,
        val ecLevel: com.google.zxing.qrcode.decoder.ErrorCorrectionLevel?,
    )

    private fun savedPreRecoveryData(): PreRecoveryDataInput? {
        val prepared = preparedFor(preprocessSettings) ?: return null
        val quad = preprocessSettings.manualQuad ?: return null
        val version = preprocessSettings.version
        val dimension = 17 + 4 * version
        val pitch = ((quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) /
            (2.0 * (dimension - 1))).coerceAtLeast(.25)
        val geometry = runCatching {
            GridGeometry(
                version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y,
                pitch, 0.0, quad, io.github.earthdiver.qrrepair.core.GeometryModel.MANUAL_PROJECTIVE,
            ).reanchored()
        }.getOrNull() ?: return null
        val profile = preprocessSettings.variants().first()
        val matrix = PreRecoveryPreviewSampler().sample(prepared.luma, geometry, profile,
            preprocessSettings.logicalGridAssigned).first
        preprocessSettings.preRecoveryEdits.forEach { (coord, value) ->
            if (coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension) {
                matrix.set(coord.x, coord.y, value, 1f, preprocessSettings.sourceForPreRecoveryEdit(value, coord))
                matrix.setPrioritySearch(coord.x, coord.y, coord in preprocessSettings.preRecoveryPrioritySearch)
            }
        }
        return PreRecoveryDataInput(matrix, version)
    }

    private val dialogViews by lazy { DialogViewFactory(this) }

    private fun button(label: String, onClick: () -> Unit): Button = dialogViews.button(label, onClick)

    private fun compactRow(vararg children: View): LinearLayout = dialogViews.compactRow(*children)

    private fun scrollRow(vararg children: View): HorizontalScrollView = dialogViews.scrollRow(*children)

    private fun currentSnapshot(): RecoverySnapshot = RecoverySnapshot(
        geometry = recoveryState.geometry,
        sourceMatrix = recoveryState.sourceMatrix?.copy(),
        workingMatrix = recoveryState.workingMatrix?.copy(),
        recoveryIndex = recoveryState.recoveryIndex,
        preprocessSettings = preprocessSettings,
        selectedQr = recoveryState.selectedRecovery?.takeIf { it.isSelectableRecovery }?.let(RecoverySelectionIdentity::from),
        samplingInverted = recoveryState.samplingProfile?.inverted,
        oneTimeSingleSegment = recoveryState.oneTimeSingleSegment,
    )

    private fun preparedFor(settings: PreprocessSettings): BitmapUtils.Prepared? {
        val source = prepared ?: return null
        return BitmapUtils.forSettings(source, settings)
    }

    private fun recordHistory(kind: HistoryEventKind, label: String, detail: String = "") {
        if (historyRestoreToken != null || recoveryState.workingMatrix == null) return
        sessionHistory.record(
            RecoveryHistoryEntry(
                kind = kind,
                label = label,
                detail = detail,
                snapshot = currentSnapshot(),
            ),
        )
        persistSessionAsync()
    }

    private fun recordPreprocessHistory(settings: PreprocessSettings, label: String, detail: String = "") {
        if (historyRestoreToken != null) return
        sessionHistory.record(
            RecoveryHistoryEntry(
                kind = HistoryEventKind.SETTINGS,
                label = label,
                detail = detail,
                snapshot = RecoverySnapshot(null, null, null, -1, settings),
            ),
        )
    }

    private fun recordRecoveryAttempt(label: String, detail: String) {
        sessionHistory.record(
            RecoveryHistoryEntry(
                kind = HistoryEventKind.AUTO_RECOVERY,
                label = label,
                detail = detail,
                snapshot = RecoverySnapshot(null, null, null, -1, preprocessSettings,
                    oneTimeSingleSegment = recoveryState.oneTimeSingleSegment),
            ),
        )
        persistSessionAsync()
    }

    private fun persistSessionAsync() {
        val id = sessionId ?: return
        if (sessionHistory.entries.isEmpty()) return
        val removed = RecoverySessionCodec.trimHistoryToPayloadLimit(sessionHistory, id,
            maxRetainedBytes = RecoveryMemoryPolicy.current().historyBytes)
        if (removed > 0) Toast.makeText(this, ui(
            "履歴の容量上限により古い履歴を${removed}件整理しました。現在の作業は保持しています。",
            "Removed $removed older history entries at the size limit. Current work is preserved."), Toast.LENGTH_SHORT).show()
        sessionSaves.enqueue(id, sessionHistory.entries.toList(), sessionHistory.cursor)
    }

    private fun reportPersistenceFailure(prefix: String, error: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastPersistenceWarningAtMs < 5_000L) return
        lastPersistenceWarningAtMs = now
        runOnUiThread {
            Toast.makeText(
                this,
                "$prefix: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showHistoryPanel() {
        if (sessionHistory.entries.isEmpty()) {
            Toast.makeText(this, ui("履歴はまだありません。", "No history is available yet."), Toast.LENGTH_SHORT).show()
            return
        }
        lateinit var dialog: AlertDialog
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = ui("再開する履歴をタップしてください。閉じるだけでは作業を再開しません。", "Tap a history entry to resume it. Closing this window does not resume work.")
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(10))
            })
        }
        sessionHistory.entries.indices.reversed().forEach { index ->
            val entry = sessionHistory.entries[index]
            val active = index == sessionHistory.cursor
            val changedCells = sessionHistory.entries.getOrNull(index - 1)?.let { previous ->
                historyCellChangeCount(previous.snapshot, entry.snapshot)
            }
            container.addView(button(buildString {
                if (active) append("● ") else append("○ ")
                append(DateFormat.format("HH:mm:ss", Date(entry.timestampMs))).append("  ")
                append("[").append(historyKindLabel(entry.kind)).append("] ")
                append(entry.label)
                if (entry.detail.isNotBlank()) append(" — ").append(entry.detail.take(80))
                if (changedCells != null) append(ui(" — Δ ${changedCells}セル", " — Δ $changedCells cells"))
            }) {
                dialog.dismiss()
                restoreHistoryIndex(index)
            })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (modernStyle) getString(R.string.operation_history) + " ${sessionHistory.cursor + 1}/${sessionHistory.entries.size}" else ui("復元履歴 ${sessionHistory.cursor + 1}/${sessionHistory.entries.size}", "Restore history ${sessionHistory.cursor + 1}/${sessionHistory.entries.size}"))
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun historyKindLabel(kind: HistoryEventKind): String = when (kind) {
        HistoryEventKind.IMPORT -> ui("取込", "Import")
        HistoryEventKind.AUTO_RECOVERY -> ui("自動", "Auto")
        HistoryEventKind.USER_EDIT -> ui("編集", "Edit")
        HistoryEventKind.APPLY_RECOVERY -> ui("適用", "Apply")
        HistoryEventKind.GEOMETRY -> ui("幾何", "Geometry")
        HistoryEventKind.CANDIDATE -> ui("結果", "Result")
        HistoryEventKind.SYSTEM -> ui("状態", "State")
        HistoryEventKind.SETTINGS -> ui("設定", "Settings")
    }

    private fun restoreHistoryIndex(index: Int) {
        val entry = sessionHistory.jump(index) ?: return
        restoreHistoryEntry(entry, ui("履歴: ${entry.label}", "History: ${entry.label}"))
    }

    private fun restoreHistoryEntry(entry: RecoveryHistoryEntry, prefix: String) {
        restoreSnapshot(entry, prefix) {
            openRestoredHistoryEntry()
        }
        persistSessionAsync()
    }

    private fun openRestoredHistoryEntry() {
        when {
            immediateResultShown -> showWorkspace()
            recoveryState.workingMatrix != null -> showZoomableResult()
            prepared != null -> showPreprocessDialog()
            else -> Toast.makeText(this, ui("この履歴点には表示できる元画像・QR行列がありません。", "This history entry has no viewable source image or QR matrix."), Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSnapshot(
        entry: RecoveryHistoryEntry,
        prefix: String,
        onRestored: (() -> Unit)? = null,
    ) {
        closePreprocessEditor()
        val cachedResult = pendingImmediateResult ?: immediateResult
        pendingImmediateResult = null
        immediateResultShown = false
        val snapshot = entry.snapshot
        savedCellEditState = null
        preprocessSettings = snapshot.preprocessSettings ?: PreprocessSettings()
        val matrix = snapshot.workingMatrix?.copy()
        val geometry = snapshot.geometry
        val p = preparedFor(preprocessSettings)
        analysisPrepared = p
        val restoredManual = matrix?.let(FormatEditPolicy::manualValues).orEmpty() + preprocessSettings.fixedPreRecoveryEdits
        FormatEditPolicy.conflict(preprocessSettings.version, preprocessSettings.formatConstraint, restoredManual)?.let {
            workGeneration.incrementAndGet()
            historyRestoreToken = null
            if (matrix != null && geometry != null) {
                recoveryState.restoreAttempt(geometry, snapshot.sourceMatrix?.copy() ?: matrix.copy(), matrix)
            } else recoveryState.clear()
            refreshMainResultSelector()
            contentPreviewText.text = getString(R.string.no_content_preview)
            clearDiagnosticDetails()
            statusText.text = ui("作業は読み込みました。形式情報の指定を修正してから修復してください。", "Work loaded. Correct the format selections before repairing.")
            guideText.text = it.message(changeRejected = false)
            onRestored?.invoke()
            showFormatEditError(it, changeRejected = false)
            return
        }
        if (matrix == null || geometry == null) {
            val token = workGeneration.incrementAndGet()
            historyRestoreToken = null
            recoveryState.clear()
            contentPreviewText.text = getString(R.string.no_content_preview)
            clearDiagnosticDetails()
            refreshMainResultSelector()
            if (p != null) {
                guideText.text = ui("案内: 元画像の状態です。「修復する」から設定を確認して修復してください。", "Guide: This is the source image. Review settings under “Repair” to continue.")
                statusText.text = ui("$prefix。元画像へ戻りました。", "$prefix. Returned to the source image.")
            } else {
                guideText.text = ui("案内: 元画像を検証できないため、この履歴点の画像設定は利用できません。保存済み行列のある履歴点を選択してください。", "Guide: The source image could not be verified. Select a history entry that contains a saved matrix.")
                statusText.text = ui("$prefix。履歴は読み込みましたが、元画像は利用できません。", "$prefix. History was loaded, but the source image is unavailable.")
            }
            // Only an untouched import represents the image that was scanned immediately.
            // Saved settings/edits must remain repair work, even if the source still decodes.
            if (p != null && entry.kind == HistoryEventKind.IMPORT && snapshot.sourceMatrix == null &&
                preprocessSettings.preRecoveryEdits.isEmpty()) {
                if (cachedResult != null && cachedResult.sessionId == sessionId &&
                    cachedResult.entryId == entry.id && cachedResult.settings == preprocessSettings) {
                    renderImmediateQrResult(cachedResult, showNotice = false)
                    onRestored?.invoke()
                } else {
                    historyRestoreToken = token
                    executor.execute {
                        val result = decodeStandardQr(p.bitmap)
                        runOnUiThread {
                            if (isFinishing || isDestroyed || token != workGeneration.get()) return@runOnUiThread
                            historyRestoreToken = null
                            if (result != null) renderImmediateQrResult(immediateQrResult(result), showNotice = false)
                            onRestored?.invoke()
                        }
                    }
                }
                return
            }
            onRestored?.invoke()
            return
        }
        val source = snapshot.sourceMatrix?.copy() ?: matrix.copy()
        recoveryState.oneTimeSingleSegment = snapshot.oneTimeSingleSegment
        val requestSettings = preprocessSettings.recoverySnapshot().let {
            if (snapshot.oneTimeSingleSegment) it.copy(trustKnownPayloadCount = true) else it
        }
        val token = workGeneration.incrementAndGet()
        historyRestoreToken = token
        // A restored snapshot represents one concrete saved geometry even though auto recovery is not persisted.
        recoveryState.restoreAttempt(geometry, source, matrix,
            snapshot.samplingInverted?.let { SamplingProfile(requestSettings, it) })
        if (maxOf(requestSettings.preRecoveryPrioritySearch.size, matrix.prioritySearchCoordinates().size) > requestSettings.prioritySearchLimit) {
            historyRestoreToken = null
            refreshMainResultSelector()
            onRestored?.invoke()
            AlertDialog.Builder(this).setMessage(ui(
                "*の指定数が上限${requestSettings.prioritySearchLimit}を超えています。詳細設定で上限を戻すか、指定数を減らしてください。既存の指定は保持されています。",
                "The number of * marks exceeds the limit of ${requestSettings.prioritySearchLimit}. Raise the limit in advanced settings or remove some marks. Existing marks are preserved."))
                .setPositiveButton(R.string.close, null).show()
            return
        }
        statusText.text = ui("$prefix。復号候補を再計算中…", "$prefix. Recalculating decode candidates…")
        recoveryProgressDialog?.close()
        val progressDialog = RecoveryProgressDialog(this, matrix.prioritySearchCoordinates().size)
        recoveryProgressDialog = progressDialog
        recoveryTasks.execute(
            generation = token,
            task = { cancellation ->
                recoveryEngine.recoverWithDiagnostics(
                    matrix.copy(),
                    trustKnownPayloadCount = requestSettings.trustKnownPayloadCount,
                    partialBitSearchLimit = requestSettings.partialBitSearchLimit,
                    listDecodingAdditionalErrors = requestSettings.listDecodingAdditionalErrors,
                    formatConstraint = requestSettings.formatConstraint,
                    maxFormatMismatches = requestSettings.effectiveFormatMismatchLimit,
                    maxKnownDataEccMismatches = requestSettings.effectiveDataEccMismatchLimit,
                    manualEdits = requestSettings.fixedPreRecoveryEdits,
                    displayLockManualBits = requestSettings.lockManualBits,
                    retentionBudget = RecoveryMemoryPolicy.current().let {
                        RecoveryRetentionBudget(it.retainedBytes, workingLimitBytes = it.workingBytes)
                    },
                    cancellation = cancellation,
                    stopRequested = { progressDialog.stopped.get() },
                    listDecodingBudget = ListDecodingBudget(
                        onProgress = progressDialog::listDecodingProgress,
                        onIdle = progressDialog::listDecodingIdle,
                    ),
                    discoveryCounter = RecoveryDiscoveryCounter(onChanged = progressDialog::candidatesFound),
                    onPriorityProgress = progressDialog::priorityProgress,
                )
            },
            onResult = { outcome ->
                val ranked = outcome.candidates
                val failure = outcome.failure
                recoveryState.updateRecoveries(
                    ranked,
                    snapshot.selectedQr?.let { identity -> ranked.indexOfFirst(identity::matches) }
                        ?: preferredRecoveryIndex(ranked, snapshot.recoveryIndex),
                    failure,
                    outcome.searchDiagnostic,
                    outcome.discoveredCandidates,
                )
                refreshInspector()
                updateSummary(if (snapshot.selectedQr != null && recoveryState.selectedRecovery == null)
                    ui("$prefix。保存時の選択候補は今回の探索範囲では再現できませんでした。",
                        "$prefix. The saved selection could not be reproduced within this search.") else prefix)
                updateContentPreview()
                onRestored?.invoke()
            },
            onFinished = {
                closeRecoveryProgress(progressDialog)
                if (historyRestoreToken == token) historyRestoreToken = null
            },
        )
    }

    private fun showRecentSessions() {
        ioExecutor.execute {
            val recent = try { sessionStore.listRecent() } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (recent.isEmpty()) {
                    Toast.makeText(this, ui("保存済みの作業はありません。", "No saved work is available."), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = recent.map {
                    "${DateFormat.format("MM/dd HH:mm", Date(it.updatedAtMs))}  ${it.lastLabel.ifBlank { ui("QR修復", "QR repair") }}  (${it.historyCount}${ui("履歴", " entries")})"
                }.toTypedArray()
                val title = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(16), dp(24), dp(8))
                    addView(TextView(this@MainActivity).apply {
                        textSize = 20f
                        text = if (modernStyle) getString(R.string.saved_work) + " (${recent.size}/${RecoverySessionStore.MAX_SAVED_SESSIONS})"
                            else getString(R.string.recent_work_select_format, recent.size, RecoverySessionStore.MAX_SAVED_SESSIONS)
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 14f
                        setPadding(0, dp(8), 0, 0)
                        setText(R.string.saved_work_actions_hint)
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 14f
                        setPadding(0, dp(8), 0, 0)
                        text = getString(R.string.saved_work_retention, RecoverySessionStore.MAX_SAVED_SESSIONS)
                    })
                }
                val dialog = AlertDialog.Builder(this)
                    // Keep guidance outside setMessage, which would hide the session ListView.
                    .setCustomTitle(title)
                    .setItems(labels) { _, which -> restoreSessionById(recent[which].id) }
                    .setNeutralButton(R.string.delete_all) { _, _ -> confirmDeleteAllRecentSessions(recent.size) }
                    .setNegativeButton(R.string.close, null)
                    .show()
                dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                    dialog.dismiss()
                    confirmDeleteRecentSession(recent[position].id)
                    true
                }
            }
        }
    }

    private fun confirmDeleteRecentSession(id: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_recent_work)
            .setMessage(R.string.delete_recent_work_message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteRecentSession(id) }
            .show()
    }

    private fun confirmDeleteAllRecentSessions(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_recent_work)
            .setMessage(resources.getQuantityString(R.plurals.delete_all_recent_work_message, count, count))
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.delete_all) { _, _ -> deleteAllRecentSessions() }
            .show()
    }

    private fun deleteAllRecentSessions() {
        // The active work is included, so detach it before queued deletion runs.
        sessionId = null
        ioExecutor.execute {
            val deleted = try {
                sessionStore.deleteAll()
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (deleted) Toast.makeText(this, R.string.all_recent_work_deleted, Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, R.string.delete_recent_work_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteRecentSession(id: String) {
        // Stop future snapshots from recreating a session the user has chosen to delete.
        if (sessionId == id) sessionId = null
        ioExecutor.execute {
            val deleted = try {
                sessionStore.delete(id)
                true
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (deleted) showRecentSessions()
                else Toast.makeText(this, R.string.delete_recent_work_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restoreSessionById(id: String) {
        showWorkspace()
        val token = workGeneration.incrementAndGet()
        statusText.text = ui("保存済みセッションを読み込み中…", "Loading saved session…")
        ioExecutor.execute {
            val stored = try { sessionStore.load(id) } catch (_: Throwable) { null }
            val bitmap = stored?.sourceBitmap
            val p = if (bitmap != null) try { BitmapUtils.prepare(bitmap) } catch (_: Throwable) { null } else null
            fun recycleLoadedBitmaps() {
                p?.bitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                bitmap?.takeIf { !it.isRecycled }?.recycle()
            }
            if (token != workGeneration.get()) {
                recycleLoadedBitmaps()
                return@execute
            }
            runOnUiThread {
                if (token != workGeneration.get()) {
                    recycleLoadedBitmaps()
                    return@runOnUiThread
                }
                if (stored == null) {
                    statusText.text = ui("セッションを読み込めませんでした。", "Could not load the session.")
                    return@runOnUiThread
                }
                bitmap?.takeIf { it !== p?.bitmap && !it.isRecycled }?.recycle()
                applyStoredSession(stored, p)
            }
        }
    }

    private fun applyStoredSession(stored: StoredSession, source: BitmapUtils.Prepared?) {
        val retained = pendingRetainedCellHistory
        pendingRetainedCellHistory = null
        savedCellEditState = null
        sessionId = stored.id
        immediateResultShown = false
        prepared = source
        analysisPrepared = source
        sessionHistory.replace(stored.historyEntries, stored.historyCursor)
        sessionHistory.current?.let {
            restoreSnapshot(it, ui("保存済み作業を復元", "Restored saved work")) {
                // Consume once, only after the saved work has been restored and checked.
                if (retained?.sessionId == stored.id && retained.settings == preprocessSettings) {
                    savedCellEditState = retained.state.copyForEditing()
                }
                openRestoredHistoryEntry()
            }
        }
        if (source == null) {
            Toast.makeText(
                this,
                ui("元画像を検証できませんでした。履歴と保存済み行列のみ読み込みます。", "The source image could not be verified. Only history and saved matrices were loaded."),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun hasInconsistentInputFailure(): Boolean =
        recoveryState.selectedRecovery == null &&
            recoveryState.searchDiagnostic?.inputBlocker == RecoveryInputBlocker.ZERO_MISMATCH &&
            recoveryState.searchDiagnostic?.coverageComplete == true

    private fun searchCandidateCount(): Int = decodedRecoveryEntries().size

    private fun searchHasUncheckedCombinations(): Boolean = recoveryState.searchDiagnostic?.completeness == SearchCompleteness.UNSEARCHED ||
        (recoveryState.searchDiagnostic == null &&
            (recoveryState.selectedRecovery?.combinationSearch ?: recoveryState.failure?.combinationSearch)
                ?.unsearched == true)

    private fun searchResultDescription(): String = diagnosticPresenter().searchResultDescription(diagnosticContext())

    private fun updateContentPreview() {
        immediateResultShown = false
        showWorkspace()
        val candidate = recoveryState.selectedRecovery
        val preview = QrContentPreviewParser.parse(candidate)
        val geometry = recoveryState.geometry
        val failure = recoveryState.failure
        arrangeResultGuide(beforeImage = false)
        if (::resultImageLabel.isInitialized) {
            resultImageLabel.visibility = if (candidate != null || failure != null) View.VISIBLE else View.GONE
            resultImageLabel.text = if (candidate?.isSelectableRecovery == true) ui("選択中の候補のQR", "QR for the selected candidate")
                else ui("入力のQR（修復前）", "Input QR (before repair)")
        }
        contentPreviewText.visibility = View.VISIBLE
        guideText.visibility = if (candidate?.isSelectableRecovery == true) View.GONE else View.VISIBLE
        resultExplanationText.visibility = View.GONE
        resultNextAction.visibility = View.GONE
        resultActions.visibility = if (candidate != null || failure != null) View.VISIBLE else View.GONE
        resultCopyButton.visibility = if (candidate?.payloadParsed == true && candidate.text != null) View.VISIBLE else View.GONE
        resultSavePngButton.visibility = if (candidate.isSavableQrResult(preview)) View.VISIBLE else View.GONE
        when {
            candidate != null -> {
                contentPreviewText.text = if (preview != null && (candidate.payloadParsed || preview.qrStructureValid)) {
                    val ranges = mutableListOf<IntRange>()
                    val text = preview.resultContentText(candidate.text) { start, end -> ranges += start until end }
                    highlightedRecoveredText(text, ranges)
                } else ui("誤り訂正の条件は満たしましたが、内容は読み取れませんでした。", "The error-correction conditions were met, but the content could not be read.")
                resultExplanationText.text = candidate.resultExplanationText()
                resultExplanationText.visibility = View.VISIBLE
                setDiagnosticReport(candidateDiagnosticReport(candidate, geometry))
                if (!candidate.payloadParsed && preview?.qrStructureValid != true) configureNextResultAction()
            }
            failure != null -> {
                contentPreviewText.text = listOf(searchResultDescription(),
                    recoveryState.searchDiagnostic?.failureExplanation(failure.displayConditions).orEmpty())
                    .filter(String::isNotBlank).joinToString("\n")
                if (failure.stage == io.github.earthdiver.qrrepair.core.RecoveryFailureStage.PAYLOAD_VALIDATION &&
                    failure.reason == null && !hasInconsistentInputFailure() &&
                    failure.conventionalRsDiagnostic?.settingsAccepted == true) {
                    recoveryState.workingMatrix?.let { matrix ->
                        val version = (matrix.dimension - 17) / 4
                        val extraction = CodewordExtractor.extract(matrix, version, failure.dataMask)
                        val de = io.github.earthdiver.qrrepair.core.QrBlockInterleaver.deinterleave(extraction, version, failure.ecLevel)
                        val bytes = io.github.earthdiver.qrrepair.core.PostTerminatorAnalysis.conventionalData(de, failure.conventionalRsDiagnostic)
                        val body = bytes?.let { QrContentPreviewParser.parseUnverifiedPrefix(it, version, failure.ecLevel) }
                        if (body != null) {
                            val prefix = contentPreviewText.text.toString() + ui(
                                "\n\nRS訂正後の本文（未検証の候補）\n", "\n\nBody after RS correction (unverified candidate)\n")
                            val ranges = mutableListOf<IntRange>()
                            val text = body.resultContentText(body.recoveredText) { start, end ->
                                ranges += (prefix.length + start) until (prefix.length + end)
                            }
                            val hex = if (body.byteSegments.isEmpty()) "" else "\n\n" + CodewordHex.payloadByteSegments(body.byteSegments)
                            contentPreviewText.text = highlightedRecoveredText(prefix + text + hex, ranges)
                        }
                    }
                }
                setDiagnosticReport(failureDiagnosticReport(failure, geometry))
                configureNextResultAction()
            }
            else -> {
                contentPreviewText.text = getString(R.string.no_content_preview)
                clearDiagnosticDetails()
            }
        }
    }

    private fun arrangeResultGuide(beforeImage: Boolean) {
        val panel = contentPreviewText.parent as LinearLayout
        panel.removeView(guideText)
        panel.removeView(resultNextAction)
        val index = panel.indexOfChild(if (beforeImage) contentPreviewText else resultExplanationText) + 1
        panel.addView(guideText, index)
        panel.addView(resultNextAction, index + 1)
    }

    private fun configureNextResultAction() {
        arrangeResultGuide(beforeImage = true)
        val blocker = recoveryState.searchDiagnostic?.takeIf { it.coverageComplete }?.inputBlocker
        val settings = blocker in setOf(RecoveryInputBlocker.FORMAT_LIMIT, RecoveryInputBlocker.FORMAT_SELECTION)
        val recommendation = recoveryState.recommendation
        guideText.text = when {
            settings -> ui("次に試すこと\n形式情報と、ECC・マスクの指定を確認してください。", "Next step\nReview the format information and ECC/mask settings.")
            blocker == RecoveryInputBlocker.STRUCTURAL_FIXED_CELLS -> ui("次に試すこと\n手動で固定したセルの白黒を確認してください。", "Next step\nReview the manually locked cell values.")
            recommendation != null -> ui("次に試すこと\nセル (${recommendation.coord.x},${recommendation.coord.y}) の白黒を確認してください（座標は0から）。",
                "Next step\nCheck cell (${recommendation.coord.x},${recommendation.coord.y}) (zero-based coordinates).")
            else -> ui("次に試すこと\n入力したセルの白黒を元画像と見比べてください。", "Next step\nCompare the entered cell values with the source image.")
        }
        resultNextAction.text = if (settings) ui("形式設定を確認", "Review format settings") else ui("入力画面で確認", "Review in the input editor")
        resultNextAction.setOnClickListener { if (settings) showPreprocessDialog() else showCellEditor() }
        resultNextAction.visibility = View.VISIBLE
    }

    private fun diagnosticPresenter() = RecoveryDiagnosticPresenter(isJapaneseUi())

    private fun diagnosticContext() = RecoveryDiagnosticContext(
        candidateCount = searchCandidateCount(),
        discoveredCandidates = recoveryState.discoveredCandidates,
        unsearched = searchHasUncheckedCombinations(),
        candidatesOmitted = recoveryState.candidatesOmitted,
        searchDiagnostic = recoveryState.searchDiagnostic,
        selectedEntry = recoveryState.autoResult?.entries?.getOrNull(recoveryState.recoveryIndex),
        selectedCandidateNumber = decodedRecoveryEntries().indexOfFirst { it.first == recoveryState.recoveryIndex }
            .takeIf { it >= 0 }?.plus(1),
    )

    private fun candidateDiagnosticReport(candidate: RecoveryCandidate, geometry: GridGeometry?): RecoveryDiagnosticReport =
        diagnosticPresenter().candidateReport(candidate, geometry, diagnosticContext())

    private fun failureDiagnosticReport(failure: RecoveryFailure, geometry: GridGeometry?): RecoveryDiagnosticReport =
        diagnosticPresenter().failureReport(failure, geometry, diagnosticContext())

    private fun candidateDiagnosticText(candidate: RecoveryCandidate, geometry: GridGeometry?): String =
        candidateDiagnosticReport(candidate, geometry).fullText()

    private fun failureDiagnosticText(failure: RecoveryFailure, geometry: GridGeometry?): String =
        failureDiagnosticReport(failure, geometry).fullText()

    private fun geometryDiagnosticText(geometry: GridGeometry): String = diagnosticPresenter().geometryDiagnosticText(geometry)

    private fun openCamera() {
        startActivityForResult(
            Intent(this, CameraActivity::class.java).putExtra(AppStylePreferences.EXTRA_ACTIVE_STYLE, activeStyle.name),
            REQ_CAMERA,
        )
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_OPEN_IMAGE)
    }

    @Deprecated("Legacy result callback retained for the file-picker/camera handoff in this compact UI.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_SAVE_DIAGNOSTIC_INPUT) releasePendingDiagnosticBitmap()
            return
        }
        when (requestCode) {
            REQ_OPEN_IMAGE -> data?.data?.let { loadImage(it) }
            REQ_OPEN_TEXT_MATRIX -> data?.data?.let { loadTextMatrix(it) }
            REQ_SAVE_PNG -> data?.data?.let { savePng(it) }
            REQ_SAVE_DIAGNOSTIC_INPUT -> data?.data?.let { saveDiagnosticInput(it) }
                ?: releasePendingDiagnosticBitmap()
            REQ_CAMERA -> data?.getStringExtra(CameraActivity.EXTRA_CAPTURE_PATH)?.let {
                val captured = File(it)
                loadImage(Uri.fromFile(captured), cleanupFile = captured,
                    imageAdjustment = ImageAdjustment(
                        data.getIntExtra(CameraActivity.EXTRA_CONTRAST, 0),
                        data.getIntExtra(CameraActivity.EXTRA_BRIGHTNESS, 0),
                        data.getBooleanExtra(CameraActivity.EXTRA_INVERTED, false),
                    ))
            }
        }
    }

    private fun beginInputImport(message: String): Int {
        val token = workGeneration.incrementAndGet()
        inputProgressDialog?.dismiss()
        inputProgressGeneration = token
        statusText.text = message
        // Cover the previous work without discarding it before the import succeeds.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(android.widget.ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = message
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }
        inputProgressDialog = AlertDialog.Builder(this)
            .setView(content)
            .setCancelable(false)
            .create().also { dialog ->
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                        if (modernStyle) PrecisionStyle.BACKGROUND else android.graphics.Color.WHITE))
                    setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
        return token
    }

    private fun finishInputImport(token: Int) {
        if (inputProgressGeneration != token) return
        inputProgressDialog?.dismiss()
        inputProgressDialog = null
        inputProgressGeneration = null
    }

    private fun loadImage(uri: Uri, cleanupFile: File? = null, imageAdjustment: ImageAdjustment = ImageAdjustment()) {
        val token = beginInputImport(ui("画像を読み込み中…", "Loading image…"))
        executor.execute {
            try {
                val bitmap = decodeReasonablySized(uri)
                    ?: throw IllegalArgumentException(ui("画像をデコードできません", "Could not decode the image"))
                if (token != workGeneration.get()) {
                    bitmap.recycle()
                    cleanupFile?.let { runCatching { it.delete() } }
                    return@execute
                }
                runOnUiThread {
                    if (token != workGeneration.get()) {
                        bitmap.recycle()
                        cleanupFile?.let { runCatching { it.delete() } }
                    } else {
                        finishInputImport(token)
                        if (cleanupFile == null) showImportedImageAdjustment(bitmap)
                        else acceptDecodedImage(bitmap, cleanupFile, imageAdjustment)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    finishInputImport(token)
                    if (token != workGeneration.get() || isDestroyed) return@runOnUiThread
                    statusText.text = ui("画像の読み込みに失敗: ${t.message ?: t.javaClass.simpleName}", "Image load failed: ${t.message ?: t.javaClass.simpleName}")
                }
                cleanupFile?.let { runCatching { it.delete() } }
            } finally {
                runOnUiThread { finishInputImport(token) }
            }
        }
    }

    private fun chooseTextMatrix() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        startActivityForResult(intent, REQ_OPEN_TEXT_MATRIX)
    }

    private fun loadTextMatrix(uri: Uri) {
        val token = beginInputImport(ui("テキスト行列を読み込み中…", "Loading text matrix…"))
        executor.execute {
            var bitmap: Bitmap? = null
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(QrTextMatrixParser.MAX_TEXT_CHARS + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val count = input.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    require(total <= QrTextMatrixParser.MAX_TEXT_CHARS) { "Text matrix is too large" }
                    buffer.copyOf(total)
                } ?: throw IllegalArgumentException("Could not open the text matrix")
                val text = bytes.toString(Charsets.UTF_8)
                require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Text matrix must be UTF-8" }
                val parsed = QrTextMatrixParser.parse(text)
                val rendered = BitmapUtils.renderTextMatrixSource(parsed.matrix)
                bitmap = rendered
                val p = BitmapUtils.prepare(rendered)
                val moduleSize = 8
                val quietModules = 4
                val center = quietModules * moduleSize + (moduleSize - 1) / 2.0
                val last = center + (parsed.matrix.dimension - 1) * moduleSize
                val orientation = QrTextMatrixOrientationEstimator.estimate(parsed.matrix)
                val physicalCorners = listOf(PointD(center, center), PointD(last, center),
                    PointD(last, last), PointD(center, last))
                val corners = List(4) { physicalCorners[(it + (orientation?.quarterTurns ?: 0)) % 4] }
                val quad = ImageQuad(corners[0], corners[1], corners[2], corners[3])
                val finders = orientation?.finderCenters?.mapValues { (_, cell) ->
                    PointD(center + cell.x * moduleSize, center + cell.y * moduleSize)
                }.orEmpty()
                val priority = parsed.matrix.prioritySearchCoordinates()
                    .mapTo(linkedSetOf()) { Coord(it.first, it.second) }
                val edits = PreRecoveryCellEditState(priority.associateWith { ModuleValue.ERASED }, priority)
                val removedPriority = if (orientation != null) {
                    edits.rotateCoordinates(parsed.matrix.dimension, orientation.quarterTurns)
                    val allowed = QrCellRegions(parsed.version).codewordCoordinates
                    edits.revalidatePrioritySearch { it in allowed }
                } else emptySet()
                val settings = PreprocessSettings(
                    polarity = PolarityMode.NORMAL,
                    automaticThresholds = false,
                    blackMax = 64,
                    whiteMin = 192,
                    quietZoneCorrection = false,
                    version = parsed.version,
                    lockCorners = true,
                    lockVersion = true,
                    manualQuad = quad,
                    manualFinders = finders,
                    logicalGridAssigned = finders.isNotEmpty(),
                    confirmedFinderPolarity = if (finders.isNotEmpty()) FinderPolarity.NORMAL else FinderPolarity.UNKNOWN,
                    preRecoveryEdits = edits.edits.toMap(),
                    preRecoverySoftEdits = emptySet(),
                    preRecoveryPrioritySearch = edits.prioritySearch.toSet(),
                ).withInitialErrorLimits()
                if (token != workGeneration.get()) {
                    p.bitmap.recycle()
                    return@execute
                }
                val standardResult = decodeStandardQr(p.bitmap)
                runOnUiThread {
                    if (token != workGeneration.get()) {
                        p.bitmap.recycle()
                        return@runOnUiThread
                    }
                    finishInputImport(token)
                    immediateResultShown = false
                    prepared = p
                    analysisPrepared = p
                    preprocessSettings = settings
                    savedCellEditState = null
                    recoveryState.clear()
                    sessionId = sessionStore.newId()
                    sessionHistory.clear()
                    val newSessionId = sessionId!!
                    ioExecutor.execute {
                        try {
                            sessionStore.saveSourceBitmap(newSessionId, p.bitmap)
                        } catch (t: Throwable) {
                            reportPersistenceFailure(ui("疑似画像の保存に失敗", "Synthetic image save failed"), t)
                        }
                    }
                    sessionHistory.record(
                        RecoveryHistoryEntry(
                            kind = HistoryEventKind.IMPORT,
                            label = ui("テキスト行列を読み込み", "Imported text matrix"),
                            detail = ui("V${parsed.version} / ${parsed.matrix.dimension}×${parsed.matrix.dimension}", "Version ${parsed.version} / ${parsed.matrix.dimension}×${parsed.matrix.dimension}") +
                                (if (finders.isNotEmpty()) ui("; Finder位置・役割を自動設定", "; Finder positions and roles assigned automatically") else "") +
                                (if (removedPriority.isNotEmpty()) "\n" + getString(R.string.priority_revalidated,
                                    removedPriority.joinToString(" ") { "(${it.x},${it.y})" }) else ""),
                            snapshot = RecoverySnapshot(null, null, null, -1, settings),
                        ),
                    )
                    persistSessionAsync()
                    guideText.text = if (finders.isNotEmpty()) ui(
                        "案内: テキスト行列からFinderの位置・役割と向きを自動設定しました。緑のFinderを確認してください。",
                        "Guide: Finder positions, roles, and orientation were assigned automatically from the text matrix. Review the green Finders.",
                    ) else ui(
                        "案内: テキスト行列から作成した疑似画像です。向きは未確定です。Finderの役割を指定すると向きが確定します。",
                        "Guide: This synthetic image was created from the text matrix. Its orientation is unassigned. Assign a Finder role to set the orientation.",
                    )
                    contentPreviewText.text = getString(R.string.no_content_preview)
                    statusText.text = ui(
                        "テキスト行列を読み込みました（V${parsed.version}、${settings.preRecoveryPrioritySearch.size}個の*）。",
                        "Text matrix imported (Version ${parsed.version}, ${settings.preRecoveryPrioritySearch.size} * cell(s)).",
                    )
                    if (standardResult != null) showImmediateQrResult(standardResult) else showPreprocessDialog()
                    if (removedPriority.isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(getString(R.string.priority_revalidated,
                                removedPriority.joinToString(" ") { "(${it.x},${it.y})" }))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                bitmap = null // ownership moved to Prepared/UI
            } catch (t: Throwable) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                runOnUiThread {
                    finishInputImport(token)
                    if (token != workGeneration.get() || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(ui("テキスト行列を読み込めません", "Could not import text matrix"))
                        .setMessage(t.message ?: t.javaClass.simpleName)
                        .setPositiveButton(R.string.close, null)
                        .show()
                    statusText.text = ui("テキスト行列の読み込みに失敗しました。", "Text matrix import failed.")
                }
            } finally {
                runOnUiThread { finishInputImport(token) }
            }
        }
    }

    private fun showImportedImageAdjustment(initialBitmap: Bitmap) {
        importEditorDialog?.dismiss()
        retainImportSource = false
        importSource = initialBitmap
        lateinit var dialog: AlertDialog
        lateinit var editor: ImageAdjustmentEditor
        editor = ImageAdjustmentEditor(this, initialBitmap, onCancel = { dialog.dismiss() }, onApply = { adjustment ->
            runCatching { editor.createCrop() }.onSuccess { cropped ->
                dialog.dismiss()
                acceptDecodedImage(cropped, imageAdjustment = adjustment)
            }.onFailure(editor::showError)
        })
        importEditor = editor
        val editorTheme = if (AppStylePreferences.isModern(this)) R.style.ModernAppTheme else R.style.AppTheme
        dialog = AlertDialog.Builder(this, editorTheme).create()
        importEditorDialog = dialog
        dialog.setOnShowListener {
            // Use the same full-width content as the camera editor, without alert-panel insets.
            dialog.setContentView(editor)
            dialog.window?.let { window ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            }
            androidx.core.view.ViewCompat.requestApplyInsets(editor)
        }
        dialog.setOnDismissListener {
            editor.dispose()
            if (!retainImportSource && !initialBitmap.isRecycled) initialBitmap.recycle()
            importEditor = null
            importSource = null
            importEditorDialog = null
        }
        dialog.show()
    }

    private fun acceptDecodedImage(bitmap: Bitmap, cleanupFile: File? = null, imageAdjustment: ImageAdjustment = ImageAdjustment()) {
        val token = beginInputImport(ui("画像を解析用に準備中…", "Preparing image for analysis…"))
        executor.execute {
            var candidate: BitmapUtils.Prepared? = null
            var adjustedCandidate: BitmapUtils.Prepared? = null
            var handedToUi = false
            try {
                val p = BitmapUtils.prepare(bitmap)
                candidate = p
                if (p.bitmap !== bitmap) bitmap.recycle()
                val adjusted = BitmapUtils.adjust(p, imageAdjustment)
                adjustedCandidate = adjusted
                val standardResult = decodeStandardQr(adjusted.bitmap)
                val initialEstimate = InitialThresholdEstimator.estimate(adjusted.bitmap)
                val originalEstimate = if (adjusted === p) initialEstimate else InitialThresholdEstimator.estimate(p.bitmap)
                val initialGrid = io.github.earthdiver.qrrepair.core.InitialGridEstimator.estimate(
                    adjusted.luma, io.github.earthdiver.qrrepair.core.ZxingCppGridDetector.detect(adjusted.bitmap),
                )
                if (token != workGeneration.get()) {
                    p.bitmap.recycle()
                    if (adjusted !== p) adjusted.bitmap.recycle()
                    return@execute
                }
                runOnUiThread {
                    if (token != workGeneration.get()) {
                        p.bitmap.recycle()
                        if (adjusted !== p) adjusted.bitmap.recycle()
                        return@runOnUiThread
                    }
                    finishInputImport(token)
                    immediateResultShown = false
                    prepared = p
                    analysisPrepared = adjusted
                    savedCellEditState = null
                    preprocessSettings = PreprocessSettings(
                        imageAdjustment = imageAdjustment,
                        automaticThresholds = true,
                        blackMax = initialEstimate?.blackMax ?: 96,
                        whiteMin = initialEstimate?.whiteMin ?: 160,
                        unknownColorClusters = originalEstimate?.unknownColorClusters.orEmpty(),
                        lockCorners = true,
                    ).withInitialGrid(initialGrid).withInitialErrorLimits()
                    sessionId = sessionStore.newId()
                    sessionHistory.clear()
                    val newSessionId = sessionId!!
                    ioExecutor.execute {
                        try {
                            sessionStore.saveSourceBitmap(newSessionId, p.bitmap)
                        } catch (t: Throwable) {
                            reportPersistenceFailure(ui("元画像の保存に失敗", "Source image save failed"), t)
                        }
                    }
                    sessionHistory.record(
                        RecoveryHistoryEntry(
                            kind = HistoryEventKind.IMPORT,
                            label = ui("画像を読み込み", "Imported image"),
                            detail = "${p.bitmap.width}×${p.bitmap.height}px" +
                                (initialGrid?.let {
                                    val method = when (it.source) {
                                        io.github.earthdiver.qrrepair.core.InitialGridSource.ZXING_CPP -> "ZXing-C++"
                                        io.github.earthdiver.qrrepair.core.InitialGridSource.PERIODICITY -> ui("周期推定", "periodicity")
                                        io.github.earthdiver.qrrepair.core.InitialGridSource.TWO_FINDER -> ui("2つのFinderから推定", "two-Finder estimate")
                                    }
                                    ui("; 格子推定: $method / V${it.version}", "; Grid estimate: $method / V${it.version}") +
                                        if (it.autoAssignedFinders.isNotEmpty()) ui("; Finder位置・役割を自動設定", "; Finder positions and roles assigned automatically") else ""
                                } ?: ""),
                            snapshot = RecoverySnapshot(null, null, null, -1, preprocessSettings),
                        ),
                    )
                    persistSessionAsync()
                    recoveryState.clear()
                    guideText.text = ui("案内: 修復前設定で閾値・反転・グリッドを確認してください。", "Guide: Review the threshold, polarity, and grid in the pre-repair settings.")
                    contentPreviewText.text = getString(R.string.no_content_preview)
                    clearDiagnosticDetails()
                    refreshMainResultSelector()
                    statusText.text = ui("画像 ${p.bitmap.width}×${p.bitmap.height}px。修復前設定を確認してください。", "Image ${p.bitmap.width}×${p.bitmap.height}px. Review the pre-repair settings.")
                    if (standardResult != null) {
                        showImmediateQrResult(standardResult)
                    } else {
                        showPreprocessDialog()
                    }
                }
                handedToUi = true
            } catch (t: Throwable) {
                runOnUiThread {
                    finishInputImport(token)
                    if (token != workGeneration.get() || isDestroyed) return@runOnUiThread
                    statusText.text = ui("画像の読み込みに失敗: ${t.message ?: t.javaClass.simpleName}", "Image load failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                runOnUiThread { finishInputImport(token) }
                if (!handedToUi) {
                    adjustedCandidate?.bitmap?.takeIf { it !== candidate?.bitmap && !it.isRecycled }?.recycle()
                    candidate?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap.takeIf { it !== candidate?.bitmap && !it.isRecycled }?.recycle()
                }
                // CameraActivity hands us a private lossless cache image. It is only a hand-off artifact;
                // the durable session copy is lossless PNG under noBackupFilesDir.
                cleanupFile?.let { runCatching { it.delete() } }
            }
        }
    }

    private fun decodeStandardQr(bitmap: Bitmap): Result? = runCatching {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        StandardQrDecoder.decode(bitmap.width, bitmap.height, pixels)
    }.getOrNull()

    private fun immediateQrResult(result: Result) = ImmediateQrResult(
        sessionId, sessionHistory.current?.id, preprocessSettings, result.text, result.rawBytes?.copyOf())

    private fun showImmediateQrResult(result: Result) = renderImmediateQrResult(immediateQrResult(result), showNotice = true)

    private fun renderImmediateQrResult(result: ImmediateQrResult, showNotice: Boolean) {
        immediateResult = result
        immediateResultShown = true
        showWorkspace()
        if (modernStyle) {
            workspaceTitle.text = ui("読み取り結果", "Scan result")
            workspaceStep.text = ui("03 結果  /  内容を確認", "03 Result  /  Review content")
        }
        clearDiagnosticDetails()
        refreshMainResultSelector()
        val rawHex = result.rawBytes?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }.orEmpty()
        val display = buildString {
            append(ui("正常なQRコードを検出しました。\n\n", "A valid QR code was detected.\n\n"))
            append(result.text.ifBlank { ui("（テキストなし）", "(No text)") })
            if (rawHex.isNotBlank()) append("\n\nRaw bytes:\n").append(rawHex)
            append(ui("\n\nURL等は安全のため自動では開きません。", "\n\nURLs are not opened automatically for safety."))
        }
        statusText.text = ui("正常なQRコードを即時デコードしました。修復は不要です。", "The valid QR code was decoded immediately; repair is unnecessary.")
        guideText.text = ui("案内: 内容を確認してください。必要な場合のみ『修復する』を開けます。", "Guide: Review the content. Open Repair only if needed.")
        contentPreviewText.text = ui("内容プレビュー: 正常QR\n${result.text}", "Content preview: Valid QR\n${result.text}")
        if (showNotice) AlertDialog.Builder(this)
            .setTitle(ui("QRコードの内容", "QR code content"))
            .setMessage(display)
            .setNegativeButton(R.string.repair) { _, _ -> showPreprocessDialog() }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun decodeReasonablySized(uri: Uri): Bitmap? {
        val orientation = contentResolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > 2600) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val spec = imageOrientationTransform(orientation)
        val transform = Matrix().apply {
            setRotate(spec.rotationDegrees)
            if (spec.scaleX != 1f || spec.scaleY != 1f) postScale(spec.scaleX, spec.scaleY)
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return decoded
        }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (t: Throwable) {
            decoded.recycle()
            throw t
        }
    }

    private fun showFormatEditError(conflict: FormatEditConflict, changeRejected: Boolean = true) {
        AlertDialog.Builder(this)
            .setTitle(ui("形式情報の指定エラー", "Format selection error"))
            .setMessage(conflict.message(changeRejected))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun settingsFormatConflict(settings: PreprocessSettings): FormatEditConflict? =
        FormatEditPolicy.conflict(settings.version, settings.formatConstraint, settings.fixedPreRecoveryEdits)

    private val singleSegmentRetryPolicy = SingleSegmentRetryPolicy()

    private fun startAutoRecovery() = startAutoRecovery(oneTimeSingleSegment = false)

    private fun startAutoRecovery(oneTimeSingleSegment: Boolean) {
        if (inputProgressGeneration != null) return
        immediateResultShown = false
        showWorkspace()
        val savedSettings = preprocessSettings.recoverySnapshot()
        val requestSettings = if (oneTimeSingleSegment) savedSettings.copy(trustKnownPayloadCount = true) else savedSettings
        val inputSource = prepared
        if (requestSettings.preRecoveryPrioritySearch.size > requestSettings.prioritySearchLimit) {
            AlertDialog.Builder(this).setMessage(ui(
                "*の指定数が上限${requestSettings.prioritySearchLimit}を超えています。詳細設定で上限を戻すか、指定数を減らしてください。既存の指定は保持されています。",
                "The number of * marks exceeds the limit of ${requestSettings.prioritySearchLimit}. Raise the limit in advanced settings or remove some marks. Existing marks are preserved."))
                .setPositiveButton(R.string.close, null).show()
            return
        }
        settingsFormatConflict(requestSettings)?.let {
            workGeneration.incrementAndGet()
            refreshMainResultSelector()
            contentPreviewText.text = getString(R.string.no_content_preview)
            clearDiagnosticDetails()
            statusText.text = ui("形式情報の指定が矛盾しているため、修復を開始できません。", "Repair cannot start: format selections conflict.")
            showFormatEditError(it, changeRejected = false)
            return
        }

        val p = preparedFor(requestSettings) ?: run {
            statusText.text = ui("先に画像を選択してください。", "Select an image first.")
            return
        }
        analysisPrepared = p
        val token = workGeneration.incrementAndGet()
        recoveryState.clear()
        recoveryState.oneTimeSingleSegment = oneTimeSingleSegment
        updateContentPreview()
        contentPreviewText.text = ""
        guideText.text = ""
        refreshMainResultSelector()
        val searchSummary = requestSettings.searchSummary()
        statusText.text = ui("$searchSummary。固定した幾何を評価中…", "$searchSummary. Evaluating the fixed geometry…")
        recoveryProgressDialog?.close()
        val progressDialog = RecoveryProgressDialog(this, requestSettings.preRecoveryPrioritySearch.size)
        recoveryProgressDialog = progressDialog
        recoveryTasks.execute(
            generation = token,
            task = { cancellation ->
                val memoryPolicy = RecoveryMemoryPolicy.current()
                RecoveryWorkflow(recoveryEngine).run(
                    p.luma, requestSettings,
                    retentionBudget = RecoveryRetentionBudget(memoryPolicy.retainedBytes,
                        workingLimitBytes = memoryPolicy.workingBytes),
                    cancellation = cancellation,
                    stopRequested = { progressDialog.stopped.get() },
                    listDecodingBudget = ListDecodingBudget(
                        onProgress = progressDialog::listDecodingProgress,
                        onIdle = progressDialog::listDecodingIdle,
                    ),
                    discoveryCounter = RecoveryDiscoveryCounter(onChanged = progressDialog::candidatesFound),
                    onPriorityProgress = progressDialog::priorityProgress,
                    onProgress = { progress ->
                        progressDialog.phase("${progress.profile.label}: ${progress.message} (${progress.done}/${progress.total})")
                    },
                )
            },
            onResult = onResult@{ result ->
                recoveryState.autoResult = result
                if (workGeneration.get() == token && prepared === inputSource &&
                    preprocessSettings.recoverySnapshot() == savedSettings && inputSource != null &&
                    singleSegmentRetryPolicy.claim(inputSource, requestSettings,
                        result.searchDiagnostic, progressDialog.stopped.get())) {
                    AlertDialog.Builder(this)
                        .setMessage(ui("単一セグメントの終端・埋め草を強制すると、内容を復号できる可能性があります。この条件で今回だけ再実行しますか？",
                            "Forcing a single segment’s terminator and padding may allow the content to decode. Retry with this condition for this run only?"))
                        .setPositiveButton(ui("今回だけ再実行", "Retry this time only")) { _, _ ->
                            if (workGeneration.get() == token && prepared === inputSource &&
                                preprocessSettings.recoverySnapshot() == savedSettings) startAutoRecovery(oneTimeSingleSegment = true)
                        }
                        .setNegativeButton(ui("再実行しない", "Do not retry"), null)
                        .show()
                }
                if (result.searchDiagnostic?.completeness == SearchCompleteness.UNSEARCHED && result.attempts.isEmpty()) {
                    statusText.text = searchResultDescription()
                    guideText.text = ui("保持された候補はありません。復元不能を意味しません。", "No candidates were retained. This does not establish that recovery is impossible.")
                    recordRecoveryAttempt(statusText.text.toString(), guideText.text.toString())
                    return@onResult
                }
                if (result.attempts.isEmpty() && result.attemptedGeometries > 0) {
                    statusText.text = ui("表示用の修復結果を保持できませんでした。", "Could not retain a recovery result for display.")
                    guideText.text = ui("メモリの保持上限に達しました。作業を保存してアプリを開き直し、再実行してください。",
                        "The memory retention limit was reached. Save the work, reopen the app and try again.")
                    recordRecoveryAttempt(statusText.text.toString(), searchResultDescription())
                    return@onResult
                }
                if (result.finders.isEmpty() && result.attempts.isEmpty()) {
                    statusText.text = ui("論理四隅が未確定のため、QR行列を作成できませんでした。四隅とFinder役割を確認してください。", "The QR matrix could not be built because the logical corners are not assigned. Check the corners and Finder roles.")
                    guideText.text = ui("案内: QR行列を作成できなかったため、修復を開始できませんでした。四隅・Finderの役割・バージョンをご確認ください。QR行列がないため、追加で確定が必要なbit数と推奨セルは算出できません。", "Guide: Repair could not be started because a QR matrix could not be created. Please review the corners, Finder roles, and Version. Without a QR matrix, the number of additional bits that must be determined and a recommended cell cannot be calculated.")
                    recordRecoveryAttempt(ui("修復できませんでした", "Repair failed"), ui("論理四隅が未確定", "Logical corners are not assigned"))
                    return@onResult
                }
                if (result.attempts.isEmpty()) {
                    statusText.text = ui("固定した四隅とバージョンから幾何候補を作れませんでした。", "No geometry candidate could be built from the fixed corners and Version.")
                    guideText.text = ui("案内: 固定された四隅とバージョンからQR行列を作成できなかったため、修復を開始できませんでした。四隅とバージョンをご確認ください。QR行列がないため、追加で確定が必要なbit数と推奨セルは算出できません。", "Guide: Repair could not be started because a QR matrix could not be created from the fixed corners and Version. Please review the corners and Version. Without a QR matrix, the number of additional bits that must be determined and a recommended cell cannot be calculated.")
                    recordRecoveryAttempt(ui("修復できませんでした", "Repair failed"), ui("固定幾何候補なし", "No fixed geometry candidate"))
                    return@onResult
                }
                val best = result.bestAttempt ?: result.attempts.first()
                recoveryState.attemptIndex = result.attempts.indexOf(best).coerceAtLeast(0)
                showAttempt(recoveryState.attemptIndex)
                recordHistory(
                    HistoryEventKind.AUTO_RECOVERY,
                    if (oneTimeSingleSegment) ui("修復処理（今回だけ終端後構造を強制）", "Repair (single-segment forcing for this run only)")
                    else ui("修復処理", "Repair"),
                    ui("試行 ${result.attemptedGeometries}件 / 保持候補 ${recoveryState.selectableEntries.size}件", "${result.attemptedGeometries} attempts / ${recoveryState.selectableEntries.size} retained candidates"),
                )
            },
            onError = { error ->
                statusText.text = ui("内部エラーにより、探索の完了状況を確認できません。",
                    "An internal error prevented determining search completion.")
                guideText.text = ui("案内: 修復処理を再実行してください。\nエラー詳細: ${error.message ?: error.javaClass.simpleName}",
                    "Guide: Run repair again.\nError details: ${error.message ?: error.javaClass.simpleName}")
            },
            onFinished = { closeRecoveryProgress(progressDialog) },
        )
    }

    private fun closeRecoveryProgress(progressDialog: RecoveryProgressDialog) {
        progressDialog.close()
        if (recoveryProgressDialog === progressDialog) recoveryProgressDialog = null
    }

    private fun showPreprocessDialog() = showPreprocessPage(PreprocessPage.BASIC)

    private fun showCellEditor() = showPreprocessPage(PreprocessPage.CELL_EDITOR)

    private fun showPreprocessPage(initialPage: PreprocessPage, restoredDraft: PreprocessEditorDraft? = null) {
        if (inputProgressGeneration != null) return
        showWorkspace()
        val source = prepared ?: run {
            statusText.text = ui("先に画像を選択してください。", "Select an image first.")
            return
        }
        preprocessEditor?.let {
            it.open(initialPage)
            return
        }
        PreprocessDialogController(
            activity = this,
            preprocessSettings = preprocessSettings,
            source = source,
            initialWorking = preparedFor(restoredDraft?.settings ?: preprocessSettings) ?: source,
            savedCellEditState = savedCellEditState,
            restoredDraft = restoredDraft,
            executor = executor,
            finderSearchGeneration = finderSearchGeneration,
            recoveryEngine = recoveryEngine,
            host = object : PreprocessDialogHost {
                override fun retains(image: BitmapUtils.Prepared): Boolean =
                    image === analysisPrepared || pendingDiagnosticBitmap?.bitmap === image.bitmap

                override fun save(draft: PreprocessSave) {
                    workGeneration.incrementAndGet()
                    historyRestoreToken = null
                    recoveryState.clear()
                    refreshMainResultSelector()
                    preprocessSettings = draft.settings
                    savedCellEditState = draft.cellEdits
                    draft.events.forEach { event ->
                        recordPreprocessHistory(event.settings, event.label, event.detail)
                    }
                    // Deletion detaches the open work from storage. Only an explicit save may
                    // create a new session; never resurrect the deleted session automatically.
                    if (sessionId == null) {
                        val newId = sessionStore.newId()
                        val sourceBitmap = source.bitmap
                        sessionId = newId
                        ioExecutor.execute {
                            try {
                                sessionStore.saveSourceBitmap(newId, sourceBitmap)
                            } catch (t: Throwable) {
                                reportPersistenceFailure(ui("元画像の保存に失敗", "Source image save failed"), t)
                            }
                        }
                    }
                    persistSessionAsync()
                    analysisPrepared = draft.image
                }

                override fun showSavedStatus() {
                    immediateResultShown = false
                    showWorkspace()
                    statusText.text = ui("修復設定を保存しました。", "Repair settings saved.")
                    contentPreviewText.text = ui("内容プレビュー: 修復設定を保存しました。修復はまだ実行していません。", "Content preview: Repair settings were saved; repair has not started.")
                }

                override fun startRecovery() = startAutoRecovery()

                override fun canShowResult(): Boolean = immediateResultShown ||
                    recoveryState.selectedRecovery != null || recoveryState.failure != null

                override fun showResult() = showWorkspace()

                override fun showHome() = showStartMenu()

                override fun editorOpened(session: PreprocessEditorSession) {
                    preprocessEditor = session
                }

                override fun editorClosed(session: PreprocessEditorSession) {
                    if (preprocessEditor === session) preprocessEditor = null
                }

                override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) =
                    startSaveDiagnosticInput(bitmap, owned)
            },
        ).show(initialPage)
    }

    private fun showAttempt(index: Int) {
        val result = recoveryState.autoResult ?: return
        if (result.entries.isNotEmpty()) {
            recoveryState.showResults(result)
        } else {
            if (result.attempts.isEmpty()) return
            val normalizedIndex = ((index % result.attempts.size) + result.attempts.size) % result.attempts.size
            val attempt = result.attempts[normalizedIndex]
            recoveryState.showAttempt(normalizedIndex, attempt.hypothesis.geometry, attempt.matrix,
                attempt.recoveries, attempt.failure, preferredRecoveryIndex(attempt.recoveries))
        }
        updateSelectedSamplingSettings()
        refreshInspector()
        updateSummary()
    }

    private fun updateSelectedSamplingSettings() {
        val result = recoveryState.autoResult ?: return
        val attempt = result.entries.getOrNull(recoveryState.recoveryIndex)?.attempt
            ?: result.attempts.getOrNull(recoveryState.attemptIndex) ?: return
        attempt.samplingStats.thresholds?.let { pair ->
            preprocessSettings = preprocessSettings.copy(blackMax = pair.blackMax, whiteMin = pair.whiteMin)
        }
    }

    private fun preferredRecoveryIndex(candidates: List<RecoveryCandidate>, requested: Int = -1): Int {
        if (candidates.isEmpty()) return -1
        val displayedDecoded = candidates.indices.asSequence()
            .filter { candidates[it].isSelectableRecovery }
            .take(MAX_DISPLAYED_RECOVERY_CANDIDATES)
            .toList()
        if (requested in displayedDecoded) return requested
        val decoded = displayedDecoded.firstOrNull() ?: -1
        return if (decoded >= 0) decoded else requested.coerceIn(0, candidates.lastIndex)
    }

    private fun refreshInspector() {
        val source = recoveryState.sourceMatrix ?: return
        val working = recoveryState.workingMatrix ?: return
        val candidate = recoveryState.selectedRecovery
        val validatedCandidate = candidate?.takeIf { it.isSelectableRecovery }
        val after = if (validatedCandidate != null) {
            RepairTraceBuilder.candidateMatrix(working, validatedCandidate, recoveryEngine)
        } else {
            working.copy()
        }
        recoveryState.inspectorMatrix = after
        recoveryState.traces = RepairTraceBuilder.build(source, after, validatedCandidate)

        val guidance = io.github.earthdiver.qrrepair.core.guidanceCandidates(recoveryState.guidanceRecoveries, validatedCandidate)
        val candidateMatrices = guidance.candidates.map {
            RepairTraceBuilder.candidateMatrix(working, it, recoveryEngine)
        }
        recoveryState.confidenceMap = RepairGuidance.confidenceMap(source, after, candidateMatrices, recoveryState.traces, guidance.total)
        recoveryState.recommendation = RepairGuidance.recommendNextCell(working, candidateMatrices, recoveryState.confidenceMap, guidance.total)
        if (candidateMatrices.isEmpty()) {
            recoveryState.recommendation = RepairGuidance.recommendFromBitSpace(working, recoveryState.failure?.rsBitDiagnostic)
        }

        updateGuideText()
    }

    private fun updateGuideText() {
        val rec = recoveryState.recommendation
        val c = recoveryState.selectedRecovery
        val distinctPayloads = recoveryState.recoveries.asSequence()
            .filter { it.payloadParsed }
            .map { it.dataBytes.toList() }
            .distinct()
            .count()
        guideText.text = when {
            c?.payloadParsed == true && distinctPayloads > 1 -> ui(
                "案内: データコード語列が異なる候補があります。表示内容が同じ場合もあります。各候補をご確認ください。",
                "Guide: Candidates have different data codeword sequences, even if their displayed text is identical. Please review each candidate.",
            )
            c?.payloadParsed == true && c.structuredAppend != null -> c.structuredAppend.description()
            c?.payloadParsed == true -> ui(
                "内容を確認して、コピーやQR画像の保存ができます。",
                "Review the content, then copy it or save the QR image.",
            )
            c != null -> ui(
                "案内: 内容を文字列として表示できませんでした。HEX／Binary表示で確認できます。",
                "Guide: The content could not be displayed as text. Check the Hex / Binary view.",
            )
            hasInconsistentInputFailure() -> ui(
                "入力の0/1に対して、不一致上限（0bit）を満たすRS候補はありません。\n入力済みセルの白黒、QRの位置・向き、形式設定をご確認ください。",
                "No RS candidate meets the zero-bit mismatch limit against entered 0/1 values.\nReview entered cell values, the QR position and orientation, and the format settings.",
            )
            recoveryState.workingMatrix != null -> {
                if (rec?.exactSpaceDimension != null) {
                    guideText.text = buildString {
                        append(ui(
                            "案内: 既知bitとの完全一致を仮定した残る選択: ${rec.exactSpaceDimension}bit分。内容検証前の目安です。\n",
                            "Guide: ${rec.exactSpaceDimension} free RS bits assuming exact known-bit agreement, before payload validation.\n",
                        ))
                        append(rec.explanationJa())
                    }
                    return
                }
                val shortfall = recoveryState.failure?.rsShortfallBits ?: estimateRsShortfallBits(recoveryState.workingMatrix!!)
                buildString {
                    append(when (shortfall) {
                        null -> ui(
                            "案内: 欠損量に基づく追加確認bit数の目安は算出できません。",
                            "Guide: An estimate of additional bits to check based on missing data is unavailable.",
                        )
                        0 -> ui(
                            "案内: 欠損量だけを見ると誤り訂正の容量内ですが、修復には至りませんでした。入力済みセルや修復設定をご確認ください。",
                            "Guide: The amount of missing data is within error-correction capacity, but repair was unsuccessful. Review the entered cells and repair settings.",
                        )
                        else -> ui(
                            "案内: 欠損量に基づく追加確認bit数の目安: ${shortfall}bit。既知bitと形式設定が正しいという前提での値で、修復成功を保証するものではありません。",
                            "Guide: Estimated additional bits to check based on missing data: $shortfall bits. This assumes the known bits and format settings are correct and does not guarantee successful repair.",
                        )
                    })
                    rec?.let {
                        append(ui(" 候補を最も絞り込めるセル: (${it.coord.x},${it.coord.y})。", " Best cell for narrowing the candidates: (${it.coord.x},${it.coord.y})."))
                        append(ui("このセルをご確認ください。", " Check this cell."))
                    }
                    if (rec == null && shortfall != 0) {
                        append(ui(" 入力済みセルや修復設定をご確認ください。", " Review the entered cells and repair settings."))
                    }
                }
            }
            else -> getString(R.string.initial_guide)
        }
    }

    /** Minimum unresolved bits that must be decided to bring every RS block within erasure capacity. */
    private fun estimateRsShortfallBits(matrix: QrMatrix): Int? {
        val version = (matrix.dimension - 17) / 4
        val formats = preprocessSettings.formatConstraint?.let { constraint ->
            FormatSolver.ranked(matrix).filter { constraint.permits(it.ecLevel, it.dataMask) }
        } ?: FormatSolver.ranked(matrix).take(8)
        return formats.mapNotNull { format ->
            runCatching {
                val extraction = CodewordExtractor.extract(matrix, version, format.dataMask)
                val deinterleaved = io.github.earthdiver.qrrepair.core.QrBlockInterleaver.deinterleave(
                    extraction, version, format.ecLevel,
                )
                io.github.earthdiver.qrrepair.core.minimumRsShortfallBits(
                    deinterleaved.blocks, deinterleaved.ecSymbolsPerBlock,
                )
            }.getOrNull()
        }.minOrNull()
    }

    private fun findCodewordAt(x: Int, y: Int): Pair<Int, Int>? {
        val candidate = recoveryState.selectedRecovery ?: return null
        for ((cw, partial) in candidate.extraction.codewords.withIndex()) {
            val bit = partial.coordinates.indexOfFirst { it.x == x && it.y == y }
            if (bit >= 0) return cw to bit
        }
        return null
    }

    private fun updateSummary(prefix: String? = null) {
        val g = recoveryState.geometry
        val m = recoveryState.workingMatrix
        if (g == null || m == null) return
        val c = recoveryState.selectedRecovery
        val decoded = decodedRecoveryEntries().map { it.second }
        val distinctPayloads = decoded.asSequence().map { it.dataBytes.toList() }.distinct().count()
        val sharedResult = when {
            c != null && !c.payloadParsed && c.isSelectableRecovery -> searchResultDescription() + ui(
                "\nQRのデータ形式を確認しました\n文字として表示できないデータです。",
                "\nThe QR data format was verified\nThe data cannot be displayed as text.")
            decoded.size == 1 -> searchResultDescription()
            decoded.size > 1 && distinctPayloads == 1 -> searchResultDescription() + ui(
                "\n表示中の読み取り結果のデータコード語列はすべて同じです。",
                "\nCandidates for the displayed reading all have the same data codeword sequence.",
            )
            decoded.size > 1 -> searchResultDescription() + ui(
                "\n表示中の読み取り結果のデータコード語列は${distinctPayloads}種類です。",
                "\nCandidates for the displayed reading have $distinctPayloads distinct data codeword sequences.",
            )
            c != null && QrContentPreviewParser.parse(c)?.qrStructureValid == true -> ui(
                "QRのデータ形式を確認しました\n文字として表示できないデータです。", "The QR data format was verified\nThe data cannot be displayed as text.")
            c != null -> ui("誤り訂正の条件を満たす候補はありますが、内容を読み取れませんでした", "Error-correction candidates exist, but their content could not be read")
            else -> searchResultDescription()
        }
        statusText.text = buildString {
            if (!prefix.isNullOrBlank()) append(prefix).append('\n')
            append(sharedResult)
            c?.structuredAppend?.let { append('\n').append(it.description()) }
        }
        refreshMainResultSelector()
        updateContentPreview()
    }


    private fun startSavePng() {
        val candidate = recoveryState.selectedRecovery
        if (!candidate.isSavableQrResult(QrContentPreviewParser.parse(candidate))) {
            statusText.text = ui(
                "検証済みの修復結果がないため、結果PNGは保存できません。調査用画像は修復設定の詳細画面から保存できます。",
                "A result PNG cannot be saved because there is no validated repair result. Diagnostic input images can be saved from detailed repair settings.",
            )
            return
        }
        if (matrixForPng() == null) {
            statusText.text = ui("保存するQR行列がありません。", "No QR matrix is available to save.")
            return
        }
        val results = displayedDecodedRecoveryEntries()
        if (results.size > 1) {
            var selectedResult = results.indexOfFirst { it.first == recoveryState.recoveryIndex }.coerceAtLeast(0)
            val choices = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                results.indices.forEach { resultIndex ->
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = getString(R.string.result_tab, resultIndex + 1)
                        isChecked = resultIndex == selectedResult
                        setOnClickListener { selectedResult = resultIndex }
                    })
                }
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.select_result)
                .setView(ScrollView(this).apply {
                    isFillViewport = true
                    addView(choices)
                })
                .setPositiveButton(R.string.save_png) { _, _ ->
                    selectRecoveryCandidate(results[selectedResult].first, recordSelection = true)
                    launchSavePngDocument()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        launchSavePngDocument()
    }

    private fun launchSavePngDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, "QRevy-repaired.png")
        }
        startActivityForResult(intent, REQ_SAVE_PNG)
    }

    /** PNG export must match the displayed result matrix, including RS and structural repairs. */
    private fun matrixForPng(): QrMatrix? = (recoveryState.inspectorMatrix ?: recoveryState.workingMatrix)?.copy()

    private fun RecoveryCandidate?.isSavableQrResult(preview: QrContentPreview?): Boolean =
        this != null && (payloadParsed || preview?.qrStructureValid == true)

    private fun startSaveDiagnosticInput(bitmap: Bitmap? = null, owned: Boolean = false) {
        if (prepared == null) {
            if (owned) bitmap?.recycle()
            statusText.text = ui("保存する入力画像がありません。", "No input image is available to save.")
            return
        }
        releasePendingDiagnosticBitmap()
        pendingDiagnosticBitmap = (bitmap ?: (analysisPrepared ?: preparedFor(preprocessSettings))?.bitmap)
            ?.let { PendingDiagnosticBitmap(it, owned && it === bitmap) }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, "QRevy-debug-input.png")
        }
        startActivityForResult(intent, REQ_SAVE_DIAGNOSTIC_INPUT)
    }

    private fun saveDiagnosticInput(uri: Uri) {
        val pending = pendingDiagnosticBitmap
            ?: (analysisPrepared ?: preparedFor(preprocessSettings))?.bitmap?.let { PendingDiagnosticBitmap(it, false) }
            ?: return
        pendingDiagnosticBitmap = null
        executor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    if (!pending.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException(ui("PNG圧縮に失敗", "PNG compression failed"))
                    }
                } ?: throw IllegalStateException(ui("保存先を開けません", "Could not open the destination"))
                runOnUiThread {
                    statusText.text = ui("デバッグ用入力画像を保存しました（${pending.bitmap.width}×${pending.bitmap.height}px）。", "Saved debug input image (${pending.bitmap.width}×${pending.bitmap.height} px).")
                }
            } catch (t: Throwable) {
                runOnUiThread { statusText.text = ui("入力画像の保存失敗: ${t.message}", "Input image save failed: ${t.message}") }
            } finally {
                if (pending.owned) pending.bitmap.recycle()
            }
        }
    }

    private fun releasePendingDiagnosticBitmap() {
        val pending = pendingDiagnosticBitmap
        pendingDiagnosticBitmap = null
        pending?.release()
    }

    private fun savePng(uri: Uri) {
        val matrix = matrixForPng() ?: return
        executor.execute {
            try {
                val bitmap = renderMatrixPng(matrix)
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IllegalStateException(ui("PNG圧縮に失敗", "PNG compression failed"))
                        }
                    } ?: throw IllegalStateException(ui("保存先を開けません", "Could not open the destination"))
                } finally {
                    bitmap.recycle()
                }
                val hasUnknown = (0 until matrix.dimension).any { y ->
                    (0 until matrix.dimension).any { x -> matrix[x, y].value == ModuleValue.ERASED }
                }
                runOnUiThread {
                    statusText.text = if (hasUnknown) {
                        ui("PNGを保存しました。未確定の?は診断用グレーで出力されます。", "PNG saved. Unresolved ? cells are shown in diagnostic grey.")
                    } else {
                        ui("修復結果のPNGを保存しました。", "Repair result PNG saved.")
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { statusText.text = ui("PNG保存失敗: ${t.message}", "PNG save failed: ${t.message}") }
            }
        }
    }

    private fun renderMatrixPng(matrix: QrMatrix): Bitmap {
        val quiet = 4
        val scale = 10
        val size = (matrix.dimension + quiet * 2) * scale
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        fun fillModule(mx: Int, my: Int, color: Int) {
            val sx = (mx + quiet) * scale
            val sy = (my + quiet) * scale
            for (y in sy until sy + scale) for (x in sx until sx + scale) pixels[y * size + x] = color
        }
        for (y in 0 until matrix.dimension) for (x in 0 until matrix.dimension) {
            val color = when (matrix[x, y].value) {
                ModuleValue.ONE -> 0xFF000000.toInt()
                ModuleValue.ZERO -> 0xFFFFFFFF.toInt()
                ModuleValue.ERASED -> 0xFFB0B0B0.toInt()
            }
            fillModule(x, y, color)
        }
        out.setPixels(pixels, 0, size, 0, 0, size, size)
        return out
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isJapaneseUi(): Boolean =
        AppLanguageManager.isJapanese

    private fun ui(japanese: String, english: String): String =
        localizedText(japanese, english)
}

/** Labels describe source-image luminance; slider values remain in logical polarity. */
internal fun polarityThresholdLabels(inverted: Boolean, blackMax: Int, whiteMin: Int, japanese: Boolean = true): Pair<String, String> =
    if (inverted) {
        if (japanese) "画像の明部（QRの黒(1)）: ${255 - blackMax}以上" to "画像の暗部（QRの白(0)）: ${255 - whiteMin}以下"
        else "Light pixels (QR black (1)): ≥ ${255 - blackMax}" to "Dark pixels (QR white (0)): ≤ ${255 - whiteMin}"
    } else {
        if (japanese) "画像の暗部（QRの黒(1)）: ${blackMax}以下" to "画像の明部（QRの白(0)）: ${whiteMin}以上"
        else "Dark pixels (QR black (1)): ≤ $blackMax" to "Light pixels (QR white (0)): ≥ $whiteMin"
    }
