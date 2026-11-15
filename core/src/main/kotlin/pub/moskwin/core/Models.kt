package pub.moskwin.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Ответы каталога. Формат сверен с живым сервером 2026-11-15 (см. docs/spec.md);
 * здесь только поля, которые нужны приложению.
 */

@Serializable
data class Genre(val id: Int, val title: String = "")

@Serializable
data class Posters(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    /** Горизонтальная обложка; в документации её нет, на живом сервере есть у всех. */
    val wide: String? = null,
)

@Serializable
data class Item(
    val id: Int,
    val type: String = "",
    val subtype: String = "",
    /** «Русское название / Original title». */
    val title: String = "",
    val year: Int? = null,
    val genres: List<Genre> = emptyList(),
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("imdb_votes") val imdbVotes: Int? = null,
    val posters: Posters = Posters(),
) {
    val name: String get() = title.substringBefore(" / ")
    val isCartoon: Boolean get() = genres.any { it.id == GENRE_CARTOON }
    val isAnime: Boolean get() = genres.any { it.id == GENRE_ANIME }

    companion object {
        const val GENRE_CARTOON = 23
        const val GENRE_ANIME = 25
    }
}

@Serializable
data class HistoryMedia(
    val id: Int = 0,
    /** Номер серии (у фильма — 1). */
    val number: Int = 0,
    /** Номер сезона; у фильмов 0. */
    val snumber: Int = 0,
    val title: String = "",
    val duration: Double = 0.0,
)

/** Строка истории: одна на серию или фильм. */
@Serializable
data class HistoryEntry(
    /** Секунда, на которой остановились. */
    val time: Double = 0.0,
    @SerialName("last_seen") val lastSeen: Long = 0,
    val item: Item,
    val media: HistoryMedia = HistoryMedia(),
)

@Serializable
data class WatchEpisode(
    val number: Int = 0,
    val title: String = "",
    val duration: Double = 0.0,
    val time: Double = 0.0,
    /** -1 не смотрели, 0 начали, 1 просмотрели. */
    val status: Int = -1,
)

@Serializable
data class WatchSeason(val number: Int = 0, val episodes: List<WatchEpisode> = emptyList())

/** Ответ `watching?id=`: у сериалов — сезоны, у фильмов их нет. */
@Serializable
data class WatchItem(val id: Int = 0, val seasons: List<WatchSeason> = emptyList())

@Serializable
data class BookmarkFolder(val id: Int, val title: String = "", val count: Int = 0)

@Serializable
data class ServerOption(val id: Int, val label: String = "", val selected: Int = 0)

/** Текущее устройство и список серверов из его настроек. */
@Serializable
data class Device(val id: Int, val servers: List<ServerOption> = emptyList())
