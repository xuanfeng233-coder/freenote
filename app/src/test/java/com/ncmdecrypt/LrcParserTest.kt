package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test fun parsesSingleLineCentiseconds() {
        val lines = LrcParser.parse("[00:12.34]hello")
        assertEquals(1, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("hello", lines[0].text)
    }

    @Test fun expandsMultipleTimestampsOnOneLine() {
        val lines = LrcParser.parse("[00:01.00][00:05.00]词")
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals(5000L, lines[1].timeMs)
        assertEquals("词", lines[0].text)
        assertEquals("词", lines[1].text)
    }

    @Test fun handlesAllTimestampPrecisions() {
        assertEquals(62000L, LrcParser.parse("[01:02]x")[0].timeMs)        // no fraction
        assertEquals(62500L, LrcParser.parse("[01:02.5]x")[0].timeMs)      // tenths
        assertEquals(62500L, LrcParser.parse("[01:02.50]x")[0].timeMs)     // hundredths
        assertEquals(62500L, LrcParser.parse("[01:02.500]x")[0].timeMs)    // millis
    }

    @Test fun skipsIdTagAndBlankLines() {
        val lrc = "[ti:歌名]\n[ar:歌手]\n[by:某人]\n\n[00:00.00]第一句"
        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("第一句", lines[0].text)
    }

    @Test fun sortsByTime() {
        val lines = LrcParser.parse("[00:05.00]b\n[00:01.00]a")
        assertEquals("a", lines[0].text)
        assertEquals("b", lines[1].text)
    }

    @Test fun hasRealLyricsTrueForTimedFalseForMetadataOnly() {
        assertTrue(LrcParser.hasRealLyrics("[00:01.00]词"))
        assertFalse(LrcParser.hasRealLyrics("[ti:x]\n[ar:y]"))
        assertFalse(LrcParser.hasRealLyrics(""))
        assertFalse(LrcParser.hasRealLyrics(null))
    }
}
