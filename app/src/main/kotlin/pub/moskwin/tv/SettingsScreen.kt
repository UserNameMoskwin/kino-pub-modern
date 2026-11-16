package pub.moskwin.tv

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.Coil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.moskwin.core.Device
import pub.moskwin.core.Kinopub
import pub.moskwin.core.LoginRequiredException
import pub.moskwin.core.measureSpeed
import pub.moskwin.core.AppVersion
import pub.moskwin.core.mediaSegments
import kotlin.math.roundToInt

private val RowFocused = Color(0xFF333333)
private val Panel = Color(0xFF2A2A2A)

/** Окно поверх списка: выбор из вариантов, ввод текста или подтверждение. */
private sealed interface Dialog {
    class Choice(val title: String, val options: List<Pair<String, String>>, val current: String?, val onPick: (String) -> Unit) : Dialog
    class Input(val title: String, val initial: String, val onSave: (String) -> Unit) : Dialog
    class Confirm(val title: String, val text: String, val action: String, val onConfirm: () -> Unit) : Dialog
    /** Новая версия: что в ней изменилось и кнопка установки. */
    class Update(val version: AppVersion, val onInstall: () -> Unit) : Dialog
}

/**
 * Настройки — по образцу официального приложения. Настройки устройства живут на стороне
 * сервиса и меняются сразу; остальное хранится в [AppSettings].
 * [onChanged] — изменилось то, от чего зависит главный экран.
 */
@Composable
internal fun SettingsScreen(
    kinopub: Kinopub,
    settings: AppSettings,
    updater: Updater,
    onChanged: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf<Device?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var lastRow by remember { mutableStateOf("name") }
    // Вышло обновление — экран открывается на нём.
    val firstRow = if (updater.available != null) "update-new" else "name"
    /** Окно новой версии: список изменений и установка. */
    // Настройки открыли — версия проверяется заново: с запуска приложения могла выйти новая.
    LaunchedEffect(Unit) { updater.check() }
    fun showUpdate(version: AppVersion) {
        dialog = Dialog.Update(version) {
            scope.launch { updater.install() }
        }
    }
    var firstFocus by remember { mutableStateOf(true) }

    suspend fun reloadDevice() {
        device = withContext(Dispatchers.Default) { kinopub.device() }
    }

    /** Действие с сервисом: при ошибке показывает её строкой внизу и перечитывает настройки. */
    fun service(done: String? = null, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                reloadDevice()
                status = done
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginRequiredException) {
                onLoggedOut()
            } catch (e: Exception) {
                status = "Не получилось: ${e.message ?: e.javaClass.simpleName}"
                runCatching { reloadDevice() }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            reloadDevice()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoginRequiredException) {
            onLoggedOut()
        } catch (e: Exception) {
            status = "Настройки устройства не загрузились: ${e.message}"
        }
    }
    // После закрытия окна фокус возвращается на строку, из которой его открыли.
    LaunchedEffect(dialog) {
        if (dialog == null && !firstFocus) runCatching { rowFocus[lastRow]?.requestFocus() }
    }
    BackHandler(enabled = dialog != null) { dialog = null }

    // [available] = false — пункт не работает на этом устройстве или не сделан: он серый и фокус не берёт.
    @Composable
    fun row(key: String, title: String, value: String? = null, checked: Boolean? = null, available: Boolean = true, mark: Boolean = false, onClick: () -> Unit) {
        val requester = remember(key) { FocusRequester().also { rowFocus[key] = it } }
        if (key == firstRow && firstFocus) {
            LaunchedEffect(Unit) {
                runCatching { requester.requestFocus() }
                firstFocus = false
            }
        }
        SettingRow(
            title, value, checked,
            hint = HINTS[key] ?: if (key.startsWith("toggle:")) toggleHint(title) else null,
            enabled = dialog == null && available,
            dimmed = !available,
            mark = mark,
            modifier = Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) lastRow = key },
            onClick = onClick,
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        // Заголовок и строка состояния не прокручиваются: итог действия виден, где бы ни стоял фокус.
        Row(Modifier.fillMaxWidth().padding(start = Edge, end = Edge, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall)
            Text(
                status.orEmpty(), color = Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                modifier = Modifier.weight(1f).padding(start = 24.dp),
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {

            // Состояние обновления всегда первой строкой: есть новая версия — она с красной
            // точкой и ведёт к установке; нет — строка говорит, что версия актуальная, и по
            // нажатию проверяет ещё раз. Ход проверки и загрузки и их ошибки пишутся в ней же.
            Header("Обновление")
            val newer = updater.available
            if (newer != null) {
                row(
                    "update-new", "Доступна версия ${newer.versionName}",
                    updater.progress?.let { "Загрузка ${(it * 100).roundToInt()} %" }
                        ?: updater.problem?.let { "Не скачалось: $it. Нажмите, чтобы повторить" }
                        ?: "Нажмите, чтобы посмотреть изменения и установить",
                    mark = true,
                ) { showUpdate(newer) }
            } else {
                row(
                    "update-new", "У вас актуальная версия ${BuildConfig.VERSION_NAME}",
                    when {
                        updater.checking -> "Проверяю…"
                        updater.checkProblem != null -> "Проверить не удалось: ${updater.checkProblem}. Нажмите, чтобы повторить"
                        else -> "Нажмите, чтобы проверить ещё раз"
                    },
                ) { scope.launch { updater.check() } }
            }

            Header("Настройки устройства")
            row("name", "Название устройства", settings.deviceName) {
                dialog = Dialog.Input("Название устройства", settings.deviceName) { name ->
                    settings.deviceName = name
                    service { kinopub.notifyDevice(name, android.os.Build.MODEL, "Android ${android.os.Build.VERSION.RELEASE}") }
                }
            }
            val loaded = device
            row("speed", "Запустить тест скорости") {
                status = "Меряю скорость…"
                scope.launch {
                    status = try {
                        // Файлы для замера — фрагменты трейлеров свежих фильмов: они лежат на видеосервере.
                        val urls = withContext(Dispatchers.Default) {
                            kinopub.fresh("movie").take(8)
                                .mapNotNull { runCatching { kinopub.trailerUrl(it.id) }.getOrNull() }
                                .take(3)
                                .flatMap { runCatching { mediaSegments(it) }.getOrDefault(emptyList()) }
                        }
                        val result = measureSpeed(urls)
                        Perf.event("speed", 0, "files=${result.files} bytes=${result.bytes} mbps=${result.mbps} problem=${result.problem}")
                        when {
                            result.mbps != null -> "Скорость загрузки с видеосервера: %.0f Мбит/с".format(result.mbps)
                            result.files == 0 -> "Не нашлось файлов для замера"
                            else -> "Замер не удался: ${result.problem ?: "файлы слишком малы"}"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Тест не удался: ${e.message}"
                    }
                }
            }
            row("server", "Сервер", loaded?.servers?.firstOrNull { it.selected == 1 }?.label ?: "…") {
                val d = loaded ?: return@row
                dialog = Dialog.Choice("Сервер", d.servers.map { "${it.id}" to it.label }, d.servers.firstOrNull { it.selected == 1 }?.id?.toString()) {
                    service { kinopub.setServer(d.id, it.toInt()) }
                }
            }
            loaded?.toggles?.forEach { toggle ->
                row("toggle:${toggle.key}", toggle.label.ifEmpty { toggle.key }, checked = toggle.on) {
                    service { kinopub.setDeviceSetting(loaded.id, toggle.key, if (toggle.on) "0" else "1") }
                }
            }

            Header("Настройки интерфейса")
            row("anime", "Отключение аниме", checked = settings.hideAnime) {
                settings.hideAnime = !settings.hideAnime
                onChanged()
            }

            Header("Режим ребёнка")
            row(
                "kids", "Режим ребёнка",
                checked = settings.kidsMode,
            ) {
                settings.kidsMode = !settings.kidsMode
                onChanged()
            }
            row("kids-name", "Имя ребёнка", settings.kidsName, available = settings.kidsMode) {
                dialog = Dialog.Input("Имя ребёнка", settings.kidsName) { name ->
                    settings.kidsName = name
                    onChanged()
                }
            }

            Header("Настройки плеера")
            row("player", "Выбор плеера", if (settings.askPlayer) "Спрашивать при запуске" else "Встроенный") {
                dialog = Dialog.Choice(
                    "Выберите плеер", listOf("builtin" to "Встроенный", "ask" to "Спрашивать при запуске"),
                    if (settings.askPlayer) "ask" else "builtin",
                ) { settings.askPlayer = it == "ask" }
            }
            row("quality", "Качество видео", AppSettings.QUALITIES.firstOrNull { it.first == settings.quality }?.second) {
                dialog = Dialog.Choice("Выберите качество видео", AppSettings.QUALITIES, settings.quality) { settings.quality = it }
            }
            row("stream", "Тип потока", loaded?.streams?.firstOrNull { it.selected == 1 }?.label ?: "…") {
                val d = loaded ?: return@row
                dialog = Dialog.Choice("Тип потока", d.streams.map { "${it.id}" to it.label }, d.streams.firstOrNull { it.selected == 1 }?.id?.toString()) {
                    service { kinopub.setDeviceSetting(d.id, Device.STREAM, it) }
                }
            }
            row("buffer", "Небольшой буфер (для слабых устройств)", checked = settings.smallBuffer) { settings.smallBuffer = !settings.smallBuffer }
            row("afr", "Автофреймрейт", "Не сделано", available = false) {}
            row("ac3", "Автовыбор ac3 аудиодорожки", checked = settings.preferAc3) { settings.preferAc3 = !settings.preferAc3 }
            row("next", "Автозапуск следующей серии", checked = settings.autoNext) { settings.autoNext = !settings.autoNext }
            row("ok", "Стоп/старт по OK", checked = settings.okPauses) { settings.okPauses = !settings.okPauses }
            row("stats", "Всегда показывать статистику", checked = settings.showStats) { settings.showStats = !settings.showStats }
            row("captions", "Настройка вида субтитров системой", checked = settings.systemCaptionStyle) {
                settings.systemCaptionStyle = !settings.systemCaptionStyle
            }
            val captionSettings = remember { Intent(Settings.ACTION_CAPTIONING_SETTINGS) }
            val hasCaptionSettings = remember { captionSettings.resolveActivity(context.packageManager) != null }
            row(
                "captions-system", "Открыть системные настройки субтитров",
                if (hasCaptionSettings) null else "На этом телевизоре их нет", available = hasCaptionSettings,
            ) {
                runCatching { context.startActivity(captionSettings) }
                    .onFailure { status = "Системные настройки субтитров не открылись" }
            }

            Header("О приложении")
            row("version", "Версия", BuildConfig.VERSION_NAME) {}
            row("posters", "Удалить закэшированные постеры") {
                val loader = Coil.imageLoader(context)
                loader.memoryCache?.clear()
                scope.launch(Dispatchers.IO) { loader.diskCache?.clear() }
                status = "Кэш обложек очищен"
            }
            row("id", "ID устройства", loaded?.id?.toString() ?: "…") {}

        }
        }

        dialog?.let { DialogPanel(it, onClose = { dialog = null }) }
    }
}

/** Что делает каждая настройка — мелкой строкой под её названием. Ключи — как у строк экрана. */
private val HINTS = mapOf(
    "name" to "Под этим именем телевизор виден в списке устройств на сайте kino.pub",
    "speed" to "Замеряет скорость загрузки с выбранного видеосервера",
    "server" to "Откуда идёт видео. Если оно тормозит, попробуйте другой сервер",
    "anime" to "Убирает аниме из рядов новинок на главном экране",
    "kids" to "Детский ряд на главной, раздел ребёнка в меню и детская кнопка в карточке; мультфильмы идут в дубляже без субтитров",
    "kids-name" to "Так называются детский ряд, раздел в меню и папка закладок в аккаунте",
    "player" to "Чем открывать видео: встроенным плеером или каждый раз выбирать приложение",
    "quality" to "Потолок качества. «Лучшее» — максимум, который позволяет сеть",
    "stream" to "Способ передачи видео. Менять стоит, только если видео не запускается",
    "buffer" to "Меньше памяти и быстрее старт, но видео чаще останавливается при слабой сети",
    "afr" to "Подстройка частоты кадров телевизора под фильм",
    "ac3" to "Выбирать дорожку AC3, если она есть: нужна ресиверу или саундбару с объёмным звуком",
    "next" to "Когда серия кончается, сразу включается следующая",
    "ok" to "Кнопка OK в плеере ставит на паузу и снимает с неё",
    "stats" to "Поверх видео: разрешение, кодек, скорость потока и запас буфера",
    "captions" to "Цвет и размер субтитров берутся из настроек телевизора, а не из приложения",
    "captions-system" to "Настройки телевизора, в которых задаётся вид субтитров",
    "posters" to "Освобождает место на телевизоре; обложки потом загрузятся заново",
    "id" to "Номер этого телевизора в вашем аккаунте kino.pub",
)

/** Пояснение к переключателю устройства: их список и названия приходят с сервиса. */
private fun toggleHint(label: String): String? = label.lowercase().let {
    when {
        "ssl" in it -> "Шифровать соединение с видеосервером"
        "hevc" in it -> "Разрешить кодек HEVC: то же качество при меньшем потоке, если телевизор его поддерживает"
        "hdr" in it -> "Показывать версии с HDR, когда они есть"
        "4k" in it -> "Показывать версии в 4K, когда они есть"
        "плейлист" in it -> "Отдавать в одном плейлисте потоки с разными кодеками"
        else -> null
    }
}

/** Переключатель: стеклянная дорожка с бегунком, включённый — красный. Нарисован, а не набран символом. */
@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier.size(width = 46.dp, height = 26.dp)
            .background(if (on) Accent else Color(0x29FFFFFF), CircleShape)
            .border(1.dp, if (on) Color.Transparent else Color(0x52FFFFFF), CircleShape)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(20.dp).background(if (on) Color.White else Color(0xFFB4B4BE), CircleShape)) }
}

@Composable
private fun Header(title: String) {
    Text(
        title.uppercase(), color = Dim, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = Edge, end = Edge, top = 24.dp, bottom = 6.dp),
    )
}

/** Строка настроек: название, под ним значение, справа галочка у переключателей. */
@Composable
internal fun SettingRow(
    title: String,
    value: String?,
    checked: Boolean?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    mark: Boolean = false,
    hint: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Edge - 16.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = enabled }
            .clip(RowShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .glassRow(focused)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Красная точка — строка, на которую стоит обратить внимание (вышло обновление).
        if (mark) Box(Modifier.padding(end = 12.dp).size(9.dp).background(Accent, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (dimmed) Color(0xFF6A6A6A) else Foreground)
            if (value != null) Text(value, color = Dim, style = MaterialTheme.typography.bodyMedium)
            // Пояснение, что делает настройка: мельче и тусклее значения.
            if (hint != null) Text(hint, color = Color(0xFF7E7E86), style = MaterialTheme.typography.bodySmall)
        }
        if (checked != null) Switch(checked)
    }
}

@Composable
private fun DialogPanel(dialog: Dialog, onClose: () -> Unit) {
    val first = remember(dialog) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(dialog) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).glassPanel()
                // Стрелки вбок не уводят фокус из окна в боковое меню.
                .onKeyEvent { it.key == Key.DirectionLeft || it.key == Key.DirectionRight }
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (dialog) {
                is Dialog.Choice -> {
                    PanelTitle(dialog.title)
                    // Фокус встаёт на текущий вариант; нажатие сразу применяет и закрывает.
                    val focusIndex = dialog.options.indexOfFirst { it.first == dialog.current }.coerceAtLeast(0)
                    dialog.options.forEachIndexed { index, (id, label) ->
                        SettingRow(
                            if (id == dialog.current) "●  $label" else "○  $label", null, null, enabled = true,
                            modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
                        ) {
                            onClose()
                            if (id != dialog.current) dialog.onPick(id)
                        }
                    }
                }
                is Dialog.Input -> {
                    var text by remember(dialog) { mutableStateOf(dialog.initial) }
                    PanelTitle(dialog.title)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = Foreground),
                        cursorBrush = SolidColor(Foreground),
                        // Галочка на клавиатуре сохраняет: кнопки под клавиатурой не видны.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            onClose()
                            val name = text.trim()
                            if (name.isNotEmpty() && name != dialog.initial) dialog.onSave(name)
                        }),
                        modifier = Modifier
                            .padding(horizontal = Edge, vertical = 8.dp)
                            .fillMaxWidth()
                            .glass(RowShape)
                            .padding(12.dp)
                            .focusRequester(first)
                            // Поле ввода забирает стрелки пульта себе: «вниз» уводим на кнопки.
                            .onPreviewKeyEvent { event ->
                                event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown &&
                                    focusManager.moveFocus(FocusDirection.Down)
                            },
                    )
                    SettingRow("Сохранить", null, null, enabled = true) {
                        onClose()
                        val name = text.trim()
                        if (name.isNotEmpty() && name != dialog.initial) dialog.onSave(name)
                    }
                    SettingRow("Отмена", null, null, enabled = true, onClick = onClose)
                }
                is Dialog.Update -> {
                    PanelTitle("Версия ${dialog.version.versionName}")
                    Text(
                        dialog.version.notes.ifBlank { "Список изменений не указан." },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = Edge, vertical = 8.dp),
                    )
                    SettingRow("Скачать и установить", null, null, enabled = true, modifier = Modifier.focusRequester(first)) {
                        onClose()
                        dialog.onInstall()
                    }
                    SettingRow("Не сейчас", null, null, enabled = true, onClick = onClose)
                }
                is Dialog.Confirm -> {
                    PanelTitle(dialog.title)
                    Text(dialog.text, color = Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Edge, vertical = 8.dp))
                    // Фокус на «Отмена»: случайное нажатие ничего не ломает.
                    SettingRow("Отмена", null, null, enabled = true, modifier = Modifier.focusRequester(first), onClick = onClose)
                    SettingRow(dialog.action, null, null, enabled = true) {
                        onClose()
                        dialog.onConfirm()
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Edge, vertical = 8.dp))
}
