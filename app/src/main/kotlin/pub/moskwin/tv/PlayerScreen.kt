package pub.moskwin.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.moskwin.core.Details
import pub.moskwin.core.Device
import pub.moskwin.core.Kinopub
import pub.moskwin.core.MediaAudio
import pub.moskwin.core.MediaLinks
import pub.moskwin.core.Video
import pub.moskwin.core.pickTracks

/** Что запустить: [kids] — детское ли это, от него зависит правило выбора дорожек. */
internal class PlayRequest(val details: Details, val video: Video, val kids: Boolean)

/** Ручной выбор озвучки и субтитров: запоминается для сериала (фильма) целиком. */
internal class PlaybackChoices(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    fun audio(itemId: Int): String? = prefs.getString("a:$itemId", null)
    fun subtitle(itemId: Int): String? = prefs.getString("s:$itemId", null)
    fun save(itemId: Int, audio: String?, subtitle: String) {
        prefs.edit().putString("a:$itemId", audio).putString("s:$itemId", subtitle).apply()
    }
}

/** Что открыто поверх видео. */
private enum class Panel { MENU, AUDIO, SUBTITLES, QUALITY, SPEED, SERVER, EXTERNAL }

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_MS = 4_000L
private const val REPORT_MS = 30_000L

/** Шаг перемотки кнопками влево и вправо. */
private const val SEEK_MS = 15_000L

/**
 * Плеер. Пульт: OK — пауза, влево/вправо — перемотка, вверх/вниз — панель выбора
 * (озвучка, субтитры, качество, скорость, сервер), «назад» — закрыть панель или выйти.
 * В панели выбор применяется сразу и панель закрывается; фокус стоит на текущем значении.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerScreen(
    kinopub: Kinopub,
    settings: AppSettings,
    request: PlayRequest,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val details = request.details
    val itemId = details.item.id
    val choices = remember { PlaybackChoices(context.applicationContext) }
    val root = remember { FocusRequester() }

    var video by remember { mutableStateOf(request.video) }
    var links by remember { mutableStateOf<MediaLinks?>(null) }
    var device by remember { mutableStateOf<Device?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var controlsUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var speed by remember { mutableFloatStateOf(1f) }
    // Выбранное сейчас: дорожка озвучки, номер субтитров (null — выключены), высота кадра (0 — авто).
    var audio by remember { mutableStateOf<MediaAudio?>(null) }
    var subtitle by remember { mutableStateOf<Int?>(null) }
    var quality by remember { mutableIntStateOf(settings.quality.toIntOrNull() ?: 0) }
    var reload by remember { mutableIntStateOf(0) }
    var askedExternal by remember { mutableStateOf(false) }
    var loadedVideo by remember { mutableIntStateOf(-1) }

    val player = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                // «Небольшой буфер»: меньше памяти и быстрее старт ценой устойчивости к провалам сети.
                if (settings.smallBuffer) DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 20_000, 1_500, 3_000).build()
                else DefaultLoadControl(),
            )
            // Начальная оценка канала высокая: по умолчанию начинаем с лучшего качества.
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(40_000_000).build())
            .build()
    }

    fun season(): Int? = details.seasons.firstOrNull { video in it.episodes }?.number
    fun showControls() { controlsUntil = System.currentTimeMillis() + CONTROLS_MS }

    /** Отметка на сервере, где остановились. Не зависит от жизни экрана: вызывается и при выходе. */
    fun report(seconds: Long, of: Video = video, inSeason: Int? = season()) {
        if (seconds <= 0) return
        CoroutineScope(Dispatchers.IO).launch { runCatching { kinopub.markTime(itemId, of.number, inSeason, seconds.toInt()) } }
    }

    // --- Дорожки ---------------------------------------------------------------------------

    /** Номер озвучки из названия дорожки («08. Оригинал (ENG)»); без номера — по порядку. */
    fun audioIndexOf(group: Tracks.Group, ordinal: Int): Int =
        group.getTrackFormat(0).label?.let { Regex("""^\s*(\d+)\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: (ordinal + 1)

    fun applySelection() {
        val builder = player.trackSelectionParameters.buildUpon()
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        audioGroups.withIndex().firstOrNull { (i, g) -> audioIndexOf(g, i) == audio?.index }?.value?.let {
            builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
        }
        // Субтитры берутся из API (там известны язык и forced), у них id вида «api:N».
        val textGroup = subtitle?.let { wanted ->
            tracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.substringAfterLast("api:", "")?.toIntOrNull() == wanted
            }
        }
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textGroup == null)
        if (textGroup != null) builder.setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, 0))
        // Качество: авто — адаптивно без ограничений, иначе не выше выбранной высоты.
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (quality > 0) builder.setMaxVideoSize(Int.MAX_VALUE, quality) else builder.clearVideoSizeConstraints()
        player.trackSelectionParameters = builder.build()
    }

    fun rememberChoice() {
        choices.save(itemId, audio?.signature, subtitle?.let { links?.subtitles?.getOrNull(it)?.signature } ?: "off")
    }

    // --- Загрузка видео --------------------------------------------------------------------

    LaunchedEffect(video, reload) {
        error = null
        buffering = true
        try {
            val loaded = withContext(Dispatchers.Default) { kinopub.mediaLinks(video.id) }
            if (device == null) device = withContext(Dispatchers.Default) { runCatching { kinopub.device() }.getOrNull() }
            val url = loaded.streamUrl(device?.streams?.firstOrNull { it.selected == 1 }?.label)
                ?: throw IllegalStateException("сервис не дал ссылку на видео")
            links = loaded
            if (settings.askPlayer && !askedExternal) {
                askedExternal = true
                panel = Panel.EXTERNAL
                return@LaunchedEffect
            }
            // Та же серия заново (сменили сервер): позиция и выбранные дорожки сохраняются.
            // Новая серия: начинаем с её отметки, дорожки — по правилу или запомненному выбору.
            val sameVideo = loadedVideo == video.id && player.currentPosition > 0
            loadedVideo = video.id
            val resumeAt = if (sameVideo) player.currentPosition
            else video.watching.takeIf { it.status == 0 && it.time > 30 && it.time < video.duration * 0.93 }?.let { (it.time * 1000).toLong() } ?: 0L
            if (!sameVideo || audio == null) {
                val pick = pickTracks(
                    video.audios, loaded.subtitles, request.kids, settings.preferAc3,
                    choices.audio(itemId), choices.subtitle(itemId),
                )
                audio = pick.audio
                subtitle = pick.subtitle
            }
            val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(
                loaded.subtitles.mapIndexedNotNull { i, s ->
                    val address = s.url ?: return@mapIndexedNotNull null
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(address))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(s.lang).setId("api:$i").build()
                },
            ).build()
            tracks = Tracks.EMPTY
            // Играть начинаем после выбора дорожек, чтобы не прозвучала не та озвучка.
            player.playWhenReady = false
            player.setMediaItem(item, resumeAt)
            player.prepare()
            showControls()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(changed: Tracks) {
                val first = tracks.isEmpty && !changed.isEmpty
                tracks = changed
                if (first) {
                    applySelection()
                    player.playWhenReady = true
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                view.keepScreenOn = isPlaying
                if (!isPlaying) report(player.currentPosition / 1000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    // Досмотрено: отмечаем полную длительность и идём к следующей серии.
                    report(video.duration.toLong().coerceAtLeast(player.duration / 1000))
                    val all = details.allVideos
                    val next = all.getOrNull(all.indexOf(video) + 1)
                    if (settings.autoNext && next != null) video = next else onExit()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Не удалось воспроизвести: ${e.errorCodeName}"
            }
        }
        player.addListener(listener)
        Perf.screen = "player"
        onDispose {
            report(player.currentPosition / 1000)
            view.keepScreenOn = false
            player.removeListener(listener)
            player.release()
        }
    }

    // Часы для полосы прогресса и регулярная отметка на сервере.
    LaunchedEffect(Unit) {
        var lastReport = System.currentTimeMillis()
        while (true) {
            val time = System.currentTimeMillis()
            // Пока поверх видео ничего не показано, состояние не трогаем: экран не пересобирается.
            if (time < controlsUntil + 1_000 || !playing || panel != null || settings.showStats) {
                now = time
                position = player.currentPosition
                duration = player.duration.takeIf { it > 0 } ?: (video.duration * 1000).toLong()
            }
            if (playing && time - lastReport > REPORT_MS) {
                lastReport = time
                report(player.currentPosition / 1000)
            }
            delay(500)
        }
    }
    LaunchedEffect(panel) { if (panel == null) runCatching { root.requestFocus() } }
    // Шаг назад в панели: из списка — в меню, из меню — к видео, ничего не меняя.
    val panelBack = { panel = if (panel == Panel.MENU || panel == Panel.EXTERNAL) null else Panel.MENU }
    BackHandler(enabled = panel != null) { panelBack() }

    fun seekBy(forward: Boolean) {
        // Удержание кнопки повторяет нажатия, поэтому отдельного ускорения нет.
        val target = (player.currentPosition + if (forward) SEEK_MS else -SEEK_MS).coerceIn(0, duration.coerceAtLeast(0))
        player.seekTo(target)
        position = target
        showControls()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(root)
            .focusable()
            .onKeyEvent { event ->
                if (panel != null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (settings.okPauses || event.key == Key.MediaPlayPause) {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                        showControls(); true
                    }
                    Key.MediaPlay -> { player.play(); showControls(); true }
                    Key.MediaPause -> { player.pause(); showControls(); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seekBy(true); true }
                    Key.DirectionLeft, Key.MediaRewind -> { seekBy(false); true }
                    Key.DirectionUp, Key.DirectionDown, Key.Menu -> { panel = Panel.MENU; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    this.player = player
                    subtitleView?.apply {
                        if (settings.systemCaptionStyle) {
                            setUserDefaultStyle()
                            setUserDefaultTextSize()
                        } else {
                            setStyle(
                                CaptionStyleCompat(
                                    android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    android.graphics.Color.BLACK, null,
                                ),
                            )
                            setFractionalTextSize(0.055f)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val serverLabel = device?.servers?.firstOrNull { it.selected == 1 }?.label
        val height = player.videoFormat?.height
        val qualityLabel = height?.let { "${it}p" + if (quality == 0) " (авто)" else "" } ?: ""
        val subtitleLabel = subtitle?.let { links?.subtitles?.getOrNull(it)?.title } ?: "выключены"

        if (error != null) {
            Centered {
                Text(error.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("«Назад» — выйти, вверх — выбрать другой сервер или качество", color = Dim, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (buffering && panel == null) {
            Text("Загрузка…", color = Dim, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
        }

        if (settings.showStats) {
            val v = player.videoFormat
            val a = player.audioFormat
            Text(
                listOfNotNull(
                    v?.let { "${it.width}×${it.height} ${it.codecs.orEmpty()} ${it.bitrate.takeIf { b -> b > 0 }?.div(1000)?.let { k -> "$k кбит/с" }.orEmpty()}" },
                    a?.let { "${it.sampleMimeType.orEmpty().substringAfter('/')} ${it.channelCount} кан." },
                    "буфер ${(player.totalBufferedDuration / 1000)} с",
                ).joinToString("   "),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopEnd).background(Color(0x99000000)).padding(8.dp),
            )
        }

        // Полоса управления: видна после нажатий и на паузе.
        if (panel == null && (now < controlsUntil || (!playing && !buffering && error == null))) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = Edge, end = Edge, top = 48.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    details.item.name + (season()?.let { "  ·  сезон $it, серия ${video.number}" } ?: ""),
                    style = MaterialTheme.typography.titleLarge,
                )
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF))) {
                    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Accent))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (playing) "" else "❚❚  ") + "${clock(position)} / ${clock(duration)}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(serverLabel, qualityLabel.ifEmpty { null }, audio?.title, "субтитры: $subtitleLabel", if (speed != 1f) "${speed}×" else null)
                            .joinToString("  ·  "),
                        color = Dim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        panel?.let { open ->
            val close = { panel = null }
            PanelBox(onBack = panelBack) {
                when (open) {
                    Panel.MENU -> {
                        PanelItem("Озвучка", audio?.title ?: "—", first = true) { panel = Panel.AUDIO }
                        PanelItem("Субтитры", subtitleLabel) { panel = Panel.SUBTITLES }
                        PanelItem("Качество", if (quality == 0) "Авто (лучшее)" else "${quality}p") { panel = Panel.QUALITY }
                        PanelItem("Скорость", "${speed}×") { panel = Panel.SPEED }
                        PanelItem("Сервер", serverLabel ?: "—") { panel = Panel.SERVER }
                    }
                    Panel.AUDIO -> video.audios.forEachIndexed { i, track ->
                        PanelItem(track.title, null, first = track.index == audio?.index || (audio == null && i == 0), marked = track.index == audio?.index) {
                            audio = track; applySelection(); rememberChoice(); close()
                        }
                    }
                    Panel.SUBTITLES -> {
                        PanelItem("Выключены", null, first = subtitle == null, marked = subtitle == null) {
                            subtitle = null; applySelection(); rememberChoice(); close()
                        }
                        links?.subtitles?.forEachIndexed { i, s ->
                            if (s.url != null) PanelItem(s.title, null, first = subtitle == i, marked = subtitle == i) {
                                subtitle = i; applySelection(); rememberChoice(); close()
                            }
                        }
                    }
                    Panel.QUALITY -> {
                        val heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
                            .filter { it > 0 }.distinct().sortedDescending()
                        PanelItem("Авто (лучшее)", null, first = quality == 0, marked = quality == 0) {
                            quality = 0; applySelection(); close()
                        }
                        // Подписи — из API («1080p»): высота кадра в потоке зависит от формата фильма.
                        val names = links?.files.orEmpty().sortedByDescending { it.h }.map { it.quality }
                        heights.forEachIndexed { i, h ->
                            val name = names.getOrNull(i)?.takeIf { names.size == heights.size } ?: "${h}p"
                            PanelItem(name, null, first = quality == h, marked = quality == h) {
                                quality = h; applySelection(); close()
                            }
                        }
                    }
                    Panel.SPEED -> SPEEDS.forEach { s ->
                        PanelItem("${s}×", null, first = speed == s, marked = speed == s) {
                            speed = s; player.setPlaybackSpeed(s); close()
                        }
                    }
                    Panel.SERVER -> device?.servers.orEmpty().forEach { server ->
                        PanelItem(server.label, null, first = server.selected == 1, marked = server.selected == 1) {
                            close()
                            if (server.selected != 1) scope.launch {
                                try {
                                    val d = device ?: return@launch
                                    withContext(Dispatchers.Default) {
                                        kinopub.setDeviceSetting(d.id, Device.SERVER, "${server.id}")
                                        device = kinopub.device()
                                    }
                                    // Ссылки зависят от сервера: берём новые и продолжаем с того же места.
                                    reload++
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "Сервер не переключился: ${e.message}"
                                }
                            }
                        }
                    }
                    Panel.EXTERNAL -> {
                        PanelItem("Встроенный плеер", null, first = true) { close(); reload++ }
                        PanelItem("Другое приложение", null) {
                            val url = links?.streamUrl(device?.streams?.firstOrNull { it.selected == 1 }?.label)
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"), details.item.name),
                                )
                            }
                            onExit()
                        }
                    }
                }
            }
        }
    }
}

/** Панель выбора справа поверх видео. */
@Composable
private fun PanelBox(onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(420.dp).fillMaxHeight().background(Color(0xF01C1C1C))
                // Панель справа, поэтому «влево» — тоже выход из неё без выбора, как и «назад».
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onBack()
                    true
                }
                .verticalScroll(rememberScrollState()).padding(vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/** Строка панели. [first] — на ней фокус при открытии, [marked] — текущее значение. */
@Composable
private fun PanelItem(title: String, value: String?, first: Boolean = false, marked: Boolean = false, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    SettingRow(
        if (marked) "●  $title" else title, value, null, enabled = true,
        modifier = if (first) Modifier.focusRequester(focus) else Modifier,
        onClick = onClick,
    )
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
