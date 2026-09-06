package io.github.earthdiver.qrrepair

import android.content.Context
import android.content.res.Configuration
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

    /** Applies a user-selected locale without recreating the Activity and losing in-memory work. */
    @Suppress("DEPRECATION")
    fun applyTo(context: Context, language: AppLanguage) {
        save(context, language)
        current = language
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }
}

internal fun localizedText(japanese: String, english: String): String =
    if (AppLanguageManager.current == AppLanguage.JAPANESE) japanese else english
