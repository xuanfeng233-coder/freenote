# CLAUDE.md

Working guide for Claude Code (and human contributors) in this repo. For the public
project overview, supported formats, and algorithm reference, read **README.md** — this
file is the *operating manual* for changing the code, not a re-description of it.

## What this is

FreeNote / NcmDecrypt — an offline Android decoder for DRM-wrapped local music files
(NCM / QMC family / TM / KGM / KWM …). Pure Kotlin, no server, no network. The user
supplies files they already downloaded; the app strips the container encryption and
writes plain FLAC/MP3/OGG/etc.

- `namespace = com.ncmdecrypt`, `applicationId = com.braynlabs.freenote`, app label **FreeNote**.
- The three names (NcmDecrypt / FreeNote / com.braynlabs.freenote) are historical; don't "fix"
  them without a reason — renaming the applicationId breaks update installs.

## Build / install / test

```bash
# Debug build (default debug keystore if no keystore.properties present)
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk

# Release build (signed only if keystore.properties + the .keystore exist; else unsigned)
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk

# JVM unit tests for codec-adjacent pure logic
./gradlew :app:testDebugUnitTest

# Install to a connected device (-r replace, -t allow test, -g grant runtime perms)
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
```

- **Toolchain**: Java 17, Android SDK 34, Gradle 8.5 (wrapper), AGP 8.2.2, Kotlin 1.9.22.
  `versionName` / `versionCode` live in `app/build.gradle.kts` (the *only* source of truth).
- **JVM unit tests now exist** under `app/src/test`. They cover pure logic around filename
  sanitization, EKey/MMKV parsing, QMC footer behavior, malformed header bounds, and audio
  format detection. Full codec vectors should still come only from known-good reference
  implementations (see below). Real-device smoke testing remains manual: Bluetooth/`adb push`
  a real `.ncm` / `.qmc` / `.kgm` / `.kwm` to the phone, open it in the app, play the output.
- Historic test device: Vivo/OPPO `3142621725000KW` (Android 14+). If `adb install` hangs,
  unlock the screen / confirm the on-device install dialog.
- Signing: real keystore is **git-ignored** (`keystore.properties`, `*.keystore`). Copy
  `keystore.properties.example` → `keystore.properties` to sign locally.

## Critical invariants — do not relearn these the hard way

1. **Crypto must match a reference implementation byte-for-byte. Never invent constants,
   tables, or round counts.** A previous version shipped *made-up* KGM/KGG/VPR/KWM algorithms
   (a hardcoded `"3HENGELING"` table, a fixed 256-byte XOR table) that only produced garbage.
   Everything is now ported from **unlock-music** (`algo/qmc/*`, etc.). If you touch a codec,
   re-verify against that source and its official test vectors before claiming it works.

2. **NCM is XOR-over-a-KeyBox, NOT RC4.** Flow: `keyData ^= 0x64` → AES-128-ECB decrypt with
   `SCORE_KEY` → `decryptedKey[17..]` builds a 256-byte KeyBox → XOR the audio with the box
   (not RC4 PRGA). `SCORE_KEY`'s last byte is **`0x57`**, not `0x61` — this exact byte has
   been gotten wrong before.

3. **QMC tail-type detection drives everything** (last 4 bytes of the file):
   `QTag` → ekey in trailer meta; a small LE u32 → `[audio][ekey][keyLen]`; `STag` / `cex\0`
   (musicex) → **no embedded key**, must throw and fall back to imported keys; anything else →
   legacy QMCv1 static cipher over the whole file. Then pick the stream cipher by *real* key
   length: `>300` RC4 (5120-byte segments), `1..300` map, `0` static.

4. **STag / musicex (and `.kgg` / KGM v5) are intentionally NOT offline-decryptable** — the key
   lives in the client's private DB (QQ Music MMKV vault / Kugou `KGMusicV3.db` SQLCipher), not
   in the file. Don't try to "fix" this with an algorithm; the path is the user importing keys,
   or using an older client that embeds keys (see README user section).

5. **MMKV vault parse has a two-varint gotcha**: between key and raw ekey there are *two*
   varints (the value is itself `[varint rawLen][raw]`). Append-only: last write for a key wins,
   zero-length = deleted. Only the plaintext `MMKVStreamEncryptId` vault is supported (no AES-CFB).

6. **Double extensions** (`song.mflac0.flac`, `song.mgg1.ogg`) are matched by dot-segment:
   if any segment (e.g. `mflac0`) is a QMC extension, treat as QMC. Same logic reduces a name
   to its "stem" for ekey matching (`song.mflac` ≡ `song.mflac0.flac`).

## Code map

| File | Responsibility |
|---|---|
| `MusicDecoder.kt` | All codecs. `decrypt(bytes,name)` / `decryptFile(in,out,name)` (64KB streaming, OOM-proof) / `extractNcmInfo`. Format enums live here. |
| `MmkvParser.kt` | Read-only Tencent MMKV vault parser (ekey extraction). |
| `EkeyStore.kt` | Persists imported ekeys to `filesDir/ekeys.json`; name/stem matching. |
| `MainActivity.kt` | UI entry — multi-select (SAF `OpenMultipleDocuments`), batch decrypt, key import, share, help dialog. |
| `FileListAdapter.kt` | File rows: status, cover thumb, play/edit-tags/share buttons. |
| `PlaybackService.kt` | Media3 `MediaSessionService` — background ExoPlayer + lock-screen/notification. |
| `PlayerHub.kt` | Owns the `MediaController` + queue; broadcasts `PlayerState`. |
| `PlayerUiController.kt` | Bottom-sheet player + Apple-Music-style motion (spring cover, mini↔full expand, Palette ambient gradient). |
| `Track.kt` / `TrackBuilder.kt` | `Track` model + building it post-decrypt (NCM header info or `MediaMetadataRetriever`; writes cover sidecar). |
| `MetadataEditor.kt` / `MetadataEditSheet.kt` | jAudioTagger read/write of title/artist/album/cover + bottom-sheet editor. |

## Conventions

- **All UI text → `res/values/strings.xml`** (incl. accessibility `contentDescription`). No
  string literals in layouts/Kotlin. The QQ/Kugou version-rollback guidance is `help_message`.
- **No hardcoded hex colors** in layouts — go through theme tokens (`colors.xml` → `themes.xml`,
  Material 3 dark theme).
- Public decrypted output defaults to MediaStore `Music/FreeNote` on Android 10+; legacy
  direct storage is only for Android 9 and below where applicable. Output name:
  `<原名>解锁_HHmmss.<ext>`. Cache copy via `FileProvider` powers share + in-app playback.
- APE is decoded by no ExoPlayer build here — it decrypts fine but won't preview.

## Don't commit

The commercial app installers (QQ音乐 / 酷狗 APKs) bundled in the working dir, the real keystore,
`local.properties`, and any built APK. All are git-ignored; APKs ship as GitHub Release assets.

## Reference implementations (consult before editing a codec)

- **unlock-music** — `git.unlock-music.dev` (some regions 451-block it). Primary source for QMC / KGM / KWM.
- go-mmkv fixtures — MMKV vault parsing.
- parakeet-rs / libparakeet (DMCA'd; archived), MusicDecrypto, jixunmoe/qmc-decode — cross-checks.
- ncmdump (`github.com/taurusxin/ncmdump`) — NCM reference.
