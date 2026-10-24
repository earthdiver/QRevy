package io.github.earthdiver.qrrepair.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProvisionalStructureGuideTest {
    @Test fun physicalGridShowsFourFindersAndFourTimingStripsAcrossVersionsAndPerspective() {
        for (version in listOf(1, 7, 40)) for (skewed in listOf(false, true)) {
            fixture(version, skewed) { view, geometry, render ->
                view.setPhysicalImageQuad(geometry.imageQuad!!)
                val image = render()
                try {
                    val d = geometry.dimension
                    for (y in listOf(3, d - 4)) for (x in listOf(3, d - 4)) {
                        assertGreen(image, geometry, x, y, true)
                    }
                    for (k in 8..d - 9 step 2) {
                        for ((x, y) in listOf(k to 6, 6 to k, k to d - 7, d - 7 to k)) {
                            assertGreen(image, geometry, x, y, true)
                        }
                    }
                    assertGreen(image, geometry, 8, d - 8, false)
                    assertFalse(view.isLogicalGridAssigned())
                    assertTrue(view.finderPoints().isEmpty())
                } finally { image.recycle() }
            }
        }
    }

    @Test fun assigningRolesRestoresOnlyThreeFindersTwoTimingStripsAndTheDarkModule() {
        fixture { view, geometry, render ->
            view.setImageQuad(geometry.imageQuad!!)
            val assigned = render()
            view.setPhysicalImageQuad(geometry.imageQuad!!)
            render().recycle()
            view.setImageQuad(geometry.imageQuad!!)
            val restored = render()
            try {
                assertTrue("Provisional paint must not leak into assigned rendering", assigned.sameAs(restored))
                for ((x, y) in listOf(3 to 3, 17 to 3, 3 to 17, 10 to 6, 6 to 10, 8 to 13)) {
                    assertGreen(restored, geometry, x, y, true)
                }
                for ((x, y) in listOf(17 to 17, 10 to 14, 14 to 10)) {
                    assertGreen(restored, geometry, x, y, false)
                }
            } finally { assigned.recycle(); restored.recycle() }
        }
    }

    @Test fun guidesRequireAGridAndRespectManualOnlyDisplay() {
        fixture { view, geometry, render ->
            val outlineOnly = render()
            try { assertGreen(outlineOnly, geometry, 3, 3, false) }
            finally { outlineOnly.recycle() }
            view.setPhysicalImageQuad(geometry.imageQuad!!)
            view.highlightManualOnly = true
            val hidden = render()
            try {
                for (y in listOf(3, 17)) for (x in listOf(3, 17)) assertGreen(hidden, geometry, x, y, false)
            } finally { hidden.recycle() }
        }
    }

    @Test fun removingLastFinderRestoresProvisionalGuidesWithoutChangingCorners() {
        fixture { view, geometry, render ->
            val quad = geometry.imageQuad!!
            view.setPhysicalImageQuad(quad)
            val before = render()
            view.setFinderPoints(mapOf(FinderRole.TOP_LEFT to geometry.moduleToImage(3.0, 3.0)))
            assertTrue(view.removeConfirmedFinder(FinderRole.TOP_LEFT))
            val after = render()
            try {
                assertEquals(quad, view.imageQuad())
                assertFalse(view.isLogicalGridAssigned())
                assertTrue(before.sameAs(after))
            } finally { before.recycle(); after.recycle() }
        }
    }

    @Test fun provisionalRenderingPreservesManualValuesAndExplainsTheGuideInTheLegend() {
        fixture { view, geometry, render ->
            val matrix = QrMatrix(21).apply {
                set(3, 3, ModuleValue.ZERO, 1f, ModuleSource.USER)
                set(17, 17, ModuleValue.ONE, .5f, ModuleSource.USER_SOFT)
            }
            val before = matrix.copy()
            view.setCellEditPreview(geometry, matrix)
            view.setPhysicalImageQuad(geometry.imageQuad!!)
            render().recycle()
            fun legend() = PreprocessOverlayView::class.java.getDeclaredField("lastClassificationSummary")
                .apply { isAccessible = true }.get(view) as String
            assertEquals(2, legend().lines().size)
            assertTrue(legend().contains("provisional alignment guides") || legend().contains("位置合わせ用の仮ガイド"))
            for (y in 0 until 21) for (x in 0 until 21) assertEquals(before[x, y], matrix[x, y])
            view.highlightManualOnly = true
            render().recycle()
            assertEquals(1, legend().lines().size)
            view.highlightManualOnly = false
            view.setImageQuad(geometry.imageQuad!!)
            render().recycle()
            assertEquals(1, legend().lines().size)
        }
    }

    private fun assertGreen(image: Bitmap, geometry: GridGeometry, x: Int, y: Int, expected: Boolean) {
        val p = geometry.moduleToImage(x.toDouble(), y.toDouble())
        val color = image.getPixel(p.x.roundToInt(), p.y.roundToInt())
        assertEquals("Guide at ($x, $y), version ${geometry.version}", expected,
            Color.green(color) > Color.red(color) + 15 && Color.green(color) > Color.blue(color) + 10)
    }

    private fun fixture(version: Int = 1, skewed: Boolean = false,
                        test: (PreprocessOverlayView, GridGeometry, () -> Bitmap) -> Unit) {
        val d = 17 + 4 * version
        val size = d * 10 + 40
        val edge = 20.0 + (d - 1) * 10
        val quad = ImageQuad(PointD(20.0, 20.0), PointD(edge, if (skewed) 65.0 else 20.0),
            PointD(if (skewed) edge - 30 else edge, edge), PointD(20.0, edge))
        val geometry = GridGeometry(version, FinderRole.TOP_LEFT, 20.0, 20.0, 10.0, 0.0,
            quad, GeometryModel.MANUAL_PROJECTIVE).reanchored()
        val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val view = PreprocessOverlayView(RuntimeEnvironment.getApplication()).apply {
            setBitmap(source)
            setVersion(version)
            layout(0, 0, size, size)
        }
        try {
            test(view, geometry) {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
        } finally { source.recycle() }
    }
}
