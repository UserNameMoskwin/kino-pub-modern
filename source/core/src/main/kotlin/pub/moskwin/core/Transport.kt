package pub.moskwin.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal class RawResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299

    fun toException(): ApiException {
        val obj = runCatching { LenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        fun field(name: String) = obj?.get(name)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        return ApiException(
            status = status,
            error = field("error") ?: field("name"),
            message = field("error_description") ?: field("message"),
        )
    }
}

/**
 * Отправка запросов с перебором адресов.
 *
 * Переходит к следующему адресу при сетевой ошибке и при ответах 502/503/504.
 * Запросы с [failover] = false (переключатели, повтор которых отменяет действие)
 * идут только на текущий адрес и не повторяются.
 */
internal class Transport(
    private val http: OkHttpClient,
    private val hosts: Hosts,
    private val retryDelayMs: Long = 3_000,
) {
    /** Наблюдатель за запросами: путь, код ответа, миллисекунды, размер тела в символах. */
    @Volatile
    var onRequest: ((path: String, status: Int, ms: Long, size: Int) -> Unit)? = null

    suspend fun send(
        path: String,
        query: Map<String, String?> = emptyMap(),
        form: Map<String, String?>? = null,
        bearer: String? = null,
        failover: Boolean = true,
        /**
         * Ответы, при которых тоже пробуем следующий адрес: путь, который один из
         * адресов не обслуживает. Рабочим такой адрес после успеха не запоминается.
         */
        skipHostOn: Set<Int> = emptySet(),
    ): RawResponse {
        val candidates = hosts.candidates().let { if (failover) it else it.take(1) }
        var lastError: Throwable? = null
        for (base in candidates) {
            val url = base.resolve(path).apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }.build()
            val request = Request.Builder().url(url).apply {
                header("Accept", "application/json")
                if (bearer != null) header("Authorization", "Bearer $bearer")
                if (form != null) {
                    post(FormBody.Builder().apply {
                        form.forEach { (k, v) -> if (v != null) add(k, v) }
                    }.build())
                }
            }.build()
            try {
                val began = System.nanoTime()
                val response = executeWithRateLimit(request, retry = failover)
                onRequest?.invoke(path, response.status, (System.nanoTime() - began) / 1_000_000, response.body.length)
                if (response.status in GATEWAY_ERRORS || response.status in skipHostOn) {
                    lastError = response.toException()
                    continue
                }
                if (skipHostOn.isEmpty()) hosts.markWorking(base.index)
                return response
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (!failover && lastError is IOException) throw lastError
        throw NoHostAvailableException(lastError)
    }

    private suspend fun executeWithRateLimit(request: Request, retry: Boolean): RawResponse {
        var attempt = 0
        while (true) {
            val response = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
            }
            if (response.status != 429 || !retry || attempt >= MAX_RATE_LIMIT_RETRIES) return response
            attempt++
            delay(retryDelayMs)
        }
    }

    private companion object {
        val GATEWAY_ERRORS = setOf(502, 503, 504)
        const val MAX_RATE_LIMIT_RETRIES = 2
    }
}
