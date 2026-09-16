package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CheckBox
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import io.github.earthdiver.qrrepair.ui.QrEditorView
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w412dp-h892dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrecisionDesignTest {
    @Before fun selectModernStyle() {
        AppStylePreferences.save(org.robolectric.RuntimeEnvironment.getApplication(), AppStyle.MODERN)
    }

    @Test
    fun homeAndWorkspaceSwitchWithoutLosingImportedImage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val decor = activity.window.decorView
            assertTrue(field<View>(activity, "homePanel").isShown)
            assertEquals(View.GONE, field<View>(activity, "workspacePanel").visibility)
            assertTrue(field<View>(activity, "resumeButton").isShown)
            assertEquals("過去の作業を再開", field<TextView>(activity, "resumeButton").text.toString())
            val logo = allViews(field(activity, "homePanel")).filterIsInstance<io.github.earthdiver.qrrepair.ui.RepairPreviewView>().single()
            assertNull(logo.background)
            assertTrue(logo.layoutParams.height >= 100)
            val homeFooter = field<ViewGroup>(activity, "homeActions")
            assertEquals(listOf("作業画面", "操作履歴", "終了"),
                (0 until homeFooter.childCount).map { (homeFooter.getChildAt(it) as TextView).text.toString() })
            assertEquals(1, allViews(decor).filterIsInstance<TextView>().count { it.text.toString() == "終了" })
            fun fill(button: View) = ((button.background as android.graphics.drawable.RippleDrawable)
                .getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor
            assertEquals(PrecisionStyle.PINK, fill(find(decor, "終了")!!))
            assertEquals(PrecisionStyle.ACCENT, fill(find(decor, activity.getString(R.string.select_image))!!))
            render(decor, "home")

            val bits = QRCodeWriter().encode("QR REPAIR DESIGN TEST", BarcodeFormat.QR_CODE, 240, 240)
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            for (y in 0 until 240) for (x in 0 until 240) {
                bitmap.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
            val prepared = BitmapUtils.prepare(bitmap)
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.set(activity, prepared)
            find(decor, "作業画面")!!.performClick()
            assertTrue(field<View>(activity, "workspacePanel").isShown)
            assertTrue(field<View>(activity, "workspaceAction").isShown)
            find(decor, "ホーム")!!.performClick()
            assertTrue(field<View>(activity, "resumeButton").isShown)
            find(decor, "現在の作業に戻る")!!.performClick()
            assertSame(prepared, field<BitmapUtils.Prepared>(activity, "prepared"))
            assertFalse(bitmap.isRecycled)
            render(decor, "workspace")

            MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply { isAccessible = true }.invoke(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            render(dialog.window!!.decorView, "adjustment")
            assertNull(find(dialog.window!!.decorView, activity.getString(R.string.cell_edit)))
            find(dialog.window!!.decorView, activity.getString(R.string.cell_edit_tap))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(find(dialog.window!!.decorView, activity.getString(R.string.open_large_editor)))
            render(dialog.window!!.decorView, "cell-editor")
            dialog.dismiss()

            val encoded = com.google.zxing.qrcode.encoder.Encoder.encode("QR REPAIR DESIGN TEST", com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M).matrix
            val matrix = io.github.earthdiver.qrrepair.core.QrMatrix(encoded.width)
            for (y in 0 until encoded.height) for (x in 0 until encoded.width) {
                matrix.set(x, y, if (encoded.get(x, y).toInt() == 1) io.github.earthdiver.qrrepair.core.ModuleValue.ONE
                    else io.github.earthdiver.qrrepair.core.ModuleValue.ZERO, 1f, io.github.earthdiver.qrrepair.core.ModuleSource.IMAGE)
            }
            val state = field<RecoveryUiState>(activity, "recoveryState")
            state.sourceMatrix = matrix.copy()
            state.workingMatrix = matrix
            MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
            val inspectorDialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotNull(find(inspectorDialog.window!!.decorView, "セルの修復根拠"))
            val evidenceRoot = inspectorDialog.window!!.decorView
            val heading = find(evidenceRoot, "セルの修復根拠")!!
            assertEquals(View.GONE, ((heading.parent as View).parent as View).visibility)
            find(evidenceRoot, activity.getString(R.string.show_evidence))!!.performClick()
            assertEquals(View.VISIBLE, ((heading.parent as View).parent as View).visibility)
            render(evidenceRoot, "evidence")
            inspectorDialog.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
    fun primaryRepairActionStaysVisibleOnSmallScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }
                .set(activity, BitmapUtils.prepare(bitmap))
            find(activity.window.decorView, "作業画面")!!.performClick()
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 360, 640)
            val action = field<View>(activity, "workspaceAction")
            val rect = android.graphics.Rect()
            assertTrue(action.getGlobalVisibleRect(rect))
            assertEquals(action.height, rect.height())
            assertTrue(action.height >= 36 && action.height < 48)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun scanResultAndCurrentPageSurviveHomeAndLanguageChanges() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val result = com.google.zxing.Result("QR REPAIR DESIGN TEST", null, emptyArray(), BarcodeFormat.QR_CODE)
            MainActivity::class.java.getDeclaredMethod("showImmediateQrResult", com.google.zxing.Result::class.java)
                .apply { isAccessible = true }.invoke(activity, result)
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            val decor = activity.window.decorView
            find(decor, "ホーム")!!.performClick()
            find(decor, "作業画面")!!.performClick()
            assertEquals("読み取り結果", field<TextView>(activity, "workspaceTitle").text.toString())
            val changeLanguage = MainActivity::class.java.getDeclaredMethod("applyLanguage", AppLanguage::class.java)
                .apply { isAccessible = true }
            changeLanguage.invoke(activity, AppLanguage.ENGLISH)
            assertTrue(field<View>(activity, "workspacePanel").isShown)
            assertEquals("Scan result", field<TextView>(activity, "workspaceTitle").text.toString())
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains(result.text))
            val panel = field<ViewGroup>(activity, "workspacePanel")
            assertTrue(panel.indexOfChild(field(activity, "statusText")) < panel.indexOfChild(field(activity, "contentPreviewText")))
            assertTrue(panel.indexOfChild(field(activity, "contentPreviewText")) < panel.indexOfChild(field(activity, "workspacePreview")))
            assertTrue(find(activity.window.decorView, activity.getString(R.string.step_result))!!.isSelected)
            assertEquals(1, allViews(activity.window.decorView).filterIsInstance<TextView>()
                .count { it.text.toString() == activity.getString(R.string.cell_edit_tap) })
            find(activity.window.decorView, "Home")!!.performClick()
            changeLanguage.invoke(activity, AppLanguage.JAPANESE)
            assertTrue(field<View>(activity, "homePanel").isShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
    fun workflowKeepsDraftAndManualFocusAcrossEditors() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val prepared = BitmapUtils.prepare(bitmap)
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.set(activity, prepared)
            find(activity.window.decorView, "作業画面")!!.performClick()
            find(activity.window.decorView, activity.getString(R.string.step_edit))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val root = dialog.window!!.decorView
            fun <T : View> view(type: Class<T>): T = allViews(root).filter { type.isInstance(it) }.map { type.cast(it) }.first()!!
            val overlay = view(PreprocessOverlayView::class.java)
            overlay.onEditCellSelected?.invoke(10, 10)
            find(root, activity.getString(R.string.cell_make_manual))!!.performClick()
            val focus = find(root, activity.getString(R.string.highlight_manual_only)) as CheckBox
            focus.performClick()
            assertTrue(overlay.highlightManualOnly)
            val expectedCount = "変更操作: 1回・変更セル: 1セル　やり直し: 0回"
            assertNotNull(find(root, expectedCount))
            find(root, activity.getString(R.string.step_adjust))!!.performClick()
            assertFalse(overlay.highlightManualOnly)
            find(root, activity.getString(R.string.step_edit))!!.performClick()
            assertTrue(overlay.highlightManualOnly)
            assertNotNull(find(root, expectedCount))
            find(root, activity.getString(R.string.open_large_editor))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val large = ShadowDialog.getLatestDialog() as AlertDialog
            val largeRoot = large.window!!.decorView
            val editor = allViews(largeRoot).filterIsInstance<QrEditorView>().single()
            assertTrue(editor.highlightManualOnly)
            largeRoot.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            largeRoot.layout(0, 0, 360, 640)
            assertTrue("Large editor lost its usable canvas", editor.height >= 240)
            (find(largeRoot, activity.getString(R.string.highlight_manual_only)) as CheckBox).performClick()
            assertFalse(editor.highlightManualOnly)
            assertFalse(overlay.highlightManualOnly)
            render(largeRoot, "large-editor")
            large.dismiss()
            assertFalse(focus.isChecked)
            assertNotNull(find(root, expectedCount))
            val counts = find(root, expectedCount)!!
            val column = counts.parent as ViewGroup
            assertSame(column.getChildAt(column.indexOfChild(counts) + 1), find(root, "Undo")!!.parent)
            assertNull("Count label should have no separate frame", counts.background)
            assertNotNull("History controls should share a frame", column.background)
            val sourcePanel = focus.parent as View
            val brushRow = find(root, activity.getString(R.string.zero_brush))!!.parent as View
            val controls = sourcePanel.parent as ViewGroup
            assertEquals(controls.indexOfChild(brushRow) + 1, controls.indexOfChild(sourcePanel))
            render(root, "manual-cell")
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun resumePreviousWorkLoadsTheNewestSavedSession() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            val store = io.github.earthdiver.qrrepair.session.RecoverySessionStore(activity)
            val entry = io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry(
                kind = io.github.earthdiver.qrrepair.core.HistoryEventKind.IMPORT,
                label = "Synthetic import",
                snapshot = io.github.earthdiver.qrrepair.core.RecoverySnapshot(null, null, null, -1),
            )
            for (id in listOf("older-work", "newer-work")) {
                store.saveSourceBitmap(id, source)
                store.save(id, io.github.earthdiver.qrrepair.core.UiMode.SIMPLE, listOf(entry), 0)
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            }
            assertEquals("newer-work", store.listRecent().first().id)
            find(activity.window.decorView, activity.getString(R.string.resume_work))!!.performClick()
            val io = field<java.util.concurrent.ExecutorService>(activity, "ioExecutor")
            repeat(3) {
                io.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals("newer-work", field<String>(activity, "sessionId"))
            assertNotNull(field<BitmapUtils.Prepared>(activity, "prepared"))
            assertTrue(field<View>(activity, "workspacePanel").isShown)
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        } finally { controller.pause().stop().destroy(); source.recycle() }
    }

    @Test fun emptyWorkspaceOffersImportInsteadOfAnUnusableEditor() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            find(activity.window.decorView, "作業画面")!!.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotNull(find(dialog.window!!.decorView, activity.getString(R.string.import_before_work)))
            assertEquals(View.GONE, field<View>(activity, "workspacePanel").visibility)
            dialog.listView.performItemClick(null, 1, 1L)
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivityForResult.intent.action)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun operationHistoryUsesEntrySelectionWithoutUndoRedoButtons() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val history = field<io.github.earthdiver.qrrepair.core.RecoverySessionHistory>(activity, "sessionHistory")
            history.record(io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry(
                kind = io.github.earthdiver.qrrepair.core.HistoryEventKind.IMPORT, label = "Synthetic import",
                snapshot = io.github.earthdiver.qrrepair.core.RecoverySnapshot(null, null, null, -1)))
            find(activity.window.decorView, activity.getString(R.string.operation_history))!!.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val root = dialog.window!!.decorView
            assertNull(find(root, "Undo"))
            assertNull(find(root, "Redo"))
            assertTrue(allViews(root).filterIsInstance<android.widget.Button>().any { it.text.contains("Synthetic import") })
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun classicOperationHistoryAlsoOmitsUndoRedo() {
        AppStylePreferences.save(org.robolectric.RuntimeEnvironment.getApplication(), AppStyle.CLASSIC)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
            val history = field<io.github.earthdiver.qrrepair.core.RecoverySessionHistory>(activity, "sessionHistory")
            history.record(io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry(
                kind = io.github.earthdiver.qrrepair.core.HistoryEventKind.IMPORT, label = "Synthetic import",
                snapshot = io.github.earthdiver.qrrepair.core.RecoverySnapshot(null, null, null, -1)))
            find(activity.window.decorView, activity.getString(R.string.history))!!.performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNull(find(dialog.window!!.decorView, "Undo"))
            assertNull(find(dialog.window!!.decorView, "Redo"))
            assertTrue(allViews(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().any { it.text.contains("Synthetic import") })
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun workflowTabsAlignAndReturnToTheSameDraftThroughTheActualResultPage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val prepared = BitmapUtils.prepare(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
            MainActivity::class.java.getDeclaredField("prepared").apply { isAccessible = true }.set(activity, prepared)
            MainActivity::class.java.getDeclaredMethod("showImmediateQrResult", com.google.zxing.Result::class.java)
                .apply { isAccessible = true }.invoke(activity,
                    com.google.zxing.Result("QR REPAIR TAB TEST", null, emptyArray(), BarcodeFormat.QR_CODE))
            (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
            val matrix = io.github.earthdiver.qrrepair.core.QrMatrix(21)
            val state = field<RecoveryUiState>(activity, "recoveryState")
            state.sourceMatrix = matrix.copy()
            state.workingMatrix = matrix
            MainActivity::class.java.getDeclaredMethod("showCellEditor").apply { isAccessible = true }.invoke(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val editor = ShadowDialog.getLatestDialog() as AlertDialog
            val root = editor.window!!.decorView
            val overlay = allViews(root).filterIsInstance<PreprocessOverlayView>().single()
            overlay.onEditCellSelected!!.invoke(10, 10)
            find(root, activity.getString(R.string.cell_make_manual))!!.performClick()
            val count = "変更操作: 1回・変更セル: 1セル　やり直し: 0回"
            assertNotNull(find(root, count))
            fun tabRect(view: View): android.graphics.Rect {
                render(view, "tab-navigation")
                val tab = find(view, activity.getString(R.string.step_edit))!!
                return android.graphics.Rect(0, 0, tab.width, tab.height).also {
                    (view as ViewGroup).offsetDescendantRectToMyCoords(tab, it)
                }
            }
            val cellTab = tabRect(root)
            for (tab in listOf(R.string.step_adjust, R.string.step_edit)) {
                find(root, activity.getString(tab))!!.performClick()
                assertTrue(allViews(root).filterIsInstance<TextView>().none {
                    it.isShown && it.text.toString() == activity.getString(R.string.cancel)
                })
                find(root, activity.getString(R.string.data))!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val popup = ShadowDialog.getLatestDialog() as AlertDialog
                assertNotSame(editor, popup)
                render(popup.window!!.decorView, "data-popup")
                assertTrue(allViews(popup.window!!.decorView).filterIsInstance<android.widget.ScrollView>()
                    .any { it.height >= 200 })
                find(popup.window!!.decorView, activity.getString(R.string.close))!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(root.isShown)
                assertTrue(find(root, activity.getString(tab))!!.isSelected)
                assertNotNull(find(root, count))
            }
            find(root, activity.getString(R.string.step_adjust))!!.performClick()
            assertEquals(cellTab, tabRect(root))
            assertNotNull(find(root, "橙の角をドラッグするか、選択後に矢印キーで調整"))
            assertNull(find(root, activity.getString(R.string.preprocess_title_help)))
            render(root, "aligned-adjustment")
            find(root, activity.getString(R.string.step_result))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(root.isShown)
            assertTrue("Result tab must not create a visible popup", ShadowDialog.getShownDialogs().none {
                it.window?.decorView?.isShown == true
            })
            val main = activity.window.decorView
            assertTrue(field<View>(activity, "workspacePanel").isShown)
            assertEquals(View.GONE, field<View>(activity, "modernHeader").visibility)
            assertTrue(find(main, activity.getString(R.string.step_result))!!.isSelected)
            assertEquals(cellTab, tabRect(main))
            val preview = field<View>(activity, "workspacePreview")
            for (width in listOf(320, 412)) {
                main.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY))
                main.layout(0, 0, width, 892)
                assertEquals("Result preview must resize to a square", preview.width, preview.height)
                assertTrue(preview.width >= width - 32)
            }
            val footer = field<ViewGroup>(activity, "workspaceActions")
            assertEquals(listOf("ホーム", "データ", "修復設定", "セル編集"),
                (0 until footer.childCount).map { (footer.getChildAt(it) as TextView).text.toString() })
            val panel = field<ViewGroup>(activity, "workspacePanel")
            assertFalse(allViews(panel).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.data) })
            MainActivity::class.java.getDeclaredMethod("setDiagnosticDetails", String::class.java)
                .apply { isAccessible = true }.invoke(activity, "Synthetic diagnostic information")
            val diagnostics = find(main, "診断情報を表示")!!
            val png = find(main, activity.getString(R.string.save_png))!!
            assertTrue(panel.indexOfChild(png.parent as View) < panel.indexOfChild(diagnostics))
            diagnostics.performClick()
            assertEquals("診断情報を閉じる", diagnostics.text.toString())
            assertTrue(field<View>(activity, "diagnosticDetailsText").isShown)
            diagnostics.performClick()
            assertEquals("診断情報を表示", diagnostics.text.toString())
            assertFalse(field<View>(activity, "diagnosticDetailsText").isShown)
            render(main, "aligned-result")
            find(main, activity.getString(R.string.show_detailed_result))!!.performClick()
            val inspector = ShadowDialog.getLatestDialog() as AlertDialog
            find(inspector.window!!.decorView, activity.getString(R.string.close))!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(root.isShown)
            assertTrue(find(main, activity.getString(R.string.step_result))!!.isSelected)
            val backToSettings = find(main, activity.getString(R.string.repair_settings_navigation))!!
            val backToCells = find(main, activity.getString(R.string.cell_edit_tap))!!
            assertSame(backToSettings.parent, backToCells.parent)
            for (button in listOf(backToSettings, backToCells)) {
                val background = button.background as android.graphics.drawable.RippleDrawable
                assertEquals(PrecisionStyle.ACCENT,
                    (background.getDrawable(0) as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
            }
            backToSettings.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(find(root, activity.getString(R.string.step_adjust))!!.isSelected)
            find(root, activity.getString(R.string.step_result))!!.performClick()
            backToCells.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(root.isShown)
            assertNotNull("Unsaved manual edit must survive round trip", find(root, count))
            render(root, "aligned-cells")
            find(root, "Undo")!!.performClick()
            assertNotNull(find(root, "変更操作: 0回・変更セル: 0セル　やり直し: 1回"))
            find(root, "Redo")!!.performClick()
            find(root, activity.getString(R.string.home_navigation))!!.performClick()
            val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotSame(editor, confirmation)
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(root.isShown)
            find(root, activity.getString(R.string.home_navigation))!!.performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(field<View>(activity, "homePanel").isShown)
            assertTrue(field<View>(activity, "homeActions").isShown)
            assertFalse(root.isShown)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun candidateSelectorsAboveAndBelowQrStayInSync() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            val state = field<RecoveryUiState>(activity, "recoveryState")
            val matrix = io.github.earthdiver.qrrepair.core.QrMatrix(21)
            state.sourceMatrix = matrix
            state.workingMatrix = matrix
            state.geometry = io.github.earthdiver.qrrepair.core.GridGeometry(
                1, io.github.earthdiver.qrrepair.core.FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0,
            )
            state.recoveries = listOf("first", "second").mapIndexed { index, text ->
                io.github.earthdiver.qrrepair.core.RecoveryCandidate(
                    1, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M, index, text,
                    byteArrayOf(0x40, 0x14, 0x10), 15, 0, 0, 0, 0, 0, 8, intArrayOf(),
                    io.github.earthdiver.qrrepair.core.CodewordExtraction(emptyList()),
                )
            }
            state.selectRecovery(0)
            fun refresh() = MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java)
                .apply { isAccessible = true }.invoke(activity, null)
            refresh()
            val panel = field<ViewGroup>(activity, "workspacePanel")
            val upper = field<ViewGroup>(activity, "upperResultSelector")
            val lower = field<ViewGroup>(activity, "mainResultSelector")
            val status = field<View>(activity, "statusText")
            val qr = field<View>(activity, "workspacePreview")
            assertEquals(panel.indexOfChild(status) + 1, panel.indexOfChild(upper))
            assertTrue(panel.indexOfChild(upper) < panel.indexOfChild(qr))
            assertTrue(panel.indexOfChild(qr) < panel.indexOfChild(lower))
            fun radios(view: View) = allViews(view).filterIsInstance<android.widget.RadioButton>()
            fun assertSelected(index: Int) {
                assertEquals(index, state.recoveryIndex)
                for (selector in listOf(upper, lower)) {
                    assertEquals(View.VISIBLE, selector.visibility)
                    assertEquals(listOf(index == 0, index == 1), radios(selector).map { it.isChecked })
                }
            }
            assertSelected(0)
            radios(upper)[1].performClick()
            assertSelected(1)
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains("second"))
            radios(lower)[0].performClick()
            assertSelected(0)
            assertTrue(field<TextView>(activity, "contentPreviewText").text.contains("first"))
            state.recoveries = state.recoveries.take(1)
            refresh()
            for (selector in listOf(upper, lower)) {
                assertEquals(View.GONE, selector.visibility)
                assertTrue(radios(selector).isEmpty())
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    private fun render(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 412, 892)
        val bitmap = Bitmap.createBitmap(412, 892, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // Optional local design review, using only the synthetic fixture created above.
        System.getenv("QR_REPAIR_DESIGN_PREVIEWS")?.let { path ->
            val folder = File(path).apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun find(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
