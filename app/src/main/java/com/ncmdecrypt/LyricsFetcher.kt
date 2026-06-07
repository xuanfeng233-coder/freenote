package com.ncmdecrypt

import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Online lyric (LRC) lookup, mirroring [CoverFetcher]: origin-platform-first, strict artist+title
 * matching (reusing CoverFetcher's matchers), session-cached, best-effort. Returns time-synced LRC
 * text or null. The SECOND of the app's two networked features — sends only title/artist text,
 * never audio. The search half mirrors CoverFetcher's calls; this adds the lyric-fetch step.
 * Per-platform response parsing is split into pure functions (fixture-testable); only the HTTP
 * wrappers touch the network. Endpoints verified live 2026-06-07 (see design doc §3.1).
 */
object LyricsFetcher {

    enum class Source { QQ, NETEASE, KUGOU, KUWO }

    private const val MAX_CANDIDATES = 5
    private val hits = ConcurrentHashMap<String, String>()
    private val misses: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Origin platform first, then Netease (widest coverage), then the rest. No iTunes (no lyrics). */
    fun providersFor(platformTag: String): List<Source> = when (platformTag.uppercase()) {
        "NCM" -> listOf(Source.NETEASE, Source.QQ, Source.KUGOU, Source.KUWO)
        "QMC", "TM" -> listOf(Source.QQ, Source.NETEASE, Source.KUGOU, Source.KUWO)
        "KGM", "KGMA", "KGG", "VPR" -> listOf(Source.KUGOU, Source.NETEASE, Source.QQ, Source.KUWO)
        "KWM", "KWMA" -> listOf(Source.KUWO, Source.NETEASE, Source.QQ, Source.KUGOU)
        else -> listOf(Source.NETEASE, Source.QQ, Source.KUGOU, Source.KUWO)
    }

    /** Returns time-synced LRC text or null. Best-effort, never throws. Runs on a background thread. */
    fun fetch(platformTag: String, query: CoverFetcher.Query): String? {
        val key = CoverFetcher.normalize(query.artist) + "|" + CoverFetcher.normalize(query.title)
        hits[key]?.let { return it }
        if (misses.contains(key)) return null
        for (src in providersFor(platformTag)) {
            val lrc = try { fromSource(src, query) } catch (_: Exception) { null }
            if (lrc != null && LrcParser.hasRealLyrics(lrc)) {
                hits[key] = lrc
                return lrc
            }
        }
        misses.add(key)
        return null
    }

    private fun fromSource(src: Source, q: CoverFetcher.Query): String? = when (src) {
        Source.NETEASE -> fromNetease(q)
        Source.QQ -> fromQq(q)
        Source.KUGOU -> fromKugou(q)
        Source.KUWO -> fromKuwo(q)
    }

    /** Strict accept: title matches AND result adds no extra artist (reuses CoverFetcher matchers). */
    private fun accept(q: CoverFetcher.Query, artists: List<String>, title: String): Boolean =
        CoverFetcher.textMatch(q.title, title) && CoverFetcher.artistMatches(q.artist, artists)

    // ── Netease ──────────────────────────────────────────────────────────────────
    private fun fromNetease(q: CoverFetcher.Query): String? {
        val s = MusicHttp.enc("${q.artist} ${q.title}")
        val search = MusicHttp.getString(
            "https://music.163.com/api/search/get?s=$s&type=1&limit=$MAX_CANDIDATES",
            referer = "https://music.163.com"
        ) ?: return null
        val id = neteaseMatchId(search, q) ?: return null
        val lyric = MusicHttp.getString(
            "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1",
            referer = "https://music.163.com"
        ) ?: return null
        return parseNeteaseLyric(lyric)
    }

    fun neteaseMatchId(searchJson: String, q: CoverFetcher.Query): Long? {
        val songs = JSONObject(searchJson).optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val o = songs.getJSONObject(i)
            val artists = o.optJSONArray("artists")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("name") }
            } ?: emptyList()
            if (accept(q, artists, o.optString("name"))) {
                val id = o.optLong("id"); if (id > 0) return id
            }
        }
        return null
    }

    fun parseNeteaseLyric(json: String): String? {
        val o = JSONObject(json)
        if (o.optBoolean("pureMusic", false)) return null
        return o.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }

    // ── QQ ───────────────────────────────────────────────────────────────────────
    private fun fromQq(q: CoverFetcher.Query): String? {
        val w = MusicHttp.enc("${q.artist} ${q.title}")
        val search = MusicHttp.getString(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298&new_json=1" +
                "&t=0&p=1&n=$MAX_CANDIDATES&w=$w&format=json&platform=yqq.json&needNewCode=0",
            referer = "https://y.qq.com/"
        ) ?: return null
        val mid = qqMatchMid(search, q) ?: return null
        val lyric = MusicHttp.getString(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid" +
                "&format=json&nobase64=1&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8" +
                "&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0",
            referer = "https://y.qq.com/"
        ) ?: return null
        return parseQqLyric(lyric)
    }

    fun qqMatchMid(searchJson: String, q: CoverFetcher.Query): String? {
        val list = JSONObject(searchJson).optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val title = o.optString("title").ifBlank { o.optString("name") }
            val singers = o.optJSONArray("singer")?.let { s ->
                (0 until s.length()).map { s.getJSONObject(it).optString("name") }
            } ?: emptyList()
            if (accept(q, singers, title)) {
                val mid = o.optString("mid"); if (mid.isNotBlank()) return mid
            }
        }
        return null
    }

    fun parseQqLyric(json: String): String? {
        val o = JSONObject(json)
        if (o.optInt("retcode", -1) != 0) return null
        return o.optString("lyric").takeIf { it.isNotBlank() }
    }

    // ── Kugou (two-step) ───────────────────────────────────────────────────────────
    private fun fromKugou(q: CoverFetcher.Query): String? {
        val keyword = MusicHttp.enc("${q.artist} ${q.title}")
        val search = MusicHttp.getString(
            "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$keyword" +
                "&page=1&pagesize=$MAX_CANDIDATES&showtype=1"
        ) ?: return null
        val hash = kugouMatchHash(search, q) ?: return null
        val candJson = MusicHttp.getString(
            "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=$hash"
        ) ?: return null
        val (id, accesskey) = kugouCandidate(candJson) ?: return null
        val dl = MusicHttp.getString(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
        ) ?: return null
        return parseKugouDownload(dl)
    }

    fun kugouMatchHash(searchJson: String, q: CoverFetcher.Query): String? {
        val info = JSONObject(searchJson).optJSONObject("data")?.optJSONArray("info") ?: return null
        for (i in 0 until info.length()) {
            val o = info.getJSONObject(i)
            if (accept(q, listOf(o.optString("singername")), o.optString("songname"))) {
                val hash = o.optString("hash"); if (hash.isNotBlank()) return hash
            }
        }
        return null
    }

    /** First Kugou call: hash → (lyric id, accesskey). Picks the top candidate. */
    fun kugouCandidate(json: String): Pair<String, String>? {
        val cands = JSONObject(json).optJSONArray("candidates") ?: return null
        if (cands.length() == 0) return null
        val c = cands.getJSONObject(0)
        val id = c.optString("id"); val key = c.optString("accesskey")
        return if (id.isNotBlank() && key.isNotBlank()) id to key else null
    }

    /** Second Kugou call: download body → base64-decoded LRC text. */
    fun parseKugouDownload(json: String): String? {
        val content = JSONObject(json).optString("content").takeIf { it.isNotBlank() } ?: return null
        return try { String(Base64.getDecoder().decode(content), Charsets.UTF_8) } catch (_: Exception) { null }
    }

    // ── Kuwo ─────────────────────────────────────────────────────────────────────
    private fun fromKuwo(q: CoverFetcher.Query): String? {
        val all = MusicHttp.enc("${q.artist} ${q.title}")
        val raw = MusicHttp.getString(
            "https://search.kuwo.cn/r.s?all=$all&ft=music&itemset=web_2013&client=kt" +
                "&pn=0&rn=$MAX_CANDIDATES&rformat=json&encoding=utf8"
        ) ?: return null
        val musicId = kuwoMatchId(raw, q) ?: return null
        val lyric = MusicHttp.getString(
            "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$musicId"
        ) ?: return null
        return parseKuwoLyric(lyric)
    }

    fun kuwoMatchId(searchRaw: String, q: CoverFetcher.Query): String? {
        // Kuwo returns relaxed JSON (single quotes); same swap CoverFetcher uses.
        val obj = runCatching { JSONObject(searchRaw.replace('\'', '"')) }.getOrNull() ?: return null
        val list = obj.optJSONArray("abslist") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val title = kuwoText(o.optString("NAME").ifBlank { o.optString("SONGNAME") })
            val artist = kuwoText(o.optString("ARTIST"))
            if (accept(q, listOf(artist), title)) {
                val rid = o.optString("MUSICRID")                       // e.g. "MUSIC_152809941"
                val id = rid.substringAfterLast('_').takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                if (id != null) return id
            }
        }
        return null
    }

    private fun kuwoText(s: String) = s.replace("&nbsp;", " ").trim()

    fun parseKuwoLyric(json: String): String? {
        val o = JSONObject(json)
        if (o.optInt("status", -1) != 200) return null
        val list = o.optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        if (list.length() == 0) return null
        val sb = StringBuilder()
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val t = e.optString("time").toDoubleOrNull() ?: continue   // time is a String of seconds
            sb.append(kuwoLrcLine(t, e.optString("lineLyric"))).append('\n')
        }
        return sb.toString().takeIf { LrcParser.hasRealLyrics(it) }
    }

    /** Reassemble a Kuwo {seconds, line} pair into a standard `[mm:ss.xx]line` LRC line. */
    fun kuwoLrcLine(timeSec: Double, line: String): String {
        val totalCs = Math.round(timeSec * 100)                        // centiseconds, rounded
        val min = totalCs / 6000
        val sec = (totalCs % 6000) / 100
        val cs = totalCs % 100
        return "[%02d:%02d.%02d]%s".format(min, sec, cs, line)
    }
}
