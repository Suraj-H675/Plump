# P4-D — Branch-isolated historical checkpoint restoration: technical report

**Date:** 2026-11-16
**Baseline:** `main` at `00fa62ae398524e132424849492d2b32cbc789d8` (P4-C accepted at host level).
**Verdict:** **D1 real-core host isolation PASS for GBA and DS; D2 durable branch storage and D3 narrow internal restoration implemented and host-tested; full P4-D remains PARTIAL** pending durable active-branch selection/recovery, on-device lifecycle proof, and D4 promotion. No APK installed, no phone access, no push, no general state-load controls, no core revision changes.

## Save ownership, audited boundaries

`N_latest` is the newer canonical PLSV v1 save (two atomic generation slots and `.history` receipt); `N_state` is the immutable native half of `S_state` from a verified P4-C machine checkpoint; `N_branch` is the writable save history of an independently restored timeline. No historical save may overwrite `N_latest`. Source audit covered `CheckpointStore`, `GameSaveStore`, owner, GBA/DS Activities, and pinned Libretro JNI. P4-C checkpoint manifest already pins ROM, console, core/front-end revisions, binary SHA-256, ABI, serializer API and blob sizes/hashes. PLSV supports independent directory roots without format change. DS core chooses some writable system/core paths at init: branch restore must reinitialize that engine with isolated paths, not just redirect a Java journal.

The raw DS EEPROM rollback hazard remains reproducible: restoring an old state directly turns later EEPROM marker `504c4d502d44533301feac5301020304` into `00000000000000000000000000000000`. Evidence `app/build/p4-d-review/ds-hazard-baseline.log`, plus the unchanged `STATE SAFETY` regression.

## D1 — Independent genuine core / journal isolation: PASS

`bash scripts/test-p4-d-real-branch-isolation.sh` **PASS**. The harness exercises pinned real Linux mGBA/melonDS cores, real game frames and emulated buttons, actual SRAM/EEPROM writes and actual Java `GameSaveStore` PLSV journals. For each console it executes **four separate subprocesses**: first/newer canonical, restored older historical branch with four branch-native saves, cold canonical restart, cold historical restart. All three canonical files (two `.sav` and `.history`) are hashed before branch execution and compared byte-for-byte after both cold restarts. GBA uses a tiny original ARM7TDMI `KEYINPUT`/SRAM counter cartridge generated under ignored build storage, with the pinned SHA-256-verified MIT gba-tests cartridge header; the previous licensed SRAM fixture only writes once and could not distinguish histories. DS uses the pinned CC0 synthetic EEPROM cartridge. No test ROM or save enters Git.

| Verified real-core fact | mGBA GBA | melonDS DS |
|---|---|---|
| Linux host core SHA-256 | `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c` | `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` |
| Fixture ROM SHA-256 | `c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5` | `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70` |
| Old `S_state` SHA-256 | `287a5c2521b4568bf0204ed4cf54b94cebdc71fc6239097bcb4d5c4b8a492be1` | `0d622a04c035396486319f306f7713eaaf4cb35d948a482a3ec6b135ae35d238` |
| Old `N_state` SHA-256 | `2d864c0b789a43214eee8524d3182075125e5ca2cd527f3582ec87ffd94076bc` | `9f1dcbc35c350d6027f98be0f5c8b43b42ca52b7604459c0c42be3aa88913d47` |
| Newer canonical `N_latest` SHA-256 | `aeffef888d2bfeeb99077c67a2a2a7a3964e075628827434a46c0925ede5d52e` | `4cd149f96fd40ac5c9c314f4222d3ad3ab6e3036f69b48517292b46f5b41d8ad` |
| Continued branch `N_branch` SHA-256 | `2f7bedc09fd70db7bb9871e4899a9d1cbdfc9fcfd46dd825913005a61719fe97` | `ae6b8f25600d613d32cbb0d97fda8ca4898ef043f20551e85ab06878276f040b` |
| Canonical cold in-game counter | 1, then real A press advances to 2 | 1 |
| Branch cold in-game counter | 2, then real A press advances to 3 | 2 |
| Branch journal writes / canonical full-file changes | **4 / zero** | **4 / zero** |

**These are distinct resumed real-game histories**, not mocked state bytes. Evidence `app/build/p4-d-review/tracked-d1-real-core.log` and individual ignored stage logs. This establishes D1 feasibility for the tested cartridge-save paths, not arbitrary game compatibility or Android runtime acceptance.

### Full canonical PLSV file SHA-256 — original equals final

The test compares the complete file bytes, including checksum envelopes and history receipt, not just decoded SRAM/EEPROM payloads.

| Core | `.0.sav` SHA-256 | `.1.sav` SHA-256 | `.history` SHA-256 |
|---|---|---|---|
| GBA | `97a38a6d7c143eb610557102381bcd1fb2e97b3547397c0a077b8db0a09116d4` | `ebeeef4be54377cc8a518d26bcf31d5c8fd15447cfdc02d4475374356972c14d` | `2b58c04dc4defd717e82e4c00aa10bbe5bf326eb0860e7bf2440355203e3157b` |
| DS | `d796c5c52a31f8c93cb4db6faa83410d0d714211047e3d28e29a3c72702ff84e` | `ccfc83b662486c23ee252cad84d85a62efc6fe38c71cb1ac76d1889dc72fa6af` | `1e3354b8c34f1badd5c44a0919d1ad7b498cca6042843f0af5f25adcbc591a43` |

## D2 — Branch transaction implementation and failure evidence

`HistoricalBranchStore` publishes branches under an independent app-private `historical-branches/` root, never under the canonical PLSV directory. It obtains a strict `CheckpointStore.readPair`, which verifies the source manifest/pair and rereads the bounded machine/native bytes with SHA-256 and no-follow regular-file checks. It checks available capacity and creates a unique `.pending-UUID`, writes+fsyncs+independently readback-verifies `machine.bin`, `native.bin` and `manifest.hbr`, and seeds a separate PLSV v1 `journal/` from `N_state`. DS-specific `system/` and `core-data/` directories are separate. After syncing child dirs and staging it atomically renames to `branch-UUID` and syncs its parent. Post-publication failure can quarantine the branch by atomic rename. The manifest pins ROM, console, source checkpoint, core/front-end revision, binary SHA, ABI/API and lengths/checksums. It uses SHA-256 for accidental corruption, not keyed adversarial authentication.

The `open(id, identity)` path verifies UUID, no-follow directories/files (including journal generation symlinks), manifest, machine/native sizes and hashes, exact core/game identity, recoverable journal and native save geometry. Future **valid branch journal writes** do not rewrite the immutable `S_state + N_state` and cold reopen can recover progressed branch SRAM/EEPROM; a mismatched future journal native-save size fails closed. Unknown file/directory content is not deleted by retention/cleanup.

`bash scripts/test-p4-d-branch-publication.sh` executed **five real separate JVM hard kills** (`Runtime.halt(87)`), followed by independent process restart/verification. All canonical PLSV generations/receipt remained byte-identical, the original source checkpoint remained verifiable, and no half-branch was published:

| JVM death boundary | Verified published branches | Canonical and source checkpoint |
|---|---:|---|
| `AFTER_MACHINE` | 0 | Intact and unchanged |
| `AFTER_JOURNAL` | 0 | Intact and unchanged |
| `BEFORE_PUBLISH` | 0 | Intact and unchanged |
| `AFTER_PUBLISH` | 1 complete | Intact and unchanged |
| `AFTER_READBACK` | 1 complete | Intact and unchanged |

Log: ignored `app/build/p4-d-review/process-hard-kill-publication.log`; **PASS 5/5**. Additional JVM `IOException` fault injections cover stage/pub boundaries, simulated zero free space, corruption, ROM/core mismatch, path traversal, symlinked journal/branch directories, incompatible native journal length and cold opening a progressed branch. **Actual physical disk exhaustion and Android power-loss were NOT tested.**

**D2 remaining blocker:** there is **no durable active branch/canonical pointer** independently reconciled at process restart, no automatic branch cold reentry or validated branch selection/recovery workflow, and no complete branch retention policy. Existing canonical cold-load behavior is preserved rather than auto-selecting historical state. No older save is promoted or merged.

## D3 — Internal exclusive restore, native-save routing, JNI

`EmulatorSessionOwner.restoreHistoricalCheckpoint` requires an exclusively owned **paused canonical** session. It stops/quiesces workers, durably flushes canonical progress and publishes its paused receipt, verifies the checkpoint identity and **publishes the independent branch before changing emulator memory**. The Activity callback binds `activeSaveStore` to the branch PLSV journal *before* invoking any restoration, retires and freshly initializes the core, and loads only the branch pair. Both Activities route later periodic, pause, switching and shutdown save writes through that active journal. The DS core is reinitialized with branch-only `system/` and `core-data/`. Canonical-tagged auto/manual checkpoint creation is prohibited on active branches.

The new mGBA and DS JNI `restoreCheckpoint(machine,native)` enforce bounded **exact** serialized and native-save sizes, Libretro API v1 and capabilities under each core mutex. `retro_unserialize` may rewind the native-save buffer: code reacquires the current SRAM/EEPROM pointer *after* machine restoration, installs the selected `N_state` and verifies native readback. On any failed/partial restore the possibly contaminated core is retired **without saving**, a fresh cold canonical PLSV recovery/readback is attempted, and the failed branch is quarantined; if canonical fallback also fails the session is retired/marked for recovery, not resumed. There is no general state-loading UI.

**GBA real-core size hazard and fix:** immediately after `retro_load_game`, mGBA advertises provisional 128 KiB SRAM and a 528,448-byte state; after one isolated no-button frame it settles to 32 KiB SRAM and 430,144-byte checkpoint state. D3 primes **only the isolated branch core** with one frame before `retro_unserialize`. The genuine JNI regression verifies that premature restore rejects and primed restore succeeds with matching SRAM, then advances five real frames. Neither engine revision nor P4-C serialization format was modified.

Real pinned JNI/owner/store host test `bash scripts/test-p4-c-checkpoints.sh` **PASS** after extending mGBA and melonDS host harnesses with genuine `restoreCheckpoint`, both native/machine byte-readback and rejection of wrong machine/native lengths; all existing GBA keys/audio, DS stylus/audio, original EEPROM rollback, unsupported/mutating/oversized capture and cold native-save regressions also pass. Log `app/build/p4-d-review/p4c-full-fresh-jni.log`. JVM owner/storage tests validate paused exclusivity, suppression of canonical-tagged checkpoints on a branch, bad recovery, atomic publication and identity checks.

**D3 remains host-only and partial:** there is no verified real Android lifecycle/process-death historical restore, no process-resumable active branch pointer and chooser, no user-facing load controls, no real Android branch fsync/performance/power-loss proof. **D4 explicit branch promotion transaction is NOT IMPLEMENTED.** No implicit canonical replacement can occur via a promotion API.

## Full independent verification

| Command / evidence | Actual result |
|---|---|
| `bash scripts/test-p4-d-real-branch-isolation.sh` | **PASS** both pinned real cores, genuine GBA/DS input and save progress, four subprocesses/core, four branch flushes/core, two canonical generations+receipt byte-identical, both cold branches valid |
| `bash scripts/test-p4-d-branch-publication.sh` | **PASS 5/5** fresh-process hard JVM exits at publication boundaries; verified source checkpoint+canonical journal intact |
| `bash scripts/test-p4-c-checkpoints.sh` | **PASS** real JNI mGBA/DS capture, new historical restore, invalid sizes, GBA fresh load geometry, DS raw rollback, native first/cold saves, input, video, audio, owner/store integration |
| `./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` (Android SDK 35, NDK 28.2; project-local `TMPDIR`) | **PASS, 88/88 JVM tests** (12 suites; 0 fail/error/skip), ARM64 debug and unsigned release assembled, lint **0 errors, 23 warnings**; ignored `app/build/p4-d-review/gradle-post-review-fixes.log` |
| `python3 scripts/test-p4-c-packaged-core-identity.py --negative-test` | **PASS** production Java validates actual APK-extracted/Gradle-stripped debug+release cores, modified/unstripped binary rejects; ignored `app/build/p4-d-review/final-apk-pins.log` |

**Final observed packaged core SHA-256 (both variants):** mGBA `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6`; melonDS `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30`. Whole local APK SHA-256, **not an installation authorization**: debug `da4c9725333f09564746f0a705b30fd604c03a70b76366940fe62ea30bbe9922`, unsigned release `8330d0133df39569214101c59a7d528b61592bce1a2c1f0a45925614d4757488`. APKs vary across rebuilds; a future candidate must be individually frozen/verified before installation. No APK installed. The source-pinned core revisions and binary inputs were left unchanged.

Initial source compilation errors (unretained GBA SurfaceView, C++ `const` reassignment), an incorrect first branch manifest hash, and direct `javac` host scripts lacking the new class dependency were discovered by independent verification and fixed before the above passing runs. The worker's restricted Gradle socket error did not constitute an app build failure; the independent canonical shell built successfully.

## Exact implementation, tests and boundaries

- **Immutable checkpoint read:** `app/src/main/java/dev/plump/emulator/CheckpointStore.java` adds `readPair`, verified no-follow readback; no manifest/schema changes.
- **Separate branch owner:** new `app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java`; immutable pair + checked manifest, private PLSV journals, isolated DS core/system paths, atomic staging/publication, quarantine and strict open validation.
- **Exclusive internal restore:** `app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java`, `MainActivity.java`, `DsActivity.java` add paused-only transition, branch-first save routing, fresh core restore/fail-closed fallback, no public load UI.
- **Native adapters:** `app/src/main/java/dev/plump/emulator/{NativeCore,NativeDs}.java` and `app/src/main/cpp/{plump_host,nds_host}.cpp` add exact-size guarded machine/native `retro_unserialize` and readback, retaining pinned core revisions.
- **Java regressions:** new `app/src/test/java/dev/plump/emulator/HistoricalBranchStoreTest.java` and extended `EmulatorSessionOwnerTest.java`.
- **Real pinned JNI regressions:** extended `tests/native/mgba_checkpoint_test.cpp`, `tests/native/nds_host_lifecycle_test.cpp`; original P4-C entry scripts `scripts/test-p4-c-checkpoints.sh`, `scripts/test-p4-br-ds-reload.sh` now compile all required Java source types.
- **Reproducible independent D1:** `scripts/test-p4-d-real-branch-isolation.sh`, `tests/host/JournalCli.java`, `tests/roms/{p4-d-gba-sram-counter.s,verify-p4-d-gba-isolation.py,verify-p4-d-ds-isolation.py}`.
- **Real publication interruptions:** `scripts/test-p4-d-branch-publication.sh`, `tests/host/BranchPublicationProbe.java`.
- **Only documentation added:** `docs/P4_D_TECHNICAL_REPORT.md`. All compiled cores, ROMs, native saves, APKs, logs and state blobs remain in Git-ignored `app/build/`.

No PLSV native-save on-disk format, historical P4-C checkpoint format, session receipt format, P4-BR DS core reload repair, protected source revision, or owner-private file was altered. The user's unrelated `docs/P3_TECHNICAL_REPORT.md` modification and untracked `docs/P4_ENGINEERING_PLAN.md` are expressly **not** P4-D files to stage.

## Mastermind gate and acceptance recommendation

**D1: ACCEPT CANDIDATE** — independently proven real mGBA/DS historical continuation with repeated branch native writes, separate-process cold recovery and byte-identical newer canonical journals.

**D2: PARTIAL HOST IMPLEMENTATION** — source/branch pairing, exact identity, atomic private branch journals, interrupted publication and corruption fail-closed testing pass; no durable active branch pointer/selector, full retention or real disk-full/power-failure tests.

**D3: PARTIAL HOST IMPLEMENTATION** — internal paused-owner code, separate save paths, genuine mGBA/DS JNI restore and rejection tests compile and pass. Android-device gameplay, lifecycle, hard-kill mid-restore, and process-restored branch selection are unverified; do not expose publicly.

**D4: NOT IMPLEMENTED** — no implicit promotion; explicit confirmed promotion, durable rollback copy, and idempotent crash-recovery gates require separate scope and proof.

**P4-D overall: PARTIAL — not accepted as a complete restoration milestone.** The exact primary invariant was demonstrated for two host cores, but production history-selection/recovery is unfinished. Submit this evidence for mastermind review and stop; no P4-E/F, phone install, ADB, remote push, or personal save interaction.

Git baseline before commit: `00fa62ae398524e132424849492d2b32cbc789d8` on `main`; the final local repair commit/HEAD and preserved unrelated tree status must be reported separately after staging audit (a commit cannot contain its own hash).
