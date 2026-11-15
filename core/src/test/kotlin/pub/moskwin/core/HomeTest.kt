package pub.moskwin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeTest {
    private fun item(id: Int, vararg genres: Int, rating: Double? = null, votes: Int? = null) =
        Item(id, genres = genres.map { Genre(it) }, imdbRating = rating, imdbVotes = votes)

    private fun entry(id: Int, season: Int, episode: Int, time: Double, duration: Double, lastSeen: Long = 0) =
        HistoryEntry(time, lastSeen, item(id), HistoryMedia(number = episode, snumber = season, duration = duration))

    private fun watch(vararg seasons: Pair<Int, List<Int>>) = WatchItem(
        1,
        seasons.map { (number, statuses) ->
            WatchSeason(number, statuses.mapIndexed { i, s -> WatchEpisode(number = i + 1, duration = 100.0, status = s) })
        },
    )

    @Test fun `history keeps the latest row per item`() {
        val rows = listOf(entry(1, 1, 1, 0.0, 1.0, lastSeen = 10), entry(2, 1, 1, 0.0, 1.0, lastSeen = 30), entry(1, 1, 2, 0.0, 1.0, lastSeen = 20))
        assertEquals(listOf(2 to 1, 1 to 2), latestPerItem(rows).map { it.item.id to it.media.number })
    }

    @Test fun `unfinished movie stays, finished one goes`() {
        assertEquals(0.5f, continueCard(entry(1, 0, 1, 50.0, 100.0), null)?.progress)
        assertNull(continueCard(entry(1, 0, 1, 95.0, 100.0), null))
    }

    @Test fun `unfinished episode is shown as is`() {
        val card = continueCard(entry(1, 2, 3, 40.0, 100.0), watch(2 to listOf(1, 1, 0, -1)))
        assertEquals(Triple(2, 3, 0.4f), Triple(card?.season, card?.episode, card?.progress))
    }

    @Test fun `finished episode moves to the next unwatched one, across seasons`() {
        val w = watch(1 to listOf(1, 1), 2 to listOf(1, -1))
        val card = continueCard(entry(1, 1, 2, 100.0, 100.0), w)
        assertEquals(Triple(2, 2, 0f), Triple(card?.season, card?.episode, card?.progress))
    }

    @Test fun `finished serial leaves the row`() {
        assertNull(continueCard(entry(1, 1, 2, 100.0, 100.0), watch(1 to listOf(-1, 1))))
    }

    @Test fun `finished episode without watch data shows the last one`() {
        assertEquals(2, continueCard(entry(1, 1, 2, 100.0, 100.0), null)?.episode)
    }

    @Test fun `fresh row drops cartoons, kids and low vote counts, sorts by imdb`() {
        val items = listOf(
            item(1, 9, rating = 6.0, votes = 5000),
            item(2, 9, rating = 8.0, votes = 2000),
            item(3, Item.GENRE_CARTOON, rating = 9.0, votes = 9000),
            item(4, Item.GENRE_CARTOON, Item.GENRE_ANIME, rating = 7.0, votes = 3000),
            item(5, 9, rating = 9.5, votes = 20),
            item(6, 9, rating = 9.9, votes = 9000),
            item(7, 9),
            item(2, 9, rating = 8.0, votes = 2000),
        )
        assertEquals(listOf(2, 4, 1), rankFresh(items) { it.id == 6 }.map { it.id })
    }

    private fun details(vararg statuses: Int) = Details(
        item(1), "", "", "", emptyList(), ItemDuration(),
        seasons = listOf(
            Season(1, episodes = statuses.take(2).mapIndexed { i, s -> Video(number = i + 1, duration = 100.0, watching = Watching(s, 50.0)) }),
            Season(2, episodes = statuses.drop(2).mapIndexed { i, s -> Video(number = i + 1, snumber = 2, duration = 100.0, watching = Watching(s, 50.0)) }),
        ),
        videos = emptyList(),
    )

    @Test fun `resume point is the started episode, then the next one, then the first`() {
        assertEquals(1 to 0, details(-1, -1, -1).resumePoint()?.let { it.number to it.snumber })
        assertEquals(2 to 0, details(1, 0, -1).resumePoint()?.let { it.number to it.snumber })
        assertEquals(1 to 2, details(1, 1, -1).resumePoint()?.let { it.number to it.snumber })
        assertEquals(1 to 0, details(1, 1, 1).resumePoint()?.let { it.number to it.snumber })
        assertEquals(0.5f, details(1, 0, -1).resumePoint()?.progress)
        // Пропущенная серия посреди просмотренных: продолжаем её, а не начинаем сначала.
        assertEquals(2 to 0, details(1, 0, 1, 1).resumePoint()?.let { it.number to it.snumber })
    }

    @Test fun `resume point follows the most recently watched episode when marks are known`() {
        fun marks(vararg e: Pair<Int, Long>) = WatchItem(1, listOf(
            WatchSeason(1, e.take(2).mapIndexed { i, (s, u) -> WatchEpisode(number = i + 1, status = s, updated = u) }),
            WatchSeason(2, e.drop(2).mapIndexed { i, (s, u) -> WatchEpisode(number = i + 1, status = s, updated = u) }),
        ))
        val card = details(0, 1, 0, -1)
        // Последней смотрели начатую серию 1 первого сезона, хотя во втором тоже есть начатая.
        assertEquals(1 to 0, card.resumePoint(marks(0 to 30L, 1 to 20L, 0 to 10L, -1 to 0L))?.let { it.number to it.snumber })
        // Последняя просмотренная досмотрена — следующая непросмотренная после неё.
        assertEquals(1 to 2, card.resumePoint(marks(0 to 5L, 1 to 20L, 0 to 10L, -1 to 0L))?.let { it.number to it.snumber })
        // После неё всё просмотрено — с начала.
        assertEquals(1 to 0, card.resumePoint(marks(1 to 5L, 1 to 9L, 1 to 8L, 1 to 30L))?.let { it.number to it.snumber })
    }

    private fun audio(index: Int, lang: String, type: String?, author: Int? = null, codec: String = "aac") =
        MediaAudio(index, codec, 6, lang, type?.let { NamedRef(it.hashCode(), it) }, author?.let { NamedRef(it, "a$it") })

    private val odyssey = listOf(
        audio(1, "rus", "Многоголосый", 15), audio(2, "rus", "Дубляж", 3), audio(8, "eng", "Оригинал"),
        audio(9, "rus", "Многоголосый", 15, codec = "ac3"),
    )
    private val subs = listOf(MediaSubtitle("eng"), MediaSubtitle("rus", forced = true), MediaSubtitle("rus"))

    @Test fun `adults get the original with russian subtitles, kids get the dub`() {
        assertEquals(8 to 2, pickTracks(odyssey, subs, kids = false).let { it.audio?.index to it.subtitle })
        assertEquals(2 to null, pickTracks(odyssey, subs, kids = true).let { it.audio?.index to it.subtitle })
        // Русских субтитров нет — русская озвучка: дубляж, если есть.
        assertEquals(2 to null, pickTracks(odyssey, listOf(MediaSubtitle("eng")), kids = false).let { it.audio?.index to it.subtitle })
    }

    @Test fun `manual choice for the serial wins, and falls back to the rule when the track is missing`() {
        val voiceover = odyssey[0].signature
        assertEquals(1 to null, pickTracks(odyssey, subs, false, savedAudio = voiceover, savedSubtitle = "off").let { it.audio?.index to it.subtitle })
        assertEquals(1 to 0, pickTracks(odyssey, subs, false, savedAudio = voiceover, savedSubtitle = "eng|false").let { it.audio?.index to it.subtitle })
        assertEquals(9, pickTracks(odyssey, subs, false, preferAc3 = true, savedAudio = voiceover, savedSubtitle = "off").audio?.index)
        assertEquals(8 to 2, pickTracks(odyssey, subs, false, savedAudio = "jpn|1|1", savedSubtitle = "off").let { it.audio?.index to it.subtitle })
    }

    @Test fun `untyped foreign track counts as the original and stream type picks the url`() {
        assertEquals(AudioKind.ORIGINAL, audio(1, "eng", null).kind)
        assertEquals(AudioKind.VOICEOVER, audio(1, "rus", null).kind)
        val links = MediaLinks(listOf(MediaFile("480p", 406, url = MediaUrls("h0", "l0", "l20", "l40")), MediaFile("2160p", 2160, url = MediaUrls("h", "l", "l2", "l4"))))
        assertEquals("l4", links.streamUrl("HLS4"))
        assertEquals("l4", links.streamUrl(null))
        assertEquals("h", links.streamUrl("http"))
        assertEquals("l2", links.streamUrl("HLS2"))
    }

    @Test fun `live history row decodes`() {
        val json = """{"time":437,"counter":0,"first_seen":1,"last_seen":1794757699,
            "item":{"id":63673,"type":"serial","subtype":"","title":"Блуи / Bluey","year":2019,"voice":null,
              "genres":[{"id":23,"title":"Мультфильм"}],"imdb_rating":9.2,"imdb_votes":null,"kinopoisk_rating":8.9,
              "posters":{"small":"s","medium":"m","big":"b","wide":"w"}},
            "media":{"id":816220,"number":2,"snumber":2,"title":"Hammerbarn","duration":437,"subtitles":[{"lang":"eng","forced":false}]}}"""
        val row = LenientJson.decodeFromString(HistoryEntry.serializer(), json)
        assertEquals("Блуи", row.item.name)
        assertEquals("w", row.item.posters.wide)
        assertNull(row.item.imdbVotes)
        assertEquals(2, row.media.snumber)
        assertEquals(row, HomeData.fromJson(HomeData(continueKids = listOf(ContinueCard(row.item, 2, 2, 1f))).toJson())!!
            .continueKids.single().let { HistoryEntry(row.time, row.lastSeen, it.item, row.media) })
    }
}
