# Custom output directory + Root key import — design

Date: 2026-06-06
Status: approved (design), pre-implementation

Two independent features for FreeNote / NcmDecrypt:

1. **Custom save directory** for decrypted audio (currently fixed to MediaStore `Music/FreeNote`).
2. **Root key import** — detect root and, on user request, pull QQ Music's ekey vault
   from its private `/data/data/...` dir and import the keys.

Both are additive. No existing behaviour is removed; both degrade gracefully to today's
behaviour when unused/unavailable.

---

## Feature 1 — Custom output directory

### Decision

Use the Storage Access Framework folder picker (`ACTION_OPEN_DOCUMENT_TREE`) with a
**persistable** URI permission. The chosen directory is an **optional override**: when set,
the public copy is written there; when unset (or the permission was lost), we fall back to
today's default (MediaStore `Music/FreeNote` on Q+, `/sdcard/FreeNote` on legacy).

Alternatives rejected:
- Raw filesystem path — requires `MANAGE_EXTERNAL_STORAGE` (all-files access), which is
  review-hostile and a privacy red flag for this app.
- Pure MediaStore relative path — cannot target arbitrary folders or removable SD cards.

Side benefit: SAF works on API 21+, so this gives Android ≤9 a save target that needs no
broad storage permission either.

### Components

**`OutputDirStore.kt`** (new `object`, single purpose — persist the chosen tree):
- Backed by `SharedPreferences("output_dir")`, key `tree_uri` (string).
- `set(context, uriString)` — persist the string. (The `takePersistableUriPermission`
  call lives in the Activity, where the result-intent flags are available.)
- `getTreeUri(context): Uri?` — return the stored tree URI **only if** a matching entry is
  present in `contentResolver.persistedUriPermissions` with write access; otherwise null
  (treat as "unset" so callers fall back to the default).
- `displayName(context): String?` — `DocumentFile.fromTreeUri(...).name` for the button label.
- `clear(context)` — forget the override (and release the persisted permission).

**`MainActivity.kt`** changes:
- `registerForActivityResult(OpenDocumentTree())`: on a non-null uri →
  `contentResolver.takePersistableUriPermission(uri, READ|WRITE)`, `OutputDirStore.set`,
  refresh label, toast.
- New `outputDirButton` (Material text button in the header action area).
  - Tap when **unset** → launch the picker.
  - Tap when **set** → `AlertDialog` with *更换目录 / 恢复默认 / 取消*.
  - Label: `保存目录：默认` or `保存目录：<folder name>`.
- `saveDecrypted()`:
  - If `OutputDirStore.getTreeUri()` is non-null → `saveViaTreeUri(treeUri, outputName, mime, src)`
    and set `anyCustomDirOutput = true`. The returned document content-URI is the public copy.
  - On **any** failure of the custom write (null/exception) → fall through to the existing
    MediaStore (Q+) / legacy (`≤P`) branch, so a decrypt never silently loses its output.
- `saveViaTreeUri(treeUri, outputName, mime, src): String?`:
  `DocumentFile.fromTreeUri(this, treeUri)?.createFile(mime, outputName)?.let { doc ->
  contentResolver.openOutputStream(doc.uri)?.use { src.inputStream().copyTo(it) }; doc.uri.toString() }`
  — wrapped so any exception returns null.
- `SaveResult` gains `customDirUri: String?`. When building the `Track`, the public URI passed
  to `TrackBuilder` is `mediaStoreUri ?: customDirUri` (a SAF content URI is read by
  `ContentResolver.openInputStream` exactly like a MediaStore URI, so playback/share is unaffected).
- Completion-toast state: add `anyCustomDirOutput` → new string "已保存到所选文件夹".

### Data flow

decrypt → `saveDecrypted` → [custom tree set? → `saveViaTreeUri`] else [`saveViaMediaStore` / legacy]
→ cache copy (always, for player + FileProvider share) → `SaveResult` → `TrackBuilder`.

### Dependency

Declare `implementation("androidx.documentfile:documentfile:1.0.0")` directly (already present
transitively).

### Edge cases

- Permission revoked / folder deleted → `getTreeUri` returns null → default path used.
- Duplicate name → `createFile` auto-disambiguates; output name already carries `_HHmmss`.
- Pre-Q devices → custom tree works (API 21+); no `WRITE_EXTERNAL_STORAGE` needed.

---

## Feature 2 — Root key import (QQ Music MMKV only)

### Decision

Button-triggered (not automatic on launch). Scope: QQ Music's **plaintext**
`MMKVStreamEncryptId` vault only — the one `MmkvParser` already parses. Kugou's
`KGMusicV3.db` is SQLCipher-encrypted and explicitly out of scope (CLAUDE.md invariant #4/#5).

### Components

**`RootHelper.kt`** (new `object`):
- `isRootAvailable(paths = DEFAULT_SU_PATHS): Boolean` — existence check of a `su` binary in
  the common locations (`/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/su/bin/su`,
  `/system/sbin/su`, `/vendor/bin/su`, `/odm/bin/su`). Cheap; does **not** fire a su prompt.
  `paths` is injectable for unit testing.
- `runAsRoot(commands: List<String>, timeoutMs: Long): RootResult` —
  `Runtime.getRuntime().exec("su")`, write each command + `\n` then `exit\n` to its stdin,
  read stdout/stderr, `waitFor` bounded by `timeoutMs` (default ~20s; destroy + Timeout on overrun).
  `RootResult(exitCode, stdout, stderr)`. First real invocation triggers the su grant dialog.

**`QqMusicKeyImporter.kt`** (new `object`):
- `CANDIDATE_PACKAGES = ["com.tencent.qqmusic", "com.tencent.qqmusiclite", "com.tencent.qqmusicpad"]`.
- `importEkeys(context): ImportResult` (caller runs it off the main thread):
  1. If `!RootHelper.isRootAvailable()` → `NoRoot`.
  2. Create a private temp dir under `cacheDir` (e.g. `root_vault_tmp`).
  3. Build one su script: for each candidate package, if `/data/data/<pkg>/files/mmkv`
     exists, `cp -r /data/data/<pkg>/files/mmkv/. <tmp>/<pkg>/` then `chmod -R 666 <tmp>/<pkg>`.
  4. `runAsRoot(...)`. If the command never produced a readable file and stderr indicates a
     denied/aborted su → `RootDenied`. If no package dir existed → `NoData`.
  5. For each copied file (skip `*.crc`), feed bytes to the **existing**
     `EkeyStore.importFrom(context, bytes)` (auto-detects MMKV, filters ekey-like values,
     persists, returns count). Sum the added counts; remember which package contributed.
  6. **Delete the temp dir** (raw vault copies) before returning.
  7. Return `Success(added, total = EkeyStore.count(), pkg)` / `NoData` (dir existed but
     0 keys parsed — likely an encrypted vault) / `Error(message)`.
- `sealed ImportResult: Success | NoRoot | RootDenied | NoData | Error`.

**`MainActivity.kt`** changes:
- `rootImportButton` — visibility set in `onCreate` from `RootHelper.isRootAvailable()` (GONE
  when no root, so non-rooted users never see it).
- Tap → `AlertDialog` explaining it will use root to read QQ音乐's key database (继续 / 取消).
- On 继续 → background thread → `QqMusicKeyImporter.importEkeys(this)` → `runOnUiThread`:
  toast the mapped result string and refresh the existing import-keys count label
  (`updateImportKeysLabel`).

### Result → string mapping

- `Success(n>0)` → "已从 QQ音乐 导入 %1$d 个密钥（共 %2$d）"
- `Success(0)` / `NoData` → "未找到可用密钥（可能是加密版 vault）"
- `NoRoot` → "未检测到 Root"
- `RootDenied` → "Root 授权被拒绝"
- `Error` → "Root 导入失败"

### Edge cases / safety

- su denied or hung → bounded timeout → `RootDenied` / `Error`; never blocks the UI thread.
- Encrypted (AES-CFB) vault → parses to 0 keys → `NoData` message (consistent with invariant #5).
- Raw vault copies live only transiently in `cacheDir` and are deleted right after import.
  ekeys themselves are already persisted by `EkeyStore` today — no new trust surface.
- No new manifest permission (SAF needs none; `su` is an external binary).

---

## Testing

- **JVM unit tests** (`app/src/test`) for the isolatable pure logic:
  - `RootHelper.isRootAvailable(paths)` with an injected list of existing/absent temp paths.
  - A small target-selection helper (custom-tree-set vs. fall-back) if extracted pure.
  - ekey parsing itself is already covered via `EkeyStore`/`MmkvParser` tests.
- **Not unit-testable here** (Android runtime / device-specific): SAF `DocumentFile`,
  `SharedPreferences`, the `su` subprocess. Covered by:
  - a clean compile (`assembleDebug`) + the existing `:app:testDebugUnitTest` suite (no regressions), and
  - a **manual rooted-device smoke test** the maintainer runs: pick a custom folder, decrypt,
    confirm the file lands there; on a rooted phone with QQ音乐 installed, tap Root 导入,
    grant su, confirm a positive key count and that a previously un-decryptable `.mflac0`
    now decrypts. This step cannot be run from the dev environment and is called out as such.

## Out of scope

- Kugou `.kgg` / `KGMusicV3.db` SQLCipher extraction.
- Automatic-on-launch root scanning.
- Backing up the raw vault file to the output directory (explicitly declined).
