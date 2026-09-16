package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.RepairReasonKind
import io.github.earthdiver.qrrepair.ui.QrResultInspectorView
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FormatInspectorColorTest {
    @Test fun formatInformationHasItsOwnColor() {
        val view = QrResultInspectorView(RuntimeEnvironment.getApplication())
        val method = QrResultInspectorView::class.java.getDeclaredMethod("colorFor", RepairReasonKind::class.java)
            .apply { isAccessible = true }
        val format = method.invoke(view, RepairReasonKind.FORMAT_INFORMATION)
        for (kind in listOf(RepairReasonKind.FINDER_PATTERN, RepairReasonKind.VERSION_INFORMATION,
            RepairReasonKind.RS_ERASURE_RECOVERY, RepairReasonKind.USER_OVERRIDE, RepairReasonKind.PAYLOAD_STRUCTURE)) {
            assertNotEquals(method.invoke(view, kind), format)
        }
    }
}
