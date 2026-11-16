package pub.moskwin.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Реплика субтитров: показывается с [startMs] до [endMs] от начала видео. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

private val TIMING = Regex("""(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
private val MARKUP = Regex("""<[^>]+>|\{[^}]*\}""")

/**
 * Разбирает субтитры SRT. Разметка (курсив, положение на экране) отбрасывается;
 * реплики без текста и с нечитаемым временем пропускаются. Результат отсортирован по началу.
 */
fun parseSrt(text: String): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>()
    val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var i = 0
    while (i < lines.size) {
        val match = TIMING.find(lines[i++]) ?: continue
        val (h1, m1, s1, f1, h2, m2, s2, f2) = match.destructured
        val body = StringBuilder()
        while (i < lines.size && lines[i].isNotBlank()) {
            // Следующая реплика без пустой строки перед ней: её номер и время текстом не считаются.
            if (TIMING.containsMatchIn(lines[i])) {
                if (body.endsWith('\n')) body.setLength(body.length - 1)
                val last = body.lastIndexOf('\n')
                if (body.substring(last + 1).trim().all { it.isDigit() }) body.setLength(maxOf(last, 0))
                break
            }
            body.append(MARKUP.replace(lines[i], "").trim()).append('\n')
            i++
        }
        val shown = body.toString().trim()
        if (shown.isNotEmpty()) cues += SubtitleCue(millis(h1, m1, s1, f1), millis(h2, m2, s2, f2), shown)
    }
    return cues.sortedBy { it.startMs }
}

private fun millis(h: String, m: String, s: String, fraction: String): Long =
    h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + fraction.padEnd(3, '0').toLong()

/** Текст, который должен быть на экране в момент [ms]; null — реплики нет. */
fun subtitleAt(cues: List<SubtitleCue>, ms: Long): String? {
    // Двоичный поиск последней реплики, начавшейся не позже момента; перекрытия редки и коротки.
    var low = 0
    var high = cues.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (cues[mid].startMs <= ms) low = mid + 1 else high = mid - 1
    }
    var shown: String? = null
    for (index in maxOf(high - 2, 0)..high) {
        val cue = cues[index]
        if (ms < cue.endMs) shown = if (shown == null) cue.text else shown + "\n" + cue.text
    }
    return shown
}

/** Скачивает и разбирает субтитры. Файлы сервиса — в UTF-8; старые встречаются в Windows-1251. */
suspend fun fetchSubtitles(url: String, http: OkHttpClient = OkHttpClient()): List<SubtitleCue> = withContext(Dispatchers.IO) {
    val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        response.body?.bytes() ?: ByteArray(0)
    }
    val text = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { String(bytes, Charset.forName("windows-1251")) }
    parseSrt(text)
}
