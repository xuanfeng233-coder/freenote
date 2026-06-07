# 歌词自动补全 + 导出 — 设计文档

- 日期：2026-06-07
- 状态：已确认，待实现
- 作者：Claude Code（brainstorming）+ 用户确认

## 1. 目标

为 FreeNote 增加**歌词自动补全**，与现有的「联网补全封面」同构：解密后联网抓取
带时间轴的 LRC 歌词，**嵌入音频标签**并**导出为旁挂 `.lrc` 文件**，同时在应用内播放器中
以**单独 tab** 显示随播放滚动的歌词。

约束沿用封面那套：

- 纯离线解密不变；联网只发送歌曲**标题/歌手文本**，绝不发送音频。
- 由独立的用户开关「联网补全歌词」（默认开）门控，可关闭。
- best-effort：任何抓取/写入失败都不影响解密结果。
- **byte-stable 不变量**：只有在文件**本身没有歌词**时才写；已带歌词的输出保持字节不变。
- 开关关闭时**完全不联网**，但文件里**已内嵌**的歌词仍会被导出 + 显示（不消耗网络）。

## 2. 总体架构（镜像封面流水线）

封面流程为：`搜索 → 拿图 → 嵌入文件标签 → TrackBuilder 写 .cover 旁挂 → 播放器读`。
歌词照搬，额外增加三样封面没有的东西：**可导出的 `.lrc` 文件**、**LRC 解析器**、**应用内歌词视图**。

解密后（已有的后台线程 `decryptNext`，见 `MainActivity.kt:589`+）：

```
val lrc: String? = maybeFetchLyrics(toSave, audioFmt, formatTag, displayName)
   // 1) 先读文件已有 FieldKey.LYRICS → 非空就直接返回（不联网）
   // 2) 否则若 LyricsPrefs 开 → LyricsFetcher.fetch() 联网抓 → embedLyricsIfMissing 写入标签 → 返回
   // 3) 否则 → null
val saved = saveDecrypted(displayName, toSave, audioFmt, lrc)
   // 扩展：在公共输出目录写 <音频名>.lrc（导出）
val track = TrackBuilder.build(..., lyrics = lrc)
   // 在缓存副本旁写 <音频名>.lrc → Track.lyricsPath（供应用内显示）
```

LRC 字符串显式贯穿三处（抓取 → 公共导出 → 缓存旁挂/Track），避免重复读标签。

## 3. 抓取层 `LyricsFetcher.kt`

与 `CoverFetcher` 同构：origin-platform-first、严格 artist+title 匹配、session 缓存、纯
`HttpURLConnection` + `org.json`。`fetch(platformTag, query): String?` 返回带时间轴的 LRC 文本。

**复用**（这些在 `CoverFetcher` 中已是 public、已有单测）：

- `CoverFetcher.Query`、`CoverFetcher.parseFromFilename`
- `CoverFetcher.textMatch(title, title)`、`CoverFetcher.artistMatches(queryArtist, resultArtists)`
- 匹配判定：`textMatch(q.title, resultTitle) && artistMatches(q.artist, resultArtists)`
  （`CoverFetcher.accept` 是 private，故在 LyricsFetcher 内用上面两个 public 函数自行组合）

**HTTP 抽出**：把 `CoverFetcher` 现有的私有 `openGet / httpGetString / httpGetBytes / enc`
抽到新文件 `MusicHttp.kt`（`object MusicHttp { fun getString(url, referer?): String?;
fun getBytes(url, referer?): ByteArray?; fun enc(s): String }`），`CoverFetcher` 改为委托，
`LyricsFetcher` 复用。纯重构，行为不变。

**Provider 排序**（歌词无 iTunes；回退到覆盖率最高的网易）：

```
NCM        -> [Netease, QQ, Kugou, Kuwo]
QMC, TM    -> [QQ, Netease, Kugou, Kuwo]
KGM/KGMA/KGG/VPR -> [Kugou, Netease, QQ, Kuwo]
KWM, KWMA  -> [Kuwo, Netease, QQ, Kugou]
else       -> [Netease, QQ, Kugou, Kuwo]
```

命中（强匹配 + 拿到非空真实 LRC）即返回；缓存命中/未命中（hits/misses，键 = 归一化 `artist|title`）。

### 3.1 各平台 API（已逐一实测验证 ✅，2026-06-07）

搜索那一跳封面代码已有，下表是**第二跳**（取词）。每个 provider 自己做一次搜索拿到所需 id，
再调取词端点；用搜索结果的 artist+title 做强匹配后才接受。

| 平台 | id 来源（搜索响应路径） | 取词端点 | 取词路径 / 解码 |
|---|---|---|---|
| **网易** | `result.songs[].id`（CoverFetcher 已解析，`optLong("id")`） | `GET https://music.163.com/api/song/lyric?id=<id>&lv=1&kv=1&tv=-1` | 裸 JSON（Content-Type 谎称 text/plain，照样按 JSON 解）。`lrc.lyric` = 同步 LRC，无需解码。`pureMusic:true` 或只有 `[by:]/作词` 元信息行 → 视作无词跳过。Referer/UA 可带可不带。 |
| **QQ** | `data.song.list[].mid`（**新增解析** 这个 song 级 mid，CoverFetcher 当前只取了 album.mid） | `GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&format=json&nobase64=1` | **必须** `Referer: https://y.qq.com`（否则 retcode -1310）。`format=json`（非 jsonp，免去 callback 包裹）。`nobase64=1` → `lyric` 字段直接是裸 LRC。成功判 `retcode==0`。`trans` 为翻译（v1 不用）。 |
| **酷狗** | `data.info[].hash`（CoverFetcher 已解析，`optString("hash")`） | 两跳：① `GET https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=<HASH>` → `candidates[].id` + `candidates[].accesskey`；② `GET https://lyrics.kugou.com/download?ver=1&client=pc&id=<id>&accesskey=<key>&fmt=lrc&charset=utf8` | `content` 字段为 base64（`android.util.Base64.decode(..., DEFAULT)` → UTF-8）。**用 `fmt=lrc`，不要 `fmt=krc`**（krc 是加密+zlib 专有格式）。`candidates` 空 = 无词。accesskey 时效短，①②要连着调，不缓存。**两跳都用 https**（避免 `network_security_config.xml` 的 cleartext 限制；研究确认 krcs/lyrics 两个 host 均支持 https）。 |
| **酷我** | `abslist[].MUSICRID` 形如 `MUSIC_<num>`，去前缀取 `<num>`（**新增解析**，CoverFetcher 当前未取） | `GET https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=<num>` | 裸 JSON（Content-Type 谎称 text/html）。失败为 `data:null` + `status:301`，**必须先判 `data` 非空 / `status==200`**。`data.lrclist[]` 每项 `{lineLyric:String, time:String}`，`time` 是**字符串秒**（如 `"29.26"`），需 `Float.parse` 后重组：`m=floor(t/60); s=t-60m; "[%02d:%05.2f]%s".format(m,s,lineLyric)`。空 `lrclist` → 无词。前几行常是元信息，保留无妨。**不要**用 `www.kuwo.cn/openapi/.../getlyric`（需签名）。 |

均无需请求签名 / AES / JS VM，纯 `HttpURLConnection` + `org.json`（+ `android.util.Base64` 给酷狗）可完成。
区域可达性与封面调用一致：失败一律 best-effort 返回 null，按 provider 顺序回退。

### 3.2 「真实歌词」判定

抓到的字符串需过滤掉**假命中**：只含 `[ti:]/[ar:]/[al:]/[by:]/作词/作曲` 等元信息、无任何带
时间轴的实际歌词行的，视作无词，继续下一个 provider。判据：至少存在一行 `[mm:ss(.xx)]` 后跟非空文本。
（此判定可复用 §5 的 `LrcParser`：解析后存在 ≥1 条非空 `LrcLine` 才算有词。）

## 4. 写入 + 导出层

### 4.1 嵌入标签 `MetadataEditor`

新增：

```kotlin
fun embedLyricsIfMissing(file: File, fmt: AudioFormat, lrc: String?) {
    if (!isEmbeddable(fmt)) return            // 复用：FLAC/MP3/OGG/WAV
    val text = lrc?.trim().orEmpty(); if (text.isEmpty()) return
    try {
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tagOrCreateAndSetDefault
        val existing = runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()
        if (existing.isNullOrBlank()) { tag.setField(FieldKey.LYRICS, text); audioFile.commit() }
    } catch (_: Exception) { /* bonus；吞掉，同 embedIfMissing */ }
}

fun readLyrics(file: File): String? =
    runCatching { AudioFileIO.read(file).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() } }.getOrNull()
```

- `FieldKey.LYRICS` 已存在于 jaudiotagger 3.0.1（项目现有依赖），映射：MP3/WAV → `USLT` 帧，
  FLAC/OGG → `LYRICS` vorbis comment。
- 同步 LRC **整段原文（含时间轴）**塞进非同步歌词字段是业界通行做法，**不碰 SYLT**。
- 门控、blank-only、changed-才-commit，与 `embedIfMissing` 一致 → 已带词的输出字节不变。
- jaudiotagger 按**扩展名**选 reader，故须在文件已具备正确 `.flac/.mp3/.ogg/.wav` 扩展名后调用
  （decrypt 流程里 `toSave` 已是带扩展名的文件，满足）。

### 4.2 旁挂 `.lrc`（导出）

- **命名**：`<音频名去扩展名> + ".lrc"`，与音频同目录。
  **⚠️ 与封面 `.cover` 不同**：封面用 `name + ".cover"`（`song.flac.cover`），歌词必须用
  `nameWithoutExtension + ".lrc"`（`song.lrc`），否则播放器配不上。过 `FilenameSanitizer`。
- **编码**：UTF-8 + BOM（`EF BB BF` + `lrc.toByteArray(UTF_8)`），利于桌面播放器正确识别 CJK。
- **三种输出模式**（`saveDecrypted` 扩展，接收 `lrc: String?`，非空才写）：
  - 自定义 SAF 目录：经 `DocumentFile.createFile("application/octet-stream", "<name>.lrc")` 写入（镜像 `saveViaTreeUri`，**可靠**）。
  - 旧版 Android ≤9 直存 `/sdcard/FreeNote`：直接 `File` 写 + `MediaScannerConnection.scanFile`（**可靠**）。
  - MediaStore (Android 10+)：**best-effort** 经 `MediaStore.Files.getContentUri("external")` +
    `RELATIVE_PATH = Music/FreeNote`、mime `application/octet-stream` 插入；失败则**以内嵌标签兜底**
    （歌词随音频走，分享/移动文件也带着）。不依赖其成功。
  - 缓存副本旁：**始终**写一份 `<name>.lrc`（供应用内显示，见 §5）。
- 分享路径（FileProvider 单文件）不变：分享音频时歌词已在标签内随文件走。

## 5. 应用内滚动歌词（单独 tab）

### 5.1 `LrcParser.kt`（纯函数，可测）

```
parse(lrcText: String): List<LrcLine>     // LrcLine(timeMs: Long, text: String)
```

- 处理一行多时间戳 `[00:12.34][01:20.00]词`（展开成多条）。
- 时间戳支持 `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`。
- 跳过纯 ID 标签行（`[ti:]/[ar:]/[al:]/[by:]/[offset:]` 等）与无时间戳行。
- 按 timeMs 升序排序。空文本行可保留为空行（节奏停顿）或过滤——实现时保留非空即可。

### 5.2 `LyricsView.kt`（自定义 `View`）

- 持有 `List<LrcLine>`；`updatePosition(ms: Long)` 二分定位当前行，平滑滚动使当前行居中，
  当前行高亮（更大/更亮），其余暗淡；`invalidate()` 重绘。
- 无歌词时显示空状态文案「暂无歌词」。
- v1 **只显示、不支持拖动选段跳转**（留作后续）。
- 自包含、不依赖播放器内部，独立可测/可复用。

### 5.3 播放器接线 `PlayerUiController` + 布局

- `Track` 增加 `lyricsPath: String?`（与 `coverPath` 同构）。
- 切歌时（`track.id != lastTrackId` 分支）在已有的 `coverExecutor` 后台线程读 `lyricsPath`、
  `LrcParser.parse`，回主线程喂给 `LyricsView`。
- 每个 render tick 用**已有的** `state.positionMs`（ticker 每 250ms publish）调 `lyricsView.updatePosition()`。
- **单独 tab UI**：在全屏播放器顶部（collapse/edit 行之下、cover 之上）加
  `com.google.android.material.tabs.TabLayout`，两个 tab：「封面」「歌词」。
  - 「封面」tab：显示 `coverContainer + fullTitle + fullArtist`，隐藏歌词面板。
  - 「歌词」tab：隐藏上述三者，显示 `lyricsPanel`（容纳 `LyricsView`），其约束 top = tabs 底、
    bottom = seekBar 顶，占据更大竖向空间便于阅读。
  - `seekBar / position / duration / 传输控件`两个 tab 都常驻可见。
  - 不引入 `ViewPager2`（会与 bottom sheet 拖拽、现有 `applySlide` 的 alpha/scale 动效冲突）。
    tabs 与 lyricsPanel 是 `fullPlayer` 子节点，随其一起淡入淡出；lyricsPanel 不参与 cover 的缩放动效。

## 6. 开关 / 文案 / 文档 / 测试

- **开关**：`menu/main_menu.xml` 新增 checkable `action_fetch_lyrics`；`LyricsPrefs.kt`（独立 pref store
  `lyrics_fetch` / `online_enabled`，默认 ON，镜像 `CoverPrefs`）。`MainActivity` 菜单初始化/点击照搬封面那段。
- **文案**：全部进 `strings.xml`（含无障碍 `contentDescription`）。新增至少：
  `menu_fetch_lyrics`「联网补全歌词」、`tab_cover`「封面」、`tab_lyrics`「歌词」、`lyrics_empty`「暂无歌词」、
  以及 tab 的 contentDescription。
- **文档**：更新 `CLAUDE.md` 与 `README.md` 的「唯一联网」段落：由「仅封面联网补全」改为
  「封面**与歌词**联网补全」，仍强调只发标题/歌手文本、音频全离线、可 opt-out。`CLAUDE.md` Code map
  增加 `LyricsFetcher.kt / LyricsPrefs.kt / LrcParser.kt / LyricsView.kt / MusicHttp.kt` 条目。
- **测试**（`app/src/test`，纯 JVM 逻辑）：
  - `LrcParserTest`：多时间戳、各时间格式、ID 标签过滤、排序、空/畸形输入。
  - `LyricsFetcherTest`：provider 排序、强匹配判定、酷我 `time` 字符串重组、各平台响应 JSON 解析
    （用 fixture 字符串，不打网络）、「真实歌词」假命中过滤。
  - `MetadataEditorTest` 增量：`embedLyricsIfMissing` 的 blank-only / changed-才-commit / 不可嵌容器跳过。

## 7. 后续增强（功能测试无误后再做 —— 不要忘记）

> **明确记录，避免遗漏**：v1 只写**原文 LRC**。待歌词主链路（抓取/嵌入/导出/显示）功能测试无误后，
> 再增加以下两项：

1. **翻译歌词**：网易 `tlyric.lyric`、QQ `trans` 字段都已能拿到（QQ `nobase64=1` 下 `trans` 即裸 LRC）。
   做法：抓取层一并返回翻译 LRC；显示层在 `LyricsView` 当前行下叠加译文第二行；导出可选「双语 LRC」
   （按时间戳合并原文/译文）。
2. **罗马音**：网易加 `&rv=-1` 返回 `romalrc.lyric`（日文等）。同翻译，作为可选第二行/导出。

设计时已为此预留：`LyricsFetcher` 返回值未来可从 `String?` 升级为 `data class LyricsResult(lrc, trans?, roma?)`；
`LrcParser`/`LyricsView` 已是独立单元，叠加第二行不影响主结构。

## 8. 改动文件清单

**新增（6）**：`MusicHttp.kt`、`LyricsFetcher.kt`、`LyricsPrefs.kt`、`LrcParser.kt`、`LyricsView.kt`、
对应测试（`LrcParserTest`、`LyricsFetcherTest`）。

**修改**：
- `CoverFetcher.kt` — HTTP 委托给 `MusicHttp`（纯重构）。
- `MetadataEditor.kt` — 增 `embedLyricsIfMissing` / `readLyrics`。
- `TrackBuilder.kt` — 读歌词、写缓存旁挂 `.lrc`、`build(..., lyrics)`、`writeLyricsSidecar`。
- `Track.kt` — 增 `lyricsPath: String?`。
- `MainActivity.kt` — `maybeFetchLyrics`、菜单 `action_fetch_lyrics`、`saveDecrypted(..., lrc)` 写公共旁挂、
  穿线 lrc 到 `TrackBuilder.build`。
- `res/layout/activity_main.xml` — 全屏播放器加 `TabLayout` + `lyricsPanel`(LyricsView)。
- `res/menu/main_menu.xml` — 加 `action_fetch_lyrics`。
- `res/values/strings.xml` — 新增文案/无障碍。
- `CLAUDE.md`、`README.md` — 更新联网说明 + Code map。

## 9. 验证

- JVM 单测：`./gradlew :app:testDebugUnitTest`。
- 真机冒烟（CLAUDE.md 要求）：`adb push` 真实 `.ncm/.qmc/.kgm/.kwm` → 解密 →
  ① 检查输出目录/缓存旁出现 `.lrc` 且内容正确；② 音频标签内含歌词（用支持内嵌歌词的播放器验证）；
  ③ 应用内播放器「歌词」tab 随播放滚动高亮；④ 关闭「联网补全歌词」后不再联网、已内嵌歌词仍显示。
