package pub.moskwin.tv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.moskwin.core.ContinueCard
import pub.moskwin.core.Device
import pub.moskwin.core.HomeData
import pub.moskwin.core.Item
import pub.moskwin.core.Kinopub
import pub.moskwin.core.LoginRequiredException
import pub.moskwin.core.loadHome
import java.io.File

/** Сохранённый главный экран: рисуется сразу при запуске, до ответа сети. */
private class HomeCache(context: Context) {
    private val file = File(context.filesDir, "home.json")
    fun load(): HomeData? = runCatching { HomeData.fromJson(file.readText()) }.getOrNull()
    fun save(data: HomeData) = runCatching { file.writeText(data.toJson()) }
}

/**
 * Главный экран. Данные обновляются в фоне при каждом показе и при смене [refreshKey]
 * (возврат в приложение, правка детского); экран при этом не очищается.
 */
@Composable
internal fun HomeScreen(
    kinopub: Kinopub,
    refreshKey: Int,
    notKids: Set<Int>,
    hideAnime: Boolean,
    focus: FocusMemory,
    onOpen: (Int) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onLoginRequired: () -> Unit,
) {
    val context = LocalContext.current
    val cache = remember { HomeCache(context.applicationContext) }
    var data by remember { mutableStateOf(cache.load()) }
    val fromCache = remember { data != null }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    remember { focus.pending = true }

    LaunchedEffect(refreshKey, reload, notKids, hideAnime) {
        try {
            // Разбор ответов (около мегабайта JSON) — не в потоке интерфейса.
            val fresh = Perf.timed("home") { withContext(Dispatchers.Default) { loadHome(kinopub, notKids, hideAnime) } }
            error = null
            // Те же данные, что на экране, не пересобирают ряды.
            if (fresh != data) {
                data = fresh
                withContext(Dispatchers.IO) { cache.save(fresh) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoginRequired()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val home = data
    if (home != null) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            Perf.homeShown(fromCache)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(kinopub, home?.device, focus, onSearch, onSettings, onChanged = { reload++ })
        when {
            home != null -> Rows(home, focus, onOpen)
            error != null -> Centered {
                Text("Не удалось загрузить: $error", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { error = null; reload++ }) { Text("Повторить") }
            }
            else -> Centered { Text("Загрузка…", style = MaterialTheme.typography.titleLarge, color = Dim) }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Поиск и текущий сервер. Нажатие на сервер раскрывает список, второе — выбирает. */
@Composable
private fun TopBar(
    kinopub: Kinopub,
    device: Device?,
    focus: FocusMemory,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // Выбор показывается сразу, не дожидаясь ответа сервера.
    var chosen by remember(device) { mutableStateOf(device?.servers?.firstOrNull { it.selected == 1 }?.id) }
    val currentFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = Edge, end = Edge, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("moskwin-pub", style = MaterialTheme.typography.titleMedium, color = Dim)
        Spacer(Modifier.weight(1f))
        Button(onClick = onSearch, modifier = Modifier.restoresFocus(focus, "top:search")) { Text("Поиск") }
        Button(onClick = onSettings, modifier = Modifier.restoresFocus(focus, "top:settings")) { Text("Настройки") }
        val servers = device?.servers.orEmpty()
        if (servers.isEmpty()) {
            // Список серверов ещё не получен.
        } else if (!expanded) {
            val label = servers.firstOrNull { it.id == chosen }?.label ?: "не выбран"
            // Кнопка списка, на которой стоял фокус, исчезла — возвращаем его сюда.
            LaunchedEffect(refocus) {
                if (refocus) {
                    runCatching { chipFocus.requestFocus() }
                    refocus = false
                }
            }
            Button(onClick = { expanded = true }, modifier = Modifier.focusRequester(chipFocus)) { Text("Сервер: $label") }
        } else {
            LaunchedEffect(Unit) { runCatching { currentFocus.requestFocus() } }
            servers.forEachIndexed { index, server ->
                val current = server.id == chosen || (chosen == null && index == 0)
                Button(
                    onClick = {
                        expanded = false
                        refocus = true
                        if (server.id != chosen) {
                            val previous = chosen
                            chosen = server.id
                            scope.launch {
                                try {
                                    kinopub.setServer(device!!.id, server.id)
                                    onChanged()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Сервис выбор не принял — показываем прежний сервер.
                                    chosen = previous
                                }
                            }
                        }
                    },
                    modifier = if (current) Modifier.focusRequester(currentFocus) else Modifier,
                ) { Text(if (server.id == chosen) "● ${server.label}" else server.label) }
            }
        }
    }
}

private class HomeRow(val title: String, val cards: List<ContinueCard>?, val items: List<Item>?)

@Composable
private fun Rows(home: HomeData, focus: FocusMemory, onOpen: (Int) -> Unit) {
    // Пустые ряды не показываются; порядок — из docs/spec.md.
    val rows = listOf(
        HomeRow("Продолжить просмотр", home.continueAdults, null),
        HomeRow("Нелли: продолжить", home.continueKids, null),
        HomeRow("Нелли: избранное", null, home.kidsFavorites),
        HomeRow("Новинки: фильмы", null, home.freshMovies),
        HomeRow("Новинки: сериалы", null, home.freshSerials),
    ).filter { !(it.cards ?: it.items).isNullOrEmpty() }

    // Рядов всего пять, поэтому по вертикали список не ленивый: ряды создаются один раз,
    // и прокрутка вниз только сдвигает готовое. С LazyColumn каждый новый ряд собирался
    // прямо во время прокрутки — на процессоре телевизора это давало рывки. Внутри ряда
    // по-прежнему существуют только видимые карточки.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 40.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            key(row.title) { Column(Modifier.padding(bottom = 20.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Edge, bottom = 4.dp),
                )
                CardRow {
                    row.cards?.let { cards ->
                        itemsIndexed(cards, key = { _, card -> card.item.id }, contentType = { _, _ -> "wide" }) { index, card ->
                            val item = card.item
                            MediaCard(
                                image = item.posters.wide ?: item.posters.big,
                                width = 256.dp, height = 144.dp,
                                title = item.name,
                                subtitle = if (card.season > 0) "Сезон ${card.season} · серия ${card.episode}"
                                else item.year?.toString().orEmpty(),
                                progress = card.progress,
                                modifier = Modifier.restoresFocus(focus, "${row.title}:${item.id}", rowIndex == 0 && index == 0),
                                onClick = { onOpen(item.id) },
                            )
                        }
                    }
                    row.items?.let { items ->
                        itemsIndexed(items, key = { _, item -> item.id }, contentType = { _, _ -> "poster" }) { index, item ->
                            PosterCard(
                                item,
                                Modifier.restoresFocus(focus, "${row.title}:${item.id}", rowIndex == 0 && index == 0),
                                onClick = { onOpen(item.id) },
                            )
                        }
                    }
                }
            } }
        }
    }
}

@Composable
internal fun CardRow(content: LazyListScope.() -> Unit) {
    // Отступы внутри ряда оставляют место увеличенной карточке в фокусе.
    LazyRow(
        contentPadding = PaddingValues(horizontal = Edge, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
