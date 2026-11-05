package io.github.earthdiver.qrrepair

import android.content.Context
import android.content.res.Configuration
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** Keep Activity and dialog fixtures independent of the language used by earlier tests. */
@Suppress("DEPRECATION")
internal fun <T> withTestAppLanguage(language: AppLanguage, action: () -> T): T {
    val context = RuntimeEnvironment.getApplication()
    val previousLanguage = AppLanguageManager.current
    val previousLocale = Locale.getDefault()
    val previousDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
    val preferences = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
    val previousPreference = preferences.getString("language", null)
    val previousConfiguration = Configuration(context.resources.configuration)
    try {
        AppLanguageManager.applyTo(context, language)
        return action()
    } finally {
        AppLanguageManager.applyTo(context, previousLanguage)
        preferences.edit().apply {
            if (previousPreference == null) remove("language") else putString("language", previousPreference)
        }.commit()
        context.resources.updateConfiguration(previousConfiguration, context.resources.displayMetrics)
        Locale.setDefault(previousLocale)
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplayLocale)
        Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale)
    }
}
