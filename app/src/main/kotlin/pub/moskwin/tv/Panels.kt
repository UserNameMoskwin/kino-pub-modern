package pub.moskwin.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Панель у края экрана поверх содержимого: боковое меню слева, списки выбора справа.
 * Закрывается кнопкой «назад» и стрелкой в сторону от края ([left] — панель слева,
 * закрывает «вправо»), ничего при этом не меняя.
 */
@Composable
internal fun SidePanel(
    left: Boolean,
    onClose: () -> Unit,
    width: Dp = 400.dp,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onClose)
    val closeKey = if (left) Key.DirectionRight else Key.DirectionLeft
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            Modifier.width(width).fillMaxHeight().background(Color(0xFF1C1C1C))
                .onPreviewKeyEvent { event ->
                    if (event.key != closeKey) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onClose()
                    true
                }
                .verticalScroll(rememberScrollState()).padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Edge, vertical = 8.dp))
            }
            content()
        }
    }
}

/** Строка панели. [focusFirst] — на ней фокус при открытии, [marked] — текущее значение. */
@Composable
internal fun PanelRow(
    title: String,
    value: String? = null,
    focusFirst: Boolean = false,
    marked: Boolean = false,
    onClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SettingRow(
        if (marked) "●  $title" else title, value, null, enabled = true,
        modifier = if (focusFirst) Modifier.focusRequester(focus) else Modifier,
        onClick = onClick,
    )
}

/** Список выбора: нажатие применяет и закрывает, фокус при открытии — на текущем значении. */
@Composable
internal fun <T> ChoicePanel(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onPick: (T) -> Unit,
    onClose: () -> Unit,
) {
    val focusIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    SidePanel(left = false, onClose = onClose, title = title) {
        options.forEachIndexed { index, (value, label) ->
            PanelRow(label, focusFirst = index == focusIndex, marked = value == current) {
                onClose()
                if (value != current) onPick(value)
            }
        }
    }
}
