package pub.moskwin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import pub.moskwin.core.Details
import pub.moskwin.core.Kinopub
import pub.moskwin.core.LoginRequiredException
import pub.moskwin.core.Video
import pub.moskwin.core.isInKidsFolder
import pub.moskwin.core.setInKidsFolder
import kotlin.math.roundToInt

/**
 * Карточка фильма или сериала: описание, «Смотреть», детские кнопки, сезоны и серии.
 * [onKidsChanged] сообщает, что состав детского изменился и главный экран надо пересобрать.
 */
@Composable
internal fun DetailsScreen(
    kinopub: Kinopub,
    itemId: Int,
    notKids: Set<Int>,
    onNotKids: (Int, Boolean) -> Unit,
    onKidsChanged: () -> Unit,
    onLoginRequired: () -> Unit,
) {
    var details by remember { mutableStateOf<Details?>(null) }
    var resume by remember { mutableStateOf<Video?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(itemId, attempt) {
        error = null
        try {
            Perf.timed("card", "id=$itemId") { withContext(Dispatchers.Default) {
                // Отметки серий нужны, чтобы «Продолжить» совпадало с главным экраном.
                val marks = async { runCatching { kinopub.watching(itemId) }.getOrNull() }
                val loaded = kinopub.details(itemId)
                resume = loaded.resumePoint(marks.await())
                details = loaded
            } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoginRequired()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val loaded = details
    when {
        loaded != null -> Loaded(kinopub, loaded, resume, notKids, onNotKids, onKidsChanged)
        error != null -> Centered {
            Text("Не удалось открыть: $error", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { attempt++ }) { Text("Повторить") }
        }
        else -> Centered { Text("Загрузка…", style = MaterialTheme.typography.titleLarge, color = Dim) }
    }
}

@Composable
private fun Loaded(
    kinopub: Kinopub,
    details: Details,
    resume: Video?,
    notKids: Set<Int>,
    onNotKids: (Int, Boolean) -> Unit,
    onKidsChanged: () -> Unit,
) {
    val item = details.item
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    var notice by remember { mutableStateOf<String?>(null) }
    // null — ещё не знаем, лежит ли в детской папке.
    var inKidsFolder by remember { mutableStateOf<Boolean?>(null) }
    var season by remember(details) {
        mutableIntStateOf(details.seasons.firstOrNull { resume in it.episodes }?.number ?: details.seasons.firstOrNull()?.number ?: 0)
    }

    LaunchedEffect(item.id) {
        runCatching { playFocus.requestFocus() }
        inKidsFolder = runCatching { isInKidsFolder(kinopub, item.id) }.getOrNull()
    }
    // Плеер — шаг 4.
    val play = { _: Video? -> notice = "Плеер появится на следующем шаге" }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.posters.wide ?: item.posters.big,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.35f),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Background, Background.copy(alpha = 0.2f)))))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = Edge).fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val original = item.title.substringAfter(" / ", "")
                if (original.isNotEmpty()) Text(original, style = MaterialTheme.typography.titleSmall, color = Dim, maxLines = 1)
                Text(facts(details), style = MaterialTheme.typography.bodyMedium, color = Dim)
                Text(details.plot, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (details.director.isNotBlank()) {
                    Text("Режиссёр: ${details.director}", style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (details.cast.isNotBlank()) {
                    Text("В ролях: ${details.cast}", style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Row(Modifier.padding(horizontal = Edge, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { play(resume) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text(playLabel(details, resume))
                }
                OutlinedButton(
                    onClick = {
                        val target = inKidsFolder != true
                        inKidsFolder = target
                        scope.launch {
                            try {
                                setInKidsFolder(kinopub, item.id, target)
                                onKidsChanged()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                inKidsFolder = !target
                                notice = "Не получилось изменить папку: ${e.message}"
                            }
                        }
                    },
                ) { Text(if (inKidsFolder == true) "✓ Для Нелли" else "Для Нелли") }
                // Исключение нужно только мультфильмам: остальное детским и так не считается.
                if (item.isCartoon && !item.isAnime) {
                    val marked = item.id in notKids
                    OutlinedButton(onClick = { onNotKids(item.id, !marked) }) {
                        Text(if (marked) "✓ Не детское" else "Не детское")
                    }
                }
            }
            notice?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Edge)) }

            if (details.isSerial) {
                if (details.seasons.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Edge),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(details.seasons.sortedBy { it.number }, key = { it.number }) { s ->
                            if (s.number == season) Button(onClick = {}) { Text("Сезон ${s.number}") }
                            else OutlinedButton(onClick = { season = s.number }) { Text("Сезон ${s.number}") }
                        }
                    }
                }
                val episodes = details.seasons.firstOrNull { it.number == season }?.episodes.orEmpty().sortedBy { it.number }
                Videos(episodes, play)
            } else if (details.videos.size > 1) {
                Videos(details.videos.sortedBy { it.number }, play)
            }
        }
    }
}

@Composable
private fun Videos(videos: List<Video>, onClick: (Video) -> Unit) {
    CardRow {
        items(videos, key = { it.number }) { video ->
            MediaCard(
                image = video.thumbnail,
                width = 208.dp, height = 117.dp,
                title = "${video.number}. ${video.title.substringBefore(" / ")}".trimEnd(' ', '.'),
                subtitle = listOfNotNull(if (video.watched) "✓ просмотрено" else null, minutes(video.duration)).joinToString(" · "),
                progress = video.progress,
                onClick = { onClick(video) },
            )
        }
    }
}

private fun minutes(seconds: Double): String? =
    (seconds / 60).roundToInt().takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} ч ${it % 60} мин" else "$it мин" }

private fun playLabel(details: Details, resume: Video?): String {
    val started = resume != null && resume.watching.status == 0 && resume.progress > 0f
    val verb = if (started) "Продолжить" else "Смотреть"
    if (!details.isSerial || resume == null) return "▶ $verb"
    val season = details.seasons.firstOrNull { resume in it.episodes }?.number ?: resume.snumber
    return "▶ $verb: сезон $season, серия ${resume.number}"
}

/** Год, страны, жанры, рейтинг IMDb, длительность — одной строкой. Рейтинга Кинопоиска нет намеренно. */
private fun facts(details: Details): String {
    val item = details.item
    val length = if (details.isSerial) {
        val episodes = details.seasons.sumOf { it.episodes.size }
        "сезонов: ${details.seasons.size}, серий: $episodes"
    } else minutes(details.duration.total)
    return listOfNotNull(
        item.year?.toString(),
        details.countries.joinToString(", ") { it.title }.ifEmpty { null },
        item.genres.joinToString(", ") { it.title }.ifEmpty { null },
        item.imdbRating?.takeIf { it > 0 }?.let { "IMDb %.1f".format(it) },
        length,
    ).joinToString(" · ")
}
