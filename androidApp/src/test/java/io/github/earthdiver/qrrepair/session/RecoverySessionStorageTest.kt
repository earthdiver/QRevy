package io.github.earthdiver.qrrepair.session

import android.graphics.Bitmap
import android.graphics.Color
import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.UiMode
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoverySessionStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun folder(id: String) = File(context.noBackupFilesDir, "qr_sessions/$id")
    private fun entry() = RecoveryHistoryEntry(kind = HistoryEventKind.IMPORT, label = "Synthetic input",
        snapshot = RecoverySnapshot(null, null, null, -1))
    private fun bitmap(color: Int = Color.BLACK) = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }
    private fun storeSession(id: String): RecoverySessionStore {
        folder(id).deleteRecursively()
        return RecoverySessionStore(context).also { it.save(id, UiMode.ANALYSIS, listOf(entry()), 0) }
    }
    private fun source(folder: File) = File(folder, "source-${File(folder, "source.ref").readText()}.png")
    private fun writePng(file: File, bitmap: Bitmap) {
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun sourceRoundTripAndReplacementUseTheVerifiedPointer() {
        val id = "source-roundtrip"
        val store = storeSession(id)
        store.saveSourceBitmap(id, bitmap())
        val first = source(folder(id))
        assertTrue(first.isFile)
        val restored = store.load(id)!!.sourceBitmap!!
        assertEquals(3, restored.width)
        assertEquals(2, restored.height)
        assertEquals(Color.BLACK, restored.getPixel(0, 0))

        store.saveSourceBitmap(id, bitmap(Color.WHITE))
        val second = source(folder(id))
        assertNotEquals(first, second)
        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertEquals(Color.WHITE, store.load(id)!!.sourceBitmap!!.getPixel(0, 0))
    }

    @Test fun unavailableOrUnverifiedSourcesDoNotHideTheValidTimeline() {
        val mutations: List<(File) -> Unit> = listOf(
            { File(it, "source.ref").delete() },
            { File(it, "source.ref").writeText("../invalid-pointer") },
            { File(it, "source.ref").writeText("g".repeat(64)) },
            { source(it).delete() },
            { source(it).appendBytes(byteArrayOf(1)) },
            { folder ->
                val invalid = byteArrayOf(1, 2, 3)
                val hash = digest(invalid)
                File(folder, "source-$hash.png").writeBytes(invalid)
                File(folder, "source.ref").writeText(hash)
            },
        )
        mutations.forEachIndexed { index, mutate ->
            val id = "unverified-source-$index"
            val store = storeSession(id)
            store.saveSourceBitmap(id, bitmap())
            mutate(folder(id))
            val restored = store.load(id)
            assertNotNull(restored)
            assertNull("Source mutation $index must be rejected", restored!!.sourceBitmap)
            assertEquals("Synthetic input", restored.historyEntries.single().label)
            assertTrue(File(folder(id), "session.qrs").isFile)
            assertFalse(folder(id).listFiles().orEmpty().any { it.name.startsWith("session.qrs.corrupt-") })
        }
    }

    @Test fun oversizedSourceDimensionsAreRejectedEvenWithMatchingHash() {
        val id = "source-dimension-limit"
        val store = storeSession(id)
        val png = File(folder(id), "generated.png")
        writePng(png, Bitmap.createBitmap(2601, 1, Bitmap.Config.ARGB_8888))
        val hash = digest(png.readBytes())
        assertTrue(png.renameTo(File(folder(id), "source-$hash.png")))
        File(folder(id), "source.ref").writeText(hash)
        assertNull(store.load(id)!!.sourceBitmap)
    }

    @Test fun sourceFilesWithoutTheCurrentPointerAreIgnored() {
        for (extension in listOf("png", "jpg")) {
            val id = "unsupported-source-$extension"
            val store = storeSession(id)
            val file = File(folder(id), "source.$extension")
            file.outputStream().use {
                val format = if (extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                assertTrue(bitmap().compress(format, 100, it))
            }
            File(folder(id), "source.sha256").writeText(digest(file.readBytes()))
            assertNull(store.load(id)!!.sourceBitmap)
            assertTrue(file.isFile)
        }
    }

    @Test fun onlyTheNoBackupDirectoryIsUsedForSessionStorage() {
        val id = "outside-session-root"
        val outside = File(context.filesDir, "qr_sessions/$id").apply { mkdirs() }
        folder(id).deleteRecursively()
        File(outside, "session.qrs").writeBytes(RecoverySessionCodec.encode(
            RecoverySessionCodec.Payload(id, 1, UiMode.SIMPLE, listOf(entry()), 0)))

        val store = RecoverySessionStore(context)
        assertNull(store.load(id))
        assertFalse(store.listRecent().any { it.id == id })
        assertFalse(folder(id).exists())
        assertTrue(File(outside, "session.qrs").isFile)

        store.save(id, UiMode.SIMPLE, listOf(entry()), 0)
        assertNotNull(store.load(id))
        store.delete(id)
        assertFalse(folder(id).exists())
        assertTrue(File(outside, "session.qrs").isFile)
        outside.deleteRecursively()
    }

    @Test fun quarantinedFilesAreNotRestoredOnStartup() {
        val id = "quarantined-session"
        storeSession(id)
        val primary = File(folder(id), "session.qrs")
        val quarantine = File(folder(id), "session.qrs.corrupt-1-decode")
        assertTrue(primary.renameTo(quarantine))
        val store = RecoverySessionStore(context)
        assertNull(store.load(id))
        assertFalse(store.listRecent().any { it.id == id })
        assertFalse(primary.exists())
        assertTrue(quarantine.isFile)
    }
}
