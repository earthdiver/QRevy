package io.github.earthdiver.qrrepair
// Presentation only; production recovery and sampling algorithms are compiled unchanged.
internal fun localizedText(japanese: String, english: String): String = english
enum class AppLanguage { JAPANESE, ENGLISH }
object AppLanguageManager { val current = AppLanguage.ENGLISH }
