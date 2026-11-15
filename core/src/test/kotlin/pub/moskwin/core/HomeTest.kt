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
