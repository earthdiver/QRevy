package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.FormatCellGroup
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleRepairTrace
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView

/** One selected result and its matching observation, captured together for comparison. */
internal data class RecoveryResultDocument(
    val before: QrMatrix,
    val after: QrMatrix,
    val geometry: GridGeometry?,
    val traces: Map<Coord, ModuleRepairTrace>,
    val candidate: RecoveryCandidate?,
    val candidateIndex: Int,
    val failureMask: Int?,
) {
    fun codewordAt(coord: Coord): Pair<Int, Int>? {
        for ((index, word) in candidate?.extraction?.codewords.orEmpty().withIndex()) {
            val bit = word.coordinates.indexOf(coord)
            if (bit >= 0) return index to bit
        }
        return null
    }
}

/** Owns comparison controls and viewport state; candidate selection stays with the workspace. */
internal class RecoveryResultDialog(
    private val activity: Activity,
    private val modernStyle: Boolean,
    private val initialDocument: RecoveryResultDocument,
    private val candidates: List<Pair<Int, RecoveryCandidate>>,
    private val selectCandidate: (Int) -> RecoveryResultDocument?,
    private val cellDescription: (Int, Int, QrMatrix) -> String,
    private val cellTechnicalDescription: (Int, Int, QrMatrix) -> String,
    private val codewordDescription: (Int, Int) -> String,
) {
    fun show() = with(activity) {
        var document = initialDocument
        var before = document.before
        var displayedGeometry = document.geometry
        var after = document.after
        val detail = TextView(this).apply {
            text = ui("セルをタップ：セルの修復理由を表示", "Tap a cell to view its repair reason")
            textSize = 12f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextIsSelectable(true)
        }
        lateinit var relatedCellsButton: Button
        var selectedCoord: Coord? = null
        var showCellCalculations = false
        var relatedCellsHighlighted = false
        var revealEvidence: () -> Unit = {}
        val view = QrResultInspectorView(this).apply { setComparisonEnabled(false) }
        val unmask = CheckBox(this).apply {
            text = ui("マスク解除", "Unmask")
            setOnCheckedChangeListener { _, checked -> view.setUnmasked(checked) }
        }
        val maskDescription = TextView(this).apply {
            textSize = 12f
            setPadding(dp(12), 0, dp(12), dp(4))
        }
        fun refreshInspectorDocument(keepViewport: Boolean = false) {
            val candidate = document.candidate
            val mask = (candidate?.dataMask ?: document.failureMask)?.takeIf { it in 0..7 }
            view.setDocument(before, after, document.traces, keepViewport = keepViewport, dataMask = mask)
            unmask.isEnabled = mask != null
            if (mask == null) unmask.isChecked = false
            view.setUnmasked(unmask.isChecked)
            maskDescription.text = when {
                mask == null -> ui("マスク番号が取得できないため、マスク解除は使用できません。",
                    "Unmask is unavailable because no mask number is available.")
                candidate != null -> ui("使用マスク: $mask（選択候補）", "Display mask: $mask (selected candidate)")
                else -> ui("使用マスク: $mask（失敗した試行・未検証）", "Display mask: $mask (failed attempt; unverified)")
            }
        }
        refreshInspectorDocument()
        fun refreshCellEvidence() {
            val coord = selectedCoord
            val found = coord?.let { document.codewordAt(it) }
            val word = found?.let { document.candidate?.extraction?.codewords?.getOrNull(it.first) }
            val formatGroup = coord?.let { FormatCellGroup.at(after.dimension, it.x, it.y) }
            relatedCellsButton.text = formatGroup?.role?.buttonLabel() ?: ui("同じコード語を表示", "Show same codeword")
            relatedCellsButton.isEnabled = formatGroup != null || word != null
            relatedCellsButton.visibility = if (showCellCalculations) View.VISIBLE else View.GONE
            view.clearHighlightedCodeword()
            if (!showCellCalculations || (word == null && formatGroup == null)) relatedCellsHighlighted = false
            if (coord == null) return
            view.setSelected(coord.x, coord.y)
            detail.text = buildString {
                append(cellDescription(coord.x, coord.y, after))
                if (formatGroup != null && showCellCalculations) {
                    if (relatedCellsHighlighted) view.setHighlightedCodeword(formatGroup.coordinates(after.dimension))
                    append("\n\n").append(cellTechnicalDescription(coord.x, coord.y, after))
                } else if (relatedCellsHighlighted && found != null && word != null) {
                    view.setHighlightedCodeword(word.coordinates)
                    append('\n').append(codewordDescription(found.first, found.second))
                } else if (showCellCalculations) {
                    append("\n\n").append(cellTechnicalDescription(coord.x, coord.y, after))
                }
            }
        }
        view.onCellSelected = { x, y ->
            selectedCoord = Coord(x, y)
            relatedCellsHighlighted = false
            revealEvidence()
            refreshCellEvidence()
        }
        val candidateTabs = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            candidates.forEachIndexed { resultIndex, (candidateIndex, _) ->
                addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = getString(
                        R.string.result_tab,
                        resultIndex + 1,
                    )
                    isChecked = candidateIndex == document.candidateIndex
                    setPadding(dp(10), 0, dp(10), 0)
                    setOnClickListener {
                        if (candidateIndex == document.candidateIndex) return@setOnClickListener
                        val nextDocument = selectCandidate(candidateIndex) ?: return@setOnClickListener
                        val nextBefore = nextDocument.before
                        val nextAfter = nextDocument.after
                        val keepPosition = before === nextBefore && displayedGeometry == nextDocument.geometry &&
                            after.dimension == nextAfter.dimension
                        before = nextBefore
                        after = nextAfter
                        document = nextDocument
                        displayedGeometry = document.geometry
                        refreshInspectorDocument(keepViewport = keepPosition)
                        view.clearHighlightedCodeword()
                        if (!keepPosition) {
                            selectedCoord = null
                            relatedCellsHighlighted = false
                            detail.text = ui("表示位置をリセットしました。セルを選択してください。", "View reset. Select a cell.")
                        }
                        refreshCellEvidence()
                    }
                })
            }
        }
        relatedCellsButton = button(ui("同じコード語を表示", "Show same codeword")) {
            relatedCellsHighlighted = true
            refreshCellEvidence()
        }.apply { isEnabled = false; visibility = View.GONE }
        val detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (modernStyle) {
                PrecisionStyle.panel(this)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(TextView(activity).apply {
                    text = ui("セルの修復根拠", "Cell repair evidence")
                    setTextColor(PrecisionStyle.ACCENT)
                    textSize = 16f
                    setPadding(dp(12), 0, dp(12), dp(4))
                })
                addView(detail)
            } else {
                setBackgroundColor(0xFFF4F4F4.toInt())
                addView(detail)
            }
            addView(button(ui("計算の説明を表示／閉じる", "Show/hide calculation details")) {
                showCellCalculations = !showCellCalculations
                refreshCellEvidence()
            })
            addView(relatedCellsButton)
        }
        val evidenceScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(detailPanel)
            visibility = View.GONE
        }
        val evidenceToggle = button(getString(R.string.show_evidence)) {
            evidenceScroll.visibility = if (evidenceScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        revealEvidence = { evidenceScroll.visibility = View.VISIBLE }
        val legend = TextView(this).apply {
            text = ui(
                "セル: 黒=1 / 白=0 / 灰色×=不明\n枠: 緑=QRの固定構造 / 黄土色=形式情報 / 橙=RS誤り訂正 / 紫=データ構造の条件 / 赤紫=ビット候補探索 / 青=手動指定",
                "Cells: black=1 / white=0 / grey ×=unknown\nBorders: green=QR structure / ochre=format / orange=RS correction / purple=payload constraints / magenta=bit search / blue=manual entry")
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        detailPanel.addView(legend)
        val comparison = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for ((index, label) in listOf(ui("修復後", "After"), ui("修復前", "Before"), ui("左右で比較", "Compare")).withIndex()) {
                addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = index == 0
                    setOnClickListener {
                        view.setComparisonEnabled(index != 0)
                        view.setBeforeFraction(if (index == 1) 1f else .5f)
                    }
                })
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (candidates.size > 1) addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(button(ui("候補の内容一覧", "Candidate contents")) {
                        CandidateContentsDialog(activity, candidates, document.candidateIndex) { index ->
                            val position = candidates.indexOfFirst { it.first == index }
                            candidateTabs.getChildAt(position)?.performClick()
                        }.show()
                    })
                    addView(candidateTabs)
                })
            })
            addView(HorizontalScrollView(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(comparison)
                    addView(unmask)
                })
            })
            addView(maskDescription)
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(activity).apply {
                text = ui("左右の移動・拡大は連動します。セルをタップすると修復理由を表示します。", "Both sides pan and zoom together. Tap a cell for its repair reason.")
                textSize = 13f
                setPadding(dp(12), 0, dp(12), 0)
            })
            addView(evidenceToggle)
            addView(evidenceScroll, LinearLayout.LayoutParams(-1, 0, .65f))
            addView(button(ui("色と記号の説明", "Colors and symbols")) {
                legend.visibility = if (legend.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                evidenceScroll.visibility = View.VISIBLE
                if (legend.visibility == View.VISIBLE) evidenceScroll.post { evidenceScroll.smoothScrollTo(0, legend.top) }
            })
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

    private val dialogViews = DialogViewFactory(activity)
    private fun button(label: String, onClick: () -> Unit): Button = dialogViews.button(label, onClick)
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun ui(japanese: String, english: String): String =
        localizedText(japanese, english)
}
