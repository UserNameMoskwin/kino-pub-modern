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

@Serializable
data class Country(val id: Int = 0, val title: String = "")

@Serializable
data class Watching(
    /** -1 не смотрели, 0 начали, 1 просмотрели. */
    val status: Int = -1,
    val time: Double = 0.0,
)

/** Серия сериала или видео фильма внутри карточки. */
@Serializable
data class Video(
    val id: Int = 0,
    val number: Int = 0,
    val snumber: Int = 0,
    val title: String = "",
    val thumbnail: String? = null,
    val duration: Double = 0.0,
    val watching: Watching = Watching(),
) {
    val watched: Boolean get() = watching.status == 1
    val progress: Float
        get() = when {
            watched -> 1f
            duration <= 0 -> 0f
            else -> (watching.time / duration).coerceIn(0.0, 1.0).toFloat()
        }
}

@Serializable
data class Season(val number: Int = 0, val title: String = "", val episodes: List<Video> = emptyList())

@Serializable
data class ItemDuration(val average: Double = 0.0, val total: Double = 0.0)

/** Поля карточки, которых нет в [Item]. */
@Serializable
internal data class ItemExtra(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val countries: List<Country> = emptyList(),
    val duration: ItemDuration = ItemDuration(),
    val seasons: List<Season> = emptyList(),
    val videos: List<Video> = emptyList(),
)

/** Ответ `items/<id>`: у сериалов заполнены [seasons], у фильмов — [videos]. */
data class Details(
    val item: Item,
    val plot: String,
    val cast: String,
    val director: String,
    val countries: List<Country>,
    val duration: ItemDuration,
    val seasons: List<Season>,
    val videos: List<Video>,
) {
    val isSerial: Boolean get() = seasons.isNotEmpty()

    /** Все видео по порядку: серии по сезонам или части фильма. */
    val allVideos: List<Video>
        get() = if (isSerial) seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
        else videos.sortedBy { it.number }

    /**
     * С чего продолжать: недосмотренное последнее из начатых, после досмотренного —
     * следующее, если ничего не начато или всё досмотрено — первое.
     */
    fun resumePoint(): Video? {
        val all = allVideos
        val last = all.indexOfLast { it.watching.status != -1 }
        return when {
            last == -1 -> all.firstOrNull()
            !all[last].watched -> all[last]
            else -> all.getOrNull(last + 1) ?: all.first()
        }
    }
}
