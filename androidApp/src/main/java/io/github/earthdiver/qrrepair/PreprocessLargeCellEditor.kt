package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.view.ViewGroup
import io.github.earthdiver.qrrepair.core.Coord
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrFunctionMask
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.ui.CodewordOverlayKind
import io.github.earthdiver.qrrepair.ui.EditorTool
import io.github.earthdiver.qrrepair.ui.QrEditorView

/** Owns the expanded cell editor and routes edits through the same commands as the inline editor. */
internal class PreprocessLargeCellEditor(
    private val activity: Activity,
    bitmap: Bitmap,
    geometry: GridGeometry,
    matrix: QrMatrix,
    private val version: () -> Int,
    private val cellEditState: PreRecoveryCellEditState,
    private val cellCommands: PreprocessCellEditController,
    private val cellRegionLabel: (Coord) -> String,
    private val currentMatrix: () -> QrMatrix?,
    private val onSelectionChanged: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val dialogViews = DialogViewFactory(activity)
    val editorView = QrEditorView(activity).apply {
        setDocument(bitmap, geometry, matrix)
        setCodewordOverlayVersion(version())
        setSelection(emptySet())
        cornerEditEnabled = false
        showQrStructureOverlay = true
    }
    private val page: PreRecoveryLargeCellEditPage
    private val dialog: AlertDialog
    val sourceControl get() = page.sourceControl
    val status get() = page.status
    val countStatus get() = page.countStatus

    init {
        fun setLargeEditorTool(tool: EditorTool) {
            editorView.editorTool = tool
            status.text = editorViewToolStatus(tool)
        }
        editorView.onCellSelected = { x, y ->
            cellEditState.select(setOf(Coord(x, y)))
            val matrix = currentMatrix()
            val value = matrix?.get(x, y)?.value
            val coord = Coord(x, y)
            val codeword = editorView.codewordCellAt(coord)
            val kind = when {
                QrFunctionMask.isFunction(version(), x, y) ->
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
            status.text = ui("選択: ($x,$y) / $kind / 値=$label", "Selected: ($x,$y) / $kind / value=$label")
            onSelectionChanged()
        }
        editorView.onBrushApplied = { coords, value ->
            cellCommands.setValues(coords, value, asRectangle = isStamp(editorView.editorTool))
        }
        editorView.onRangeSelected = { coords ->
            cellEditState.select(coords, asRectangle = true)
            editorView.setSelection(coords, asRectangle = true)
            status.text = ui("矩形選択 ${coords.size}セル。0/1/?で一括変更できます。", "Selected ${coords.size} cells. Use 0/1/? to change them together.")
            onSelectionChanged()
        }
        page = PreRecoveryLargeCellEditPage(
            activity,
            editor = editorView,
            buttonFactory = dialogViews::button,
            compactRow = { views -> dialogViews.compactRow(*views) },
            scrollRow = { views -> dialogViews.scrollRow(*views) },
            actions = LargeCellEditPageActions(
                setTool = ::setLargeEditorTool,
                setValue = { value -> cellCommands.setValues(cellEditState.selectedCells, value) },
                setPriority = { cellCommands.setPriority(cellEditState.selectedCells) },
                changeSource = cellCommands::changeSource,
                undo = cellCommands::undo,
                redo = cellCommands::redo,
                showCodewords = { checked ->
                    editorView.showCodewordOverlay = checked
                    status.text = if (checked) getString(R.string.codeword_overlay_legend)
                    else editorViewToolStatus(editorView.editorTool)
                },
                close = { dismiss() },
            ),
        )
        dialog = AlertDialog.Builder(activity).setView(page.view).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnDismissListener { onDismiss() }
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()

    private fun isStamp(tool: EditorTool) = tool in setOf(EditorTool.STAMP_ERASURE_SMALL,
        EditorTool.STAMP_ERASURE_MEDIUM, EditorTool.STAMP_ERASURE_LARGE)
    private fun getString(id: Int) = activity.getString(id)
    private fun ui(japanese: String, english: String) = localizedText(japanese, english)

    private fun editorViewToolStatus(tool: EditorTool): CharSequence = when (tool) {
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
}
