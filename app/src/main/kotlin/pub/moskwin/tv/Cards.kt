package pub.moskwin.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import pub.moskwin.core.Item

internal val Dim = Color(0xFF9A9A9A)
internal val Accent = Color(0xFFE50914)
internal val Edge = 48.dp

/**
 * Где стоял фокус на экране, пока тот был закрыт другим. [pending] взводится при
 * возврате на экран и гасится, когда фокус восстановлен.
 */
internal class FocusMemory {
    var key: String? = null
    var pending = false
}

/**
 * Запоминает фокус на элементе [key] и возвращает его при возврате на экран.
 * [isDefault] — элемент, с которого экран начинается, пока запоминать нечего.
 */
@Composable
internal fun Modifier.restoresFocus(memory: FocusMemory, key: String, isDefault: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    val restore = remember { memory.pending && (memory.key == key || (memory.key == null && isDefault)) }
    if (restore) {
        LaunchedEffect(Unit) {
            runCatching { requester.requestFocus() }
            memory.pending = false
        }
    }
    return this
        .then(if (restore) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { if (it.isFocused) memory.key = key }
}

/** Карточка: обложка под размер, подпись, при [progress] — полоска просмотра. */
@Composable
internal fun MediaCard(
    image: String?,
    width: Dp,
    height: Dp,
    title: String,
    subtitle: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(width, height),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White))),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF242424))) {
                AsyncImage(
                    model = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (progress != null && progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Accent))
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

/** Вертикальная обложка с рейтингом IMDb и годом. */
@Composable
internal fun PosterCard(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val rating = item.imdbRating?.takeIf { it > 0 }?.let { "IMDb %.1f".format(it) }
    MediaCard(
        image = item.posters.big ?: item.posters.medium,
        width = 132.dp, height = 198.dp,
        title = item.name,
        subtitle = listOfNotNull(rating, item.year?.toString()).joinToString(" · "),
        progress = null,
        modifier = modifier,
        onClick = onClick,
    )
}
