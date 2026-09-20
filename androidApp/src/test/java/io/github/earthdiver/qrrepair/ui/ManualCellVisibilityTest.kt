package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualCellVisibilityTest {
    private val focus = Coord(10, 10)
    private val geometry = GridGeometry(1, FinderRole.TOP_LEFT, 66.0, 66.0, 12.0, 0.0)

    @Test fun tentativeBorderHasReadableGapsAtNormalAndEnlargedSizes() {
        for (halfSize in listOf(5, 16, 40, 80)) {
            val bitmap = Bitmap.createBitmap(halfSize * 2 + 20, halfSize * 2 + 20, Bitmap.Config.ARGB_8888)
            try {
                ManualCellMarker().draw(Canvas(bitmap), halfSize + 10f, halfSize + 10f,
                    halfSize.toFloat(), 1.5f, tentative = true)
                var gap = 0
                var widestGap = 0
                var cyanPixels = 0
                var strokes = 0
                var wasCyan = false
                for (x in 10 until 10 + halfSize * 2) {
                    val color = bitmap.getPixel(x, 10)
                    val cyan = Color.red(color) < 80 && Color.green(color) > 190 && Color.blue(color) > 190
                    if (cyan) {
                        cyanPixels++
                        if (!wasCyan) strokes++
                        gap = 0
                    } else {
                        gap++
                        widestGap = maxOf(widestGap, gap)
                    }
                    wasCyan = cyan
                }
                assertTrue("Visible strokes at size $halfSize: $cyanPixels", cyanPixels >= halfSize * .8f)
                assertTrue("Visible gaps at size $halfSize: $widestGap", widestGap >= halfSize * .25f)
                assertTrue("Gaps must not dominate at size $halfSize: $widestGap", widestGap <= halfSize * .6f)
                assertTrue("Enlarging must not add fine dashes: $strokes at $halfSize", strokes in 2..3)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun tentativeMarkerIsDashedAndRemainsVisibleInFocusModeAndMagnifier() {
        val source = image()
        try {
            val matrix = matrix(ModuleValue.ZERO)
            val large = QrEditorView(RuntimeEnvironment.getApplication()).apply {
                setDocument(source, geometry, matrix)
                highlightManualOnly = true
            }
            val overlay = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(source)
                setCellEditing(geometry, matrix, focus)
                highlightManualOnly = true
            }
            for (view in listOf(large, overlay)) {
                matrix[focus.x, focus.y].source = ModuleSource.USER
                val fixed = render(view)
                matrix[focus.x, focus.y].source = ModuleSource.USER_SOFT
                val soft = render(view)
                assertTrue("Tentative marker must remain visible", cyanCount(soft) > 10)
                assertTrue("Tentative border must contain gaps", cyanCount(soft) < cyanCount(fixed))
                matrix[focus.x, focus.y].source = ModuleSource.USER
                val restored = render(view)
                assertTrue("Dashed paint leaked into fixed markers", fixed.sameAs(restored))
                fixed.recycle(); soft.recycle(); restored.recycle()
            }
        } finally { source.recycle() }
    }

    @Test fun magnifierShowsManualValuesEvenWhenSelectedAndMatchingImageValues() {
        val source = image()
        try {
            for (value in ModuleValue.entries) for (selected in listOf(focus, Coord(11, 10))) {
                val matrix = matrix(value)
                val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                    setBitmap(source)
                    setCellEditing(geometry, matrix, focus)
                    setCellSelection(setOf(selected))
                }
                val before = render(view)
                matrix[focus.x, focus.y].source = ModuleSource.USER
                val manual = render(view)
                // Inspect only the top inset, excluding the main image's centre marker.
                assertTrue("Missing 5x manual marker for $value / selection $selected",
                    cyanCount(manual, 0, 0, 168, 168) > cyanCount(before, 0, 0, 168, 168) + 30)
                matrix[focus.x, focus.y].source = ModuleSource.IMAGE
                val restored = render(view)
                assertTrue("Restoring Image left a stale 5x marker", before.sameAs(restored))
                before.recycle(); manual.recycle(); restored.recycle()
            }
        } finally { source.recycle() }
    }

    @Test fun largeEditorShowsManualValuesAlongsideSelectionPriorityAndCodewordOverlays() {
        val source = image()
        try {
            for (value in ModuleValue.entries) for (selected in listOf(false, true)) {
                val matrix = matrix(value)
                if (value == ModuleValue.ERASED) matrix.setPrioritySearch(focus.x, focus.y, true)
                val view = QrEditorView(RuntimeEnvironment.getApplication()).apply {
                    setDocument(source, geometry, matrix)
                    setCodewordOverlayVersion(1)
                    showCodewordOverlay = true
                    showQrStructureOverlay = true
                    if (selected) setSelection(setOf(focus))
                }
                val before = render(view)
                matrix[focus.x, focus.y].source = ModuleSource.USER
                val manual = render(view)
                assertTrue("Missing large-editor manual marker for $value / selected=$selected",
                    cyanCount(manual) > cyanCount(before) + 10)
                matrix[focus.x, focus.y].source = ModuleSource.IMAGE
                val restored = render(view)
                assertTrue("Restoring Image left a stale large-editor marker", before.sameAs(restored))
                before.recycle(); manual.recycle(); restored.recycle()
            }
        } finally { source.recycle() }
    }

    @Test fun magnifierShowsPriorityManualSourceWithoutUsingSelectionColour() {
        val source = image()
        try {
            val matrix = matrix(ModuleValue.ERASED)
            matrix.setPrioritySearch(focus.x, focus.y, true)
            val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(source)
                setCellEditing(geometry, matrix, focus)
                setCellSelection(setOf(focus))
            }
            val before = render(view)
            matrix[focus.x, focus.y].source = ModuleSource.USER
            val manual = render(view)
            assertTrue(cyanCount(manual, 0, 0, 168, 168) > cyanCount(before, 0, 0, 168, 168) + 30)
            before.recycle(); manual.recycle()
        } finally { source.recycle() }
    }

    @Test fun focusModeHidesOtherLabelsWithoutChangingImageOrManualMarkers() {
        val source = image()
        try {
            val matrix = matrix(ModuleValue.ZERO)
            matrix[focus.x, focus.y].source = ModuleSource.USER
            val large = QrEditorView(RuntimeEnvironment.getApplication()).apply {
                setDocument(source, geometry, matrix)
                setSelection(setOf(focus))
            }
            val overlay = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
                setBitmap(source)
                setCellEditing(geometry, matrix, focus)
                setCellSelection(setOf(focus))
            }
            for (view in listOf(large, overlay)) {
                val before = render(view)
                when (view) {
                    is QrEditorView -> view.highlightManualOnly = true
                    is PreprocessOverlayView -> view.highlightManualOnly = true
                }
                val focused = render(view)
                assertTrue("Focus mode must visibly remove other labels", !before.sameAs(focused))
                assertTrue("Focus mode hid the manual marker", cyanCount(focused) > 10)
                when (view) {
                    is QrEditorView -> view.highlightManualOnly = false
                    is PreprocessOverlayView -> view.highlightManualOnly = false
                }
                val restored = render(view)
                assertTrue("Display toggle changed the document", before.sameAs(restored))
                assertTrue(!source.isRecycled)
                assertTrue(matrix[focus.x, focus.y].source == ModuleSource.USER)
                before.recycle(); focused.recycle(); restored.recycle()
            }
        } finally { source.recycle() }
    }

    private fun matrix(value: ModuleValue) = QrMatrix(21).apply {
        for (y in 0 until dimension) for (x in 0 until dimension) set(x, y, value, 1f, ModuleSource.IMAGE)
    }
    private fun image() = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
    private fun render(view: View): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 360)
        return Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }
    private fun cyanCount(image: Bitmap, left: Int = 0, top: Int = 0, right: Int = image.width, bottom: Int = image.height): Int {
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val colour = image.getPixel(x, y)
            if (Color.red(colour) < 80 && Color.green(colour) > 190 && Color.blue(colour) > 190) count++
        }
        return count
    }
}
