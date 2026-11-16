# P4-BR — v8 physical Android device validation and safe-update evidence

**Date:** 2026-11-16. **Target:** owner's iQOO Neo 6 (`I2202`), Android 14/API 34, ARM64. **Branch/revision under test:** `main` at `e597dccbdef81daf42ecac412257d040fab8c588` (accepted P4-BR host repair). **Status: focused v8 installation, DS ROM-reload regression, GBA controls/SRAM, and ordinary pause/background behavior VERIFIED ON DEVICE; final protected save integrity PASS; broad P4/LFC acceptance PENDING mastermind review.**

This is the *post-approval physical validation* addendum to [`P4_BR_NATIVE_CRASH_FIX_REPORT.md`](P4_BR_NATIVE_CRASH_FIX_REPORT.md). The original report correctly recorded v8 as not yet installed **at the time it was written**. This follow-up documents the subsequent one-time authorized installation and real-device execution; it does not reinterpret prior host evidence or accept a separate milestone.

## 1. Scope, approval, and immutable artifacts

The mastermind authorized exactly one `adb install -r` of the frozen v8 candidate, conditioned on uniquely authorized I2202, gracefully quiescent app, complete fresh Plump-only backup, identity/signature/version checks, and byte-verified protected native-save journals. Explicitly **excluded:** any Android `am force-stop`/process-death test, ADB-driven touchscreen input, phone-file corruption or restoration, changes to other apps or personal ROMs, app code/feature work, public APK distribution, Git push, unrelated milestones, and unapproved rollback. The owner performed all actual touch/navigation/audio observations. ADB access was confined to read-only device state, Plump-only scoped log and exit history, and `run-as` reads inside Plump's own sandbox, apart from the one approved APK update.

| Protected item | Exact identity and verification |
| --- | --- |
| v8 candidate | `dev.plump.emulator`, versionCode **8**, versionName `0.2.3-p4br-ds-reload`, ARM64-only, minSdk 26 / targetSdk 35, **no requested Android permissions**; SHA-256 `8be596f0258579fcc8463243fc20ccbb27b77eb33a9a6270159667bc2645e38c` |
| v7 installed before update | versionCode **7**, versionName `0.2.2-p4b-session`; its actual pulled APK matched the protected v7 frozen APK SHA-256 `ebffea9a50c4c824780cca756dbeffc44866b84c406c6a41b9b120bcec873d79` |
| Signing certificate | APK v2 signature verified; v7, frozen v8, and installed v8 agree on certificate SHA-256 `c48b3d3b6e5913013476b3bf8f9de667dac6121a6c8e713da3ce986d7b09e016` |
| Fresh, standalone pre-v8 backup | 22 files / 13 directories, complete app-private archive SHA-256 **`ccf9863a5f7b84368ce57da86dfbfcc99b5f345a68aebfecd4b531cbba5a5ba5`**. Phone-before, separately extracted host, and phone-after inventories matched *all* paths, file sizes, and SHA-256 hashes. Archive could be reopened, all entries safely extracted, and all 8 historical licensed native-save records validated. |
| Older protected artifacts | Pre-v7 backup archive SHA-256 `0f0b5eea3c6d58f4b4bcf89ec1f911b8e9069c0ef627f273ee3c643ae1da82b4` and protected v7/v6 APKs preserved; no restore, downgrade, or overwrite. |

Fresh backup lives only under ignored/restricted `.plump-private/device-validation/p4br-v8/backups/pre-v8-20261116T052205Z-ca7cb73a/` (`0600` files, `0700` directory). Private actual installed-APK copies, full app inventories, validation logs, and scripts remain ignored/uncommitted. No save payload, ROM content, personal data, APK, or device serial is included in this report or Git.

**Installation:** after every preflight gate passed, the **single** approved `adb install -r <exact-frozen-v8-apk>` returned `Success` (exit 0). Before any ROM launch, Android reported versionCode 8; a separately pulled *installed* `base.apk` was byte-identical to approved v8, including its signer. All **22** pre-update app-private files and **13** directories remained present and byte-identical, with **zero** unexpected added files. Plump's process was not running during the backup or immediately after the update. No retry or rollback occurred.

## 2. Actual Android DS regression — five ROM loads in one process

The owner selected only the separately licensed diagnostic fixtures:

- DS input/audio fixture (`p3_input_diagnostic.nds`), SHA-256 `109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d`.
- DS synthetic EEPROM fixture (`p3_save_diagnostic.nds`), SHA-256 `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70`.

**Actual device sequence:** input/audio → EEPROM → input/audio → EEPROM → input/audio. The owner confirmed actual upper/lower screen graphics, stylus/touch dragging, A+B, and audio on each input-fixture visit; on *both* EEPROM visits the display showed **`Initial save: RESTORED`**, **`Initial counter: 1`**, **`Current counter: 1`**, and **`Write: NOT REQUESTED`**. The owner did not press A in the EEPROM fixture, so no intentional EEPROM write was requested.

Independent Plump-scoped Android logs, preserved across the circular log-buffer turnover, recorded five distinct `DS loaded: geometry 256x384, fps 59.826` markers, all in the **same Android PID `5248`**:

| Sequence | Device-local log timestamp | Proven outcome |
| --- | --- | --- |
| DS input #1 | 10:56:11.280 | Both screens/input/audio owner-verified; process `5248` |
| DS EEPROM #1 | 11:02:29.863 | Native cartridge save **restored and byte-verified**, generation 20 |
| DS input #2 | 11:14:50.177 | Controls/audio/stylus owner-verified; same process |
| DS EEPROM #2 | 11:16:52.205 | Native cartridge save **restored and byte-verified**, generation 35 |
| DS input #3 | 11:20:25.576 | Controls/audio/stylus owner-verified; same process |

Telemetry showed DS measured rate approximately **59.7–60.0 FPS** against 59.83 target, and **zero logged audio underruns**. Frame rejection was *not identically zero*: 3 frames at one EEPROM sampling point and 1 at the last input sampling point; frame posting continued without `PlumpDS` errors. No native crash, PID replacement, deadlock, repeated renderer ownership error, or native-save **payload** mutation was observed. EEPROM journal slots remained valid 8 KiB PLSV records with generation advances through **41/42** by end-of-DS, **unchanged payload SHA-256** relative to the fresh pre-v8 baseline. Corresponding session receipts showed earlier ROM `CLOSED`, current ROM `RUNNING` while foregrounded, and `PAUSED` during backgrounding. Their two-slot SHA-256 envelopes verified independently.

**Focused outcome:** the exact sequential DS reload pattern that triggered the old melonDS renderer crash under v7 **passes on v8 physical hardware for these fixtures**, across five loads within one process. This is not proof that every DS game works or that all other historical native crashes are fixed.

## 3. GBA↔DS handoff, real SRAM, controls, and lifecycle

Following the five DS loads, the owner switched back to GBA in the **same PID `5248`**.

- **MIT GBA SRAM fixture** (`sram.gba`, SHA-256 `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`): owner saw **“All tests passed.”** Android's native log: `Native SRAM restore readback VERIFIED: generation=34 bytes=32768`. Both real 32 KiB PLSV journal slots remained valid and retained historical payload hashes, even as envelope generations advanced. GBA video posted real nonblack 240×160 frames; no rendering `err` or lock failure.
- **GBA input/audio fixture** (`p2-input-audio.gba`, SHA-256 `b9ec1db3f1e0c91a4019e2058de0b08b6468bf7896baa9dc3279988fd6a0235a`): owner confirmed A turns the display red and starts a tone, releasing A stops the tone, and A+B turns display yellow. The Android log confirmed ongoing genuine 240×160 nonblack frame posting without renderer `err` or lock failure. Sound/control assertions are the **owner's actual-device observations**; Android telemetry alone does not measure speaker output or press correctness.
- **GBA pause/resume:** three owner-performed Pause → Resume cycles passed; two-slot receipt checksum valid, latest state `RUNNING`, generation advanced from **43 to 49**, consistent with six state transitions; native save payloads unchanged; PID remained `5248`.
- **Ordinary Android Home/Recents:** three owner-performed Home → Recents → Plump returns worked with controls/audio operational. PID remained `5248`, current GBA receipt `RUNNING`, rendering continued, save payloads unchanged, no additional native crashes. Receipt advanced **49 → 51 (two generations)**; this is **not** independent proof of *three* distinct Android Home/Recents events, so the three-cycle count is attributed only to the owner's observation. Full Activity destruction/recreation, screen rotation/GPU context loss, and abrupt process death were not exercised.
- After GBA restoration and subsequent ROM selection, previous GBA/DS sessions had valid `CLOSED` receipts and no duplicate active session. The two-slot GBA input and SRAM journals retained historical payload checksums; their envelopes advanced normally.

The last live GBA session displayed over 35,000 emulated frames in device logs prior to closure. Rejected frames accumulated up to 8 on that run, but subsequent valid nonblack frames continued and `err=0`; no inference of perfect zero-drop presentation is made. No heat, battery, latency, or broad game compatibility benchmark was performed.

## 4. Final normal Android exit and read-only data audit

The user closed Plump via **ordinary Android navigation/Recents task removal**, **not** an in-app Exit button (none exists in this prototype), force-stop, test kill, or ADB command. ADB independently confirmed no `dev.plump.emulator` process. Android `dumpsys activity exit-info` recorded:

```text
2026-11-16 11:36:16.660  pid=5248
reason=10 (USER REQUESTED) subreason=22 (REMOVE TASK) status=0
```

Historical app-native crash count remained **4 before and after** this v8 test; the latest exit was **not a native crash**. Historical older FORCE STOP entries in Android's exit history predate this test and do not describe an action in this validation. No process-death test was performed.

The final entirely **read-only**, Plump-only inventory was collected twice with consistent device-side SHA-256 and sizes. It matched the fresh backup's path set: **22/22 files and 13/13 directories preserved**. All **8/8 protected PLSV native-save slots** (four licensed fixture identities, 2 slots each) parsed with valid magic/version, game SHA identity, length, generation, and payload SHA; all eight payload hashes remained byte-equivalent to the protected pre-v7 / pre-v8 baseline, including an older licensed GBA diagnostic. All **8/8** two-slot session receipts parsed with valid identities, generations and checksums. The remaining **6/6 non-journal original files** retained identical byte sizes and SHA-256 values. The private pre-v8 tar archive remained unchanged at the exact documented SHA-256 and could still be reopened. No application session restarted during the final audit.

**Final latest receipt states:**

| Licensed fixture | Latest state | Generation | Device interpretation |
| --- | --- | --- | --- |
| GBA input/audio (last active game) | **`PAUSED`** | **54** | **Not `CLOSED`** after Android removed the task; pause was persisted and native payload intact. Do not call this a successful explicit close-receipt test. |
| GBA SRAM | `CLOSED` | 15 | Previous game closed safely during switch. |
| DS input/audio | `CLOSED` | 19 | Previous game closed safely during switch. |
| DS EEPROM | `CLOSED` | 23 | Previous game closed safely during switch. |

**Important limitation:** the GBA Activity's `onPause()` writes `PAUSED`, whereas its `onDestroy()` tries `closeHost()` and publishes `CLOSED` if successful. Android task removal can terminate the process without a durable `onDestroy()` close transition. The resulting last `PAUSED` receipt is **observed**, not silently treated as `CLOSED`. Because the native save is valid and device process is gone, no save loss was observed, but this does **not** satisfy an unconditional “normal task removal always produces `CLOSED`” expectation. Evaluate whether `PAUSED` is the intended durable recovery boundary or whether this deserves a separate lifecycle UX/contract follow-up; do not retrofit behavior in this validation task.

## 5. Evidence, exact change boundary, and checks

**Read-only inspections and one approved update** (all returned successful exit status unless the early preflight script hit a fail-closed bug *before* backup capture/installation, which was corrected locally): `adb devices -l`, `getprop`, `dumpsys package`, `pm path`, `pidof`, `dumpsys activity activities`, `dumpsys activity exit-info`, `adb exec-out run-as ...` for restricted file inventory, SHA-256, sizes, journals and receipt bytes, Plump/PlumpDS-tag-scoped `logcat`, `aapt dump badging/permissions`, `apksigner verify --print-certs`, one `adb pull` of actual installed APK at each approved verification stage, and exactly **one** `adb install -r` of the frozen v8 candidate. Local Python archive extraction and independent checksums validated the initial protected snapshot and the final read-only data audit; no ADB touch injection or writing any data back to the phone. The backup creation was a local private archive of the debuggable Plump app sandbox. The preliminary backup script once returned nonzero because `pidof` legitimately returns 1 when no process exists; it was fixed **before any app data capture/install**. Two later *read-only verification scripts* also initially assumed an overly strict `RUNNING` receipt on a backgrounded EEPROM session or five retained load markers in the circular log buffer; reruns accounted for `PAUSED` and collated already-preserved logs without weakening integrity criteria. The Home/Recents receipt-count assertion was correctly reclassified as owner-observed (rather than independently counted). No native-save mismatch was encountered.

Private evidence location (never commit/publish): `.plump-private/device-validation/p4br-v8/`, notably `backups/pre-v8-20261116T052205Z-ca7cb73a/verified-private-manifest.json`, `post-install-verification.private.json` in that backup folder, `v8-first-ds-before-switch-baseline.private.json`, each per-fixture/switch verification `.private.json` and scoped `.private.log`, `v8-post-graceful-exit-history.private.txt`, and `v8-post-graceful-exit-audit.private.json`. Ignored local verification scripts: `app/build/p4-br-v8-device-backup/`. Detailed phone inventories, APK copies, and raw logs are deliberately restricted to these private locations.

**Git boundary:** this follow-up is documentation only, added as `docs/P4_BR_V8_ANDROID_DEVICE_VALIDATION_REPORT.md`. The app and emulator code are unchanged at tested revision `e597dccb...`; no new build, version change, ROM or personal file access, or core modification occurred. Existing unrelated `docs/P3_TECHNICAL_REPORT.md` modified and `docs/P4_ENGINEERING_PLAN.md` untracked states were preserved untouched. No Git push, branch/worktree creation, public APK distribution, rollback, or other milestone was performed.

## 6. Assessment and mastermind decision requested

**Recommended disposition: ACCEPT the narrowly scoped P4-BR v8 device fix verification**, since the previously crashing real-device DS input→EEPROM→input sequence, repeated to five total DS loads, succeeded in one PID; the actual OEM phone showed correct touch, visuals/audio, verified EEPROM/GBA SRAM restoration, protected update and save integrity, normal pause/resume and Home/Recents observations, and zero new native crashes. **Do not equate** this with acceptance of *all* P4 recovery, all DS games, exact-frame DS state loading, full Android lifecycle matrix or premium UI.

**Known gaps requiring distinct approval/planning:** (1) final task-removal receipt `PAUSED` rather than `CLOSED`, and whether this is the intended documented recovery semantics; (2) LFC-02 controlled process-death test **explicitly excluded** until separately authorized with a disposable durable fixture; (3) LFC-03 full Activity/renderer recreation, orientation and GPU context-loss stress untested; (4) production UI/back/exit affordance absent in the current prototype—owner navigated with Android OS, not invented in-app controls; (5) long-run thermal and input latency, broader game coverage, and several save-state/backup features remain out of scope. LFC-01 received limited physical evidence, not broad acceptance; P4 phase-level SAV/LFC matrix remains open.

**Next bounded task:** mastermind reviews this physical report and decides on narrow P4-BR acceptance. Only on separate approval should engineering address any receipt contract/Android task-removal nuance or execute an authorized LFC-02/LFC-03 device test. **Stop; do not automatically start unrelated work.**
