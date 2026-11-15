# P4-BR — melonDS sequential ROM reload crash repair

**Date:** 2026-11-16

**Scope:** host-only repair, regression and private Android candidate preparation.

**Decision:** **HOST REPAIR READY FOR MASTERMIND REVIEW; P4-B ANDROID DEVICE GATE STILL BLOCKED.**
**Android:** no phone commands, APK installation, process termination, ROM selection or save modification performed during P4-BR. The already accepted P4-A GBA restoration evidence remains valid.

## 1. Observed crash and pinned upstream source

On the owner's iQOO I2202 / Android 14, installed Plump v7 `0.2.2-p4b-session` terminated at **2026-11-16 00:33:14 device time**, PID **24295**, with `SIGSEGV` / `SEGV_MAPERR`. Android's tombstone contains:

```text
MelonDsDs::RenderStateWrapper::UpdateRenderer
MelonDsDs::CoreState::ApplyConfig
MelonDsDs::CoreState::LoadGame
retro_load_game
Java_dev_plump_emulator_NativeDs_load
```

Source-grounded cause in pinned `JesseTG/melonds-ds` Libretro wrapper revision `f394adbacb5722ee97c1b37c8064da9a25818310` (melonDS dependency `16f127dbb587a73371827ce79689c5d237c4b59b`):

- `app/build/p3-melonds-source/src/libretro/core/core.cpp`: `CoreState::UnloadGame()` clears `Console`; it does not clear `_renderState`.
- `CoreState::LoadGame()` calls `ApplyConfig(Config)` **before** creating the new console with `CreateConsole(...)`.
- When the previous renderer remains configured, `ApplyConfig()` can call `_renderState.UpdateRenderer(Config, *Console)`; this dereferences the null `Console`.
- `app/src/main/cpp/nds_host.cpp` in v7 called `retro_unload_game`, but left that initialized Libretro core alive for the next `retro_load_game`. `EmulatorSessionOwner` had correctly stopped and saved the previous game: the missing step was the **native core-state reset between ROMs**.

This explains the demonstrated v7 second-load crash, **not** all earlier Android native crashes. The upstream core itself still permits the unsafe raw Libretro sequence; Plump's adapter now prevents that sequence through its own API. No upstream revision or renderer implementation was changed.

## 2. Fix and ownership invariants

**Implementation:** `app/src/main/cpp/nds_host.cpp` only. The fix is lazy so no unnecessary reset occurs when a user selects an invalid file before a valid game has been loaded:

1. On every real `retro_load_game` attempt, remember that the core may retain stale/partial renderer state (even when the load is rejected).
2. After the currently owned game has been stopped, save-flushed and unloaded by `EmulatorSessionOwner`, the **next valid** `NativeDs.load` executes, under the existing `coreMutex`: `retro_deinit → re-register environment, video, audio-sample, audio-batch, input-poll, input-state callbacks → retro_init → retro_load_game`.
3. Failed `retro_load_game` results also perform `retro_unload_game` cleanup (for partially created core tasks or consoles), clear ROM/input/audio and require that reset on the next attempt. Invalid input length is rejected; it never calls `retro_load_game`.
4. `NativeDs.shutdown` still unloads then deinitializes and `dlclose`s exactly once; the native handle and reset marker are cleared. It retains existing surface release, input release, and audio flushing. A JNI UTF-string allocation failure now releases any successfully acquired earlier path string and propagates the pending allocation exception.

**Not changed:** `EmulatorSessionOwner` host tokens, the exclusive-console guard, stop/join/save/publish order, `NativeDs` Java signature, core binaries/revisions, renderer options, PLSV v1 save format, save journal transactions, session-receipt schema, game IDs, Android permissions, network policy, DS save-state prohibition, or DSi/3DS scope. There is one core/owner per console; the adapter neither creates another emulator instance nor bypasses an unresolved owner transition.

The call is serialized by `coreMutex`; the Java owner must first stop and join its frame/audio threads and complete native-save durability before unload/next load. Its existing fail-closed behavior remains covered by JVM regression tests. ROM bytes remain in the adapter's owned `rom` vector across `retro_load_game` and until unload; Android surfaces remain under `surfaceMutex` and are not arbitrarily discarded between games.

## 3. Reproducible **before/after** host evidence

`bash scripts/test-p4-br-ds-reload.sh --prove-before-after` builds a **real production JNI adapter** test with minimal host Android window/log stubs. It loads the unmodified pinned **Linux melonDS** `.so`, runs the CC0 input/audio ROM followed by the CC0 synthetic EEPROM ROM in the **same process**, and exercises the same JNI load/unload/frame/snapshot/restore/input/audio/surface/shutdown functions used on Android. This is not a mock core.

| Target | Actual result | Evidence |
|---|---|---|
| Old v7 `nds_host.cpp` retrieved from Git `6eced089...` | **FAIL reproduced:** second ROM load produces native **SIGSEGV** in `MelonDsDs::RenderStateWrapper::UpdateRenderer`. The JVM handles the fatal native signal then exits with code **134**; the signal itself is 11. | Ignored `app/build/p4-br-ds-reload/before-unsafe.log` and `hs_err_pid*.log` |
| Repaired `nds_host.cpp`, same real core, same ROMs | **PASS, exit 0.** First input ROM 90 real dual-screen frames; second EEPROM ROM 90 real frames; third input ROM 55 frames; recovered EEPROM ROM 55 frames; fresh shutdown/reinit input ROM 55 frames. Both 256×192 surface buffers posted. | Ignored `app/build/p4-br-ds-reload/before-after-verified.log` |
| Libretro callbacks through real core | **PASS.** Nonzero PCM captured on input ROM before and after resets (98,468 / 60,174 / 60,174 samples reported). Correct two-screen output, input button/stylus state released on unload. | Same log; `tests/native/nds_host_lifecycle_test.cpp` |
| Native EEPROM persistence | **PASS.** Exact 8,192-byte licensed fixture restored before frames on both EEPROM loads; byte-identical readback after frame execution; no A-button EEPROM write injected during this host switching test. | Same harness and log |
| Bad inputs / repeated cleanup | **PASS.** 511-byte invalid ROM rejected, 4,096-byte bad-content ROM rejected by the actual core; valid subsequent loads succeed. Repeated unload/shutdown, duplicate initialization and reinitialization succeed or reject as specified. | Same harness/log |
| Partial core initialization | **PASS.** Missing JNI path and an intentionally incomplete Libretro `.so` report an error; repeated initialize/shutdown remains retryable without exposing an initialized unusable core. | Same harness/log, `--missing-symbols` branch |

The mastermind's original direct-Libretro `reproduce-second-load-host.py`, `reinit-second-load-host.py`, `reinit-with-callbacks-host.py` and `reinit-second-run-frames-host.py` are **preserved unchanged** in `app/build/p4-b-native-crash-review/`. The original raw Libretro reproducer still demonstrates the upstream core's bug; the new tracked regression specifically establishes that the former **Plump frontend** behavior fails and the repaired frontend succeeds. No upstream core source was patched, and no external test game content was added to Git.

Input ROM SHA-256 `109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d` and synthetic EEPROM ROM SHA-256 `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70`. All new executable/temp/error-report artifacts are ignored under `app/build/p4-br-ds-reload/`.

## 4. Other regressions and remaining toolchain limitation

**Android/Java:** offline Gradle `:app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug`: **BUILD SUCCESSFUL** (53 executed tasks, 18 seconds). **59 JVM tests passed** (0 failures, 0 errors, 0 skips), including a new `EmulatorSessionOwnerTest.multipleDsRomReloadsAndGbaReturnNeverDuplicateOwnedWorkers` (DS A→B→A with GBA→DS→GBA interleaving, stop/save/unload/receipt order, unique run tokens and one worker pair per active console). Android ARM64 debug APK built. Lint **passed with 23 warnings**, not zero warnings; Gradle also reports a future Gradle-9 deprecation warning. Evidence: `app/build/p4-br-ds-reload/gradle-full.log`, `app/build/test-results/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`.

**Actual desktop emulation:** `scripts/test-melonds-ds-host.sh`, `scripts/test-nds-frame-split.sh`, `scripts/test-rgb565-renderer.sh`, host `verify-p2-input-audio.py`, and `verify-p4-a-gba-host.py` (independent first/cold MIT GBA 32 KiB SRAM) **PASS**. The DS input and EEPROM scripts that **rebuild** licensed ROMs via pinned BlocksDS/Docker could not rebuild them: the pinned Docker image/daemon was unavailable in this session. Do **not** count these two wrappers as successful.

The **actual DS ROM runtime checks passed independently** using the prebuilt SHA-verified licensed fixtures and the pinned real Linux melonDS core:

- `verify-p3-input-diagnostic.py`: three stylus locations, release, A+B with held stylus, nonzero PSG audio — **PASS**.
- `verify-p3-native-save-diagnostic.py first` and `cold` in two separate host processes: real in-game EEPROM write, immediate readback and byte-exact 8 KiB cold restoration — **PASS**.
- `verify-p3-native-save-diagnostic.py state-rewind`: demonstrates that older DS state rollback erases newer in-memory EEPROM — **PASS safety proof**, and DS state loading remains disabled.

Evidence: `app/build/p4-br-ds-reload/full-host-regressions.log` (contains the failed Docker/BlocksDS preparation attempts; its earlier aggregate marker is **not authoritative**), and the correctly fail-fast direct checks `app/build/p4-br-ds-reload/ds-direct-verified.log`. No ROM content or save data was pulled from the phone. Rebuilding the pinned BlocksDS ROMs from source is an environmental reproduction **gap**, not a failed runtime regression.

## 5. Version-8 **private, not-installed** Android candidate

`app/build.gradle` changed from code 7 / `0.2.2-p4b-session` to code **8** / **`0.2.3-p4br-ds-reload`**. Frozen, mode `0600` private candidate:

```text
.plump-private/device-validation/p4br-v8/Plump-v8-0.2.3-p4br-ds-reload-debug-arm64.apk
SHA-256 8be596f0258579fcc8463243fc20ccbb27b77eb33a9a6270159667bc2645e38c
Size 2,859,097 bytes
```

`aapt` reports package `dev.plump.emulator`, minSdk 26, targetSdk 35, `arm64-v8a` only, and **zero requested Android permissions**. `apksigner` confirms APK v2 signature verified, debug certificate SHA-256 `c48b3d3b6e5913013476b3bf8f9de667dac6121a6c8e713da3ce986d7b09e016` (same archived signer as v7). `zipalign -c -v 4` verified the archive. The pinned **ARM64 melonDS core bytes** are exactly identical between v7 and v8 (embedded SHA-256 `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30`); only the Plump adapter native library changed. The candidate is neither installed nor released/distributed.

The pre-v7 app-private backup archive remains byte-identical, SHA-256 `0f0b5eea3c6d58f4b4bcf89ec1f911b8e9069c0ef627f273ee3c643ae1da82b4`; both original protected v7/v6 APKs remain unchanged. These private contents were not restored, overwritten or repackaged into the APK.

## 6. Proposed **freshly authorized** Android verification — no installation yet

1. Mastermind approves one exact v8 candidate by **SHA-256**, target I2202/API 34/arm64 and debug signing certificate, including specific permission for a **fresh pre-v8 complete private Plump backup**. Until then: no ADB/phone interaction of any kind.
2. After that approval, the owner gracefully stops any active Plump session and exits using the UI. Confirm quiescence **without force-stop**, then independently capture full pre-v8 app sandbox with `run-as` under a **new** restricted/ignored directory; compare phone-before, host-extracted, and phone-after file counts, sizes and hashes, verify PLSV journals/generations/payloads and session receipts for licensed fixtures. Preserve all prior backups and most recent valid journal generations; stop on any mismatch. Never use historical pre-v7 backup *instead* of fresh pre-v8.
3. Recheck actual installed code 7, APK signer, frozen v8 package/signature/hash, ABI, permissions and v7 native-save baseline. After every gate passes, request a clear one-time installation decision; if approved, `adb install -r` exactly once, then inspect v8 package and unchanged app-private files **before running any game**. No uninstall, `pm clear`, downgrade, file overwrite or silent rollback.
4. The owner repeats licensed GBA SRAM/controls/lifecycle and GBA→DS handoff, then specifically exercises **DS input ROM → DS EEPROM ROM → DS input ROM** in the *same process*, multiple times. Independently capture private Plump-only logs and verify no native crash or duplicate frame/audio workers; verify real DS frames, both screens, stylus, A+B/audio, pause and resize on the device. Verify EEPROM counter/restoration and both journal records before and after, without unnecessary A-button writes; compare to pre-v8 payload hashes. Check `RUNNING` / `PAUSED` / `CLOSED` receipts and journal ordering/checksums.
5. Continue only if each preceding step passes; defer LFC-02 single controlled process-death test to a separately confirmed durable disposable fixture recovery point and **explicit authorization** to terminate Plump once. Surface/recreation/LFC-03 proof remains distinct from ordinary orientation handling. Conclude with a fresh read-only final data-integrity inventory after graceful owner exit.
6. **Immediate stop** on unknown ROM/save identity, missing file, bad checksum, native error, unresolved cleanup, renderer ownership failure or crash. Preserve crash logs/evidence; never automatically attempt rollback. No personal ROM/firmware access, no other app data, no Android setting changes, no automated screen interaction and no public push/release.

## 7. Known limits and acceptance recommendation

- **Proposed host acceptance: PASS with explicit BlocksDS rebuild environment caveat.** Actual second ROM frames/audio, native SRAM restore and failure paths have direct host proofs. The pinned upstream renderer defect itself remains present; Plump's safe lifecycle is what prevents triggering it.
- **Android post-repair validation: NOT STARTED / BLOCKED pending mastermind approval.** Host JNI shims cannot prove real Android `ANativeWindow` lifecycle, speaker output, OEM process control, or live native crash absence on the iQOO.
- **LFC-02 process death / LFC-03 full Activity recreation / LFC-04/05:** not accepted by this repair. Other historical Android native crashes were not triaged, and this fix does not claim to address them.
- **Product capability:** no DS save-state loading, virtual homebrew-SD persistence, DSi/3DS, unrelated feature or app polish was enabled. No native save or session receipt format changed.

**Stop here.** The mastermind must review source, host evidence, environmental limitation and frozen v8 identity before approving any further device action or accepting P4-B's device gate.
