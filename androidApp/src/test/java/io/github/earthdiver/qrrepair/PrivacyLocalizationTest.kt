package io.github.earthdiver.qrrepair

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class PrivacyLocalizationTest {
    @Test fun privacyBodyFollowsLanguageChangesWithoutRestarting() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            for (language in listOf(AppLanguage.JAPANESE, AppLanguage.ENGLISH, AppLanguage.JAPANESE)) {
                MainActivity::class.java.getDeclaredMethod("applyLanguage", AppLanguage::class.java)
                    .apply { isAccessible = true }.invoke(activity, language)
                MainActivity::class.java.getDeclaredMethod("showLicensesAndPrivacy")
                    .apply { isAccessible = true }.invoke(activity)
                shadowOf(Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                val body = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                    .single { it.text.contains("Apache License 2.0") }.text.toString()
                if (language == AppLanguage.JAPANESE) {
                    assertTrue(body.startsWith("プライバシー"))
                    assertTrue(body.contains("自動保存"))
                    assertTrue(body.contains("最大20件"))
                    assertTrue(body.contains("暗号化するものではありません"))
                } else {
                    assertTrue(body.startsWith("Privacy"))
                    assertTrue(body.contains("automatically saves"))
                    assertTrue(body.contains("20 saved sessions"))
                    assertTrue(body.contains("do not encrypt"))
                }
                // The bundled license remains available after the translated privacy notice.
                assertTrue(body.contains("Apache License"))
                dialog.dismiss()
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
