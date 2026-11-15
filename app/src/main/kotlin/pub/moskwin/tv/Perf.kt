package pub.moskwin.tv

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import coil.EventListener
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * Метрики скорости в logcat под тегом `moskwin-perf`:
 *
 * - `frames` — сводка по кадрам экрана: сколько, медиана, p95, худший, доля медленных
 *   (дольше 16,7 мс — пропущен кадр при 60 Гц) и застывших (дольше 100 мс);
 * - `start` — от запуска процесса до первого кадра главного экрана;
 * - `home`, `card`, `search` — сколько заняла загрузка данных экрана;
 * - `http` — каждый запрос к API: путь, код, время, размер;
 * - `image` — каждая обложка: откуда взята, время, размер.
 *
 * Смотреть: `adb logcat -s moskwin-perf`.
 */
object Perf {
    private const val TAG = "moskwin-perf"
    private const val FLUSH_FRAMES = 240
    private const val SLOW_NS = 16_700_000L
    private const val FROZEN_NS = 100_000_000L

    /** Экран, к которому относятся кадры. Смена экрана закрывает текущую сводку. */
    @Volatile
    var screen: String = "start"
        set(value) {
            if (field != value) {
                handler?.post { flush() }
                field = value
            }
        }

    private var handler: Handler? = null
    private val frames = LongArray(FLUSH_FRAMES)
    private var count = 0

    // Этапы кадра: ввод, анимации (здесь же пересборка Compose), измерение и раскладка,
    // отрисовка, передача в поток рендера, команды видеочипу, показ.
    private val STAGES = intArrayOf(
        FrameMetrics.INPUT_HANDLING_DURATION, FrameMetrics.ANIMATION_DURATION,
        FrameMetrics.LAYOUT_MEASURE_DURATION, FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION,
        FrameMetrics.COMMAND_ISSUE_DURATION, FrameMetrics.SWAP_BUFFERS_DURATION,
    )
    private val STAGE_NAMES = arrayOf("input", "anim", "layout", "draw", "sync", "gpu", "swap")
    private val stageSums = LongArray(STAGES.size)
    private var framesScreen = "start"
    private var startLogged = false

    fun event(name: String, ms: Long, details: String = "") {
        Log.i(TAG, "$name ${ms}ms $details".trimEnd())
    }

    inline fun <T> timed(name: String, details: String = "", block: () -> T): T {
        val began = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            event(name, SystemClock.elapsedRealtime() - began, details)
        }
    }

    /** Первый кадр с данными главного экрана: время от запуска процесса. Пишется один раз. */
    fun homeShown(fromCache: Boolean) {
        if (startLogged) return
        startLogged = true
        event("start", SystemClock.uptimeMillis() - Process.getStartUptimeMillis(), "cache=$fromCache")
    }

    fun install(activity: Activity) {
        val thread = HandlerThread("perf").apply { start() }
        val handler = Handler(thread.looper).also { this.handler = it }
        activity.window.addOnFrameMetricsAvailableListener(
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (framesScreen != screen) flush()
                framesScreen = screen
                frames[count++] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                for (i in STAGES.indices) stageSums[i] += metrics.getMetric(STAGES[i])
                if (count == FLUSH_FRAMES) flush()
            },
            handler,
        )
    }

    /** Закрывает сводку по кадрам. Вызывается только из потока метрик. */
    private fun flush() {
        if (count == 0) return
        val sorted = frames.copyOf(count).also { it.sort() }
        fun ms(ns: Long) = "%.1f".format(ns / 1e6)
        fun percentile(p: Double) = sorted[((count - 1) * p).toInt()]
        val slow = sorted.count { it > SLOW_NS } * 100 / count
        val frozen = sorted.count { it > FROZEN_NS }
        Log.i(
            TAG,
            "frames screen=$framesScreen n=$count p50=${ms(percentile(0.5))} p95=${ms(percentile(0.95))} " +
                "max=${ms(sorted.last())} slow=$slow% frozen=$frozen | среднее по этапам: " +
                STAGE_NAMES.indices.joinToString(" ") { "${STAGE_NAMES[it]}=${ms(stageSums[it] / count)}" },
        )
        count = 0
        stageSums.fill(0)
    }

    /** Время и источник каждой обложки. */
    val images = object : EventListener {
        private val began = java.util.concurrent.ConcurrentHashMap<ImageRequest, Long>()

        override fun onStart(request: ImageRequest) {
            began[request] = SystemClock.elapsedRealtime()
        }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            val ms = SystemClock.elapsedRealtime() - (began.remove(request) ?: return)
            val drawable = result.drawable
            event(
                "image", ms,
                "${result.dataSource.name.lowercase()} ${drawable.intrinsicWidth}x${drawable.intrinsicHeight} " +
                    request.data.toString().substringAfter("staticpop.net"),
            )
        }

        override fun onError(request: ImageRequest, result: coil.request.ErrorResult) {
            began.remove(request)
            Log.w(TAG, "image failed ${request.data}: ${result.throwable.message}")
        }

        override fun onCancel(request: ImageRequest) {
            began.remove(request)
        }
    }
}
