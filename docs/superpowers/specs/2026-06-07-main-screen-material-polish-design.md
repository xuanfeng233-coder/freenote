# 主屏 Material 化 + 文案精简 — 设计文档

日期：2026-06-07
范围：主屏（`activity_main.xml` + `MainActivity.kt` 头部）布局重构 + 全局用户可见文案精简，
其中「新版加密需 root/降级」的说明按 README 详写。播放器、标签编辑器、文件卡片布局不动，
仅跟进文案。

## 目标

1. 主屏顶部更符合 Material 3：用顶部应用栏 + 溢出菜单取代当前「标题 + 长副标题 + ⓘ 图标 +
   三个堆叠文字按钮」的杂乱头部。
2. 文案统一精简、更正式。
3. 「使用说明」对话框内容按 README 详写，并新增"下载旧版安装包"的一键跳转（蓝奏云）。

## 非目标

- 不改播放器底部 sheet（`fullPlayer` / `miniBar`）、标签编辑器（`sheet_metadata.xml`）、文件
  卡片（`item_file.xml`）的**布局**；这些只跟进文案（实际上它们的文案本就 OK，预计不动）。
- 不改色板（`colors.xml` 的 M3 角色）。
- 不改任何解密/播放逻辑。

## 当前问题（基线）

`activity_main.xml` 头部把以下元素塞在一起：
- 标题 `FreeNote`（headlineSmall）
- 长技术副标题 `支持 NCM / QMC(含新版 EKey) / TM / KGM / KGG / KWM`
- 右上 ⓘ 信息图标
- 右侧**三个右对齐、带文件夹图标的文字按钮**：`导入密钥`、`保存目录：默认`、`Root 导入密钥`（root 时才显示）

三个堆叠文字按钮像链接、不 Material；长副标题喧宾夺主；信息入口是裸图标。

## 设计

### 1. 顶部应用栏（替换整块头部）

- 在 `mainContent`（ConstraintLayout）顶部放一个 `com.google.android.material.appbar.MaterialToolbar`，
  作为**独立 toolbar**（不调用 `setSupportActionBar`，主题是 `NoActionBar`），约束到 parent top、全宽。
  - `app:title` = `@string/app_name`（FreeNote）
  - `app:subtitle` = `@string/app_subtitle`（改为 `本地音乐解锁`）
  - 在 `MainActivity.onCreate` 里 `toolbar.inflateMenu(R.menu.main_menu)` +
    `toolbar.setOnMenuItemClickListener { … }`
- 删除原 `titleTextView`、`subtitleTextView`、`infoButton`、`importKeysButton`、`outputDirButton`、
  `rootImportButton`。

### 2. 溢出菜单（新建 `res/menu/main_menu.xml`）

三个菜单项，全部 `app:showAsAction="never"`（进溢出 ⋮）：

| id | 标题 | 行为 |
|---|---|---|
| `action_import_keys` | `导入密钥` / 有密钥时 `密钥 (N)` | `keyImporter.launch("*/*")` |
| `action_root_import` | `Root 导入密钥` | `onRootImportClicked()`；仅 root 时 `isVisible=true` |
| `action_help` | `使用说明` | `showHelpDialog()` |

`MainActivity`：
- `updateKeyCount()` 改为设置 `toolbar.menu.findItem(R.id.action_import_keys).title`（沿用
  `import_keys` / `import_keys_n` 两个字符串）。
- root 可见性：`toolbar.menu.findItem(R.id.action_root_import).isVisible =
  RootHelper.isRootAvailable()`（取代原 `rootImportButton.visibility`）。
- 菜单需在 `updateKeyCount()` 之前 inflate；onCreate 中保证顺序。

### 3. 主操作区 + 保存目录 chip

- 操作行不变：`选择文件`（`Widget.Ncm.Button.Tonal`）/ `开始解密`（`Widget.Ncm.Button`，未选文件禁用）。
  - 文案 `全部解密` → `开始解密`（已确认）。
- `保存目录` 文字按钮 → **M3 assist chip**：`com.google.android.material.chip.Chip`，
  `style="@style/Widget.Material3.Chip.Assist"`，`app:chipIcon="@drawable/ic_folder"`，左对齐，
  约束在操作行下方、列表/divider 上方。
  - 文案 `保存目录 · 默认` / `保存目录 · <目录名>`（中点替换冒号）。
  - 点按仍走 `onOutputDirClicked()`。
  - `updateOutputDirLabel()` 改为设置该 chip 的 `text`。

### 4. 三个对话框升级为 `MaterialAlertDialogBuilder`

`showHelpDialog()`、`onOutputDirClicked()`、`onRootImportClicked()` 从 appcompat
`androidx.appcompat.app.AlertDialog.Builder` 换成
`com.google.android.material.dialog.MaterialAlertDialogBuilder`（M3 圆角/配色），行为不变。

### 5. 「使用说明」对话框详写 + 下载入口

- 标题 `help_title`：`QQ音乐 / 酷狗音乐 密钥说明` → `新版加密与密钥说明`。
- 正文 `help_message` 改为下述详写版（对话框内可滚动）：

```
可直接离线解密（无需任何操作）：
• 网易云 .ncm
• 酷狗 .kgm / .kgma / .vpr
• 酷我 .kwm / .kwma
• QQ音乐／Moo 内嵌密钥的文件（.qmc / .mflac / .mgg 等）

需要额外处理：
• 酷狗 .kgg
• QQ音乐 尾部为 STag / musicex 的文件
这类文件的密钥存在客户端数据库里、没写进文件，无法直接离线解密。两种办法：

方法一（推荐）｜用旧版客户端重新下载
旧版会把密钥内嵌进文件，下载即可直接解。
• QQ音乐：回退到 10.12.0.8（v11.6 之前内嵌密钥）
• 酷狗音乐：回退到安卓 12.5.6（最后一个支持 .kgm 的版本，约 2024-11）
已在蓝奏云备好这两个降级安装包，点下方「下载旧版安装包」直接获取。

方法二｜导入密钥（需能访问客户端数据库）
在菜单点「导入密钥」，导入任一格式：
• MMKV：QQ音乐的 MMKVStreamEncryptId 明文库
• JSON：{ "文件名": "base64密钥" }
• 文本：每行「文件名<TAB或=或,>base64密钥」
密钥库需从 PC 客户端或已 root 的安卓导出；未 root 的安卓读不到其它 App 的私有数据，请改用方法一。

其它：APE 能解密，但内置播放器不支持预览，可解出后用其它播放器播放。
```

- **新增 neutral button** `下载旧版安装包`（`help_download_apk`）：点按用
  `Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help_apk_url)))` 跳手机浏览器，
  `try/catch ActivityNotFoundException` 兜底（无浏览器时 Toast 提示）。
  - `help_apk_url` = `https://wwatt.lanzoup.com/b00g48x2kf`
- 正向按钮保留 `知道了`（`help_got_it`）。

## 文案变更清单

| key | 现在 | 改为 |
|---|---|---|
| `app_subtitle` | `支持 NCM / QMC(含新版 EKey) / TM / KGM / KGG / KWM` | `本地音乐解锁` |
| `decrypt_all` | `全部解密` | `开始解密` |
| `output_dir_default` | `保存目录：默认` | `保存目录 · 默认` |
| `output_dir_set` | `保存目录：%1$s` | `保存目录 · %1$s` |
| `status_idle` | `选择文件开始解密` | `请选择要解密的文件` |
| `status_selected` | `已选择 %1$d 个文件，点击「全部解密」开始` | `已选择 %1$d 个文件，点按「开始解密」` |
| `status_decrypting` | `解密中… (%1$d/%2$d)` | `正在解密 %1$d/%2$d` |
| `status_done` | `解密完成 · 成功 %1$d · 失败 %2$d` | 保持 |
| `empty_title` | `还没有选择文件` | `尚未选择文件` |
| `empty_hint` | `点击上方按钮选择加密歌曲` | `选择加密音频文件以开始` |
| `help_title` | `QQ音乐 / 酷狗音乐 密钥说明` | `新版加密与密钥说明` |
| `help_message` | （旧短版） | 详写版（见上） |

新增字符串：
- `help_download_apk` = `下载旧版安装包`
- `help_apk_url` = `https://wwatt.lanzoup.com/b00g48x2kf`
- `error_no_browser` = `未找到可打开链接的浏览器`（neutral button 兜底）
- `menu_help` = `使用说明`（溢出菜单项标题，专用，不复用 `cd_help`/`help`）

菜单项标题：`action_import_keys` 复用 `import_keys` / `import_keys_n`；`action_root_import` 复用
`root_import`；`action_help` 用新增的 `menu_help`。

## 涉及文件

- `res/layout/activity_main.xml` — 头部整块替换为 `MaterialToolbar` + assist chip。
- `res/menu/main_menu.xml` — **新建**。
- `res/values/strings.xml` — 上表文案 + 新增字符串。
- `res/values/themes.xml` — 如需，给 toolbar/chip 加薄样式（标题/副标题色、chip 复用 M3 默认即可，
  优先零新增样式）。
- `MainActivity.kt` — toolbar/menu 接管按钮；`updateKeyCount`/`updateOutputDirLabel` 改指向菜单项/chip；
  三对话框换 `MaterialAlertDialogBuilder`；help 加 neutral button + 浏览器跳转；root 菜单项可见性。

## 兼容性 / 风险

- `MaterialToolbar` 作为独立 view（非 support action bar），避免 `NoActionBar` 主题冲突。
- 蓝奏云链接走系统浏览器，App 不内嵌 WebView、不联网请求（保持"纯离线"承诺；仅用户主动点按时
  才由系统浏览器联网）。
- assist chip 与 `选择文件` 都用文件夹图标，位置分离、可接受；如觉重复，后续可换 chip 图标。
- 顶栏溢出菜单 inflate 与 `updateKeyCount()` 的调用顺序需在 onCreate 内保证（先 inflate 再 update）。

## 验证

- `./gradlew assembleDebug` 通过。
- 真机冒烟：顶栏标题/副标题正确；溢出菜单三项可点（root 机型才见 Root 项）；导入密钥后菜单项变
  `密钥 (N)`；保存目录 chip 文案随所选目录更新、点按弹 M3 对话框；空状态/各状态文案正确；
  「使用说明」内容完整可滚动，「下载旧版安装包」跳转蓝奏云。
