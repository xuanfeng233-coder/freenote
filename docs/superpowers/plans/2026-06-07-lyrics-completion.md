# 歌词自动补全 + 导出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解密后联网抓取带时间轴的 LRC 歌词，嵌入音频标签并导出旁挂 `.lrc`，并在应用内播放器以「封面/歌词」单独 tab 随播放滚动显示。

**Architecture:** 镜像现有的封面补全流水线。新增 `LyricsFetcher`（origin-platform-first、复用 `CoverFetcher` 的严格匹配、纯 `HttpURLConnection`+`org.json`）、`LrcParser`（纯解析）、`LyricsView`（自定义滚动视图）、`LyricsPrefs`（独立开关），HTTP 抽到共用 `MusicHttp`。歌词经 `MetadataEditor.embedLyricsIfMissing` 写入 `FieldKey.LYRICS`，并以 `.lrc` 旁挂导出到公共输出目录。byte-stable 不变量与封面一致：只在文件无歌词时写。

**Tech Stack:** Kotlin 1.9.22, Android SDK 34 / minSdk 26, jaudiotagger 3.0.1（`FieldKey.LYRICS`），org.json，`java.util.Base64`（minSdk 26 起可用，JVM 单测亦可用），Material `TabLayout`，自定义 `View`。JUnit 纯 JVM 单测（无 Robolectric，无真机音频文件 —— jaudiotagger 写路径由真机冒烟验证）。

**前置：** 已在分支 `feat/lyrics-completion`，spec 见 `docs/superpowers/specs/2026-06-07-lyrics-completion-design.md`。

**全程测试命令：** `./gradlew :app:testDebugUnitTest`（JVM 单测）；`./gradlew assembleDebug`（编译验证）。

---

### Task 1: 抽出共用 HTTP `MusicHttp`

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/MusicHttp.kt`
- Modify: `app/src/main/java/com/ncmdecrypt/CoverFetcher.kt`
- Test: 复用现有 `app/src/test/java/com/ncmdecrypt/CoverFetcherTest.kt`（行为不变）

纯重构：把 `CoverFetcher` 私有的 HTTP 助手抽出共用，`LyricsFetcher` 后续复用。

- [ ] **Step 1: 创建 `MusicHttp.kt`**

```kotlin
package com.ncmdecrypt

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared best-effort HTTP GET helpers for the app's only networked features — online cover
 * ([CoverFetcher]) and lyric ([LyricsFetcher]) completion. Plain HttpURLConnection; any failure
 * returns null and never throws. Audio decryption never touches this; only title/artist text does.
 */
object MusicHttp {
    private const val TIMEOUT_MS = 8000
    const val UA = "Mozilla/5.0 (Linux; Android) FreeNote"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getString(urlStr: String, referer: String? = null): String? =
        openGet(urlStr, referer) { it.bufferedReader(Charsets.UTF_8).readText() }

    fun getBytes(urlStr: String, referer: String? = null): ByteArray? =
        openGet(urlStr, referer) { it.readBytes() }

    private fun <T> openGet(urlStr: String, referer: String?, read: (java.io.InputStream) -> T): T? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            if (referer != null) setRequestProperty("Referer", referer)
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.use { read(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
```

- [ ] **Step 2: 改 `CoverFetcher` 删除自有 HTTP 助手 + 常量**

在 `CoverFetcher.kt` 中删除这两个常量（原 32-33 行）：

```kotlin
    private const val TIMEOUT_MS = 8000
    private const val UA = "Mozilla/5.0 (Linux; Android) FreeNote"
```

并删除整个 HTTP 助手区块（原 `// ── HTTP + helpers ──` 下的 `enc / looksLikeImage` 中除 `looksLikeImage` 外的部分）。具体：删除 `enc`、`httpGetString`、`httpGetBytes`、`openGet` 四个私有函数，**保留** `looksLikeImage`。删除后该区块只剩：

```kotlin
    // ── HTTP + helpers ───────────────────────────────────────────────────────────

    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 256) return false
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte()
        return jpg || png
    }
```

- [ ] **Step 3: 改 `CoverFetcher` 全部调用点委托给 `MusicHttp`**

在 `CoverFetcher.kt` 内全局替换（仅这三种调用，注意 `enc(` 不要误改其它）：
- `httpGetString(` → `MusicHttp.getString(`
- `httpGetBytes(` → `MusicHttp.getBytes(`
- `enc(` → `MusicHttp.enc(`

（涉及 `fromItunes/fromNetease/neteasePicUrl/fromKuwo/fromQq/fromKugou/kugouCover` 等处的调用。）

删除 `CoverFetcher.kt` 顶部现在已不再使用的三个 import（避免无用 import 警告）：

```kotlin
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
```

（保留 `org.json.JSONObject`、`java.text.Normalizer`、`java.util.concurrent.ConcurrentHashMap`。）

- [ ] **Step 4: 编译 + 跑现有测试，确认行为不变**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；`CoverFetcherTest` 全绿（未改断言，纯重构）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/MusicHttp.kt app/src/main/java/com/ncmdecrypt/CoverFetcher.kt
git commit -m "$(cat <<'EOF'
refactor: extract shared HTTP helpers into MusicHttp

CoverFetcher now delegates its GET helpers to MusicHttp so the upcoming
LyricsFetcher can reuse them. No behavior change.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: LRC 解析器 `LrcParser`

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/LrcParser.kt`
- Test: `app/src/test/java/com/ncmdecrypt/LrcParserTest.kt`

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/ncmdecrypt/LrcParserTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.LrcParserTest`
Expected: 编译失败 / FAIL（`LrcParser` 未定义）。

- [ ] **Step 3: 实现 `LrcParser.kt`**

```kotlin
package com.ncmdecrypt

/** One time-tagged lyric line. [timeMs] is the start offset; [text] is the lyric text. */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parses LRC text into time-sorted [LrcLine]s. Handles multiple timestamps on one line
 * (`[00:01.00][00:05.00]词`), `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` forms, and skips pure
 * ID-tag / blank lines (`[ti:]`, `[ar:]`, `[al:]`, `[by:]`, `[offset:]`, …). Pure + side-effect free.
 */
object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(lrc: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (rawLine in lrc.lineSequence()) {
            val matches = TIME_TAG.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val millis = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out.add(LrcLine(min * 60_000 + sec * 1_000 + millis, text))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** True when the text has ≥1 real timed lyric line (not just ID-tag metadata). */
    fun hasRealLyrics(lrc: String?): Boolean = !lrc.isNullOrBlank() && parse(lrc).isNotEmpty()
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.LrcParserTest`
Expected: PASS（6 tests）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/LrcParser.kt app/src/test/java/com/ncmdecrypt/LrcParserTest.kt
git commit -m "$(cat <<'EOF'
feat: add LrcParser for time-synced lyric lines

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 在线歌词抓取 `LyricsFetcher`

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/LyricsFetcher.kt`
- Test: `app/src/test/java/com/ncmdecrypt/LyricsFetcherTest.kt`

按平台分纯解析函数（fixture 可测）+ 网络编排（真机验证）。各平台端点见 spec §3.1（已实测）。

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/ncmdecrypt/LyricsFetcherTest.kt`：

```kotlin
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
```

> 注：`import org.junit.Assert.assertFalse` 也需加入（测试里用了 `assertFalse`）。补到 import 区。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.LyricsFetcherTest`
Expected: 编译失败（`LyricsFetcher` 未定义）。

- [ ] **Step 3: 实现 `LyricsFetcher.kt`**

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.LyricsFetcherTest`
Expected: PASS（全部 fixture/排序/重组测试）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/LyricsFetcher.kt app/src/test/java/com/ncmdecrypt/LyricsFetcherTest.kt
git commit -m "$(cat <<'EOF'
feat: add LyricsFetcher (online LRC, origin-platform-first)

Netease/QQ/Kugou/Kuwo lyric-fetch second call, reusing CoverFetcher's
search half + strict matching and MusicHttp. Per-platform parsing is pure
and fixture-tested; endpoints verified live.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 独立开关 `LyricsPrefs`

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/LyricsPrefs.kt`

镜像 `CoverPrefs`（无单测，与 `CoverPrefs` 一致）。

- [ ] **Step 1: 实现 `LyricsPrefs.kt`**

```kotlin
package com.ncmdecrypt

import android.content.Context

/**
 * User toggle for online lyric completion ("联网补全歌词"). Defaults to ON. Independent from
 * [CoverPrefs] so cover and lyric network use can be toggled separately. When off, no lyric
 * network call is made (already-embedded lyrics are still surfaced — that uses no network).
 */
object LyricsPrefs {

    private const val PREFS = "lyrics_fetch"
    private const val KEY_ENABLED = "online_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/LyricsPrefs.kt
git commit -m "$(cat <<'EOF'
feat: add LyricsPrefs toggle for online lyric completion

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `MetadataEditor` 歌词读写

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/MetadataEditor.kt`
- Test: `app/src/test/java/com/ncmdecrypt/MetadataEditorTest.kt`

- [ ] **Step 1: 写失败测试（仅纯逻辑：不可嵌容器/空词 早返回、不抛）**

在 `MetadataEditorTest.kt` 末尾（最后一个 `}` 之前）追加：

```kotlin
    @Test
    fun embedLyricsSkipsNonEmbeddableWithoutThrowing() {
        // AAC is not embeddable → early return, no file touched, no throw.
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.aac"), AudioFormat.AAC, "[00:01.00]x")
    }

    @Test
    fun embedLyricsSkipsBlankLyricsWithoutThrowing() {
        // Embeddable format but blank/null lyric → early return before touching the file.
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.flac"), AudioFormat.FLAC, null)
        MetadataEditor.embedLyricsIfMissing(java.io.File("/no/such/file.flac"), AudioFormat.FLAC, "   ")
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.MetadataEditorTest`
Expected: 编译失败（`embedLyricsIfMissing` 未定义）。

- [ ] **Step 3: 实现 —— 在 `MetadataEditor` 内（`embedIfMissing` 之后、类结束 `}` 之前）新增**

```kotlin
    /**
     * Best-effort read of the embedded lyric tag ([FieldKey.LYRICS] → USLT / Vorbis LYRICS comment).
     * Returns null on any failure or when no lyric is present.
     */
    fun readLyrics(file: File): String? = try {
        AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * Additive lyric tagging used right after decryption, mirroring [embedIfMissing]: writes the
     * full synced LRC text (timestamps and all) into [FieldKey.LYRICS] **only when the file has no
     * lyric** — so an output that already carries lyrics stays byte-identical. Maps to the USLT
     * frame (MP3/WAV) or the LYRICS Vorbis comment (FLAC/OGG); SYLT is intentionally not used.
     * Never throws; unsupported containers are skipped. The file must already have its real
     * extension (jAudioTagger selects its reader by extension).
     */
    fun embedLyricsIfMissing(file: File, fmt: AudioFormat, lrc: String?) {
        if (!isEmbeddable(fmt)) return
        val text = lrc?.trim().orEmpty()
        if (text.isEmpty()) return
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            val existing = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
            if (existing.isNullOrBlank()) {
                tag.setField(FieldKey.LYRICS, text)
                audioFile.commit()
            }
        } catch (_: Exception) {
            // Embedding is a bonus; the raw decrypted audio is already correct. Swallow.
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests com.ncmdecrypt.MetadataEditorTest`
Expected: PASS（含 2 个新增 no-throw 测试）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/MetadataEditor.kt app/src/test/java/com/ncmdecrypt/MetadataEditorTest.kt
git commit -m "$(cat <<'EOF'
feat: MetadataEditor read/embed lyrics via FieldKey.LYRICS

Additive, blank-only, commit-only-if-changed — byte-stable like
embedIfMissing. USLT (mp3/wav) / LYRICS vorbis comment (flac/ogg).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `Track` 增加 `lyricsPath`

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/Track.kt`

- [ ] **Step 1: 加字段**

把 `coverPath` 后面加入 `lyricsPath`。将：

```kotlin
    val coverPath: String?,
    val mediaStoreUri: String?,
    val publicPath: String? = null
```

改为：

```kotlin
    val coverPath: String?,
    val lyricsPath: String? = null,
    val mediaStoreUri: String?,
    val publicPath: String? = null
```

并在类 KDoc 末尾补一句：`[lyricsPath] points at a sidecar .lrc file next to the cached audio, or null when there is none.`

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`TrackBuilder.build` 用具名参数构造 `Track`，新字段有默认值不破坏现有调用）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/Track.kt
git commit -m "$(cat <<'EOF'
feat: add Track.lyricsPath sidecar field

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `TrackBuilder` 写歌词旁挂 + 透传

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/TrackBuilder.kt`

缓存副本旁写 `.lrc`（供应用内显示），并把 `lyricsPath` 填进 `Track`。

- [ ] **Step 1: `build` 增加 `lyrics` 形参**

把签名：

```kotlin
    fun build(
        originalName: String,
        encryptedTempPath: String?,
        decryptedFile: File,
        mediaStoreUri: String?,
        formatTag: String,
        publicPath: String? = null
    ): Track {
```

改为：

```kotlin
    fun build(
        originalName: String,
        encryptedTempPath: String?,
        decryptedFile: File,
        mediaStoreUri: String?,
        formatTag: String,
        publicPath: String? = null,
        lyrics: String? = null
    ): Track {
```

- [ ] **Step 2: 计算 `lyricsPath` 并填入 `Track`**

把：

```kotlin
        val coverPath = cover?.let { writeCoverSidecar(decryptedFile, it) }

        val fallbackTitle = originalName.substringBeforeLast('.').ifBlank { originalName }
        return Track(
            id = decryptedFile.absolutePath,
            filePath = decryptedFile.absolutePath,
            title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            artist = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家",
            album = album?.takeIf { it.isNotBlank() } ?: "",
            formatTag = formatTag,
            coverPath = coverPath,
            mediaStoreUri = mediaStoreUri,
            publicPath = publicPath
        )
    }
```

改为：

```kotlin
        val coverPath = cover?.let { writeCoverSidecar(decryptedFile, it) }

        // Lyrics: prefer the value threaded from the decrypt flow (fetched / pre-existing), else
        // read the file's own embedded lyric. Write a sidecar next to the cached copy for the
        // in-app lyrics tab.
        val lyricsText = lyrics?.takeIf { LrcParser.hasRealLyrics(it) }
            ?: MetadataEditor.readLyrics(decryptedFile)?.takeIf { LrcParser.hasRealLyrics(it) }
        val lyricsPath = lyricsText?.let { writeLyricsSidecar(decryptedFile, it) }

        val fallbackTitle = originalName.substringBeforeLast('.').ifBlank { originalName }
        return Track(
            id = decryptedFile.absolutePath,
            filePath = decryptedFile.absolutePath,
            title = title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            artist = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家",
            album = album?.takeIf { it.isNotBlank() } ?: "",
            formatTag = formatTag,
            coverPath = coverPath,
            lyricsPath = lyricsPath,
            mediaStoreUri = mediaStoreUri,
            publicPath = publicPath
        )
    }
```

- [ ] **Step 3: 新增 `writeLyricsSidecar`（在 `writeCoverSidecar` 之后）**

```kotlin
    /**
     * Writes lyrics to "<audio-name-without-ext>.lrc" next to the cached file, UTF-8 + BOM. NOTE the
     * stem differs from the cover sidecar ("song.lrc", NOT "song.flac.lrc") so players pair it with
     * the audio. Returns its path or null.
     */
    fun writeLyricsSidecar(audioFile: File, lrc: String): String? = try {
        val sidecarName = FilenameSanitizer.sanitizeFileName(audioFile.nameWithoutExtension + ".lrc", "lyrics.lrc")
        val sidecar = File(audioFile.parentFile, sidecarName)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        sidecar.writeBytes(bom + lrc.toByteArray(Charsets.UTF_8))
        sidecar.absolutePath
    } catch (_: Exception) {
        null
    }
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/TrackBuilder.kt
git commit -m "$(cat <<'EOF'
feat: TrackBuilder writes .lrc sidecar + threads lyrics into Track

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `MainActivity` 接线（抓取 / 导出 / 菜单）

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/MainActivity.kt`

无 JVM 单测（Android 入口），由编译 + 真机冒烟验证。

- [ ] **Step 1: 菜单初始化 —— 在封面那行之后加歌词开关初始化**

把（约 145 行）：

```kotlin
        topAppBar.menu.findItem(R.id.action_fetch_covers).isChecked = CoverPrefs.isEnabled(this)
```

改为：

```kotlin
        topAppBar.menu.findItem(R.id.action_fetch_covers).isChecked = CoverPrefs.isEnabled(this)
        topAppBar.menu.findItem(R.id.action_fetch_lyrics).isChecked = LyricsPrefs.isEnabled(this)
```

- [ ] **Step 2: 菜单点击 —— 在 `action_fetch_covers` case 之后加 `action_fetch_lyrics`**

把：

```kotlin
                R.id.action_fetch_covers -> {
                    val enabled = !item.isChecked
                    item.isChecked = enabled
                    CoverPrefs.setEnabled(this, enabled)
                    true
                }
```

之后插入：

```kotlin
                R.id.action_fetch_lyrics -> {
                    val enabled = !item.isChecked
                    item.isChecked = enabled
                    LyricsPrefs.setEnabled(this, enabled)
                    true
                }
```

- [ ] **Step 3: 解密流程 —— 抓歌词 + 透传**

把（约 650-662 行）：

```kotlin
                    maybeFetchCover(toSave, audioFmt, formatTag, displayName)

                    val saved = saveDecrypted(displayName, toSave, audioFmt)
                    // Build the playable Track while the encrypted source still exists (NCM
                    // metadata/cover lives in its header).
                    val track = TrackBuilder.build(
                        originalName = displayName,
                        encryptedTempPath = encryptedFile.absolutePath,
                        decryptedFile = saved.cacheFile,
                        mediaStoreUri = saved.mediaStoreUri ?: saved.customDirUri,
                        formatTag = formatTag,
                        publicPath = saved.publicPath
                    )
```

改为：

```kotlin
                    maybeFetchCover(toSave, audioFmt, formatTag, displayName)
                    val lrc = maybeFetchLyrics(toSave, audioFmt, formatTag, displayName)

                    val saved = saveDecrypted(displayName, toSave, audioFmt, lrc)
                    // Build the playable Track while the encrypted source still exists (NCM
                    // metadata/cover lives in its header).
                    val track = TrackBuilder.build(
                        originalName = displayName,
                        encryptedTempPath = encryptedFile.absolutePath,
                        decryptedFile = saved.cacheFile,
                        mediaStoreUri = saved.mediaStoreUri ?: saved.customDirUri,
                        formatTag = formatTag,
                        publicPath = saved.publicPath,
                        lyrics = lrc
                    )
```

- [ ] **Step 4: 新增 `maybeFetchLyrics`（紧跟 `maybeFetchCover` 之后）**

```kotlin
    /**
     * Returns the LRC for this output (to be exported + displayed), or null. Surfaces an
     * already-embedded lyric without any network; otherwise, when enabled, looks it up online from
     * the origin platform ([LyricsFetcher]) and embeds it into the audio tag. Runs on the existing
     * background thread; best-effort, swallows all errors. The audio is already correct without it.
     */
    private fun maybeFetchLyrics(
        file: File,
        audioFmt: AudioFormat,
        platformTag: String,
        displayName: String
    ): String? {
        try {
            // Pre-existing embedded lyric (e.g. a QMC that already carried one) — use, no network.
            MetadataEditor.readLyrics(file)?.takeIf { LrcParser.hasRealLyrics(it) }?.let { return it }

            if (!LyricsPrefs.isEnabled(this)) return null
            if (!MetadataEditor.isEmbeddable(audioFmt)) return null

            val existing = MetadataEditor.read(file)
            // File name first (its "Artist - Title" is usually truer than generic embedded tags),
            // then the embedded title/artist. Same strategy as cover lookup.
            val candidates = buildList {
                CoverFetcher.parseFromFilename(displayName)?.let { add(it) }
                val a = existing?.artist?.takeIf { it.isNotBlank() }
                val t = existing?.title?.takeIf { it.isNotBlank() }
                if (a != null && t != null) add(CoverFetcher.Query(a, t))
            }.distinct()

            for (q in candidates) {
                val lrc = LyricsFetcher.fetch(platformTag, q) ?: continue
                MetadataEditor.embedLyricsIfMissing(file, audioFmt, lrc)
                return lrc
            }
        } catch (_: Exception) {
            // Best-effort: the decrypted audio is already correct without lyrics.
        }
        return null
    }
```

- [ ] **Step 5: `saveDecrypted` 接收 `lrc` 并导出旁挂到三种输出模式**

把签名：

```kotlin
    private fun saveDecrypted(
        originalName: String,
        decryptedFile: File,
        audioFmt: AudioFormat
    ): SaveResult {
        val ext = extFor(audioFmt)
        val baseName = FilenameSanitizer.sanitizeBase(originalName.substringBeforeLast("."))
        val timestamp = SimpleDateFormat("_HHmmss", Locale.getDefault()).format(Date())
        val outputName = FilenameSanitizer.sanitizeFileName("${baseName}解锁$timestamp$ext")
        val mime = getMimeType(ext)
```

改为（增加 `lrc` 形参 + `lrcName` 计算）：

```kotlin
    private fun saveDecrypted(
        originalName: String,
        decryptedFile: File,
        audioFmt: AudioFormat,
        lrc: String? = null
    ): SaveResult {
        val ext = extFor(audioFmt)
        val baseName = FilenameSanitizer.sanitizeBase(originalName.substringBeforeLast("."))
        val timestamp = SimpleDateFormat("_HHmmss", Locale.getDefault()).format(Date())
        val outputName = FilenameSanitizer.sanitizeFileName("${baseName}解锁$timestamp$ext")
        val lrcName = FilenameSanitizer.sanitizeFileName("${outputName.substringBeforeLast('.')}.lrc")
        val mime = getMimeType(ext)
```

然后在三个保存分支里各加一处 `.lrc` 写入：

(a) 自定义 SAF 目录分支，把：

```kotlin
        OutputDirStore.getTreeUri(this)?.let { tree ->
            customDirUri = saveViaTreeUri(tree, outputName, mime, decryptedFile)
        }
```

改为：

```kotlin
        OutputDirStore.getTreeUri(this)?.let { tree ->
            customDirUri = saveViaTreeUri(tree, outputName, mime, decryptedFile)
            if (customDirUri != null && lrc != null) saveLrcViaTreeUri(tree, lrcName, lrc)
        }
```

(b) MediaStore 分支，把：

```kotlin
        if (customDirUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri = saveViaMediaStore(outputName, mime, decryptedFile)
        }
```

改为：

```kotlin
        if (customDirUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUri = saveViaMediaStore(outputName, mime, decryptedFile)
            if (mediaStoreUri != null && lrc != null) saveLrcViaMediaStore(lrcName, lrc) // best-effort
        }
```

(c) 旧版直存分支，把：

```kotlin
                if (dir.exists() || dir.mkdirs()) {
                    val out = File(dir, outputName)
                    copyFile(decryptedFile, out)
                    // Make it visible to music apps / the media scanner.
                    MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf(mime), null)
                    publicPath = out.absolutePath
                }
```

改为：

```kotlin
                if (dir.exists() || dir.mkdirs()) {
                    val out = File(dir, outputName)
                    copyFile(decryptedFile, out)
                    // Make it visible to music apps / the media scanner.
                    MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf(mime), null)
                    publicPath = out.absolutePath
                    if (lrc != null) runCatching { File(dir, lrcName).writeBytes(lrcBytes(lrc)) }
                }
```

- [ ] **Step 6: 新增三个 `.lrc` 写入助手（放在 `saveViaTreeUri` 之后）**

```kotlin
    /** UTF-8 + BOM bytes for an exported .lrc (BOM helps desktop players detect CJK). */
    private fun lrcBytes(lrc: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + lrc.toByteArray(Charsets.UTF_8)

    /** Best-effort: write the .lrc next to the audio in the user's SAF tree. */
    private fun saveLrcViaTreeUri(treeUri: Uri, lrcName: String, lrc: String) {
        runCatching {
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return
            val doc = dir.createFile("application/octet-stream", lrcName) ?: return
            contentResolver.openOutputStream(doc.uri)?.use { it.write(lrcBytes(lrc)) }
        }
    }

    /**
     * Best-effort: drop the .lrc into Music/FreeNote via MediaStore.Files. Scoped storage may reject
     * a non-media file here; the embedded lyric tag is the guaranteed fallback, so failure is fine.
     */
    private fun saveLrcViaMediaStore(lrcName: String, lrc: String) {
        runCatching {
            val relPath = Environment.DIRECTORY_MUSIC + "/" + getString(R.string.output_folder)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, lrcName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(lrcBytes(lrc)) }
        }
    }
```

- [ ] **Step 7: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`DocumentFile`/`MediaStore`/`Environment`/`ContentValues`/`Uri` 均已在 MainActivity import）。
若报 `R.id.action_fetch_lyrics` 未解析，说明 Task 9（menu）尚未做 —— 可先做 Task 9 再回来编译，或一起编译。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat: wire lyric fetch + .lrc export + toggle into decrypt flow

maybeFetchLyrics surfaces pre-existing/embeds fetched lyrics; saveDecrypted
exports a .lrc beside the audio (SAF/legacy reliable, MediaStore best-effort).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: 菜单项 + 文案

**Files:**
- Modify: `app/src/main/res/menu/main_menu.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 菜单加歌词开关**

在 `main_menu.xml` 的 `action_fetch_covers` `<item>` 之后插入：

```xml
    <item
        android:id="@+id/action_fetch_lyrics"
        android:checkable="true"
        android:title="@string/menu_fetch_lyrics"
        app:showAsAction="never" />
```

- [ ] **Step 2: 新增文案**

在 `strings.xml` 的 `menu_fetch_covers` 一行之后插入：

```xml
    <string name="menu_fetch_lyrics">联网补全歌词</string>
    <string name="tab_cover">封面</string>
    <string name="tab_lyrics">歌词</string>
    <string name="lyrics_empty">暂无歌词</string>
    <string name="cd_tab_cover">显示封面</string>
    <string name="cd_tab_lyrics">显示歌词</string>
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（资源 + Task 8 的 `R.id.action_fetch_lyrics` 引用此时均能解析）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/menu/main_menu.xml app/src/main/res/values/strings.xml
git commit -m "$(cat <<'EOF'
feat: add lyric toggle menu item + lyric/tab strings

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: 自定义滚动视图 `LyricsView`

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/LyricsView.kt`

无 JVM 单测（自定义 View，真机验证滚动/高亮）。

- [ ] **Step 1: 实现 `LyricsView.kt`**

```kotlin
package com.ncmdecrypt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs

/**
 * Renders time-synced LRC lyrics that scroll with playback: the active line is centered and
 * highlighted, others dim. Display-only (no seek-by-tap in v1). Feed lines via [setLines] and
 * drive it from playback position via [updatePosition]. Self-contained; no player dependency.
 */
class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var lines: List<LrcLine> = emptyList()
    private var activeIndex = -1
    private var currentOffset = 0f
    private var targetOffset = 0f

    private val activePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(18f); isFakeBoldText = true
    }
    private val inactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(16f) }
    private val lineSpacing = dp(16f)
    private val emptyText = context.getString(R.string.lyrics_empty)

    private val onSurface =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)

    fun setLines(newLines: List<LrcLine>) {
        lines = newLines
        activeIndex = -1
        currentOffset = 0f
        targetOffset = 0f
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        if (lines.isEmpty()) return
        var idx = -1
        for (i in lines.indices) { if (lines[i].timeMs <= positionMs) idx = i else break }
        if (idx != activeIndex) {
            activeIndex = idx
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (lines.isEmpty()) {
            inactivePaint.color = onSurfaceVariant
            inactivePaint.alpha = 150
            canvas.drawText(emptyText, cx - inactivePaint.measureText(emptyText) / 2f, cy, inactivePaint)
            return
        }
        // Ease the scroll toward the active line being centered.
        targetOffset = activeIndex.coerceAtLeast(0) * lineHeight()
        currentOffset += (targetOffset - currentOffset) * 0.2f
        if (abs(targetOffset - currentOffset) > 0.5f) postInvalidateOnAnimation()

        for (i in lines.indices) {
            val y = cy + i * lineHeight() - currentOffset
            if (y < -lineHeight() || y > height + lineHeight()) continue
            val paint = if (i == activeIndex) activePaint else inactivePaint
            paint.color = if (i == activeIndex) onSurface else onSurfaceVariant
            paint.alpha = if (i == activeIndex) 255 else 140
            val text = lines[i].text
            canvas.drawText(text, cx - paint.measureText(text) / 2f, y, paint)
        }
    }

    private fun lineHeight(): Float = activePaint.textSize + lineSpacing
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/LyricsView.kt
git commit -m "$(cat <<'EOF'
feat: add LyricsView (synced scrolling lyric display)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: 播放器布局加「封面/歌词」Tab

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`

在 `fullPlayer` ConstraintLayout 内：加 `TabLayout` + `LyricsView`，并把 `coverContainer` 顶部约束改到 tab 之下。

- [ ] **Step 1: 在 `editButton` `</...>` 之后、`coverContainer` 之前插入 Tab + LyricsView**

```xml
            <com.google.android.material.tabs.TabLayout
                android:id="@+id/playerTabs"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/collapseButton"
                app:tabGravity="center"
                app:tabIndicatorColor="?attr/colorPrimary"
                app:tabMode="auto"
                app:tabSelectedTextColor="?attr/colorOnSurface"
                app:tabTextColor="?attr/colorOnSurfaceVariant">

                <com.google.android.material.tabs.TabItem
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/tab_cover" />

                <com.google.android.material.tabs.TabItem
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/tab_lyrics" />
            </com.google.android.material.tabs.TabLayout>

            <com.ncmdecrypt.LyricsView
                android:id="@+id/lyricsView"
                android:layout_width="0dp"
                android:layout_height="0dp"
                android:layout_marginTop="12dp"
                android:layout_marginBottom="12dp"
                android:visibility="gone"
                app:layout_constraintBottom_toTopOf="@id/seekBar"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/playerTabs" />
```

- [ ] **Step 2: `coverContainer` 顶部约束改到 tab 之下**

把 `coverContainer` 的：

```xml
                app:layout_constraintTop_toBottomOf="@id/collapseButton"
```

改为：

```xml
                app:layout_constraintTop_toBottomOf="@id/playerTabs"
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（`TabLayout`/`TabItem` 来自已依赖的 Material 库；`LyricsView` 来自 Task 10）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml
git commit -m "$(cat <<'EOF'
feat: add cover/lyrics TabLayout + LyricsView to full player

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: `PlayerUiController` 接 Tab + 歌词加载 + 滚动

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/PlayerUiController.kt`

- [ ] **Step 1: 加 import + 视图字段 + token**

在 import 区加：

```kotlin
import com.google.android.material.tabs.TabLayout
```

在视图字段区（`nextButton` 声明之后）加：

```kotlin
    private val playerTabs: TabLayout = activity.findViewById(R.id.playerTabs)
    private val lyricsView: LyricsView = activity.findViewById(R.id.lyricsView)
```

在 `coverLoadToken` 声明之后加：

```kotlin
    private val lyricsLoadToken = AtomicLong(0)
```

- [ ] **Step 2: `attach()` 里注册 tab 切换（在 `repeatButton.setOnClickListener { ... }` 之后）**

```kotlin
        playerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showLyrics(tab.position == 1) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        showLyrics(false)
```

- [ ] **Step 3: 切歌时加载歌词 —— 在 `render` 的 track 变化分支补一行**

把：

```kotlin
        val track = state.track
        if (track.id != lastTrackId) {
            lastTrackId = track.id
            bindTrackText(track)
            loadCover(track)
        }
```

改为：

```kotlin
        val track = state.track
        if (track.id != lastTrackId) {
            lastTrackId = track.id
            bindTrackText(track)
            loadCover(track)
            loadLyrics(track)
        }
```

- [ ] **Step 4: 每 tick 推进歌词高亮 —— 在 `render` 的进度段补一行**

把：

```kotlin
        durationText.text = formatTime(dur)
        miniProgress.setProgressCompat((fraction * 1000).toInt(), true)
```

改为：

```kotlin
        durationText.text = formatTime(dur)
        miniProgress.setProgressCompat((fraction * 1000).toInt(), true)
        lyricsView.updatePosition(state.positionMs)
```

- [ ] **Step 5: 新增 `showLyrics` + `loadLyrics`（放在 `loadCover` 之后）**

```kotlin
    /** Toggle the cover/lyrics tab: lyrics replaces cover + title + artist; transport stays. */
    private fun showLyrics(show: Boolean) {
        lyricsView.visibility = if (show) View.VISIBLE else View.GONE
        val coverVis = if (show) View.INVISIBLE else View.VISIBLE
        coverContainer.visibility = coverVis
        fullTitle.visibility = coverVis
        fullArtist.visibility = coverVis
    }

    private fun loadLyrics(track: Track) {
        val token = lyricsLoadToken.incrementAndGet()
        val path = track.lyricsPath
        coverExecutor.execute {
            val lines = path?.let {
                runCatching {
                    LrcParser.parse(File(it).readText(Charsets.UTF_8).removePrefix("﻿"))
                }.getOrNull()
            } ?: emptyList()
            activity.runOnUiThread {
                if (token != lyricsLoadToken.get()) return@runOnUiThread
                lyricsView.setLines(lines)
            }
        }
    }
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/PlayerUiController.kt
git commit -m "$(cat <<'EOF'
feat: wire lyrics tab — load .lrc, scroll with playback position

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: 文档更新（CLAUDE.md + README）

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: 更新 `CLAUDE.md` 的「Network exception」段落**

把现有：

```
**Network exception (the only one):** decryption is 100% offline, but `CoverFetcher.kt`
optionally looks up *album art* online for outputs that ship cover-less (QMC/KGM/KWM —
unlike NCM, whose header carries a cover). It sends only the song's title/artist text,
never audio, and is gated by the user toggle `CoverPrefs` ("联网补全封面", default on). The
`INTERNET` permission and `res/xml/network_security_config.xml` exist solely for this. If
you reintroduce a "no network at all" claim anywhere, it is now wrong — keep the audio
path offline, keep cover lookup the single, opt-out-able network use.
```

改为：

```
**Network exception (the only one):** decryption is 100% offline, but the app optionally looks up
*album art* (`CoverFetcher.kt`) **and *lyrics*** (`LyricsFetcher.kt`) online for outputs that ship
without them (QMC/KGM/KWM — and lyrics for all formats, since even NCM headers carry no lyric). It
sends only the song's title/artist text, never audio, and is gated by two independent default-on
toggles: `CoverPrefs` ("联网补全封面") and `LyricsPrefs` ("联网补全歌词"). The `INTERNET` permission and
`res/xml/network_security_config.xml` exist solely for these. If you reintroduce a "no network at
all" claim anywhere, it is now wrong — keep the audio path offline, keep cover + lyric lookup the
single, opt-out-able network use. Lyrics are embedded into the audio tag (`FieldKey.LYRICS`) and
exported as a sidecar `.lrc`; shared HTTP lives in `MusicHttp.kt`.
```

- [ ] **Step 2: 更新 `CLAUDE.md` Code map 表格 —— 在 `CoverFetcher.kt` 那行之后加**

```
| `LyricsFetcher.kt` / `LyricsPrefs.kt` | Online LRC lookup (origin-platform-first, reuses CoverFetcher matching + `MusicHttp`) + its on-by-default toggle. |
| `LrcParser.kt` / `LyricsView.kt` | Pure LRC→`LrcLine` parser + custom synced-scrolling lyric view (player "歌词" tab). |
| `MusicHttp.kt` | Shared best-effort HTTP GET (cover + lyric). |
```

- [ ] **Step 3: 更新 `README.md`（精确改三处 + 加一条功能）**

(a) 第 3 行（顶部副标题括注），把：

```
> Android 本地加密音乐解码器 — 纯 Kotlin，解密全程离线、无需服务器。（仅"联网补全封面"开关开启时，会用歌名/艺术家联网查询专辑封面，可关闭。）
```

改为：

```
> Android 本地加密音乐解码器 — 纯 Kotlin，解密全程离线、无需服务器。（仅"联网补全封面/歌词"开关开启时，会用歌名/艺术家联网查询专辑封面与歌词，均可关闭。）
```

(b) 第 9 行段落末尾的「唯一的联网……」一句，把：

```
唯一的联网是可选的「联网补全封面」（默认开、可在菜单关闭）：当解出的文件自身没有封面时，仅用歌名/艺术家去公开音乐接口查一张专辑封面内嵌进去——只发送文字、不发送音频。
```

改为：

```
唯一的联网是可选的「联网补全封面」与「联网补全歌词」（两个独立开关，默认开、均可在菜单关闭）：当解出的文件自身没有封面/歌词时，仅用歌名/艺术家去公开音乐接口查询，封面内嵌进文件、歌词内嵌进文件并另存一份 `.lrc`——只发送文字、不发送音频。
```

(c) 第 26 行的封面功能条目之后，新增一条歌词功能条目：

```
- ✅ 自动补全歌词：可选「联网补全歌词」按来源平台（QQ/酷狗/酷我，再退网易云）查询带时间轴的 LRC，内嵌进音频并导出同名 `.lrc`；应用内播放器「歌词」tab 随播放滚动，仅发歌名/艺术家
```

(d) 第 121 行 Code map 注释，把 `# Track 模型 + 解密后构建（含封面 sidecar）` 改为 `# Track 模型 + 解密后构建（含封面/歌词 sidecar）`。

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "$(cat <<'EOF'
docs: record lyrics completion as the second opt-out network use

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 14: 全量验证 + 真机冒烟

**Files:** 无（验证）

- [ ] **Step 1: 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL；`LrcParserTest` / `LyricsFetcherTest` / `MetadataEditorTest` / `CoverFetcherTest` 全绿。

- [ ] **Step 2: Release 编译冒烟（确保未破坏打包）**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 3: 真机冒烟（按 CLAUDE.md，手动）**

安装：`adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk`
逐项验证（用真实 `.ncm/.qmc/.kgm/.kwm`，需联网）：
1. 解密后：输出目录（或缓存旁）出现同名 `.lrc`，内容是带时间轴的歌词。
2. 用支持内嵌歌词的播放器打开输出音频，能看到歌词（`FieldKey.LYRICS` 已写入）。
3. 应用内播放 → 全屏播放器「歌词」tab → 歌词随播放滚动、当前行高亮；「封面」tab 切回封面正常。
4. 菜单关闭「联网补全歌词」→ 重新解密一首无内嵌歌词的曲子 → 不再联网抓歌词（无 `.lrc`/无嵌入）；
   而本身已内嵌歌词的文件仍能显示（不联网）。
5. byte-stable：本就带歌词的 QMC/KGM 输出，解密前后音频字节一致（仅在无歌词时才写）。

- [ ] **Step 4: 完成分支**

按需进入 `superpowers:finishing-a-development-branch`（合并 / PR / 清理）。

---

## 后续增强（已在 spec §7 记录，功能测试无误后再做 —— 勿忘）

1. **翻译歌词**：网易 `tlyric.lyric` / QQ `trans`（`nobase64=1` 下即裸 LRC）。`LyricsFetcher.fetch`
   返回值可升级为 `data class LyricsResult(lrc, trans?, roma?)`；`LyricsView` 当前行下叠加译文第二行；
   导出可选「双语 LRC」。
2. **罗马音**：网易加 `&rv=-1` 取 `romalrc.lyric`。同翻译处理。

`LrcParser` / `LyricsView` 已是独立单元，叠加第二行不影响主结构。
