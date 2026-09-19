package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.RadioButton
import io.github.earthdiver.qrrepair.ui.BitmapUtils
import io.github.earthdiver.qrrepair.ui.CropOverlayView
import io.github.earthdiver.qrrepair.ui.PreprocessOverlayView
import io.github.earthdiver.qrrepair.ui.QrEditorView
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView
import io.github.earthdiver.qrrepair.core.QrMatrix
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.FinderRole
import io.github.earthdiver.qrrepair.core.GridGeometry
import io.github.earthdiver.qrrepair.core.ImageQuad
import io.github.earthdiver.qrrepair.core.PointD
import io.github.earthdiver.qrrepair.core.PreprocessSettings
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.RecoveryCandidate
import io.github.earthdiver.qrrepair.core.RecoveryFailure
import io.github.earthdiver.qrrepair.core.RecoveryFailureReason
import io.github.earthdiver.qrrepair.core.RecoveryFailureStage
import io.github.earthdiver.qrrepair.core.CodewordExtraction
import io.github.earthdiver.qrrepair.core.UiMode
import io.github.earthdiver.qrrepair.core.SamplingProfile
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.earthdiver.qrrepair.session.StoredSession
import io.github.earthdiver.qrrepair.session.RecoverySessionStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-rJP-w360dp-h640dp-port-mdpi")
class PreprocessDialogLayoutTest {
    @Test
    fun subsequentFinderTapFarFromItsExpectedPositionIsRejected() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val overlay = PreprocessOverlayView(context).apply {
            setBitmap(bitmap)
            setVersion(1)
            setPhysicalImageQuad(ImageQuad(
                PointD(12.0, 12.0), PointD(228.0, 12.0),
                PointD(228.0, 228.0), PointD(12.0, 228.0),
            ))
            measure(
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 240, 240)
        }
        overlay.setFinderPoint(FinderRole.TOP_LEFT, overlay.expectedFinderCenter(FinderRole.TOP_LEFT))
        var rejected: FinderRole? = null
        overlay.onFinderPlacementRejected = { rejected = it }
        overlay.finderPlacementRole = FinderRole.BOTTOM_LEFT

        overlay.onTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 120f, 120f, 0))

        assertEquals(FinderRole.BOTTOM_LEFT, rejected)
        assertTrue(FinderRole.BOTTOM_LEFT !in overlay.finderPoints())
    }

    @Test
    fun removingLastFinderMakesTheProvisionalGridAssignableAgain() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val physical = ImageQuad(
            PointD(12.0, 12.0), PointD(228.0, 12.0),
            PointD(228.0, 228.0), PointD(12.0, 228.0),
        )
        val overlay = PreprocessOverlayView(context).apply {
            setBitmap(bitmap)
            setPhysicalImageQuad(physical)
        }

        overlay.setFinderPoint(FinderRole.TOP_RIGHT, physical.topRight)
        assertTrue(overlay.isLogicalGridAssigned())
        assertTrue(overlay.removeConfirmedFinder(FinderRole.TOP_RIGHT))
        assertTrue("Finder解除後も論理格子が確定扱いです", !overlay.isLogicalGridAssigned())

        overlay.setFinderPoint(FinderRole.BOTTOM_LEFT, physical.topLeft)
        assertTrue("Finderを別の役割で再指定できません", overlay.isLogicalGridAssigned())
        assertTrue(FinderRole.BOTTOM_LEFT in overlay.finderPoints())
    }

    @Test
    fun provisionalPeriodicityGridShowsClassificationBeforeFinderRolesAreAssigned() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x / 12 + y / 12) % 2 == 0) Color.BLACK else Color.WHITE)
            }
        }
        val prepared = BitmapUtils.prepare(bitmap)
        val overlay = PreprocessOverlayView(context).apply {
            setBitmap(prepared.bitmap)
            setVersion(1)
            setPhysicalImageQuad(ImageQuad(
                PointD(12.0, 12.0), PointD(228.0, 12.0),
                PointD(228.0, 228.0), PointD(12.0, 228.0),
            ))
            setClassificationPreview(prepared.luma, SamplingProfile(PreprocessSettings(), inverted = false))
            measure(
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 240, 240)
        }

        overlay.draw(Canvas(Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)))
        val summary = PreprocessOverlayView::class.java.getDeclaredField("lastClassificationSummary").run {
            isAccessible = true
            get(overlay) as String
        }

        assertTrue("Finder役割の確定前にセル分類が表示されません: $summary", summary.contains("1=") && summary.contains("0=") && summary.contains("?="))
        assertTrue("暫定プレビューが論理四隅を確定扱いにしています", !overlay.isLogicalGridAssigned())
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
    fun englishUiUsesCompactLabelsWithoutHorizontalClipping() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_language", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()

        val mainDecor = activity.window.decorView
        listOf("Menu", "History", "Exit", "Select image", "Camera", "Repair", "Show detailed result", "Data", "Save PNG").forEach { label ->
            assertNotNull("English main control is missing: $label", findText(mainDecor, label))
        }
        findText(mainDecor, "Menu")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull("Language setting is missing from the menu", findText(menu.window!!.decorView, "Language"))
        assertNull("The main-screen TXT matrix action is duplicated in the menu", findText(menu.window!!.decorView, "Import text matrix"))
        menu.dismiss()

        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        MainActivity::class.java.getDeclaredField("prepared").apply {
            isAccessible = true
            set(activity, BitmapUtils.prepare(bitmap))
        }
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply {
            isAccessible = true
            invoke(activity)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)

        listOf("Details", "Nudge:", "Rectify selection", "QR Version:", "Assign Finder", "Save & repair").forEach { label ->
            val control = findText(decor, label)
            assertNotNull("English pre-repair control is missing: $label", control)
            assertTrue("English control has no measurable size: $label", control!!.measuredWidth > 0 && control.measuredHeight > 0)
            assertTrue("English control exceeds the dialog width: $label", control.measuredWidth <= 360)
        }
    }

    @Test
    fun recentWorkListActuallyDisplaysItsSavedSessionItems() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "画像を読み込み",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        RecoverySessionStore(activity).save("recent-list-guidance", UiMode.SIMPLE, listOf(entry), 0)

        MainActivity::class.java.getDeclaredMethod("showRecentSessions").apply {
            isAccessible = true
            invoke(activity)
        }
        var recentDialog: AlertDialog? = null
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            if (dialog?.listView?.adapter?.count?.let { it > 0 } == true) {
                recentDialog = dialog
                break
            }
            Thread.sleep(5)
        }

        assertNotNull("最近の作業ダイアログに保存済みセッション項目が表示されません", recentDialog)
        val dialog = requireNotNull(recentDialog)
        assertTrue("最近の作業リストが画面に表示されません", dialog.listView.isShown)
        val title = findText(dialog.window!!.decorView, "最近の作業（")?.text?.toString().orEmpty()
        assertTrue("最近の作業に保存上限が表示されません", title.contains("/20件"))
        assertEquals("すべて削除", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text.toString())
        MainActivity::class.java.getDeclaredMethod("confirmDeleteAllRecentSessions", Int::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(activity, dialog.listView.adapter.count)
        }
        val deleteAllConfirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(
            "全件削除の確認ダイアログが表示されません",
            findText(deleteAllConfirmation.window!!.decorView, "保存済み作業をすべて削除"),
        )
        deleteAllConfirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test
    fun recentWorkCanBeDeletedWithConfirmation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val id = "recent-delete-confirmation"
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "削除する作業",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        val store = RecoverySessionStore(activity)
        store.save(id, UiMode.SIMPLE, listOf(entry), 0)
        val sessionIdField = MainActivity::class.java.getDeclaredField("sessionId").apply { isAccessible = true }
        sessionIdField.set(activity, id)

        MainActivity::class.java.getDeclaredMethod("showRecentSessions").apply {
            isAccessible = true
            invoke(activity)
        }
        var recentDialog: AlertDialog? = null
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            val candidate = ShadowDialog.getLatestDialog() as? AlertDialog
            if (candidate?.listView?.adapter?.count?.let { it > 0 } == true) {
                recentDialog = candidate
                break
            }
            Thread.sleep(5)
        }
        assertNotNull(recentDialog)
        val dialog = requireNotNull(recentDialog)
        val position = (0 until dialog.listView.adapter.count)
            .first { dialog.listView.adapter.getItem(it).toString().contains("削除する作業") }
        assertTrue(dialog.listView.onItemLongClickListener.onItemLongClick(
            dialog.listView,
            dialog.listView.adapter.getView(position, null, dialog.listView),
            position,
            dialog.listView.adapter.getItemId(position),
        ))
        val confirmation = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(confirmation.isShowing)
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        var deleted = false
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (store.listRecent().none { it.id == id }) {
                deleted = true
                break
            }
            Thread.sleep(5)
        }
        assertTrue("確認後も保存済み作業が残っています", deleted)
        assertNull("削除した作業が自動保存対象に残っています", sessionIdField.get(activity))
    }

    @Test
    fun recentSessionKeepsItsHistoryAccessibleWhenTheSourceImageIsUnavailable() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val matrix = QrMatrix(21)
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.AUTO_RECOVERY,
            label = "修復処理",
            snapshot = RecoverySnapshot(
                GridGeometry(1, FinderRole.TOP_LEFT, 16.0, 16.0, 2.0, 0.0),
                matrix.copy(),
                matrix,
                -1,
            ),
        )
        val store = RecoverySessionStore(activity)
        store.save("history-without-source", UiMode.SIMPLE, listOf(entry), 0)

        val stored = store.load("history-without-source")

        assertNotNull("元画像がないだけでチェックサム済み履歴まで破棄されています", stored)
        assertNull("存在しない元画像が復元された扱いになっています", stored!!.sourceBitmap)
        assertEquals(1, stored.historyEntries.size)
        MainActivity::class.java.getDeclaredMethod(
            "applyStoredSession",
            StoredSession::class.java,
            BitmapUtils.Prepared::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, stored, null)
        }
        assertEquals(
            "保存済み幾何の表示番号が0/1へ回帰しています",
            0,
            recoveryState(activity).attemptIndex,
        )
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (recoveryState(activity).inspectorMatrix != null) break
            Thread.sleep(5)
        }
        assertNotNull(
            "元画像がないだけで履歴に保存されたQR行列へアクセスできません",
            recoveryState(activity).workingMatrix,
        )
    }

    @Test
    fun recentSessionSelectionUsesTheRealAsyncLoadPathAndImportsTheTimeline() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val id = "async-recent-session"
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.AUTO_RECOVERY,
            label = "修復処理",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        RecoverySessionStore(activity).save(id, UiMode.SIMPLE, listOf(entry), 0)

        MainActivity::class.java.getDeclaredMethod("restoreSessionById", String::class.java).apply {
            isAccessible = true
            invoke(activity, id)
        }
        val historyField = MainActivity::class.java.getDeclaredField("sessionHistory").apply { isAccessible = true }
        var imported = false
        for (attempt in 0 until 200) {
            shadowOf(Looper.getMainLooper()).idle()
            val history = historyField.get(activity) as io.github.earthdiver.qrrepair.core.RecoverySessionHistory
            if (history.entries.size == 1) {
                imported = true
                break
            }
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("実際の「最近の作業」非同期読込経路で履歴が取り込まれません", imported)
        val status = MainActivity::class.java.getDeclaredField("statusText").apply { isAccessible = true }.get(activity) as TextView
        assertTrue("保存地点を直接再開する経路へ進みません", status.text.contains("保存済み作業を復元"))
    }

    @Test
    fun validTimelineQuarantinedByTheOldSourceImageBugIsRecovered() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val id = "falsely-quarantined-session"
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.AUTO_RECOVERY,
            label = "修復処理",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        RecoverySessionStore(activity).save(id, UiMode.SIMPLE, listOf(entry), 0)
        val folder = java.io.File(activity.noBackupFilesDir, "qr_sessions/$id")
        val primary = java.io.File(folder, "session.qrs")
        val quarantine = java.io.File(folder, "session.qrs.corrupt-1-decode")
        assertTrue(primary.renameTo(quarantine))

        val reopened = RecoverySessionStore(activity)

        assertTrue("旧版が誤隔離した正常なQRR9履歴を検出できません", reopened.listRecent().any { it.id == id })
        assertEquals("修復処理", reopened.load(id)?.historyEntries?.single()?.label)
        assertTrue("復帰後も履歴本体が隔離名のままです", primary.exists())
    }

    @Test
    fun pngExportUsesTheDisplayedRepairedMatrixInsteadOfTheDamagedWorkingMatrix() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val working = QrMatrix(21).apply {
            set(10, 10, ModuleValue.ERASED, 0f, ModuleSource.IMAGE)
        }
        val displayed = working.copy().apply {
            set(10, 10, ModuleValue.ONE, 1f, ModuleSource.RECOVERED)
        }
        recoveryState(activity).workingMatrix = working
        recoveryState(activity).inspectorMatrix = displayed

        val exported = MainActivity::class.java.getDeclaredMethod("matrixForPng").apply {
            isAccessible = true
        }.invoke(activity) as QrMatrix

        assertEquals("修復済みセルがPNG用行列で?へ戻っています", ModuleValue.ONE, exported[10, 10].value)
        assertTrue("PNG用行列が画面表示用行列の参照をそのまま公開しています", exported !== displayed)
    }

    @Test
    fun selectingARecentSessionImmediatelyResumesItsSavedCursor() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val prepared = BitmapUtils.prepare(bitmap)
        val entries = listOf(
            RecoveryHistoryEntry(
                kind = HistoryEventKind.IMPORT,
                label = "画像を読み込み",
                snapshot = RecoverySnapshot(null, null, null, -1),
            ),
            RecoveryHistoryEntry(
                kind = HistoryEventKind.AUTO_RECOVERY,
                label = "修復処理",
                snapshot = RecoverySnapshot(null, null, null, -1),
            ),
        )
        val stored = StoredSession("recent-session", 1L, UiMode.SIMPLE, entries, 1, bitmap)

        MainActivity::class.java.getDeclaredMethod(
            "applyStoredSession",
            StoredSession::class.java,
            BitmapUtils.Prepared::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, stored, prepared)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val resumedDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull("最近の作業が保存カーソルから直接再開されません", findText(resumedDialog.window!!.decorView, "修復前設定"))
        val history = MainActivity::class.java.getDeclaredField("sessionHistory").apply { isAccessible = true }.get(activity) as io.github.earthdiver.qrrepair.core.RecoverySessionHistory
        assertEquals(2, history.entries.size)
        assertEquals(1, history.cursor)
    }

    @Test
    fun selectingAnImportedHistoryEntryActuallyOpensTheResumedWorkScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val prepared = BitmapUtils.prepare(bitmap)
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "画像を読み込み",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        val stored = StoredSession("resume-session", 1L, UiMode.SIMPLE, listOf(entry), 0, bitmap)
        MainActivity::class.java.getDeclaredMethod(
            "applyStoredSession",
            StoredSession::class.java,
            BitmapUtils.Prepared::class.java,
        ).apply {
            isAccessible = true
            invoke(activity, stored, prepared)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val resumedDialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(
            "履歴を選択しても作業再開用の修復前設定画面へ遷移しません",
            findText(resumedDialog.window!!.decorView, "修復前設定"),
        )
    }

    @Test
    fun importedImageOffersRotationAndCropBeforeAnalysis() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)

        MainActivity::class.java.getDeclaredMethod("showImportedImageAdjustment", Bitmap::class.java).apply {
            isAccessible = true
            invoke(activity, bitmap)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        listOf("左90°", "-1°", "-0.1°", "+0.1°", "+1°", "右90°", "クロップして解析").forEach { label ->
            assertNotNull("画像ファイルの回転・クロップ画面に$label がありません", findText(decor, label))
        }
        val cropView = findViews(decor, CropOverlayView::class.java).single()
        assertTrue("画像ファイルのクロップ範囲編集Viewがありません", cropView.isShown)
        findText(decor, "左90°")!!.performClick()
        val rotated = CropOverlayView::class.java.getDeclaredField("bitmap").apply { isAccessible = true }.get(cropView) as Bitmap
        assertTrue("画像ファイルの90度回転がプレビューへ反映されません", rotated.width == 480 && rotated.height == 640)

        findExactText(decor, "キャンセル")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("回転前Bitmapが解放されません", bitmap.isRecycled)
        assertTrue("画像ファイルのクロップをキャンセルしても回転後Bitmapが解放されません", rotated.isRecycled)
    }

    @Test
    fun resultInspectorUsesTapDetailsAndAnExplicitCodewordButton() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val matrix = QrMatrix(21)
        recoveryState(activity).apply {
            sourceMatrix = matrix
            workingMatrix = matrix
            inspectorMatrix = matrix
        }

        MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply {
            isAccessible = true
            invoke(activity)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)
        val button = findText(decor, "同じコード語を表示")
        assertNotNull("Codeword強調の明示ボタンがありません", button)
        assertNotNull(button)
        assertTrue("Codeword未選択時にボタンが有効です", button!!.isEnabled.not())
        assertNotNull("セルタップ操作の案内がありません", findText(decor, "セルをタップ：セルの修復理由を表示"))
        val inspector = findViews(decor, QrResultInspectorView::class.java).single()
        inspector.onCellSelected?.invoke(3, 4)
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 360, 640)
        assertTrue("根拠表示時もQR領域を確保", inspector.height >= 160)
        val detail = findText(decor, "セル (3,4)")
        assertNotNull("タップ時のセル詳細が表示されません", detail)
        assertTrue("セルの修復理由が小画面で見切れています", isInside(decor, detail!!))
    }

    @Test
    fun resultInspectorShowsAndSwitchesOnlyDecodedResultsAsTabs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val matrix = QrMatrix(21)
        recoveryState(activity).apply {
            sourceMatrix = matrix
            workingMatrix = matrix
            inspectorMatrix = matrix
        }
        fun candidate(mask: Int, text: String?) = RecoveryCandidate(
            1, ErrorCorrectionLevel.M, mask, text, text?.toByteArray() ?: byteArrayOf(),
            15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
        )
        val candidates = listOf(candidate(0, null), candidate(1, "first"), candidate(2, "second"))
        recoveryState(activity).apply {
            recoveries = candidates
            selectRecovery(1)
        }

        MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val decor = (ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView
        assertNotNull("最良の復号候補タブがありません", findText(decor, "候補1"))
        val secondTab = findText(decor, "候補2")
        assertNotNull("次点の復号結果タブがありません", secondTab)
        assertNull("未復号候補が結果として表示されています", findText(decor, "候補3"))

        secondTab!!.performClick()

        assertEquals(2, recoveryState(activity).recoveryIndex)
        assertEquals(candidates[2], recoveryState(activity).selectedRecovery)

        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()
        MainActivity::class.java.getDeclaredMethod("refreshMainResultSelector").apply { isAccessible = true }.invoke(activity)
        val mainDecor = activity.window.decorView
        assertNotNull("メイン画面に候補選択がありません", findText(mainDecor, "候補1"))
        assertNotNull("メイン画面で次点候補を選べません", findText(mainDecor, "候補2"))

        MainActivity::class.java.getDeclaredMethod("showCodewordHex").apply { isAccessible = true }.invoke(activity)
        val dataDecor = (ShadowDialog.getLatestDialog() as AlertDialog).window!!.decorView
        assertNotNull("データ画面に候補選択がありません", findText(dataDecor, "候補1"))
        assertNotNull("データ画面で次点候補を選べません", findText(dataDecor, "候補2"))
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()

        MainActivity::class.java.getDeclaredMethod("startSavePng").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val pngDialog = ShadowDialog.getLatestDialog() as AlertDialog
        val pngDecor = pngDialog.window!!.decorView
        val firstPngResult = findText(pngDecor, "候補1")
        assertNotNull("PNG保存時に結果選択がありません", firstPngResult)
        assertNotNull("PNG保存時に次点候補を選べません", findText(pngDecor, "候補2"))
        findViews(pngDecor, android.widget.RadioButton::class.java).first { it.text == "候補1" }.performClick()
        assertEquals("結果を選んだだけで現在結果が変更されました", 2, recoveryState(activity).recoveryIndex)
        assertNull("結果を選んだだけでファイル保存へ進みました", shadowOf(activity).nextStartedActivity)

        pngDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("保存ボタンで選択結果が確定されません", 1, recoveryState(activity).recoveryIndex)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun pngCandidateSelectionScrollsWithoutHidingDialogActions() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val matrix = QrMatrix(21)
        fun candidate(index: Int) = RecoveryCandidate(
            1, ErrorCorrectionLevel.M, index % 8, "decoded-$index", byteArrayOf(index.toByte()),
            15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
        )
        recoveryState(activity).apply {
            sourceMatrix = matrix
            workingMatrix = matrix
            inspectorMatrix = matrix
            recoveries = List(40, ::candidate)
            selectRecovery(0)
        }

        MainActivity::class.java.getDeclaredMethod("startSavePng").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)
        val lastCandidate = requireNotNull(findText(decor, "候補40"))

        assertNotNull("多数のPNG候補をスクロールできません", findAncestor(lastCandidate, ScrollView::class.java))
        assertTrue("PNG保存ボタンが小画面で見切れています", isInside(decor, dialog.getButton(AlertDialog.BUTTON_POSITIVE)))
        assertTrue("キャンセルボタンが小画面で見切れています", isInside(decor, dialog.getButton(AlertDialog.BUTTON_NEGATIVE)))
    }

    @Test
    fun everyCandidateSelectorDisplaysAtMostTheTop256Results() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.dismiss()
        val matrix = QrMatrix(21)
        fun candidate(index: Int) = RecoveryCandidate(
            1, ErrorCorrectionLevel.M, index % 8, "decoded-$index", byteArrayOf(index.toByte()),
            15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
        )
        recoveryState(activity).apply {
            sourceMatrix = matrix
            workingMatrix = matrix
            inspectorMatrix = matrix
            recoveries = List(300, ::candidate)
            selectRecovery(0)
        }
        fun candidateCount(root: View): Int =
            findViews(root, RadioButton::class.java).count { it.text.toString().startsWith("候補") }

        MainActivity::class.java.getDeclaredMethod("refreshMainResultSelector").apply { isAccessible = true }.invoke(activity)
        val mainSelector = MainActivity::class.java.getDeclaredField("mainResultSelector").apply { isAccessible = true }.get(activity) as View
        assertNotNull(findText(mainSelector, "候補を選択（上位256件／全300件）"))
        assertEquals(256, candidateCount(mainSelector))

        MainActivity::class.java.getDeclaredMethod("showZoomableResult").apply { isAccessible = true }.invoke(activity)
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(256, candidateCount(dialog.window!!.decorView))
        assertNull(findText(dialog.window!!.decorView, "候補257"))
        dialog.dismiss()

        MainActivity::class.java.getDeclaredMethod("showCodewordHex").apply { isAccessible = true }.invoke(activity)
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(256, candidateCount(dialog.window!!.decorView))
        assertNull(findText(dialog.window!!.decorView, "候補257"))
        dialog.dismiss()

        MainActivity::class.java.getDeclaredMethod("startSavePng").apply { isAccessible = true }.invoke(activity)
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(256, candidateCount(dialog.window!!.decorView))
        assertNull(findText(dialog.window!!.decorView, "候補257"))

        val preferred = MainActivity::class.java.getDeclaredMethod("preferredRecoveryIndex", List::class.java, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(activity, recoveryState(activity).recoveries, 299) as Int
        assertEquals(0, preferred)
    }

    @Test
    fun mainScreenPlacesSharedResultAboveSelectorAndCandidateDetailsBelowIt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val matrix = QrMatrix(21)
        fun candidate(mask: Int, formatErrors: Int, knownErrors: Int) = RecoveryCandidate(
            version = 1,
            ecLevel = ErrorCorrectionLevel.M,
            dataMask = mask,
            text = "decoded-$mask",
            dataBytes = byteArrayOf(mask.toByte()),
            formatMatches = 28 - formatErrors,
            formatMismatches = formatErrors,
            originalErasedCodewords = 3,
            erasedCodewords = 2,
            correctedErrataSymbols = 1,
            knownBitMismatchesAfterCorrection = knownErrors,
            knownBitsCompared = 100,
            correctedInterleaved = intArrayOf(),
            extraction = CodewordExtraction(emptyList()),
            minRsMargin = 2,
            constraintFilledBits = 48,
            constraintEvidenceBits = 0,
            constraintForcedByUserSetting = true,
        )
        val state = recoveryState(activity)
        state.showAttempt(
            0,
            GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0),
            matrix,
            listOf(candidate(1, 2, 4), candidate(2, 3, 5)),
            null,
            0,
        )

        MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
            isAccessible = true
            invoke(activity, null)
        }

        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View
        val status = field("statusText") as TextView
        val guide = field("guideText") as TextView
        val selector = field("mainResultSelector")
        val candidateDetails = field("contentPreviewText") as TextView
        val parent = status.parent as ViewGroup
        assertNotNull("結果領域が縦スクロールできません", findAncestor(status, ScrollView::class.java))
        val repairButton = requireNotNull(findText(activity.window.decorView, "修復する"))
        assertNull("上部の操作ボタンが結果と一緒にスクロールします", findAncestor(repairButton, ScrollView::class.java))
        assertTrue(parent.indexOfChild(status) < parent.indexOfChild(guide))
        assertTrue(parent.indexOfChild(candidateDetails) < parent.indexOfChild(guide))
        assertTrue(parent.indexOfChild(field("upperResultSelector")) < parent.indexOfChild(candidateDetails))
        assertNotNull(findText(selector, "候補を選択（上限256まで）"))
        assertTrue(status.text.contains("修復候補"))
        assertTrue("共有結果にECCが混在しています", !status.text.contains("ECC"))
        val settingsText = (field("diagnosticDetailsText") as TextView).text
        assertTrue(settingsText.contains("ECC M / マスク1"))
        assertTrue(settingsText.contains("形式情報領域: 比較対象の読み取り済み28bitとの不一致 2bit"))
        assertTrue(settingsText.contains("データ・ECC領域: 比較対象の読み取り済み100bitとの不一致 4bit"))
        assertTrue(!candidateDetails.text.contains("誤り訂正レベル"))
        assertTrue((field("resultExplanationText") as TextView).text.contains("使用した方法"))
        assertFalse((field("resultExplanationText") as TextView).text.contains("100bitと比較"))
        assertTrue(!candidateDetails.text.contains("format誤り"))

        val toggle = field("diagnosticToggle") as TextView
        val diagnostics = field("diagnosticDetailsText") as TextView
        assertEquals(View.GONE, diagnostics.visibility)
        toggle.performClick()
        assertEquals(View.VISIBLE, diagnostics.visibility)
        assertTrue(diagnostics.text.contains("QRの位置・向き: 相似変換 / V1 / 左上基準"))
        val fullDiagnostic = (MainActivity::class.java.getDeclaredField("diagnosticReport").apply { isAccessible = true }.get(activity) as io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport).fullText()
        assertTrue(fullDiagnostic.contains("ブロック最小）: 2"))
        assertTrue(fullDiagnostic.contains("指定した仮定に基づく補完・変更: 48bit"))
    }

    @Test
    fun failedMainResultShowsSharedActionAndCandidateSpecificReasonOnlyOnce() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val matrix = QrMatrix(21)
        recoveryState(activity).showAttempt(
            0,
            GridGeometry(1, FinderRole.TOP_LEFT, 10.0, 10.0, 2.0, 0.0),
            matrix,
            emptyList(),
            RecoveryFailure(
                ecLevel = ErrorCorrectionLevel.L,
                dataMask = 3,
                formatMatches = 25,
                formatMismatches = 0,
                formatKnownBits = 25,
                stage = RecoveryFailureStage.REED_SOLOMON,
                reason = RecoveryFailureReason.BOUNDED_BIT_SEARCH_LIMIT,
                failedBlockIndex = 0,
                rsBlockCount = 1,
                failedBlockErasures = 42,
                ecSymbolsPerBlock = 20,
                originalErasedCodewords = 42,
                erasedCodewords = 42,
                rsShortfallBits = 81,
            ),
            -1,
        )
        MainActivity::class.java.getDeclaredMethod("updateSummary", String::class.java).apply {
            isAccessible = true
            invoke(activity, null)
        }
        MainActivity::class.java.getDeclaredMethod("updateGuideText").apply {
            isAccessible = true
            invoke(activity)
        }

        fun text(name: String) = (MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as TextView).text.toString()
        val status = text("statusText")
        val guide = text("guideText")
        val candidateDetails = text("contentPreviewText")
        val diagnostics = (MainActivity::class.java.getDeclaredField("diagnosticReport").apply { isAccessible = true }.get(activity) as io.github.earthdiver.qrrepair.core.RecoveryDiagnosticReport).fullText()
        assertEquals("候補が見つかりませんでした", status)
        assertTrue(guide.contains("81"))
        assertTrue(!guide.contains("推奨セル"))
        assertTrue(!guide.contains("絞り込めるセル"))
        assertTrue(!guide.contains("探索上限"))
        assertTrue(diagnostics.contains("形式情報領域: 比較対象の読み取り済み25bitとの不一致 0bit"))
        assertTrue(candidateDetails.contains("探索上限"))
        assertTrue(diagnostics.contains("追加確認の目安: 81bit"))
        assertTrue(diagnostics.contains("最初に失敗したRSブロック: 1 / 全1ブロック"))
        assertTrue(diagnostics.contains("最初に失敗したRSブロック: 1 / 全1ブロック\n失敗ブロックのECCシンボル数: 20\n失敗ブロックの未知bitを含むコード語数（探索前）: 42"))
    }

    @Test
    fun clearingCandidatesForAnotherRecoveryHidesMainResultSelector() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun candidate(mask: Int) = RecoveryCandidate(
            1, ErrorCorrectionLevel.M, mask, "decoded-$mask", byteArrayOf(),
            15, 0, 0, 0, 0, 0, 8, intArrayOf(), CodewordExtraction(emptyList()),
        )
        recoveryState(activity).recoveries = listOf(candidate(0), candidate(1))
        val refresh = MainActivity::class.java.getDeclaredMethod("refreshMainResultSelector").apply { isAccessible = true }
        refresh.invoke(activity)
        val selector = MainActivity::class.java.getDeclaredField("mainResultSelector").apply { isAccessible = true }
            .get(activity) as android.widget.LinearLayout
        assertEquals(android.view.View.VISIBLE, selector.visibility)

        recoveryState(activity).recoveries = emptyList()
        refresh.invoke(activity)

        assertEquals(android.view.View.GONE, selector.visibility)
        assertEquals(0, selector.childCount)
    }

    @Test
    fun mainScreenUsesConsolidatedRepairActions() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)

        listOf("履歴", "終了", "修復する").forEach { label ->
            val control = findText(decor, label)
            assertNotNull("$label がメイン画面にありません", control)
            assertTrue("$label が小画面で見切れています", isInside(decor, control!!))
        }
        val resultActions = MainActivity::class.java.getDeclaredField("resultActions").apply { isAccessible = true }.get(activity) as View
        assertEquals(View.GONE, resultActions.visibility)
        listOf("修復設定", "修復開始", "結果を拡大", "HEX", "詳細ツール").forEach { removed ->
            assertNull("旧ボタンがメイン画面に残っています: $removed", findText(decor, removed))
        }
        assertTrue("メイン画面に大きな画像編集領域が残っています",
            findViews(decor, QrEditorView::class.java).isEmpty())

        findExactText(decor, "終了")!!.performClick()
        assertTrue("終了ボタンでActivityが終了しません", activity.isFinishing)
    }

    @Test
    fun smallScreenShowsAndCanScrollThroughAllBasicSettings() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        MainActivity::class.java.getDeclaredField("prepared").apply {
            isAccessible = true
            set(activity, BitmapUtils.prepare(bitmap))
        }
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply {
            isAccessible = true
            invoke(activity)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)

        val required = listOf("詳細設定", "微調整幅:", "選択範囲を正方形に射影補正", "QRバージョン:", "Finderの位置と役割", "キャンセル", "保存", "設定を保存して修復")
        required.forEach { label ->
            val view = findText(decor, label)
            assertNotNull("$label がView階層にありません", view)
            assertTrue("$label の幅が0です", view!!.measuredWidth > 0)
            assertTrue("$label の高さが0です", view.measuredHeight > 0)
        }
        listOf(
            "画像上の色:", "高度な白黒判定", "カメラのクロップ", "場所ごとの補正をON",
            "この設定で修復", "（全Version探索なし）", "紫の角",
        ).forEach { removed -> assertNull("削除対象の表示が残っています: $removed", findText(decor, removed)) }
        assertTrue("濃淡指定の数値入力欄が残っています", findViews(decor, EditText::class.java).isEmpty())
        assertTrue("Versionスライダーが残っているか、濃淡スライダーが不足しています",
            findViews(decor, SeekBar::class.java).size == 2)
        assertTrue("濃淡ラベルに中間値の説明が残っています",
            findViews(decor, TextView::class.java).none { it.text.toString().contains("中間は") })

        val fixedGeometry = findText(decor, "修復には表示中のQR格子位置・向き")
        assertNotNull("固定幾何の説明がありません", fixedGeometry)
        val geometrySearch = findText(decor, "サンプリング位置を自動微調整する")
        val geometrySearchNote = findText(decor, "ONでは0.6モジュールの位置・大きさ・一隅のずれを試します")
        val payloadTrust = findText(decor, "種類・文字数／バイト数が正しく読み取れている場合")
        val payloadTrustNote = findText(decor, "数字・英数字・バイト・漢字のいずれか1種類")
        assertNull("Global locking is replaced by per-cell modes", findText(decor, "手動指定した0/1と矛盾する"))
        assertNotNull("四隅近傍探索の説明がありません", geometrySearchNote)
        assertNotNull("単一セグメント前提の設定がありません", payloadTrust)
        assertNotNull("単一セグメント前提の説明がありません", payloadTrustNote)
        val detailedSettingsColumn = geometrySearch!!.parent as ViewGroup
        assertTrue("四隅近傍探索の説明が該当設定の直後にありません",
            detailedSettingsColumn.indexOfChild(geometrySearchNote!!) == detailedSettingsColumn.indexOfChild(geometrySearch) + 1)
        assertTrue("Payload trust note must remain immediately below its control",
            detailedSettingsColumn.indexOfChild(payloadTrustNote!!) == detailedSettingsColumn.indexOfChild(payloadTrust!!) + 1)
        assertTrue("微調整幅の既定値が0.10 moduleではありません",
            findText(decor, "微調整幅:")!!.text.toString() == "微調整幅: 0.10 module")

        val imageHost = findLargestVisible(decor, FrameLayout::class.java)
        assertNotNull("画像表示領域がありません", imageHost)
        assertTrue("画像表示領域が小さすぎます", imageHost!!.measuredHeight >= 240)

        val scroll = findAncestor(fixedGeometry!!, ScrollView::class.java)
        assertNotNull("基本設定がScrollView内にありません", scroll)
        scroll!!.fullScroll(View.FOCUS_DOWN)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("基本設定をスクロールできません", scroll.scrollY > 0)
        assertTrue("固定下部の保存ボタンがダイアログから見切れています", isInside(decor, findText(decor, "設定を保存して修復")!!))
        findExactText(decor, "保存")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("保存ボタンだけで修復前設定画面が閉じています", dialog.isShowing)

        val basicCellEdit = findText(decor, "セル編集（長押し）")!!
        val finderManual = findText(decor, "Finderの位置と役割")!!
        assertTrue("セル編集がFinder指定の上にありません", screenTop(basicCellEdit) < screenTop(finderManual))
        basicCellEdit.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("通常タップでセル編集が開きます", basicCellEdit.isShown)
        assertTrue("長押しでセル編集が開きません", basicCellEdit.performLongClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("セル編集の既定表示が従来の1セル編集ではありません",
            findViews(decor, PreprocessOverlayView::class.java).single().visibility == View.VISIBLE)
        assertTrue("一括ブラシ画面が既定表示になっています",
            findViews(decor, QrEditorView::class.java).all { it.visibility != View.VISIBLE })
        val editCount = findText(decor, "変更操作:")
        assertNotNull("変更回数・変更セル数の表示がありません", editCount)
        assertTrue("変更セル数の表示がありません", editCount!!.text.contains("変更セル: 0セル"))
        assertTrue("優先探索の表示が残っています", !editCount.text.contains("優先探索"))
        assertNotNull("*指定ボタンがありません", findExactText(decor, "*"))
        assertNotNull("ブラシ・矩形を元画像上で操作する案内がありません",
            findText(decor, "タップでセル選択。矢印で移動、0/1/?/*で編集。"))
        assertNull("小型拡大表示でブラシ・矩形を操作する旧案内が残っています",
            findText(decor, "ブラシ・矩形は元画像と拡大表示のどちらでも操作できます。"))
        findText(decor, "0ブラシ")!!.performClick()
        assertTrue("ブラシ選択時に元画像オーバーレイが非表示になります",
            findViews(decor, PreprocessOverlayView::class.java).single().visibility == View.VISIBLE)
        findText(decor, "大きな編集画面を開く")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val editorDialog = ShadowDialog.getLatestDialog() as AlertDialog
        val editorDecor = editorDialog.window!!.decorView
        assertTrue("専用編集画面に拡大・パン可能な編集ビューがありません",
            findViews(editorDecor, QrEditorView::class.java).size == 1)
        val largeEditor = findViews(editorDecor, QrEditorView::class.java).single()
        assertNull("大きな編集画面を開いた時点で対象セルが選択されています",
            QrEditorView::class.java.getDeclaredField("selected").apply { isAccessible = true }.get(largeEditor))
        assertTrue("大きな編集画面へ以前の選択範囲が持ち越されています",
            (QrEditorView::class.java.getDeclaredField("selectedCells").apply { isAccessible = true }.get(largeEditor) as Set<*>).isEmpty())
        val codewordToggle = findExactText(editorDecor, "コード語構造") as? CheckBox
        assertNotNull("専用編集画面にコード語構造の切替がありません", codewordToggle)
        assertTrue("コード語構造チェックが画面から見切れています", isInside(editorDecor, codewordToggle!!))
        assertTrue("コード語構造チェックの表示高が不足しています", codewordToggle.measuredHeight >= 40)
        assertTrue("コード語構造が既定でONになっています", !codewordToggle.isChecked)
        codewordToggle.performClick()
        assertTrue("コード語構造チェックをONにしても描画設定へ反映されません", largeEditor.showCodewordOverlay)
        assertNotNull("コード語構造の色と拡大条件の説明がありません", findText(editorDecor, "青: Data / 橙: ECC"))
        listOf("移動・拡大", "0ブラシ", "1ブラシ", "?ブラシ1", "?ブラシ3", "矩形へ", "? 7×7", "? 5×5", "? 3×3", "Undo", "Redo").forEach { label ->
            assertNotNull("専用編集画面に${label}がありません", findText(editorDecor, label))
        }
        findText(editorDecor, "矩形へ")!!.performClick()
        listOf("スタンプへ", "0", "1", "?", "*").forEach { label ->
            assertNotNull("矩形モードに${label}がありません", findExactText(editorDecor, label))
        }
        val switchMode = findExactText(editorDecor, "スタンプへ")!!
        val zero = findExactText(editorDecor, "0")!!
        assertTrue("切替ボタンが1文字ボタンと同じ幅に圧縮されています",
            switchMode.measuredWidth >= zero.measuredWidth * 1.8f)
        assertEquals("切替ボタンの圧縮で行高が崩れています", zero.measuredHeight, switchMode.measuredHeight)
        assertNotNull("専用編集画面の小型タイトルがありません", findExactText(editorDecor, "セル編集（拡大）"))
        assertEquals("標準タイトル欄と小型タイトルが重複しています", 1,
            findViews(editorDecor, TextView::class.java).count { it.text.toString() == "セル編集（拡大）" })
        editorDialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        findText(decor, "戻る")!!.performClick()
        findText(decor, "詳細設定")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        findText(decor, "データ")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("データ表示領域がダイアログから見切れています", isInside(decor, findText(decor, "マスク解除後")!!))
    }

    @Test
    fun preprocessStateTransitionsDoNotLeaveStaleOrUnsafeUi() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }
        MainActivity::class.java.getDeclaredField("prepared").apply {
            isAccessible = true
            set(activity, BitmapUtils.prepare(bitmap))
        }
        MainActivity::class.java.getDeclaredMethod("showPreprocessDialog").apply {
            isAccessible = true
            invoke(activity)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val decor = dialog.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)
        val overlay = findViews(decor, PreprocessOverlayView::class.java).single()

        // A corner magnifier is transient: opening another setting must clear it immediately.
        PreprocessOverlayView::class.java.getDeclaredField("selectedCorner").apply {
            isAccessible = true
            set(overlay, 0)
        }
        findText(decor, "QRバージョン:")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("Version選択を開いても四隅拡大表示が残ります",
            PreprocessOverlayView::class.java.getDeclaredField("selectedCorner").apply { isAccessible = true }.get(overlay))
        (ShadowDialog.getLatestDialog() as AlertDialog).dismiss()

        // Threshold changes must replace the currently rendered 1/0/? classification.
        overlay.draw(Canvas(Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)))
        shadowOf(Looper.getMainLooper()).idle()
        val before = findText(decor, "判定:")!!.text.toString()
        val thresholds = findViews(decor, SeekBar::class.java)
        thresholds.first().progress = 160
        overlay.draw(Canvas(Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)))
        shadowOf(Looper.getMainLooper()).idle()
        val after = findText(decor, "判定:")!!.text.toString()
        assertTrue("濃淡変更後も1/0/?集計が更新されません: $before", before != after)

        // Primary one-cell controls must fit before the optional brush/navigation controls.
        findText(decor, "セル編集（長押し）")!!.performLongClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("セル編集画面へ以前の対象セルが持ち越されています", overlay.selectedEditCell())
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 360, 640)
        listOf("0", "1", "?", "Undo", "Redo").forEach { label ->
            val control = findExactText(decor, label)
            assertNotNull("1セル編集の$label ボタンがありません", control)
        }
        listOf("←", "↑", "↓", "→").forEach { label ->
            val control = findExactText(decor, label)
            assertNotNull("1セル編集の$label キーがありません", control)
            assertTrue("1セル編集の$label キーが見切れています", isInside(decor, control!!))
        }
        val arrow = findExactText(decor, "←")!!
        val value = findExactText(decor, "0")!!
        val undo = findExactText(decor, "Undo")!!
        val mode = findExactText(decor, "1セル")!!
        val large = findText(decor, "大きな編集画面を開く")!!
        assertTrue("セル編集ボタンの順序が指定どおりではありません",
            screenTop(arrow) < screenTop(value) && screenTop(value) < screenTop(mode) &&
                screenTop(mode) < screenTop(undo) && screenTop(undo) < screenTop(large))
        val cellScroll = findAncestor(large, ScrollView::class.java)
        assertNotNull("セル編集画面が縦スクロールに対応していません", cellScroll)
        cellScroll!!.fullScroll(View.FOCUS_DOWN)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("小画面で大きな編集画面ボタンまで到達できません", isInside(decor, large))
        findText(decor, "戻る")!!.performClick()
        findText(decor, "戻る")!!.performClick()

        // The threshold change above is unsaved, so Cancel must ask before discarding it.
        findExactText(decor, "キャンセル")!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val discardConfirm = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull("未保存キャンセル時の確認ダイアログがありません",
            findText(discardConfirm.window!!.decorView, "未保存の変更があります"))
        discardConfirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue("破棄確認で戻った後に修復前設定が閉じています", dialog.isShowing)
    }

    private fun findText(view: View, prefix: String): TextView? {
        if (view is TextView && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), prefix)?.let { return it }
        return null
    }

    private fun recoveryState(activity: MainActivity): RecoveryUiState =
        MainActivity::class.java.getDeclaredField("recoveryState").apply { isAccessible = true }
            .get(activity) as RecoveryUiState

    private fun findExactText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findExactText(view.getChildAt(i), value)?.let { return it }
        return null
    }

    private fun <T : View> findAncestor(view: View, type: Class<T>): T? {
        var current = view.parent
        while (current is View) {
            if (type.isInstance(current)) return type.cast(current)
            current = current.parent
        }
        return null
    }

    private fun <T : View> findLargestVisible(view: View, type: Class<T>): T? {
        var best: T? = if (type.isInstance(view) && view.visibility == View.VISIBLE) type.cast(view) else null
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val candidate = findLargestVisible(view.getChildAt(i), type)
            if (candidate != null && (best == null || candidate.measuredHeight > best!!.measuredHeight)) best = candidate
        }
        return best
    }

    private fun <T : View> findViews(view: View, type: Class<T>): List<T> = buildList {
        if (type.isInstance(view)) add(type.cast(view))
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(findViews(view.getChildAt(i), type))
    }

    private fun isInside(root: View, child: View): Boolean {
        val rootLocation = IntArray(2); val childLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation); child.getLocationOnScreen(childLocation)
        return childLocation[0] >= rootLocation[0] &&
            childLocation[0] + child.measuredWidth <= rootLocation[0] + root.measuredWidth &&
            childLocation[1] >= rootLocation[1] &&
            childLocation[1] + child.measuredHeight <= rootLocation[1] + root.measuredHeight
    }

    private fun screenTop(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]
}
