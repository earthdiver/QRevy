package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
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
        override fun retains(image: BitmapUtils.Prepared) =
            saves.lastOrNull()?.image === image || diagnostic === image.bitmap
        override fun save(draft: PreprocessSave) { saves += draft; effects += "save" }
        override fun showSavedStatus() { effects += "saved status" }
        override fun startRecovery() { effects += "repair" }
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
                           block: (AlertDialog, Host, BitmapUtils.Prepared, BitmapUtils.Prepared) -> Unit) {
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
            source, working, savedCellEdits, Executor { it.run() }, AtomicInteger(), RecoveryEngine(), host).show()
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

    @Test fun rectificationKeepsPhysicalCornersButRequiresFinderReassignment() {
        for (mirrored in listOf(false, true)) for (style in AppStyle.entries) {
            val physicalQuad = ImageQuad(PointD(30.0, 20.0), PointD(210.0, 30.0),
                PointD(220.0, 220.0), PointD(20.0, 210.0))
            val initial = PreprocessSettings(mirroredInput = mirrored,
                manualQuad = if (mirrored) physicalQuad.transposed() else physicalQuad,
                manualFinders = mapOf(FinderRole.TOP_LEFT to PointD(55.0, 50.0)),
                confirmedFinderPolarity = FinderPolarity.NORMAL,
                preRecoveryEdits = mapOf(Coord(10, 12) to ModuleValue.ONE),
                searchQuadNeighborhood = false)
            checkRequiresFinderAfterSaving(initial, style) { dialog, overlay ->
                assertTrue(overlay.isLogicalGridAssigned())
                click(dialog, "選択範囲を正方形に射影補正")
                assertNotEquals(initial.manualQuad, overlay.imageQuad())
                val rectified = overlay.imageQuad().let { if (mirrored) it.transposed() else it }
                assertEquals(rectified.topLeft.y, rectified.topRight.y, .001)
                assertEquals(rectified.topLeft.x, rectified.bottomLeft.x, .001)
                assertEquals(rectified.topLeft.distanceTo(rectified.topRight),
                    rectified.topLeft.distanceTo(rectified.bottomLeft), .001)
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
                    assertFalse(overlay.isLogicalGridAssigned())
                    assertTrue(overlay.finderPoints().isEmpty())
                    click(dialog, "保存")
                    val degrees = host.saves.last().settings.rotationDegrees.toDouble()
                    val radians = Math.toRadians(degrees)
                    val c = kotlin.math.cos(radians)
                    val s = kotlin.math.sin(radians)
                    val w = source.bitmap.width.toDouble()
                    val h = source.bitmap.height.toDouble()
                    val left = minOf(0.0, w * c, -h * s, w * c - h * s)
                    val top = minOf(0.0, w * s, h * c, w * s + h * c)
                    fun rotated(p: PointD) = PointD(
                        (p.x + .5) * c - (p.y + .5) * s - left - .5,
                        (p.x + .5) * s + (p.y + .5) * c - top - .5)
                    logicalQuad.asList().zip(overlay.imageQuad().asList()).forEach { (before, after) ->
                        assertTrue("$action preserves the ordered grid: $before -> $after",
                            rotated(before).distanceTo(after) < .001)
                    }
                    overlay.setFinderPoint(FinderRole.TOP_LEFT, rotated(PointD(59.5, 59.5)))
                    click(dialog, "保存")
                    val saved = host.saves.last()
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
            val reference = overlay.imageQuad().let { if (mirrored) it.transposed() else it }
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
                    val rectified = BitmapUtils.rectify(unadjusted.bitmap, physicalQuad, requestedVersion, quietModules = 1.0)
                    try {
                        assertEquals(rebuilt.bitmap.width, saved.image.bitmap.width)
                        assertTrue(rebuilt.bitmap.sameAs(saved.image.bitmap))
                        assertArrayEquals(rebuilt.luma.pixels, saved.image.luma.pixels)
                        val mapping = Homography.fromCorrespondences(reference.asList(), rectified.qrQuad.asList())
                        fun assertMapped(before: PointD, after: PointD) {
                            assertTrue(mapping.map(before.x, before.y).distanceTo(after) < 1e-6)
                        }
                        originalQuad.asList().zip(savedSettings.manualQuad!!.asList()).forEach { (before, after) ->
                            assertMapped(before, after)
                        }
                        assertEquals(originalFinders.keys, savedSettings.manualFinders.keys)
                        originalFinders.forEach { (role, point) -> assertMapped(point, savedSettings.manualFinders.getValue(role)) }
                    } finally {
                        rebuilt.bitmap.recycle()
                        rectified.prepared.bitmap.recycle()
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
