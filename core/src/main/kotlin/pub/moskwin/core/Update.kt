package pub.moskwin.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Выпущенная версия приложения — файл `apk/version.json` в репозитории. Из него же сборка
 * берёт свой номер, поэтому сравнивать достаточно [versionCode].
 */
@Serializable
data class AppVersion(
    val versionCode: Int,
    val versionName: String = "",
    val notes: String = "",
    /** Имя APK рядом с файлом версии: в нём стоит номер версии, поэтому оно меняется с каждым выпуском. */
    val file: String = DEFAULT_APK,
) {
    /** Имя файла из [file], если это простое имя APK без путей; иначе — имя по умолчанию. */
    val apkName: String get() = file.takeIf { APK_NAME.matches(it) } ?: DEFAULT_APK
}

/** Файл, который качали версии до 0.5: в них имя было зашито. */
const val DEFAULT_APK = "kino-pub.apk"
private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

fun parseAppVersion(json: String): AppVersion? =
    runCatching { LenientJson.decodeFromString(AppVersion.serializer(), json) }.getOrNull()?.takeIf { it.versionCode > 0 }

/** Читает описание выпущенной версии по адресу [url]. */
suspend fun fetchAppVersion(url: String, http: OkHttpClient = OkHttpClient()): AppVersion = withContext(Dispatchers.IO) {
    http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        parseAppVersion(response.body?.string().orEmpty()) ?: throw IOException("файл версии не разобран")
    }
}

/**
 * Скачивает файл по адресу [url] в [target]. [onProgress] получает долю скачанного, 0..1;
 * если сервер не сообщил размер, не вызывается. Недокачанный файл не остаётся.
 */
suspend fun downloadFile(url: String, target: File, http: OkHttpClient = OkHttpClient(), onProgress: (Float) -> Unit = {}) =
    withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("пустой ответ")
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    val source = body.byteStream()
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (total > 0 && done != total) throw IOException("файл скачан не полностью")
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("не удалось сохранить файл")
        } finally {
            part.delete()
        }
    }
