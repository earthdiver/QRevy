package io.github.earthdiver.qrrepair
internal fun localizedText(japanese: String, english: String): String = if (AppLanguageManager.isJapanese) japanese else english
enum class AppLanguage { JAPANESE, ENGLISH }
object AppLanguageManager {
    var current = AppLanguage.JAPANESE
    val isJapanese: Boolean get() = current == AppLanguage.JAPANESE
}
