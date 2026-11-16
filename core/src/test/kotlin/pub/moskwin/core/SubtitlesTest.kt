package pub.moskwin.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitlesTest {
    private val srt = "﻿1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Привет,</i>\r\nмир\r\n\r\n" +
        "2\n00:00:03,000 --> 00:00:04,25\n{\\an8}Вторая\n3\n01:00:00.000 --> 01:00:02.000\nТретья\n\n" +
        "4\n01:00:05,000 --> 01:00:06,000\n\n"

    @Test fun `srt is parsed without markup, numbers and empty cues`() {
        assertEquals(
            listOf(
                SubtitleCue(1_500, 3_000, "Привет,\nмир"),
                SubtitleCue(3_000, 4_250, "Вторая"),
                SubtitleCue(3_600_000, 3_602_000, "Третья"),
            ),
            parseSrt(srt),
        )
    }

    @Test fun `cue is picked by time`() {
        val cues = parseSrt(srt)
        assertNull(subtitleAt(cues, 1_499))
        assertEquals("Привет,\nмир", subtitleAt(cues, 1_500))
        assertEquals("Вторая", subtitleAt(cues, 3_000))
        assertNull(subtitleAt(cues, 4_250))
        assertEquals("Третья", subtitleAt(cues, 3_601_000))
        assertNull(subtitleAt(emptyList(), 0))
    }
}
