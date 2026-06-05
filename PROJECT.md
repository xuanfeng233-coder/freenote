# FreeNote Public Release Hardening Plan

Date: 2026-06-05

This document persists the staged plan for fixing the audit findings with a
public app-store release and long-term maintenance as the target. It is a design
and execution guide, not an implementation diff.

## Current Status

Last updated: 2026-06-05

- Phase 1 code is complete.
- Phase 1 automated validation passed:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:lintDebug`
  - `./gradlew :app:testDebugUnitTest` (`NO-SOURCE` until Phase 4 adds tests)
  - `./gradlew :app:assembleRelease`
- Debug APK signing was verified with `apksigner`; the signer is
  `CN=Android Debug`.
- Real-device smoke testing is still pending because no ADB device was attached:
  in-app playback, notification controls, and lock-screen controls.
- Next planned work: Phase 2 public-release storage compliance.

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

- `./gradlew testDebugUnitTest` runs meaningful tests.
- New tests cover both success paths and malformed input failures.

### Instrumented or Manual Smoke Tests

- Maintain a short manual test checklist in `PROJECT.md` or `README.md` for:
  - selecting multiple files through SAF;
  - decrypting one NCM and one QMC sample;
  - MediaStore output visibility;
  - background playback, notification controls, and lock-screen controls;
  - metadata edit and resync;
  - sharing via FileProvider.

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

- Move real keystores, `keystore.properties`, and third-party app installers out
  of the repository working directory when practical.
- Keep `.gitignore` protections in place.
- Before publishing, run `git status --short` and verify no sensitive file is
  tracked.

Acceptance:

- The repository can be zipped or shared without private signing material or
  copyrighted third-party installers.

### Release Checklist

- Build debug and release variants.
- Verify release signing identity intentionally.
- Install on a real device and run the smoke checklist.
- Archive the APK/AAB only from `app/build/outputs`.
- Record versionCode/versionName changes in the release notes.

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
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

`testDebugUnitTest` becomes meaningful after Phase 4 adds test sources.

## Current Known Audit Findings Mapped to Phases

- Exported playback service without source control: Phase 1.
- Plain imported EKey store plus backups: Phase 1.
- Unsafe output filename construction: Phase 1.
- Debug build signed with release keystore: Phase 1.
- `MANAGE_EXTERNAL_STORAGE` public-release risk: Phase 2.
- Temp files left after decrypt failures: Phase 1.
- Unbounded `readBytes()` and decoder allocations: Phase 3.
- No automated tests: Phase 4.
- Broad ProGuard keep rules: Phase 4.
- Local keystore/APK hygiene: Phase 5.
