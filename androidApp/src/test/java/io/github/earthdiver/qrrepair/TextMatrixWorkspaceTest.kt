package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CheckBox
import android.widget.RadioButton
import io.github.earthdiver.qrrepair.core.*
import io.github.earthdiver.qrrepair.ui.QrEditorView
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class TextMatrixWorkspaceTest {
    @Test fun importedMatrixReturnsToRealSettingsFromBothHomeActions() {
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), AppStyle.MODERN)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            // An incomplete payload keeps the import on the settings page.
            val text = rows(21).joinToString("\n") { String(it) }
            val uri = Uri.parse("content://qr-repair-test/matrix")
            shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
            MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, uri)
            (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val settings = field("preprocessSettings").get(activity) as PreprocessSettings
            assertTrue(settings.manualFinders.isEmpty())
            assertFalse(settings.logicalGridAssigned)
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            for (action in listOf(R.string.workspace_navigation, 0)) {
                label(dialog.window!!.decorView, activity.getString(R.string.home_navigation)).performClick()
                assertFalse(dialog.isShowing)
                if (action == 0) {
                    (field("resumeButton").get(activity) as View).performClick()
                } else label(activity.window.decorView, activity.getString(action)).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue("Returning home must reopen the settings editor", dialog.isShowing)
                assertTrue(label(dialog.window!!.decorView, activity.getString(R.string.step_adjust)).isSelected)
                assertEquals(1, views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().size)
            }
            dialog.dismiss()
            field("immediateResultShown").set(activity, true)
            MainActivity::class.java.getDeclaredMethod("openWorkspace").apply { isAccessible = true }.invoke(activity)
            assertFalse("An existing scan result must remain accessible", dialog.isShowing)
            assertTrue(label(activity.window.decorView, activity.getString(R.string.step_result)).isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun importedMatricesWithoutVerifiedStructureRequireOrientationInBothEditors() {
        for (intact in listOf(false, true)) for (large in listOf(false, true)) {
            val input = if (intact) rows(25) else Array(25) { CharArray(25) { '?' } }
            withImportedMatrix(input) { activity, dialog, settings ->
                assertEquals(2, settings.version)
                assertNotNull(settings.manualQuad)
                assertFalse(settings.logicalGridAssigned)
                assertTrue(settings.manualFinders.isEmpty())
                val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
                assertFalse(overlay.isLogicalGridAssigned())
                assertTrue(label(dialog.window!!.decorView, "セル編集（長押し）").performLongClick())
                val editor = if (large) {
                    label(dialog.window!!.decorView, "大きな編集画面を開く").performClick()
                    ShadowDialog.getLatestDialog() as AlertDialog
                } else dialog
                val root = editor.window!!.decorView
                if (large) {
                    label(root, activity.getString(R.string.switch_to_rectangle)).performClick()
                    views(root).filterIsInstance<QrEditorView>().single().onCellSelected!!.invoke(17, 19)
                } else overlay.onEditCellSelected!!.invoke(17, 19)
                org.robolectric.shadows.ShadowToast.reset()
                label(root, "*").performClick()
                assertEquals(activity.getString(R.string.priority_requires_geometry),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                if (large) editor.dismiss()
            }
        }
    }

    @Test fun automaticFinderRolesOrientImportedCellsAndPrioritySearch() {
        val logical = structuredRows(25).apply {
            this[10][12] = '1'
            this[10][13] = '0'
            this[12][10] = '*'
        }
        val corners = listOf(PointD(35.5, 35.5), PointD(227.5, 35.5),
            PointD(227.5, 227.5), PointD(35.5, 227.5))
        for (rotation in 0..3) withImportedMatrix(rotated(logical, rotation)) { activity, dialog, settings ->
            assertTrue("Rotation $rotation must have verified Finder roles", settings.logicalGridAssigned)
            assertEquals(FinderRole.entries.toSet(), settings.manualFinders.keys)
            assertEquals(2, settings.version)
            assertEquals(List(4) { corners[(it + rotation) % 4] }, settings.logicalQuad!!.asList())
            assertEquals(setOf(Coord(10, 12)), settings.preRecoveryPrioritySearch)
            assertEquals(mapOf(Coord(10, 12) to ModuleValue.ERASED), settings.preRecoveryEdits)
            assertEquals(FinderPolarity.NORMAL, settings.confirmedFinderPolarity)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(settings.manualFinders, overlay.finderPoints())
            val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 0.0, 0.0, 8.0, 0.0,
                settings.logicalQuad, GeometryModel.MANUAL_PROJECTIVE)
            for (role in FinderRole.entries) {
                val module = geometry.finderModulePoint(role)
                val expected = geometry.moduleToImage(module.x, module.y)
                assertTrue(expected.distanceTo(settings.manualFinders.getValue(role)) < 1e-7)
            }
            val source = field("prepared").get(activity) as BitmapUtils.Prepared
            val sampled = MatrixSampler(source.luma, SamplingProfile(settings, false)).sample(geometry).first
            for (y in logical.indices) for (x in logical.indices) {
                val expected = when (logical[y][x]) {
                    '0' -> ModuleValue.ZERO
                    '1' -> ModuleValue.ONE
                    else -> ModuleValue.ERASED
                }
                assertEquals("Rotation $rotation, cell ($x,$y)", expected, sampled[x, y].value)
                assertEquals(ModuleSource.IMAGE, sampled[x, y].source)
            }
        }
    }

    @Test fun automaticOrientationRevalidatesPriorityCellsAndExplainsRemovedMarks() {
        val input = structuredRows(25).apply {
            this[0][8] = '*'
            this[12][10] = '*'
        }
        for (rotation in 0..3) withImportedMatrix(rotated(input, rotation), noticeCoordinates = "(8,0)") { _, _, settings ->
            assertTrue(settings.logicalGridAssigned)
            assertEquals(setOf(Coord(10, 12)), settings.preRecoveryPrioritySearch)
            assertEquals(ModuleValue.ERASED, settings.preRecoveryEdits[Coord(8, 0)])
            assertEquals(ModuleSource.USER_SOFT, settings.sourceForPreRecoveryEdit(ModuleValue.ERASED, Coord(8, 0)))
        }
    }

    @Test fun damagedStructureStillAssignsFinderRoles() {
        val input = structuredRows(25).apply {
            this[0][0] = '?'
            this[3][3] = '0'
            this[4][7] = '?'
            this[4][17] = '1'
            this[6][8] = '0'
            this[9][6] = '?'
        }
        withImportedMatrix(input) { _, dialog, settings ->
            assertTrue(settings.logicalGridAssigned)
            assertEquals(FinderRole.entries.toSet(), settings.manualFinders.keys)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertEquals(settings.manualFinders, overlay.finderPoints())
        }
    }

    @Test fun mirrorToggleTransposesAutomaticRolesAndEditsWithoutChangingSourceCells() {
        val logical = structuredRows(25).apply {
            this[10][12] = '1'
            this[10][13] = '0'
            this[12][10] = '*'
        }
        val mirrored = Array(25) { y -> CharArray(25) { x -> logical[x][y] } }
        for (rotation in 0..3) withImportedMatrix(rotated(mirrored, rotation)) { activity, dialog, initial ->
            val root = dialog.window!!.decorView
            val overlay = views(root).filterIsInstance<PreprocessOverlayView>().single()
            val option = label(root, activity.getString(R.string.mirrored_input)) as CheckBox
            val originalImage = field("prepared").get(activity) as BitmapUtils.Prepared
            assertFalse(option.isChecked)
            assertEquals(setOf(Coord(12, 10)), initial.preRecoveryPrioritySearch)
            for (enabled in listOf(true, false, true)) {
                option.isChecked = enabled
                assertTrue(overlay.isLogicalGridAssigned())
                assertEquals(if (enabled) initial.manualQuad!!.transposed() else initial.manualQuad, overlay.imageQuad())
                assertEquals(if (enabled) initial.manualFinders.mapKeys { it.key.transposed() } else initial.manualFinders,
                    overlay.finderPoints())
            }
            label(root, "保存").performClick()
            val saved = field("preprocessSettings").get(activity) as PreprocessSettings
            assertTrue(saved.mirroredInput)
            assertEquals(setOf(Coord(10, 12)), saved.preRecoveryPrioritySearch)
            assertSame(originalImage, field("prepared").get(activity))
            val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 0.0, 0.0, 8.0, 0.0,
                saved.logicalQuad, GeometryModel.MANUAL_PROJECTIVE)
            val sampled = MatrixSampler(originalImage.luma, saved.variants().single()).sample(geometry).first
            for (y in logical.indices) for (x in logical.indices) {
                val expected = when (logical[y][x]) {
                    '0' -> ModuleValue.ZERO
                    '1' -> ModuleValue.ONE
                    else -> ModuleValue.ERASED
                }
                assertEquals("Rotation $rotation, cell ($x,$y)", expected, sampled[x, y].value)
            }
        }
    }

    @Test fun importedBitsFollowEffectivePolarityWhileManualValuesRemainLiteral() {
        for (assigned in listOf(false, true)) {
            val input = (if (assigned) structuredRows(25) else Array(25) { CharArray(25) { '?' } }).apply {
                this[10][12] = '1'
                this[10][13] = '0'
                this[12][10] = '*'
            }
            withImportedMatrix(input) { activity, dialog, initial ->
                val root = dialog.window!!.decorView
                val overlay = views(root).filterIsInstance<PreprocessOverlayView>().single()
                overlay.onCellBrushApplied!!.invoke(setOf(Coord(15, 10)), ModuleValue.ONE)
                views(root).filterIsInstance<RadioButton>().single { it.tag == PolarityMode.INVERTED }.isChecked = true
                label(root, "保存").performClick()
                val saved = field("preprocessSettings").get(activity) as PreprocessSettings
                assertEquals(PolarityMode.INVERTED, saved.polarity)
                val profile = saved.variants().single()
                assertEquals(!assigned, profile.inverted)
                val source = field("prepared").get(activity) as BitmapUtils.Prepared
                val geometry = GridGeometry(2, FinderRole.TOP_LEFT, 0.0, 0.0, 8.0, 0.0,
                    saved.manualQuad, GeometryModel.MANUAL_PROJECTIVE)
                val matrix = MatrixSampler(source.luma, profile).sample(geometry).first
                PreRecoveryCellEditState(saved.preRecoveryEdits, saved.preRecoveryPrioritySearch,
                    initialSoftEdits = saved.preRecoverySoftEdits).applyTo(matrix, false)
                assertEquals(if (assigned) ModuleValue.ONE else ModuleValue.ZERO, matrix[12, 10].value)
                assertEquals(if (assigned) ModuleValue.ZERO else ModuleValue.ONE, matrix[13, 10].value)
                assertEquals(ModuleValue.ERASED, matrix[14, 10].value)
                assertEquals(ModuleValue.ERASED, matrix[10, 12].value)
                assertTrue(matrix[10, 12].prioritySearch)
                assertEquals(ModuleValue.ONE, matrix[15, 10].value)
                assertEquals(ModuleSource.USER, matrix[15, 10].source)
                assertEquals(initial.logicalGridAssigned, saved.logicalGridAssigned)
            }
        }
    }

    @Test fun clearedAutomaticFindersStayUnassignedWhenSavedWorkIsReopened() {
        val input = rotated(structuredRows(25).apply { this[12][10] = '*' }, 1)
        withImportedMatrix(input) { activity, dialog, initial ->
            assertTrue(initial.logicalGridAssigned)
            val overlay = views(dialog.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            val editedCell = Coord(12, 10)
            overlay.onCellBrushApplied!!.invoke(setOf(editedCell), ModuleValue.ONE)
            for (role in FinderRole.entries) {
                assertTrue(overlay.beginFinderRemoval())
                assertTrue(overlay.removeConfirmedFinder(role))
                overlay.onFinderRemoved!!.invoke(role)
            }
            assertFalse(overlay.isLogicalGridAssigned())
            assertTrue(overlay.finderPoints().isEmpty())
            label(dialog.window!!.decorView, "保存").performClick()
            val saved = field("preprocessSettings").get(activity) as PreprocessSettings
            assertFalse(saved.logicalGridAssigned)
            assertTrue(saved.manualFinders.isEmpty())
            assertEquals(initial.manualQuad, saved.manualQuad)
            assertEquals(ModuleValue.ONE, saved.fixedPreRecoveryEdits[editedCell])
            assertEquals(initial.preRecoveryPrioritySearch, saved.preRecoveryPrioritySearch)
            val io = field("ioExecutor").get(activity) as ExecutorService
            io.submit {}.get(30, TimeUnit.SECONDS)
            val id = field("sessionId").get(activity) as String
            // Replace in-memory settings so the assertions require restoring the saved work.
            field("preprocessSettings").set(activity, initial)
            MainActivity::class.java.getDeclaredMethod("restoreSessionById", String::class.java)
                .apply { isAccessible = true }.invoke(activity, id)
            io.submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val restored = field("preprocessSettings").get(activity) as PreprocessSettings
            assertFalse(restored.logicalGridAssigned)
            assertTrue(restored.manualFinders.isEmpty())
            assertEquals(saved.manualQuad, restored.manualQuad)
            assertEquals(saved.preRecoveryEdits, restored.preRecoveryEdits)
            assertEquals(saved.preRecoveryPrioritySearch, restored.preRecoveryPrioritySearch)
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(reopened.isShowing)
            val restoredOverlay = views(reopened.window!!.decorView).filterIsInstance<PreprocessOverlayView>().single()
            assertFalse(restoredOverlay.isLogicalGridAssigned())
            assertTrue(restoredOverlay.finderPoints().isEmpty())
            reopened.dismiss()
        }
    }

    @Test fun finderAssignmentRotatesImportedEditsAndChecksPriorityPositions() {
        val input = Array(25) { CharArray(25) { '?' } }
        input[24][24] = '*'
        input[10][10] = '*'
        withImportedMatrix(input) { activity, dialog, settings ->
            assertEquals(setOf(Coord(24, 24), Coord(10, 10)), settings.preRecoveryPrioritySearch)
            val root = dialog.window!!.decorView
            val overlay = views(root).filterIsInstance<PreprocessOverlayView>().single()
            val quad = overlay.imageQuad()
            overlay.setFinderPoint(FinderRole.TOP_LEFT, quad.topRight)
            assertTrue(overlay.isLogicalGridAssigned())
            assertEquals(quad.asList().toSet(), overlay.imageQuad().asList().toSet())
            assertEquals(quad.topRight, overlay.imageQuad().topLeft)
            val notice = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(notice.findViewById<TextView>(android.R.id.message).text.contains("(24,0)"))
            notice.dismiss()
            label(root, "保存").performClick()
            val saved = field("preprocessSettings").get(activity) as PreprocessSettings
            assertTrue(saved.logicalGridAssigned)
            assertEquals(setOf(Coord(10, 14)), saved.preRecoveryPrioritySearch)
            assertEquals(ModuleValue.ERASED, saved.preRecoveryEdits[Coord(24, 0)])
        }
    }

    private fun withImportedMatrix(input: Array<CharArray>, noticeCoordinates: String? = null,
        block: (MainActivity, AlertDialog, PreprocessSettings) -> Unit) {
        AppStylePreferences.save(RuntimeEnvironment.getApplication(), AppStyle.CLASSIC)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val uri = Uri.parse("content://qr-repair-test/matrix")
            val text = input.joinToString("\n") { String(it) }
            shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
            MainActivity::class.java.getDeclaredMethod("loadTextMatrix", Uri::class.java)
                .apply { isAccessible = true }.invoke(activity, uri)
            (field("executor").get(activity) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            if (noticeCoordinates != null) {
                val notice = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(activity.getString(R.string.priority_revalidated, noticeCoordinates),
                    notice.findViewById<TextView>(android.R.id.message).text.toString())
                notice.dismiss()
            }
            val dialog = ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>().last { it.isShowing }
            try { block(activity, dialog, field("preprocessSettings").get(activity) as PreprocessSettings) }
            finally { dialog.dismiss() }
        } finally { controller.pause().stop().destroy() }
    }

    private fun rows(dimension: Int): Array<CharArray> {
        val rows = Array(dimension) { CharArray(dimension) { '?' } }
        val pattern = listOf("1111111", "1000001", "1011101", "1011101", "1011101", "1000001", "1111111")
        for ((left, top) in listOf(0 to 0, dimension - 7 to 0, 0 to dimension - 7)) {
            pattern.forEachIndexed { y, row -> row.forEachIndexed { x, cell -> rows[top + y][left + x] = cell } }
        }
        return rows
    }

    private fun structuredRows(dimension: Int): Array<CharArray> = rows(dimension).apply {
        for (i in 0..7) {
            this[7][i] = '0'; this[i][7] = '0'
            this[7][dimension - 1 - i] = '0'; this[i][dimension - 8] = '0'
            this[dimension - 8][i] = '0'; this[dimension - 1 - i][7] = '0'
        }
        for (i in 8 until dimension - 8) {
            val bit = if (i % 2 == 0) '1' else '0'
            this[6][i] = bit; this[i][6] = bit
        }
    }

    private fun rotated(input: Array<CharArray>, quarterTurns: Int): Array<CharArray> {
        var result = input
        repeat(quarterTurns) {
            val before = result
            result = Array(before.size) { y -> CharArray(before.size) { x -> before[before.lastIndex - x][y] } }
        }
        return result
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun label(root: View, text: String) = views(root).filterIsInstance<TextView>().first { it.text.toString() == text }
}
