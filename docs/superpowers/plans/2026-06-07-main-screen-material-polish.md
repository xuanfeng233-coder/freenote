# 主屏 Material 化 + 文案精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把主屏头部重构为 M3 顶部应用栏 + 溢出菜单 + 保存目录 assist chip，统一精简文案，并把「使用说明」按 README 详写、加一键跳转蓝奏云下载降级安装包。

**Architecture:** 纯 UI/资源改动。`activity_main.xml` 头部整块替换；新增 `res/menu/main_menu.xml`；`strings.xml` 文案；`MainActivity.kt` 用独立 `MaterialToolbar`（非 support action bar，主题为 NoActionBar）接管原三个文字按钮 + 信息图标，三个对话框升级 `MaterialAlertDialogBuilder`。不动播放器、标签编辑器、文件卡片布局，不动解密/播放逻辑、不动色板。

**Tech Stack:** Kotlin 1.9.22 / AGP 8.2.2 / Material 1.11.0（`MaterialToolbar`、`Chip` `Widget.Material3.Chip.Assist`、`MaterialAlertDialogBuilder` 均可用）/ ViewBinding 未用（项目用 `findViewById`）。

**Note on testing:** 本仓库的 JVM 单测（`app/src/test`）只覆盖纯解码逻辑，不覆盖 UI。本计划的改动**无可写单测**，每个任务的验证 = `./gradlew assembleDebug` 编译通过 + `./gradlew :app:testDebugUnitTest` 不回归 + 真机冒烟。不要为 XML/字符串伪造单测。

**参考 spec:** `docs/superpowers/specs/2026-06-07-main-screen-material-polish-design.md`

---

### Task 1: 文案（strings.xml）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

改字符串后构建仍通过（改值不改 key，新增 key 暂未引用），可独立提交。

- [ ] **Step 1: 改写已有字符串**

把下列 key 的值改成右侧。逐条 `Edit`（每条 old/new 唯一）：

| key | 新值 |
|---|---|
| `app_subtitle` | `本地音乐解锁` |
| `decrypt_all` | `开始解密` |
| `status_idle` | `请选择要解密的文件` |
| `status_selected` | `已选择 %1$d 个文件，点按「开始解密」` |
| `status_decrypting` | `正在解密 %1$d/%2$d` |
| `empty_title` | `尚未选择文件` |
| `empty_hint` | `选择加密音频文件以开始` |
| `output_dir_default` | `保存目录 · 默认` |
| `output_dir_set` | `保存目录 · %1$s` |
| `help_title` | `新版加密与密钥说明` |

例如 `app_subtitle`：

```xml
<!-- old -->
<string name="app_subtitle">支持 NCM / QMC(含新版 EKey) / TM / KGM / KGG / KWM</string>
<!-- new -->
<string name="app_subtitle">本地音乐解锁</string>
```

`status_selected`（注意保留 `%1$d` 占位符与转义的书名号）：

```xml
<!-- old -->
<string name="status_selected">已选择 %1$d 个文件，点击「全部解密」开始</string>
<!-- new -->
<string name="status_selected">已选择 %1$d 个文件，点按「开始解密」</string>
```

- [ ] **Step 2: 改写 `help_message`（详写版）**

整条替换 `help_message`。**XML 转义要点**：换行用 `\n`；`<` `>` 必须写成 `&lt;` `&gt;`；JSON 里的双引号写成 `\"`。

```xml
<string name="help_message">可直接离线解密（无需任何操作）：\n• 网易云 .ncm\n• 酷狗 .kgm / .kgma / .vpr\n• 酷我 .kwm / .kwma\n• QQ音乐／Moo 内嵌密钥的文件（.qmc / .mflac / .mgg 等）\n\n需要额外处理：\n• 酷狗 .kgg\n• QQ音乐 尾部为 STag / musicex 的文件\n这类文件的密钥存在客户端数据库里、没写进文件，无法直接离线解密。两种办法：\n\n方法一（推荐）｜用旧版客户端重新下载\n旧版会把密钥内嵌进文件，下载即可直接解。\n• QQ音乐：回退到 10.12.0.8（v11.6 之前内嵌密钥）\n• 酷狗音乐：回退到安卓 12.5.6（最后一个支持 .kgm 的版本，约 2024-11）\n已在蓝奏云备好这两个降级安装包，点下方「下载旧版安装包」直接获取。\n\n方法二｜导入密钥（需能访问客户端数据库）\n在菜单点「导入密钥」，导入任一格式：\n• MMKV：QQ音乐的 MMKVStreamEncryptId 明文库\n• JSON：{ \"文件名\": \"base64密钥\" }\n• 文本：每行「文件名&lt;TAB或=或,&gt;base64密钥」\n密钥库需从 PC 客户端或已 root 的安卓导出；未 root 的安卓读不到其它 App 的私有数据，请改用方法一。\n\n其它：APE 能解密，但内置播放器不支持预览，可解出后用其它播放器播放。</string>
```

- [ ] **Step 3: 新增字符串**

在 `<!-- Help / key-source note -->` 区块内追加：

```xml
<string name="menu_help">使用说明</string>
<string name="help_download_apk">下载旧版安装包</string>
<string name="help_apk_url">https://wwatt.lanzoup.com/b00g48x2kf</string>
<string name="error_no_browser">未找到可打开链接的浏览器</string>
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（资源编译通过，无 `%`/转义报错）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(ui): streamline copy and expand key-source help text"
```

---

### Task 2: 溢出菜单资源（main_menu.xml）

**Files:**
- Create: `app/src/main/res/menu/main_menu.xml`

新文件、暂未被引用，构建仍通过，可独立提交。

- [ ] **Step 1: 新建菜单文件**

`app/src/main/res/menu/main_menu.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <item
        android:id="@+id/action_import_keys"
        android:title="@string/import_keys"
        app:showAsAction="never" />

    <item
        android:id="@+id/action_root_import"
        android:title="@string/root_import"
        android:visible="false"
        app:showAsAction="never" />

    <item
        android:id="@+id/action_help"
        android:title="@string/menu_help"
        app:showAsAction="never" />
</menu>
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/res/menu/main_menu.xml
git commit -m "feat(ui): add overflow menu for keys/root/help actions"
```

---

### Task 3: 头部布局重构 + MainActivity 接线

> 布局移除的 view id（`importKeysButton`/`outputDirButton`/`rootImportButton`/`infoButton`）由 `MainActivity` 在运行时 `findViewById` 引用——只改布局会让 App 启动崩溃。因此**布局与 Activity 必须同任务一起改、一起提交**，保证提交后可运行。

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/ncmdecrypt/MainActivity.kt`

#### 布局改动

- [ ] **Step 1: 用 MaterialToolbar 替换标题区整块**

把 `activity_main.xml` 中从 `titleTextView` 到 `rootImportButton` 的整段（当前第 17–86 行：`titleTextView`、`subtitleTextView`、`infoButton`、`importKeysButton`、`outputDirButton`、`rootImportButton` 六个 view）整体替换为：

```xml
<com.google.android.material.appbar.MaterialToolbar
    android:id="@+id/topAppBar"
    style="@style/Widget.Material3.Toolbar"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    app:contentInsetStart="0dp"
    app:subtitle="@string/app_subtitle"
    app:title="@string/app_name"
    app:layout_constraintEnd_toEndOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toTopOf="parent" />
```

- [ ] **Step 2: selectFilesButton 改约束到 toolbar**

把 `selectFilesButton` 的这两个属性：

```xml
android:layout_marginTop="20dp"
...
app:layout_constraintTop_toBottomOf="@id/rootImportButton"
```

改为：

```xml
android:layout_marginTop="8dp"
...
app:layout_constraintTop_toBottomOf="@id/topAppBar"
```

（`selectFilesButton` 其余约束、`actionDivider`、`decryptAllButton` 不变。）

- [ ] **Step 3: 新增保存目录 assist chip，并把 status 接到它下方**

在 `decryptAllButton` 之后、`statusTextView` 之前插入：

```xml
<com.google.android.material.chip.Chip
    android:id="@+id/outputDirChip"
    style="@style/Widget.Material3.Chip.Assist"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="12dp"
    android:text="@string/output_dir_default"
    app:chipIcon="@drawable/ic_folder"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintTop_toBottomOf="@id/selectFilesButton" />
```

并把 `statusTextView` 的：

```xml
app:layout_constraintTop_toBottomOf="@id/selectFilesButton"
```

改为：

```xml
app:layout_constraintTop_toBottomOf="@id/outputDirChip"
```

（`progressBar`、`divider`、`fileRecyclerView`、`emptyState`、player sheet 全部不变。）

#### MainActivity 改动

- [ ] **Step 4: 调整 import**

删除：

```kotlin
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
```

新增（放到对应分组，保持字母序即可）：

```kotlin
import android.content.ActivityNotFoundException
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
```

（`android.content.Intent`、`android.net.Uri`、`android.widget.Toast`、`com.google.android.material.button.MaterialButton` 已存在，保留。）

- [ ] **Step 5: 替换字段声明**

把这四行字段：

```kotlin
private lateinit var importKeysButton: MaterialButton
private lateinit var outputDirButton: MaterialButton
private lateinit var rootImportButton: MaterialButton
private lateinit var infoButton: ImageButton
```

替换为：

```kotlin
private lateinit var topAppBar: MaterialToolbar
private lateinit var outputDirChip: Chip
```

- [ ] **Step 6: 替换 onCreate 里的 findViewById**

把：

```kotlin
importKeysButton = findViewById(R.id.importKeysButton)
outputDirButton = findViewById(R.id.outputDirButton)
rootImportButton = findViewById(R.id.rootImportButton)
infoButton = findViewById(R.id.infoButton)
```

替换为：

```kotlin
topAppBar = findViewById(R.id.topAppBar)
outputDirChip = findViewById(R.id.outputDirChip)
```

- [ ] **Step 7: 替换 onCreate 里的监听器接线**

把：

```kotlin
importKeysButton.setOnClickListener { keyImporter.launch("*/*") }
outputDirButton.setOnClickListener { onOutputDirClicked() }
rootImportButton.visibility = if (RootHelper.isRootAvailable()) View.VISIBLE else View.GONE
rootImportButton.setOnClickListener { onRootImportClicked() }
infoButton.setOnClickListener { showHelpDialog() }
updateKeyCount()
updateOutputDirLabel()
```

替换为（先 inflate 菜单，再设 root 可见性，最后 update——顺序重要）：

```kotlin
topAppBar.inflateMenu(R.menu.main_menu)
topAppBar.menu.findItem(R.id.action_root_import).isVisible = RootHelper.isRootAvailable()
topAppBar.setOnMenuItemClickListener { item ->
    when (item.itemId) {
        R.id.action_import_keys -> { keyImporter.launch("*/*"); true }
        R.id.action_root_import -> { onRootImportClicked(); true }
        R.id.action_help -> { showHelpDialog(); true }
        else -> false
    }
}
outputDirChip.setOnClickListener { onOutputDirClicked() }
updateKeyCount()
updateOutputDirLabel()
```

- [ ] **Step 8: updateKeyCount 指向菜单项**

把：

```kotlin
private fun updateKeyCount() {
    val n = EkeyStore.count()
    importKeysButton.text = if (n > 0) getString(R.string.import_keys_n, n)
    else getString(R.string.import_keys)
}
```

替换为：

```kotlin
private fun updateKeyCount() {
    val n = EkeyStore.count()
    topAppBar.menu.findItem(R.id.action_import_keys).title =
        if (n > 0) getString(R.string.import_keys_n, n) else getString(R.string.import_keys)
}
```

- [ ] **Step 9: updateOutputDirLabel 指向 chip**

把：

```kotlin
private fun updateOutputDirLabel() {
    val name = OutputDirStore.displayName(this)
    outputDirButton.text = if (name != null) getString(R.string.output_dir_set, name)
    else getString(R.string.output_dir_default)
}
```

替换为：

```kotlin
private fun updateOutputDirLabel() {
    val name = OutputDirStore.displayName(this)
    outputDirChip.text = if (name != null) getString(R.string.output_dir_set, name)
    else getString(R.string.output_dir_default)
}
```

- [ ] **Step 10: 三个对话框换 MaterialAlertDialogBuilder + help 加下载按钮**

`onOutputDirClicked()`：把 `AlertDialog.Builder(this)` 改为 `MaterialAlertDialogBuilder(this)`（其余链式调用不变）。

`onRootImportClicked()`：把 `AlertDialog.Builder(this)` 改为 `MaterialAlertDialogBuilder(this)`（其余不变）。

`showHelpDialog()` 整体替换，并新增 `openDowngradeApks()`：

```kotlin
private fun showHelpDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.help_title)
        .setMessage(R.string.help_message)
        .setNeutralButton(R.string.help_download_apk) { _, _ -> openDowngradeApks() }
        .setPositiveButton(R.string.help_got_it, null)
        .show()
}

private fun openDowngradeApks() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help_apk_url))))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 11: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。若报「unresolved reference: importKeysButton/outputDirButton/rootImportButton/infoButton」，说明有遗漏引用未替换，回到对应 step 修。

- [ ] **Step 12: 单测不回归**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（UI 改动不影响纯逻辑单测）。

- [ ] **Step 13: 真机冒烟（手动，建议）**

`adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk`，确认：
- 顶栏显示 `FreeNote` + 副标题 `本地音乐解锁`；右侧 ⋮ 溢出菜单可展开。
- 菜单含「导入密钥 / 使用说明」（非 root 机不显示「Root 导入密钥」）。
- 导入密钥后菜单项变 `密钥 (N)`。
- `保存目录 · 默认` chip 可点，弹 M3 对话框；选目录后 chip 文案更新。
- 空状态文案 `尚未选择文件` / `选择加密音频文件以开始`；选文件/解密各状态文案正确。
- 「使用说明」内容完整可滚动；点「下载旧版安装包」跳手机浏览器到蓝奏云链接。

- [ ] **Step 14: 提交**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/java/com/ncmdecrypt/MainActivity.kt
git commit -m "feat(ui): rebuild header with M3 top app bar, overflow menu, output-dir chip"
```

---

## Self-Review 结论

- **Spec coverage：** 顶栏+溢出菜单（T2+T3 step1/7）、保存目录 chip（T3 step3/9）、三对话框换 M3 builder（T3 step10）、文案精简全表（T1 step1）、help 详写（T1 step2）、下载蓝奏云 neutral button（T1 step3 + T3 step10）、root 菜单可见性（T3 step7）、菜单项动态密钥计数（T3 step8）——逐条有对应任务。
- **Placeholder scan：** 无 TBD/TODO；每个改 code 的 step 都给了完整旧→新代码。
- **Type/命名一致：** 字段 `topAppBar: MaterialToolbar` / `outputDirChip: Chip` 在 step5 定义，step6/7/8/9 一致使用；菜单 id `action_import_keys`/`action_root_import`/`action_help` 在 T2 定义、T3 step7/8 一致引用；字符串 key（`menu_help`/`help_download_apk`/`help_apk_url`/`error_no_browser`）在 T1 step3 定义、T2/T3 引用。
- **风险：** `topAppBar.menu.findItem(...)` 必须在 `inflateMenu` 之后调用——onCreate 顺序已在 step7 保证（inflate→root 可见性→updateKeyCount）。
