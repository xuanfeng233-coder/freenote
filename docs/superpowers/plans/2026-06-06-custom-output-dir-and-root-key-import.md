# Custom Output Directory + Root Key Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users pick a custom save folder for decrypted audio (SAF), and let rooted users import QQ Music ekeys directly from the app's private `/data/data` MMKV vault.

**Architecture:** Two additive, independent features. (1) An `OutputDirStore` persists a SAF tree URI; `MainActivity.saveDecrypted` writes there via `DocumentFile` when set, else falls back to today's MediaStore/legacy path. (2) `RootHelper` detects/uses `su`; `QqMusicKeyImporter` copies QQ Music's plaintext MMKV vault out and feeds it through the existing `EkeyStore.importFrom`.

**Tech Stack:** Kotlin, Android SDK 34 (minSdk 26), `androidx.documentfile`, SAF `OpenDocumentTree`, `Runtime.exec("su")`, JUnit (JVM unit tests).

**Reference spec:** `docs/superpowers/specs/2026-06-06-custom-output-dir-and-root-key-import-design.md`

**Testing reality:** SAF/`DocumentFile`, `SharedPreferences`, and the `su` subprocess are Android-runtime and not JVM-unit-testable. Only `RootHelper.isRootAvailable(paths)` has a real unit test (injected path list). Everything else is verified by a clean `assembleDebug` + the existing `:app:testDebugUnitTest` suite (no regressions). A rooted-device smoke test is the maintainer's manual step and is called out, not claimed.

---

## Task 1: Declare the documentfile dependency

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block)

- [ ] **Step 1: Add the dependency**

In the `dependencies { }` block, after the `recyclerview` line, add:

```kotlin
    implementation("androidx.documentfile:documentfile:1.0.0")
```

- [ ] **Step 2: Verify it resolves**

Run: `./gradlew :app:help -q` (or rely on Task 8's build).
Expected: no dependency-resolution error.

---

## Task 2: OutputDirStore — persist the chosen SAF tree

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/OutputDirStore.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.ncmdecrypt

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Persists the user's optional custom output directory: a SAF tree Uri the user picked via
 * ACTION_OPEN_DOCUMENT_TREE. When unset (or the persisted permission was lost), callers fall
 * back to the default MediaStore Music/FreeNote (Q+) or legacy /sdcard/FreeNote (<=P).
 */
object OutputDirStore {

    private const val PREFS = "output_dir"
    private const val KEY_TREE_URI = "tree_uri"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Store the chosen tree Uri string. Persistable permission is taken by the caller. */
    fun set(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    /** Forget the override and release any persisted permission we hold for it. */
    fun clear(context: Context) {
        val current = rawUri(context)
        prefs(context).edit().remove(KEY_TREE_URI).apply()
        if (current != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    /** The stored tree Uri only if we still hold a writable persisted permission; else null. */
    fun getTreeUri(context: Context): Uri? {
        val uri = rawUri(context) ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return if (held) uri else null
    }

    /** Human-readable folder name for the button label, or null if unavailable. */
    fun displayName(context: Context): String? {
        val uri = getTreeUri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    private fun rawUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/ncmdecrypt/OutputDirStore.kt
git commit -m "feat: add OutputDirStore for custom SAF output directory"
```

---

## Task 3: Custom-dir save logic in MainActivity

**Files:**
- Modify: `app/src/main/java/com/ncmdecrypt/MainActivity.kt`

- [ ] **Step 1: Add the `anyCustomDirOutput` flag**

After the `anyCacheOnly` field (around line 58) add:

```kotlin
    private var anyCustomDirOutput = false      // landed in the user's custom SAF folder
```

- [ ] **Step 2: Reset it per run**

In `startDecryptAll()`, next to `anyMediaStoreOutput = false`, add:

```kotlin
        anyCustomDirOutput = false
```

- [ ] **Step 3: Add `customDirUri` to SaveResult**

Change the `SaveResult` class to:

```kotlin
    private class SaveResult(
        val cacheFile: File,
        val publicPath: String?,
        val mediaStoreUri: String?,
        val customDirUri: String?
    )
```

- [ ] **Step 4: Write the custom-dir first in saveDecrypted**

In `saveDecrypted`, add a local `var customDirUri: String? = null` next to the other vars, then
BEFORE the MediaStore branch (the `if (Build.VERSION.SDK_INT >= Q)` block) insert:

```kotlin
        // 0) User-chosen custom folder (SAF) takes priority on any Android version.
        OutputDirStore.getTreeUri(this)?.let { tree ->
            customDirUri = saveViaTreeUri(tree, outputName, mime, decryptedFile)
        }
```

Then guard the existing MediaStore + legacy branches so they only run when the custom write
did NOT happen. Change the MediaStore branch condition to:

```kotlin
        if (customDirUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
```

(The legacy branch already starts with `if (mediaStoreUri == null && ...`; also require
`customDirUri == null` — change it to `if (customDirUri == null && mediaStoreUri == null && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && canWriteLegacyPublicStorage())`.)

- [ ] **Step 5: Record the custom-dir outcome**

Change the `when` that sets the toast flags to put custom dir first:

```kotlin
        when {
            customDirUri != null -> anyCustomDirOutput = true
            mediaStoreUri != null -> anyMediaStoreOutput = true
            publicPath != null -> { /* legacy direct /sdcard/FreeNote */ }
            else -> anyCacheOnly = true
        }
```

- [ ] **Step 6: Return the new field**

Change the final `return SaveResult(cacheFile, publicPath, mediaStoreUri)` to:

```kotlin
        return SaveResult(cacheFile, publicPath, mediaStoreUri, customDirUri)
```

- [ ] **Step 7: Add saveViaTreeUri**

After `saveViaMediaStore(...)` add:

```kotlin
    /** Writes [src] into the user's chosen SAF tree. Returns the new document uri or null. */
    private fun saveViaTreeUri(treeUri: Uri, outputName: String, mime: String, src: File): String? {
        return try {
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return null
            val doc = dir.createFile(mime, outputName) ?: return null
            val ok = contentResolver.openOutputStream(doc.uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
            if (ok) doc.uri.toString() else { runCatching { doc.delete() }; null }
        } catch (_: Exception) {
            null
        }
    }
```

- [ ] **Step 8: Pass the custom uri to TrackBuilder**

In `decryptNext`, change the `mediaStoreUri = saved.mediaStoreUri` argument of the
`TrackBuilder.build(...)` call to:

```kotlin
                        mediaStoreUri = saved.mediaStoreUri ?: saved.customDirUri,
```

- [ ] **Step 9: Add the completion-toast branch**

In `onDecryptAllComplete`, change the `when` to add the custom-dir case first:

```kotlin
            val msg = when {
                anyCacheOnly -> R.string.saved_cache_only
                anyCustomDirOutput -> R.string.saved_to_custom_dir
                anyMediaStoreOutput -> R.string.saved_to_music_folder
                else -> R.string.saved_to_folder
            }
```

- [ ] **Step 10: Add imports**

At the top of MainActivity, add:

```kotlin
import androidx.documentfile.provider.DocumentFile
```

- [ ] **Step 11: Add the string**

In `app/src/main/res/values/strings.xml`, near the other `saved_to_*` strings add:

```xml
    <string name="saved_to_custom_dir">已保存到所选文件夹</string>
```

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/MainActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: write decrypted output to custom SAF folder when set"
```

---

## Task 4: Custom-dir UI (button, picker, dialog, strings)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ncmdecrypt/MainActivity.kt`

- [ ] **Step 1: Add the layout button**

In `activity_main.xml`, add a text button below the `importKeysButton`, anchored under it and
to the parent end. Insert after the `importKeysButton` element:

```xml
        <com.google.android.material.button.MaterialButton
            android:id="@+id/outputDirButton"
            style="@style/Widget.Ncm.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:text="@string/output_dir_default"
            app:icon="@drawable/ic_folder"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toBottomOf="@id/importKeysButton" />
```

Then change `selectFilesButton`'s top constraint so the action row sits below the new button:

```xml
            app:layout_constraintTop_toBottomOf="@id/outputDirButton"
```

(It currently reads `app:layout_constraintTop_toBottomOf="@id/subtitleTextView"`.)

- [ ] **Step 2: Add strings**

In `strings.xml` add:

```xml
    <string name="output_dir_default">保存目录：默认</string>
    <string name="output_dir_set">保存目录：%1$s</string>
    <string name="output_dir_change">更换目录</string>
    <string name="output_dir_reset">恢复默认</string>
    <string name="output_dir_title">解密文件保存到</string>
    <string name="cancel">取消</string>
```

(If `cancel` already exists, skip that line.)

- [ ] **Step 3: Declare the field + result launcher**

In MainActivity, add the field near the other buttons:

```kotlin
    private lateinit var outputDirButton: MaterialButton
```

Add the launcher near the other `registerForActivityResult` blocks:

```kotlin
    private val outputDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            OutputDirStore.set(this, uri)
            updateOutputDirLabel()
        }
    }
```

- [ ] **Step 4: Wire it in onCreate**

After `importKeysButton = findViewById(...)` add:

```kotlin
        outputDirButton = findViewById(R.id.outputDirButton)
```

After `importKeysButton.setOnClickListener { ... }` add:

```kotlin
        outputDirButton.setOnClickListener { onOutputDirClicked() }
```

After `updateKeyCount()` add:

```kotlin
        updateOutputDirLabel()
```

- [ ] **Step 5: Add the handlers**

Near `updateKeyCount()` add:

```kotlin
    private fun updateOutputDirLabel() {
        val name = OutputDirStore.displayName(this)
        outputDirButton.text = if (name != null) getString(R.string.output_dir_set, name)
        else getString(R.string.output_dir_default)
    }

    private fun onOutputDirClicked() {
        if (OutputDirStore.getTreeUri(this) == null) {
            outputDirPicker.launch(null)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.output_dir_title)
            .setItems(
                arrayOf(getString(R.string.output_dir_change), getString(R.string.output_dir_reset))
            ) { _, which ->
                when (which) {
                    0 -> outputDirPicker.launch(null)
                    1 -> { OutputDirStore.clear(this); updateOutputDirLabel() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
```

- [ ] **Step 6: Add import**

Ensure `import android.content.Intent` is present (it already is in MainActivity).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/ncmdecrypt/MainActivity.kt
git commit -m "feat: custom output directory picker UI"
```

---

## Task 5: RootHelper (TDD: isRootAvailable; runAsRoot)

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/RootHelper.kt`
- Test: `app/src/test/java/com/ncmdecrypt/RootHelperTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.ncmdecrypt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RootHelperTest {

    @Test
    fun detectsSuWhenAPathExists() {
        val tmp = File.createTempFile("su", "bin").apply { deleteOnExit() }
        assertTrue(RootHelper.isRootAvailable(listOf("/no/such/path", tmp.absolutePath)))
    }

    @Test
    fun reportsNoRootWhenNoPathExists() {
        assertFalse(RootHelper.isRootAvailable(listOf("/no/such/path", "/also/missing")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ncmdecrypt.RootHelperTest"`
Expected: FAIL — `RootHelper` unresolved.

- [ ] **Step 3: Write RootHelper**

```kotlin
package com.ncmdecrypt

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Best-effort root detection and a bounded `su` command runner. Detection is a cheap binary
 * existence check (it does NOT trigger a su grant prompt); the prompt appears the first time
 * [runAsRoot] actually executes.
 */
object RootHelper {

    private val DEFAULT_SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/odm/bin/su"
    )

    data class RootResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val launched: Boolean get() = exitCode != LAUNCH_FAILED
        companion object { const val LAUNCH_FAILED = -1000 }
    }

    fun isRootAvailable(paths: List<String> = DEFAULT_SU_PATHS): Boolean =
        paths.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** Runs [commands] (joined with newlines) under `su`, bounded by [timeoutMs]. */
    fun runAsRoot(commands: List<String>, timeoutMs: Long = 20_000): RootResult {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(false).start()
            process.outputStream.bufferedWriter().use { w ->
                for (c in commands) { w.write(c); w.write("\n") }
                w.write("exit\n")
                w.flush()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootResult(RootResult.LAUNCH_FAILED, "", "timeout")
            }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            RootResult(process.exitValue(), out, err)
        } catch (e: Exception) {
            RootResult(RootResult.LAUNCH_FAILED, "", e.message ?: "su failed")
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ncmdecrypt.RootHelperTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/RootHelper.kt app/src/test/java/com/ncmdecrypt/RootHelperTest.kt
git commit -m "feat: add RootHelper with root detection and su runner"
```

---

## Task 6: QqMusicKeyImporter — copy vault via root, import ekeys

**Files:**
- Create: `app/src/main/java/com/ncmdecrypt/QqMusicKeyImporter.kt`

- [ ] **Step 1: Write the file**

```kotlin
package com.ncmdecrypt

import android.content.Context
import java.io.File

/**
 * On a rooted device, copies QQ Music's plaintext MMKV ekey vault out of its private
 * /data/data/<pkg>/files/mmkv directory and imports the keys via [EkeyStore]. Only the
 * plaintext MMKVStreamEncryptId vault is parseable (see CLAUDE.md invariant #5); an encrypted
 * vault yields zero keys and is reported as [NoData]. Run off the main thread.
 */
object QqMusicKeyImporter {

    private val CANDIDATE_PACKAGES = listOf(
        "com.tencent.qqmusic", "com.tencent.qqmusiclite", "com.tencent.qqmusicpad"
    )

    private const val FOUND_MARKER = "FREENOTE_FOUND:"

    sealed class ImportResult {
        data class Success(val added: Int, val total: Int, val pkg: String) : ImportResult()
        object NoRoot : ImportResult()
        object RootDenied : ImportResult()
        object NoData : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun importEkeys(context: Context): ImportResult {
        if (!RootHelper.isRootAvailable()) return ImportResult.NoRoot

        val tmpRoot = File(context.cacheDir, "root_vault_tmp")
        runCatching { tmpRoot.deleteRecursively() }
        tmpRoot.mkdirs()

        // For each candidate, copy its mmkv dir into tmp/<pkg> and echo a marker if present.
        val script = buildList {
            for (pkg in CANDIDATE_PACKAGES) {
                val dst = File(tmpRoot, pkg).absolutePath
                val srcDir = "/data/data/$pkg/files/mmkv"
                add("if [ -d $srcDir ]; then mkdir -p $dst; cp -r $srcDir/. $dst/ 2>/dev/null; " +
                    "chmod -R 666 $dst 2>/dev/null; echo $FOUND_MARKER$pkg; fi")
            }
        }

        val result = RootHelper.runAsRoot(script)
        if (!result.launched) return ImportResult.RootDenied
        // su can exit 0 even when denied on some managers; treat "no marker AND no readable file"
        // as denied only when su clearly failed. Otherwise distinguish NoData below.
        val foundPkgs = result.stdout.lineSequence()
            .filter { it.startsWith(FOUND_MARKER) }
            .map { it.removePrefix(FOUND_MARKER).trim() }
            .toList()

        return try {
            var added = 0
            var contributingPkg: String? = null
            for (pkg in foundPkgs) {
                val dir = File(tmpRoot, pkg)
                val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".crc") } ?: emptyList()
                for (f in files) {
                    val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
                    val n = runCatching { EkeyStore.importFrom(context, bytes) }.getOrDefault(0)
                    if (n > 0) { added += n; if (contributingPkg == null) contributingPkg = pkg }
                }
            }
            when {
                foundPkgs.isEmpty() && result.stdout.isBlank() && result.stderr.isNotBlank() ->
                    ImportResult.RootDenied
                added > 0 -> ImportResult.Success(added, EkeyStore.count(), contributingPkg ?: foundPkgs.first())
                else -> ImportResult.NoData
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "import failed")
        } finally {
            runCatching { tmpRoot.deleteRecursively() }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ncmdecrypt/QqMusicKeyImporter.kt
git commit -m "feat: import QQ Music ekeys from private MMKV vault via root"
```

---

## Task 7: Root import UI (button visible only when rooted)

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/ncmdecrypt/MainActivity.kt`

- [ ] **Step 1: Add the layout button**

In `activity_main.xml`, add below `outputDirButton` (GONE by default):

```xml
        <com.google.android.material.button.MaterialButton
            android:id="@+id/rootImportButton"
            style="@style/Widget.Ncm.Button.Text"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="2dp"
            android:text="@string/root_import"
            android:visibility="gone"
            app:icon="@drawable/ic_folder"
            app:layout_constraintEnd_toEndOf="parent"
            app:layout_constraintTop_toBottomOf="@id/outputDirButton" />
```

Then re-anchor `selectFilesButton`'s top to the root button:

```xml
            app:layout_constraintTop_toBottomOf="@id/rootImportButton"
```

(Because `rootImportButton` is `gone` and chains directly under `outputDirButton`, a gone view
keeps its constraint anchor position; the action row stays put when no root.)

- [ ] **Step 2: Add strings**

```xml
    <string name="root_import">Root 导入密钥</string>
    <string name="root_import_confirm_title">通过 Root 导入密钥</string>
    <string name="root_import_confirm_msg">将使用 Root 权限读取 QQ音乐 的本地密钥库（MMKV），仅在本机解析，不上传任何数据。继续？</string>
    <string name="root_import_continue">继续</string>
    <string name="root_import_running">正在通过 Root 导入…</string>
    <string name="root_import_success">已从 QQ音乐 导入 %1$d 个密钥（共 %2$d）</string>
    <string name="root_import_no_keys">未找到可用密钥（可能是加密版 vault）</string>
    <string name="root_import_no_root">未检测到 Root</string>
    <string name="root_import_denied">Root 授权被拒绝</string>
    <string name="root_import_failed">Root 导入失败</string>
```

- [ ] **Step 3: Declare field + wire in onCreate**

Add the field:

```kotlin
    private lateinit var rootImportButton: MaterialButton
```

In onCreate after `outputDirButton = findViewById(...)`:

```kotlin
        rootImportButton = findViewById(R.id.rootImportButton)
        rootImportButton.visibility = if (RootHelper.isRootAvailable()) View.VISIBLE else View.GONE
        rootImportButton.setOnClickListener { onRootImportClicked() }
```

- [ ] **Step 4: Add the handler**

```kotlin
    private fun onRootImportClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.root_import_confirm_title)
            .setMessage(R.string.root_import_confirm_msg)
            .setPositiveButton(R.string.root_import_continue) { _, _ -> runRootImport() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runRootImport() {
        Toast.makeText(this, R.string.root_import_running, Toast.LENGTH_SHORT).show()
        Thread {
            val result = QqMusicKeyImporter.importEkeys(this)
            runOnUiThread {
                val msg = when (result) {
                    is QqMusicKeyImporter.ImportResult.Success ->
                        getString(R.string.root_import_success, result.added, result.total)
                    is QqMusicKeyImporter.ImportResult.NoData ->
                        getString(R.string.root_import_no_keys)
                    is QqMusicKeyImporter.ImportResult.NoRoot ->
                        getString(R.string.root_import_no_root)
                    is QqMusicKeyImporter.ImportResult.RootDenied ->
                        getString(R.string.root_import_denied)
                    is QqMusicKeyImporter.ImportResult.Error ->
                        getString(R.string.root_import_failed)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateKeyCount()
            }
        }.start()
    }
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml app/src/main/res/values/strings.xml app/src/main/java/com/ncmdecrypt/MainActivity.kt
git commit -m "feat: root key-import button and flow"
```

---

## Task 8: Build, test, final verification

**Files:** none (verification only)

- [ ] **Step 1: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the new `RootHelperTest`. No regressions in existing tests.

- [ ] **Step 2: Assemble debug**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` produced.

- [ ] **Step 3: Manual smoke test (maintainer, rooted device — cannot run from dev env)**

1. Custom dir: tap 保存目录 → pick a folder → decrypt a known `.ncm`/`.qmc` → confirm the
   output appears in that folder and plays in-app.
2. Reset: tap 保存目录 → 恢复默认 → decrypt → confirm it lands in 音乐/FreeNote again.
3. Root: on a rooted phone with QQ音乐 installed, confirm the Root 导入密钥 button is visible,
   tap it → grant su → confirm a positive key count toast, then decrypt a previously
   un-decryptable `.mflac0`/musicex file successfully.

- [ ] **Step 4: Final commit (if any uncommitted verification tweaks)**

```bash
git add -A && git commit -m "test: verify custom output dir + root key import build"
```

---

## Self-review

- **Spec coverage:** Feature 1 (OutputDirStore T2, save logic T3, UI T4, dep T1) ✓; Feature 2
  (RootHelper T5, QqMusicKeyImporter T6, UI T7) ✓; testing (T5 unit + T8 build/test/manual) ✓.
- **Placeholders:** none — all steps carry concrete code/commands.
- **Type consistency:** `SaveResult(cacheFile, publicPath, mediaStoreUri, customDirUri)` used
  consistently (T3); `ImportResult` variants match between T6 definition and T7 `when` (Success,
  NoData, NoRoot, RootDenied, Error); `OutputDirStore.getTreeUri/displayName/set/clear`,
  `RootHelper.isRootAvailable/runAsRoot`, `QqMusicKeyImporter.importEkeys` signatures align
  across tasks.
