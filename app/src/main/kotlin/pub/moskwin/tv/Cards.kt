package pub.moskwin.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import pub.moskwin.core.Item

internal val Dim = Color(0xFF9A9A9A)
internal val Accent = Color(0xFFE50914)
internal val Edge = 48.dp
private val CardShape = RoundedCornerShape(14.dp)

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
    // Своя рамка фокуса вместо Card из tv-material: та на слабом процессоре телевизора
    // заметно дороже в сборке и перерисовке. Масштаб и рамка читаются на этапе
    // отрисовки, поэтому смена фокуса не пересобирает карточку.
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(120), label = "focus")
    Column(modifier.width(width)) {
        // Фокус принимает внешняя рамка неизменного размера, а увеличивается только
        // содержимое внутри неё. Иначе прокрутка, которая ставит карточку в фокусе на
        // опорную точку, видит меняющиеся во время анимации границы и дёргает весь экран.
        Box(
            Modifier
                .size(width, height)
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = null, indication = null, onClick = onClick),
        ) { Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = CardShape
                    clip = true
                }
                .background(Color(0xFF242424))
                .drawWithContent {
                    drawContent()
                    if (progress != null && progress > 0f) {
                        val bar = 4.dp.toPx()
                        drawRect(Color(0x99000000), Offset(0f, size.height - bar), Size(size.width, bar))
                        drawRect(Accent, Offset(0f, size.height - bar), Size(size.width * progress.coerceIn(0f, 1f), bar))
                    }
                    if (focused) {
                        val stroke = 3.dp.toPx()
                        drawRoundRect(
                            Color.White,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(14.dp.toPx() - stroke / 2),
                            style = Stroke(stroke),
                        )
                    }
                },
        ) {
            AsyncImage(
                model = image,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } }
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

/** Вертикальная обложка; под ней рейтинг (IMDb, без подписи) и год: «7,9  2025». */
@Composable
internal fun PosterCard(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val rating = item.imdbRating?.takeIf { it > 0 }?.let { "%.1f".format(it) }
    MediaCard(
        image = item.posters.big ?: item.posters.medium,
        width = 132.dp, height = 198.dp,
        title = item.name,
        subtitle = listOfNotNull(rating, item.year?.toString()).joinToString("  "),
        progress = null,
        modifier = modifier,
        onClick = onClick,
    )
}
