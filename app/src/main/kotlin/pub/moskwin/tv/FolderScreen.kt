package pub.moskwin.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pub.moskwin.core.Item
import pub.moskwin.core.LoginRequiredException

/**
 * Раздел из папки закладок: избранное ребёнка или «Посмотрю позже». Открывается из меню.
 * [load] читает содержимое папки, [emptyNote] показывается, пока в ней пусто.
 * [refreshKey] растёт, когда папки правят в карточке.
 */
@Composable
internal fun FolderScreen(
    title: String,
    emptyNote: String,
    load: suspend () -> List<Item>,
    refreshKey: Int,
    focus: FocusMemory,
    onOpen: (Int) -> Unit,
    onLoginRequired: () -> Unit,
) {
    var items by remember { mutableStateOf<List<Item>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    remember { focus.pending = true }

    LaunchedEffect(refreshKey) {
        try {
            items = withContext(Dispatchers.Default) { load() }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoginRequired()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = Edge, bottom = 4.dp))
        val loaded = items
        val note = when {
            loaded == null && error != null -> "Не удалось загрузить: $error"
            loaded == null -> "Загрузка…"
            loaded.isEmpty() -> emptyNote
            else -> null
        }
        if (note != null) {
            Text(note, color = Dim, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Edge, top = 12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = Edge, end = 24.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(loaded.orEmpty(), key = { _, item -> item.id }) { index, item ->
                PosterCard(item, Modifier.restoresFocus(focus, "folder:${item.id}", index == 0), onClick = { onOpen(item.id) })
            }
        }
    }
}
