# 🎵 FreeNote · 音频解锁 (NcmDecrypt)

> Android 本地加密音乐解码器 — 纯 Kotlin，解密全程离线、无需服务器。（仅"联网补全封面/歌词"开关开启时，会用歌名/艺术家联网查询专辑封面与歌词，均可关闭。）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84)
![Language](https://img.shields.io/badge/Kotlin-100%25-7f52ff)

把你**已经下载到本地**的加密音乐（网易云 `.ncm`、QQ音乐 `.qmc/.mflac`、酷狗 `.kgm`、酷我 `.kwm` 等）解码还原成普通的 FLAC / MP3 / OGG 文件。**解密全程在手机本地完成，不上传任何音频文件、不连任何解密服务器。** 唯一的联网是可选的「联网补全封面」与「联网补全歌词」（两个独立开关，默认开、均可在菜单关闭）：当解出的文件自身没有封面/歌词时，仅用歌名/艺术家去公开音乐接口查询，封面内嵌进文件、歌词内嵌进文件并另存一份 `.lrc`——只发送文字、不发送音频。

> ⚖️ **免责声明**：本工具仅用于**个人合法获取的音乐文件的本地格式转换与互操作**（例如把你已购买/已下载的歌曲转成通用格式在自有设备上播放）。请勿用于侵犯版权、商业分发或任何违反你所在地区法律法规及平台服务条款的用途。开发者不对滥用承担责任。

---

# 📱 用户版

面向直接使用 App 的用户：怎么装、怎么用、哪些情况需要特殊处理。

## 这是什么

一个本地音乐解锁小工具。很多音乐 App 下载的歌曲是**加密格式**，换个播放器就放不了。本 App 把这些文件解开，还原成所有播放器都认识的标准音频，并内置播放器和标签编辑器。

- ✅ 解密纯离线，音频文件不出手机
- ✅ 一次可多选、批量解密
- ✅ 内置播放器（后台播放 + 锁屏控制）+ 改标题/艺术家/专辑/封面
- ✅ 自动补全封面：NCM 用文件自带封面；QMC/酷狗/酷我 等文件本身没封面时，可选「联网补全封面」按来源平台（QQ/酷狗/酷我，再退网易云/iTunes）查询内嵌，仅发歌名/艺术家
- ✅ 自动补全歌词：可选「联网补全歌词」按来源平台（QQ/酷狗/酷我，再退网易云）查询带时间轴的 LRC，内嵌进音频并导出同名 `.lrc`；应用内播放器「歌词」tab 随播放滚动，仅发歌名/艺术家
- ✅ 解密后可一键分享

## 下载安装

1. 到本仓库的 **[Releases](../../releases)** 页面下载最新版 APK。
2. 传到手机，点击安装（首次需在系统设置里允许「安装未知来源应用」）。
3. 系统要求：**Android 8.0（API 26）及以上**。

## 支持哪些格式

| 平台 | 文件后缀 | 能否直接离线解密 |
|------|---------|----------------|
| 网易云音乐 | `.ncm` | ✅ 可以 |
| QQ音乐 / Moo音乐 | `.qmc*` / `.mflac*` / `.mgg*` / `.bkc*` / `.tm*` 等 | ✅ 旧版下载的可以（见下方注意事项） |
| 酷狗音乐 | `.kgm` / `.kgma` / `.vpr` | ✅ 可以 |
| 酷狗音乐 | `.kgg` | ❌ 新版格式，密钥在客户端，无法离线解 |
| 酷我音乐 | `.kwm` / `.kwma` | ✅ 可以 |

## 怎么用（三步）

1. 点 **「选择文件」** → 在系统文件选择器里挑要解密的歌曲（可一次多选）。
2. 点 **「全部解密」** → 等进度跑完。
3. 解密结果默认保存到系统音乐目录 **`Music/FreeNote`**。每首歌后面会出现 **播放 / 编辑标签 / 分享** 按钮。

> Android 10 及以上不需要授予特殊存储权限，文件可在系统音乐 App 或文件管理器的「音乐/FreeNote」中找到。
> Android 9 及以下可能会请求旧版存储权限，用于写入 `/sdcard/FreeNote`。

## ⚠️ 重要：QQ音乐 / 酷狗 新版可能需要「版本回退」

部分**新版客户端**下载的文件**没有把解密密钥写进文件里**（密钥被存进了 App 自己的私有数据库），这类文件本 App **无法直接离线解密**。解决办法二选一：

### 方法一（推荐）：用旧版客户端下载

旧版客户端会把密钥**内嵌进文件**，下载下来即可直接解密。

| 平台 | 现象 | 回退到的版本 |
|------|------|------------|
| **QQ音乐** | 新版下载的 `.mflac0.flac` / `.mgg1.ogg`（文件尾部是 `STag` / `musicex`，约客户端 **≥11.6**） | 安装 **10.12.0.8**（v11.6 之前，密钥内嵌） |
| **酷狗音乐** | 新版下载的是 `.kgg`（约安卓 **≥20.x**），无法离线解 | 回退到安卓 **12.5.6**（最后一个支持 `.kgm` 的版本，约 2024-11） |

> 拿旧版安装包：到 APKMirror / APKPure 等站点搜对应版本号下载。装旧版前可能需要先卸载新版。

### 方法二：导入密钥（需要能拿到客户端数据库）

点顶部 **「导入密钥」**，导入以下任一格式后再解密：

- **MMKV 文件**：QQ音乐客户端的 `MMKVStreamEncryptId`（明文 vault）
- **JSON**：`{ "文件名": "base64密钥", ... }`
- **文本**：每行 `文件名<TAB或=或,>base64密钥`

> 拿密钥数据库需要从 **PC 客户端**（`mmkv` 目录）或**已 root 的安卓**（`/data/data/...`）导出。
> **未 root 的安卓读不到其它 App 的私有目录**——这种情况请改用「方法一」装旧版。

## 注意事项 / 常见问题

- **网易云 `.ncm`、酷狗 `.kgm/.vpr`、酷我 `.kwm`、以及内嵌密钥的 QQ音乐文件**：无需任何额外操作，直接解。
- **`.kgg` 文件 / QQ音乐 `STag`·`musicex` 文件**：本 App **解不了**（密钥不在文件里）。请按上面的版本回退或导入密钥处理。
- **APE 格式**：能解密，但内置播放器**不支持预览播放**（可解出来后用别的播放器放）。
- 输出文件名形如 `原曲名解锁_HHmmss.flac`。
- 选好文件后切到后台再回来仍可解密（已持久化文件访问权限）。

---

# 🛠️ 开发者版

面向想了解实现、二次开发或贡献代码的人。**贡献者请同时阅读 [`CLAUDE.md`](CLAUDE.md)**，那里有改代码时的关键不变量与避坑清单。

## 技术栈

- **语言/构建**：Kotlin 1.9.22，AGP 8.2.2，Gradle 8.5（wrapper），Java 17
- **SDK**：`minSdk 26` / `compileSdk 34` / `targetSdk 34`
- **UI**：Material 3（深色主题，全部走主题色令牌，零硬编码 hex），ViewBinding
- **播放**：AndroidX Media3（`media3-exoplayer` + `media3-session`），后台 `MediaSessionService`
- **动效**：`androidx.dynamicanimation`（弹簧）+ `androidx.palette`（封面取色）
- **标签**：`net.jthink:jaudiotagger`（读写 FLAC/MP3/OGG/M4A/WAV 元数据）

## 项目结构

```
ncm-android/
├── app/
│   ├── build.gradle.kts          # 模块构建配置、签名、依赖
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ncmdecrypt/
│       │   ├── MusicDecoder.kt         # 所有格式的核心解码器（内存 + 流式）
│       │   ├── MmkvParser.kt           # Tencent MMKV vault 只读解析（取 ekey）
│       │   ├── EkeyStore.kt            # 导入密钥持久化 + 文件名/词干匹配
│       │   ├── MainActivity.kt         # UI 入口：多选 / 批量解密 / 导入密钥 / 分享
│       │   ├── FileListAdapter.kt      # 文件列表（状态 / 封面 / 播放·编辑·分享）
│       │   ├── PlaybackService.kt      # Media3 MediaSessionService（后台播放）
│       │   ├── PlayerHub.kt            # 持有 MediaController + 队列，广播 PlayerState
│       │   ├── PlayerUiController.kt    # 底部 sheet 播放器 + Apple Music 风格动效
│       │   ├── Track.kt / TrackBuilder.kt   # Track 模型 + 解密后构建（含封面/歌词 sidecar）
│       │   └── MetadataEditor.kt / MetadataEditSheet.kt   # jAudioTagger 标签编辑
│       └── res/                        # 布局 / drawable / 主题 / strings
├── build.gradle.kts                # 项目级插件版本
├── settings.gradle.kts
├── gradle.properties
├── keystore.properties.example     # 签名配置模板（复制为 keystore.properties）
└── gradlew
```

## 核心架构

- **`MusicDecoder.kt`** — 统一解码器，两种模式：`decrypt(data, filename)`（小文件内存）与 `decryptFile(in, out, filename)`（大文件 64KB 流式，OOM-proof）。`extractNcmInfo()` 从 `.ncm` 头部提取标题/艺术家/专辑 + 封面。
- **播放链路**：`PlaybackService`（前台 `mediaPlayback` 服务，ExoPlayer + MediaSession，自动处理音频焦点/拔耳机暂停）← `PlayerHub`（MediaController + 队列 + `PlayerState` 广播）← `PlayerUiController`（mini ↔ 全屏容器展开、封面弹簧缩放、Palette 取色氛围渐变、流动进度 + 跑马灯）。
- **解密后元数据**：`TrackBuilder` 构建 `Track`（NCM 头信息或 `MediaMetadataRetriever` 读标签，写 `<音频>.cover` sidecar）；`MetadataEditor` / `MetadataEditSheet` 用 jAudioTagger 改写并镜像到 MediaStore。
- **导入密钥**：`MmkvParser` 解析 QQ音乐 MMKV vault → `EkeyStore` 持久化到 `filesDir/ekeys.json`，解密时按文件名/词干匹配。

## 支持格式与算法

| 平台 | 格式 | 算法 |
|------|------|------|
| 网易云 | `.ncm` | AES-128-ECB(SCORE_KEY) + 自定义 KeyBox XOR |
| QQ音乐/Moo | `.qmc*` / `.mflac[0/1/a/h/l/m]` / `.mgg[…]` / `.bkc*` / `.tkm` / `.mmp4` / 微云 hex | **QMCv2**：尾部 EKey → TEA(CBC) 解密 → 按密钥长度选 map / RC4 / 静态密码 |
| QQ音乐 | `.tm0/2/3/6` | iOS 容器：`.tm2/.tm6` 还原 M4A `ftyp` 头；`.tm0/.tm3` 直通 |
| 酷狗 | `.kgm` / `.kgma` / `.vpr` | crypto v3：头部 `AudioOffset` + 每文件密钥(md5)+slot 密钥，逐字节 4 步异或 |
| 酷狗 | `.kgg` / KGM v5 | ❌ 密钥在客户端 `KGMusicV3.db`(SQLCipher)，无法离线解密 |
| 酷我 | `.kwm` / `.kwma` | 头部 `0x18` 处 8 字节密钥 → 32 字节 mask，0x400 头后异或(周期32) |

### 验证状态（重要）

| 格式 | 状态 | 验证方式 |
|---|---|---|
| QMC（全系，含 EKey/map/RC4/static） | ✅ 已验证 | unlock-music 官方向量，15/15 |
| MMKV 密钥解析 | ✅ 已验证 | go-mmkv fixture |
| KGM v3 / VPR / KWM | ✅ 算法已比对 | 逐字节对照参考 Go 实现 + 流式跨块一致(20/20)；建议用真实文件实测 |
| NCM | ✅ 算法正确 | key-box + 密钥流公式与参考一致；未端到端实测 |
| TM (`.tm*`) | ✅ 照搬参考 | 未实测 |
| KGG / KGM v5 | ⛔ 不支持 | 需客户端数据库密钥 |

> **历史教训**：早期版本的 KGM/KGG/VPR/KWM 是**臆造算法**（写死的 `"3HENGELING"` 表、固定 256 字节 XOR 表），只会输出乱码。现已全部替换为 unlock-music 参考实现。改任何 codec 前，务必比对参考实现并跑测试向量——详见 [`CLAUDE.md`](CLAUDE.md)。

### NCM 解密算法（关键）

NCM **不是** RC4 加密：

1. 头部 `magic(8B) + padding(2B) + keyLen(4B LE) + keyData`
2. `keyData` 逐字节 XOR `0x64`，再 AES-128-ECB 解密（密钥 `SCORE_KEY`）
3. `decryptedKey[17..]` 送入 `buildKeyBox()` → 256 字节 KeyBox
4. `keyBoxXorChunk()` 逐字节 XOR 音频数据（**不是** RC4 PRGA）

```
SCORE_KEY = [0x68,0x7A,0x48,0x52,0x41,0x6D,0x73,0x6F,
             0x35,0x6B,0x49,0x6E,0x62,0x61,0x78,0x57]  // 注意最后字节是 0x57 不是 0x61
```

### QMCv2 解密流程（关键）

1. 读文件**末 4 字节**判断尾部类型：
   - `QTag` → `[rawMeta][metaLen(BE u32)]["QTag"]`，`rawMeta = "ekey,songID,extra2"`，取 `ekey`
   - 数字（LE u32，1..0xFFFF）→ `[audio][ekey][keyLen(LE u32)]`，取末尾 `keyLen` 字节的 `ekey`
   - `STag` / `cex\0`(musicex) → 无内嵌密钥，抛 `DecryptException`（走导入密钥）
   - 其它 → 无尾部，整文件按**静态密码**（旧版 QMCv1）解
2. `deriveKey(ekey)`：base64 解码 →（可选 `QQMusic EncV2,Key:` 前缀走双层 TEA）→ V1 TEA-CBC 解密（大端，`DELTA=0x9E3779B9`，16 轮，CBC 框架 `PadLen|Pad|Salt(2)|Body|Zero(7)`，`simpleKey` 与 ekey 前 8 字节交错成 16 字节 key）
3. 按**真实密钥长度**选流密码：`>300` → RC4（5120 字节分段）；`1..300` → map；`0` → 静态
4. 仅解密 `[0, audioLen)`，跳过尾部

> 全部常量/算法逐字节核对自 unlock-music CLI v0.2.12（`algo/qmc/*`），官方测试向量（map/RC4/EKey/分段流式）全部通过。

### 新版无内嵌密钥（STag / musicex）+ 导入密钥机制

部分新版 QQ音乐文件尾部为 `STag` 或 `musicex`，密钥保存在客户端 MMKV 数据库而非文件内——只有尾部为**数字（EKey 长度）**或 **`QTag`** 的文件才内嵌密钥、可直接离线解密。

`EkeyStore` 接收用户导入的密钥（MMKV / JSON / 文本，自动识别），按文件名/词干匹配（`song.mflac` 与 `song.mflac0.flac` 视为同一首），持久化到 `filesDir/ekeys.json`。解密时优先用 musicex tag 里的 `MediaFileName`，再回退所选文件名。

`MmkvParser` 逐字节核对自 Tencent/MMKV 源码 + unlock-music go-mmkv fixture：仅支持**明文** vault（加密 AES-CFB vault 不支持）；注意 key 与 raw ekey 之间有**两个** varint。

## 构建方法

```bash
cd ncm-android
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # JVM unit tests for codec-adjacent pure logic
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk
```

- **签名**：推荐把真实 `keystore.properties` 和 keystore 放在仓库外，然后用 `FREENOTE_KEYSTORE_PROPERTIES=/path/to/keystore.properties ./gradlew assembleRelease` 或 `./gradlew -PfreenoteKeystoreProperties=/path/to/keystore.properties assembleRelease` 指定；`storeFile` 可写绝对路径，或相对该 properties 文件。根目录仍兼容放 `keystore.properties`（从 `keystore.properties.example` 复制），不放也能构建——debug 用默认 debug keystore，release 产出**未签名**包（自行用 `apksigner` 签）。`keystore.properties` 与 `*.keystore` 已在 `.gitignore` 中，不会被提交。
- 安装：`adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk`

## 可继续添加的格式（调研结论）

纯离线工具只能解**密钥自带在文件内**的格式。

| 格式 | 平台 | 可离线？ | 难度 | 说明 |
|------|------|--------|------|------|
| `.bkc*` Moo | 腾讯 Moo | ✅ 已支持 | — | 复用 QMC 解码器 |
| `.tm0/2/3/6` | QQ音乐 iOS | ✅ 已支持 | — | 头部还原 |
| `.xm` | 虾米（已停服） | ✅ | 低 | 头部含 mask + 加密起始偏移，单字节 XOR |
| `.x2m` / `.x3m` | 喜马拉雅(安卓) | ✅ | 低 | 固定扰码表；`.x3m` 原名需 DB |
| `.xm`(喜马拉雅 PC) | 喜马拉雅 | ✅ | 中 | 两段 AES，密钥材料在 ID3 标签 |
| `.mg3d` | 咪咕 | ⚠️ | 高 | 需 salt+file_key |
| `.kgg` / kgm v5 | 酷狗新版 | ❌ | — | 密钥在 `KGMusicV3.db`(SQLCipher) |
| QQ `STag`/`musicex` | QQ音乐新版 | ❌ | — | 密钥在客户端 MMKV |
| Kuwo v2 / Joox / 蜻蜓FM | — | ❌ | — | 需设备指纹 / PBKDF2 / 在线 |

## 参考实现 / 致谢

- **unlock-music** — `git.unlock-music.dev`（部分地区 451 屏蔽）：QMC / KGM / KWM 主要参考
- **ncmdump** — https://github.com/taurusxin/ncmdump ：NCM 解密算法参考（C++）
- go-mmkv fixtures：MMKV vault 解析
- parakeet-rs / libparakeet（已 DMCA，存档）、MusicDecrypto、jixunmoe/qmc-decode：交叉验证

## 许可证

[MIT](LICENSE) © 2026 BraynLabs
