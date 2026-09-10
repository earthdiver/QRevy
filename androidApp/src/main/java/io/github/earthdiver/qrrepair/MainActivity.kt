package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.ModuleSource

import android.app.Activity
import android.app.AlertDialog
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
import io.github.earthdiver.qrrepair.core.AutoRecoveryEngine
import io.github.earthdiver.qrrepair.core.AutoRecoveryResult
import io.github.earthdiver.qrrepair.core.CodewordHex
import io.github.earthdiver.qrrepair.core.CodewordExtractor
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FinderRole
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
import io.github.earthdiver.qrrepair.core.FormatEditPolicy
import io.github.earthdiver.qrrepair.core.FormatEditConflict
import io.github.earthdiver.qrrepair.core.QrTextMatrixParser
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.description
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.RecoveryFailureReason
import io.github.earthdiver.qrrepair.core.RecoveryEngine
import io.github.earthdiver.qrrepair.core.RecoveryCancellation
import io.github.earthdiver.qrrepair.core.RecoveryCancelledException
import io.github.earthdiver.qrrepair.core.geometryAttemptComparator
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySessionHistory
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.historyCellChangeCount
import io.github.earthdiver.qrrepair.core.InitialThresholdEstimator
import io.github.earthdiver.qrrepair.core.QrContentPreviewParser
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.core.RepairGuidance
import io.github.earthdiver.qrrepair.core.RepairTraceBuilder
import io.github.earthdiver.qrrepair.core.ThresholdMode
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.RepairPreviewView
import io.github.earthdiver.qrrepair.ui.CropOverlayView
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import io.github.earthdiver.qrrepair.session.RecoverySessionCodec
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
    cacheDir.listFiles()
        ?.filter {
            it.isFile &&
                it.name.startsWith("qr-capture-") &&
                it.name.endsWith(".jpg", ignoreCase = true) &&
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
    private data class RetainedActivityState(val style: AppStyle, val history: RetainedCellHistory?)

    private var immediateResultShown = false
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
    private lateinit var diagnosticDetailsText: TextView
    private var diagnosticDetailsExpanded = false
    private lateinit var mainResultSelector: LinearLayout


    private val executor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val workGeneration = AtomicInteger(0)
    private val saveGeneration = AtomicInteger(0)
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
    private var preprocessSettings = PreprocessSettings()
    // In-memory checkpoint for the active work only. Dialogs edit independent copies.
    private var savedCellEditState: PreRecoveryCellEditState? = null
    private data class RetainedCellHistory(
        val sessionId: String,
        val settings: PreprocessSettings,
        val state: PreRecoveryCellEditState,
    )
    private var pendingRetainedCellHistory: RetainedCellHistory? = null
    private val recoveryState = RecoveryUiState()

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
        val restoredSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
        if (restoredSessionId != null && RecoverySessionCodec.isSafeSessionId(restoredSessionId)) {
            pendingRetainedCellHistory = retained?.history
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
        return RetainedActivityState(activeStyle, history)
    }

    private fun purgeStaleCameraCaptures() {
        runCatching { purgeStaleCameraCaptures(cacheDir) }
    }

    override fun onDestroy() {
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

    private fun buildUi(): View = if (modernStyle) buildModernUi() else buildClassicUi()

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
        root.addView(compactRow(
            button(getString(R.string.show_result)) { showZoomableResult() },
            button(getString(R.string.data)) { showCodewordHex() },
            button(getString(R.string.save_png)) { startSavePng() },
        ))
        val resultContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainResultSelector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        statusText = TextView(this).apply {
            text = getString(R.string.select_image_prompt)
            textSize = 14f
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        resultContent.addView(statusText)

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

        diagnosticDetailsText = TextView(this).apply {
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setTextIsSelectable(true)
            setBackgroundColor(0xFFF4F4F4.toInt())
            visibility = View.GONE
        }
        resultContent.addView(diagnosticDetailsText)

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
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PrecisionStyle.BACKGROUND)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        modernHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.app_name), 28f).apply {
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
            addView(label(ui("QRコードの救世主", "A lifesaver for QR codes"), 30f).apply {
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
        statusText = label(getString(R.string.select_image_prompt), 16f).apply { setTextColor(PrecisionStyle.TEXT) }
        guideText = label(getString(R.string.initial_guide), 13f)

        contentPreviewText = label(getString(R.string.no_content_preview)).apply {
            setTextColor(PrecisionStyle.TEXT)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            PrecisionStyle.panel(this)
        }
        // Content comes first when a result exists; navigation and the page title remain above it.
        workspacePanel.addView(contentPreviewText, workspacePanel.indexOfChild(workspacePreview))
        workspacePanel.addView(mainResultSelector)
        workspacePanel.addView(statusText)
        workspacePanel.addView(guideText)
        workspacePanel.addView(button(getString(R.string.show_detailed_result)) { showZoomableResult() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        diagnosticToggle = button(getString(R.string.show_diagnostic_details)) {
            diagnosticDetailsExpanded = !diagnosticDetailsExpanded
            refreshDiagnosticDetailsVisibility()
        }.apply { visibility = View.GONE }
        workspacePanel.addView(diagnosticToggle)
        diagnosticDetailsText = label("", 12f).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
            PrecisionStyle.panel(this)
            visibility = View.GONE
        }
        workspacePanel.addView(diagnosticDetailsText)
        workspacePanel.addView(action(getString(R.string.save_png)) { startSavePng() })
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
            showWorkspace()
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
            showWorkspace()
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

    private fun setDiagnosticDetails(text: String?) {
        if (!::diagnosticToggle.isInitialized || !::diagnosticDetailsText.isInitialized) return
        diagnosticDetailsText.text = text.orEmpty()
        diagnosticToggle.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        refreshDiagnosticDetailsVisibility()
    }

    private fun refreshDiagnosticDetailsVisibility() {
        if (!::diagnosticToggle.isInitialized || !::diagnosticDetailsText.isInitialized) return
        val hasDetails = diagnosticDetailsText.text.isNotBlank()
        diagnosticToggle.text = getString(
            if (diagnosticDetailsExpanded) R.string.hide_diagnostic_details
            else R.string.show_diagnostic_details,
        )
        diagnosticDetailsText.visibility = if (hasDetails && diagnosticDetailsExpanded) View.VISIBLE else View.GONE
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
        val current = AppLanguageManager.selected(this)
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

    private fun showZoomableResult() {
        val before = recoveryState.sourceMatrix ?: run { Toast.makeText(this, ui("表示できる結果がありません。", "No result is available."), Toast.LENGTH_SHORT).show(); return }
        var after = recoveryState.inspectorMatrix ?: recoveryState.workingMatrix ?: return
        val detail = TextView(this).apply {
            text = ui("セルをタップ：セルの修復理由を表示", "Tap a cell to view its repair reason")
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextIsSelectable(true)
        }
        lateinit var codewordButton: Button
        var selectedCoord: Coord? = null
        var revealEvidence: () -> Unit = {}
        val view = QrResultInspectorView(this).apply {
            setDocument(before, after, recoveryState.traces)
            setComparisonEnabled(false)
            onCellSelected = { x, y ->
                selectedCoord = Coord(x, y)
                revealEvidence()
                detail.text = resultCellDetailText(x, y, after)
                codewordButton.isEnabled = findCodewordAt(x, y) != null
                clearHighlightedCodeword()
            }
        }
        val displayedResults = displayedDecodedRecoveryEntries()
        val candidateTabs = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            displayedResults.forEachIndexed { resultIndex, (candidateIndex, _) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    text = getString(
                        R.string.result_tab,
                        resultIndex + 1,
                    )
                    isChecked = candidateIndex == recoveryState.recoveryIndex
                    setPadding(dp(10), 0, dp(10), 0)
                    setOnClickListener {
                        if (candidateIndex == recoveryState.recoveryIndex) return@setOnClickListener
                        selectRecoveryCandidate(candidateIndex, recordSelection = true)
                        after = recoveryState.inspectorMatrix ?: recoveryState.workingMatrix ?: return@setOnClickListener
                        selectedCoord = null
                        detail.text = ui("セルをタップ：セルの修復理由を表示", "Tap a cell to view its repair reason")
                        codewordButton.isEnabled = false
                        view.setDocument(before, after, recoveryState.traces)
                        view.clearHighlightedCodeword()
                    }
                })
            }
        }
        codewordButton = button(ui("同じコード語を表示", "Show same codeword")) {
            val coord = selectedCoord ?: return@button
            val found = findCodewordAt(coord.x, coord.y) ?: return@button
            val partial = recoveryState.selectedRecovery?.extraction?.codewords?.getOrNull(found.first) ?: return@button
            view.setHighlightedCodeword(partial.coordinates)
            detail.text = resultCellDetailText(coord.x, coord.y, after) + "\n" + codewordDetailText(found.first, found.second)
        }.apply { isEnabled = false }
        val detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (modernStyle) {
                PrecisionStyle.panel(this)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(TextView(this@MainActivity).apply {
                    text = ui("セルの修復根拠", "Cell repair evidence")
                    setTextColor(PrecisionStyle.ACCENT)
                    textSize = 16f
                    setPadding(dp(12), 0, dp(12), dp(4))
                })
                addView(ScrollView(this@MainActivity).apply { addView(detail) },
                    LinearLayout.LayoutParams(-1, dp(128)))
            } else {
                setBackgroundColor(0xFFF4F4F4.toInt())
                addView(detail)
            }
            addView(codewordButton)
        }
        val evidenceToggle = button(getString(R.string.show_evidence)) {
            detailPanel.visibility = if (detailPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        if (modernStyle) detailPanel.visibility = View.GONE
        revealEvidence = { if (modernStyle) detailPanel.visibility = View.VISIBLE }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = ui(
                    "セル: 黒=1  白=0  灰色×=未確定（修復後も値が定まらないセル）\n枠: 緑=固定構造  黄土色=形式情報  橙=RS  紫=制約  赤紫=ビット候補探索  青=ユーザー編集",
                    "Cells: black=1, white=0, grey ×=unresolved after repair\nBorders: green=fixed structure, ochre=format information, orange=RS, purple=constraint, magenta=bit candidate search, blue=user edit",
                )
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            })
            if (displayedResults.size > 1) addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(candidateTabs)
            })
            if (!modernStyle) addView(detailPanel)
            addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            if (modernStyle) {
                addView(evidenceToggle)
                addView(detailPanel)
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(ui("修復結果（ピンチで拡大）", "Repair result (pinch to zoom)"))
            .setView(content)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * .9f).toInt(),
            )
        }
        dialog.show()
    }

    private fun selectRecoveryCandidate(index: Int, recordSelection: Boolean) {
        val candidate = recoveryState.recoveries.getOrNull(index) ?: return
        recoveryState.selectRecovery(index)
        refreshInspector()
        updateSummary()
        if (recordSelection) recordHistory(
            HistoryEventKind.CANDIDATE,
            ui("候補${displayedDecodedRecoveryEntries().indexOfFirst { it.first == index } + 1}を選択", "Selected candidate ${displayedDecodedRecoveryEntries().indexOfFirst { it.first == index } + 1}"),
            "score=${candidate.score}, ECC=${candidate.ecLevel}, mask=${candidate.dataMask}",
        )
    }

    private fun decodedRecoveryEntries(): List<Pair<Int, RecoveryCandidate>> =
        recoveryState.recoveries.mapIndexedNotNull { index, candidate -> (index to candidate).takeIf { candidate.payloadParsed } }

    private fun displayedDecodedRecoveryEntries(): List<Pair<Int, RecoveryCandidate>> =
        decodedRecoveryEntries().take(MAX_DISPLAYED_RECOVERY_CANDIDATES)

    private fun candidateSelectionLabel(total: Int): String =
        if (total > MAX_DISPLAYED_RECOVERY_CANDIDATES) {
            getString(R.string.select_result_truncated, MAX_DISPLAYED_RECOVERY_CANDIDATES, total)
        } else {
            getString(R.string.select_result_limited, MAX_DISPLAYED_RECOVERY_CANDIDATES)
        }

    private fun refreshMainResultSelector() {
        if (!::mainResultSelector.isInitialized) return
        val allResults = decodedRecoveryEntries()
        val results = allResults.take(MAX_DISPLAYED_RECOVERY_CANDIDATES)
        mainResultSelector.removeAllViews()
        mainResultSelector.visibility = if (results.size > 1) View.VISIBLE else View.GONE
        if (results.size <= 1) return
        mainResultSelector.addView(TextView(this).apply {
            text = candidateSelectionLabel(allResults.size)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), 0)
        })
        mainResultSelector.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(resultRadioGroup(results) { renderMainResultSelection() })
        })
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

    private fun resultCellDetailText(x: Int, y: Int, matrix: QrMatrix): String {
        val coord = Coord(x, y)
        val trace = recoveryState.traces[coord]
        val confidence = recoveryState.confidenceMap[coord]
        if (io.github.earthdiver.qrrepair.core.RepairTraceBuilder.isFormat(x, y, matrix.dimension)) {
            return io.github.earthdiver.qrrepair.core.formatCellDescription(x, y,
                trace?.originalValue ?: recoveryState.sourceMatrix?.get(x, y)?.value ?: matrix[x, y].value,
                matrix[x, y].value, recoveryState.selectedRecovery, matrix[x, y].source == ModuleSource.USER)
        }
        if (trace != null) return buildString {
            append(trace.explanationJa())
            confidence?.let { append(ui("\n相対信頼度（確率ではありません）: ${it.labelJa} (${"%.2f".format(it.score)}) — ${it.detailJa}", "\nRelative confidence (not a probability): ${it.labelEn} (${"%.2f".format(it.score)}) — ${it.detailEn}")) }
        }
        val cell = matrix[x, y]
        val value = when (cell.value) {
            ModuleValue.ZERO -> "0"
            ModuleValue.ONE -> "1"
            ModuleValue.ERASED -> "?"
        }
        return ui(
            "セル ($x,$y) = $value / source=${cell.source}\nこのセルは現在の候補では変更されていません。",
            "Cell ($x,$y) = $value / source=${cell.source}\nThis cell is unchanged in the current candidate.",
        )
    }

    private fun codewordDetailText(codewordIndex: Int, bitIndex: Int): String {
        val candidate = recoveryState.selectedRecovery ?: return ""
        val partial = candidate.extraction.codewords.getOrNull(codewordIndex) ?: return ""
        val info = candidate.repairInfo.getOrNull(codewordIndex)
        val corrected = candidate.correctedInterleaved.getOrNull(codewordIndex)
        val beforeBits = buildString {
            for (bit in 7 downTo 0) append(
                if ((partial.unknownBits and (1 shl bit)) != 0) '?' else if ((partial.value and (1 shl bit)) != 0) '1' else '0',
            )
        }
        return buildString {
            append(ui("Codeword #$codewordIndex / 選択bit $bitIndex", "Codeword #$codewordIndex / selected bit $bitIndex"))
            info?.let { append(" / RS block ${it.blockIndex} local ${it.localIndex}") }
            append("\n$beforeBits → ${corrected?.toString(2)?.padStart(8, '0') ?: "????????"}")
            if (info?.partialEnumerated == true) append(ui("\nbit-level限定列挙の対象", "\nIncluded in limited bit-level enumeration"))
            if (info?.rsErrata == true) append(ui("\nRS errataとして訂正", "\nCorrected as RS errata"))
        }
    }

    private fun showCodewordHex() {
        val attemptedCandidate = recoveryState.selectedRecovery
        val failureData = if (attemptedCandidate == null && recoveryState.failure != null && recoveryState.workingMatrix != null) {
            val matrix = recoveryState.workingMatrix!!
            val failure = recoveryState.failure!!
            val version = (matrix.dimension - 17) / 4
            runCatching {
                SavedRawData(matrix, CodewordExtractor.extract(matrix, version, failure.dataMask), version, failure.dataMask, failure.ecLevel)
            }.getOrNull()
        } else null
        val savedData = when {
            recoveryState.failure != null -> failureData
            attemptedCandidate == null -> savedPreRecoveryData()
            else -> null
        }
        val matrix = recoveryState.workingMatrix?.takeIf { attemptedCandidate != null } ?: savedData?.matrix
            ?: run { Toast.makeText(this, ui("表示できるデータがありません。", "No data is available."), Toast.LENGTH_SHORT).show(); return }
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val displayState = RecoveryDataDisplayState(repairedStage = recoveryState.selectedRecovery?.payloadParsed == true)
        fun render() {
            val candidate = recoveryState.selectedRecovery
            val validatedCandidate = candidate?.takeIf { it.payloadParsed }
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

    private fun savedPreRecoveryData(): SavedRawData? {
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
        val profile = preprocessSettings.variants().firstOrNull()
        val matrix = MatrixSampler(prepared.luma, profile).sample(geometry).first
        preprocessSettings.preRecoveryEdits.forEach { (coord, value) ->
            if (coord.x in 0 until matrix.dimension && coord.y in 0 until matrix.dimension) {
                matrix.set(coord.x, coord.y, value, 1f, preprocessSettings.sourceForPreRecoveryEdit(value))
                matrix.setPrioritySearch(coord.x, coord.y, coord in preprocessSettings.preRecoveryPrioritySearch)
            }
        }
        val format = FormatSolver.ranked(matrix).firstOrNull() ?: return null
        val extraction = runCatching { CodewordExtractor.extract(matrix, version, format.dataMask) }.getOrNull() ?: return null
        return SavedRawData(matrix, extraction, version, format.dataMask, format.ecLevel)
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
    )

    private fun preparedFor(settings: PreprocessSettings): BitmapUtils.Prepared? {
        val source = prepared ?: return null
        val rotated = if (settings.rotationDegrees % 360f == 0f) source
        else BitmapUtils.rotate(source.bitmap, settings.rotationDegrees)
        val quad = settings.perspectiveCorrectionQuad ?: return rotated
        val rectified = runCatching {
            BitmapUtils.rectify(rotated.bitmap, quad, settings.version, quietModules = 1.0).prepared
        }.getOrNull() ?: return rotated
        if (rotated !== source && rotated.bitmap !== rectified.bitmap) rotated.bitmap.recycle()
        return rectified
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
                snapshot = RecoverySnapshot(null, null, null, -1, preprocessSettings),
            ),
        )
        persistSessionAsync()
    }

    private fun persistSessionAsync() {
        val id = sessionId ?: return
        if (sessionHistory.entries.isEmpty()) return
        val entries = sessionHistory.entries.toList()
        val cursor = sessionHistory.cursor
        val mode = UiMode.SIMPLE
        val token = saveGeneration.incrementAndGet()
        ioExecutor.execute {
            if (token != saveGeneration.get()) return@execute
            try {
                sessionStore.save(id, mode, entries, cursor)
            } catch (t: Throwable) {
                reportPersistenceFailure(ui("セッションの自動保存に失敗", "Automatic session save failed"), t)
            }
        }
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
        val snapshot = entry.snapshot
        savedCellEditState = null
        preprocessSettings = snapshot.preprocessSettings ?: PreprocessSettings()
        val matrix = snapshot.workingMatrix?.copy()
        val geometry = snapshot.geometry
        val p = preparedFor(preprocessSettings)
        analysisPrepared = p
        val restoredManual = matrix?.let(FormatEditPolicy::manualValues).orEmpty() + preprocessSettings.preRecoveryEdits
        FormatEditPolicy.conflict(preprocessSettings.version, preprocessSettings.formatConstraint, restoredManual)?.let {
            workGeneration.incrementAndGet()
            historyRestoreToken = null
            if (matrix != null && geometry != null) {
                recoveryState.restoreAttempt(geometry, snapshot.sourceMatrix?.copy() ?: matrix.copy(), matrix)
            } else recoveryState.clear()
            refreshMainResultSelector()
            contentPreviewText.text = getString(R.string.no_content_preview)
            setDiagnosticDetails(null)
            statusText.text = ui("作業は読み込みました。形式情報の指定を修正してから修復してください。", "Work loaded. Correct the format selections before repairing.")
            guideText.text = it.message(changeRejected = false)
            onRestored?.invoke()
            showFormatEditError(it, changeRejected = false)
            return
        }
        if (matrix == null || geometry == null) {
            workGeneration.incrementAndGet()
            historyRestoreToken = null
            recoveryState.clear()
            contentPreviewText.text = getString(R.string.no_content_preview)
            setDiagnosticDetails(null)
            refreshMainResultSelector()
            if (p != null) {
                guideText.text = ui("案内: 元画像の状態です。「修復する」から設定を確認して修復してください。", "Guide: This is the source image. Review settings under “Repair” to continue.")
                statusText.text = ui("$prefix。元画像へ戻りました。", "$prefix. Returned to the source image.")
            } else {
                guideText.text = ui("案内: 元画像を検証できないため、この履歴点の画像設定は利用できません。保存済み行列のある履歴点を選択してください。", "Guide: The source image could not be verified. Select a history entry that contains a saved matrix.")
                statusText.text = ui("$prefix。履歴は読み込みましたが、元画像は利用できません。", "$prefix. History was loaded, but the source image is unavailable.")
            }
            onRestored?.invoke()
            return
        }
        val source = snapshot.sourceMatrix?.copy() ?: matrix.copy()
        val requestSettings = preprocessSettings.recoverySnapshot()
        val token = workGeneration.incrementAndGet()
        historyRestoreToken = token
        // A restored snapshot represents one concrete saved geometry even though auto recovery is not persisted.
        recoveryState.restoreAttempt(geometry, source, matrix)
        statusText.text = ui("$prefix。復号候補を再計算中…", "$prefix. Recalculating decode candidates…")
        executor.execute {
            if (token != workGeneration.get()) return@execute
            val ranked = try { recoveryEngine.recover(
                matrix.copy(),
                trustKnownPayloadCount = requestSettings.trustKnownPayloadCount,
                formatConstraint = requestSettings.formatConstraint,
                maxFormatMismatches = requestSettings.formatMismatchLimit,
                maxKnownDataEccMismatches = requestSettings.dataEccMismatchLimit,
                manualEdits = requestSettings.preRecoveryEdits,
                cancellation = RecoveryCancellation { token != workGeneration.get() || Thread.currentThread().isInterrupted },
            ) } catch (_: RecoveryCancelledException) { return@execute }
            val failure = recoveryEngine.lastFailure
            runOnUiThread {
                if (token != workGeneration.get()) {
                    if (historyRestoreToken == token) historyRestoreToken = null
                    return@runOnUiThread
                }
                recoveryState.updateRecoveries(
                    ranked,
                    preferredRecoveryIndex(ranked, snapshot.recoveryIndex),
                    failure,
                )
                if (historyRestoreToken == token) historyRestoreToken = null
                refreshInspector()
                updateSummary(prefix)
                updateContentPreview()
                onRestored?.invoke()
            }
        }
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
                val dialog = AlertDialog.Builder(this)
                    // AlertDialog does not attach its ListView when both a message and setItems
                    // are supplied. Keep the guidance in the title so the saved sessions remain
                    // visible and tappable.
                    .setTitle(if (modernStyle) getString(R.string.saved_work) + " (${recent.size}/${RecoverySessionStore.MAX_SAVED_SESSIONS})" else getString(R.string.recent_work_select_format, recent.size, RecoverySessionStore.MAX_SAVED_SESSIONS))
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

    private fun updateContentPreview() {
        immediateResultShown = false
        showWorkspace()
        val candidate = recoveryState.selectedRecovery
        val preview = QrContentPreviewParser.parse(candidate)
        val geometry = recoveryState.geometry
        when {
            candidate != null -> {
                contentPreviewText.text = buildString {
                    if (preview != null) append(preview.toDisplayText(maskSensitive = true))
                    else append(ui("内容: 復号できませんでした。", "Content: Could not be decoded."))
                    append(ui("\n\n誤り訂正レベル: ", "\n\nError correction level: ")).append(candidate.ecLevel)
                    append(ui(" / マスク: ", " / Mask: ")).append(candidate.dataMask)
                    append(ui("\n形式情報の誤り: ", "\nFormat information errors: "))
                        .append(candidate.formatMismatches).append(ui("bit / 既知", " bits / "))
                        .append(candidate.formatMatches + candidate.formatMismatches).append(ui("bit", " known bits"))
                    append(ui("\nデータ・ECCの誤り: ", "\nData and ECC errors: "))
                        .append(candidate.knownBitMismatchesAfterCorrection).append(ui("bit / 既知", " bits / "))
                        .append(candidate.knownBitsCompared).append(ui("bit", " known bits"))
                    if (preview?.kind == io.github.earthdiver.qrrepair.core.QrContentKind.URL) {
                        append(ui("\nURLは安全のため自動では開きません。", "\nURLs are not opened automatically for safety."))
                    }
                }
                setDiagnosticDetails(candidateDiagnosticText(candidate, geometry))
            }
            recoveryState.failure != null -> {
                val failure = recoveryState.failure!!
                contentPreviewText.text = buildString {
                    append(ui("最良の失敗試行", "Best failed attempt"))
                    append(ui("\n誤り訂正レベル: ", "\nError correction level: ")).append(failure.ecLevel)
                    append(ui(" / マスク: ", " / Mask: ")).append(failure.dataMask)
                    append(ui("\n形式情報の誤り: ", "\nFormat information errors: "))
                        .append(failure.formatMismatches).append(ui("bit / 既知", " bits / "))
                        .append(failure.formatKnownBits).append(ui("bit", " known bits"))
                    failure.formatMismatchLimit?.let {
                        append(ui("\n形式情報の指定上限: ${it}bit", "\nSpecified format limit: $it bits"))
                    }
                    if (failure.knownBitMismatches != null && failure.knownBitsCompared != null) {
                        append(ui("\nデータ・ECCの誤り: ", "\nData and ECC errors: "))
                            .append(failure.knownBitMismatches).append(ui("bit / 既知", " bits / "))
                            .append(failure.knownBitsCompared).append(ui("bit", " known bits"))
                        failure.knownBitMismatchLimit?.let {
                            append(ui("\n指定された上限: ${it}bit", "\nSpecified limit: $it bits"))
                        }
                    }
                    append(ui("\n理由: ", "\nReason: ")).append(recoveryFailureReasonText(failure))
                }
                setDiagnosticDetails(failureDiagnosticText(failure, geometry))
            }
            else -> {
                contentPreviewText.text = getString(R.string.no_content_preview)
                setDiagnosticDetails(null)
            }
        }
    }

    private fun candidateDiagnosticText(candidate: RecoveryCandidate, geometry: GridGeometry?): String = buildString {
        geometry?.let { append(geometryDiagnosticText(it)).append('\n') }
        append(ui("消失コード語: 修復時 ", "Erased codewords: at recovery "))
            .append(candidate.erasedCodewords)
            .append(ui(" / 入力時 ", " / at input "))
            .append(candidate.originalErasedCodewords)
        append(ui("\nRS訂正シンボル: ", "\nRS corrected symbols: ")).append(candidate.correctedErrataSymbols)
        if (candidate.repairInfo.any { it.bitConstraintSolved }) {
            append(ui("\nRS余裕: 算定対象外（部分bit制約を使用）", "\nRS margin: not applicable (partial-bit constraints used)"))
        } else append(ui(" / RS余裕: ", " / RS margin: ")).append(candidate.minRsMargin)
        if (candidate.constraintFilledBits > 0) {
            append(ui("\n構造制約で確定: ", "\nFilled by structure constraints: "))
                .append(candidate.constraintFilledBits).append(ui("bit", " bits"))
            if (candidate.constraintForcedByUserSetting) {
                append(ui("（ユーザ指定による強制、既知構造との一致: ", " (forced by user setting, known-structure matches: "))
            } else {
                append(ui("（既知構造との一致: ", " (known-structure matches: "))
            }
            append(candidate.constraintEvidenceBits).append(ui("bit）", " bits)"))
        }
        if (candidate.priorityGuessedBits > 0) {
            append(ui("\n優先0/1探索: ", "\nPriority 0/1 search: "))
                .append(candidate.priorityGuessedBits).append(ui("bit（元入力では不明）", " bits (unknown in the input)"))
        }
        if (candidate.partialGuessedBits > 0) {
            append(ui("\n部分bit探索: ", "\nPartial-bit search: ")).append(candidate.partialGuessedBits).append(ui("bit", " bits"))
        }
        if (candidate.combinationSearch.passes > 0) append('\n').append(candidate.combinationSearch.description())
        candidate.rsBitDiagnostic?.let { append('\n').append(it.description()) }
        val qrRoutes = listOf(candidate) + candidate.alternativeDerivations
        qrRoutes.forEachIndexed { qrIndex, route ->
            route.blockDerivations.forEach { (block, derivations) ->
                append(ui("\n経路${qrIndex + 1}・RS block $block: 同じ訂正値への${derivations.size}経路（代表は1）",
                    "\nRoute ${qrIndex + 1}, RS block $block: ${derivations.size} derivations of the same values (representative: 1)"))
                derivations.forEachIndexed { index, derivation ->
                    append("\n  ${index + 1}: ").append(derivation.description())
                }
            }
        }
        if (candidate.alternativeDerivations.isNotEmpty()) {
            val routes = listOf(candidate) + candidate.alternativeDerivations
            append(ui("\n同じ復元結果への探索経路: ${routes.size}件（セルの理由は経路1）", "\nDerivations of the same QR: ${routes.size} (cell reasons use route 1)"))
            routes.forEachIndexed { index, route ->
                append(ui("\n経路${index + 1}: 構造制約 ${route.constraintFilledBits}bit / 優先探索 ${route.priorityGuessedBits}bit / 部分bit探索 ${route.partialGuessedBits}bit",
                    "\nRoute ${index + 1}: structure ${route.constraintFilledBits} bits / priority ${route.priorityGuessedBits} bits / partial-bit search ${route.partialGuessedBits} bits"))
            }
        }
    }.trimEnd()

    private fun failureDiagnosticText(failure: RecoveryFailure, geometry: GridGeometry?): String = buildString {
        geometry?.let { append(geometryDiagnosticText(it)) }
        if (failure.failedBlockIndex != null) {
            if (isNotEmpty()) append('\n')
            append(ui("最初に失敗したRSブロック: ", "First failing RS block: ")).append(failure.failedBlockIndex + 1)
            failure.rsBlockCount?.let { append(ui(" / 全${it}ブロック", " / $it total")) }
            failure.ecSymbolsPerBlock?.let {
                append(ui("\nブロックあたりECCシンボル: ", "\nECC symbols per block: ")).append(it)
            }
        }
        if (failure.originalErasedCodewords > 0 || failure.erasedCodewords > 0) {
            if (isNotEmpty()) append('\n')
            append(ui("消失コード語: 修復時 ", "Erased codewords: at recovery "))
                .append(failure.erasedCodewords)
                .append(ui(" / 入力時 ", " / at input "))
                .append(failure.originalErasedCodewords)
        }
        if (failure.combinationSearch.passes > 0) {
            if (isNotEmpty()) append('\n')
            append(failure.combinationSearch.description())
        }
        failure.rsBitDiagnostic?.let {
            if (isNotEmpty()) append('\n')
            append(it.description())
        }
    }.trimEnd()

    private fun geometryDiagnosticText(geometry: GridGeometry): String {
        val model = when (geometry.model) {
            GeometryModel.SIMILARITY -> ui("相似変換", "Similarity")
            GeometryModel.TWO_FINDER -> ui("2 Finder", "Two Finder patterns")
            GeometryModel.AFFINE -> ui("アフィン変換", "Affine")
            GeometryModel.PROJECTIVE -> ui("射影変換", "Projective")
            GeometryModel.MANUAL_PROJECTIVE -> ui("手動射影", "Manual projective")
        }
        val role = when (geometry.role) {
            FinderRole.TOP_LEFT -> ui("左上基準", "top-left reference")
            FinderRole.TOP_RIGHT -> ui("右上基準", "top-right reference")
            FinderRole.BOTTOM_LEFT -> ui("左下基準", "bottom-left reference")
        }
        return ui("幾何: $model / V${geometry.version} / $role", "Geometry: $model / V${geometry.version} / $role")
    }

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
                loadImage(Uri.fromFile(captured), cleanupFile = captured)
            }
        }
    }

    private fun loadImage(uri: Uri, cleanupFile: File? = null) {
        val token = workGeneration.incrementAndGet()
        statusText.text = ui("画像を読み込み中…", "Loading image…")
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
                    } else if (cleanupFile == null) showImportedImageAdjustment(bitmap)
                    else acceptDecodedImage(bitmap, cleanupFile)
                }
            } catch (t: Throwable) {
                if (token == workGeneration.get()) runOnUiThread {
                    statusText.text = ui("画像の読み込みに失敗: ${t.message ?: t.javaClass.simpleName}", "Image load failed: ${t.message ?: t.javaClass.simpleName}")
                }
                cleanupFile?.let { runCatching { it.delete() } }
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
        showWorkspace()
        val token = workGeneration.incrementAndGet()
        statusText.text = ui("テキスト行列を読み込み中…", "Loading text matrix…")
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
                val quad = io.github.earthdiver.qrrepair.core.ImageQuad(
                    io.github.earthdiver.qrrepair.core.PointD(center, center),
                    io.github.earthdiver.qrrepair.core.PointD(last, center),
                    io.github.earthdiver.qrrepair.core.PointD(last, last),
                    io.github.earthdiver.qrrepair.core.PointD(center, last),
                )
                val priority = parsed.matrix.prioritySearchCoordinates()
                    .mapTo(linkedSetOf()) { io.github.earthdiver.qrrepair.core.Coord(it.first, it.second) }
                val settings = PreprocessSettings(
                    polarity = PolarityMode.NORMAL,
                    thresholdMode = ThresholdMode.GLOBAL,
                    blackMax = 64,
                    whiteMin = 192,
                    quietZoneCorrection = false,
                    version = parsed.version,
                    lockCorners = true,
                    lockVersion = true,
                    manualQuad = quad,
                    logicalGridAssigned = true,
                    preRecoveryEdits = priority.associateWith { ModuleValue.ERASED },
                    preRecoveryPrioritySearch = priority,
                )
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
                            detail = ui("V${parsed.version} / ${parsed.matrix.dimension}×${parsed.matrix.dimension}", "Version ${parsed.version} / ${parsed.matrix.dimension}×${parsed.matrix.dimension}"),
                            snapshot = RecoverySnapshot(null, null, null, -1, settings),
                        ),
                    )
                    persistSessionAsync()
                    guideText.text = ui(
                        "案内: テキスト行列から作成した疑似画像です。必要なら修復前設定でセルを編集してください。",
                        "Guide: This synthetic image was created from the text matrix. Edit cells in pre-repair settings if needed.",
                    )
                    contentPreviewText.text = getString(R.string.no_content_preview)
                    statusText.text = ui(
                        "テキスト行列を読み込みました（V${parsed.version}、${priority.size}個の*）。",
                        "Text matrix imported (Version ${parsed.version}, ${priority.size} * cell(s)).",
                    )
                    if (standardResult != null) showImmediateQrResult(standardResult) else showPreprocessDialog()
                }
                bitmap = null // ownership moved to Prepared/UI
            } catch (t: Throwable) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                if (token == workGeneration.get()) runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(ui("テキスト行列を読み込めません", "Could not import text matrix"))
                        .setMessage(t.message ?: t.javaClass.simpleName)
                        .setPositiveButton(R.string.close, null)
                        .show()
                    statusText.text = ui("テキスト行列の読み込みに失敗しました。", "Text matrix import failed.")
                }
            }
        }
    }

    private fun showImportedImageAdjustment(initialBitmap: Bitmap) {
        var bitmap = initialBitmap
        var handedOff = false
        val cropView = CropOverlayView(this).apply { setBitmap(bitmap) }
        val adjustmentStatus = TextView(this).apply {
            text = ui("解析する範囲を指定してください。角・辺をドラッグ、枠内をドラッグで移動できます。", "Select the area to analyze. Drag corners or edges to resize, or drag inside to move.")
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        fun showTransformFailure(error: Throwable) {
            adjustmentStatus.text = ui(
                "画像変換に失敗: ${error.message ?: error.javaClass.simpleName}",
                "Image transform failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        fun rotateBy(degrees: Float) {
            replaceOwnedBitmap(bitmap) { BitmapUtils.rotate(it, degrees).bitmap }
                .onSuccess { rotated ->
                    bitmap = rotated
                    cropView.setBitmap(rotated)
                }
                .onFailure(::showTransformFailure)
        }
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(adjustmentStatus)
            addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(scrollRow(
                button(getString(R.string.rotate_left_90)) { rotateBy(-90f) },
                button("-1°") { rotateBy(-1f) },
                button("-0.1°") { rotateBy(-.1f) },
                button("+0.1°") { rotateBy(.1f) },
                button("+1°") { rotateBy(1f) },
                button(getString(R.string.rotate_right_90)) { rotateBy(90f) },
            ))
            addView(compactRow(
                button(getString(R.string.cancel)) { dialog.dismiss() },
                button(getString(R.string.crop_and_analyze)) {
                    val source = bitmap
                    replaceOwnedBitmap(source) {
                        createIndependentCrop(it, cropView.cropRect()).getOrThrow()
                    }.onSuccess { cropped ->
                        bitmap = cropped
                        handedOff = true
                        dialog.dismiss()
                        acceptDecodedImage(cropped)
                    }.onFailure(::showTransformFailure)
                },
            ))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(ui("画像の回転・クロップ", "Rotate and crop image"))
            .setView(content)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * .98f).toInt(),
                (resources.displayMetrics.heightPixels * .92f).toInt(),
            )
        }
        dialog.setOnDismissListener {
            if (!handedOff && !bitmap.isRecycled) bitmap.recycle()
            if (!handedOff) statusText.text = ui("画像の読み込みをキャンセルしました。", "Image import was canceled.")
        }
        dialog.show()
    }

    private fun acceptDecodedImage(bitmap: Bitmap, cleanupFile: File? = null) {
        showWorkspace()
        val token = workGeneration.incrementAndGet()
        statusText.text = ui("画像を解析用に準備中…", "Preparing image for analysis…")
        executor.execute {
            var candidate: BitmapUtils.Prepared? = null
            var handedToUi = false
            try {
                val p = BitmapUtils.prepare(bitmap)
                candidate = p
                if (p.bitmap !== bitmap) bitmap.recycle()
                val standardResult = decodeStandardQr(p.bitmap)
                val initialEstimate = InitialThresholdEstimator.estimate(p.bitmap)
                val initialGrid = io.github.earthdiver.qrrepair.core.GridPeriodicityEstimator.estimate(p.luma)
                if (token != workGeneration.get()) {
                    p.bitmap.recycle()
                    return@execute
                }
                runOnUiThread {
                    if (token != workGeneration.get()) {
                        p.bitmap.recycle()
                        return@runOnUiThread
                    }
                    immediateResultShown = false
                    prepared = p
                    analysisPrepared = p
                    savedCellEditState = null
                    preprocessSettings = PreprocessSettings(
                        blackMax = initialEstimate?.blackMax ?: 96,
                        whiteMin = initialEstimate?.whiteMin ?: 160,
                        unknownColorClusters = initialEstimate?.unknownColorClusters.orEmpty(),
                        version = initialGrid?.version ?: 1,
                        lockCorners = true,
                        logicalGridAssigned = initialGrid == null,
                        manualQuad = initialGrid?.moduleCenterQuad,
                    )
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
                            detail = "${p.bitmap.width}×${p.bitmap.height}px",
                            snapshot = RecoverySnapshot(null, null, null, -1, preprocessSettings),
                        ),
                    )
                    persistSessionAsync()
                    if (initialEstimate == null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(ui("濃度閾値を推定できませんでした", "Could not estimate intensity thresholds"))
                            .setMessage(ui("黒白ピークを信頼できる精度で分離できなかったため、既定値（暗部96／明部160）を使用します。修復前設定で画像を確認してください。", "The black and white peaks could not be separated reliably. The defaults (dark 96 / light 160) will be used. Review the image in pre-repair settings."))
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                    recoveryState.clear()
                    guideText.text = ui("案内: 修復前設定で閾値・反転・グリッドを確認してください。", "Guide: Review the threshold, polarity, and grid in the pre-repair settings.")
                    contentPreviewText.text = getString(R.string.no_content_preview)
                    setDiagnosticDetails(null)
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
                if (token == workGeneration.get()) runOnUiThread {
                    statusText.text = ui("画像の読み込みに失敗: ${t.message ?: t.javaClass.simpleName}", "Image load failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                if (!handedToUi) {
                    candidate?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                    bitmap.takeIf { it !== candidate?.bitmap && !it.isRecycled }?.recycle()
                }
                // CameraActivity hands us a private cache JPEG. It is only a hand-off artifact;
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

    private fun showImmediateQrResult(result: Result) {
        immediateResultShown = true
        showWorkspace()
        if (modernStyle) {
            workspaceTitle.text = ui("読み取り結果", "Scan result")
            workspaceStep.text = ui("03 結果  /  内容を確認", "03 Result  /  Review content")
        }
        setDiagnosticDetails(null)
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
        AlertDialog.Builder(this)
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
        FormatEditPolicy.conflict(settings.version, settings.formatConstraint, settings.preRecoveryEdits)

    private fun startAutoRecovery() {
        immediateResultShown = false
        showWorkspace()
        val requestSettings = preprocessSettings.recoverySnapshot()
        settingsFormatConflict(requestSettings)?.let {
            workGeneration.incrementAndGet()
            refreshMainResultSelector()
            contentPreviewText.text = getString(R.string.no_content_preview)
            setDiagnosticDetails(null)
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
        contentPreviewText.text = ""
        setDiagnosticDetails(null)
        guideText.text = ""
        refreshMainResultSelector()
        val searchSummary = requestSettings.searchSummary()
        statusText.text = ui("$searchSummary。固定した幾何を評価中…", "$searchSummary. Evaluating the fixed geometry…")
        executor.execute {
            if (token != workGeneration.get()) return@execute
            try {
                val variants = requestSettings.variants()
                val retentionBudget = io.github.earthdiver.qrrepair.core.RecoveryRetentionBudget()
                val results = mutableListOf<io.github.earthdiver.qrrepair.core.AutoRecoveryResult>()
                for ((variantIndex, profile) in variants.withIndex()) {
                    if (retentionBudget.exhausted) break
                    val variantResult = AutoRecoveryEngine(recoveryEngine).run(
                        p.luma,
                        profile = profile,
                        retentionBudget = retentionBudget,
                        cancellation = RecoveryCancellation { token != workGeneration.get() || Thread.currentThread().isInterrupted },
                        onProgress = { done, total, message ->
                            if (token == workGeneration.get() && (done % 3 == 0 || done == total)) {
                                runOnUiThread {
                                    if (token == workGeneration.get()) {
                                        statusText.text = "${profile.label}: $message  (${variantIndex + 1}/${variants.size}, $done/$total)"
                                    }
                                }
                            }
                        },
                    )
                    results += variantResult
                }
                val selectedResult = results.minWithOrNull { first, second ->
                    val a = first.bestAttempt
                    val b = second.bestAttempt
                    when {
                        a != null && b != null -> geometryAttemptComparator.compare(a, b)
                        a != null -> -1
                        b != null -> 1
                        else -> 0
                    }
                }
                    ?: io.github.earthdiver.qrrepair.core.AutoRecoveryResult(emptyList(), emptyList())
                val result = if (retentionBudget.exhausted) selectedResult.withRetentionLimit() else selectedResult
                if (token != workGeneration.get()) return@execute
                runOnUiThread {
                    if (token != workGeneration.get()) return@runOnUiThread
                    recoveryState.autoResult = result
                    if (result.finders.isEmpty() && result.attempts.isEmpty()) {
                        statusText.text = ui("論理四隅が未確定のため、QR行列を作成できませんでした。四隅とFinder役割を確認してください。", "The QR matrix could not be built because the logical corners are not assigned. Check the corners and Finder roles.")
                        guideText.text = ui("案内: QR行列を作成できなかったため、修復を開始できませんでした。四隅・Finderの役割・バージョンをご確認ください。QR行列がないため、追加で確定が必要なbit数と推奨セルは算出できません。", "Guide: Repair could not be started because a QR matrix could not be created. Please review the corners, Finder roles, and Version. Without a QR matrix, the number of additional bits that must be determined and a recommended cell cannot be calculated.")
                        recordRecoveryAttempt(ui("修復できませんでした", "Repair failed"), ui("論理四隅が未確定", "Logical corners are not assigned"))
                        return@runOnUiThread
                    }
                    if (result.attempts.isEmpty()) {
                        statusText.text = ui("固定した四隅とバージョンから幾何候補を作れませんでした。", "No geometry candidate could be built from the fixed corners and Version.")
                        guideText.text = ui("案内: 固定された四隅とバージョンからQR行列を作成できなかったため、修復を開始できませんでした。四隅とバージョンをご確認ください。QR行列がないため、追加で確定が必要なbit数と推奨セルは算出できません。", "Guide: Repair could not be started because a QR matrix could not be created from the fixed corners and Version. Please review the corners and Version. Without a QR matrix, the number of additional bits that must be determined and a recommended cell cannot be calculated.")
                        recordRecoveryAttempt(ui("修復できませんでした", "Repair failed"), ui("固定幾何候補なし", "No fixed geometry candidate"))
                        return@runOnUiThread
                    }
                    val best = result.bestAttempt ?: result.attempts.first()
                    recoveryState.attemptIndex = result.attempts.indexOf(best).coerceAtLeast(0)
                    showAttempt(recoveryState.attemptIndex)
                    recordHistory(
                        HistoryEventKind.AUTO_RECOVERY,
                        ui("修復処理", "Repair"),
                        ui("幾何候補 ${result.attempts.size}件 / 復号候補 ${recoveryState.recoveries.size}件", "${result.attempts.size} geometry candidate(s) / ${recoveryState.recoveries.size} decode candidate(s)"),
                    )
                }
            } catch (_: RecoveryCancelledException) {
                return@execute
            } catch (error: Exception) {
                if (token == workGeneration.get()) runOnUiThread {
                    if (token == workGeneration.get()) {
                        statusText.text = ui("修復処理に失敗: ${error.message ?: error.javaClass.simpleName}", "Repair failed: ${error.message ?: error.javaClass.simpleName}")
                        guideText.text = ui("案内: QRの四隅から有効な読み取り範囲を作成できませんでした。四隅が交差または重複していないかご確認ください。", "Guide: A valid reading area could not be created from the QR corners. Please check that the corners do not cross or overlap.")
                    }
                }
            }
        }
    }

    private fun showPreprocessDialog() = showPreprocessPage(PreprocessPage.BASIC)

    private fun showCellEditor() = showPreprocessPage(PreprocessPage.CELL_EDITOR)

    private fun showPreprocessPage(initialPage: PreprocessPage) {
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
            initialWorking = preparedFor(preprocessSettings) ?: source,
            savedCellEditState = savedCellEditState,
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
        if (result.attempts.isEmpty()) return
        val normalizedIndex = ((index % result.attempts.size) + result.attempts.size) % result.attempts.size
        val attempt = result.attempts[normalizedIndex]
        recoveryState.showAttempt(
            normalizedIndex,
            attempt.hypothesis.geometry,
            attempt.matrix,
            attempt.recoveries,
            attempt.failure,
            preferredRecoveryIndex(attempt.recoveries),
        )
        refreshInspector()
        updateSummary()
        updateContentPreview()
    }

    private fun preferredRecoveryIndex(candidates: List<RecoveryCandidate>, requested: Int = -1): Int {
        if (candidates.isEmpty()) return -1
        val displayedDecoded = candidates.indices.asSequence()
            .filter { candidates[it].payloadParsed }
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
        val validatedCandidate = candidate?.takeIf { it.payloadParsed }
        val after = if (validatedCandidate != null) {
            RepairTraceBuilder.candidateMatrix(working, validatedCandidate, recoveryEngine)
        } else {
            working.copy()
        }
        recoveryState.inspectorMatrix = after
        recoveryState.traces = RepairTraceBuilder.build(source, after, validatedCandidate)

        val guidance = io.github.earthdiver.qrrepair.core.guidanceCandidates(recoveryState.recoveries, validatedCandidate)
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
                "案内: 内容が異なるため正しい結果を確定できません。各候補の内容をご確認ください。",
                "Guide: The contents differ, so the correct result could not be determined. Please review each candidate.",
            )
            c?.payloadParsed == true && c.structuredAppend != null -> c.structuredAppend.description()
            c?.payloadParsed == true -> ui(
                "案内: 重要な内容については、表示された結果をご確認ください。",
                "Guide: Please verify the displayed result if the content is important.",
            )
            c != null -> ui(
                "案内: 誤り訂正と整合する候補はありますが、QRデータ構造を最後まで検証できませんでした。診断詳細と設定をご確認ください。",
                "Guide: A candidate is consistent with error correction, but the complete QR data structure could not be validated. Review the diagnostic details and settings.",
            )
            recoveryState.workingMatrix != null -> {
                if (rec?.exactSpaceDimension != null) {
                    guideText.text = buildString {
                        append(ui(
                            "案内: 既知bitとの完全一致を仮定したRS自由度: ${rec.exactSpaceDimension}bit。ペイロード検証前の目安です。\n",
                            "Guide: ${rec.exactSpaceDimension} free RS bits assuming exact known-bit agreement, before payload validation.\n",
                        ))
                        append(rec.explanationJa())
                    }
                    return
                }
                val shortfall = recoveryState.failure?.rsShortfallBits ?: estimateRsShortfallBits(recoveryState.workingMatrix!!)
                val shortfallText = shortfall?.toString() ?: ui("算出できません", "unavailable")
                buildString {
                    append(ui("案内: 追加で確定が必要なbit数: $shortfallText。", "Guide: Additional bits that must be determined: $shortfallText."))
                    rec?.let {
                        append(ui(" 候補を最も絞り込めるセル: (${it.coord.x},${it.coord.y})。", " Best cell for narrowing the candidates: (${it.coord.x},${it.coord.y})."))
                        append(ui("このセルをご確認ください。", " Check this cell."))
                    } ?: append(ui("設定をご確認ください。", " Review the settings."))
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
            decoded.size == 1 -> ui("修復成功", "Repair succeeded")
            decoded.size > 1 && distinctPayloads == 1 -> ui(
                "修復成功: ${decoded.size}件の候補は同じ内容です",
                "Repair succeeded: ${decoded.size} candidates have the same content",
            )
            decoded.size > 1 -> ui(
                "修復成功: ${decoded.size}件の候補 / 異なる内容 ${distinctPayloads}件",
                "Repair succeeded: ${decoded.size} candidates / $distinctPayloads distinct contents",
            )
            c != null -> ui("修復未完了: RSと整合する候補があります", "Repair incomplete: an RS-consistent candidate is available")
            recoveryState.failure != null -> ui("修復失敗", "Repair failed")
            else -> ui("修復候補なし", "No recovery candidate")
        }
        statusText.text = buildString {
            if (!prefix.isNullOrBlank()) append(prefix).append('\n')
            append(sharedResult)
            c?.structuredAppend?.let { append('\n').append(it.description()) }
        }
        refreshMainResultSelector()
        updateContentPreview()
    }

    private fun recoveryFailureReasonText(failure: RecoveryFailure): String = when (failure.reason) {
        RecoveryFailureReason.ERASURE_CAPACITY_EXCEEDED ->
            ui("不明なデータが誤り訂正容量を超えています", "The amount of unknown data exceeds the error-correction capacity")
        RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT ->
            ui("探索上限までに、誤り訂正可能な組み合わせを見つけられませんでした", "No correctable combination could be found within the search limit")
        RecoveryFailureReason.ERROR_ERASURE_CAPACITY_EXCEEDED ->
            ui("不明なデータは容量内ですが、読み取り済みセルの誤りを含めると訂正容量を超えています。閾値・反転・グリッド、または固定セルをご確認ください", "The unknown data is within capacity, but errors in the observed cells exceed the correction capacity. Please review the thresholds, polarity, grid, or fixed cells")
        RecoveryFailureReason.NO_RS_CONSISTENT_ASSIGNMENT ->
            ui("現在のセル値から、誤り訂正と整合する結果を見つけられませんでした", "No result consistent with error correction could be found from the current cell values")
        RecoveryFailureReason.RS_CORRECTION_FAILED ->
            ui("誤り訂正を試みましたが、整合する結果を得られませんでした。読み取り済みセルの誤りが考えられますので、閾値・反転・グリッドをご確認ください", "Error correction was attempted, but no consistent result was obtained. Some observed cells may have been read incorrectly; please review the thresholds, polarity, and grid")
        RecoveryFailureReason.QR_COMBINATION_SEARCH_LIMIT ->
            ui("複数の誤り訂正候補を検証しましたが、探索上限までに復号可能な結果を確定できませんでした", "Multiple error-correction candidates were checked, but no decodable result could be determined within the search limit")
        RecoveryFailureReason.KNOWN_DATA_ECC_MISMATCH_LIMIT ->
            ui("RSと整合する候補は得られましたが、既知のデータ・ECC bitとの不一致が指定された上限を超えました", "An RS-consistent candidate was found, but its differences from known data/ECC bits exceeded the specified limit")
        RecoveryFailureReason.KNOWN_BIT_CONSTRAINT_CONFLICT ->
            ui("既知のデータ・ECC bitと完全一致するRS解がありません。入力bit・固定セル・形式の指定をご確認ください", "No RS solution exactly matches the known data/ECC bits. Check input bits, fixed cells, and format settings")
        RecoveryFailureReason.FORMAT_MISMATCH_LIMIT ->
            ui("形式情報の不一致が指定上限を超え、試せる形式がありません。形式セル・ECC・マスクの指定、または形式情報の誤り上限をご確認ください", "Every permitted format exceeds the format-error limit. Review format cells, ECC/mask selections, or the format-error limit")
        RecoveryFailureReason.FORMAT_EDIT_CONFLICT ->
            FormatEditConflict(failure.manualConflicts).message(changeRejected = false)
        RecoveryFailureReason.MANUAL_PIN_CONFLICT ->
            ui("手動固定値と矛盾するため、この候補は採用できません。", "This candidate conflicts with manually fixed values.") +
                failure.manualConflicts.joinToString(prefix = "\n") { "(${it.coord.x},${it.coord.y}): ${it.actual.bitOrNull()} → ${it.expected?.bitOrNull()}" }
        RecoveryFailureReason.RS_BIT_SPACE_LIMIT ->
            ui("RS解空間は求まりましたが、自由度が今回の列挙上限を超えています。追加確認セルと診断詳細をご確認ください", "The RS solution space was found, but its free dimension exceeds this pass's enumeration limit. Review the additional-check cell and diagnostics")
        null -> when (failure.stage) {
            io.github.earthdiver.qrrepair.core.RecoveryFailureStage.EXTRACTION -> ui("QR行列からデータを取り出せませんでした。バージョン・マスク・グリッドをご確認ください", "Data could not be extracted from the QR matrix. Please review the Version, mask, and grid")
            io.github.earthdiver.qrrepair.core.RecoveryFailureStage.DEINTERLEAVE -> ui("抽出したデータをQRの誤り訂正ブロックとして解釈できませんでした。バージョンと形式情報をご確認ください", "The extracted data could not be interpreted as QR error-correction blocks. Please review the Version and format information")
            io.github.earthdiver.qrrepair.core.RecoveryFailureStage.REED_SOLOMON -> ui("誤り訂正を完了できませんでした。閾値・反転・グリッドをご確認ください", "Error correction could not be completed. Please review the thresholds, polarity, and grid")
            io.github.earthdiver.qrrepair.core.RecoveryFailureStage.PAYLOAD_VALIDATION -> ui("誤り訂正候補は得られましたが、QRデータ構造と一致しないため修復結果として採用できませんでした", "An error-correction candidate was obtained, but it could not be accepted as a repaired result because it did not match the QR data structure")
        }
    }

    private fun startSavePng() {
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
            putExtra(Intent.EXTRA_TITLE, "qr-repaired.png")
        }
        startActivityForResult(intent, REQ_SAVE_PNG)
    }

    /** PNG export must match the displayed result matrix, including RS and structural repairs. */
    private fun matrixForPng(): QrMatrix? = (recoveryState.inspectorMatrix ?: recoveryState.workingMatrix)?.copy()

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
            putExtra(Intent.EXTRA_TITLE, "qr-repair-debug-input.png")
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
        resources.configuration.locales[0]?.language == AppLanguage.JAPANESE.tag

    private fun ui(japanese: String, english: String): String =
        if (isJapaneseUi()) japanese else english
}

internal fun polarityThresholdMeanings(mode: PolarityMode, japanese: Boolean = true): Pair<String, String> = when (mode) {
    PolarityMode.NORMAL -> if (japanese) "QRの黒(1)" to "QRの白(0)" else "QR black (1)" to "QR white (0)"
    PolarityMode.INVERTED -> if (japanese) "QRの白(0)" to "QRの黒(1)" else "QR white (0)" to "QR black (1)"
    PolarityMode.AUTO -> if (japanese) "通常=1 / 反転=0" to "通常=0 / 反転=1" else "normal=1 / inverted=0" to "normal=0 / inverted=1"
}
