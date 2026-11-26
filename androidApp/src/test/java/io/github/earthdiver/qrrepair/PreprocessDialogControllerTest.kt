package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import io.github.earthdiver.qrrepair.ui.QrEditorView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class PreprocessDialogControllerTest {
    private class Host : PreprocessDialogHost {
        val saves = mutableListOf<PreprocessSave>()
        val effects = mutableListOf<String>()
        var diagnostic: Bitmap? = null
        var session: PreprocessEditorSession? = null
        override fun editorOpened(session: PreprocessEditorSession) { this.session = session }
        override fun retains(image: BitmapUtils.Prepared) =
            saves.lastOrNull()?.image === image || diagnostic === image.bitmap
        override fun save(draft: PreprocessSave) { saves += draft; effects += "save" }
        override fun showSavedStatus() { effects += "saved status" }
        override fun startRecovery() { effects += "repair" }
        override fun saveTextMatrix(matrix: io.github.earthdiver.qrrepair.core.QrMatrix) = Unit
        override fun saveDiagnosticInput(bitmap: Bitmap, owned: Boolean) { diagnostic = bitmap }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun click(dialog: AlertDialog, label: String) {
        views(dialog.window!!.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == label }.performClick()
    }

    private fun automaticThresholdControl(dialog: AlertDialog): CheckBox =
        views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
            .single { it.text.toString() == "閾値を自動設定" }

    private fun withDialog(settings: PreprocessSettings? = null, style: AppStyle = AppStyle.CLASSIC, sourceBitmap: Bitmap? = null,
                           savedCellEdits: PreRecoveryCellEditState? = null,
                           worker: Executor = Executor { it.run() },
                           savedCorrection: SavedCorrectionPreview? = null,
                           block: (AlertDialog, Host, BitmapUtils.Prepared, BitmapUtils.Prepared) -> Unit) = withTestAppLanguage(AppLanguage.JAPANESE) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.get().setTheme(style.theme)
        val source = BitmapUtils.prepare(sourceBitmap ?: Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        })
        val working = BitmapUtils.forSettings(source, settings ?: PreprocessSettings(rotationDegrees = 15f))
        val host = Host()
        PreprocessDialogController(activity.get(), settings ?: PreprocessSettings(version = 1, rotationDegrees = 15f,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
                PointD(220.0, 220.0), PointD(20.0, 220.0))),
            source, working, savedCellEdits, worker, AtomicInteger(), RecoveryEngine(), host, savedCorrection = savedCorrection).show()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        try { block(dialog, host, source, working) } finally {
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            activity.pause().stop().destroy()
            if (!working.bitmap.isRecycled) working.bitmap.recycle()
            if (!source.bitmap.isRecycled) source.bitmap.recycle()
            host.saves.map { it.image.bitmap }.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun externalImagesRetainRecoveryAcrossDetailedEditorOperations() {
        val directory = System.getenv("QREVY_DIAGNOSTIC_IMAGES")
        val manifest = System.getenv("QREVY_DIAGNOSTIC_MANIFEST")
        org.junit.Assume.assumeTrue(directory != null && manifest != null)
        val reports = org.json.JSONArray(java.io.File(requireNotNull(manifest)).readText())
        val failures = mutableListOf<String>()
        for (index in 0 until reports.length()) {
            val report = reports.getJSONObject(index)
            val expected = report.optString("quickText")
            if (expected.isEmpty() || !report.has("initialSettings")) continue
            val name = report.getString("name")
            require(java.io.File(name).name == name)
            val json = report.getJSONObject("initialSettings")
            val coordinates = json.getJSONArray("quad")
            val points = (0..3).map { PointD(coordinates.getDouble(it * 2), coordinates.getDouble(it * 2 + 1)) }
            val clusters = json.optJSONArray("colorClusters")
            val finders = json.optJSONArray("finders")
            val warp = json.optJSONArray("warp")
            val preview = if (warp == null) null else {
                val previewCoordinates = json.optJSONArray("previewQuad") ?: coordinates
                val previewPoints = (0..3).map { PointD(previewCoordinates.getDouble(it * 2), previewCoordinates.getDouble(it * 2 + 1)) }
                val quad = ImageQuad(previewPoints[0], previewPoints[1], previewPoints[2], previewPoints[3])
                val version = json.getInt("version")
                val pitch = (quad.topLeft.distanceTo(quad.topRight) + quad.topLeft.distanceTo(quad.bottomLeft)) / (2 * (16 + 4 * version))
                GridGeometry(version, FinderRole.TOP_LEFT, quad.topLeft.x, quad.topLeft.y, pitch, 0.0, quad,
                    GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp((0 until warp.length() / 5).map {
                        WarpLandmark(PointD(warp.getDouble(it * 5), warp.getDouble(it * 5 + 1)),
                            PointD(warp.getDouble(it * 5 + 2), warp.getDouble(it * 5 + 3)), warp.getDouble(it * 5 + 4))
                    })).reanchored()
            }
            val settings = PreprocessSettings(version = json.getInt("version"),
                manualQuad = ImageQuad(points[0], points[1], points[2], points[3]), logicalGridAssigned = true,
                distortionCorrection = json.optBoolean("curved"), previewGeometry = preview,
                photographicSource = json.optBoolean("photographicSource", true),
                formatMismatchLimitMode = ErrorLimitMode.valueOf(json.optString("formatLimitMode", "AUTOMATIC")),
                dataEccMismatchLimitMode = ErrorLimitMode.valueOf(json.optString("dataLimitMode", "AUTOMATIC")),
                formatMismatchLimit = json.optInt("formatLimit", 6), dataEccMismatchLimit = json.optInt("dataLimit", 20),
                partialBitSearchLimit = json.optInt("partial", 12), listDecodingAdditionalErrors = json.optInt("list", 1),
                knownBitErrorLimit = json.optInt("known"), prioritySearchLimit = json.optInt("priorityLimit", 4),
                manualFinders = if (finders == null) emptyMap() else (0 until finders.length() / 3).associate {
                    val role = when (finders.getInt(it * 3)) {
                        0 -> FinderRole.TOP_LEFT
                        1 -> FinderRole.TOP_RIGHT
                        3 -> FinderRole.BOTTOM_LEFT
                        else -> error("Invalid Finder role")
                    }
                    role to PointD(finders.getDouble(it * 3 + 1), finders.getDouble(it * 3 + 2))
                },
                lmCorrectionEnabled = json.optBoolean("lmEnabled", true), automaticThresholds = json.optBoolean("automatic", true),
                blackMax = json.optInt("black", 96), whiteMin = json.optInt("white", 160),
                provisionalBlackMax = json.optInt("provisionalBlack", 96), provisionalWhiteMin = json.optInt("provisionalWhite", 160),
                confirmedFinderPolarity = FinderPolarity.valueOf(json.optString("confirmedPolarity", "UNKNOWN")),
                polarity = PolarityMode.valueOf(json.optString("polarity", "AUTO")),
                trustKnownPayloadCount = json.optBoolean("single"), assumeUtf8ByteSegments = json.optBoolean("utf8"),
                useUnknownColorClusters = json.optBoolean("colorUnknown"),
                unknownColorClusters = if (clusters == null) emptyList() else (0 until clusters.length() / 4).map {
                    UnknownColorCluster(clusters.getInt(it * 4), clusters.getInt(it * 4 + 1), clusters.getInt(it * 4 + 2), clusters.getInt(it * 4 + 3))
                })
            try {
                val original = requireNotNull(android.graphics.BitmapFactory.decodeFile(java.io.File(directory, name).path))
                val padded = Bitmap.createBitmap(original.width + 64, original.height + 64, Bitmap.Config.ARGB_8888)
                padded.eraseColor(Color.WHITE)
                Canvas(padded).drawBitmap(original, 32f, 32f, null)
                val cropEditor = io.github.earthdiver.qrrepair.ui.ImageAdjustmentEditor(
                    org.robolectric.RuntimeEnvironment.getApplication(), padded, onCancel = {}, onApply = {})
                cropEditor.preview.restoreCrop(android.graphics.RectF(32f, 32f, original.width + 32f, original.height + 32f))
                val cropped = cropEditor.createCrop()
                println("EXTERNAL_CROP $name source=${original.width}x${original.height} cropped=${cropped.width}x${cropped.height}")
                cropEditor.dispose(); padded.recycle(); original.recycle()
                withDialog(settings, sourceBitmap = cropped) { dialog, host, source, _ ->
                    val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                    overlay.layout(0, 0, cropped.width, cropped.height)
                    var foundExpected = false
                    fun verify(stage: String, requireExpected: Boolean = false): Boolean {
                        shadowOf(Looper.getMainLooper()).idle()
                        val current = requireNotNull(host.session).captureDraft().settings
                        val accumulator = RecoveryResultAccumulator()
                        for (profile in current.variants()) AutoRecoveryEngine().run(source.luma,
                            profile = profile, resultAccumulator = accumulator)
                        val entries = accumulator.result().entries
                        val texts = entries.mapNotNull { it.candidate.text }.distinct()
                        println("EXTERNAL_EDITOR $name $stage candidates=${entries.size} matches=${expected in texts}")
                        println("EXTERNAL_PAYLOADS ${org.json.JSONObject().put("name", name).put("stage", stage).put("texts", org.json.JSONArray(texts))}")
                        foundExpected = foundExpected || expected in texts
                        if (requireExpected && expected !in texts) failures += "$name $stage: expected payload absent"
                        return expected in texts
                    }
                    fun touch(action: Int, point: PointD) {
                        val event = MotionEvent.obtain(0, 0, action, point.x.toFloat(), point.y.toFloat(), 0)
                        try { overlay.onTouchEvent(event) } finally { event.recycle() }
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    fun assign() {
                        for (role in FinderRole.entries) {
                            val point = overlay.expectedFinderCenter(role)
                            overlay.finderPlacementRole = role
                            touch(MotionEvent.ACTION_DOWN, point)
                            val confirmation = ShadowDialog.getLatestDialog()
                            if (confirmation !== dialog && confirmation is AlertDialog && confirmation.isShowing) {
                                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                                shadowOf(Looper.getMainLooper()).idle()
                            }
                        }
                    }
                    val baselineMatched = verify("cropped-detailed")
                    overlay.clearFinders(); overlay.setPhysicalImageQuad(overlay.imageQuad())
                    for (corner in 0..3) {
                        val point = overlay.imageQuad().asList()[corner]
                        touch(MotionEvent.ACTION_DOWN, point); touch(MotionEvent.ACTION_UP, point)
                        assertTrue(overlay.nudgeSelectedCorner(.25, 0.0))
                        assertTrue(overlay.nudgeSelectedCorner(-.25, 0.0))
                    }
                    assign(); verify("corners-and-finders", requireExpected = baselineMatched)
                    for ((_, point) in overlay.finderPoints()) {
                        overlay.beginFinderRemoval(); touch(MotionEvent.ACTION_DOWN, point)
                    }
                    if (overlay.visibleFinderRole != null) click(dialog, "位置未測定の役割指定を解除")
                    assertFalse(overlay.isLogicalGridAssigned())
                    assign(); verify("removed-and-reassigned", requireExpected = baselineMatched)
                    val boxes = views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                    val single = boxes.single { it.text.toString() == "単一セグメントの終端後構造を強制する" }
                    val utf8 = boxes.single { it.text.toString() == dialog.context.getString(R.string.assume_utf8_byte_segments) }
                    for ((singleEnabled, utf8Enabled) in listOf(false to false, true to false, false to true, true to true)) {
                        if (singleEnabled && !single.isEnabled) {
                            println("EXTERNAL_EDITOR $name single=$singleEnabled utf8=$utf8Enabled unavailable")
                            continue
                        }
                        single.isChecked = singleEnabled; utf8.isChecked = utf8Enabled
                        verify("single=$singleEnabled utf8=$utf8Enabled", requireExpected = false)
                    }
                    assertTrue("$name: expected payload absent under all supported assumptions", foundExpected)
                }
            } catch (error: Throwable) {
                failures += "$name: ${error.message}"
                error.printStackTrace()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun finderPlacementKeepsLmEnabledAndSavesItEvenWhenFittingFails() {
        withDialog(PreprocessSettings(version = 1, lmCorrectionEnabled = true)) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val lm = views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                .single { it.text.toString() == "LM法で格子を補正" }
            assertTrue(lm.isChecked)
            click(dialog, "左上")
            assertTrue(lm.isChecked)
            overlay.layout(0, 0, 240, 240)
            // The blank image has no Finder to detect, so commit the placement a detection would make.
            assertTrue(overlay.placeFinder(FinderRole.TOP_LEFT, overlay.expectedFinderCenter(FinderRole.TOP_LEFT)))
            assertTrue(overlay.finderPoints().containsKey(FinderRole.TOP_LEFT))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(lm.isChecked)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == "補正を適用できませんでした" })
            click(dialog, "保存")
            assertTrue(host.saves.last().settings.lmCorrectionEnabled)
            assertNull(host.saves.last().settings.lmGeometry)
        }
    }

    @Test fun finderReassignmentMovesTheSampledGridWithLmOffAndPreservesEdits() {
        val physical = ImageQuad(PointD(20.0, 25.0), PointD(220.0, 15.0), PointD(215.0, 225.0), PointD(25.0, 210.0))
        val edits = mapOf(Coord(10, 10) to ModuleValue.ONE)
        for (mirrored in listOf(false, true)) for (role in FinderRole.entries) for (count in 1..3) {
            val quad = if (mirrored) physical.transposed() else physical
            val geometry = GridGeometry(1, role, 50.0, 50.0, 10.0, 0.0, quad)
            val module = geometry.finderModulePoint(role)
            val oldPoint = geometry.moduleToImage(module.x, module.y)
            val target = oldPoint + PointD(4.0, -3.0)
            val finders = (listOf(role) + FinderRole.entries.filter { it != role }.take(count - 1)).associateWith {
                val m = geometry.finderModulePoint(it)
                geometry.moduleToImage(m.x, m.y)
            }
            val initial = PreprocessSettings(lmCorrectionEnabled = false, mirroredInput = mirrored,
                manualQuad = quad, manualFinders = finders, preRecoveryEdits = edits,
                lockManualBits = true)
            withDialog(initial) { dialog, host, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                overlay.layout(0, 0, 240, 240)
                // The blank image has no Finder to detect, so commit the moved Finder a detection would report.
                assertTrue(overlay.placeFinder(role, target))
                shadowOf(Looper.getMainLooper()).idle()
                val sampled = requireNotNull(overlay.previewGeometry())
                assertTrue(sampled.moduleToImage(module.x, module.y).distanceTo(target) < .02)
                (finders + (role to target)).forEach { (r, point) ->
                    val m = sampled.finderModulePoint(r)
                    assertTrue(sampled.moduleToImage(m.x, m.y).distanceTo(point) < .02)
                }
                val movedIndex = when (role) {
                    FinderRole.TOP_LEFT -> 0
                    FinderRole.TOP_RIGHT -> 1
                    FinderRole.BOTTOM_LEFT -> 3
                }
                quad.asList().zip(overlay.imageQuad().asList()).forEachIndexed { index, (before, after) ->
                    if (index == movedIndex) assertTrue(before.distanceTo(after) > 1.0)
                    else if (listOf(FinderRole.TOP_LEFT, FinderRole.TOP_RIGHT, null, FinderRole.BOTTOM_LEFT)[index] !in finders)
                        assertEquals(before, after)
                }
                click(dialog, "保存")
                val saved = host.saves.last().settings
                assertEquals(overlay.imageQuad(), saved.manualQuad)
                assertFalse(saved.lmCorrectionEnabled)
                assertEquals(edits, saved.fixedPreRecoveryEdits)
                assertTrue(saved.manualFinders.getValue(role).distanceTo(target) < .001)
                finders.filterKeys { it != role }.forEach { (r, point) -> assertEquals(point, saved.manualFinders[r]) }
            }
        }
    }

    /** A version 2 symbol filling the image without a quiet zone, with unknown gray data regions. */
    private fun edgeFinderSymbol(unit: Int): Bitmap {
        val n = 25
        fun black(x: Int, y: Int): Boolean {
            for ((sx, sy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                val dx = x - sx; val dy = y - sy
                if (dx in -1..7 && dy in -1..7) {
                    if (dx !in 0..6 || dy !in 0..6) return false
                    return dx == 0 || dy == 0 || dx == 6 || dy == 6 || (dx in 2..4 && dy in 2..4)
                }
            }
            if (x == 6 && y in 8..n - 9) return y % 2 == 0
            if (y == 6 && x in 8..n - 9) return x % 2 == 0
            return (x * 17 + y * 11 + x * y) % 7 < 3
        }
        val size = n * unit
        val pixels = IntArray(size * size) { i ->
            val x = (i % size) / unit; val y = (i / size) / unit
            when {
                (x in 9..16 && y in 9..20) || (x in 13..16 && y < 6) -> Color.rgb(128, 128, 128)
                black(x, y) -> Color.BLACK
                else -> Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
    }

    @Test fun finderTapOnTheFinderMovesToItsCenterWithoutConfirmation() {
        val unit = 8
        fun center(mx: Double, my: Double) = PointD((mx + .5) * unit, (my + .5) * unit)
        val truth = ImageQuad(center(0.0, 0.0), center(24.0, 0.0), center(24.0, 24.0), center(0.0, 24.0))
        val others = mapOf(FinderRole.TOP_RIGHT to center(21.0, 3.0), FinderRole.BOTTOM_LEFT to center(3.0, 21.0))
        // Taps anywhere on the top-left Finder of a symbol without a quiet zone, away from its center.
        for ((dx, dy) in listOf(1.0 to 0.0, 1.5 to 1.5, 2.5 to 0.0, 0.0 to 2.5, 3.0 to 3.0)) {
            val bitmap = edgeFinderSymbol(unit)
            val initial = PreprocessSettings(version = 2, manualQuad = truth, manualFinders = others, lmCorrectionEnabled = false)
            withDialog(initial, sourceBitmap = bitmap) { dialog, _, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                overlay.layout(0, 0, bitmap.width, bitmap.height)
                overlay.finderPlacementRole = FinderRole.TOP_LEFT
                val tap = center(3.0 + dx, 3.0 + dy)
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, tap.x.toFloat(), tap.y.toFloat(), 0)
                try { assertTrue(overlay.onTouchEvent(event)) } finally { event.recycle() }
                shadowOf(Looper.getMainLooper()).idle()
                val label = "tap offset ($dx, $dy)"
                assertSame(label, dialog, ShadowDialog.getLatestDialog())
                assertTrue(label, overlay.finderPoints().getValue(FinderRole.TOP_LEFT).distanceTo(center(3.0, 3.0)) < unit * .15)
                truth.asList().zip(overlay.imageQuad().asList()).forEach { (expected, actual) ->
                    assertTrue("$label: $actual vs $expected", actual.distanceTo(expected) < unit * .15)
                }
            }
        }
    }

    @Test fun finderTapWithoutADetectedFinderOnlyAssignsTheRoleAndKeepsTheGrid() {
        val unit = 8
        val size = 25 * unit
        fun center(mx: Double, my: Double) = PointD((mx + .5) * unit, (my + .5) * unit)
        val physical = ImageQuad(center(0.0, 0.0), center(24.0, 0.0), center(24.0, 24.0), center(0.0, 24.0))
        fun tap(overlay: PreprocessOverlayView, point: PointD) {
            overlay.finderPlacementRole = FinderRole.TOP_LEFT
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, point.x.toFloat(), point.y.toFloat(), 0)
            try { assertTrue(overlay.onTouchEvent(event)) } finally { event.recycle() }
            shadowOf(Looper.getMainLooper()).idle()
        }
        // A blank image has no Finder anywhere, so every tap below is undetected.
        fun blank() = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        // On an unoriented grid, the tap names the grid corner whose Finder position is nearest.
        val bitmap = blank()
        withDialog(PreprocessSettings(version = 2, manualQuad = physical, logicalGridAssigned = false, lmCorrectionEnabled = false),
            sourceBitmap = bitmap) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            overlay.layout(0, 0, size, size)
            tap(overlay, center(20.5, 21.5))
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(FinderRole.TOP_LEFT, overlay.visibleFinderRole)
            assertTrue(overlay.finderPoints().isEmpty())
            // The corners keep their positions; only their roles turn to put the tapped corner at the top left.
            assertEquals(physical.bottomRight, overlay.imageQuad().topLeft)
            assertEquals(physical.asList().toSet(), overlay.imageQuad().asList().toSet())
            // No Finder constraint is added, so every corner stays adjustable, and the role is saved.
            click(dialog, "保存")
            val saved = host.saves.last().settings
            assertTrue(saved.logicalGridAssigned)
            assertEquals(FinderRole.TOP_LEFT, saved.visibleFinderRole)
            assertTrue(saved.manualFinders.isEmpty())
        }

        // A tap away from every grid corner, or without any grid to name, assigns nothing.
        for ((settings, point) in listOf(
            PreprocessSettings(version = 2, manualQuad = physical, logicalGridAssigned = false, lmCorrectionEnabled = false) to center(12.0, 12.0),
            PreprocessSettings(version = 2, logicalGridAssigned = false, lmCorrectionEnabled = false) to center(3.0, 3.0),
        )) {
            val image = blank()
            withDialog(settings, sourceBitmap = image) { dialog, _, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                overlay.layout(0, 0, size, size)
                val before = overlay.imageQuad()
                tap(overlay, point)
                assertFalse("$point", overlay.isLogicalGridAssigned())
                assertNull(overlay.visibleFinderRole)
                assertTrue(overlay.finderPoints().isEmpty())
                assertEquals(before, overlay.imageQuad())
            }
        }
    }

    @Test fun finderTapOutsideTheFinderIsConfirmedAndCancellingRestoresTheGrid() {
        val unit = 8
        fun center(mx: Double, my: Double) = PointD((mx + .5) * unit, (my + .5) * unit)
        val truth = ImageQuad(center(0.0, 0.0), center(24.0, 0.0), center(24.0, 24.0), center(0.0, 24.0))
        val others = mapOf(FinderRole.TOP_RIGHT to center(21.0, 3.0), FinderRole.BOTTOM_LEFT to center(3.0, 21.0))
        for (accept in listOf(false, true)) {
            val bitmap = edgeFinderSymbol(unit)
            val initial = PreprocessSettings(version = 2, manualQuad = truth, manualFinders = others, lmCorrectionEnabled = false)
            withDialog(initial, sourceBitmap = bitmap) { dialog, _, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                overlay.layout(0, 0, bitmap.width, bitmap.height)
                val finders = overlay.finderPoints()
                overlay.finderPlacementRole = FinderRole.TOP_LEFT
                // The separator and the data next to it, outside the Finder's 7x7 modules.
                val tap = center(7.5, 3.0)
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, tap.x.toFloat(), tap.y.toFloat(), 0)
                try { assertTrue(overlay.onTouchEvent(event)) } finally { event.recycle() }
                shadowOf(Looper.getMainLooper()).idle()
                val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
                assertNotSame(dialog, confirmation)
                // Until the user decides, the tapped point is not a Finder center.
                assertEquals(truth, overlay.imageQuad())
                assertEquals(finders, overlay.finderPoints())
                confirmation.getButton(if (accept) AlertDialog.BUTTON_POSITIVE else AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                if (accept) {
                    assertTrue(overlay.finderPoints().getValue(FinderRole.TOP_LEFT).distanceTo(center(3.0, 3.0)) < unit * .15)
                    truth.asList().zip(overlay.imageQuad().asList()).forEach { (expected, actual) -> assertTrue(actual.distanceTo(expected) < unit * .15) }
                } else {
                    assertEquals(truth, overlay.imageQuad())
                    assertEquals(finders, overlay.finderPoints())
                }
            }
        }
    }

    @Test fun resettingZeroRotationPreservesTheRectifiedImageGridAndDraft() {
        val perspective = ImageQuad(PointD(20.0, 30.0), PointD(220.0, 20.0),
            PointD(210.0, 220.0), PointD(30.0, 210.0))
        val rectified = BitmapUtils.rectifiedGrid(perspective, 1)
        val edits = mapOf(Coord(10, 10) to ModuleValue.ONE)
        val initial = PreprocessSettings(perspectiveCorrectionQuad = perspective, manualQuad = rectified,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0)), preRecoveryEdits = edits,
            lmCorrectionEnabled = true, lockManualBits = true)
        withDialog(initial) { dialog, host, _, working ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            click(dialog, "保存")
            val before = host.saves.last()
            click(dialog, "詳細設定")
            click(dialog, "回転リセット")
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(rectified, overlay.imageQuad())
            assertEquals(initial.manualFinders, overlay.finderPoints())
            click(dialog, "保存")
            val after = host.saves.last()
            assertSame(working.bitmap, after.image.bitmap)
            assertEquals(before.settings, after.settings)
            assertEquals(before.events, after.events)
            assertEquals(edits, after.settings.fixedPreRecoveryEdits)
        }
    }

    @Test fun japaneseDialogFixtureHandlesAndRestoresAnEnglishIncomingLanguage() =
        withTestAppLanguage(AppLanguage.ENGLISH) {
            withDialog { dialog, _, _, _ ->
                assertEquals(AppLanguage.JAPANESE, AppLanguageManager.current)
                assertEquals("ja", dialog.context.resources.configuration.locales[0].language)
                assertNotNull(automaticThresholdControl(dialog))
                click(dialog, "保存")
            }
            assertEquals(AppLanguage.ENGLISH, AppLanguageManager.current)
        }

    @Test fun pendingCorrectionCannotBeSavedAndSupersededJobsDoNotRestoreTheOldGrid() {
        for (style in AppStyle.entries) {
            val jobs = mutableListOf<Runnable>()
            val initialQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
            withDialog(PreprocessSettings(lmCorrectionEnabled = false, distortionCorrection = true, manualQuad = initialQuad), style,
                worker = Executor { jobs += it }) { dialog, host, _, _ ->
                assertEquals(1, jobs.size)
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<Button>()
                    .any { it.text.toString() == "曲面補正: 計算中…" })
                views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == "保存" }.performClick()
                assertTrue(host.saves.isEmpty())
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                val moved = initialQuad.copy(topLeft = PointD(24.0, 20.0))
                overlay.setImageQuad(moved)
                overlay.onGeometryEdited!!.invoke()
                overlay.onGeometryEditFinished!!.invoke()
                assertEquals(2, jobs.size)
                jobs[0].run()
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<Button>().any { it.text.toString().contains("計算中") })
                host.session!!.open(PreprocessPage.CELL_EDITOR)
                jobs[1].run()
                shadowOf(Looper.getMainLooper()).idle()
                overlay.layout(0, 0, 600, 600)
                var selected: Coord? = null
                overlay.onEditCellSelected = { x, y -> selected = Coord(x, y) }
                val point = overlay.previewGeometry()!!.moduleToImage(10.0, 10.0)
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    android.view.MotionEvent.obtain(0, 10, action, (point.x * 2.5).toFloat(), (point.y * 2.5).toFloat(), 0).also {
                        overlay.onTouchEvent(it); it.recycle()
                    }
                }
                assertEquals(Coord(10, 10), selected)
                host.session!!.open(PreprocessPage.BASIC)
                assertTrue(views(dialog.window!!.decorView).filterIsInstance<Button>().any { it.text.toString().contains("直線格子") })
                views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == "保存" }.performClick()
                assertEquals(moved, host.saves.single().settings.previewGeometry!!.imageQuad)
                assertEquals(moved, overlay.previewGeometry()!!.imageQuad)
            }
        }
    }

    @Test fun basicSettingsRestoreAndSaveTheChangedCorrectionChoice() {
        for (style in AppStyle.entries) for (enabled in listOf(false, true)) {
            withDialog(PreprocessSettings(distortionCorrection = enabled), style) { dialog, host, _, _ ->
                val label = dialog.context.getString(R.string.image_distortion_correction)
                val toggle = views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                    .single { it.text.toString() == label }
                assertEquals(enabled, toggle.isChecked)
                val group = toggle.parent as ViewGroup
                val candidate = group.getChildAt(group.indexOfChild(toggle) + 1)
                assertTrue(candidate is Button)
                assertEquals(if (enabled) View.VISIBLE else View.GONE, candidate.visibility)
                toggle.performClick()
                assertEquals(if (enabled) View.GONE else View.VISIBLE, candidate.visibility)
                automaticThresholdControl(dialog).isChecked = true
                click(dialog, "保存")
                assertEquals(!enabled, host.saves.single().settings.distortionCorrection)
            }
        }
    }

    @Test fun restoredCurvedPreviewWaitsForWorkerSamplingAndRefreshesTheOpenModernDataDialog() {
        val jobs = mutableListOf<Runnable>()
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
        val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 50.0, 50.0, 10.0, 0.0,
            quad, GeometryModel.THIN_PLATE_SPLINE, ThinPlateWarp(listOf(
                WarpLandmark(PointD(0.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 0.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(0.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(1.0, 1.0), PointD(0.0, 0.0)),
                WarpLandmark(PointD(.5, .5), PointD(4.0, -3.0)),
            ))).reanchored()
        val settings = PreprocessSettings(lmCorrectionEnabled = false, distortionCorrection = true, manualQuad = quad, previewGeometry = geometry,
            formatConstraint = QrFormatConstraint(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, 0))
        withDialog(settings, AppStyle.MODERN, worker = Executor { jobs += it }) { dialog, host, _, _ ->
            // Construction queues sampling without observing all warped cells on the UI thread.
            assertEquals(2, jobs.size)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertNull(PreprocessOverlayView::class.java.getDeclaredField("editMatrix").run {
                isAccessible = true; get(overlay)
            })
            click(dialog, "保存")
            assertTrue(host.saves.isEmpty())
            click(dialog, dialog.context.getString(R.string.data))
            val dataDialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(views(dataDialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == "セルを読み取り中…" })
            jobs[1].run()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(geometry, overlay.previewGeometry())
            assertTrue(views(dataDialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains("Binary") })
            assertFalse(views(dataDialog.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString() == "セルを読み取り中…" })
            jobs[0].run()
            shadowOf(Looper.getMainLooper()).idle()
            dataDialog.dismiss()
            click(dialog, "保存")
            assertEquals(geometry, host.saves.single().settings.previewGeometry)
            click(dialog, dialog.context.getString(R.string.data))
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(reopened.isShowing)
        }
    }

    @Test fun cornerLabelsPreferOutsideAndUseTheSideWithSpaceAtTheScreenEdge() {
        val corners = listOf(PointD(60.0, 4.0), PointD(160.0, 4.0), PointD(160.0, 130.0), PointD(60.0, 130.0))
        for (quad in listOf(corners, corners.reversed())) {
            val label = io.github.earthdiver.qrrepair.ui.cornerLabelCenter(quad,
                quad.indexOf(corners.first()), 12.0, 8.0, 220, 180, 16.0)
            assertTrue(label.x + 12 < 60)
            assertTrue(label.y >= 8)
        }
    }

    @Test fun imageWithoutAnEstimatedGridRequiresAFinderRoleBeforeRepair() {
        val image = LumaImage(240, 240, IntArray(240 * 240) { 255 })
        val estimate = InitialGridEstimator.estimate(image, emptyList())
        assertNull(estimate)
        checkRequiresFinderAfterSaving(PreprocessSettings().withInitialGrid(estimate))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun selectingAVersionShowsAnUnorientedGridUntilAFinderRoleIsAssigned() {
        fun assertGridVisible(overlay: PreprocessOverlayView, visible: Boolean) {
            overlay.layout(0, 0, 240, 240)
            val rendered = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            try {
                overlay.draw(Canvas(rendered))
                // Inspect the interior, away from the cyan outer boundary and corner handles.
                val cyanInside = (80 until 160).any { y -> (80 until 160).any { x ->
                    val pixel = rendered.getPixel(x, y)
                    Color.red(pixel) < 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240
                } }
                assertEquals("Interior grid visibility", visible, cyanInside)
            } finally { rendered.recycle() }
        }
        for (style in AppStyle.entries) for (mirrored in listOf(false, true)) for (requested in listOf(1, 2)) {
            val initial = PreprocessSettings(mirroredInput = mirrored).withInitialGrid(null)
            lateinit var savedSettings: PreprocessSettings
            withDialog(initial, style) { dialog, host, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                val originalQuad = overlay.imageQuad()
                assertGridVisible(overlay, false)
                click(dialog, "QRバージョン: 1 / 21×21セル")
                (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
                assertGridVisible(overlay, false)
                click(dialog, "QRバージョン: 1 / 21×21セル")
                (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null,
                    requested - 1, (requested - 1).toLong())
                assertGridVisible(overlay, true)
                assertEquals(originalQuad, overlay.imageQuad())
                assertFalse(overlay.isLogicalGridAssigned())
                assertTrue(overlay.finderPoints().isEmpty())
                click(dialog, "保存")
                val saved = host.saves.single()
                savedSettings = saved.settings
                assertEquals(requested, savedSettings.version)
                assertEquals(originalQuad, savedSettings.manualQuad)
                assertNull(savedSettings.logicalQuad)
                assertEquals(0, recover(saved).attemptedGeometries)
            }
            withDialog(savedSettings, style) { dialog, host, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                assertGridVisible(overlay, true)
                assertFalse(overlay.isLogicalGridAssigned())
                overlay.setFinderPoint(FinderRole.TOP_LEFT, overlay.expectedFinderCenter(FinderRole.TOP_LEFT))
                click(dialog, "保存")
                assertTrue(recover(host.saves.single()).attemptedGeometries > 0)
            }
        }
    }

    @Test fun selectingTheCurrentVersionPreservesFinderAssignmentsAndCellEdits() {
        val roles = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0))
        val edits = mapOf(Coord(10, 12) to ModuleValue.ONE)
        val initial = PreprocessSettings(manualFinders = roles, preRecoveryEdits = edits)
        withDialog(initial) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val originalQuad = overlay.imageQuad()
            click(dialog, "QRバージョン: 1 / 21×21セル")
            (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null, 0, 0L)
            click(dialog, "保存")
            val saved = host.saves.single().settings
            assertEquals(originalQuad, saved.logicalQuad)
            assertEquals(roles, saved.manualFinders)
            assertEquals(edits, saved.preRecoveryEdits)
        }
    }

    @Test fun importedProvisionalCornersRequireAFinderRoleBeforeRepair() {
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
            PointD(220.0, 220.0), PointD(20.0, 220.0))
        val estimate = InitialGridEstimate(1, quad, InitialGridSource.PERIODICITY)
        checkRequiresFinderAfterSaving(PreprocessSettings().withInitialGrid(estimate))
    }

    @Test fun importedAutomaticFinderRolesAllowRepairWithoutManualAssignment() {
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0),
            PointD(220.0, 220.0), PointD(20.0, 220.0))
        val roles = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0),
            FinderRole.TOP_RIGHT to PointD(190.0, 50.0), FinderRole.BOTTOM_LEFT to PointD(50.0, 190.0))
        val estimate = InitialGridEstimate(1, quad, InitialGridSource.ZXING_CPP, roles, FinderPolarity.NORMAL)
        withDialog(PreprocessSettings().withInitialGrid(estimate)) { dialog, host, _, _ ->
            click(dialog, "設定を保存して修復")
            val saved = host.saves.single()
            assertEquals(quad, saved.settings.logicalQuad)
            assertEquals(roles, saved.settings.manualFinders)
            assertEquals(FinderPolarity.NORMAL, saved.settings.confirmedFinderPolarity)
            assertTrue(recover(saved).attemptedGeometries > 0)
        }
    }

    @Test fun rectificationPreservesFinderRolesPolarityAndFixedEdits() {
        for (mirrored in listOf(false, true)) for (style in AppStyle.entries) {
            val physicalQuad = ImageQuad(PointD(30.0, 20.0), PointD(210.0, 30.0),
                PointD(220.0, 220.0), PointD(20.0, 210.0))
            val initial = PreprocessSettings(mirroredInput = mirrored,
                manualQuad = if (mirrored) physicalQuad.transposed() else physicalQuad,
                manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(55.0, 50.0)),
                confirmedFinderPolarity = FinderPolarity.NORMAL,
                preRecoveryEdits = mapOf(Coord(10, 12) to ModuleValue.ONE),
                searchQuadNeighborhood = false)
            withDialog(initial, style) { dialog, host, _, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                assertTrue(overlay.isLogicalGridAssigned())
                click(dialog, "選択範囲を正方形に射影補正")
                assertNotEquals(initial.manualQuad, overlay.imageQuad())
                val rectified = overlay.imageQuad().let { if (mirrored) it.transposed() else it }
                assertEquals(rectified.topLeft.y, rectified.topRight.y, .001)
                assertEquals(rectified.topLeft.x, rectified.bottomLeft.x, .001)
                assertEquals(rectified.topLeft.distanceTo(rectified.topRight),
                    rectified.topLeft.distanceTo(rectified.bottomLeft), .001)
                assertTrue(overlay.isLogicalGridAssigned())
                assertEquals(initial.manualFinders.keys, overlay.finderPoints().keys)
                assertEquals(initial.confirmedFinderPolarity, overlay.confirmedFinderPolarity())
                click(dialog, "保存")
                assertEquals(initial.preRecoveryEdits, host.saves.last().settings.preRecoveryEdits)
                assertTrue(host.saves.last().settings.lmCorrectionEnabled)
            }
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun rotationAndResetKeepEditsAndHistoryOnTheSameQrModules() {
        val modules = com.google.zxing.qrcode.encoder.Encoder.encode("ROTATION CHECK",
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            mapOf(com.google.zxing.EncodeHintType.QR_VERSION to 1)).matrix
        val matrix = QrMatrix(21)
        for (y in 0..20) for (x in 0..20) matrix.set(x, y,
            if (modules[x, y].toInt() == 1) ModuleValue.ONE else ModuleValue.ZERO, 1f, ModuleSource.IMAGE)
        val physicalQuad = ImageQuad(PointD(35.5, 35.5), PointD(195.5, 35.5),
            PointD(195.5, 195.5), PointD(35.5, 195.5))
        val edits = mapOf(Coord(10, 12) to ModuleValue.ONE, Coord(11, 12) to ModuleValue.ZERO,
            Coord(12, 12) to ModuleValue.ERASED, Coord(13, 12) to ModuleValue.ERASED)
        val soft = setOf(Coord(11, 12), Coord(12, 12), Coord(13, 12))
        val priority = setOf(Coord(13, 12))
        val extra = Coord(14, 12)
        val history = PreRecoveryCellEditState(edits, priority, initialSoftEdits = soft).apply {
            applyValues(listOf(extra), ModuleValue.ONE) { ModuleValue.ZERO }
            undo()
        }
        for (mirrored in listOf(false, true)) for (rectified in listOf(false, true)) {
            val logicalQuad = if (mirrored) physicalQuad.transposed() else physicalQuad
            val initial = PreprocessSettings(mirroredInput = mirrored, manualQuad = logicalQuad,
                manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(59.5, 59.5)),
                preRecoveryEdits = edits, preRecoverySoftEdits = soft, preRecoveryPrioritySearch = priority)
            withDialog(initial, sourceBitmap = BitmapUtils.renderTextMatrixSource(matrix), savedCellEdits = history) { dialog, host, source, _ ->
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                if (rectified) click(dialog, "選択範囲を正方形に射影補正")
                click(dialog, "詳細設定")
                for (action in listOf("右90°", "回転リセット", "左90°", "+1°", "-1°", "+0.1°", "-0.1°", "回転リセット")) {
                    click(dialog, action)
                    assertTrue(overlay.isLogicalGridAssigned())
                    assertEquals(initial.manualFinders.keys, overlay.finderPoints().keys)
                    click(dialog, "保存")
                    val saved = host.saves.last()
                    assertTrue(saved.settings.lmCorrectionEnabled)
                    assertEquals(if (rectified) physicalQuad else null, saved.settings.perspectiveCorrectionQuad)
                    val transform = BitmapUtils.imageTransform(source.bitmap, saved.settings).sourceToImage
                    logicalQuad.asList().zip(overlay.imageQuad().asList()).forEach { (before, after) ->
                        assertTrue("$action preserves the ordered grid: $before -> $after",
                            transform.map(before).distanceTo(after) < .001)
                    }
                    assertTrue(transform.map(PointD(59.5, 59.5)).distanceTo(overlay.finderPoints().getValue(FinderRole.TOP_LEFT)) < .001)
                    assertEquals(edits, saved.settings.preRecoveryEdits)
                    assertEquals(soft, saved.settings.preRecoverySoftEdits)
                    assertEquals(priority, saved.settings.preRecoveryPrioritySearch)
                    val restored = saved.cellEdits.copyForEditing()
                    assertEquals(1, restored.undoCount)
                    assertEquals(1, restored.redoCount)
                    assertEquals(setOf(extra), restored.redo())
                    assertEquals(edits + (extra to ModuleValue.ONE), restored.edits)
                    restored.undo()
                    restored.undo()
                    assertTrue(restored.edits.isEmpty())
                    restored.redo()
                    assertEquals(edits, restored.edits)
                    assertEquals(soft, restored.softEdits)
                    assertEquals(priority, restored.prioritySearch)
                    val mapping = Homography.fromCorrespondences(
                        listOf(PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 20.0), PointD(0.0, 20.0)),
                        overlay.imageQuad().asList())
                    for (y in 0..20) for (x in 0..20) {
                        val p = mapping.map(x.toDouble(), y.toDouble())
                        val luma = requireNotNull(saved.image.luma.sampleMean(p.x, p.y, 0.0))
                        val expected = if (mirrored) modules[y, x].toInt() else modules[x, y].toInt()
                        assertEquals("$action module ($x, $y)", expected, if (luma < 128) 1 else 0)
                    }
                }
            }
        }
    }

    @Test fun rectifiedVersionChangesKeepThePreviewAndSavedGridAligned() {
        for (mirrored in listOf(false, true)) for (assigned in listOf(false, true)) {
            checkRectifiedVersionChange(mirrored, assigned)
        }
    }

    @Test fun imageTransformControlsKeepCorrectionPreferencesAndClearOnlyPerspective() {
        val quad = ImageQuad(PointD(30.0, 20.0), PointD(210.0, 30.0), PointD(220.0, 220.0), PointD(20.0, 210.0))
        for (lm in listOf(false, true)) for (curved in listOf(false, true)) {
            val initial = PreprocessSettings(manualQuad = quad, manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(55.0, 50.0)),
                lmCorrectionEnabled = lm, distortionCorrection = curved, preRecoveryEdits = mapOf(Coord(10, 10) to ModuleValue.ONE))
            withDialog(initial) { dialog, host, _, _ ->
                fun saved(): PreprocessSettings {
                    click(dialog, "保存")
                    return host.saves.last().settings.also {
                        assertEquals(lm, it.lmCorrectionEnabled)
                        assertEquals(curved, it.distortionCorrection)
                        assertEquals(initial.preRecoveryEdits, it.preRecoveryEdits)
                        assertTrue(it.logicalGridAssigned)
                        assertEquals(initial.manualFinders.keys, it.manualFinders.keys)
                    }
                }
                click(dialog, "選択範囲を正方形に射影補正")
                assertEquals(quad, saved().perspectiveCorrectionQuad)
                click(dialog, "詳細設定")
                click(dialog, "右90°")
                assertEquals(quad, saved().perspectiveCorrectionQuad)
                host.session!!.open(PreprocessPage.BASIC)
                click(dialog, "射影補正を解除")
                val cleared = saved()
                assertNull(cleared.perspectiveCorrectionQuad)
                assertEquals(90f, cleared.rotationDegrees)
                host.session!!.open(PreprocessPage.DETAILS)
                click(dialog, "回転リセット")
                val reset = saved()
                assertEquals(0f, reset.rotationDegrees)
                quad.asList().zip(reset.manualQuad!!.asList()).forEach { (before, after) ->
                    assertTrue(before.distanceTo(after) < 1e-8)
                }
            }
        }
    }

    private fun checkRectifiedVersionChange(mirrored: Boolean, assigned: Boolean) {
        val sourceSize = 240
        val physicalQuad = ImageQuad(PointD(20.0, 20.0), PointD(sourceSize - 20.0, 20.0),
            PointD(sourceSize - 20.0, sourceSize - 20.0), PointD(20.0, sourceSize - 20.0))
        val initial = PreprocessSettings(rotationDegrees = 15f, mirroredInput = mirrored,
            imageAdjustment = ImageAdjustment(20, -10, false),
            manualQuad = if (mirrored) physicalQuad.transposed() else physicalQuad)
        fun sourceBitmap() = Bitmap.createBitmap(IntArray(sourceSize * sourceSize) { i ->
            Color.rgb((i % sourceSize) * 255 / sourceSize, (i / sourceSize) * 255 / sourceSize,
                (i % sourceSize + i / sourceSize) % 256)
        }, sourceSize, sourceSize, Bitmap.Config.ARGB_8888)
        lateinit var savedSettings: PreprocessSettings
        lateinit var savedPixels: IntArray
        withDialog(initial, sourceBitmap = sourceBitmap()) { dialog, host, source, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            click(dialog, "選択範囲を正方形に射影補正")
            val sourceQuad = BitmapUtils.sourceQuad(source.bitmap, initial, physicalQuad)
            val referenceSettings = initial.copy(perspectiveCorrectionQuad = sourceQuad)
            val toSource = BitmapUtils.imageTransform(source.bitmap, referenceSettings).sourceToImage.inverse()
            // Preserve a user's corner adjustment as well as logical corner ordering.
            val adjusted = overlay.imageQuad().let { it.copy(bottomRight = it.bottomRight + PointD(2.0, -1.0)) }
            overlay.setPhysicalImageQuad(adjusted)
            if (assigned) {
                for ((role, polarity) in listOf(FinderRole.TOP_LEFT to FinderPolarity.NORMAL,
                    FinderRole.TOP_RIGHT to FinderPolarity.INVERTED)) {
                    val point = overlay.expectedFinderCenter(role)
                    overlay.setVerifiedFinder(role, FinderCandidate(point.x, point.y, 10.0, 0.0, 1.0, 5,
                        polarity = polarity))
                }
            }
            val originalQuad = overlay.imageQuad()
            val originalFinders = overlay.finderPoints()
            val unadjusted = BitmapUtils.forSettings(source, initial.copy(imageAdjustment = ImageAdjustment()))
            try {
                var currentVersion = 1
                for (requestedVersion in listOf(2, 10, 1)) {
                    val dimension = 17 + 4 * currentVersion
                    click(dialog, "QRバージョン: $currentVersion / ${dimension}×${dimension}セル")
                    (ShadowDialog.getLatestDialog() as AlertDialog).listView.performItemClick(null,
                        requestedVersion - 1, (requestedVersion - 1).toLong())
                    click(dialog, "保存")
                    val saved = host.saves.last()
                    savedSettings = saved.settings
                    assertEquals(requestedVersion, savedSettings.version)
                    assertEquals(assigned, savedSettings.logicalGridAssigned)
                    assertEquals(initial.mirroredInput, savedSettings.mirroredInput)
                    assertEquals(initial.imageAdjustment, savedSettings.imageAdjustment)
                    val rebuilt = BitmapUtils.forSettings(source, savedSettings)
                    try {
                        assertEquals(rebuilt.bitmap.width, saved.image.bitmap.width)
                        assertTrue(rebuilt.bitmap.sameAs(saved.image.bitmap))
                        assertArrayEquals(rebuilt.luma.pixels, saved.image.luma.pixels)
                        val mapping = BitmapUtils.imageTransform(source.bitmap, savedSettings).sourceToImage
                        fun assertMapped(before: PointD, after: PointD) {
                            assertTrue(mapping.map(toSource.map(before)).distanceTo(after) < 1e-6)
                        }
                        originalQuad.asList().zip(savedSettings.manualQuad!!.asList()).forEach { (before, after) ->
                            assertMapped(before, after)
                        }
                        assertEquals(originalFinders.keys, savedSettings.manualFinders.keys)
                        originalFinders.forEach { (role, point) -> assertMapped(point, savedSettings.manualFinders.getValue(role)) }
                    } finally {
                        rebuilt.bitmap.recycle()
                    }
                    currentVersion = requestedVersion
                }
                savedPixels = host.saves.last().image.luma.pixels.copyOf()
                if (assigned) {
                    // Conflicting evidence must survive remapping independently for each role.
                    assertEquals(FinderPolarity.UNKNOWN, overlay.confirmedFinderPolarity())
                    assertTrue(overlay.removeConfirmedFinder(FinderRole.TOP_LEFT))
                    assertEquals(FinderPolarity.INVERTED, overlay.confirmedFinderPolarity())
                }
            } finally {
                if (unadjusted.bitmap !== source.bitmap) unadjusted.bitmap.recycle()
            }
        }
        withDialog(savedSettings, sourceBitmap = sourceBitmap()) { dialog, _, _, working ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertEquals(savedSettings.manualQuad, overlay.imageQuad())
            assertEquals(savedSettings.manualFinders, overlay.finderPoints())
            assertEquals(assigned, overlay.isLogicalGridAssigned())
            assertArrayEquals(savedPixels, working.luma.pixels)
        }
    }

    private fun recover(saved: PreprocessSave) = RecoveryWorkflow(RecoveryEngine()).run(
        saved.image.luma, saved.settings, RecoveryRetentionBudget())

    private fun checkRequiresFinderAfterSaving(
        initial: PreprocessSettings,
        style: AppStyle = AppStyle.CLASSIC,
        prepare: (AlertDialog, PreprocessOverlayView) -> Unit = { _, _ -> },
    ) {
        lateinit var savedSettings: PreprocessSettings
        lateinit var physicalQuad: ImageQuad
        val repairLabel = if (style == AppStyle.CLASSIC) "設定を保存して修復" else "修復"
        withDialog(initial, style) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            prepare(dialog, overlay)
            assertFalse(overlay.isLogicalGridAssigned())
            assertTrue(overlay.finderPoints().isEmpty())
            physicalQuad = overlay.imageQuad()
            click(dialog, repairLabel)
            assertEquals(listOf("save", "repair"), host.effects)
            val saved = host.saves.single()
            savedSettings = saved.settings
            assertEquals(physicalQuad, savedSettings.manualQuad)
            assertEquals(initial.preRecoveryEdits, savedSettings.preRecoveryEdits)
            assertNull(savedSettings.logicalQuad)
            assertEquals(FinderPolarity.UNKNOWN, savedSettings.confirmedFinderPolarity)
            val result = recover(saved)
            assertEquals(0, result.attemptedGeometries)
            assertTrue(result.attempts.isEmpty())
            assertTrue(result.finders.isEmpty())
        }
        withDialog(savedSettings, style) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertEquals(physicalQuad, overlay.imageQuad())
            assertFalse(overlay.isLogicalGridAssigned())
            assertTrue(overlay.finderPoints().isEmpty())
            overlay.setFinderPoint(FinderRole.TOP_LEFT, overlay.expectedFinderCenter(FinderRole.TOP_LEFT))
            click(dialog, repairLabel)
            val saved = host.saves.single()
            assertEquals(setOf(FinderRole.TOP_LEFT), saved.settings.manualFinders.keys)
            assertNotNull(saved.settings.logicalQuad)
            assertEquals(initial.preRecoveryEdits, saved.settings.preRecoveryEdits)
            assertTrue(recover(saved).attemptedGeometries > 0)
        }
    }

    @Test fun advancedPriorityLimitSavesAndLoweringItPreservesExistingMarks() {
        val coords = CodewordExtractor.extract(QrMatrix(21), 1, 0).codewords.take(14).map { it.coordinates.first() }.toSet()
        val settings = PreprocessSettings(prioritySearchLimit = 14,
            preRecoveryEdits = coords.associateWith { ModuleValue.ERASED }, preRecoveryPrioritySearch = coords,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            click(dialog, "詳細設定")
            shadowOf(Looper.getMainLooper()).idle()
            val limit = views(dialog.window!!.decorView).filterIsInstance<TextView>()
                .single { it.text.startsWith(" * （優先探索）の上限:") }
            assertTrue(limit.text.contains("16384"))
            limit.performClick()
            val choices = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(11, choices.listView.adapter.count)
            assertEquals("14", choices.listView.adapter.getItem(10))
            choices.listView.performItemClick(null, 0, 0)
            val notice = ShadowDialog.getLatestDialog() as AlertDialog
            notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            click(dialog, "保存")
            assertEquals(4, host.saves.last().settings.prioritySearchLimit)
            assertEquals(coords, host.saves.last().settings.preRecoveryPrioritySearch)
        }
    }

    @Test fun priorityButtonHighlightsMarkerFitsItsTextAndPrecedesSamplingAdjustment() {
        for (style in AppStyle.entries) withDialog(style = style) { dialog, _, _, _ ->
            click(dialog, "詳細設定")
            shadowOf(Looper.getMainLooper()).idle()
            val button = views(dialog.window!!.decorView).filterIsInstance<android.widget.Button>()
                .single { it.text.startsWith(" * （優先探索）の上限:") }
            val text = button.text as android.text.Spanned
            val highlight = text.getSpans(0, text.length, android.text.style.BackgroundColorSpan::class.java).single()
            assertEquals(" * ", text.subSequence(text.getSpanStart(highlight), text.getSpanEnd(highlight)).toString())
            val parent = button.parent as ViewGroup
            val next = parent.getChildAt(parent.indexOfChild(button) + 1)
            assertTrue(next is CheckBox)
            assertEquals("サンプリング位置を自動微調整する", (next as CheckBox).text.toString())
            for (width in listOf(240, 320)) {
                button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                button.layout(0, 0, button.measuredWidth, button.measuredHeight)
                val layout = button.layout
                assertTrue("$style/$width: text must fit inside vertical padding",
                    layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
                for (line in 0 until layout.lineCount) {
                    assertEquals(0, layout.getEllipsisCount(line))
                    assertTrue(layout.getLineWidth(line) <= layout.width)
                }
            }
        }
    }

    @Test fun importedAdjustmentsArePreservedWithoutOfferingAnotherImageEditor() {
        val edits = mapOf(Coord(12, 12) to ModuleValue.ONE)
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0))
        val tones = ImageAdjustment(25, -10, true)
        val settings = PreprocessSettings(rotationDegrees = 15f, polarity = PolarityMode.NORMAL, imageAdjustment = tones,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0)),
            confirmedFinderPolarity = FinderPolarity.NORMAL, manualQuad = quad,
            preRecoveryEdits = edits, lockManualBits = true)
        for (style in AppStyle.entries) withDialog(settings, style) { dialog, host, source, _ ->
            assertFalse(views(dialog.window!!.decorView).filterIsInstance<android.widget.Button>()
                .any { it.text.toString() == "画像調整" })
            click(dialog, "保存")
            val saved = host.saves.last()
            assertEquals(tones, saved.settings.imageAdjustment)
            assertEquals(quad, saved.settings.manualQuad)
            assertEquals(settings.manualFinders, saved.settings.manualFinders)
            assertEquals(edits, saved.settings.fixedPreRecoveryEdits)
            assertEquals(settings.polarity, saved.settings.polarity)
            assertEquals(settings.confirmedFinderPolarity, saved.settings.confirmedFinderPolarity)
            val restored = BitmapUtils.forSettings(source, saved.settings)
            assertTrue(restored.bitmap.sameAs(saved.image.bitmap))
            assertArrayEquals(restored.luma.pixels, saved.image.luma.pixels)
            assertArrayEquals(restored.luma.rgbPixels, saved.image.luma.rgbPixels)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackConfirmsBeforeDiscardingUnsavedChangesInBothStyles() {
        for (style in AppStyle.entries) withDialog(style = style) { dialog, host, _, _ ->
            automaticThresholdControl(dialog).performClick()
            dialog.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(dialog, confirmation)
            assertTrue(dialog.isShowing)
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing)
            assertTrue(host.saves.isEmpty())
            dialog.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(host.saves.isEmpty())
        }
    }

    @Test fun dialogCancellationAlsoProtectsUnsavedChanges() = withDialog { dialog, host, _, _ ->
        automaticThresholdControl(dialog).performClick()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)
        assertNotSame(dialog, ShadowDialog.getLatestDialog())
        assertTrue(host.saves.isEmpty())
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackClosesSavedWorkWithoutAnExtraConfirmation() = withDialog { dialog, host, _, _ ->
        click(dialog, "保存")
        dialog.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, host.saves.size)
    }

    @Test fun eitherSliderUnchecksAutomaticThresholdsUntilTheyAreEnabledAgain() {
        val settings = PreprocessSettings(automaticThresholds = true,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        for (sliderIndex in 0..1) withDialog(settings) { dialog, host, _, _ ->
            val automaticControl = automaticThresholdControl(dialog)
            assertTrue(automaticControl.isChecked)
            val slider = views(dialog.window!!.decorView).filterIsInstance<android.widget.SeekBar>()
                .filter { it.max == 254 }[sliderIndex]
            val before = slider.progress
            slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            assertTrue(slider.progress > before)
            assertFalse(automaticControl.isChecked)
            click(dialog, "保存")
            val manual = host.saves.last().settings
            assertFalse(manual.automaticThresholds)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setImageQuad(quad.copy(topLeft = PointD(quad.topLeft.x + 1, quad.topLeft.y)))
            overlay.onGeometryEditFinished!!.invoke()
            click(dialog, "保存")
            assertEquals(manual.blackMax, host.saves.last().settings.blackMax)
            assertEquals(manual.whiteMin, host.saves.last().settings.whiteMin)
            assertFalse(host.saves.last().settings.automaticThresholds)
            automaticControl.performClick()
            assertTrue(automaticControl.isChecked)
            click(dialog, "保存")
            val automatic = host.saves.last().settings
            assertTrue(automatic.automaticThresholds)
            assertEquals(settings.provisionalBlackMax, automatic.blackMax)
            assertEquals(settings.provisionalWhiteMin, automatic.whiteMin)
        }
    }

    @Test fun thresholdButtonEntryUsesDisplayedPolarityAndUpdatesThePreviewAndSavedPair() {
        for (inverted in listOf(false, true)) {
            val settings = PreprocessSettings(
                polarity = if (inverted) PolarityMode.INVERTED else PolarityMode.NORMAL,
                automaticThresholds = true,
                manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)),
            )
            val gray = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            withDialog(settings, sourceBitmap = gray) { dialog, host, _, _ ->
                val controls = views(dialog.window!!.decorView)
                val label = controls.filterIsInstance<TextView>().single { it.text.startsWith("画像の暗部") }
                val sliders = controls.filterIsInstance<SeekBar>().filter { it.max == 254 }
                val originalProgress = sliders.map { it.progress }
                fun openInput(): Pair<AlertDialog, EditText> {
                    assertTrue((label.parent as ViewGroup).getChildAt(1).performClick())
                    shadowOf(Looper.getMainLooper()).idle()
                    val popup = ShadowDialog.getLatestDialog() as AlertDialog
                    return popup to views(popup.window!!.decorView).filterIsInstance<EditText>().single()
                }
                val (cancelled, firstInput) = openInput()
                val current = if (inverted) 255 - (sliders.last().progress + 1) else sliders.first().progress
                assertEquals(current.toString(), firstInput.text.toString())
                firstInput.setText("255")
                cancelled.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertNotNull(firstInput.error)
                assertTrue(cancelled.isShowing)
                firstInput.setText("254")
                cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertTrue(automaticThresholdControl(dialog).isChecked)
                assertEquals(originalProgress, sliders.map { it.progress })

                val (popup, input) = openInput()
                input.setText("254")
                popup.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(popup.isShowing)
                assertFalse(automaticThresholdControl(dialog).isChecked)
                assertTrue(label.text.endsWith("254以下"))
                val progress = if (inverted) 0 else 254
                assertEquals(listOf(progress, progress), sliders.map { it.progress })
                val overlay = controls.filterIsInstance<PreprocessOverlayView>().single()
                val preview = PreprocessOverlayView::class.java.getDeclaredField("editMatrix").run {
                    isAccessible = true; get(overlay) as QrMatrix
                }
                assertEquals(if (inverted) ModuleValue.ZERO else ModuleValue.ONE, preview[10, 10].value)
                click(dialog, "保存")
                val saved = host.saves.single().settings
                assertEquals(progress, saved.blackMax)
                assertEquals(progress + 1, saved.whiteMin)
                assertFalse(saved.automaticThresholds)
            }
        }
    }

    @Test fun thresholdNumericEntryIsAvailableOnlyThroughButtons() {
        for (style in AppStyle.entries) withDialog(style = style) { dialog, _, _, _ ->
            val labels = views(dialog.window!!.decorView).filterIsInstance<TextView>().filter {
                it.text.startsWith("画像の暗部") || it.text.startsWith("画像の明部")
            }
            assertEquals(2, labels.size)
            for (label in labels) {
                assertFalse(label.isClickable)
                assertFalse(label.isLongClickable)
                val previousDialog = ShadowDialog.getLatestDialog()
                assertFalse(label.performClick())
                assertFalse(label.performLongClick())
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(previousDialog, ShadowDialog.getLatestDialog())

                val button = (label.parent as ViewGroup).getChildAt(1) as Button
                assertEquals("数値入力", button.text.toString())
                assertTrue(button.performClick())
                shadowOf(Looper.getMainLooper()).idle()
                val popup = ShadowDialog.getLatestDialog() as AlertDialog
                assertNotSame(previousDialog, popup)
                assertTrue(popup.isShowing)
                assertTrue(views(popup.window!!.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == label.text.toString()
                })
                assertEquals(1, views(popup.window!!.decorView).filterIsInstance<EditText>().size)
                popup.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Test fun numericEntryButtonsKeepBothSlidersAtTheirFullWidthAndHeight() {
        for (style in AppStyle.entries) withDialog(style = style) { dialog, _, _, _ ->
            val decor = dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 360, 640)
            val sliders = views(decor).filterIsInstance<SeekBar>().filter { it.max == 254 }
            assertEquals(2, sliders.size)
            for (slider in sliders) {
                val parent = slider.parent as ViewGroup
                assertEquals(parent.width - parent.paddingLeft - parent.paddingRight, slider.width)
                val baseline = SeekBar(slider.context).apply { max = 254 }
                baseline.measure(View.MeasureSpec.makeMeasureSpec(slider.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(baseline.measuredHeight, slider.height)
                assertTrue(slider.width > 0 && slider.height > 0)
            }
            assertEquals(2, views(decor).filterIsInstance<Button>().count { it.text == "数値入力" })
        }
    }

    @Test fun uncheckingAutomaticThresholdsKeepsTheCurrentPairAcrossCornerChanges() {
        val settings = PreprocessSettings(automaticThresholds = true,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            click(dialog, "保存")
            val before = host.saves.last().settings
            val automaticControl = automaticThresholdControl(dialog)
            automaticControl.performClick()
            assertFalse(automaticControl.isChecked)
            click(dialog, "保存")
            assertEquals(before.copy(automaticThresholds = false), host.saves.last().settings)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setImageQuad(quad.copy(topLeft = PointD(quad.topLeft.x + 1, quad.topLeft.y)))
            overlay.onGeometryEditFinished!!.invoke()
            click(dialog, "保存")
            val after = host.saves.last().settings
            assertFalse(after.automaticThresholds)
            assertEquals(before.blackMax, after.blackMax)
            assertEquals(before.whiteMin, after.whiteMin)
        }
    }

    @Test fun reopeningSettingsRestoresTheSavedAutomaticThresholdChoiceAndPair() {
        for (enabled in listOf(false, true)) {
            val initial = PreprocessSettings(automaticThresholds = enabled, blackMax = 72, whiteMin = 181,
                provisionalBlackMax = 96, provisionalWhiteMin = 160,
                manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
            var saved: PreprocessSettings? = null
            withDialog(initial) { dialog, host, _, _ ->
                assertEquals(enabled, automaticThresholdControl(dialog).isChecked)
                click(dialog, "保存")
                val captured = host.saves.last().settings
                saved = captured
                assertEquals(enabled, captured.automaticThresholds)
                assertEquals(if (enabled) 96 else 72, captured.blackMax)
                assertEquals(if (enabled) 160 else 181, captured.whiteMin)
            }
            withDialog(saved) { dialog, host, _, _ ->
                assertEquals(enabled, automaticThresholdControl(dialog).isChecked)
                click(dialog, "保存")
                assertEquals(saved, host.saves.last().settings)
            }
        }
    }

    @Test fun confirmedInvertedFinderControlsThresholdLabelsAndSavedManualValues() {
        val settings = PreprocessSettings(polarity = PolarityMode.AUTO, blackMax = 80, whiteMin = 180,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0)),
            confirmedFinderPolarity = FinderPolarity.INVERTED,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            val labels = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.contains("画像の明部（QRの黒(1)）: 175以上"))
            assertTrue(labels.contains("画像の暗部（QRの白(0)）: 75以下"))
            click(dialog, "保存")
            val saved = host.saves.last().settings
            assertEquals(80, saved.blackMax)
            assertEquals(180, saved.whiteMin)
            assertEquals(FinderPolarity.INVERTED, saved.confirmedFinderPolarity)
        }
    }

    @Test fun unassignedInvertedGridUsesSourceThresholdsInLogicalPolarity() {
        val settings = PreprocessSettings(polarity = PolarityMode.INVERTED, automaticThresholds = true,
            provisionalBlackMax = 40, provisionalWhiteMin = 80, logicalGridAssigned = false,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, host, _, _ ->
            click(dialog, "保存")
            val saved = host.saves.last().settings
            assertEquals(175, saved.blackMax)
            assertEquals(215, saved.whiteMin)
            assertEquals(40, saved.provisionalBlackMax)
            assertEquals(80, saved.provisionalWhiteMin)
        }
    }

    @Test fun manualInvertedPreviewAgreesWithRecoverySamplingWithoutConfirmedFinders() {
        val settings = PreprocessSettings(polarity = PolarityMode.INVERTED,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
        withDialog(settings) { dialog, _, _, working ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val matrix = PreprocessOverlayView::class.java.getDeclaredField("editMatrix").run {
                isAccessible = true; get(overlay) as QrMatrix
            }
            val q = overlay.imageQuad()
            val geometry = GridGeometry(1, FinderRole.TOP_LEFT, q.topLeft.x, q.topLeft.y, 10.0, 0.0,
                q, GeometryModel.MANUAL_PROJECTIVE).reanchored()
            val expected = MatrixSampler(working.luma, settings.variants().single()).sample(geometry).first
            for (y in 0 until 21) for (x in 0 until 21) assertEquals(expected[x, y].value, matrix[x, y].value)
        }
    }

    @Test fun expandedEditorSharesUndoHistoryAndPreservesEditsAfterReopening() = withDialog { dialog, host, _, _ ->
        val coord = Coord(10, 10)
        val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
        click(dialog, "大きな編集画面を開く")
        shadowOf(Looper.getMainLooper()).idle()
        val expanded = ShadowDialog.getLatestDialog() as AlertDialog
        try {
            val editor = views(expanded.window!!.decorView).filterIsInstance<QrEditorView>().single()
            editor.onBrushApplied!!.invoke(setOf(coord), ModuleValue.ZERO)
            click(expanded, "Undo")
            expanded.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            click(dialog, "保存")
            assertEquals(ModuleValue.ONE, host.saves.last().settings.preRecoveryEdits[coord])
            click(dialog, "大きな編集画面を開く")
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            try {
                click(reopened, "Redo")
            } finally {
                reopened.dismiss()
                shadowOf(Looper.getMainLooper()).idle()
            }
            click(dialog, "保存")
            assertEquals(ModuleValue.ZERO, host.saves.last().settings.preRecoveryEdits[coord])
            assertEquals(ModuleValue.ONE, host.saves.first().settings.preRecoveryEdits[coord])
        } finally {
            expanded.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun dismissDiscardsTemporaryImageWithoutSaving() = withDialog { dialog, host, source, working ->
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(host.saves.isEmpty())
        assertTrue(working.bitmap.isRecycled)
        assertFalse(source.bitmap.isRecycled)
    }

    @Test fun saveHandsOffImageAndCopiesEditsBeforeFurtherChanges() = withDialog { dialog, host, _, working ->
        val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
        // Explicitly edit the value; later draft edits must not mutate the saved snapshot.
        val coord = Coord(10, 10)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
        click(dialog, "保存")
        val saved = host.saves.single()
        overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ZERO)
        assertEquals(ModuleValue.ONE, saved.settings.preRecoveryEdits[coord])
        assertEquals(ModuleValue.ONE, saved.cellEdits.edits[coord])
        assertTrue(saved.events.isNotEmpty())
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertEquals(listOf("save", "saved status"), host.effects)
    }

    @Test fun returningFromRepairRestoresCompletedCorrectionWithoutQueuingAnotherFit() {
        for (style in AppStyle.entries) {
            var saved: PreprocessSave? = null
            val initial = PreprocessSettings(lmCorrectionEnabled = false, distortionCorrection = true,
                manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)))
            withDialog(initial, style) { dialog, host, _, _ ->
                click(dialog, if (style == AppStyle.CLASSIC) "設定を保存して修復" else "修復")
                saved = host.saves.single()
                assertNotNull(host.saves.single().correctionPreview)
            }
            val jobs = mutableListOf<Runnable>()
            val completed = requireNotNull(saved)
            withDialog(completed.settings, style, worker = Executor { jobs += it }, savedCorrection = completed.correctionPreview) { dialog, host, _, _ ->
                assertTrue("A completed straight fallback also avoids repeating the curve fit", jobs.isEmpty())
                click(dialog, "保存")
                assertEquals(completed.settings.previewGeometry, host.saves.single().settings.previewGeometry)
                val toggle = views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
                    .single { it.text == dialog.context.getString(R.string.image_distortion_correction) }
                toggle.performClick()
                toggle.performClick()
                assertEquals(1, jobs.size)
            }
        }
    }

    @Test fun repairStartsAfterSavingAndRetainsTheImage() = withDialog { dialog, host, _, working ->
        click(dialog, "設定を保存して修復")
        assertEquals(listOf("save", "repair"), host.effects)
        assertFalse(dialog.isShowing)
        assertFalse(working.bitmap.isRecycled)
    }

    @Test fun dismissKeepsImageOwnedByDiagnosticExport() = withDialog { dialog, host, _, working ->
        host.diagnostic = working.bitmap
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(working.bitmap.isRecycled)
        assertTrue(host.saves.isEmpty())
    }
    @Test fun assignedFindersCanBeReleasedAndStayReleasedAfterSaving() = checkFinderRelease(FinderRole.entries.toSet())

    @Test fun twoAssignedFindersCanBeReleasedAndStayReleasedAfterSaving() =
        checkFinderRelease(setOf(FinderRole.TOP_RIGHT, FinderRole.BOTTOM_LEFT))

    private fun checkFinderRelease(roles: Set<FinderRole>) {
        val initial = PreprocessSettings(version = 1, rotationDegrees = 15f,
            manualQuad = ImageQuad(PointD(20.0, 20.0), PointD(220.0, 20.0), PointD(220.0, 220.0), PointD(20.0, 220.0)),
            logicalGridAssigned = true,
            manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(50.0, 50.0),
                FinderRole.TOP_RIGHT to PointD(190.0, 50.0), FinderRole.BOTTOM_LEFT to PointD(50.0, 190.0)).filterKeys { it in roles })
        var saved: PreprocessSettings? = null
        withDialog(initial) { dialog, host, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertEquals(initial.manualFinders, overlay.finderPoints())
            assertTrue(overlay.isLogicalGridAssigned())
            val coord = Coord(10, 10)
            overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ERASED)
            overlay.onCellBrushApplied!!.invoke(setOf(coord), ModuleValue.ONE)
            for (role in roles) {
                assertTrue(overlay.beginFinderRemoval())
                assertTrue(overlay.removeConfirmedFinder(role))
                overlay.onFinderRemoved!!.invoke(role)
            }
            assertFalse(overlay.isLogicalGridAssigned())
            click(dialog, "保存")
            val settingsAfterRemoval = host.saves.single().settings
            saved = settingsAfterRemoval
            assertTrue(settingsAfterRemoval.manualFinders.isEmpty())
            assertFalse(settingsAfterRemoval.logicalGridAssigned)
            assertEquals(ModuleValue.ONE, settingsAfterRemoval.preRecoveryEdits[coord])
        }
        withDialog(saved) { dialog, _, _, _ ->
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertTrue(overlay.finderPoints().isEmpty())
            assertFalse(overlay.isLogicalGridAssigned())
            overlay.setFinderPoint(FinderRole.BOTTOM_LEFT, overlay.imageQuad().topLeft)
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(setOf(FinderRole.BOTTOM_LEFT), overlay.finderPoints().keys)
        }
    }

}
