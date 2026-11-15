package io.github.earthdiver.qrrepair

import android.graphics.Bitmap
import android.net.Uri
import io.github.earthdiver.qrrepair.core.QrTextMatrixParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextMatrixImportControllerTest {
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver
    private val uri = Uri.parse("content://qr-repair-test/text-import")
    private fun register(bytes: ByteArray = List(21) { "?".repeat(21) }.joinToString("\n").toByteArray()) {
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    @Test fun supersededDeliveryReleasesTheImageWithoutPublishingResults() {
        register()
        var generation = 1
        var image: Bitmap? = null
        val callbacks = ArrayDeque<() -> Unit>()
        val controller = TextMatrixImportController(resolver, Executor { it.run() }, callbacks::addLast,
            { generation }, { false }, {}, { throw AssertionError(it) },
            standardScan = { image = it; null })
        controller.load(1, uri, false, false, { false }) { error("Stale input must not be delivered") }
        assertFalse(requireNotNull(image).isRecycled)
        generation++
        while (callbacks.isNotEmpty()) callbacks.removeFirst().invoke()
        assertTrue(requireNotNull(image).isRecycled)
    }

    @Test fun stoppedImportDeliversPreparedInputWithoutStartingAutomaticRepair() {
        register()
        var result: PreparedTextMatrixImport? = null
        val controller = TextMatrixImportController(resolver, Executor { it.run() }, { it() },
            { 1 }, { false }, {}, { throw AssertionError(it) }, standardScan = { null },
            automaticScan = { _, _, _ -> error("Stopped imports must not start repair") })
        controller.load(1, uri, true, false, { true }) { result = it }
        val imported = requireNotNull(result)
        try {
            assertFalse(imported.source.bitmap.isRecycled)
            assertNull(imported.automaticResult)
            assertFalse(imported.settings.automaticThresholds)
            assertEquals(64, imported.settings.blackMax)
            assertEquals(192, imported.settings.whiteMin)
            assertNotNull(imported.settings.manualQuad)
        } finally { imported.source.bitmap.recycle() }
    }

    @Test fun invalidUtf8AndOversizedInputAreRejectedBeforeScanning() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(QrTextMatrixParser.MAX_TEXT_CHARS + 1))) {
            register(bytes)
            var failure: Throwable? = null
            val controller = TextMatrixImportController(resolver, Executor { it.run() }, { it() },
                { 1 }, { false }, {}, { failure = it }, standardScan = { error("Invalid input must not be scanned") })
            controller.load(1, uri, false, false, { false }) { error("Invalid input must not be delivered") }
            assertTrue(failure is IllegalArgumentException)
            assertEquals(if (bytes.size > 2) "Text matrix is too large" else "Text matrix must be UTF-8", failure!!.message)
        }
    }

    @Test fun failedScanReleasesItsImageAndReportsTheFailure() {
        register()
        var image: Bitmap? = null
        val failure = IllegalStateException("Scan failed")
        var reported: Throwable? = null
        val controller = TextMatrixImportController(resolver, Executor { it.run() }, { it() },
            { 1 }, { false }, {}, { reported = it }, standardScan = { image = it; throw failure })
        controller.load(1, uri, false, false, { false }) { error("Failed input must not be delivered") }
        assertSame(failure, reported)
        assertTrue(requireNotNull(image).isRecycled)
    }
}
