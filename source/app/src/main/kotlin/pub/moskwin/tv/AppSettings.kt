package pub.moskwin.tv

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Настройки, которые хранятся в приложении (остальные — на стороне сервиса, в настройках
 * устройства). Чтение — обычные свойства, запись сразу сохраняется.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = Stored(prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }
    private fun text(key: String, default: String) = Stored(prefs.getString(key, default) ?: default) { prefs.edit().putString(key, it).apply() }

    /** Название устройства в списке на сайте. */
    var deviceName by text("device_name", "KinoPub (${Build.MODEL})")

    // Интерфейс
    /** Убирает аниме из новинок. */
    var hideAnime by flag("hide_anime", false)

    // Режим ребёнка: детский ряд на главной, раздел в меню, детская кнопка в карточке, дубляж без субтитров в детском.
    var kidsMode by flag("kids_mode", true)
    var kidsName by text("kids_name", "Нелли")

    /** Имя ребёнка, если режим включён; иначе null. */
    val kidsNameOrNull: String? get() = kidsName.trim().takeIf { kidsMode && it.isNotEmpty() }

    // Плеер
    /** `best`, `1080`, `720` или `480`. */
    var quality by text("quality", QUALITIES.first().first)
    /** Как картинка вписана в экран: ключ из PICTURES в плеере. */
    var picture by text("picture", "fit")
    var askPlayer by flag("ask_player", false)
    var smallBuffer by flag("small_buffer", false)
    var preferAc3 by flag("prefer_ac3", false)
    var autoNext by flag("auto_next", true)
    var okPauses by flag("ok_pauses", true)
    var showStats by flag("show_stats", false)
    var systemCaptionStyle by flag("system_caption_style", false)

    companion object {
        val QUALITIES = listOf("best" to "Лучшее", "1080" to "1080p", "720" to "720p", "480" to "480p")
    }
}

/** Значение, которое видит Compose и которое пишется на диск при изменении. */
private class Stored<T>(initial: T, private val save: (T) -> Unit) {
    private var state by mutableStateOf(initial)

    operator fun getValue(owner: Any?, property: kotlin.reflect.KProperty<*>): T = state

    operator fun setValue(owner: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
        state = value
        save(value)
    }
}
