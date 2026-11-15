package pub.moskwin.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
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

private val Dim = Color(0xFF9A9A9A)
private val Edge = 48.dp

/** Сохранённый главный экран: рисуется сразу при запуске, до ответа сети. */
private class HomeCache(context: Context) {
    private val file = File(context.filesDir, "home.json")
    fun load(): HomeData? = runCatching { HomeData.fromJson(file.readText()) }.getOrNull()
    fun save(data: HomeData) = runCatching { file.writeText(data.toJson()) }
}

/**
 * Главный экран. [refreshKey] меняется при каждом возврате в приложение —
 * данные обновляются в фоне, экран при этом не очищается.
 */
@Composable
fun HomeScreen(kinopub: Kinopub, refreshKey: Int, onLoginRequired: () -> Unit) {
    val context = LocalContext.current
    val cache = remember { HomeCache(context.applicationContext) }
    var data by remember { mutableStateOf(cache.load()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey, reload) {
        try {
            // Исключения «Не детское» появятся вместе с карточкой (шаг 3).
            val fresh = loadHome(kinopub, notKids = emptySet())
            data = fresh
            error = null
            withContext(Dispatchers.IO) { cache.save(fresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoginRequired()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val home = data
    Column(Modifier.fillMaxSize()) {
        TopBar(kinopub, home?.device, onChanged = { reload++ })
        when {
            home != null -> Rows(home)
            error != null -> Centered {
                Text("Не удалось загрузить: $error", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { error = null; reload++ }) { Text("Повторить") }
            }
            else -> Centered { Text("Загрузка…", style = MaterialTheme.typography.titleLarge, color = Dim) }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Название и текущий сервер. Нажатие на сервер раскрывает список, второе — выбирает. */
@Composable
private fun TopBar(kinopub: Kinopub, device: Device?, onChanged: () -> Unit) {
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

@Composable
private fun Rows(home: HomeData) {
    // Пустые ряды не показываются; порядок — из docs/spec.md.
    val rows = buildList<Pair<String, @Composable () -> Unit>> {
        if (home.continueAdults.isNotEmpty()) add("Продолжить просмотр" to { ContinueRow(home.continueAdults, first = true) })
        if (home.continueKids.isNotEmpty()) {
            add("Нелли: продолжить" to { ContinueRow(home.continueKids, first = home.continueAdults.isEmpty()) })
        }
        if (home.kidsFavorites.isNotEmpty()) add("Нелли: избранное" to { PosterRow(home.kidsFavorites) })
        if (home.freshMovies.isNotEmpty()) add("Новинки: фильмы" to { PosterRow(home.freshMovies) })
        if (home.freshSerials.isNotEmpty()) add("Новинки: сериалы" to { PosterRow(home.freshSerials) })
    }

    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)) {
        items(rows, key = { it.first }) { (title, content) ->
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Edge, bottom = 4.dp),
                )
                content()
            }
        }
    }
}

@Composable
private fun CardRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // Отступы внутри ряда оставляют место увеличенной карточке в фокусе.
    LazyRow(
        contentPadding = PaddingValues(horizontal = Edge, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun ContinueRow(cards: List<ContinueCard>, first: Boolean) {
    val focus = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    CardRow {
        items(cards.size, key = { cards[it].item.id }) { index ->
            val card = cards[index]
            val item = card.item
            MediaCard(
                image = item.posters.wide ?: item.posters.big,
                width = 256.dp, height = 144.dp,
                title = item.name,
                subtitle = if (card.season > 0) "Сезон ${card.season} · серия ${card.episode}" else item.year?.toString().orEmpty(),
                progress = card.progress,
                modifier = if (first && index == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

@Composable
private fun PosterRow(items: List<Item>) {
    CardRow {
        items(items, key = { it.id }) { item ->
            val rating = item.imdbRating?.takeIf { it > 0 }?.let { "IMDb %.1f".format(it) }
            MediaCard(
                image = item.posters.big ?: item.posters.medium,
                width = 132.dp, height = 198.dp,
                title = item.name,
                subtitle = listOfNotNull(rating, item.year?.toString()).joinToString(" · "),
                progress = null,
            )
        }
    }
}

/** Карточка: обложка под размер, подпись, для «Продолжить» — полоска прогресса. */
@Composable
private fun MediaCard(
    image: String?,
    width: Dp,
    height: Dp,
    title: String,
    subtitle: String,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = { /* Карточка фильма — шаг 3. */ },
            modifier = Modifier.size(width, height),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF242424))) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color(0xFFE50914)))
                    }
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
