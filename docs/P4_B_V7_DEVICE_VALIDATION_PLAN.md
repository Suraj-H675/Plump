# Plump version-7 candidate — device-validation plan (not installation approval)

**Prepared:** 2026-11-14. **Scope:** package the mastermind-accepted P4-A and P4-B host changes for later, separately approved testing on the owner's iQOO Neo 6 (I2202, Android 14/API 34). **Installation status: NOT INSTALLED.** There is no authorization here to inspect app-private phone data, change phone settings, kill app processes, reinstall an APK or manipulate native saves. Physical touchscreen actions are performed **only by the owner**, never simulated by ADB.

## 1. Repository, code and APK identity

- Canonical Plump Git checkout; branch `main`; package `dev.plump.emulator`; one worktree. Base accepted P4-B commit `22678fbbe7f8e074a7409d25ddfcd331d2c2be17`. This candidate changes **only** `versionCode`/`versionName` in `app/build.gradle` and adds this preparation document; no runtime code, core revisions, permissions, ROMs or native-save formats changed.
- **Frozen candidate** (private, Git-ignored host directory, **outside Gradle's cleanable build tree**): `.plump-private/device-validation/p4b-v7/Plump-v7-0.2.2-p4b-session-debug-arm64.apk`. The copy under `app/build/p4-b-v7-preinstall/` has the same SHA-256, but the private frozen copy is the **specific artifact** proposed for future installation, not an arbitrary `app-debug.apk` that could be overwritten by later builds. Private candidate/rollback APKs are mode `0600`, parent directories mode `0700`.
- Package `dev.plump.emulator`; `versionCode=7`; `versionName=0.2.2-p4b-session`; debug build; `minSdk=26`, `targetSdk=35`, `compileSdk=35`; ABI **arm64-v8a only**. Contains `libmelondsds_libretro.so`, `libmgba_libretro.so`, `libplump_ds_host.so`, `libplump_host.so`, all under `lib/arm64-v8a/`.
- Candidate **size: 2,859,065 bytes**. **SHA-256: `ebffea9a50c4c824780cca756dbeffc44866b84c406c6a41b9b120bcec873d79`.** Android APK Signature Scheme **v2 verified**; one Android Debug signer, SHA-256 certificate `c48b3d3b6e5913013476b3bf8f9de667dac6121a6c8e713da3ce986d7b09e016`. ZIP alignment verified.
- Manifest: main Activity exported as launcher, DS Activity not exported; `android:allowBackup=false`, `android:usesCleartextTraffic=false`. **No Android permissions are requested** (`aapt dump permissions` matches installed v6 exactly). No Internet permission, online services, or expanded Android privilege.
- **Signing compatibility is independently verified against the *actual installed v6 APK***, not merely a local keystore name: read-only ADB `pm path` and `adb pull` copied the currently installed Plump APK, checked with `apksigner`, and compared the v6/v7 certificate SHA-256 digests byte-for-byte. The installed v6 APK also matches the older verified P3 certificate. No app-private files or game data were accessed.
- Installed v6: `dev.plump.emulator`, code `6`, name `0.2.1-p3-layout-measure`, SHA-256 `71aed3148a7b08627176e9cb5a74480c6f13184fb2b402e417283e6ea4a71795`, 2,865,330 bytes, same debug signer. The **read-only recovered rollback APK** is at `.plump-private/device-validation/p4b-v7/rollback/installed-v6.apk`, Git-ignored and outside Gradle build cleanup. Its byte-identical source was retrieved from the installed package, not from app-private data.

## 2. Build/host evidence already executed — PASS

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
TMPDIR="$PWD/app/build/p4-b-v7-preinstall/tmp" \
./gradlew --offline --no-daemon --console=plain \
  :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug
```

**Result:** `BUILD SUCCESSFUL`, 58 JVM tests / zero failures / zero errors / zero skips, full ARM64 debug APK, Android lint. Project-local ignored log: `app/build/p4-b-v7-preinstall/logs/gradle-v7-full.log`.

Additional fresh **licensed, disposable Linux-host core tests**: pinned mGBA wrote and cold-restored actual 32 KiB SRAM from MIT `gba-tests/save/sram.gba` (ROM SHA-256 `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`); pinned melonDS performed first/cold genuine DS 8 KiB EEPROM writes and in-game checks from CC0 `p3_save_diagnostic.nds` (ROM SHA-256 `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70`). GBA RGB565 renderer, DS dual-screen split, CC0 DS stylus positions, A+B simultaneous control and PSG audio callbacks passed. Outputs are only under `app/build/p4-b-v7-preinstall/host-regression/`. **These host tests do not prove v7 Android JNI/lifecycle behavior on the phone.**

## 3. Mandatory approval and pre-install protection — NOT YET PERFORMED

**Gate 0 — explicit new authorization.** The mastermind must separately authorize a *single* version-7 update, private app-data backup, read-only inspection of scoped Plump fixture save data, and narrowly defined future `am force-stop` test. Any additional destructive action, rollback restore, or injected touchscreen command is **not** implied. The owner operates Pause, Back, Home, Recents, file picker and virtual buttons personally. Do not inspect personal ROMs, firmware or unrelated applications even after approval.

**Gate 1 — reconfirm identity.** One and only one connected target device must report `I2202`, API 34/Android 14 and package `dev.plump.emulator` code 6. Re-run local `sha256sum` of the **frozen** v7 APK and installed-v6 rollback APK, `aapt dump badging`, `apksigner verify --print-certs`, and `aapt dump permissions`; stop on any mismatch. Do not accept an APK rebuilt later under the same path without fresh sign-off.

**Gate 2 — make data quiescent.** Owner opens Plump, stops/saves any active licensed diagnostic fixture with its UI, and leaves Plump. Verify no emulator worker is actively mutating its private files. Do **not** call `pm clear`, uninstall, auto-tap, force-stop before its native saves have been flushed, or change developer/rotation settings. Confirm available free host space for at least two independent copies and permission to use app-private debug `run-as`.

**Gate 3 — take a brand-new private, checked snapshot BEFORE installation.** Create a restricted `umask 077` unique directory inside `.plump-private/device-validation/p4b-v7/backups/` (ignored by Git and outside Gradle cleanup), distinct from the historical v5-to-v6 backup. Archive **all Plump-owned app-private persistent storage**, rather than assuming the GBA journal is the whole app: `files/native-saves/` including `.history` and recovery/backup records; `files/nds-native-saves/`; `files/nds-system/`; `files/nds-core-data/`; existing `files/session-receipts/` if present; any other persistent `files/`, `shared_prefs/`, `databases/`, or `no_backup/`. Use `run-as dev.plump.emulator` strictly on its own `/data/user/0/dev.plump.emulator` sandbox. Treat archive bytes as **opaque/private**; do not open game data or print personal filenames in public reports. Do not touch unrelated app directories or external ROM folders.

Illustrative command template **for execution only after that approval and after confirming `run-as`, `tar`, shell and storage behavior on the target**:

```bash
set -euo pipefail
umask 077
PKG=dev.plump.emulator
# SERIAL: deliberately obtained and verified against the connected iQOO;
# BACKUP: a unique NEW directory inside private, ignored
# .plump-private/device-validation/p4b-v7/backups/
adb -s "$SERIAL" exec-out run-as "$PKG" sh -c \
  'cd /data/user/0/dev.plump.emulator && tar -cf - .' \
  > "$BACKUP/app-private.tar.part"
test -s "$BACKUP/app-private.tar.part"
tar -tf "$BACKUP/app-private.tar.part" > "$BACKUP/archive-index.private.txt"
# Reject missing expected directories, absolute/traversal entries, unsafe links
# and incomplete archive; unpack only into a restricted private scratch folder.
# Independently compare each app-private file's device-side SHA-256 and size
# to the extracted host copy. Recheck after closing/reopening the archive.
mv "$BACKUP/app-private.tar.part" "$BACKUP/app-private.tar"
sha256sum "$BACKUP/app-private.tar" > "$BACKUP/app-private.tar.sha256"
```

The archive SHA alone does **not** prove complete capture. Produce two privately held, normalized file inventories (phone `run-as` SHA-256+byte length and extracted host SHA-256+byte length), verify each file including both generations and receipts, detect absent/corrupt generations and report the **verified recovery point**. Verify backup tar can actually be listed/extracted, and check no valid save is omitted. Include the captured installed v6 APK, its hash and debug signer in the same recovery evidence ledger. Keep the older P3 backup **unchanged** as an independent fallback. If any persistent file is unreadable, changes during capture, an inventory mismatch, or any backup verification fails: **STOP; do not install**.

**Gate 4 — safe baseline.** Record original raw-file hashes and separately verify the `PLSV` v1 envelopes (game SHA, generation, supported lengths and payload SHA-256) for licensed fixtures, without modifying bytes. Preserve two valid generations even if one is older. Keep per-ROM/mode identity separate; record expected pre-update save payload hashes privately. Save-archive and phone-side checksums must agree *before* installation. Do not declare a historical save never existed merely because a legacy receipt is absent.

## 4. Conditional installation — NOT AUTHORIZED IN THIS STEP

Only if **all four preflight gates pass** and the mastermind authorizes installation: revalidate the exact frozen v7 APK SHA/signature/package, then run precisely one data-preserving update:

```bash
adb -s "$SERIAL" install -r \
  "$PWD/.plump-private/device-validation/p4b-v7/Plump-v7-0.2.2-p4b-session-debug-arm64.apk"
```

**Never** use `adb uninstall`, `pm clear`, `install -g`, or a new package ID; do not overwrite any app-private file for test convenience. After Android reports `Success`, verify `dumpsys package` returns code 7 and the expected name; do not assume a successful exit alone proves data survived. Capture read-only installed APK/package details and a **new** app-private post-update file-hash inventory before launching games. If the app-private files unexpectedly disappear or differ pre-launch, stop for recovery instead of advancing the journal.

## 5. Exact physical validation sequence — owner interactions and evidence

| # | Proposed approved-device test | Required observed evidence / stop condition |
|---|---|---|
| **1** | Owner launches v7 without a ROM; choose Library/Open only when ready. | Correct package/version, no crash, **no fabricated exact-frame Resume**; no unexpected change to existing native saves. |
| **2 — P4-A** | Owner opens already authorized licensed `/sdcard/Plump-P2-Test/sram.gba` **without replacing it**. | Genuine GBA SRAM program displays **“All tests passed”**; updated Android JNI reports **exact verified 32,768-byte restore** of prior native payload before first frame, despite mGBA's initial 131,072-byte autodetection buffer. Older verified progress is present, no rejected/truncated restore. If invalid: stop and preserve both journal generations. |
| **3 — GBA rendering/input/audio** | Owner opens licensed `/sdcard/Plump-P2-Test/p2-input-audio.gba`. Hold **A** (red screen and audible PSG tone); release A (neutral screen and tone stops); hold **A+B** simultaneously (yellow). Optionally B/blue, direction and Start to compare prior P2 behavior. | Visual input and release correct, one coherent audio stream, no touch sticking, real frame updates; owner observation marked separately from logs. |
| **4 — GBA lifecycle** | Owner taps Pause/Resume 3 times; while gameplay is running, physically uses Home/Recents and returns **five cycles**, including one pause while a touch/button was held. | Frames and sound stop in background, release all buttons, no duplicate audio/core loop on return, foreground resumes once, no spurious save-failure toast. Collect process logcat and receipts read-only, without injected ADB touches. |
| **5 — switch console** | From GBA, owner opens **DS** using the app UI, then returns to GBA using actual device navigation; repeat at least twice without reopening an unrelated app. | Previous game native save is flushed before switching; a paused GBA cannot advance while DS runs and vice versa; no duplicate threads/AudioTracks or blocked activity under a successful save. Fail-closed errors are recorded, not worked around by clearing data. |
| **6 — DS rendering/input/audio** | Owner loads licensed `/sdcard/Plump-P3-Test/p3_input_diagnostic.nds`; tests normal and expanded bottom-screen layout, taps/drag stylus across three places, holds **A+B**, listens for PSG and verifies release. | Both screens render independently with correct proportions; stylus location, button chord, tone and pause/return work. No direct host-initiated touches. |
| **7 — DS EEPROM cold persistence** | Owner loads licensed `/sdcard/Plump-P3-Test/p3_save_diagnostic.nds`, checks whether its existing counter reads `RESTORED`; if a new write is explicitly approved, use the test ROM's built-in action, save/close, then cold reopen from the file picker. **Never load a DS save state.** | Real program shows prior/new saved counter, JNI reports a **byte-verified 8,192-byte cartridge restore before frame 1**, checksum/generation validates; newer canonical EEPROM progress must not be overwritten by any historical state. |
| **8 — receipt lifecycle** | With the synthetic GBA and DS ROM identities, capture **read-only** `files/session-receipts/{gba,ds}-<ROM_SHA256>.[01]` during RUNNING, after user Pause/Home (PAUSED), and after normal Activity closure (CLOSED). | Independently parse schema 1 and SHA-256 checksum, console/ROM identity, generations, legal states and two-slot fallback. RUNNING is *evidence of an interrupted or unresolved start* if seen after death, not proof that gameplay advanced. Missing/corrupt receipts display `UNKNOWN`, not clean shutdown. Do not edit/delete receipt slots on phone. |
| **9 — controlled process death** | **Separate explicit authorization after a verified backup**: owner starts one disposable fixture, host confirms its current RUNNING receipt read-only, then perform a scoped Plump-only process termination (proposed `adb -s "$SERIAL" shell am force-stop dev.plump.emulator`). Relaunch manually, reselect the fixture using the picker. | Confirm latest receipt remains RUNNING **after** termination (otherwise the no-callback scenario is inconclusive); app does not auto-replay a state or claim exact-frame resume; last **confirmed native save** restores. No native-save loss, crash loop or interference with other applications. |
| **10 — Activity/surface survival** | Repeat screen lock/unlock and Recents/background returning, then normal vs enlarged DS surface presentation. Where supported, physically rotate and restore portrait without changing system settings. | No stale surface, distorted panel or duplicate core, no unexpectedly changed native payload. **Do not claim Activity recreation from rotation alone:** both Activities declare `configChanges`. A true forced-recreation scenario may require another approved test mechanism; otherwise mark LFC-03 **PARTIAL**. |
| **11 — data integrity closure** | Owner exits Plump gracefully. Take a second *independent, read-only* scoped snapshot/hash inventory with the previously approved procedure. | Every originally valid native-save identity retains valid PLSV envelope/payload; in-game writes may legitimately change a current payload, but must have a recoverable older generation and match observed counter. Receipt generations may increase; their checksum must validate. Unrelated core data, backups and fixture files are unchanged unless a documented emulator write explains it. No cross-game/console save contamination. |

**Test boundaries:** only known CC0/MIT diagnostics already authorized on the phone; no personal game, firmware, NAND, other application or system-setting inspection. Save state loading remains disabled, writable homebrew virtual SD remains disabled, and DSi/3DS are not introduced. Evidence includes phone model/API, exact installed APK signature, owner observations, read-only scoped logs, actual receipt bytes/hashes, game identities, before/after protected-file and payload hashes, clock/sequence, crash-free outcome and each gate marked PASS/PARTIAL/FAIL/BLOCKED. These are **future tests**, not presently observed results.

## 6. Rollback and no-data-loss policy

**Rollback prerequisites available now:** the installed v6 signed APK is privately archived at `.plump-private/device-validation/p4b-v7/rollback/installed-v6.apk`, with SHA-256 `71aed3148a7b08627176e9cb5a74480c6f13184fb2b402e417283e6ea4a71795`; v7 and v6 signer digests agree. The old v5-to-v6 backup remains intact. **The essential fresh pre-v7 app-private data backup has deliberately NOT yet been taken**; installation is blocked until future authorized backup/verification.

If v7 fails: immediately stop gameplay and preserve diagnostics and a **separate private v7 post-failure snapshot** without changing or deleting prior backups. If an approved downgrade is necessary, only after re-checking the current package and target artifact and separately authorizing rollback, attempt `adb -s "$SERIAL" install -r -d "$BACKUP_ORIGINAL_V6_APK"` (same package/cert, debug APK); Android downgrade success is **not assumed** until actually verified. If refused, **stop**; never uninstall or clear data to force a downgrade. The `PLSV` v1 journal and native-save directories are unchanged across v6/v7; v6 ignores newly added receipt records, but compatibility must still be checked from actual file bytes and program restore behavior. If app-private recovery is required, preserve the newest data first, verify both archives, identify the exact damaged entries, obtain explicit owner permission for scoped restoration, and **never overwrite the only remaining good native save**. Do not automatically copy the entire old archive back over newer legitimate progress.

**Hard stop triggers:** missing or invalid backup, version/signing/ABI mismatch, unexpected permission, save-file loss, checksum/length mismatch, unresolved save/receipt error, duplicate emulator/audio session, core unload/join timeout, missing ROM grant, unknown process-death classification or a failed fallback. Do not bypass blocked transitions by editing receipts or deleting data.

## 7. P4 acceptance boundary / next approval

P4-A and P4-B are **accepted at host/build level only**. Physical verification of the new JNI and LFC-01 through LFC-05 remains mandatory and cannot be represented as passed from the above procedure. The current task stops at version-7 packaging and this plan. **Mastermind must authorize backup and installation separately**; no `adb install -r`, app-private snapshot, forced process death, virtual-SD enablement, DS state loading, P4-C, P4-E or P5 work has occurred.
