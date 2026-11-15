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
    /** Когда серию смотрели в последний раз, unixtime. */
    val updated: Long = 0,
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

/** Переключатель из настроек устройства на стороне сервиса: SSL, 4K, HEVC, HDR, смешанный плейлист. */
@Serializable
data class DeviceToggle(val key: String, val label: String = "", val on: Boolean = false)

/** Текущее устройство и его настройки на стороне сервиса. */
@Serializable
data class Device(
    val id: Int,
    val title: String = "",
    val servers: List<ServerOption> = emptyList(),
    /** Типы потока: HTTP, HLS, HLS2, HLS4. */
    val streams: List<ServerOption> = emptyList(),
    val toggles: List<DeviceToggle> = emptyList(),
) {
    companion object {
        const val SERVER = "serverLocation"
        const val STREAM = "streamingType"
    }
}

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
    val audios: List<MediaAudio> = emptyList(),
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
     * С чего продолжать. С отметками [watch] — как на главном экране: серия, которую
     * смотрели последней, а если она досмотрена — следующая непросмотренная после неё.
     * Без них (фильмы, сбой запроса): начатая и не досмотренная серия (последняя из
     * таких), иначе следующая после последней просмотренной, иначе — первая.
     */
    fun resumePoint(watch: WatchItem? = null): Video? {
        val all = allVideos
        if (isSerial && watch != null) {
            val marks = watch.seasons.sortedBy { it.number }
                .flatMap { s -> s.episodes.sortedBy { it.number }.map { s.number to it } }
            val latest = marks.withIndex().filter { it.value.second.status != -1 }.maxByOrNull { it.value.second.updated }
            if (latest != null) {
                val target = if (latest.value.second.status == 0) latest.value
                else marks.drop(latest.index + 1).firstOrNull { it.second.status != 1 }
                val video = target?.let { (season, episode) ->
                    seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == episode.number }
                }
                return video ?: all.firstOrNull()
            }
        }
        all.lastOrNull { it.watching.status == 0 }?.let { return it }
        val last = all.indexOfLast { it.watched }
        return all.getOrNull(last + 1) ?: all.firstOrNull()
    }
}

/** Справочная пара «номер — название»: тип озвучки, автор перевода. */
@Serializable
data class NamedRef(val id: Int = 0, val title: String = "")

/** Дорожка озвучки. [index] совпадает с номером в названии дорожки HLS4-плейлиста («08. Оригинал (ENG)»). */
@Serializable
data class MediaAudio(
    val index: Int = 0,
    val codec: String = "",
    val channels: Int = 0,
    val lang: String = "",
    val type: NamedRef? = null,
    val author: NamedRef? = null,
) {
    /** У дорожек без типа он не указан сервисом; нерусская такая дорожка — оригинал. */
    val kind: AudioKind
        get() = when (type?.title) {
            "Оригинал" -> AudioKind.ORIGINAL
            "Дубляж" -> AudioKind.DUB
            null -> if (lang == "rus") AudioKind.VOICEOVER else AudioKind.ORIGINAL
            else -> AudioKind.VOICEOVER
        }

    /** По этой строке запоминается ручной выбор озвучки для сериала. */
    val signature: String get() = "$lang|${type?.id}|${author?.id}"

    val title: String
        get() = listOfNotNull(type?.title ?: "Оригинал", author?.title?.ifBlank { null }).joinToString(" · ") +
            " (${lang.uppercase()})" + (if (codec == "ac3") " AC3" else "") + (if (channels > 2) " 5.1" else "")
}

@Serializable
data class MediaSubtitle(val lang: String = "", val forced: Boolean = false, val url: String? = null) {
    val signature: String get() = "$lang|$forced"
    val title: String get() = lang.uppercase() + if (forced) " (только надписи)" else ""
}

@Serializable
data class MediaUrls(val http: String? = null, val hls: String? = null, val hls2: String? = null, val hls4: String? = null)

@Serializable
data class MediaFile(val quality: String = "", val h: Int = 0, val codec: String = "", val url: MediaUrls = MediaUrls())

/** Ответ `items/media-links`: ссылки действуют ограниченное время и зависят от выбранного сервера. */
@Serializable
data class MediaLinks(val files: List<MediaFile> = emptyList(), val subtitles: List<MediaSubtitle> = emptyList()) {
    /** Адрес потока для типа из настроек устройства («HLS4», «HLS2», «HLS», «HTTP»); по умолчанию HLS4. */
    fun streamUrl(streamType: String?): String? {
        val best = files.maxByOrNull { it.h }?.url ?: return null
        return when (streamType?.uppercase()) {
            "HTTP" -> best.http
            "HLS" -> best.hls
            "HLS2" -> best.hls2
            else -> null
        } ?: best.hls4 ?: best.hls2 ?: best.hls ?: best.http
    }
}

/** Выбранные дорожки: [subtitle] — номер в списке субтитров, null — выключены. */
data class TrackPick(val audio: MediaAudio?, val subtitle: Int?)

/**
 * Дорожки для запуска видео. Ручной выбор для этого сериала ([savedAudio], [savedSubtitle] —
 * подписи дорожек, для выключенных субтитров `off`) сильнее правила из docs/spec.md;
 * если такой дорожки в серии нет, действует правило. [preferAc3] — настройка плеера.
 */
fun pickTracks(
    audios: List<MediaAudio>,
    subtitles: List<MediaSubtitle>,
    kids: Boolean,
    preferAc3: Boolean = false,
    savedAudio: String? = null,
    savedSubtitle: String? = null,
): TrackPick {
    // Из одинаковых по смыслу дорожек первой идёт AC3 или, наоборот, обычная.
    val ordered = audios.sortedBy { (it.codec == "ac3") != preferAc3 }
    val rule = defaultTracks(
        ordered.map { AudioTrack(it.index, it.lang, it.kind) },
        subtitles.mapIndexed { i, s -> SubtitleTrack(i, s.lang, s.forced) },
        kids,
    )
    val saved = savedAudio?.let { signature -> ordered.firstOrNull { it.signature == signature } }
    val audio = saved ?: ordered.firstOrNull { it.index == rule.audio }
    val subtitle = when {
        saved == null || savedSubtitle == null -> rule.subtitle
        savedSubtitle == "off" -> null
        else -> subtitles.indexOfFirst { it.signature == savedSubtitle }.takeIf { it >= 0 }
    }
    return TrackPick(audio, subtitle)
}

/** Раздел каталога: фильмы, сериалы, концерты и так далее. [id] — значение параметра `type`. */
@Serializable
data class ContentType(val id: String, val title: String = "")

@Serializable
data class Subscription(
    val active: Boolean = false,
    @SerialName("end_time") val endTime: Long = 0,
    val days: Double = 0.0,
)

@Serializable
data class User(
    val username: String = "",
    @SerialName("reg_date") val regDate: Long = 0,
    val subscription: Subscription = Subscription(),
)

/** Страница каталога. [pages] — сколько страниц всего при таких фильтрах. */
data class ItemsPage(val items: List<Item>, val pages: Int, val total: Int)

/** Фильтры и сортировка каталога. Пустое значение фильтра — «все». */
data class CatalogQuery(
    val type: String = "movie",
    /** Поле сортировки с минусом для убывания: `created-`, `imdb_rating-`, `title`. */
    val sort: String = "created-",
    val genre: Int? = null,
    val country: Int? = null,
    /** Год или промежуток `2020-2023`. */
    val year: String? = null,
)
