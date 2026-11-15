package pub.moskwin.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient

/**
 * Точка входа в API: вход, авторизованные запросы, перебор адресов.
 *
 * Типизированные методы есть для того, что нужно главному экрану;
 * для остального — [get] и [post], возвращающие разобранный JSON.
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

    /** История просмотров, новые сверху. Сервер отдаёт не больше 50 строк на страницу. */
    suspend fun history(page: Int = 1): List<HistoryEntry> =
        get("history", mapOf("page" to "$page", "perpage" to "50")).list("history")

    /** Новинки типа `movie` / `serial`, по дате добавления. Не больше 50 на страницу. */
    suspend fun fresh(type: String, page: Int = 1): List<Item> =
        get("items/fresh", mapOf("type" to type, "page" to "$page", "perpage" to "50")).list("items")

    suspend fun bookmarkFolders(): List<BookmarkFolder> = get("bookmarks").list("items")

    suspend fun bookmarkItems(folderId: Int): List<Item> = get("bookmarks/$folderId").list("items")

    /** Отметки просмотра по всем сериям. */
    suspend fun watching(itemId: Int): WatchItem =
        get("watching", mapOf("id" to "$itemId")).value("item") ?: WatchItem(itemId)

    suspend fun device(): Device {
        val device = get("device/info")["device"]!!.jsonObject
        val servers = device["settings"]?.jsonObject?.get("serverLocation")?.jsonObject
        return Device(
            id = device["id"]!!.jsonPrimitive.int,
            servers = servers?.list<ServerOption>("value").orEmpty(),
        )
    }

    /** Выбор сервера пишется в настройки устройства на стороне сервиса. */
    suspend fun setServer(deviceId: Int, serverId: Int) {
        // kino.watch/api этот путь не обслуживает (404, проверено 2026-11-15), остальные адреса — да.
        authorized(
            "v1/device/$deviceId/settings", emptyMap(), mapOf("serverLocation" to "$serverId"),
            failover = true, skipHostOn = setOf(404),
        )
    }

    private inline fun <reified T> JsonObject.list(key: String): List<T> =
        value<List<T>>(key).orEmpty()

    private inline fun <reified T> JsonObject.value(key: String): T? =
        get(key)?.takeUnless { it is JsonNull }?.let { LenientJson.decodeFromJsonElement(serializer<T>(), it) }

    private suspend fun authorized(
        path: String,
        query: Map<String, String?>,
        form: Map<String, String?>?,
        failover: Boolean,
        skipHostOn: Set<Int> = emptySet(),
    ): JsonObject {
        var token = auth.accessToken()
        var response = transport.send(path, query, form, token, failover, skipHostOn)
        if (response.status == 401) {
            token = auth.refresh(staleAccessToken = token)
            response = transport.send(path, query, form, token, failover, skipHostOn)
            if (response.status == 401) {
                auth.logoutLocally()
                throw LoginRequiredException("сервер отклонил новый токен")
            }
        }
        if (!response.ok) throw response.toException()
        return LenientJson.parseToJsonElement(response.body).jsonObject
    }
}
