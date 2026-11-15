package pub.moskwin.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import pub.moskwin.core.Item
import pub.moskwin.core.Kinopub

/** Запрос, результаты и фокус поиска: переживают уход в карточку и возврат из неё. */
internal class SearchMemory {
    var query = ""
    var results: List<Item>? = null
    val focus = FocusMemory()
}

private const val HINT = "Введите не меньше трёх букв"

/** Поиск по названию, режиссёру и актёрам. Ищет сам через полсекунды после остановки ввода. */
@Composable
internal fun SearchScreen(kinopub: Kinopub, memory: SearchMemory, onOpen: (Int) -> Unit) {
    val focus = memory.focus
    var query by remember { mutableStateOf(memory.query) }
    var results by remember { mutableStateOf(memory.results) }
    var status by remember { mutableStateOf(if (memory.results == null) HINT else "") }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    remember { focus.pending = focus.key != null }
    // При возврате из карточки фокус должен вернуться на результат. Пока он не вернулся,
    // поле ввода фокус не принимает: иначе оно перехватит его и откроет клавиатуру.
    var restoring by remember { mutableStateOf(focus.key != null) }

    LaunchedEffect(Unit) {
        if (restoring) {
            delay(400)
            restoring = false
        } else {
            runCatching { fieldFocus.requestFocus() }
        }
    }
    LaunchedEffect(query) {
        val text = query.trim()
        // Вернулись из карточки: результаты этого запроса уже есть.
        if (text == memory.query.trim() && memory.results != null) return@LaunchedEffect
        if (text.length < 3) {
            results = null
            status = HINT
            memory.query = query
            memory.results = null
            return@LaunchedEffect
        }
        delay(500)
        status = "Ищу…"
        try {
            val found = kinopub.search(text)
            results = found
            status = if (found.isEmpty()) "Ничего не найдено" else ""
            memory.query = query
            memory.results = found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Поиск не удался: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(horizontal = Edge)
                .fillMaxWidth(0.5f)
                .background(Color(0xFF242424), RoundedCornerShape(8.dp))
                .border(BorderStroke(2.dp, if (fieldFocused) Color.White else Color.Transparent), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (query.isEmpty()) Text("Поиск", color = Dim, style = MaterialTheme.typography.titleMedium)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = Foreground),
                cursorBrush = SolidColor(Foreground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Кнопка поиска на клавиатуре убирает её и переводит фокус на результаты.
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .focusProperties { canFocus = !restoring }
                    // Поле ввода само забирает стрелки пульта: «вниз» уводим к результатам,
                    // нажатие в центре снова открывает клавиатуру.
                    .onPreviewKeyEvent { event ->
                        when {
                            event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown ->
                                focusManager.moveFocus(FocusDirection.Down)
                            event.key == Key.DirectionCenter && event.type == KeyEventType.KeyUp -> {
                                keyboard?.show()
                                true
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged {
                        fieldFocused = it.isFocused
                        if (it.isFocused) focus.key = null
                    },
            )
        }
        if (status.isNotEmpty()) {
            Text(status, color = Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Edge))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            contentPadding = PaddingValues(start = Edge, end = Edge, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(results.orEmpty(), key = { it.id }) { item ->
                PosterCard(item, Modifier.restoresFocus(focus, "search:${item.id}"), onClick = { onOpen(item.id) })
            }
        }
    }
}
