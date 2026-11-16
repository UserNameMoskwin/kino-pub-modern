package pub.moskwin.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Скорость загрузки с видеосервера, Мбит/с. Качает файлы из [urls] по очереди, пока не
 * пройдёт [maxSeconds] секунд, и делит принятое на время приёма. Время считается от
 * первого байта каждого файла: установка соединения в скорость не входит.
 * Если набралось меньше мегабайта, скорости в ответе нет — по такому объёму её не оценить.
 */
suspend fun measureSpeed(urls: List<String>, maxSeconds: Int = 6, http: OkHttpClient = OkHttpClient()): SpeedResult =
    withContext(Dispatchers.IO) {
        val buffer = ByteArray(64 * 1024)
        val budget = maxSeconds * 1_000_000_000L
        var bytes = 0L
        var nanos = 0L
        var problem: String? = null
        for (url in urls) {
            if (nanos >= budget) break
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) problem = "HTTP ${response.code}"
                    val source = response.body?.byteStream()?.takeIf { response.isSuccessful } ?: return@use
                    var firstByteAt = 0L
                    while (true) {
                        val read = source.read(buffer)
                        val now = System.nanoTime()
                        if (read < 0) break
                        if (firstByteAt == 0L) {
                            firstByteAt = now
                            continue
                        }
                        bytes += read
                        if (nanos + (now - firstByteAt) >= budget) break
                    }
                    if (firstByteAt != 0L) nanos += System.nanoTime() - firstByteAt
                }
            }.onFailure { problem = it.message ?: it.javaClass.simpleName }
        }
        SpeedResult(
            mbps = if (bytes < MIN_BYTES || nanos == 0L) null else bytes * 8 / (nanos / 1e9) / 1e6,
            bytes = bytes,
            files = urls.size,
            problem = problem,
        )
    }

/**
 * Разворачивает HLS-плейлист в адреса фрагментов видео: сервис отдаёт трейлеры
 * плейлистами, а мерить скорость нужно на самих фрагментах. Из главного плейлиста
 * берётся последний вариант (обычно самое высокое качество). Адрес, который не
 * плейлист, возвращается как есть.
 */
suspend fun mediaSegments(url: String, http: OkHttpClient = OkHttpClient()): List<String> = withContext(Dispatchers.IO) {
    fun lines(address: String): Pair<okhttp3.HttpUrl, List<String>>? {
        val base = address.toHttpUrlOrNull() ?: return null
        val text = http.newCall(Request.Builder().url(base).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            // Плейлист — небольшой текст; видеофайл целиком читать незачем.
            response.peekBody(256 * 1024).string()
        }
        return if (text.startsWith("#EXTM3U")) base to text.lines().map { it.trim() } else null
    }

    var (base, playlist) = lines(url) ?: return@withContext listOf(url)
    if (playlist.any { it.startsWith("#EXT-X-STREAM-INF") }) {
        val variant = playlist.lastOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { base.resolve(it) }
        val inner = variant?.let { lines(it.toString()) } ?: return@withContext emptyList()
        base = inner.first
        playlist = inner.second
    }
    playlist.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { base.resolve(it)?.toString() }
}

/** Итог замера: [mbps] = null, если данных не хватило; тогда [problem] — последняя ошибка, если она была. */
data class SpeedResult(val mbps: Double?, val bytes: Long, val files: Int, val problem: String?)

private const val MIN_BYTES = 1024 * 1024L
