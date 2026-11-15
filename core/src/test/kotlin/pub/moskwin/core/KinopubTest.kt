package pub.moskwin.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KinopubTest {
    private val servers = mutableListOf<MockWebServer>()
    private var clock = 1_000L
    private val creds = ClientCredentials("cid", "csecret")

    private fun server() = MockWebServer().also { it.start(); servers += it }
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun client(store: TokenStore, vararg bases: String) =
        Kinopub(creds, store, Hosts(bases.toList()), now = { clock }, retryDelayMs = 1)

    private fun MockWebServer.base(prefix: String = "") = url(prefix).toString()

    @AfterTest fun tearDown() = servers.forEach { runCatching { it.shutdown() } }

    @Test fun `device flow stores tokens and keeps api prefix`() = runTest {
        val s = server()
        s.enqueue(json("""{"code":"dc","user_code":"ABCDEF","verification_uri":"https://kino.pub/device","expires_in":300,"interval":5}"""))
        s.enqueue(json("""{"error":"authorization_pending"}""", 400))
        s.enqueue(json("""{"access_token":"a1","refresh_token":"r1","expires_in":3600,"token_type":"bearer"}"""))
        val store = InMemoryTokenStore()
        val kp = client(store, s.base("/api"))

        val code = kp.auth.requestDeviceCode()
        assertEquals("ABCDEF", code.userCode)
        assertFalse(kp.auth.pollOnce(code))
        assertTrue(kp.auth.pollOnce(code))
        assertEquals(Tokens("a1", "r1", 4_600), store.load())

        val first = s.takeRequest()
        assertEquals("/api/oauth2/device", first.path)
        assertEquals("grant_type=device_code&client_id=cid&client_secret=csecret", first.body.readUtf8())
        s.takeRequest()
        assertTrue(s.takeRequest().body.readUtf8().contains("grant_type=device_token") )
    }

    @Test fun `fatal device flow error is thrown`() = runTest {
        val s = server()
        s.enqueue(json("""{"error":"invalid_client","error_description":"The client credentials are invalid"}""", 400))
        val e = assertFailsWith<ApiException> { client(InMemoryTokenStore(), s.base()).auth.requestDeviceCode() }
        assertEquals("invalid_client", e.error)
    }

    @Test fun `falls over to next host and remembers it`() = runTest {
        val dead = server().also { it.shutdown() }
        val bad = server()
        val good = server()
        bad.enqueue(json("gateway", 502))
        good.enqueue(json("""{"status":200,"user":{"username":"u"}}"""))
        good.enqueue(json("""{"status":200}"""))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), dead.base(), bad.base(), good.base("/api"))

        kp.get("user")
        assertEquals(2, kp.hosts.currentIndex)
        kp.get("types")
        assertEquals(1, bad.requestCount)
        assertEquals("/api/v1/user", good.takeRequest().path)
        assertEquals("Bearer a", good.takeRequest().getHeader("Authorization"))
    }

    @Test fun `all hosts down reports no host available`() = runTest {
        val dead = server().also { it.shutdown() }
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), dead.base())
        assertFailsWith<NoHostAvailableException> { kp.get("user") }
    }

    @Test fun `expired token is refreshed before the request`() = runTest {
        val s = server()
        s.enqueue(json("""{"access_token":"a2","refresh_token":"r2","expires_in":3600}"""))
        s.enqueue(json("""{"status":200}"""))
        val store = InMemoryTokenStore(Tokens("a1", "r1", expiresAt = 1_030))
        client(store, s.base()).get("user")

        assertEquals("/oauth2/token", s.takeRequest().path)
        assertEquals("Bearer a2", s.takeRequest().getHeader("Authorization"))
        assertEquals(Tokens("a2", "r2", 4_600), store.load())
    }

    @Test fun `401 triggers one refresh and retry`() = runTest {
        val s = server()
        s.enqueue(json("""{"status":401,"name":"Unauthorized"}""", 401))
        s.enqueue(json("""{"access_token":"a2","refresh_token":"r2","expires_in":3600}"""))
        s.enqueue(json("""{"status":200,"ok":1}"""))
        val store = InMemoryTokenStore(Tokens("a1", "r1", 9_999))
        val result = client(store, s.base()).get("user")

        assertEquals("1", result["ok"]!!.jsonPrimitive.content)
        assertEquals(3, s.requestCount)
        assertEquals("r2", store.load()!!.refreshToken)
    }

    @Test fun `rejected refresh logs out`() = runTest {
        val s = server()
        s.enqueue(json("""{"error":"invalid_grant"}""", 400))
        val store = InMemoryTokenStore(Tokens("a1", "r1", expiresAt = 0))
        assertFailsWith<LoginRequiredException> { client(store, s.base()).get("user") }
        assertNull(store.load())
    }

    @Test fun `network failure during refresh keeps the session`() = runTest {
        val s = server()
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val other = server()
        val tokens = Tokens("a1", "r1", expiresAt = 0)
        val store = InMemoryTokenStore(tokens)
        assertFailsWith<java.io.IOException> { client(store, s.base(), other.base()).get("user") }
        assertEquals(tokens, store.load())
        assertEquals(0, other.requestCount)
    }

    @Test fun `429 is retried`() = runTest {
        val s = server()
        s.enqueue(json("{}", 429))
        s.enqueue(json("""{"status":200}"""))
        client(InMemoryTokenStore(Tokens("a", "r", 9_999)), s.base()).get("items")
        assertEquals(2, s.requestCount)
    }

    @Test fun `toggle is sent once and only to the current host`() = runTest {
        val bad = server()
        val other = server()
        bad.enqueue(json("gateway", 503))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), bad.base(), other.base())
        assertFailsWith<java.io.IOException> { kp.get("watching/togglewatchlist", mapOf("id" to "1"), failover = false) }
        assertEquals(1, bad.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test fun `server switch skips a host without the settings path and keeps the main host`() = runTest {
        val main = server()
        val spare = server()
        main.enqueue(json("<html>not found</html>", 404))
        spare.enqueue(json("""{"status":200}"""))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), main.base(), spare.base())
        kp.setServer(deviceId = 7, serverId = 3)
        val sent = spare.takeRequest()
        assertEquals("/v1/device/7/settings", sent.path)
        assertEquals("serverLocation=3", sent.body.readUtf8())
        assertEquals(0, kp.hosts.currentIndex)
    }

    @Test fun `card decodes seasons and kids folder is created on first add`() = runTest {
        val s = server()
        s.enqueue(json("""{"status":200,"item":{"id":9,"type":"serial","title":"Блуи / Bluey","year":2019,"plot":"Описание",
            "genres":[{"id":23,"title":"Мультфильм"}],"countries":[{"id":3,"title":"Австралия"}],"duration":{"average":437,"total":900},
            "seasons":[{"number":1,"episodes":[{"id":1,"number":1,"title":"Эпизод","thumbnail":"t","duration":437,
              "watched":1,"watching":{"status":1,"time":437}},{"id":2,"number":2,"duration":400,"watching":{"status":0,"time":100}}]}]}}"""))
        s.enqueue(json("""{"status":200,"items":[{"id":1,"title":"посмотрю"}]}"""))
        s.enqueue(json("""{"status":200,"folder":{"id":77,"title":"Нелли"}}"""))
        s.enqueue(json("""{"status":200}"""))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), s.base())

        val card = kp.details(9)
        assertEquals("/v1/items/9?nolinks=1", s.takeRequest().path)
        assertTrue(card.isSerial && card.item.isCartoon)
        assertEquals("Австралия", card.countries.single().title)
        assertEquals(2, card.resumePoint()?.number)
        assertEquals(0.25f, card.resumePoint()?.progress)

        setInKidsFolder(kp, itemId = 9, inFolder = true)
        s.takeRequest()
        assertEquals("title=%D0%9D%D0%B5%D0%BB%D0%BB%D0%B8", s.takeRequest().body.readUtf8())
        val add = s.takeRequest()
        assertEquals("/v1/bookmarks/add", add.path)
        assertEquals("item=9&folder=77", add.body.readUtf8())
    }

    @Test fun `speed test expands a master playlist into segments and measures them`() = runTest {
        val s = server()
        s.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=9\nhigh/index.m3u8\n"))
        s.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXTINF:4,\nseg2.ts\n#EXT-X-ENDLIST\n"))
        val segments = mediaSegments(s.url("/t/master.m3u8").toString())
        assertEquals(listOf("/t/high/seg1.ts", "/t/high/seg2.ts"), segments.map { java.net.URI(it).path })
        assertEquals("/t/high/index.m3u8", s.takeRequest().let { s.takeRequest().path })

        repeat(2) { s.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(900_000)))) }
        val result = measureSpeed(segments)
        assertTrue(result.mbps != null && result.mbps!! > 0, "скорость должна посчитаться: $result")
        assertNull(result.problem)
    }

    @Test fun `catalog sends only the chosen filters and reads page count`() = runTest {
        val s = server()
        s.enqueue(json("""{"status":200,"items":[{"id":7,"title":"А / A"}],"pagination":{"total":12,"current":2,"perpage":48,"total_items":560}}"""))
        s.enqueue(json("""{"status":200,"user":{"username":"u","reg_date":5,"subscription":{"active":true,"end_time":9,"days":170.9}}}"""))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), s.base())
        val page = kp.items(CatalogQuery(type = "serial", sort = "imdb_rating-", genre = 9, year = "2020-2023"), page = 2)
        assertEquals("/v1/items?type=serial&sort=imdb_rating-&genre=9&year=2020-2023&page=2&perpage=48", s.takeRequest().path)
        assertEquals(Triple(7, 12, 560), Triple(page.items.single().id, page.pages, page.total))
        assertEquals(User("u", 5, Subscription(true, 9, 170.9)), kp.user())
    }

    @Test fun `home rows decode from typed endpoints`() = runTest {
        val s = server()
        s.enqueue(json("""{"status":200,"device":{"id":5,"title":"ТВ","settings":{
            "supportSsl":{"value":1,"label":"Использовать SSL"},"support4k":{"value":0,"label":"Поддержка 4K"},
            "serverLocation":{"type":"list","value":[
              {"id":1,"label":"Нидерланды","selected":1},{"id":3,"label":"Россия","selected":0}]},
            "streamingType":{"type":"list","value":[{"id":4,"label":"HLS4","selected":1}]}}}}"""))
        s.enqueue(json("""{"status":200,"items":[{"id":1,"title":"посмотрю","count":83,"posters":null}]}"""))
        s.enqueue(json("""{"status":200,"item":{"id":9,"seasons":[{"number":1,"episodes":[{"number":1,"duration":437,"time":437,"status":1}]}]}}"""))
        val kp = client(InMemoryTokenStore(Tokens("a", "r", 9_999)), s.base())
        assertEquals(
            Device(
                5, "ТВ",
                servers = listOf(ServerOption(1, "Нидерланды", 1), ServerOption(3, "Россия", 0)),
                streams = listOf(ServerOption(4, "HLS4", 1)),
                toggles = listOf(DeviceToggle("supportSsl", "Использовать SSL", true), DeviceToggle("support4k", "Поддержка 4K", false)),
            ),
            kp.device(),
        )
        assertEquals(listOf(BookmarkFolder(1, "посмотрю", 83)), kp.bookmarkFolders())
        assertEquals(1, kp.watching(9).seasons.single().episodes.single().status)
    }
}
