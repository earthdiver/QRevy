package io.github.earthdiver.qrrepair

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.Result
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import java.util.concurrent.Executor

/** The recipient owns the prepared image after delivery. */
internal data class PreparedTextMatrixImport(
    val source: BitmapUtils.Prepared,
    val settings: PreprocessSettings,
    val removedPriority: Set<Coord>,
    val standardResult: Result?,
    val automaticResult: QuickAutoRecoveryResult?,
)

/** Reads and prepares TXT input; obsolete imports release their image without changing the workspace. */
internal class TextMatrixImportController(
    private val contentResolver: ContentResolver,
    private val executor: Executor,
    private val dispatchToUi: (() -> Unit) -> Unit,
    private val currentGeneration: () -> Int,
    private val isDestroyed: () -> Boolean,
    private val onFinished: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val standardScan: (Bitmap) -> Result? = ::decodeStandardQr,
    private val automaticScan: (LumaImage, PreprocessSettings, AutomaticScanBudget) -> QuickAutoRecoveryResult = QuickAutoRecovery()::runTextMatrix,
    private val firstAutomaticRun: () -> Boolean = QuickAutoStartupState.process::claim,
) {
    fun load(
        token: Int,
        uri: Uri,
        automaticRepair: Boolean,
        diagnostics: Boolean,
        stopRequested: () -> Boolean,
        onPrepared: (PreparedTextMatrixImport) -> Unit,
    ) {
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
                val parsed = QrTextMatrixParser.parse(text, enforcePriorityLimit = !automaticRepair)
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
                if (token != currentGeneration()) {
                    p.bitmap.recycle()
                    return@execute
                }
                val standardResult = standardScan(p.bitmap)
                val automatic = if (standardResult == null && automaticRepair && !stopRequested() && token == currentGeneration()) {
                    automaticScan(p.luma, settings,
                        AutomaticScanBudget.forImport(
                            firstRun = firstAutomaticRun(),
                            stopRequested = { stopRequested() || token != currentGeneration() || Thread.currentThread().isInterrupted },
                            trace = if (diagnostics) AutomaticScanTrace() else null))
                } else null
                dispatchToUi {
                    if (token != currentGeneration()) {
                        p.bitmap.recycle()
                        return@dispatchToUi
                    }
                    onFinished(token)
                    onPrepared(PreparedTextMatrixImport(p, settings, removedPriority, standardResult, automatic))
                }
                bitmap = null // Ownership moved to the queued delivery or its recipient.
            } catch (t: Throwable) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                dispatchToUi {
                    onFinished(token)
                    if (token == currentGeneration() && !isDestroyed()) onError(t)
                }
            } finally {
                dispatchToUi { onFinished(token) }
            }
        }
    }
}
