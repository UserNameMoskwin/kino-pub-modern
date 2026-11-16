package pub.moskwin.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.text.Cue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
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
import pub.moskwin.core.SubtitleCue
import pub.moskwin.core.fetchSubtitles
import pub.moskwin.core.subtitleAt
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

    // Сдвиги звука и субтитров — в десятых долях секунды, отдельно для каждой серии (фильма).
    fun audioOffset(videoId: Int): Int = prefs.getInt("ao:$videoId", 0)
    fun subtitleOffset(videoId: Int): Int = prefs.getInt("so:$videoId", 0)
    fun saveOffsets(videoId: Int, audio: Int, subtitle: Int) {
        prefs.edit().apply {
            if (audio == 0) remove("ao:$videoId") else putInt("ao:$videoId", audio)
            if (subtitle == 0) remove("so:$videoId") else putInt("so:$videoId", subtitle)
        }.apply()
    }
}

/** Сдвиг звука относительно видео; читается потоком воспроизведения. */
private class AudioDelay {
    @Volatile var us = 0L
}

/**
 * Плеер не умеет сдвигать звук сам. Часы воспроизведения ведёт звуковой выход: видео
 * показывает кадр, время которого на этих часах наступило. Если к показаниям часов
 * прибавить сдвиг, видео уйдёт вперёд на эту величину — звук окажется позже картинки
 * (при отрицательном сдвиге — раньше). Менять можно на ходу.
 */
@UnstableApi
private class DelayedAudioFactory(context: Context, private val delay: AudioDelay) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return object : ForwardingAudioSink(sink) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val position = super.getCurrentPositionUs(sourceEnded)
                return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else position + delay.us
            }
        }
    }
}

/** Наибольший сдвиг в обе стороны: 10 секунд, шаг — 0,1 секунды. */
private const val OFFSET_LIMIT = 100

/** Что открыто поверх видео. */
private enum class Panel { MENU, AUDIO, SUBTITLES, QUALITY, SPEED, PICTURE, SERVER, EXTERNAL, AUDIO_OFFSET, SUBTITLE_OFFSET }

/**
 * Как картинка вписана в экран: ключ для настроек, подпись и режим плеера. Нужно, когда
 * формат фильма не совпадает с экраном и остаются чёрные поля.
 */
@OptIn(UnstableApi::class)
private val PICTURES = listOf(
    Triple("fit", "Целиком, с полями", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Triple("width", "По ширине экрана", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    Triple("height", "По высоте экрана", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT),
    Triple("zoom", "Заполнить экран, обрезав края", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Triple("fill", "Растянуть на весь экран", AspectRatioFrameLayout.RESIZE_MODE_FILL),
)

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_MS = 4_000L
private const val REPORT_MS = 30_000L

/** Шаг перемотки кнопками влево и вправо. */
private const val SEEK_MS = 15_000L

/**
 * Плеер. Пульт: OK — пауза, влево/вправо — перемотка, вниз — плашка с названием и
 * временем (как на паузе), вверх — панель выбора (озвучка, субтитры, их сдвиги, качество,
 * скорость, сервер), «назад» — закрыть панель или выйти. В панели выбор применяется сразу
 * и панель закрывается; фокус стоит на текущем значении.
 *
 * Субтитры плеер рисует сам по файлу из API, а не через дорожку потока: так их можно
 * сдвигать по времени и переключать без пересборки потока.
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
    var picture by remember { mutableStateOf(settings.picture) }
    var askedExternal by remember { mutableStateOf(false) }
    var loadedVideo by remember { mutableIntStateOf(-1) }
    // Сдвиги в десятых секунды: плюс — позже. Свои у каждой серии.
    var audioOffset by remember { mutableIntStateOf(choices.audioOffset(request.video.id)) }
    var subtitleOffset by remember { mutableIntStateOf(choices.subtitleOffset(request.video.id)) }
    var cues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var cueText by remember { mutableStateOf<String?>(null) }
    var subtitleProblem by remember { mutableStateOf(false) }
    val audioDelay = remember { AudioDelay().apply { us = audioOffset * 100_000L } }

    val player = remember {
        ExoPlayer.Builder(context, DelayedAudioFactory(context, audioDelay))
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
    // Кнопки плашки: предыдущая серия, пауза, следующая. Выбранная кнопка — [barButton];
    // null — плашка только показывает, а влево и вправо перематывают. У фильма серий нет,
    // поэтому кнопок и их выбора тоже нет.
    var barButton by remember { mutableStateOf<Int?>(null) }
    // Пока в плашке выбирают кнопку, она держится дольше: успеть дойти до нужной.
    fun showControls() { controlsUntil = System.currentTimeMillis() + if (barButton != null) BAR_CONTROLS_MS else CONTROLS_MS }
    fun neighbour(step: Int): Video? = details.allVideos.let { all -> all.getOrNull(all.indexOf(video) + step) }
    fun barShown() = System.currentTimeMillis() < controlsUntil || !player.isPlaying
    fun closeBar() {
        barButton = null
        controlsUntil = 0
    }

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
        // Субтитры из потока не используются: их рисует сам экран по файлу из API.
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
            if (loadedVideo != video.id) {
                audioOffset = choices.audioOffset(video.id)
                subtitleOffset = choices.subtitleOffset(video.id)
                audioDelay.us = audioOffset * 100_000L
            }
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
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            tracks = Tracks.EMPTY
            // Играть начинаем после выбора дорожек, чтобы не прозвучала не та озвучка.
            player.playWhenReady = false
            player.setMediaItem(MediaItem.fromUri(url), resumeAt)
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

    // Файл выбранных субтитров; смена дорожки поток не трогает.
    LaunchedEffect(links, subtitle) {
        cues = emptyList()
        cueText = null
        subtitleProblem = false
        val address = subtitle?.let { links?.subtitles?.getOrNull(it)?.url } ?: return@LaunchedEffect
        try {
            cues = fetchSubtitles(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            subtitleProblem = true
        }
    }
    // Какая реплика сейчас на экране. Состояние меняется только при смене реплики.
    LaunchedEffect(cues) {
        if (cues.isEmpty()) return@LaunchedEffect
        while (true) {
            val text = subtitleAt(cues, player.currentPosition - subtitleOffset * 100L)
            if (text != cueText) cueText = text
            delay(80)
        }
    }

    fun setOffsets(audioTenths: Int, subtitleTenths: Int) {
        audioOffset = audioTenths.coerceIn(-OFFSET_LIMIT, OFFSET_LIMIT)
        subtitleOffset = subtitleTenths.coerceIn(-OFFSET_LIMIT, OFFSET_LIMIT)
        audioDelay.us = audioOffset * 100_000L
        choices.saveOffsets(video.id, audioOffset, subtitleOffset)
    }
    // Шаг назад в панели: из списка — в меню, из меню — к видео, ничего не меняя.
    val panelBack = { panel = if (panel == Panel.MENU || panel == Panel.EXTERNAL) null else Panel.MENU }
    BackHandler(enabled = panel != null) { panelBack() }

    /** Переход на соседнюю серию из плашки: место в текущей запоминается на сервере. */
    fun switchTo(target: Video?) {
        if (target == null) return
        report(player.currentPosition / 1000)
        video = target
        barButton = BAR_PLAY
        showControls()
    }

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
            // «Назад» при открытой панели ловится здесь, до элементов панели: иначе система
            // тратит нажатие на перевод фокуса с элемента панели на сам экран, и панель остаётся.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                // «Назад» сначала закрывает панель, потом плашку с кнопками и только потом плеер.
                val closesBar = panel == null && barButton != null && barShown()
                if (panel == null && !closesBar) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) { if (panel != null) panelBack() else closeBar() }
                true
            }
            .onKeyEvent { event ->
                if (panel != null || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Плашка погасла сама — выбор кнопки сбрасывается, стрелки снова перематывают.
                if (barButton != null && !barShown()) barButton = null
                val chosen = barButton
                if (chosen != null) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val step = if (event.key == Key.DirectionRight) 1 else -1
                            val target = chosen + step
                            // Кнопки без соседней серии нет — на неё выбор не переходит.
                            if (target in BAR_PREVIOUS..BAR_NEXT && (target == BAR_PLAY || neighbour(target - BAR_PLAY) != null)) barButton = target
                            showControls()
                            return@onKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            when (chosen) {
                                BAR_PREVIOUS -> switchTo(neighbour(-1))
                                BAR_NEXT -> switchTo(neighbour(1))
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                            showControls()
                            return@onKeyEvent true
                        }
                        else -> {}
                    }
                }
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
                    Key.DirectionUp, Key.Menu -> { panel = Panel.MENU; true }
                    // Вниз — плашка как на паузе, у сериала с выбором кнопок; второе нажатие убирает её.
                    Key.DirectionDown -> {
                        // У сериала плашка, которая и так видна (после перемотки, на паузе), от «вниз»
                        // не гаснет, а даёт выбрать кнопку.
                        val hide = if (details.isSerial) barButton != null else System.currentTimeMillis() < controlsUntil
                        if (hide) closeBar() else {
                            if (details.isSerial) barButton = BAR_PLAY
                            showControls()
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    this.player = player
                }
            },
            // Режим картинки меняется из панели на ходу.
            update = { it.resizeMode = PICTURES.firstOrNull { p -> p.first == picture }?.third ?: AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
        )
        // Субтитры: реплика читается внутри update, поэтому её смена не пересобирает экран.
        AndroidView(
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    isFocusable = false
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
            },
            update = { subtitles ->
                subtitles.setCues(cueText?.let { listOf(Cue.Builder().setText(it).build()) } ?: emptyList())
            },
            modifier = Modifier.fillMaxSize(),
        )

        val serverLabel = device?.servers?.firstOrNull { it.selected == 1 }?.label
        val height = player.videoFormat?.height
        val qualityLabel = height?.let { "${it}p" + if (quality == 0) " (авто)" else "" } ?: ""
        val subtitleLabel = subtitle?.let { links?.subtitles?.getOrNull(it)?.title }
            ?.let { if (subtitleProblem) "$it (не загрузились)" else it } ?: "выключены"

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
                // Плавающая стеклянная плашка внизу, а не затемнение на всю ширину.
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
                    .glassPanel()
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // «Сериал: Серия» и тем же шрифтом, но серым, номер серии: s01e15. У фильма — только название.
                val episode = season()
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (episode != null && video.title.isNotBlank()) "${details.item.name}: ${video.title.substringBefore(" / ")}" else details.item.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    )
                    if (episode != null) Text(
                        "s%02de%02d".format(episode, video.number), color = Dim,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline(),
                    )
                }
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF))) {
                    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Accent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Значок нарисован, а не набран символом: у символов из запасного шрифта
                    // другая высота строки, и при смене значка плашка подпрыгивала.
                    // У сериала рядом с паузой — предыдущая и следующая серия; выбранная кнопка залита.
                    val picked = barButton
                    if (details.isSerial) BarButton(picked == BAR_PREVIOUS, enabled = neighbour(-1) != null) { SkipIcon(forward = false, it) }
                    // Кнопка показывает действие: пока идёт — пауза, на паузе — пуск.
                    BarButton(picked == BAR_PLAY, enabled = true) { PlayIcon(!playing, it) }
                    if (details.isSerial) BarButton(picked == BAR_NEXT, enabled = neighbour(1) != null) { SkipIcon(forward = true, it) }
                    Text(
                        "${clock(position)} / ${clock(duration)}", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        listOfNotNull(serverLabel, qualityLabel.ifEmpty { null }, audio?.title, "Субтитры: $subtitleLabel", if (speed != 1f) "${speed}×" else null)
                            .joinToString("  ·  "),
                        color = Dim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when (panel) {
            Panel.AUDIO_OFFSET -> OffsetPlate("Сдвиг звука", "звук", audioOffset) { setOffsets(it, subtitleOffset) }
            Panel.SUBTITLE_OFFSET -> OffsetPlate("Сдвиг субтитров", "субтитры", subtitleOffset) { setOffsets(audioOffset, it) }
            else -> {}
        }
        panel?.takeIf { it != Panel.AUDIO_OFFSET && it != Panel.SUBTITLE_OFFSET }?.let { open ->
            val close = { panel = null }
            PanelBox(onBack = panelBack) {
                when (open) {
                    Panel.MENU -> {
                        PanelItem("Озвучка", audio?.title ?: "—", first = true) { panel = Panel.AUDIO }
                        PanelItem("Субтитры", subtitleLabel) { panel = Panel.SUBTITLES }
                        PanelItem("Сдвиг звука", offsetLabel(audioOffset)) { panel = Panel.AUDIO_OFFSET }
                        PanelItem("Сдвиг субтитров", offsetLabel(subtitleOffset)) { panel = Panel.SUBTITLE_OFFSET }
                        PanelItem("Качество", if (quality == 0) "Авто (лучшее)" else "${quality}p") { panel = Panel.QUALITY }
                        PanelItem("Скорость", "${speed}×") { panel = Panel.SPEED }
                        PanelItem("Картинка", PICTURES.firstOrNull { it.first == picture }?.second ?: "—") { panel = Panel.PICTURE }
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
                                subtitle = i; rememberChoice(); close()
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
                    // Выбор запоминается для всех фильмов: это свойство экрана, а не фильма.
                    Panel.PICTURE -> PICTURES.forEach { (key, label, _) ->
                        PanelItem(label, null, first = picture == key, marked = picture == key) {
                            picture = key; settings.picture = key; close()
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
                    Panel.AUDIO_OFFSET, Panel.SUBTITLE_OFFSET -> {}
                }
            }
        }
    }
}

/** Панель выбора справа поверх видео. */
@Composable
private fun PanelBox(onBack: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(420.dp).fillMaxHeight().glassPanel()
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

/** Значок кнопки в плашке: треугольник — пуск, две полоски — пауза. Размер не меняется. */
@Composable
private fun PlayIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        if (playing) {
            drawPath(
                Path().apply { moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.12f, size.height); close() },
                color,
            )
        } else {
            val bar = size.width * 0.3f
            drawRect(color, Offset(size.width * 0.1f, 0f), Size(bar, size.height))
            drawRect(color, Offset(size.width * 0.9f - bar, 0f), Size(bar, size.height))
        }
    }
}

/** Значок перехода к соседней серии: треугольник и черта у края, куда он смотрит. */
@Composable
private fun SkipIcon(forward: Boolean, color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val bar = size.width * 0.16f
        val tip = if (forward) size.width - bar else bar
        val back = if (forward) 0f else size.width
        drawPath(Path().apply { moveTo(back, 0f); lineTo(tip, size.height / 2); lineTo(back, size.height); close() }, color)
        drawRect(color, Offset(if (forward) size.width - bar else 0f, 0f), Size(bar, size.height))
    }
}

private const val BAR_CONTROLS_MS = 8_000L
private const val BAR_PREVIOUS = 0
private const val BAR_PLAY = 1
private const val BAR_NEXT = 2

/** Кнопка плашки: круг, залитый светлым, когда она выбрана. [enabled] = false — соседней серии нет. */
@Composable
private fun BarButton(picked: Boolean, enabled: Boolean, icon: @Composable (Color) -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).size(36.dp).background(if (picked) FocusFill else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) { icon(if (picked) OnFocus else if (enabled) Foreground else Color(0x55FFFFFF)) }
}

private fun offsetLabel(tenths: Int): String = if (tenths == 0) "нет" else "%+.1f с".format(tenths / 10f)

/**
 * Настройка сдвига поверх идущего видео: влево и вправо меняют на 0,1 с (удержание
 * повторяет), OK сбрасывает в ноль, «назад» возвращает в меню. Меняется сразу.
 */
@Composable
private fun OffsetPlate(title: String, what: String, tenths: Int, onChange: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .glassPanel()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onChange(tenths - 1); true }
                        Key.DirectionRight -> { onChange(tenths + 1); true }
                        Key.DirectionCenter, Key.Enter -> { onChange(0); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                }
                .padding(horizontal = 40.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = Dim, style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    tenths == 0 -> "0 с"
                    tenths > 0 -> "%+.1f с — $what позже".format(tenths / 10f)
                    else -> "%+.1f с — $what раньше".format(tenths / 10f)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text("◀ раньше   ·   позже ▶   ·   OK — сбросить   ·   Назад — готово", color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
