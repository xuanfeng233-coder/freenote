package com.ncmdecrypt

import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Online album-cover lookup. Used only when a decrypted file has **no embedded cover** and the
 * user has left "联网补全封面" enabled. Queries the **origin platform first** (the song is
 * guaranteed to exist there — e.g. 周杰伦 is QQ-exclusive), then falls back to Netease / iTunes.
 *
 * Every candidate is strictly matched on artist + title, so a near-miss never yields a *wrong*
 * cover (better no cover than the wrong one). Best-effort throughout: any failure returns null and
 * the caller keeps the already-correct, cover-less audio.
 *
 * This is the ONLY part of the app that touches the network. Audio decryption stays fully offline;
 * only the song's title/artist text leaves the device, to look up public album art.
 *
 * Sources verified reachable: iTunes, Netease, Kuwo. QQ + Kugou are best-effort (may be region- or
 * signature-gated from some networks); when they fail, the Netease/iTunes fallback still applies.
 */
object CoverFetcher {

    data class Query(val artist: String, val title: String)

    /** Cover sources, in the abstract. [providersFor] orders them per origin platform. */
    enum class Source { QQ, KUGOU, KUWO, NETEASE, ITUNES }

    private const val MAX_CANDIDATES = 5

    /** Session caches so a batch never refetches the same song. Key = normalized "artist|title". */
    private val hits = ConcurrentHashMap<String, ByteArray>()
    private val misses: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ── Provider ordering ────────────────────────────────────────────────────────

    /** Provider order for a UI format tag (NCM/QMC/TM/KGM/KGG/VPR/KWM/KWMA/…). Origin platform first. */
    fun providersFor(platformTag: String): List<Source> = when (platformTag.uppercase()) {
        "NCM" -> listOf(Source.NETEASE, Source.ITUNES)
        "QMC", "TM" -> listOf(Source.QQ, Source.NETEASE, Source.ITUNES)
        "KGM", "KGMA", "KGG", "VPR" -> listOf(Source.KUGOU, Source.NETEASE, Source.ITUNES)
        "KWM", "KWMA" -> listOf(Source.KUWO, Source.NETEASE, Source.ITUNES)
        else -> listOf(Source.NETEASE, Source.ITUNES)
    }

    // ── Orchestration ────────────────────────────────────────────────────────────

    /** Returns cover bytes (jpg/png) or null. Best-effort, never throws. Runs on a background thread. */
    fun fetch(platformTag: String, query: Query): ByteArray? {
        val key = cacheKey(query)
        hits[key]?.let { return it }
        if (misses.contains(key)) return null
        for (src in providersFor(platformTag)) {
            val bytes = try { fromSource(src, query) } catch (_: Exception) { null }
            if (bytes != null && looksLikeImage(bytes)) {
                hits[key] = bytes
                return bytes
            }
        }
        misses.add(key)
        return null
    }

    private fun cacheKey(q: Query) = normalize(q.artist) + "|" + normalize(q.title)

    private fun fromSource(src: Source, q: Query): ByteArray? = when (src) {
        Source.ITUNES -> fromItunes(q)
        Source.NETEASE -> fromNetease(q)
        Source.KUWO -> fromKuwo(q)
        Source.QQ -> fromQq(q)
        Source.KUGOU -> fromKugou(q)
    }

    // ── Filename → Query ─────────────────────────────────────────────────────────

    private val BRACKET_GROUP = Regex("[(（\\[【《<][^)）\\]】》>]*[)）\\]】》>]")
    private val OUR_SUFFIX = Regex("解锁_\\d{6}$")
    private val QUALITY_SUFFIX = Regex("[ _\\-]*(sq|hq|hr|flac|320k?|无损|母带)$", RegexOption.IGNORE_CASE)

    /**
     * Parse `Artist - Title` from a file name. Strips the extension, bracket groups like `[mqms2]`,
     * our own `解锁_HHmmss` suffix and trailing quality markers (`_SQ`/`flac`/…). Null if there is
     * no usable separator.
     */
    fun parseFromFilename(name: String): Query? {
        var base = name.substringBeforeLast('.', name)
        base = OUR_SUFFIX.replace(base, "")
        base = BRACKET_GROUP.replace(base, " ")
        val sep = when {
            base.contains(" - ") -> " - "
            base.contains(" – ") -> " – "
            base.contains("-") -> "-"
            else -> return null
        }
        val parts = base.split(sep, limit = 2)
        if (parts.size != 2) return null
        val artist = parts[0].trim()
        val title = QUALITY_SUFFIX.replace(parts[1].trim(), "").trim()
        if (artist.isBlank() || title.isBlank()) return null
        return Query(artist, title)
    }

    // ── Strict matching ──────────────────────────────────────────────────────────

    private val ARTIST_SEP = Regex("[/、&,，;；]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE)

    /** Normalize for comparison: NFKC, lowercase, drop bracket groups, keep only letters/digits/CJK. */
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val noBracket = BRACKET_GROUP.replace(n, "")
        return buildString { for (c in noBracket) if (c.isLetterOrDigit()) append(c) }
    }

    /** True when two text fields match: equal, or the shorter (≥2 chars) is contained in the longer. */
    fun textMatch(a0: String, b0: String): Boolean {
        val a = normalize(a0); val b = normalize(b0)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= 2 && long.contains(short)
    }

    private fun tokens(s: String): List<String> =
        s.split(ARTIST_SEP).map { normalize(it) }.filter { it.isNotEmpty() }

    /**
     * True only when **every** result artist is also in the query — i.e. the result introduces no
     * extra collaborator/remixer. This rejects remixes & tributes that merely *credit* the original
     * artist alongside others (e.g. `周杰伦-/Montagem`, `周杰伦./街道办GDC/...`), which otherwise
     * text-match and yield a wrong cover. Empty result artist tokens are ignored, not failed.
     */
    fun artistMatches(queryArtist: String, resultArtists: List<String>): Boolean {
        val qs = tokens(queryArtist)
        val rs = resultArtists.flatMap { tokens(it) }
        if (qs.isEmpty() || rs.isEmpty()) return false
        return rs.all { r -> qs.any { q -> q == r || (r.length >= 2 && q.contains(r)) || (q.length >= 2 && r.contains(q)) } }
    }

    /** Accept a candidate only when title matches AND it adds no extra artist — guards wrong covers. */
    fun isStrongMatch(q: Query, resultArtist: String, resultTitle: String): Boolean =
        accept(q, listOf(resultArtist), resultTitle)

    private fun accept(q: Query, resultArtists: List<String>, resultTitle: String): Boolean =
        textMatch(q.title, resultTitle) && artistMatches(q.artist, resultArtists)

    // ── Cover URL upscalers (pure, testable) ─────────────────────────────────────

    fun itunesUpscale(url100: String): String = url100.replace("100x100bb", "600x600bb")

    /** Kuwo `web_albumpic_short` like `120/sxx/yy/zzz.jpg` → full HTTPS URL at the given size. */
    fun kuwoCoverUrl(picShort: String, size: Int = 500): String =
        "https://img1.kuwo.cn/star/albumcover/" + picShort.replaceFirst(Regex("^\\d+"), size.toString())

    fun qqCoverUrl(albumMid: String): String =
        "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"

    // ── Providers ────────────────────────────────────────────────────────────────

    private fun fromItunes(q: Query): ByteArray? {
        val term = MusicHttp.enc("${q.artist} ${q.title}")
        val json = MusicHttp.getString(
            "https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=$MAX_CANDIDATES"
        ) ?: return null
        val arr = JSONObject(json).optJSONArray("results") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (isStrongMatch(q, o.optString("artistName"), o.optString("trackName"))) {
                val url = itunesUpscale(o.optString("artworkUrl100"))
                if (url.isNotBlank()) MusicHttp.getBytes(url)?.let { return it }
            }
        }
        return null
    }

    private fun fromNetease(q: Query): ByteArray? {
        val s = MusicHttp.enc("${q.artist} ${q.title}")
        val json = MusicHttp.getString(
            "https://music.163.com/api/search/get?s=$s&type=1&limit=$MAX_CANDIDATES",
            referer = "https://music.163.com"
        ) ?: return null
        val songs = JSONObject(json).optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val o = songs.getJSONObject(i)
            val artists = o.optJSONArray("artists")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it).optString("name") }
            } ?: emptyList()
            if (accept(q, artists, o.optString("name"))) {
                val pic = neteasePicUrl(o.optLong("id"))
                    ?: o.optJSONObject("album")?.optString("picUrl")?.takeIf { it.isNotBlank() }
                if (pic != null) MusicHttp.getBytes("$pic?param=500y500")?.let { return it }
            }
        }
        return null
    }

    private fun neteasePicUrl(id: Long): String? {
        if (id <= 0) return null
        val json = MusicHttp.getString(
            "https://music.163.com/api/song/detail/?ids=%5B$id%5D",
            referer = "https://music.163.com"
        ) ?: return null
        return JSONObject(json).optJSONArray("songs")?.optJSONObject(0)
            ?.optJSONObject("album")?.optString("picUrl")?.takeIf { it.isNotBlank() }
    }

    private fun fromKuwo(q: Query): ByteArray? {
        val all = MusicHttp.enc("${q.artist} ${q.title}")
        val raw = MusicHttp.getString(
            "https://search.kuwo.cn/r.s?all=$all&ft=music&itemset=web_2013&client=kt" +
                "&pn=0&rn=$MAX_CANDIDATES&rformat=json&encoding=utf8"
        ) ?: return null
        // Kuwo returns relaxed JSON (single quotes). Swap to double quotes, then parse leniently.
        val obj = runCatching { JSONObject(raw.replace('\'', '"')) }.getOrNull() ?: return null
        val list = obj.optJSONArray("abslist") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val title = kuwoText(o.optString("NAME").ifBlank { o.optString("SONGNAME") })
            val artist = kuwoText(o.optString("ARTIST"))
            if (accept(q, listOf(artist), title)) {
                val pic = o.optString("web_albumpic_short")
                if (pic.isNotBlank()) MusicHttp.getBytes(kuwoCoverUrl(pic))?.let { return it }
            }
        }
        return null
    }

    private fun kuwoText(s: String) = s.replace("&nbsp;", " ").trim()

    private fun fromQq(q: Query): ByteArray? {
        val w = MusicHttp.enc("${q.artist} ${q.title}")
        val json = MusicHttp.getString(
            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?ct=24&qqmusic_ver=1298&new_json=1" +
                "&t=0&p=1&n=$MAX_CANDIDATES&w=$w&format=json&platform=yqq.json&needNewCode=0",
            referer = "https://y.qq.com/"
        ) ?: return null
        val list = JSONObject(json).optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val title = o.optString("title").ifBlank { o.optString("name") }
            val singers = o.optJSONArray("singer")?.let { s ->
                (0 until s.length()).map { s.getJSONObject(it).optString("name") }
            } ?: emptyList()
            if (accept(q, singers, title)) {
                val mid = o.optJSONObject("album")?.optString("mid")
                if (!mid.isNullOrBlank()) MusicHttp.getBytes(qqCoverUrl(mid))?.let { return it }
            }
        }
        return null
    }

    private fun fromKugou(q: Query): ByteArray? {
        val keyword = MusicHttp.enc("${q.artist} ${q.title}")
        val json = MusicHttp.getString(
            "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$keyword" +
                "&page=1&pagesize=$MAX_CANDIDATES&showtype=1"
        ) ?: return null
        val info = JSONObject(json).optJSONObject("data")?.optJSONArray("info") ?: return null
        for (i in 0 until info.length()) {
            val o = info.getJSONObject(i)
            if (accept(q, listOf(o.optString("singername")), o.optString("songname"))) {
                val cover = kugouCover(o.optString("hash")) ?: continue
                return cover
            }
        }
        return null
    }

    /** Second Kugou call: hash → play data → album image URL (with a `{size}` placeholder). */
    private fun kugouCover(hash: String): ByteArray? {
        if (hash.isBlank()) return null
        val json = MusicHttp.getString(
            "https://www.kugou.com/yy/index.php?r=play/getdata&hash=$hash",
            referer = "https://www.kugou.com/"
        ) ?: return null
        val data = JSONObject(json).optJSONObject("data") ?: return null
        val img = listOf(data.optString("img"), data.optString("album_img"))
            .firstOrNull { it.isNotBlank() } ?: return null
        return MusicHttp.getBytes(img.replace("{size}", "480"))
    }

    // ── HTTP + helpers ───────────────────────────────────────────────────────────

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 256) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte()
        return jpg || png
    }
}
