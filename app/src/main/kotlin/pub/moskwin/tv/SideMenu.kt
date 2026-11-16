package pub.moskwin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import pub.moskwin.core.CatalogQuery
import pub.moskwin.core.Device
import pub.moskwin.core.Item

/**
 * Разделы каталога в меню и сортировка, с которой каждый открывается: фильмы и сериалы —
 * с новинок, мультфильмы (жанр, а не раздел сервиса) — по рейтингу. Остальные разделы
 * повторяют список типов сервиса (`v1/types`).
 */
internal val SECTIONS = listOf(
    "Фильмы" to CatalogQuery(type = "movie", sort = "created-"),
    "Сериалы" to CatalogQuery(type = "serial", sort = "created-"),
    "Мультфильмы" to CatalogQuery(type = null, sort = "imdb_rating-", genre = Item.GENRE_CARTOON),
    "4K" to CatalogQuery(type = "4k"),
    "3D" to CatalogQuery(type = "3d"),
    "Концерты" to CatalogQuery(type = "concert"),
    "Док. фильмы" to CatalogQuery(type = "documovie"),
    "Док. сериалы" to CatalogQuery(type = "docuserial"),
    "ТВ шоу" to CatalogQuery(type = "tvshow"),
)

/** Ключи пунктов меню; у разделов каталога ключ — их название. */
internal const val MENU_HOME = "home"
internal const val MENU_SEARCH = "search"
internal const val MENU_PROFILE = "profile"
internal const val MENU_SETTINGS = "settings"
private const val MENU_SERVER = "server"

/**
 * Боковое меню: всегда на экране слева от содержимого. «Влево» с края содержимого
 * переводит фокус на пункт текущего раздела, «вправо» возвращает туда, где фокус был.
 * [current] — ключ открытого раздела. Сервер меняется отсюда же: пункт открывает список.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SideMenu(
    current: String,
    device: Device?,
    serverListOpen: Boolean,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onCatalog: (String, CatalogQuery) -> Unit,
    onServer: () -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    val rows = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = rows.getOrPut(key) { FocusRequester() }
    var serverWasOpen by remember { mutableStateOf(false) }
    // Список серверов закрылся — фокус возвращается на пункт, который его открыл.
    LaunchedEffect(serverListOpen) {
        if (serverListOpen) serverWasOpen = true
        else if (serverWasOpen) runCatching { requester(MENU_SERVER).requestFocus() }
    }
    Column(
        Modifier
            .width(MenuWidth).fillMaxHeight()
            .background(Color(0x59000000))
            .focusProperties {
                // Правила касаются только стрелок пульта: фокус, который экран ставит сам, они не трогают.
                enter = { if (it == FocusDirection.Left) rows[current] ?: FocusRequester.Default else FocusRequester.Default }
                // Стрелками фокус уходит из меню только вправо, в содержимое.
                exit = {
                    when (it) {
                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Left -> FocusRequester.Cancel
                        else -> FocusRequester.Default
                    }
                }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 20.dp),
    ) {
        MenuRow("Главная", current == MENU_HOME, requester(MENU_HOME), onHome)
        MenuRow("Поиск", current == MENU_SEARCH, requester(MENU_SEARCH), onSearch)
        Spacer(Modifier.height(14.dp))
        SECTIONS.forEach { (title, query) ->
            MenuRow(title, current == title, requester(title)) { onCatalog(title, query) }
        }
        Spacer(Modifier.height(14.dp))
        val server = device?.servers?.firstOrNull { it.selected == 1 }?.label
        MenuRow("Сервер: ${server ?: "…"}", false, requester(MENU_SERVER), onServer)
        MenuRow("Профиль", current == MENU_PROFILE, requester(MENU_PROFILE), onProfile)
        MenuRow("Настройки", current == MENU_SETTINGS, requester(MENU_SETTINGS), onSettings)
    }
}

internal val MenuWidth = 188.dp

@Composable
private fun MenuRow(title: String, marked: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RowShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .then(
                when {
                    focused -> Modifier.background(Color(0xEBFFFFFF))
                    marked -> Modifier.background(Color(0x29FFFFFF))
                    else -> Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                focused -> Color(0xFF101014)
                marked -> Foreground
                else -> Color(0xFFB4B4BE)
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
