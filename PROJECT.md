# FreeNote Public Release Hardening Plan

Date: 2026-06-05

This document persists the staged plan for fixing the audit findings with a
public app-store release and long-term maintenance as the target. It is a design
and execution guide, not an implementation diff.

## Current Status

Last updated: 2026-06-06

- Phase 1 code is complete.
- Phase 2 code is complete.
- Phase 3 code is complete.
- Phase 4 initial JVM coverage is in progress:
  - Added focused unit tests for filename sanitization, imported EKey parsing,
    QMC footer handling, malformed header bounds, and audio format detection.
  - Fixed two issues found by the new tests: MP3 frame-header detection now treats
    header bytes as unsigned, and line-based EKey import now handles comma
    separators when the base64 value ends with `=` padding.
- Latest Phase 4 automated validation passed:
  - `./gradlew :app:testDebugUnitTest` (15 tests)
- Phase 5 release hygiene has started:
  - Added support for release signing properties stored outside the repository
    via `FREENOTE_KEYSTORE_PROPERTIES` or `-PfreenoteKeystoreProperties`.
  - Moved real signing material and root-level APK/third-party installer files
    out of the repository working directory to
    `/Users/xuanfeng/claudecode/freenote-private-artifacts-20260606`.
  - Full release validation passed on 2026-06-06:
    `FREENOTE_KEYSTORE_PROPERTIES=/Users/xuanfeng/claudecode/freenote-private-artifacts-20260606/signing/keystore.properties ./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease`.
  - Signing verification passed on 2026-06-06: debug APK signer is
    `CN=Android Debug`; release APK signer is
    `CN=BraynLabs Software, O=BraynLabs Software, OU=Mobile, C=CN`.
  - Root-level `local.properties` remains because neither `ANDROID_HOME` nor
    `ANDROID_SDK_ROOT` is set in the local shell environment.
  - Real-device Phase 5 install/smoke verification is blocked until an ADB
    device is connected.
- Previous full automated validation passed:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:lintDebug`
  - `./gradlew :app:assembleRelease`
- The merged release manifest no longer declares `MANAGE_EXTERNAL_STORAGE`,
  `READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, or
  `android:requestLegacyExternalStorage`; legacy `WRITE_EXTERNAL_STORAGE` is
  limited to Android 9 and below.
- Debug APK signing was verified with `apksigner`; the signer is
  `CN=Android Debug`.
- Real-device smoke testing has started on a Vivo V2121A running Android 13
  (API 33): the release APK installed over the existing signed package and
  cold-launched successfully. End-to-end decrypt testing is still pending
  because no encrypted sample files were present on shared storage: in-app
  playback, notification controls, lock-screen controls, Android 10+ MediaStore
  output visibility, and metadata edit/resync.
- Next planned work: connect an Android device to complete Phase 5
  install/smoke testing, then continue Phase 4 codec-vector tests and
  dependency/lint cleanup.

## Goals

- Make the app suitable for public distribution, including scoped-storage
  expectations and safer default permissions.
- Reduce externally triggerable behavior, sensitive-data leakage, and accidental
  release-key exposure.
- Preserve the core user workflow: select encrypted music, decrypt locally, play,
  edit metadata, and share output.
- Add focused tests around codec correctness, malformed inputs, file handling,
  and storage fallback behavior.

## Non-Goals

- Do not redesign the UI.
- Do not rename the application ID, namespace, or app label.
- Do not rewrite all codec implementations as part of the first hardening pass.
- Do not introduce network behavior.

## Phase 1: Security Stopgap

Objective: remove the highest-risk issues without changing the main user flow.

Status: code complete on 2026-06-05; manual playback/control smoke testing is
pending on a connected Android device.

### Playback Service Access

- Keep background playback and system media controls working.
- Restrict `PlaybackService.onGetSession(controllerInfo)` so only trusted
  controllers receive the session.
- At minimum allow this app's own package and trusted system/media controllers.
- Return `null` for unknown third-party controllers.
- Add a regression test or instrumentation-oriented helper where practical; if
  Media3 controller testing is too heavy, document the allowlist decision in the
  service code.

Acceptance:

- In-app playback still works.
- Lock-screen and notification controls still work on a real device.
- Lint no longer reports an unrestricted exported service, or the remaining
  warning is justified and documented.

### Backup and Imported Keys

- Set `android:allowBackup="false"` for the application.
- Keep the current `EkeyStore` JSON file format for now to avoid migration risk.
- Do not add AndroidX Security in this phase unless a later review decides key
  encryption is required.

Acceptance:

- Imported keys are not eligible for Android Auto Backup.
- Existing users do not lose imported keys during a normal app upgrade.

### Safe Output Filenames

- Add one small filename sanitizer used for all filesystem writes.
- Treat SAF `DISPLAY_NAME` as untrusted.
- Remove path separators, control characters, leading/trailing whitespace,
  traversal fragments such as `..`, and platform-reserved filename characters.
- Enforce a reasonable filename length before adding the timestamp and extension.
- Keep showing the original display name in the UI, but write only sanitized
  output names.
- Apply the sanitized name to public output, cache copies, and cover sidecars.

Acceptance:

- A malicious provider cannot write outside the intended output/cache directory
  using a crafted display name.
- Normal Chinese, English, space, and punctuation-heavy song names still produce
  readable output names.

### Temporary File Cleanup

- Make `decryptNext()` delete encrypted and decrypted temp files in a `finally`
  path.
- At the start of a new decrypt batch, clean stale files from
  `cacheDir/decrypt_work`.
- Do not delete `cacheDir/decrypted_music` during cleanup because it backs
  playback and sharing.

Acceptance:

- Failed decrypts do not leave encrypted sources or partial decrypted audio in
  `decrypt_work`.
- Successful playback and sharing still work after decrypt completion.

### Debug Signing

- Remove release signing from the `debug` build type.
- Keep release signing conditional on local `keystore.properties`.
- Keep `keystore.properties`, keystores, APKs, AABs, and third-party installer
  APKs ignored by Git.

Acceptance:

- `assembleDebug` uses the normal debug signing identity.
- `assembleRelease` uses the release key only when local signing config exists.

## Phase 2: Public-Release Storage Compliance

Objective: make storage behavior compatible with public app-store expectations.

Status: code complete on 2026-06-05; automated validation passed; release APK
installed and cold-launched on Android 13. Android 10+ MediaStore visibility and
metadata-resync smoke testing remain pending until encrypted sample files are
available on the device.

### Default Storage Model

- Make MediaStore the default public-output path on Android 10+.
- Save decrypted audio under `Music/FreeNote` using `RELATIVE_PATH`.
- Keep user-visible success messages aligned with the actual destination.
- Stop asking for all-files access in the default public-release build.

Acceptance:

- On Android 10+, decrypting without special storage permission writes to
  `Music/FreeNote`.
- Files are visible to system music apps and file managers after completion.

### Permission Strategy

- Remove `MANAGE_EXTERNAL_STORAGE` from the default Play/public flavor.
- Remove the default prompt that sends users to All Files Access settings.
- Keep legacy `WRITE_EXTERNAL_STORAGE` behavior only where it is still relevant
  for supported Android versions.
- Consider a separate non-Play/internal flavor only if direct `/sdcard/FreeNote`
  writes remain important for sideload users.

Acceptance:

- Public flavor does not request `MANAGE_EXTERNAL_STORAGE`.
- Lint no longer reports the scoped-storage policy warning for the public flavor.

### Metadata Resync

- For MediaStore-backed output, continue mirroring tag edits through the stored
  MediaStore URI.
- For direct-path output in any internal flavor, retain the existing resync path
  but require safe filenames and permission checks.

Acceptance:

- Editing tags after decrypt updates the playable cache and public MediaStore
  copy where possible.
- A revoked permission or stale URI fails gracefully with a user-facing message.

## Phase 3: Robustness Against Malformed Inputs

Objective: avoid crashes and memory pressure from malicious or malformed files.

Status: code complete on 2026-06-05; automated validation passed. Focused
malformed-input fixtures and codec regression tests remain for Phase 4.

### Bounded Imports

- Add size limits for key imports before reading the entire stream into memory.
- Reject oversized MMKV/JSON/text key files with a clear toast.
- Add equivalent size limits for cover image selection.

Suggested initial limits:

- Key import: 16 MB.
- Cover image: 20 MB raw input, with bitmap decode bounds checked before display.

Acceptance:

- Selecting an oversized key file or cover does not OOM the app.
- Normal MMKV key exports and album covers continue to work.

### Decoder Header Validation

- Add explicit upper bounds before allocating arrays from file-controlled lengths.
- Validate NCM key length, metadata length, cover frame length, and image length.
- Validate QMC trailer metadata length before allocation.
- Validate KGM/KWM offsets before seeking or copying.
- Convert malformed input failures into `DecryptException` with concise
  user-facing messages where possible.

Acceptance:

- Truncated and malformed NCM/QMC/KGM/KWM samples fail cleanly.
- No `NegativeArraySizeException`, OOM, or uncaught parse exception is expected
  for obviously invalid inputs.

### Error Reporting

- Keep detailed internal exception classes/messages short enough for list rows.
- Avoid exposing stack traces or raw file paths in user-facing strings.

Acceptance:

- Failed rows show useful, non-sensitive reasons.
- Batch decrypt continues after one malformed file fails.

## Phase 4: Test Coverage and Maintainability

Objective: make future changes safe enough for long-term maintenance.

Status: started on 2026-06-06; first JVM unit-test suite is complete and passing.

### Implementation Tasks

- [x] Add JVM unit-test dependencies and `app/src/test` structure.
- [x] Expose the imported EKey parser through a narrow `internal` testable entry
  while keeping runtime import behavior unchanged.
- [x] Cover filename sanitizer edge cases: traversal fragments, reserved
  characters, control characters, fallback names, Unicode, and length limits.
- [x] Cover EKey import parsing for JSON, line-based text, and MMKV fixtures,
  including MMKV last-write-wins and delete semantics.
- [x] Cover QMC footer behavior for oversized metadata, negative audio length,
  and musicex external-key candidate names.
- [x] Cover malformed header bounds for NCM, QMC, KGM, and KWM without requiring
  large allocations.
- [x] Cover audio format detection and extension/magic tag detection.
- [ ] Add codec vector tests only when known-good reference vectors are available
  in the repo or supplied by a maintainer.
- [ ] Re-run full release validation after the broader Phase 4 suite stabilizes:
  `assembleDebug`, `lintDebug`, `testDebugUnitTest`, and `assembleRelease`.

### Unit Tests

- Add JVM unit tests where Android framework dependencies are not required.
- Start with:
  - filename sanitizer edge cases;
  - EKey import parsing for JSON/text/MMKV fixtures;
  - QMC footer parsing behavior;
  - malformed header bounds for NCM/QMC/KGM/KWM;
  - audio format detection.
- Add codec vector tests only from known-good reference vectors.

Acceptance:

- `./gradlew :app:testDebugUnitTest` runs meaningful tests.
- New tests cover both success paths and malformed input failures.

### Instrumented or Manual Smoke Tests

- Maintain a short manual test checklist in `PROJECT.md` or `README.md` for:
  - selecting multiple files through SAF;
  - decrypting one NCM and one QMC sample;
  - MediaStore output visibility;
  - background playback, notification controls, and lock-screen controls;
  - metadata edit and resync;
  - sharing via FileProvider.

Manual checklist:

- [ ] On Android 10+, select multiple encrypted files through the system picker
  and confirm the app retains access after background/foreground.
- [ ] Decrypt one known-good NCM sample and one known-good QMC sample with an
  embedded key.
- [ ] Confirm decrypted output appears under `Music/FreeNote` in a system file
  manager or music app.
- [ ] Play a decrypted track in-app, then verify background playback,
  notification controls, and lock-screen controls.
- [ ] Edit title, artist, album, and cover; confirm playback cache and
  MediaStore-backed public copy are resynced where possible.
- [ ] Share a decrypted track and confirm the receiving app can open the
  FileProvider URI.
- [ ] Optional: try a QQ musicex/STag sample without imported keys and confirm a
  clear no-key failure; import a matching MMKV/JSON/text key fixture and retry.

Acceptance:

- A release candidate can be verified with a repeatable checklist.

### Dependency and Lint Cleanup

- Upgrade AndroidX, Material, and Media3 dependencies in a separate PR/change set.
- Re-run `lintDebug` after each upgrade batch.
- Treat the ECB lint warning in `MusicDecoder` as expected for compatibility
  decryption, not as new-data encryption. Add a narrowly scoped suppression with
  a comment explaining why.
- Fix low-risk lint items opportunistically: content descriptions, unused
  resources, obsolete resource qualifiers, and overdraw.

Acceptance:

- Lint warnings are either fixed or explicitly justified.
- Dependency upgrades do not change decrypt/playback behavior.

### ProGuard/R8 Rules

- Remove broad keep rules for `com.ncmdecrypt.**` unless a specific class needs
  reflection.
- Keep only jAudioTagger and any proven reflection-dependent classes.
- Verify release build and metadata editing after shrinking.

Acceptance:

- `assembleRelease` succeeds.
- Tag editing and playback still work in a minified release build.

## Phase 5: Release Hygiene

Objective: reduce accidental leaks and make releases repeatable.

### Local Artifact Hygiene

- [x] Support real signing properties stored outside the repository while
  preserving root-level `keystore.properties` compatibility for local builds.
- [x] Move real keystores, `keystore.properties`, and third-party app installers out
  of the repository working directory when practical.
- [x] Keep `.gitignore` protections in place for `keystore.properties`,
  keystores, APKs, AABs, and local machine files.
- [x] Before publishing, run `git status --short` and verify no sensitive file is
  tracked.

Acceptance:

- The repository can be zipped or shared without private signing material or
  copyrighted third-party installers.

### Release Checklist

- [x] Build debug and release variants.
- [x] Run `:app:testDebugUnitTest` and `:app:lintDebug` as part of release
  validation.
- [x] Verify release signing identity intentionally.
- [ ] Install on a real device and run the smoke checklist.
- [x] Archive the APK/AAB only from `app/build/outputs`.
- [x] Record versionCode/versionName changes in the release notes/checklist.

2026-06-06 release hygiene notes:

- Current generated artifacts:
  - `app/build/outputs/apk/debug/app-debug.apk`
  - `app/build/outputs/apk/release/app-release.apk`
- Current release metadata: `applicationId=com.braynlabs.freenote`,
  `versionCode=1`, `versionName=1.0.0`.
- `git status --short` was clean before Phase 5 edits and no sensitive files
  were tracked by Git.
- Real signing files and root-level APK/installers were moved to
  `/Users/xuanfeng/claudecode/freenote-private-artifacts-20260606`:
  `signing/keystore.properties`, `signing/braynlabs.keystore`, and
  `apks/`.
- A root-level ignored `local.properties` remains only to locate the Android SDK
  for local Gradle builds.
- `adb devices -l` reported no connected devices, so install and smoke testing
  remain pending.

Acceptance:

- A release can be reproduced without relying on undocumented local state.

## Recommended Execution Order

1. Phase 1 in one small hardening change.
2. Phase 2 as the public-release storage change.
3. Phase 3 malformed-input and size-limit hardening.
4. Phase 4 tests and dependency/lint cleanup.
5. Phase 5 release hygiene before the first public release candidate.

## Validation Commands

Run these after relevant phases:

```bash
./gradlew lintDebug
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease
```

`testDebugUnitTest` now runs Phase 4 JVM coverage. Add codec-vector tests only
from known-good reference vectors.

## Current Known Audit Findings Mapped to Phases

- Exported playback service without source control: Phase 1.
- Plain imported EKey store plus backups: Phase 1.
- Unsafe output filename construction: Phase 1.
- Debug build signed with release keystore: Phase 1.
- `MANAGE_EXTERNAL_STORAGE` public-release risk: Phase 2.
- Temp files left after decrypt failures: Phase 1.
- Unbounded `readBytes()` and decoder allocations: Phase 3.
- Initial automated tests added in Phase 4; broader codec vectors remain.
- Broad ProGuard keep rules: Phase 4.
- Local keystore/APK hygiene: Phase 5.
