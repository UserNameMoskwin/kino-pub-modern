package pub.moskwin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    private val rusVoiceover = AudioTrack(1, "rus", AudioKind.VOICEOVER)
    private val rusDub = AudioTrack(2, "rus", AudioKind.DUB)
    private val engOriginal = AudioTrack(3, "eng", AudioKind.ORIGINAL)
    private val audios = listOf(rusVoiceover, rusDub, engOriginal)

    private val rusForced = SubtitleTrack(1, "rus", forced = true)
    private val engSubs = SubtitleTrack(2, "eng")
    private val rusSubs = SubtitleTrack(3, "rus")
    private val subtitles = listOf(rusForced, engSubs, rusSubs)

    @Test fun `kids get russian dub without subtitles`() {
        assertEquals(TrackChoice(2, null), defaultTracks(audios, subtitles, kids = true))
    }

    @Test fun `kids fall back to any russian track, then to the first one`() {
        assertEquals(TrackChoice(1, null), defaultTracks(listOf(rusVoiceover, engOriginal), subtitles, kids = true))
        assertEquals(TrackChoice(3, null), defaultTracks(listOf(engOriginal), subtitles, kids = true))
    }

    @Test fun `adults get original with first non-forced russian subtitles`() {
        assertEquals(TrackChoice(3, 3), defaultTracks(audios, subtitles, kids = false))
    }

    @Test fun `non-english original is still original with russian subtitles`() {
        val korean = AudioTrack(4, "kor", AudioKind.ORIGINAL)
        assertEquals(TrackChoice(4, 3), defaultTracks(listOf(rusDub, korean), subtitles, kids = false))
    }

    @Test fun `without russian subtitles adults get russian audio`() {
        assertEquals(TrackChoice(2, null), defaultTracks(audios, listOf(rusForced, engSubs), kids = false))
    }

    @Test fun `without russian subtitles and russian audio original stays`() {
        assertEquals(TrackChoice(3, null), defaultTracks(listOf(engOriginal), listOf(engSubs), kids = false))
    }

    @Test fun `without original track adults get russian audio`() {
        assertEquals(TrackChoice(2, null), defaultTracks(listOf(rusVoiceover, rusDub), subtitles, kids = false))
    }

    @Test fun `russian original needs no subtitles`() {
        val rusOriginal = AudioTrack(5, "rus", AudioKind.ORIGINAL)
        assertEquals(TrackChoice(5, null), defaultTracks(listOf(rusOriginal), subtitles, kids = false))
    }

    @Test fun `no tracks at all`() {
        assertEquals(TrackChoice(null, null), defaultTracks(emptyList(), emptyList(), kids = false))
    }

    @Test fun `kids classification`() {
        assertTrue(isKids(cartoonGenre = true, anime = false, inNellieFolder = false, markedNotKids = false))
        assertFalse(isKids(cartoonGenre = true, anime = true, inNellieFolder = false, markedNotKids = false))
        assertFalse(isKids(cartoonGenre = true, anime = false, inNellieFolder = false, markedNotKids = true))
        assertFalse(isKids(cartoonGenre = false, anime = false, inNellieFolder = false, markedNotKids = false))
        assertTrue(isKids(cartoonGenre = false, anime = true, inNellieFolder = true, markedNotKids = true))
    }
}
