package pub.moskwin.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** Карточка «Продолжить»: одна на фильм или сериал. [season] = 0 у фильмов. */
@Serializable
data class ContinueCard(
    val item: Item,
    val season: Int,
    val episode: Int,
    /** Доля просмотренного в серии или фильме, 0..1. */
    val progress: Float,
)

/** Всё, что показывает главный экран. Сохраняется на диск и рисуется до ответа сети. */
@Serializable
data class HomeData(
    val continueAdults: List<ContinueCard> = emptyList(),
    val continueKids: List<ContinueCard> = emptyList(),
    val kidsFavorites: List<Item> = emptyList(),
    val freshMovies: List<Item> = emptyList(),
    val freshSerials: List<Item> = emptyList(),
    val device: Device? = null,
) {
    fun toJson(): String = LenientJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): HomeData? =
            runCatching { LenientJson.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/** Папка закладок с детским. В спецификации она названа по имени ребёнка, в аккаунте сейчас — «мутики». */
val KIDS_FOLDER_TITLES = setOf("нелли", "мутики")

/** Имя ребёнка по умолчанию: им подписаны детские ряды и названа папка, которую приложение создаёт само. */
const val KIDS_FOLDER_NEW = "Нелли"

/** Детская папка: с именем ребёнка [name] или одним из прежних названий. */
private fun isKidsFolder(title: String, name: String): Boolean =
    title.trim().lowercase().let { it == name.trim().lowercase() || it in KIDS_FOLDER_TITLES }

suspend fun kidsFolder(api: Kinopub, name: String = KIDS_FOLDER_NEW): BookmarkFolder? =
    api.bookmarkFolders().firstOrNull { isKidsFolder(it.title, name) }

/** Избранное ребёнка — содержимое детской папки; папки ещё нет — список пуст. */
suspend fun kidsFavorites(api: Kinopub, name: String = KIDS_FOLDER_NEW): List<Item> =
    kidsFolder(api, name)?.let { api.bookmarkItems(it.id) }.orEmpty()

/** Лежит ли фильм или сериал в детской папке. */
suspend fun isInKidsFolder(api: Kinopub, itemId: Int, name: String = KIDS_FOLDER_NEW): Boolean =
    api.itemFolders(itemId).any { isKidsFolder(it.title, name) }

/** Кнопка «Для <имя>»: кладёт в детскую папку или убирает из неё. Папка создаётся при первом добавлении. */
suspend fun setInKidsFolder(api: Kinopub, itemId: Int, inFolder: Boolean, name: String = KIDS_FOLDER_NEW) {
    val folder = kidsFolder(api, name)
    when {
        inFolder -> api.addBookmark(itemId, (folder ?: api.createFolder(name.trim().ifEmpty { KIDS_FOLDER_NEW })).id)
        folder != null -> api.removeBookmark(itemId, folder.id)
    }
}

/** Досмотрено, если осталось меньше 7% — титры. */
private const val FINISHED = 0.93

private const val ROW_LIMIT = 30
private const val CONTINUE_LIMIT = 15
private const val HISTORY_PAGES = 4
private const val FRESH_PAGES = 3

/** Ниже этого числа голосов рейтинг IMDb случаен, и новинка в ряд не попадает. */
private const val MIN_IMDB_VOTES = 1000

/** Последняя строка истории на каждый фильм или сериал, свежие сверху. */
fun latestPerItem(history: List<HistoryEntry>): List<HistoryEntry> =
    history.sortedByDescending { it.lastSeen }.distinctBy { it.item.id }

private fun fraction(time: Double, duration: Double): Double =
    if (duration <= 0) 0.0 else (time / duration).coerceIn(0.0, 1.0)

/** Последняя серия досмотрена или только отмечена (время 0) — нужна следующая, а для неё отметки серий. */
fun needsNextEpisode(entry: HistoryEntry): Boolean =
    entry.media.snumber > 0 && fraction(entry.time, entry.media.duration).let { it == 0.0 || it >= FINISHED }

/**
 * Что показать в «Продолжить» для строки истории; null — смотреть больше нечего.
 *
 * Фильм: остаётся, пока не досмотрен. Сериал: недосмотренная серия, а если она
 * досмотрена — следующая непросмотренная по [watch]. Без [watch] (запрос не удался)
 * показывается последняя серия.
 */
fun continueCard(entry: HistoryEntry, watch: WatchItem?): ContinueCard? {
    val seen = fraction(entry.time, entry.media.duration)
    if (entry.media.snumber == 0) {
        return if (seen >= FINISHED) null else ContinueCard(entry.item, 0, entry.media.number, seen.toFloat())
    }
    val last = ContinueCard(entry.item, entry.media.snumber, entry.media.number, seen.toFloat())
    if (!needsNextEpisode(entry) || watch == null) return last
    for (season in watch.seasons.sortedBy { it.number }) {
        for (episode in season.episodes.sortedBy { it.number }) {
            val after = season.number > entry.media.snumber ||
                (season.number == entry.media.snumber && episode.number > entry.media.number)
            if (after && episode.status != 1) {
                return ContinueCard(
                    entry.item, season.number, episode.number,
                    fraction(episode.time, episode.duration).toFloat(),
                )
            }
        }
    }
    return null
}

/** Новинки: без детского и мультфильмов, с достаточным числом голосов, по рейтингу IMDb. */
fun rankFresh(items: List<Item>, isKids: (Item) -> Boolean): List<Item> =
    items.distinctBy { it.id }
        .filter { !isKids(it) && !(it.isCartoon && !it.isAnime) && (it.imdbVotes ?: 0) >= MIN_IMDB_VOTES }
        .sortedByDescending { it.imdbRating ?: 0.0 }
        .take(ROW_LIMIT)

/**
 * Собирает главный экран. [notKids] — исключения «Не детское», хранятся в приложении.
 * [kidsName] — имя ребёнка; null — режим ребёнка выключен: детских рядов нет, всё начатое
 * идёт в общий ряд «Продолжить». Второстепенные запросы (устройство, отметки серий) при
 * сбое пропускаются.
 */
suspend fun loadHome(
    api: Kinopub,
    notKids: Set<Int>,
    hideAnime: Boolean = false,
    kidsName: String? = KIDS_FOLDER_NEW,
): HomeData = coroutineScope {
    val history = (1..HISTORY_PAGES).map { async { api.history(it) } }
    val movies = (1..FRESH_PAGES).map { async { api.fresh("movie", it) } }
    val serials = (1..FRESH_PAGES).map { async { api.fresh("serial", it) } }
    val device = async { runCatching { api.device() }.getOrNull() }

    val folder = kidsName?.let { name -> api.bookmarkFolders().firstOrNull { isKidsFolder(it.title, name) } }
    val favorites = folder?.let { api.bookmarkItems(it.id) }.orEmpty()
    val folderIds = favorites.map { it.id }.toSet()
    val kids = { item: Item ->
        kidsName != null && isKids(item.isCartoon, item.isAnime, item.id in folderIds, item.id in notKids)
    }

    val (kidsEntries, adultEntries) = latestPerItem(history.awaitAll().flatten()).partition { kids(it.item) }
    val limit = Semaphore(4)
    suspend fun cards(entries: List<HistoryEntry>): List<ContinueCard> =
        // Запас: часть карточек отсеется как досмотренные.
        entries.take(CONTINUE_LIMIT * 2).map { entry ->
            async {
                val watch = if (needsNextEpisode(entry)) {
                    limit.withPermit { runCatching { api.watching(entry.item.id) }.getOrNull() }
                } else null
                continueCard(entry, watch)
            }
        }.awaitAll().filterNotNull().take(CONTINUE_LIMIT)

    HomeData(
        continueAdults = cards(adultEntries),
        continueKids = cards(kidsEntries),
        kidsFavorites = favorites,
        // «Отключение аниме» убирает его только из новинок: начатое остаётся в «Продолжить».
        freshMovies = rankFresh(movies.awaitAll().flatten()) { kids(it) || (hideAnime && it.isAnime) },
        freshSerials = rankFresh(serials.awaitAll().flatten()) { kids(it) || (hideAnime && it.isAnime) },
        device = device.await(),
    )
}
