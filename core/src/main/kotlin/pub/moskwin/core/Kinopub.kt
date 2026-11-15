package pub.moskwin.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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

    /** Для замеров скорости: вызывается после каждого запроса (путь, код, мс, размер ответа). */
    var onRequest: ((path: String, status: Int, ms: Long, size: Int) -> Unit)?
        get() = transport.onRequest
        set(value) { transport.onRequest = value }

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

    /** Папки закладок, в которых лежит фильм или сериал. */
    suspend fun itemFolders(itemId: Int): List<BookmarkFolder> =
        get("bookmarks/get-item-folders", mapOf("item" to "$itemId")).list("folders")

    suspend fun createFolder(title: String): BookmarkFolder =
        post("bookmarks/create", mapOf("title" to title)).value("folder")
            ?: throw ApiException(200, "bad_response", "сервер не вернул созданную папку")

    suspend fun addBookmark(itemId: Int, folderId: Int) {
        post("bookmarks/add", mapOf("item" to "$itemId", "folder" to "$folderId"))
    }

    suspend fun removeBookmark(itemId: Int, folderId: Int) {
        post("bookmarks/remove-item", mapOf("item" to "$itemId", "folder" to "$folderId"))
    }

    /** Карточка без ссылок на видео: у больших сериалов они занимают почти весь ответ. */
    suspend fun details(itemId: Int): Details {
        val json = get("items/$itemId", mapOf("nolinks" to "1"))["item"]
            ?: throw ApiException(200, "bad_response", "в ответе нет item")
        val item = LenientJson.decodeFromJsonElement(Item.serializer(), json)
        val extra = LenientJson.decodeFromJsonElement(ItemExtra.serializer(), json)
        return Details(
            item, extra.plot, extra.cast, extra.director, extra.countries,
            extra.duration, extra.seasons, extra.videos,
        )
    }

    /** Ссылки на видео и субтитры для серии или фильма ([mediaId] — `Video.id`). */
    suspend fun mediaLinks(mediaId: Int): MediaLinks =
        LenientJson.decodeFromJsonElement(MediaLinks.serializer(), get("items/media-links", mapOf("mid" to "$mediaId")))

    /**
     * Запоминает, на какой секунде остановились. [video] — номер серии (у фильма 1),
     * [season] — номер сезона, у фильмов не передаётся.
     */
    suspend fun markTime(itemId: Int, video: Int, season: Int?, seconds: Int) {
        get(
            "watching/marktime",
            mapOf("id" to "$itemId", "video" to "$video", "time" to "$seconds", "season" to season?.takeIf { it > 0 }?.toString()),
        )
    }

    /** Поиск по названию, режиссёру и актёрам. Сервер требует не меньше трёх символов. */
    suspend fun search(query: String): List<Item> =
        get("items/search", mapOf("q" to query, "perpage" to "40")).list("items")

    /** Отметки просмотра по всем сериям. */
    suspend fun watching(itemId: Int): WatchItem =
        get("watching", mapOf("id" to "$itemId")).value("item") ?: WatchItem(itemId)

    suspend fun device(): Device {
        val device = get("device/info")["device"]!!.jsonObject
        val settings = device["settings"]?.takeUnless { it is JsonNull }?.jsonObject.orEmpty()
        return Device(
            id = device["id"]!!.jsonPrimitive.int,
            title = device["title"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty(),
            servers = settings[Device.SERVER]?.jsonObject?.list<ServerOption>("value").orEmpty(),
            streams = settings[Device.STREAM]?.jsonObject?.list<ServerOption>("value").orEmpty(),
            // Всё, что не список, — переключатели со значением 0/1.
            toggles = settings.mapNotNull { (key, value) ->
                val setting = value as? JsonObject ?: return@mapNotNull null
                val flag = (setting["value"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                DeviceToggle(key, (setting["label"] as? JsonPrimitive)?.content.orEmpty(), flag == 1)
            },
        )
    }

    /** Выбор сервера пишется в настройки устройства на стороне сервиса. */
    suspend fun setServer(deviceId: Int, serverId: Int) = setDeviceSetting(deviceId, Device.SERVER, "$serverId")

    /**
     * Меняет одну настройку устройства на стороне сервиса: [Device.SERVER] и [Device.STREAM]
     * принимают id варианта, переключатели — `1` или `0`.
     */
    suspend fun setDeviceSetting(deviceId: Int, key: String, value: String) {
        // kino.watch/api этот путь не обслуживает (404, проверено 2026-11-15), остальные адреса — да.
        authorized(
            "v1/device/$deviceId/settings", emptyMap(), mapOf(key to value),
            failover = true, skipHostOn = setOf(404),
        )
    }

    /** Отвязывает это устройство от аккаунта: токены перестают действовать, нужен новый вход по коду. */
    suspend fun unlinkDevice() {
        authorized("v1/device/unlink", emptyMap(), emptyMap(), failover = true, skipHostOn = setOf(404))
        auth.logoutLocally()
    }

    /** Адрес трейлера: большой файл на видеосервере, по которому меряется скорость. */
    suspend fun trailerUrl(itemId: Int): String? =
        (get("items/$itemId", mapOf("nolinks" to "1"))["item"] as? JsonObject)
            ?.get("trailer")?.let { it as? JsonObject }?.get("url")?.let { it as? JsonPrimitive }?.content

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
