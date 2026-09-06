package io.github.earthdiver.qrrepair

import android.content.Context

internal enum class AppStyle(val theme: Int, val label: Int) {
    CLASSIC(R.style.AppTheme, R.string.style_classic),
    MODERN(R.style.ModernAppTheme, R.string.style_modern),
}

internal object AppStylePreferences {
    const val EXTRA_ACTIVE_STYLE = "io.github.earthdiver.qrrepair.ACTIVE_STYLE"
    private const val PREFS = "app_appearance"
    private const val KEY_STYLE = "style"

    fun selected(context: Context): AppStyle =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STYLE, null))

    fun parse(value: String?): AppStyle = AppStyle.entries.firstOrNull { it.name == value } ?: AppStyle.CLASSIC

    fun save(context: Context, style: AppStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, style.name).apply()
    }

    fun isModern(context: Context): Boolean {
        val value = android.util.TypedValue()
        return context.theme.resolveAttribute(R.attr.modernStyle, value, true) && value.data != 0
    }
}
