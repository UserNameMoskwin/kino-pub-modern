package pub.moskwin.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pub.moskwin.core.CatalogQuery
import pub.moskwin.core.ContentType
import pub.moskwin.core.Country
import pub.moskwin.core.Genre
import pub.moskwin.core.Item
import pub.moskwin.core.Kinopub
import pub.moskwin.core.LoginRequiredException

/** Состояние каталога: переживает уход в карточку и возврат из неё. */
internal class CatalogMemory {
    var query = CatalogQuery()
    var items: List<Item> = emptyList()
    var page = 0
    var pages = 1
    var total = 0
    var types: List<ContentType> = emptyList()
    var countries: List<Country> = emptyList()
    val genres = mutableMapOf<String, List<Genre>>()
    val focus = FocusMemory()
}

// Рейтинга Кинопоиска среди сортировок нет намеренно (docs/spec.md).
private val SORTS = listOf(
    "created-" to "Сначала новые",
    "updated-" to "Недавно обновлённые",
    "imdb_rating-" to "Рейтинг",
    "rating-" to "Рейтинг на сервисе",
    "views-" to "Число просмотров",
    "year-" to "Год выпуска",
    "title" to "Название",
)

private val YEARS: List<Pair<String?, String>> = listOf(
    null to "Любой год", "2026" to "2026", "2025" to "2025", "2024" to "2024", "2020-2023" to "2020–2023",
    "2010-2019" to "2010-е", "2000-2009" to "2000-е", "1990-1999" to "1990-е", "1900-1989" to "До 1990",
)

/** Раздел, из которого берутся жанры, когда каталог открыт без раздела (мультфильмы). */
private const val ALL_TYPES = "movie"

private enum class Filter { TYPE, SORT, GENRE, COUNTRY, YEAR }

/**
 * Каталог: сетка обложек, над ней кнопки раздела, сортировки и фильтров. Выбор в списке
 * применяется сразу; следующие страницы подгружаются при прокрутке вниз.
 */
@Composable
internal fun CatalogScreen(
    kinopub: Kinopub,
    memory: CatalogMemory,
    hideAnime: Boolean,
    onOpen: (Int) -> Unit,
    onLoginRequired: () -> Unit,
) {
    var query by remember { mutableStateOf(memory.query) }
    var items by remember { mutableStateOf(memory.items) }
    var status by remember { mutableStateOf(if (memory.items.isEmpty()) "Загрузка…" else "") }
    var open by remember { mutableStateOf<Filter?>(null) }
    var types by remember { mutableStateOf(memory.types) }
    var genres by remember { mutableStateOf(memory.genres[memory.query.type ?: ALL_TYPES].orEmpty()) }
    var countries by remember { mutableStateOf(memory.countries) }
    var loading by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val chips = remember { Filter.entries.associateWith { FocusRequester() } }
    val firstChip = chips.getValue(Filter.TYPE)
    var lastOpened by remember { mutableStateOf<Filter?>(null) }
    remember { memory.focus.pending = memory.focus.key != null }
    // Список выбора закрылся — фокус возвращается на кнопку, которая его открыла.
    LaunchedEffect(open) {
        if (open != null) lastOpened = open
        else lastOpened?.let { runCatching { chips.getValue(it).requestFocus() } }
    }

    /** Загружает следующую страницу; [reset] — фильтры поменялись, начинаем с первой. */
    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        try {
            val next = if (reset) 1 else memory.page + 1
            val page = withContext(Dispatchers.Default) { kinopub.items(query, next) }
            memory.items = if (reset) page.items else (memory.items + page.items).distinctBy { it.id }
            memory.page = next
            memory.pages = page.pages
            memory.total = page.total
            memory.query = query
            items = memory.items
            status = if (items.isEmpty()) "Ничего не нашлось — попробуйте убрать часть фильтров" else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoginRequired()
        } catch (e: Exception) {
            status = "Не удалось загрузить: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (memory.focus.key == null) runCatching { firstChip.requestFocus() }
        // Справочники для фильтров; сбой здесь оставляет фильтр пустым, каталог работает.
        if (types.isEmpty()) types = runCatching { withContext(Dispatchers.Default) { kinopub.types() } }.getOrDefault(emptyList()).also { memory.types = it }
        if (countries.isEmpty()) countries = runCatching { withContext(Dispatchers.Default) { kinopub.countries() } }.getOrDefault(emptyList()).also { memory.countries = it }
    }
    LaunchedEffect(query.type) {
        val type = query.type ?: ALL_TYPES
        genres = memory.genres[type]
            ?: runCatching { withContext(Dispatchers.Default) { kinopub.genres(type) } }.getOrDefault(emptyList())
                .also { if (it.isNotEmpty()) memory.genres[type] = it }
    }
    LaunchedEffect(query) {
        // Вернулись из карточки: список для этих фильтров уже есть.
        if (query == memory.query && memory.items.isNotEmpty()) return@LaunchedEffect
        items = emptyList()
        status = "Загрузка…"
        grid.scrollToItem(0)
        load(reset = true)
    }
    // Подгрузка следующей страницы, когда до конца списка остаётся два ряда.
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (items.isNotEmpty() && last >= items.size - 12 && memory.page < memory.pages) load(reset = false)
        }
    }

    val shown = if (hideAnime) items.filter { !it.isAnime } else items
    val typeLabel = if (query.type == null) "Все разделы" else types.firstOrNull { it.id == query.type }?.title ?: "…"
    val sortLabel = SORTS.firstOrNull { it.first == query.sort }?.second ?: query.sort
    val genreLabel = genres.firstOrNull { it.id == query.genre }?.title ?: "Все жанры"
    val countryLabel = countries.firstOrNull { it.id == query.country }?.title ?: "Все страны"
    val yearLabel = YEARS.firstOrNull { it.first == query.year }?.second ?: query.year.orEmpty()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Edge),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val labels = listOf(
                    Filter.TYPE to typeLabel, Filter.SORT to sortLabel, Filter.GENRE to genreLabel,
                    Filter.COUNTRY to countryLabel, Filter.YEAR to yearLabel,
                )
                items(labels.size) { i ->
                    val (filter, label) = labels[i]
                    GlassButton(onClick = { open = filter }, modifier = Modifier.focusRequester(chips.getValue(filter))) { Text(label) }
                }
            }
            Text(
                status.ifEmpty { "Найдено: ${memory.total}" },
                color = Dim, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = Edge, top = 12.dp, bottom = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                state = grid,
                contentPadding = PaddingValues(start = Edge, end = Edge, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(shown, key = { it.id }, contentType = { "poster" }) { item ->
                    PosterCard(item, Modifier.restoresFocus(memory.focus, "catalog:${item.id}"), onClick = { onOpen(item.id) })
                }
            }
        }

        // Смена любого фильтра забывает запомненный фокус: список будет другим.
        fun change(next: CatalogQuery) {
            memory.focus.key = null
            query = next
        }
        when (open) {
            Filter.TYPE -> ChoicePanel(
                "Раздел", listOf<Pair<String?, String>>(null to "Все разделы") + types.map { it.id to it.title },
                query.type, { change(query.copy(type = it, genre = null)) }, { open = null },
            )
            Filter.SORT -> ChoicePanel("Сортировка", SORTS, query.sort, { change(query.copy(sort = it)) }, { open = null })
            Filter.GENRE -> ChoicePanel(
                "Жанр", listOf<Pair<Int?, String>>(null to "Все жанры") + genres.map { it.id to it.title },
                query.genre, { change(query.copy(genre = it)) }, { open = null },
            )
            Filter.COUNTRY -> ChoicePanel(
                "Страна", listOf<Pair<Int?, String>>(null to "Все страны") + countries.map { it.id to it.title },
                query.country, { change(query.copy(country = it)) }, { open = null },
            )
            Filter.YEAR -> ChoicePanel("Год", YEARS, query.year, { change(query.copy(year = it)) }, { open = null })
            null -> {}
        }
    }
}
