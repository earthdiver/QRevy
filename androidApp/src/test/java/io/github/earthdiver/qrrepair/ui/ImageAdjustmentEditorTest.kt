package io.github.earthdiver.qrrepair.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import io.github.earthdiver.qrrepair.R
import io.github.earthdiver.qrrepair.core.ImageAdjustment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAdjustmentEditorTest {
    private val activity get() = Robolectric.buildActivity(Activity::class.java).setup().get().apply { setTheme(R.style.AppTheme) }
    private fun bitmap() = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(100, 120, 140)) }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun click(editor: ImageAdjustmentEditor, label: Int) {
        views(editor).filterIsInstance<TextView>().first { it.text == editor.context.getString(label) }.performClick()
    }
    private fun layout(editor: View, width: Int = 360) {
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, width, 800)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        editor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        editor.layout(0, 0, width, 800)
    }

    @Test fun systemBarsKeepControlsInsideTheSafeArea() {
        val editor = ImageAdjustmentEditor(activity, bitmap(), onCancel = {}, onApply = {})
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 24, 0, 32))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(editor, insets)
        layout(editor)
        assertEquals(24, editor.paddingTop)
        assertEquals(32, editor.paddingBottom)
        assertTrue(editor.getChildAt(0).top >= 24)
        assertTrue(editor.getChildAt(editor.childCount - 1).bottom <= 768)
    }

    @Test fun changingToolsAndComparingPreservesImageAreaAndCrop() {
        val editor = ImageAdjustmentEditor(activity, bitmap(), onCancel = {}, onApply = {})
        layout(editor)
        val crop = RectF(10f, 12f, 70f, 90f)
        editor.preview.restoreCrop(crop)
        val height = editor.preview.height
        val top = editor.preview.top
        click(editor, R.string.image_adjustment)
        layout(editor)
        assertEquals(height, editor.preview.height)
        assertEquals(top, editor.preview.top)
        assertTrue(views(editor).filterIsInstance<SeekBar>().single().width >= 150)
        editor.setAdjustment(ImageAdjustment(30, -10, true))
        click(editor, R.string.image_before)
        assertTrue(editor.showingBefore)
        assertEquals(crop, editor.preview.cropRect())
        assertTrue(views(editor).filterIsInstance<TextView>().any { it.text == editor.context.getString(R.string.image_analyze_after) })
        layout(editor)
        assertEquals(height, editor.preview.height)
        click(editor, R.string.image_done)
        layout(editor)
        assertEquals(height, editor.preview.height)
    }

    @Test
    @Config(qualifiers = "ja-rJP-mdpi")
    fun normalButtonsKeepCompactHeightAndHomeActionSpacing() {
        for (width in listOf(320, 360)) {
            val context = activity.apply { setTheme(R.style.ModernAppTheme) }
            val editor = ImageAdjustmentEditor(context, bitmap(), onCancel = {}, onApply = {})
            layout(editor, width)
            val buttons = listOf(R.string.image_rotate, R.string.image_invert, R.string.image_adjustment,
                R.string.cancel, R.string.crop_and_analyze).map { label ->
                views(editor).filterIsInstance<TextView>().single { it.text == context.getString(label) }
            }
            buttons.forEach { assertEquals("Compact button height: ${it.text}", 36, it.height) }
            val tools = buttons[0].parent as ViewGroup
            val actions = buttons[3].parent as ViewGroup
            assertEquals(48, tools.height)
            assertEquals(tools.bottom, actions.top)
            assertEquals(tools.height - 4, buttons[0].bottom)
            assertEquals(8, buttons[3].top)
            assertEquals(12, actions.top + buttons[3].top - tools.top - buttons[0].bottom)
        }
    }

    @Test fun largeTextDoesNotReserveAnEmptySliderRowInNormalMode() {
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.5f }
        val context = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.ModernAppTheme)
        val editor = ImageAdjustmentEditor(context, bitmap(), onCancel = {}, onApply = {})
        layout(editor, 320)
        val rotate = views(editor).filterIsInstance<TextView>().single { it.text == context.getString(R.string.image_rotate) }
        val tools = rotate.parent as ViewGroup
        val actions = views(editor).filterIsInstance<TextView>().single { it.text == context.getString(R.string.cancel) }.parent as ViewGroup
        assertEquals((0 until tools.childCount).maxOf { tools.getChildAt(it).height } + 12, tools.height)
        assertEquals(tools.bottom, actions.top)
        assertEquals(tools.height - 4, rotate.bottom)
    }

    @Test
    @Config(qualifiers = "ja-rJP-mdpi")
    fun importedImageButtonsShowAllWrappedLines() {
        for (theme in listOf(R.style.AppTheme, R.style.ModernAppTheme)) {
            for (scale in listOf(1f, 1.5f)) {
                val host = activity.apply { setTheme(theme) }
                val config = android.content.res.Configuration(host.resources.configuration).apply { fontScale = scale }
                val context = android.view.ContextThemeWrapper(host.createConfigurationContext(config), theme)
                val editor = ImageAdjustmentEditor(context, bitmap(), onCancel = {}, onApply = {})
                val dialog = android.app.AlertDialog.Builder(host).setView(editor).create()
                dialog.show()
                layout(dialog.window!!.decorView, 280)
                fun assertLabelsFit(requireWrappedLabel: Boolean = false) {
                    val buttons = views(editor).filterIsInstance<android.widget.Button>()
                    if (requireWrappedLabel) assertTrue("Exercise wrapped labels", buttons.any { it.layout.lineCount > 1 })
                    buttons.forEach { button ->
                        assertTrue("Space above label: ${button.text}", button.paddingTop >= 2)
                        assertTrue("Space below label: ${button.text}", button.paddingBottom >= 2)
                        val textLayout = button.layout
                        assertEquals("Full label: ${button.text}", button.text.length,
                            textLayout.getLineEnd(textLayout.lineCount - 1))
                        for (line in 0 until textLayout.lineCount) {
                            assertEquals("No ellipsis: ${button.text}", 0, textLayout.getEllipsisCount(line))
                        }
                        assertTrue("Label height: ${button.text}: ${textLayout.height} / ${button.height}",
                            textLayout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    }
                }
                assertLabelsFit()
                click(editor, R.string.image_adjustment)
                layout(dialog.window!!.decorView, 280)
                assertLabelsFit(requireWrappedLabel = true)
                dialog.dismiss()
            }
        }
    }

    @Test fun allFooterButtonsHaveTheSameHeightWithWrappedLabels() {
        val host = activity.apply { setTheme(R.style.ModernAppTheme) }
        val config = android.content.res.Configuration(host.resources.configuration).apply { fontScale = 1.5f }
        val context = android.view.ContextThemeWrapper(host.createConfigurationContext(config), R.style.ModernAppTheme)
        val editor = ImageAdjustmentEditor(context, bitmap(), onCancel = {}, onApply = {})
        for (editing in listOf(false, true)) {
            if (editing) click(editor, R.string.image_adjustment)
            layout(editor, 280)
            val buttons = (editor.indexOfChild(editor.preview) + 1 until editor.childCount)
                .flatMap { views(editor.getChildAt(it)) }.filterIsInstance<android.widget.Button>()
            assertTrue(buttons.any { it.layout.lineCount > 1 })
            assertEquals("Every footer button uses the tallest label", 1, buttons.map { it.height }.distinct().size)
            buttons.forEach { assertTrue(it.layout.height <= it.height - it.compoundPaddingTop - it.compoundPaddingBottom) }
        }
    }

    @Test
    @Config(qualifiers = "ja-rJP-mdpi")
    fun importedImageUsesCameraWidthAndSliderPositionAndHomeButtonSpacing() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        io.github.earthdiver.qrrepair.AppStylePreferences.save(app, io.github.earthdiver.qrrepair.AppStyle.MODERN)
        val controller = Robolectric.buildActivity(io.github.earthdiver.qrrepair.MainActivity::class.java).setup()
        val host = controller.get()
        val home = host.window.decorView
        layout(home)
        val cameraButton = views(home).filterIsInstance<android.widget.Button>().single { it.text == host.getString(R.string.camera) }
        val selectButton = views(home).filterIsInstance<android.widget.Button>().single { it.text == host.getString(R.string.select_image) }
        val homeGap = selectButton.top - cameraButton.bottom
        assertEquals(12, homeGap)
        io.github.earthdiver.qrrepair.MainActivity::class.java.getDeclaredMethod("showImportedImageAdjustment", Bitmap::class.java)
            .apply { isAccessible = true; invoke(host, bitmap()) }
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val imported = views(dialog.window!!.decorView).filterIsInstance<ImageAdjustmentEditor>().single()
        val camera = ImageAdjustmentEditor(host, bitmap(), cancelLabel = R.string.retake, onCancel = {}, onApply = {})
        val noInsets = androidx.core.view.WindowInsetsCompat.Builder().build()
        for (width in listOf(320, 360, 412)) {
            dialog.window!!.setLayout(width, 800)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            layout(dialog.window!!.decorView, width)
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(imported, noInsets)
            layout(dialog.window!!.decorView, width)
            layout(camera, width)
            assertEquals("Full width for image import", width, imported.width)
            assertEquals(camera.height, imported.height)
            val rotate = views(imported).filterIsInstance<TextView>().single { it.text == host.getString(R.string.image_rotate) }
            val cancel = views(imported).filterIsInstance<TextView>().single { it.text == host.getString(R.string.cancel) }
            assertEquals(homeGap, (cancel.parent as View).top + cancel.top - (rotate.parent as View).top - rotate.bottom)
            click(imported, R.string.image_adjustment)
            click(camera, R.string.image_adjustment)
            layout(dialog.window!!.decorView, width)
            layout(camera, width)
            fun sliderRect(editor: ImageAdjustmentEditor): android.graphics.Rect {
                val slider = views(editor).filterIsInstance<SeekBar>().single()
                return android.graphics.Rect(0, 0, slider.width, slider.height).also { editor.offsetDescendantRectToMyCoords(slider, it) }
            }
            assertEquals("Slider position at width $width", sliderRect(camera), sliderRect(imported))
            click(imported, R.string.image_done)
            click(camera, R.string.image_done)
        }
        dialog.dismiss()
        camera.dispose()
        controller.pause().stop().destroy()
    }

    @Test fun cropKeepsOriginalPixelsAndComparisonDoesNotChangeSubmittedSettings() {
        val original = bitmap()
        var submitted: ImageAdjustment? = null
        val editor = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = { submitted = it })
        val adjustment = ImageAdjustment(50, 20, true)
        editor.setAdjustment(adjustment)
        val crop = editor.createCrop()
        assertEquals(original.getPixel(20, 20), crop.getPixel(0, 0))
        click(editor, R.string.image_before)
        click(editor, R.string.image_analyze_after)
        assertEquals(adjustment, submitted)
        assertFalse(original.isRecycled)
        editor.dispose()
        assertFalse(original.isRecycled)
    }

    @Test fun recreationRestoresAdjustmentRotationCropAndComparison() {
        val original = bitmap()
        val editor = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = {})
        click(editor, R.string.image_rotate)
        val menu = ShadowPopupMenu.getLatestPopupMenu()
        shadowOf(menu).onMenuItemClickListener.onMenuItemClick(menu.menu.getItem(0))
        editor.preview.restoreCrop(RectF(4f, 6f, 90f, 70f))
        editor.setAdjustment(ImageAdjustment(25, -5, true))
        click(editor, R.string.image_adjustment)
        click(editor, R.string.image_before)
        val restored = ImageAdjustmentEditor(activity, original, onCancel = {}, onApply = {})
        restored.restoreState(editor.saveState())
        assertEquals(editor.adjustment, restored.adjustment)
        assertEquals(editor.preview.cropRect(), restored.preview.cropRect())
        assertTrue(restored.showingBefore)
        assertTrue(views(restored).any { it is SeekBar })
        assertEquals(86, restored.createCrop().width)
    }

    @Test fun resetAllKeepsGeometryAndNumericInputRejectsOutOfRange() {
        val editor = ImageAdjustmentEditor(activity, bitmap(), onCancel = {}, onApply = {})
        val crop = editor.preview.cropRect()
        editor.setAdjustment(ImageAdjustment(30, -5, true))
        click(editor, R.string.image_adjustment)
        fun menu() = views(editor).filterIsInstance<TextView>().first { it.text.startsWith(editor.context.getString(R.string.image_contrast)) }
            .let { it.performClick(); ShadowPopupMenu.getLatestPopupMenu() }
        menu().let { shadowOf(it).onMenuItemClickListener.onMenuItemClick(it.menu.getItem(2)) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val input = views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
        input.setText("101")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(input.error)
        assertEquals(30, editor.adjustment.contrast)
        input.setText("-25")
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(-25, editor.adjustment.contrast)
        menu().let { shadowOf(it).onMenuItemClickListener.onMenuItemClick(it.menu.getItem(4)) }
        assertEquals(ImageAdjustment(), editor.adjustment)
        assertEquals(crop, editor.preview.cropRect())
    }

    @Test
    fun narrowLayoutAddsTheSliderRowOnlyWhileEditing() {
        val editor = ImageAdjustmentEditor(activity, bitmap(), onCancel = {}, onApply = {})
        layout(editor, 320)
        val height = editor.preview.height
        val crop = editor.preview.cropRect()
        click(editor, R.string.image_adjustment)
        layout(editor, 320)
        assertTrue(editor.preview.height < height)
        assertEquals(crop, editor.preview.cropRect())
        val seek = views(editor).filterIsInstance<SeekBar>().single()
        assertTrue(seek.width >= 280)
        assertTrue(seek.height >= 36)
        assertTrue(editor.preview.height > 400)
        click(editor, R.string.image_done)
        layout(editor, 320)
        assertEquals(height, editor.preview.height)
    }
}
