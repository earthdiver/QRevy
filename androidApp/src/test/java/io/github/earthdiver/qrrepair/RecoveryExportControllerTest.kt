package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import io.github.earthdiver.qrrepair.core.ModuleSource
import io.github.earthdiver.qrrepair.core.ModuleValue
import io.github.earthdiver.qrrepair.core.QrMatrix
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryExportControllerTest {
    private class ExportFixture(output: OutputStream) {
        val work = ArrayDeque<Runnable>()
        val ui = ArrayDeque<() -> Unit>()
        val notices = mutableListOf<String>()
        val uri = Uri.parse("content://qr-repair-test/export-output")
        val controller: RecoveryExportController

        init {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.contentResolver).registerOutputStream(uri, output)
            controller = RecoveryExportController(context, Executor { work.addLast(it) },
                { ui.addLast(it) }, { notices += it })
        }
    }

    private class CapturedOutput : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    @Test fun resultRenderingPreservesModuleValuesAndFourModuleQuietZone() {
        val matrix = QrMatrix(21).apply {
            set(0, 0, ModuleValue.ONE, 1f, ModuleSource.USER)
            set(1, 0, ModuleValue.ZERO, 1f, ModuleSource.RECOVERED)
        }
        val bitmap = renderMatrixPng(matrix)
        try {
            assertEquals(290, bitmap.width)
            assertEquals(290, bitmap.height)
            assertEquals(Color.WHITE, bitmap.getPixel(39, 45))
            assertEquals(Color.WHITE, bitmap.getPixel(45, 39))
            assertEquals(Color.BLACK, bitmap.getPixel(40, 40))
            assertEquals(Color.BLACK, bitmap.getPixel(49, 49))
            assertEquals(Color.WHITE, bitmap.getPixel(50, 45))
            assertEquals(0xFFB0B0B0.toInt(), bitmap.getPixel(60, 45))
            assertEquals(0xFFB0B0B0.toInt(), bitmap.getPixel(249, 249))
            assertEquals(Color.WHITE, bitmap.getPixel(250, 249))
            assertEquals(Color.WHITE, bitmap.getPixel(249, 250))
        } finally { bitmap.recycle() }
    }

    @Test fun diagnosticExportReleasesOnlyOwnedImagesAfterWritingAndReportsOnUi() =
        withTestAppLanguage(AppLanguage.ENGLISH) {
            for (owned in listOf(true, false)) {
                val output = CapturedOutput()
                val fixture = ExportFixture(output)
                val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
                fixture.controller.saveDiagnosticInput(fixture.uri, PendingDiagnosticBitmap(bitmap, owned))
                assertEquals(0, output.size())
                assertFalse(bitmap.isRecycled)
                fixture.work.removeFirst().run()
                assertTrue(output.size() > 0)
                assertTrue(output.closed)
                assertEquals(owned, bitmap.isRecycled)
                assertTrue(fixture.notices.isEmpty())
                fixture.ui.removeFirst().invoke()
                assertEquals(listOf("Saved debug input image (2×3 px)."), fixture.notices)
                if (!owned) bitmap.recycle()
            }
        }

    @Test fun failedDiagnosticExportStillReleasesOwnedImagesAndKeepsBorrowedImages() =
        withTestAppLanguage(AppLanguage.ENGLISH) {
            for (owned in listOf(true, false)) {
                var closed = false
                val fixture = ExportFixture(object : OutputStream() {
                    override fun write(value: Int) { throw IOException("Destination unavailable") }
                    override fun close() { closed = true }
                })
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                fixture.controller.saveDiagnosticInput(fixture.uri, PendingDiagnosticBitmap(bitmap, owned))
                fixture.work.removeFirst().run()
                assertTrue(closed)
                assertEquals(owned, bitmap.isRecycled)
                fixture.ui.removeFirst().invoke()
                assertTrue(fixture.notices.single().startsWith("Input image save failed:"))
                if (!owned) bitmap.recycle()
            }
        }

    @Test fun resultExportDistinguishesUnknownCellsAndReportsDestinationFailure() =
        withTestAppLanguage(AppLanguage.ENGLISH) {
            val matrix = QrMatrix(21)
            for (unknown in listOf(true, false)) {
                if (!unknown) for (y in 0 until 21) for (x in 0 until 21) {
                    matrix.set(x, y, ModuleValue.ZERO, 1f, ModuleSource.RECOVERED)
                }
                val output = CapturedOutput()
                val fixture = ExportFixture(output)
                fixture.controller.savePng(fixture.uri, matrix)
                fixture.work.removeFirst().run()
                assertTrue(output.size() > 0)
                assertTrue(output.closed)
                assertTrue(fixture.notices.isEmpty())
                fixture.ui.removeFirst().invoke()
                assertEquals(listOf(if (unknown) "PNG saved. Unresolved ? cells are shown in diagnostic grey."
                    else "Repair result PNG saved."), fixture.notices)
            }
            val fixture = ExportFixture(object : OutputStream() {
                override fun write(value: Int) { throw IOException("Destination unavailable") }
            })
            fixture.controller.savePng(fixture.uri, matrix)
            fixture.work.removeFirst().run()
            fixture.ui.removeFirst().invoke()
            assertTrue(fixture.notices.single().startsWith("PNG save failed:"))
        }
}
