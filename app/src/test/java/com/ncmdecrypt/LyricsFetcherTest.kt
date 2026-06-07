package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-logic coverage for online lyric lookup: provider ordering, per-platform response parsing
 * (fixtures, no network), Kuwo time-string reassembly. Network providers verified on-device.
 */
class LyricsFetcherTest {

    private val zhou = CoverFetcher.Query("周杰伦", "晴天")

    // ── provider ordering (origin platform first; no iTunes for lyrics) ────────────
    @Test fun originPlatformFirst() {
        assertEquals(LyricsFetcher.Source.QQ, LyricsFetcher.providersFor("QMC").first())
        assertEquals(LyricsFetcher.Source.KUGOU, LyricsFetcher.providersFor("KGM").first())
        assertEquals(LyricsFetcher.Source.KUWO, LyricsFetcher.providersFor("KWMA").first())
        assertEquals(LyricsFetcher.Source.NETEASE, LyricsFetcher.providersFor("NCM").first())
        assertFalse(LyricsFetcher.providersFor("QMC").isEmpty())
    }

    // ── Netease ────────────────────────────────────────────────────────────────────
    @Test fun parsesNeteaseLyric() {
        val json = """{"code":200,"lrc":{"lyric":"[00:01.00]故事的小黄花"},"tlyric":{"lyric":""}}"""
        assertEquals("[00:01.00]故事的小黄花", LyricsFetcher.parseNeteaseLyric(json))
    }

    @Test fun skipsNeteasePureMusic() {
        val json = """{"code":200,"pureMusic":true,"lrc":{"lyric":"[00:00.00]纯音乐，请欣赏"}}"""
        assertNull(LyricsFetcher.parseNeteaseLyric(json))
    }

    @Test fun neteaseMatchPicksCorrectId() {
        val json = """{"result":{"songs":[
            {"id":111,"name":"别的歌","artists":[{"name":"别人"}]},
            {"id":222,"name":"晴天","artists":[{"name":"周杰伦"}]}]}}"""
        assertEquals(222L, LyricsFetcher.neteaseMatchId(json, zhou))
    }

    // ── QQ ───────────────────────────────────────────────────────────────────────
    @Test fun parsesQqLyricOnRetcodeZero() {
        val json = """{"retcode":0,"code":0,"lyric":"[00:01.00]词","trans":""}"""
        assertEquals("[00:01.00]词", LyricsFetcher.parseQqLyric(json))
    }

    @Test fun rejectsQqLyricOnError() {
        assertNull(LyricsFetcher.parseQqLyric("""{"retcode":-1310,"code":-1310}"""))
    }

    @Test fun qqMatchPicksMid() {
        val json = """{"data":{"song":{"list":[
            {"mid":"AAA","title":"晴天","singer":[{"name":"周杰伦"}]}]}}}"""
        assertEquals("AAA", LyricsFetcher.qqMatchMid(json, zhou))
    }

    // ── Kugou ──────────────────────────────────────────────────────────────────────
    @Test fun parsesKugouCandidate() {
        val json = """{"status":200,"candidates":[{"id":"10","accesskey":"KEY"}]}"""
        assertEquals("10" to "KEY", LyricsFetcher.kugouCandidate(json))
    }

    @Test fun nullKugouCandidateWhenEmpty() {
        assertNull(LyricsFetcher.kugouCandidate("""{"status":200,"candidates":[]}"""))
    }

    @Test fun decodesKugouDownloadBase64() {
        val lrc = "[00:01.00]词"
        val b64 = Base64.getEncoder().encodeToString(lrc.toByteArray(Charsets.UTF_8))
        val json = """{"status":200,"content":"$b64"}"""
        assertEquals(lrc, LyricsFetcher.parseKugouDownload(json))
    }

    // ── Kuwo ─────────────────────────────────────────────────────────────────────
    @Test fun kuwoLrcLineFormatting() {
        assertEquals("[00:29.26]故事的小黄花", LyricsFetcher.kuwoLrcLine(29.26, "故事的小黄花"))
        assertEquals("[00:01.70]x", LyricsFetcher.kuwoLrcLine(1.7, "x"))
        assertEquals("[02:42.00]y", LyricsFetcher.kuwoLrcLine(162.0, "y"))
    }

    @Test fun parsesKuwoLrclist() {
        val json = """{"status":200,"data":{"lrclist":[
            {"time":"1.0","lineLyric":"第一句"},{"time":"5.5","lineLyric":"第二句"}]}}"""
        val lrc = LyricsFetcher.parseKuwoLyric(json)!!
        assertTrue(lrc.contains("[00:01.00]第一句"))
        assertTrue(lrc.contains("[00:05.50]第二句"))
    }

    @Test fun rejectsKuwoFailureBody() {
        assertNull(LyricsFetcher.parseKuwoLyric("""{"data":null,"msg":"音乐查询失败","status":301}"""))
        assertNull(LyricsFetcher.parseKuwoLyric("""{"status":200,"data":{"lrclist":[]}}"""))
    }

    @Test fun kuwoMatchStripsMusicPrefix() {
        val raw = """{"abslist":[{"NAME":"晴天","ARTIST":"周杰伦","MUSICRID":"MUSIC_152809941"}]}"""
        assertEquals("152809941", LyricsFetcher.kuwoMatchId(raw, zhou))
    }
}
