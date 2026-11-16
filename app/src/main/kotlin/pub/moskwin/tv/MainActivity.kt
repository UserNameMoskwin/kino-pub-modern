package pub.moskwin.tv

import android.content.Context
import androidx.compose.runtime.SideEffect
import coil.Coil
import coil.ImageLoader
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.moskwin.core.Device
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import pub.moskwin.core.ClientCredentials
import pub.moskwin.core.Kinopub

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kinopub = Kinopub(
            ClientCredentials(BuildConfig.CLIENT_ID, BuildConfig.CLIENT_SECRET),
            PrefsTokenStore(applicationContext),
        )
        Perf.install(this)
        kinopub.onRequest = { path, status, ms, size -> Perf.event("http", ms, "$status ${size / 1024}KB $path") }
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .eventListener(Perf.images)
                // Обложки непрозрачные: RGB565 вдвое экономит память и ускоряет отрисовку.
                .allowRgb565(true)
                // Декодирование тяжёлых обложек не должно занимать все ядра слабого процессора.
                .bitmapFactoryMaxParallelism(2)
                .build(),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Background)) {
                // Без Surface цвет текста по умолчанию чёрный — на тёмном фоне его не видно.
                CompositionLocalProvider(LocalContentColor provides Foreground) {
                    // На телевизоре включена служба специальных возможностей (лаунчер ловит
                    // ею кнопки пульта). При включённой службе Compose на каждое изменение
                    // пересчитывает описание и координаты всех элементов — по профилю это
                    // половина времени кадра. Описание экрана отключено целиком; цена —
                    // приложение не читается экранным диктором.
                    Box(
                        Modifier.fillMaxSize().glassBackdrop().clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        App(kinopub, resumes)
                    }
                }
            }
        }
    }

    /** Счётчик возвратов в приложение: по нему главный экран обновляет данные. */
    private var resumes by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

val Background = Color(0xFF0B0B10)
val Foreground = Color(0xFFEDEDED)

@Composable
private fun App(kinopub: Kinopub, resumes: Int) {
    var loggedIn by remember { mutableStateOf(kinopub.auth.isLoggedIn) }
    when {
        BuildConfig.CLIENT_SECRET.isEmpty() ->
            Text("Ключ клиента не задан: добавьте kinopub.clientId и kinopub.clientSecret в local.properties")
        !loggedIn -> LoginScreen(kinopub.auth, onLoggedIn = { loggedIn = true })
        else -> {
            val context = LocalContext.current
            val settings = remember { AppSettings(context.applicationContext) }
            LaunchedEffect(Unit) {
                // Название устройства в списке на сайте; сбой здесь входу не мешает.
                runCatching {
                    kinopub.notifyDevice(settings.deviceName, Build.MODEL, "Android ${Build.VERSION.RELEASE}")
                }
            }
            // onLoginRequired: сервер отклонил токены, они уже стёрты, нужен новый вход.
            Screens(kinopub, settings, resumes, onLoginRequired = { loggedIn = false })
        }
    }
}

private sealed interface Screen {
    class Search(val memory: SearchMemory = SearchMemory()) : Screen
    class Card(val itemId: Int) : Screen
    data object Settings : Screen
    class Player(val request: PlayRequest) : Screen
    class Catalog(val title: String, val memory: CatalogMemory) : Screen
    data object Profile : Screen
}

/** Главный экран и стопка открытых поверх него: поиск, карточки, настройки. «Назад» снимает верхний. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Screens(kinopub: Kinopub, settings: AppSettings, resumes: Int, onLoginRequired: () -> Unit) {
    val context = LocalContext.current
    val notKidsStore = remember { NotKidsStore(context.applicationContext) }
    var notKids by remember { mutableStateOf(notKidsStore.load()) }
    val stack = remember { mutableStateListOf<Screen>() }
    val homeFocus = remember { FocusMemory() }
    // Растёт, когда меняют детскую папку или настройки: главный экран пересобирает ряды.
    var kidsEdits by remember { mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val top = stack.lastOrNull()
    val screenName = when (top) {
        null -> "home"
        is Screen.Search -> "search"
        is Screen.Card -> "card"
        Screen.Settings -> "settings"
        is Screen.Player -> "player"
        is Screen.Catalog -> "catalog"
        Screen.Profile -> "profile"
    }
    SideEffect { Perf.screen = screenName }

    // Настройки устройства нужны меню ради сервера; перечитываются при возврате в приложение.
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<Device?>(null) }
    var serverListOpen by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) {
        withContext(Dispatchers.Default) { runCatching { kinopub.device() }.getOrNull() }?.let { device = it }
    }
    val kidsName = settings.kidsNameOrNull
    // Раздел из меню открывается вместо текущего, а не поверх: «назад» ведёт на главную.
    fun open(screen: Screen?) {
        stack.clear()
        if (screen != null) stack += screen
    }

    // Меню стоит рядом с разделами; карточка и плеер занимают весь экран.
    val menuShown = top !is Screen.Card && top !is Screen.Player
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (menuShown) {
                SideMenu(
                    current = when (top) {
                        null -> MENU_HOME
                        is Screen.Search -> MENU_SEARCH
                        is Screen.Catalog -> top.title
                        Screen.Profile -> MENU_PROFILE
                        Screen.Settings -> MENU_SETTINGS
                        else -> ""
                    },
                    device = device,
                    serverListOpen = serverListOpen,
                    onHome = { open(null) },
                    onSearch = {
                        // Новый поиск начинается с чистого экрана, без прокрутки прошлого.
                        states.removeState("search")
                        open(Screen.Search())
                    },
                    onCatalog = { title, query ->
                        states.removeState("catalog")
                        open(Screen.Catalog(title, CatalogMemory().apply { this.query = query }))
                    },
                    onServer = { if (device != null) serverListOpen = true },
                    onProfile = { open(Screen.Profile) },
                    onSettings = { open(Screen.Settings) },
                )
            }
            // Положение прокрутки каждого экрана сохраняется, пока он закрыт другим.
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    // Стрелками фокус уходит из содержимого только влево, в меню; при возврате встаёт
                    // туда, где был. Фокус, который экран ставит сам, правило не трогает.
                    .focusProperties {
                        exit = {
                            when (it) {
                                FocusDirection.Up, FocusDirection.Down, FocusDirection.Right -> FocusRequester.Cancel
                                FocusDirection.Left -> if (menuShown) FocusRequester.Default else FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusRestorer()
                    .focusGroup(),
            ) {
                when (top) {
                    null -> states.SaveableStateProvider("home") {
                        HomeScreen(
                            kinopub, refreshKey = resumes + kidsEdits, notKids = notKids, hideAnime = settings.hideAnime,
                            kidsName = kidsName,
                            focus = homeFocus,
                            onOpen = { stack += Screen.Card(it) },
                            onLoginRequired = onLoginRequired,
                        )
                    }
                    // Ключ должен ложиться в Bundle; поиск в стопке один.
                    is Screen.Search -> states.SaveableStateProvider("search") {
                        SearchScreen(kinopub, top.memory, onOpen = { stack += Screen.Card(it) })
                    }
                    is Screen.Card -> DetailsScreen(
                        kinopub, top.itemId, notKids, kidsName,
                        onNotKids = { id, marked ->
                            notKids = if (marked) notKids + id else notKids - id
                            notKidsStore.save(notKids)
                        },
                        onKidsChanged = { kidsEdits++ },
                        onPlay = { stack += Screen.Player(it) },
                        onLoginRequired = onLoginRequired,
                    )
                    // Другой раздел каталога — другой экран, а не тот же с прежним состоянием.
                    is Screen.Catalog -> key(top) {
                        states.SaveableStateProvider("catalog") {
                            CatalogScreen(
                                kinopub, top.memory, settings.hideAnime,
                                onOpen = { stack += Screen.Card(it) },
                                onLoginRequired = onLoginRequired,
                            )
                        }
                    }
                    Screen.Profile -> ProfileScreen(kinopub, settings, onLoggedOut = onLoginRequired)
                    is Screen.Player -> PlayerScreen(kinopub, settings, top.request, onExit = { stack.remove(top) })
                    Screen.Settings -> SettingsScreen(
                        kinopub, settings,
                        onChanged = { kidsEdits++ },
                        onLoggedOut = onLoginRequired,
                    )
                }
            }
        }
        val current = device
        if (serverListOpen && current != null) {
            ChoicePanel(
                "Сервер", current.servers.map { it.id to it.label },
                current.servers.firstOrNull { it.selected == 1 }?.id ?: -1,
                onPick = { id ->
                    scope.launch {
                        // Сбой переключения оставляет прежний сервер: настройки перечитываются в любом случае.
                        withContext(Dispatchers.Default) {
                            runCatching { kinopub.setServer(current.id, id) }
                            runCatching { kinopub.device() }.getOrNull()
                        }?.let { device = it }
                        kidsEdits++
                    }
                },
                onClose = { serverListOpen = false },
            )
        }
    }
}

/** Исключения «Не детское»: взрослые мультсериалы, которые не должны попадать в ряды Нелли. */
private class NotKidsStore(context: Context) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
    fun load(): Set<Int> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
    fun save(ids: Set<Int>) = prefs.edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()

    private companion object {
        const val KEY = "not_kids"
    }
}
