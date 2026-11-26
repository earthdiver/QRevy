package io.github.earthdiver.qrrepair

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import java.util.concurrent.Executor

/** Writes captured export content and releases images owned by the export. */
internal class RecoveryExportController(
    private val context: Context,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val onResultNotice: (String) -> Unit,
) {
    fun saveTextMatrix(uri: Uri, text: String) {
        executor.execute {
            val message = try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException(context.getString(R.string.text_matrix_destination_unavailable))
                context.getString(R.string.text_matrix_saved)
            } catch (error: Exception) {
                context.getString(R.string.text_matrix_save_failed, error.message ?: error.javaClass.simpleName)
            }
            dispatchToUi { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    fun saveDiagnosticInput(uri: Uri, pending: PendingDiagnosticBitmap) {
        executor.execute {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    if (!pending.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException(localizedText("PNG圧縮に失敗", "PNG compression failed"))
                    }
                } ?: throw IllegalStateException(localizedText("保存先を開けません", "Could not open the destination"))
                dispatchToUi {
                    onResultNotice(localizedText("デバッグ用入力画像を保存しました（${pending.bitmap.width}×${pending.bitmap.height}px）。", "Saved debug input image (${pending.bitmap.width}×${pending.bitmap.height} px)."))
                }
            } catch (t: Throwable) {
                dispatchToUi { onResultNotice(localizedText("入力画像の保存失敗: ${t.message}", "Input image save failed: ${t.message}")) }
            } finally {
                if (pending.owned) pending.bitmap.recycle()
            }
        }
    }

    fun savePng(uri: Uri, matrix: QrMatrix) {
        executor.execute {
            try {
                val bitmap = renderMatrixPng(matrix)
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IllegalStateException(localizedText("PNG圧縮に失敗", "PNG compression failed"))
                        }
                    } ?: throw IllegalStateException(localizedText("保存先を開けません", "Could not open the destination"))
                } finally {
                    bitmap.recycle()
                }
                val hasUnknown = (0 until matrix.dimension).any { y ->
                    (0 until matrix.dimension).any { x -> matrix[x, y].value == ModuleValue.ERASED }
                }
                dispatchToUi {
                    onResultNotice(if (hasUnknown) {
                        localizedText("PNGを保存しました。未確定の?は診断用グレーで出力されます。", "PNG saved. Unresolved ? cells are shown in diagnostic grey.")
                    } else {
                        localizedText("修復結果のPNGを保存しました。", "Repair result PNG saved.")
                    })
                }
            } catch (t: Throwable) {
                dispatchToUi { onResultNotice(localizedText("PNG保存失敗: ${t.message}", "PNG save failed: ${t.message}")) }
            }
        }
    }
}

internal fun textMatrixForExport(matrix: QrMatrix): String = buildString {
    for (y in 0 until matrix.dimension) {
        for (x in 0 until matrix.dimension) {
            val cell = matrix[x, y]
            append(if (cell.prioritySearch) '*' else when (cell.value) {
                ModuleValue.ZERO -> '0'
                ModuleValue.ONE -> '1'
                ModuleValue.ERASED -> '?'
            })
        }
        append('\n')
    }
}
internal fun renderMatrixPng(matrix: QrMatrix): Bitmap {
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
