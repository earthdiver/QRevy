package io.github.earthdiver.qrrepair.session

import io.github.earthdiver.qrrepair.core.HistoryEventKind
import io.github.earthdiver.qrrepair.core.RecoveryHistoryEntry
import io.github.earthdiver.qrrepair.core.RecoverySnapshot
import io.github.earthdiver.qrrepair.core.UiMode
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoverySessionStoreIdentityTest {
    @Test fun payloadIdMustMatchContainingDirectory() {
        val context = RuntimeEnvironment.getApplication()
        val folderId = "stored-under-a"
        val payloadId = "claims-to-be-b"
        val folder = File(context.noBackupFilesDir, "qr_sessions/$folderId").apply {
            deleteRecursively()
            mkdirs()
        }
        val entry = RecoveryHistoryEntry(
            kind = HistoryEventKind.IMPORT,
            label = "identity",
            snapshot = RecoverySnapshot(null, null, null, -1),
        )
        File(folder, "session.qrs").writeBytes(
            RecoverySessionCodec.encode(
                RecoverySessionCodec.Payload(payloadId, 1L, UiMode.SIMPLE, listOf(entry), 0),
            ),
        )

        val loaded = RecoverySessionStore(context).load(folderId)

        assertNull(loaded)
        assertTrue(folder.listFiles().orEmpty().any { it.name.startsWith("session.qrs.corrupt-") })
    }
}
