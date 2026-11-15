package io.github.earthdiver.qrrepair

import android.content.Context

/** App preference for subsequent image imports, independent of saved recovery sessions. */
internal object AutomaticRepairPreferences {
    private const val PREFS = "automatic_repair"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }
}
