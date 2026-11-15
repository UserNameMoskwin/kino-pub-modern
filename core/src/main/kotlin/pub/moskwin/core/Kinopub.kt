package pub.moskwin.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * Точка входа в API: вход, авторизованные запросы, перебор адресов.
 *
 * Типизированные методы каталога появятся после сверки формата с живыми ответами;
 * пока есть [get] и [post], возвращающие разобранный JSON.
 */
class Kinopub(
    credentials: ClientCredentials,
    store: TokenStore,
    val hosts: Hosts = Hosts(),
    http: OkHttpClient = OkHttpClient(),
    now: () -> Long = { System.currentTimeMillis() / 1000 },
    retryDelayMs: Long = 3_000,
) {
    private val transport = Transport(http, hosts, retryDelayMs)
    val auth = Auth(transport, credentials, store, now)

    /** GET `v1/<path>`. [failover] = false для переключателей вроде `watching/toggle`. */
    suspend fun get(
        path: String,
        query: Map<String, String?> = emptyMap(),
        failover: Boolean = true,
    ): JsonObject = authorized("v1/${path.trimStart('/')}", query, null, failover)

    suspend fun post(
        path: String,
        form: Map<String, String?> = emptyMap(),
        failover: Boolean = false,
    ): JsonObject = authorized("v1/${path.trimStart('/')}", emptyMap(), form, failover)

    /** Сообщает сервису название и версию устройства. Вызывать после входа и при каждом запуске. */
    suspend fun notifyDevice(title: String, hardware: String, software: String) {
        post("device/notify", mapOf("title" to title, "hardware" to hardware, "software" to software), failover = true)
    }

    private suspend fun authorized(
        path: String,
        query: Map<String, String?>,
        form: Map<String, String?>?,
        failover: Boolean,
    ): JsonObject {
        var token = auth.accessToken()
        var response = transport.send(path, query, form, token, failover)
        if (response.status == 401) {
            token = auth.refresh(staleAccessToken = token)
            response = transport.send(path, query, form, token, failover)
            if (response.status == 401) {
                auth.logoutLocally()
                throw LoginRequiredException("сервер отклонил новый токен")
            }
        }
        if (!response.ok) throw response.toException()
        return LenientJson.parseToJsonElement(response.body).jsonObject
    }
}
