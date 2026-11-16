package pub.moskwin.core

/*
 * Правила из docs/spec.md, не зависящие от формата ответов сервера.
 * Перевод ответов API в эти типы появится после сверки с живым сервером.
 */

enum class AudioKind { ORIGINAL, DUB, VOICEOVER }

data class AudioTrack(val index: Int, val lang: String, val kind: AudioKind)

data class SubtitleTrack(val index: Int, val lang: String, val forced: Boolean = false)

/** Выбранные дорожки: [audio] и [subtitle] — значения `index`; `subtitle = null` — субтитры выключены. */
data class TrackChoice(val audio: Int?, val subtitle: Int?)

private const val RUS = "rus"

/**
 * Озвучка и субтитры по умолчанию.
 *
 * Детское: русский дубляж, без субтитров.
 * Остальное: оригинал + первые русские не forced субтитры; если таких субтитров
 * или оригинальной дорожки нет — русская озвучка без субтитров.
 */
fun defaultTracks(audios: List<AudioTrack>, subtitles: List<SubtitleTrack>, kids: Boolean): TrackChoice {
    val russian = audios.firstOrNull { it.lang == RUS && it.kind == AudioKind.DUB }
        ?: audios.firstOrNull { it.lang == RUS && it.kind != AudioKind.ORIGINAL }
        ?: audios.firstOrNull { it.lang == RUS }
    val fallback = (russian ?: audios.firstOrNull())?.index
    if (kids) return TrackChoice(fallback, null)

    val original = audios.firstOrNull { it.kind == AudioKind.ORIGINAL }
        ?: return TrackChoice(fallback, null)
    // Оригинал на русском: субтитры не нужны.
    if (original.lang == RUS) return TrackChoice(original.index, null)
    val subtitle = subtitles.firstOrNull { it.lang == RUS && !it.forced }
    if (subtitle != null) return TrackChoice(original.index, subtitle.index)
    return TrackChoice(russian?.index ?: original.index, null)
}

/**
 * Детское = жанр «Мультфильм» или папка закладок «Нелли».
 * Аниме и отмеченное кнопкой «Не детское» детским не считается;
 * папка «Нелли» сильнее обоих исключений.
 */
fun isKids(cartoonGenre: Boolean, anime: Boolean, inNellieFolder: Boolean, markedNotKids: Boolean): Boolean =
    inNellieFolder || (cartoonGenre && !anime && !markedNotKids)
