package io.github.earthdiver.qrrepair

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

internal enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    JAPANESE("ja"),
}

internal object AppLanguageManager {
    private const val PREFS = "app_language"
    private const val KEY_LANGUAGE = "language"
    @Volatile internal var current: AppLanguage = AppLanguage.JAPANESE
        private set

    val isJapanese: Boolean get() = current == AppLanguage.JAPANESE

    fun selected(context: Context): AppLanguage {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return when (saved) {
            AppLanguage.JAPANESE.tag -> AppLanguage.JAPANESE
            AppLanguage.ENGLISH.tag -> AppLanguage.ENGLISH
            else -> if (context.resources.configuration.locales[0]?.language == AppLanguage.JAPANESE.tag) {
                AppLanguage.JAPANESE
            } else {
                AppLanguage.ENGLISH
            }
        }
    }

    fun wrap(context: Context): Context {
        current = selected(context)
        val locale = Locale.forLanguageTag(current.tag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }

    /** Keeps Android string resources aligned with the same language used by dynamic text. */
    @Suppress("DEPRECATION")
    fun localizedResources(resources: Resources): Resources {
        if (resources.configuration.locales[0]?.language != current.tag) {
            val configuration = Configuration(resources.configuration).apply {
                val locale = Locale.forLanguageTag(current.tag)
                setLocale(locale)
                setLayoutDirection(locale)
            }
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        return resources
    }

    /** Applies a user-selected locale without recreating the Activity and losing in-memory work. */
    fun applyTo(context: Context, language: AppLanguage) {
        save(context, language)
        current = language
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        localizedResources(context.resources)
    }
}

internal fun localizedText(japanese: String, english: String): String =
    if (AppLanguageManager.isJapanese) japanese else english
