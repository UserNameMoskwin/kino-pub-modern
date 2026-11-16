package pub.moskwin.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Список адресов API с запоминанием рабочего.
 *
 * Адрес включает префикс пути, если он есть: у `kino.watch` API лежит под `/api`,
 * у старых зеркал — в корне. Дальше пути одинаковые: `v1/...` и `oauth2/...`.
 */
class Hosts(bases: List<String> = DEFAULT, startIndex: Int = 0) {
    private val bases: List<HttpUrl> = bases.map { it.trimEnd('/').plus("/").toHttpUrl() }

    init {
        require(this.bases.isNotEmpty()) { "нужен хотя бы один адрес API" }
    }

    @Volatile
    private var current: Int = startIndex.coerceIn(0, this.bases.size - 1)

    /** Индекс рабочего адреса — его стоит сохранять между запусками. */
    val currentIndex: Int get() = current

    /** Адреса в порядке попыток: сначала текущий рабочий, потом остальные по кругу. */
    fun candidates(): List<IndexedBase> =
        List(bases.size) { i -> (current + i) % bases.size }.map { IndexedBase(it, bases[it]) }

    fun markWorking(index: Int) {
        current = index
    }

    data class IndexedBase(val index: Int, val url: HttpUrl) {
        fun resolve(path: String): HttpUrl.Builder =
            url.newBuilder().addPathSegments(path.trimStart('/'))
    }

    companion object {
        /**
         * Первый адрес проверен 2026-11-15 (отвечает 401 без токена).
         * Остальные взяты из сторонних клиентов и вживую не проверялись.
         */
        val DEFAULT = listOf(
            "https://kino.watch/api",
            "https://api.srvkp.com",
            "https://api.service-kp.com",
        )
    }
}
