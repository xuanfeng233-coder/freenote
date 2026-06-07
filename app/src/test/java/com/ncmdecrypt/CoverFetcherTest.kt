package com.ncmdecrypt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for online cover lookup: filename parsing, strict artist/title matching
 * (the guard against wrong covers), provider ordering, and cover-URL upscaling. The network
 * providers themselves are verified by on-device smoke test (per CLAUDE.md).
 */
class CoverFetcherTest {

    // ── parseFromFilename ─────────────────────────────────────────────────────────

    @Test
    fun parsesArtistTitleStrippingQualityAndExtension() {
        val q = CoverFetcher.parseFromFilename("李荣浩 - 恋人_SQ.kgm")
        assertEquals("李荣浩", q?.artist)
        assertEquals("恋人", q?.title)
    }

    @Test
    fun parsesStrippingBracketTag() {
        val q = CoverFetcher.parseFromFilename("周杰伦 - 那天下雨了 [mqms2].mflac")
        assertEquals("周杰伦", q?.artist)
        assertEquals("那天下雨了", q?.title)
    }

    @Test
    fun parsesStrippingOwnUnlockSuffix() {
        val q = CoverFetcher.parseFromFilename("周杰伦 - 那天下雨了 [mqms2]解锁_040425.flac")
        assertEquals("周杰伦", q?.artist)
        assertEquals("那天下雨了", q?.title)
    }

    @Test
    fun parsesPlainArtistTitle() {
        val q = CoverFetcher.parseFromFilename("门尼 - 巴拉莱卡")
        assertEquals("门尼", q?.artist)
        assertEquals("巴拉莱卡", q?.title)
    }

    @Test
    fun returnsNullWhenNoSeparator() {
        assertNull(CoverFetcher.parseFromFilename("无分隔符歌名.kgm"))
    }

    // ── strict matching (wrong-cover guard) ───────────────────────────────────────

    @Test
    fun titleMatchIgnoresBracketSuffix() {
        assertTrue(CoverFetcher.textMatch("巴拉莱卡", "巴拉莱卡 (The Rod)"))
    }

    @Test
    fun titleMismatchRejected() {
        assertFalse(CoverFetcher.textMatch("那天下雨了", "晴天"))
    }

    @Test
    fun artistMatchesCleanSoloEntry() {
        assertTrue(CoverFetcher.artistMatches("李荣浩", listOf("李荣浩")))
        assertTrue(CoverFetcher.artistMatches("门尼", listOf("门尼")))
    }

    @Test
    fun artistRejectsCollabWithExtraArtists() {
        // A query for one artist must NOT match a result crediting extra artists (remix/collab),
        // even though the query artist appears — that yields a wrong cover.
        assertFalse(CoverFetcher.artistMatches("李荣浩", listOf("陈露&杜江&李荣浩&刘涛")))
        assertFalse(CoverFetcher.artistMatches("周杰伦", listOf("周杰伦-/Montagem")))
        assertFalse(CoverFetcher.artistMatches("周杰伦", listOf("周杰伦./街道办GDC/欧阳耀莹.")))
    }

    @Test
    fun artistMismatchRejectsTribute() {
        assertFalse(CoverFetcher.artistMatches("周杰伦", listOf("Jwong")))
    }

    @Test
    fun strongMatchRejectsRemixCreditingOriginalArtist() {
        // The real 周杰伦「那天下雨了」 isn't licensed on these services; only remixes that credit
        // 周杰伦 + a remixer remain. Title matches but the extra artist must reject it → no cover.
        assertFalse(
            CoverFetcher.isStrongMatch(CoverFetcher.Query("周杰伦", "那天下雨了"), "周杰伦-/Montagem", "那天下雨了")
        )
    }

    @Test
    fun strongMatchRejectsWrongTrackSameArtist() {
        // iTunes returned 晴天 for a 那天下雨了 query — must be rejected so we don't embed a wrong cover.
        assertFalse(CoverFetcher.isStrongMatch(CoverFetcher.Query("周杰伦", "那天下雨了"), "周杰伦", "晴天"))
    }

    @Test
    fun strongMatchRejectsTributeArtist() {
        assertFalse(
            CoverFetcher.isStrongMatch(
                CoverFetcher.Query("周杰伦", "那天下雨了"), "Jwong", "那天下雨了.致敬周杰伦版"
            )
        )
    }

    @Test
    fun strongMatchAcceptsExact() {
        assertTrue(
            CoverFetcher.isStrongMatch(CoverFetcher.Query("门尼", "巴拉莱卡"), "门尼", "巴拉莱卡 (The Rod)")
        )
    }

    // ── provider ordering (origin platform first) ─────────────────────────────────

    @Test
    fun originPlatformIsTriedFirst() {
        assertEquals(CoverFetcher.Source.QQ, CoverFetcher.providersFor("QMC").first())
        assertEquals(CoverFetcher.Source.QQ, CoverFetcher.providersFor("TM").first())
        assertEquals(CoverFetcher.Source.KUGOU, CoverFetcher.providersFor("KGM").first())
        assertEquals(CoverFetcher.Source.KUGOU, CoverFetcher.providersFor("VPR").first())
        assertEquals(CoverFetcher.Source.KUWO, CoverFetcher.providersFor("KWMA").first())
        assertEquals(listOf(CoverFetcher.Source.NETEASE, CoverFetcher.Source.ITUNES), CoverFetcher.providersFor("NCM"))
    }

    @Test
    fun unknownPlatformFallsBackToNeteaseThenItunes() {
        assertEquals(listOf(CoverFetcher.Source.NETEASE, CoverFetcher.Source.ITUNES), CoverFetcher.providersFor("???"))
    }

    // ── cover-URL upscaling ───────────────────────────────────────────────────────

    @Test
    fun itunesUrlUpscaledTo600() {
        assertEquals(
            "https://x/600x600bb.jpg",
            CoverFetcher.itunesUpscale("https://x/100x100bb.jpg")
        )
    }

    @Test
    fun kuwoCoverUrlReplacesLeadingSize() {
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/500/s4s39/33/1990118261.jpg",
            CoverFetcher.kuwoCoverUrl("120/s4s39/33/1990118261.jpg")
        )
    }

    @Test
    fun qqCoverUrlEmbedsAlbumMid() {
        assertTrue(CoverFetcher.qqCoverUrl("ABCmid").contains("ABCmid"))
    }
}
