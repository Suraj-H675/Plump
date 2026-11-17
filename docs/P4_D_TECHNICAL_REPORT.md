# P4-D — Branch-isolated historical checkpoint restoration: technical report

**Date:** 2026-11-16
**Baseline:** `main` at `00fa62ae398524e132424849492d2b32cbc789d8` (P4-C accepted at host level).
**Verdict:** **D1 real-core host isolation PASS for GBA and DS; D2 durable branch selection/recovery and D3 narrow internal restoration have host closeout evidence; full P4-D remains PARTIAL** because Android-device lifecycle proof and D4 promotion are outside this closeout. The addendum at the end supersedes the earlier D2 pointer blocker and earlier verification counts. No APK installed, no phone access, no push, no general state-load controls, no core revision changes.

**Later host closeout:** The final 2026-11-17 D4 addendum at the end supersedes the historical statement above that D4 was unimplemented. D4 now has host engineering evidence; Android-device acceptance and the mastermind D4 verdict remain outstanding.

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

**D2 remaining blocker at the time of the initial report:** the active branch/canonical pointer and process-restart reconciliation were not yet implemented. See the closeout addendum for the final host implementation and recovery evidence. A complete branch retention policy remains out of scope; no older save is promoted or merged.

## D3 — Internal exclusive restore, native-save routing, JNI

`EmulatorSessionOwner.restoreHistoricalCheckpoint` requires an exclusively owned **paused canonical** session. It stops/quiesces workers, durably flushes canonical progress and publishes its paused receipt, verifies the checkpoint identity and **publishes the independent branch before changing emulator memory**. The Activity callback binds `activeSaveStore` to the branch PLSV journal *before* invoking any restoration, retires and freshly initializes the core, and loads only the branch pair. Both Activities route later periodic, pause, switching and shutdown save writes through that active journal. The DS core is reinitialized with branch-only `system/` and `core-data/`. Canonical-tagged auto/manual checkpoint creation is prohibited on active branches.

The new mGBA and DS JNI `restoreCheckpoint(machine,native)` enforce bounded **exact** serialized and native-save sizes, Libretro API v1 and capabilities under each core mutex. `retro_unserialize` may rewind the native-save buffer: code reacquires the current SRAM/EEPROM pointer *after* machine restoration, installs the selected `N_state` and verifies native readback. In the original D3 experiment a canonical cold fallback was attempted after failure; the D2/D3 closeout **removed that unsafe automatic fallback**. The final implementation retires a possibly contaminated core without saving, marks ownership uncertain, and never automatically runs or flushes canonical on failed historical restoration. There is no general state-loading UI.

**GBA real-core size hazard and fix:** immediately after `retro_load_game`, mGBA advertises provisional 128 KiB SRAM and a 528,448-byte state; after one isolated no-button frame it settles to 32 KiB SRAM and 430,144-byte checkpoint state. D3 primes **only the isolated branch core** with one frame before `retro_unserialize`. The genuine JNI regression verifies that premature restore rejects and primed restore succeeds with matching SRAM, then advances five real frames. Neither engine revision nor P4-C serialization format was modified.

Real pinned JNI/owner/store host test `bash scripts/test-p4-c-checkpoints.sh` **PASS** after extending mGBA and melonDS host harnesses with genuine `restoreCheckpoint`, both native/machine byte-readback and rejection of wrong machine/native lengths; all existing GBA keys/audio, DS stylus/audio, original EEPROM rollback, unsupported/mutating/oversized capture and cold native-save regressions also pass. Log `app/build/p4-d-review/p4c-full-fresh-jni.log`. JVM owner/storage tests validate paused exclusivity, suppression of canonical-tagged checkpoints on a branch, bad recovery, atomic publication and identity checks.

**D3 remains host-only:** process-resumable branch ownership was added and tested in the later closeout addendum. Android-device lifecycle, physical process-death during historical restore, real Android branch fsync/performance/power-loss proof, and public recovery controls remain unverified or unimplemented. **D4 explicit branch promotion transaction is NOT IMPLEMENTED.** No implicit canonical replacement can occur via a promotion API.

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
- **Exclusive internal restore:** `app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java`, `MainActivity.java`, `DsActivity.java` add paused-only transition, branch-first save routing, fresh core restore/fail-closed retirement, no public load UI.
- **Native adapters:** `app/src/main/java/dev/plump/emulator/{NativeCore,NativeDs}.java` and `app/src/main/cpp/{plump_host,nds_host}.cpp` add exact-size guarded machine/native `retro_unserialize` and readback, retaining pinned core revisions.
- **Java regressions:** new `app/src/test/java/dev/plump/emulator/HistoricalBranchStoreTest.java` and extended `EmulatorSessionOwnerTest.java`.
- **Real pinned JNI regressions:** extended `tests/native/mgba_checkpoint_test.cpp`, `tests/native/nds_host_lifecycle_test.cpp`; original P4-C entry scripts `scripts/test-p4-c-checkpoints.sh`, `scripts/test-p4-br-ds-reload.sh` now compile all required Java source types.
- **Reproducible independent D1:** `scripts/test-p4-d-real-branch-isolation.sh`, `tests/host/JournalCli.java`, `tests/roms/{p4-d-gba-sram-counter.s,verify-p4-d-gba-isolation.py,verify-p4-d-ds-isolation.py}`.
- **Real publication interruptions:** `scripts/test-p4-d-branch-publication.sh`, `tests/host/BranchPublicationProbe.java`.
- **Only documentation added:** `docs/P4_D_TECHNICAL_REPORT.md`. All compiled cores, ROMs, native saves, APKs, logs and state blobs remain in Git-ignored `app/build/`.

No PLSV native-save on-disk format, historical P4-C checkpoint format, session receipt format, P4-BR DS core reload repair, protected source revision, or owner-private file was altered. The user's unrelated `docs/P3_TECHNICAL_REPORT.md` modification and untracked `docs/P4_ENGINEERING_PLAN.md` are expressly **not** P4-D files to stage.

## Mastermind gate and acceptance recommendation

**D1: ACCEPTED by mastermind** — independently proven real mGBA/DS historical continuation with repeated branch native writes, separate-process cold recovery and byte-identical newer canonical journals.

**D2: PARTIAL IN ORIGINAL HOST IMPLEMENTATION** — source/branch pairing, exact identity, atomic private branch journals, interrupted publication and corruption fail-closed testing passed. The following closeout addendum addresses the former missing active selector; full retention and real disk-full/power-failure tests remain outside host evidence.

**D3: PARTIAL IN ORIGINAL HOST IMPLEMENTATION** — internal paused-owner code, separate save paths, genuine mGBA/DS JNI restore and rejection tests passed. Process-restored branch selection is now host-verified in the closeout addendum; on-device gameplay, lifecycle and actual Android process-death restore remain unverified. Do not expose publicly.

**D4: NOT IMPLEMENTED** — no implicit promotion; explicit confirmed promotion, durable rollback copy, and idempotent crash-recovery gates require separate scope and proof.

**P4-D overall: PARTIAL — not accepted as a complete restoration milestone.** The exact primary invariant and the later host-only selection/recovery closeout have evidence, but Android-device lifecycle and D4 promotion remain unverified or unimplemented. Submit for mastermind review and stop; no P4-E/F, phone install, ADB, remote push, or personal save interaction.

## D2/D3 host closeout addendum — 2026-11-16

This addendum supersedes the earlier D2 pointer blocker and the earlier test count. The closeout began on canonical `main` at `fe01a72654df6dcd5c4611013f5d7caf5a9f9dcd`; no branch/worktree, remote push, device access or personal save access was used. The coherent local commit is recorded separately after the final staged audit. D1 remains independently accepted. D4 remains unimplemented.

### Durable active timeline selector

New `ActiveTimelineStore` keeps one selector per console+ROM in `active-timelines/`, separate from canonical and branch PLSV stores. It maintains two checksummed generations; each publication writes and fsyncs a same-directory temporary file, atomically renames it, fsyncs the directory, and validates the bytes by readback. A record pins ROM/core/frontend revisions, core binary SHA-256, ABI and serializer API. It identifies `CANONICAL` or a branch UUID and immutable source checkpoint UUID, and records the actual latest native PLSV generation and SHA-256. Coherent checkpoint fields are present only while the latest branch native generation still equals the immutable source `N_state`.

Branch selection publishes `PREPARED` before core reinitialization and commits `COMMITTED` only after the internal restore callback succeeds and the target PLSV generation/hash is revalidated. Interrupted selection remains `PREPARED`/`AMBIGUOUS` and is never treated as canonical. After a native PLSV write, Activities verify that the current selector owns the destination, publish the durable PLSV generation/hash to the selector, and verify readback. A failed pointer update blocks subsequent saves and loads that selected target as invalid/ambiguous; it cannot route a later branch flush into canonical storage.

For pre-selector v1 games, a valid canonical PLSV can initialize the canonical selector only if branch scanning proves there is no branch for that ROM. A valid same-ROM branch with no selector, an unreadable branch scan, an unreadable/corrupt selector, an unsafe/symlinked path, a wrong ROM/core/ABI identity, or a selector-to-PLSV generation/hash mismatch requires an explicit internal recovery choice. Valid unrelated-ROM branches do not block legacy initialization. Normal selection refuses corrupt-present selector metadata. Explicit `recoverCanonical`/`recoverBranch` first preserves selector bytes intact under `recovery-evidence/`; receipts do not select a timeline. Missing ROM availability leaves selection untouched and requires reselection. No automatic orphan cleanup or GC was added.

An intact branch at its original native generation can be classified `POTENTIAL_EXACT_STATE`, but cold opening in either Activity always reinitializes the core and loads only the selector-verified latest native PLSV. It never auto-calls machine `retro_unserialize`; this prevents combining original `S_state` with a progressed `N_branch` and avoids a repeated cold-restore crash loop. Once `N_branch` advances, it is classified `BRANCH_NATIVE_ONLY`, and reselection retains the immutable original pair without claiming that it is coherent with the newer journal. Exact state+native restoration remains limited to the initial explicit in-session internal restore, whose new branch starts with the verified pair. This matches the current P4-C rule that branch checkpoint capture is forbidden.

### Fresh-JVM crash interruption matrix

`bash scripts/test-p4-d-branch-publication.sh` passed the existing 5/5 separate-JVM branch publication hard exits and the new 10/10 active-selector cases. Every crash process exited with `Runtime.halt(87)`; an independent JVM checked recovery classification, all three canonical PLSV files, and the immutable source checkpoint.

| Hard-kill boundary | Fresh-JVM result | Evidence preserved |
|---|---|---|
| Before branch selection | `CANONICAL` | Canonical PLSV and source checkpoint |
| After branch publication | `CANONICAL` | Canonical PLSV and complete orphan branch |
| After selector `PREPARED` | `AMBIGUOUS` | Both histories and source checkpoint |
| Before core init | `AMBIGUOUS` | Both histories and source checkpoint |
| After core init | `AMBIGUOUS` | Both histories and source checkpoint |
| After native restore | `AMBIGUOUS` | Both histories and source checkpoint |
| After machine restore | `AMBIGUOUS` | Both histories and source checkpoint |
| Before resumable `COMMITTED` | `AMBIGUOUS` | Both histories and source checkpoint |
| After resumable `COMMITTED` | `POTENTIAL_EXACT_STATE`; cold path remains native-only | Both histories and source checkpoint |
| During next branch journal temporary-record write | `POTENTIAL_EXACT_STATE`; incomplete newer PLSV generation ignored | Prior complete branch generation, canonical PLSV and source checkpoint |

The selector probe's `AFTER_CORE_INIT`, `AFTER_NATIVE_RESTORE`, and `AFTER_MACHINE_RESTORE` names are host transaction barriers: that probe independently reopens and verifies branch storage/pair data at those points, but it does **not** execute or kill inside a native core call. Genuine JNI machine/native restoration is covered separately by the P4-C host regression. Real native-core process restart and continued writes are covered by the D1 harness below. No Android process-death or physical power-loss test is claimed. Evidence: ignored `app/build/p4-d-closeout/timeline-hardkill-final.log`.

### Independent actual pinned-core restoration process deaths — 8/8 PASS

Beyond the ten **transaction-boundary** kills, independent review added a reproducible genuine Libretro-core process-death harness: `scripts/test-p4-d-real-restore-deaths.sh`, `tests/host/RealCoreDeathProbe.java` and `tests/roms/verify-p4-d-real-restore-death.py`. Supply `PLUMP_P4_D_D1_SOURCE` pointing to the ignored test directory produced by the accepted D1 script (for this verification: `app/build/p4-d-d1-aNTyFn0z`). Each new disposable scenario seeds the canonical two PLSV generations and history receipt from the **genuine newer native save**, publishes an immutable real-core `S_state + N_state`, and leaves the selector durably `PREPARED`. A fresh Python process loads the **actual pinned mGBA or melonDS Libretro engine**, reaches the specified boundary and calls `os._exit(87)`; a fresh JVM independently verifies `AMBIGUOUS`, both source blobs, branch journal, and SHA-256 of **all three canonical PLSV files**.

| Actual pinned core process killed at | GBA mGBA | DS melonDS | Independent cold classification |
|---|---|---|---|
| After genuine core initialization / ROM load | PASS | PASS | `AMBIGUOUS` |
| After genuine native SRAM/EEPROM restoration | PASS | PASS | `AMBIGUOUS` |
| Immediately after actual `retro_unserialize` (before selected native recopy) | PASS | PASS | `AMBIGUOUS` |
| After reacquiring and verifying selected native bytes | PASS | PASS | `AMBIGUOUS` |

**Result: 8/8 real pinned-core abrupt terminations PASS.** Each scenario independently preserved newer canonical three-file content, valid original checkpoint and branch journal; no contaminated in-memory history was flushed to canonical. Ignored evidence: `app/build/p4-d-closeout/actual-core-deaths-final.log` and stage logs under `app/build/p4-d-actual-core-deaths-DYmKToGx/`. These are real **Linux Libretro** core execution boundaries, not the Java transaction-barrier simulations. Neither suite claims actual Android app-process kills or physical battery/power-loss tests.

### Real-core process restart and resumed branch writes

`bash scripts/test-p4-d-real-branch-isolation.sh` passed again. The harness now bridges the real pinned engines to `ActiveTimelineStore`, `HistoricalBranchStore` and `GameSaveStore`: setup, selector resolution and branch journal writes run as separate Java processes; mGBA and melonDS each start a fresh native-core process, consume only latest branch PLSV bytes, advance the original test game's input-driven save counter, and publish/re-resolve the new generation. Neither cold path supplies the original machine state. The complete canonical two-slot PLSV plus receipt checksums remained identical before/after.

| Evidence | mGBA GBA | melonDS DS |
|---|---|---|
| Host core SHA-256 | `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c` | `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` |
| Fixture ROM SHA-256 | `c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5` | `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70` |
| Selected branch generation 2 SHA-256 | `2f7bedc09fd70db7bb9871e4899a9d1cbdfc9fcfd46dd825913005a61719fe97` | `ae6b8f25600d613d32cbb0d97fda8ca4898ef043f20551e85ab06878276f040b` |
| Fresh-JVM recovered classification | `BRANCH_NATIVE_ONLY`, counter 2 | `BRANCH_NATIVE_ONLY`, counter 2 |
| Genuine cold-core input and durable branch write | real `KEY_A`, counter 2→3; generation 3 SHA-256 `27d7a3e59112e54e3f4d18b02be633d41de2e3f2155bc153ae7249091ddb43f0` | real `KEY_A`, counter 2→3; generation 3 SHA-256 `6971dc0db44c79d131648dd9710e91a58994138a854fe3908820c7a5de694460` |
| Canonical full-file SHA-256 before/after | `.0` `97a38a6d7c143eb610557102381bcd1fb2e97b3547397c0a077b8db0a09116d4`; `.1` `ebeeef4be54377cc8a518d26bcf31d5c8fd15447cfdc02d4475374356972c14d`; receipt `2b58c04dc4defd717e82e4c00aa10bbe5bf326eb0860e7bf2440355203e3157b` | `.0` `d796c5c52a31f8c93cb4db6faa83410d0d714211047e3d28e29a3c72702ff84e`; `.1` `ccfc83b662486c23ee252cad84d85a62efc6fe38c71cb1ac76d1889dc72fa6af`; receipt `1e3354b8c34f1badd5c44a0919d1ad7b498cca6042843f0af5f25adcbc591a43` |

Evidence: ignored `app/build/p4-d-d1-VcNkIgU0/{gba,ds}-timeline_{setup,cold}.log` and `app/build/p4-d-closeout/real-core-pointer.log`. The original D1 canonical-vs-branch real-core checks also passed in the same run.

### Final host verification and limits

| Command | Result |
|---|---|
| Direct `javac --release 17` compilation of all app Java main/test sources, then JUnitCore across all 13 suites | **PASS, 98 tests**; `app/build/p4-d-closeout/junit-final.log` |
| `bash scripts/test-p4-c-checkpoints.sh` | **PASS**; genuine pinned mGBA/melonDS JNI, owner, state/EEPROM rollback and existing host regressions; `app/build/p4-d-closeout/p4c-final.log` |
| `bash scripts/test-p4-d-branch-publication.sh` | **PASS**, 5/5 branch-publication plus 10/10 selector hard-kill matrix; `app/build/p4-d-closeout/timeline-hardkill-final.log` |
| `bash scripts/test-p4-d-real-branch-isolation.sh` | **PASS**, original D1 plus fresh-JVM persisted-selector cold recovery and real branch writes on both engines; `app/build/p4-d-closeout/real-core-pointer.log` and per-stage logs above |
| `PLUMP_P4_D_D1_SOURCE=app/build/p4-d-d1-aNTyFn0z bash scripts/test-p4-d-real-restore-deaths.sh` (source path may be absolute) | **PASS 8/8**; actual pinned Linux mGBA+melonDS core process terminations and independent selector/canonical/source verification; `app/build/p4-d-closeout/actual-core-deaths-final.log` |
| `python3 scripts/test-p4-c-packaged-core-identity.py --negative-test` | **PASS**, both existing APK variants contain the pinned packaged cores and altered mGBA binaries are rejected; `app/build/p4-d-closeout/packaged-core-final.log` |
| Final independent `./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` | **PASS** in canonical development shell after final source fixes: **98/98 JVM tests**, 13 suites, zero failures/errors/skips; ARM64 debug and unsigned release assembled, Android lint **0 errors, 23 warnings**. Log: `app/build/p4-d-closeout/gradle-independent-final.log`. The separate Codex worker sandbox **could not run Gradle** due its restricted socket/file-lock permissions; that is not a failure of the independently successful canonical build. |

The final **independent packaged-core negative check** ran after the successful rebuild, `app/build/p4-d-closeout/packaged-core-independent-final.log`. Last observed whole-APK SHA-256: debug `4787823ba0db3a64c934170b87bc38c18700f6b1a0ffbaf2fb92cfaee574e699`; unsigned release `2919a5e95f2752046ea74848fb6557ea94e792ca9508468848fa81da003da03f`. **Whole APK hashes are not stable across arbitrary rebuilds:** any future installation candidate requires separate freezing and verification. The actual packaged ARM64 runtime emulator ELF SHA-256 stayed mGBA `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6`, melonDS `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30`; intentionally altered/unstripped unexpected binaries were rejected. No APK was installed or authorized for installation.

**Closeout status:** D2 active selector/recovery and D3 internal restore integration have concrete host implementation and evidence; the requested closeout boundary remains **host-only**. No public chooser, generic load UI, D4 promotion, Android-device lifecycle validation, exact cold machine resume, PLSV v1 change, P4-C format change, core revision, SD/3DS, or P4-E/F work was added. When a selector is ambiguous or unsafe, the Activities refuse auto-load and require explicit internal recovery; there is intentionally no end-user recovery UI in this scope.

Git **closeout** baseline before commit: `fe01a72654df6dcd5c4611013f5d7caf5a9f9dcd` on `main`. The final local commit SHA and preserved unrelated working-tree status are reported in the handoff after staging audit (a committed report cannot contain its own commit hash).

---

## D4 — Explicit rollback-safe cartridge-native branch promotion (host-only closeout, 2026-11-17)

**Authorization:** mastermind accepted host D1/D2/D3 at `86c4a782f5b4c04deed4a8a4b6fa1e08141f194d` and authorized D4 host engineering only. **D4 status: HOST ACCEPTANCE CANDIDATE; mastermind decision pending.** This section is additive: prior D1–D3 historical findings, accepted regressions and baseline commit descriptions above remain historical evidence, not new D4 claims. No Android install, ADB, device data, core update, public state UI, remote push, DSi/3DS/virtual SD or P4-E/F activity.

### D4 pre-code audit and exact safety boundary

The approved D2/D3 code already separates `N_latest` (canonical), immutable starting `S_state + N_state`, and independently progressed `N_branch`. D4 audited `GameSaveStore`, `ActiveTimelineStore`, `HistoricalBranchStore`, `CheckpointStore`, `EmulatorSessionOwner`, both Activities' periodic/pause/shutdown native-save paths, and the existing PLSV two-generation plus history receipt implementation. The pre-code state-machine and fault-boundary design was written **before the D4 source changes** to ignored `app/build/p4-d4/STAGE1_PROMOTION_SAFETY_DESIGN.md`.

**Atomicity gap:** PLSV v1 individually publishes `.0.sav` or `.1.sav` with an atomic rename and file/directory sync, but the native slots, `.history` receipt, active selector's two metadata slots and the D4 transaction receipt are **not one filesystem transaction**. A new native generation can be visible while the durable selector still says historical branch, or the selector can say canonical before D4 final commit. Neither the newest SRAM/EEPROM bytes nor a generic `RUNNING/PAUSED` receipt proves transaction completion. An older canonical slot may be replaced before a process dies, so the rollback anchor must preserve **both original complete PLSV generation envelopes and the original history receipt**, including size, SHA-256 and explicitly absent slots, not just `N_latest`.

### Internal API and publication state machine

D4 adds `NativeSavePromotionStore` and a package-private, exclusively owner-mediated `proposeNativeSavePromotion` → `approveNativeSavePromotion` → `promoteNativeSaveBranch` path. The proposal is read-only and carries a transaction UUID and an exact confirmation phrase (`PROMOTE <transaction UUID>`); an unmatched phrase does not approve anything. The separate explicit approval is **durably checksum-validated** and pins the transaction, original owner session, console, ROM SHA-256, pinned core/front-end revisions, native ELF hash, ABI, Libretro API, cartridge save mode, branch UUID and source checkpoint UUID, branch native PLSV generation+SHA, original canonical native generation+SHA, and active selector generation/transaction. Approval and execution independently reject either timeline changing after the proposal or **after the approval**. An in-memory `activeBranch` flag is never approval.

`EmulatorSessionOwner` requires a **paused, exclusive and current historical-branch session**, a matching exact ROM/console/core identity, and an owned, short-lived lease while the transition lock is held. A successful promotion retires that still-historical in-memory core/session from any further resume or flush; a later canonical session must perform an independently verified cold open. The owner may not treat a historical core as canonical merely because the disk selector changed. The production Activities supply canonical save-store access to the host owner but **do not expose promotion UI or opt in to any save mode**; their default mode is `UNKNOWN`, so actual Android promotion remains deliberately unavailable pending separate acceptance.

The storage is scoped by console and ROM under `active-timelines/native-save-promotions/<console>-<rom-sha>/<transaction-uuid>/` with a per-game interprocess advisory lock. A single promotion transaction is supported per game in this host slice; another approval is refused while any prior transaction evidence remains, including a completed terminal, rather than implementing an unsafe unproven multi-promotion chain. All transaction records are bounded, fully keyed, SHA-256 protected, fsynced, atomically published, directory-synced and re-read; unexpected or unsafe filesystem paths fail closed. No transaction/anchor/source evidence is automatically garbage-collected.

| Durable stage | Publication and readback | Cold/process-death rule |
|---|---|---|
| Proposal / declined confirmation | No durable approval, no canonical write | Normal selector remains; deliberate exact confirmation required |
| `APPROVED` | Scoped, durable `approval.record` | Treat this prepared transaction as `RECOVERY_REQUIRED`, even before destructive work |
| `ANCHOR_VERIFIED` | Immutable atomically published `anchor/`: exact `record0`, `record1`, `history`, `canonical.native`, checksummed `manifest.record`, explicit absent markers, all fsynced/read back | No canonical modification yet; incomplete staged directories preserved as evidence |
| `INTENT` | Durable `intent.record` binds exact approval/anchor SHA, ROM/core/branch and target | **Canonical write forbidden before this boundary.** Pending intent blocks ordinary native writes, selector changes and cold auto-load |
| Canonical PLSV publication | Existing v1 `GameSaveStore.writeForPromotion`, new expected generation and exact branch-native bytes verified by readback; no machine-state restore | A torn/partially published record remains **uncommitted**; original full 3-file journal is recoverable from immutable anchor |
| Selector transition | Promotion-only selector path validates exact pending durable intent and new canonical generation/hash; uses two-generation `PREPARED` → `COMMITTED` | If interrupted, cold recovery remains `RECOVERY_REQUIRED`, not success inferred from payload equality |
| Final commit | Independent durable `terminal.record` with `COMMITTED`, transaction/anchor/intent hashes and target generation/hash | Only a verified terminal **and** normal consistent canonical PLSV/selector permit canonical cold continuation |
| Explicit rollback | Durable `rollback.record` published before replacement, verified anchor native bytes written as a **new** PLSV generation, canonical selector verified, final `ROLLED_BACK` terminal/rollback-terminal read back | Retain both original historical branch and complete anchor; interrupted rollback requires explicit recovery; never silently overwrite later canonical gameplay |

`ActiveTimelineStore.resolveCold`, `assertWritable`, `recordDurableSave`, canonical/branch selection and selector recovery inspect the durable promotion guard. A corrupt, missing-part, conflicting or nonterminal promotion is `RECOVERY_REQUIRED`/`CORRUPT`, not permission to write. Only the internal D4 selector path may pass the guard, and only for the exact live intent/target. Existing PLSV **v1 bytes, reader/writer envelope and legacy save paths** remain compatible; the P4-C immutable checkpoint format and pinned core revisions are unchanged. D4 promotes **only the latest verified cartridge-native PLSV bytes from `N_branch`**; it never replays or merges original `S_state`, RTC, NAND, DLDI image, virtual SD or filesystem histories.

**Supported modes in this host slice:** precisely the pinned 32 KiB mGBA GBA cartridge SRAM and 8 KiB melonDS DS cartridge EEPROM fixtures. Unknown, zero, malformed, unsupported-length, cross-console/core, DSi/3DS and filesystem/virtual-SD modes fail closed. This is deliberately a narrow host capability, not a claim that all cartridge types/RTC scenarios are ready for promotion.

### Genuine pinned-core promotion and cold rollback evidence

`PLUMP_P4_D_D1_SOURCE=app/build/p4-d-d1-aNTyFn0z bash scripts/test-p4-d4-promotion.sh` — **PASS**. The script compiles production Java stores/owner/selector, uses only the already accepted **licensed disposable** D1 fixture under ignored `app/build`, and invokes separate JVMs for seed, explicit promotion, selector recovery, canonical writes and rollback. Real Linux pinned Libretro mGBA and melonDS instances consume saved native bytes **before their first frame**, not a stale machine state; a real emulated `KEY_A` advances counter `2 → 3`. All **14 frozen original D1 test state/native/journal input files** retained exactly their pre-test SHA-256 values; see ignored `app/build/p4-d4/accepted-d1-fixture-baseline.json` and independent hash comparison. A separate isolated transaction proves immediate explicit rollback from counter 2 to old counter 1, without overwriting the independent successful counter-3 continuation scenario.

| Verified quantity | mGBA GBA SRAM | melonDS DS EEPROM |
|---|---|---|
| Pinned host core SHA-256 | `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c` | `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` |
| Licensed disposable ROM SHA-256 | `c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5` | `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70` |
| Original canonical `N_latest`, counter **1** | 32,768 B; `aeffef888d2bfeeb99077c67a2a2a7a3964e075628827434a46c0925ede5d52e` | 8,192 B; `4cd149f96fd40ac5c9c314f4222d3ad3ab6e3036f69b48517292b46f5b41d8ad` |
| Original historical `N_branch`, counter **2** | `2f7bedc09fd70db7bb9871e4899a9d1cbdfc9fcfd46dd825913005a61719fe97` | `ae6b8f25600d613d32cbb0d97fda8ca4898ef043f20551e85ab06878276f040b` |
| Explicit successful promoted canonical | PLSV gen **3**, counter **2**, exact branch SHA | PLSV gen **3**, counter **2**, exact branch SHA |
| Real cold start after promotion | Genuine mGBA counter **2** verified | Genuine melonDS counter **2** verified |
| Real next in-game write and canonical cold restart | `KEY_A` counter **3**, gen **4**, SHA `27d7a3e59112e54e3f4d18b02be633d41de2e3f2155bc153ae7249091ddb43f0` | `KEY_A` counter **3**, gen **4**, SHA `6971dc0db44c79d131648dd9710e91a58994138a854fe3908820c7a5de694460` |
| Original canonical anchor, separately cold-run | Original **counter 1**, exact pre-promotion native SHA | Original **counter 1**, exact pre-promotion native SHA |
| Independent branch, separately cold-run | Original **counter 2**, branch journal unchanged | Original **counter 2**, branch journal unchanged |
| Explicit immediate rollback in separate scenario | PLSV gen **4** to native **counter 1**; fresh-JVM and genuine-core cold PASS | PLSV gen **4** to native **counter 1**; fresh-JVM and genuine-core cold PASS |

**Full original 3-file PLSV anchor hashes**, verified byte-for-byte from the independently cold-read immutable anchor (not just its native payload):

| Original canonical v1 file | GBA SHA-256 | DS SHA-256 |
|---|---|---|
| `.0.sav` | `97a38a6d7c143eb610557102381bcd1fb2e97b3547397c0a077b8db0a09116d4` | `d796c5c52a31f8c93cb4db6faa83410d0d714211047e3d28e29a3c72702ff84e` |
| `.1.sav` | `ebeeef4be54377cc8a518d26bcf31d5c8fd15447cfdc02d4475374356972c14d` | `ccfc83b662486c23ee252cad84d85a62efc6fe38c71cb1ac76d1889dc72fa6af` |
| `.history` | `2b58c04dc4defd717e82e4c00aa10bbe5bf326eb0860e7bf2440355203e3157b` | `1e3354b8c34f1badd5c44a0919d1ad7b498cca6042843f0af5f25adcbc591a43` |

Explicit rollback **re-publishes original native bytes as a new generation** to preserve PLSV journal monotonicity; it does **not** destructively substitute the old raw `.0/.1` records into the live canonical directory. The complete original envelopes and receipt remain independently in the anchor. A subsequent legitimate post-promotion canonical counter-3 write makes the old rollback transaction **stale and is refused** rather than destroying the newly saved counter 3; additional rollback capability would need another independently approved anchor/transaction design.

### Separate-process hard-exit and recovery matrix

`tests/host/PromotionD4HardKillProbe.java` creates a fresh disposable transaction per scenario. The process reaches a named production `NativeSavePromotionStore` fault boundary (or **actual PLSV v1** partial-write/record-rename fault), calls `Runtime.halt(87)` and is replaced by an independent JVM. Verification checks: expected terminal/pending classification; active cold gate never automatically loads an unresolved promotion; **source branch journal and immutable `S_state + N_state` unchanged**; before anchor, all original canonical PLSV files still match; after anchor, the complete original `.0`, `.1`, `.history` records and `N_latest` bytes match their independent size and SHA-256 baseline. No ambiguous transaction is marked successful from matching branch bytes alone. All evidence remains in ignored per-run directories.

| Cut family | Boundary cases per console | Expected independent restart classification |
|---|---:|---|
| Before/after immutable rollback anchor publication | 2 | `RECOVERY_REQUIRED`; pre-anchor canonical untouched or immutable anchor fully verified |
| Before/after durable intent publication | 2 | `RECOVERY_REQUIRED` |
| Before, during and after canonical PLSV write, including partial write and slot atomic rename | 5 | `RECOVERY_REQUIRED` |
| Before/after selector transition | 2 | `RECOVERY_REQUIRED` |
| Before/after final commit publication | 2 | Before `RECOVERY_REQUIRED`; after `COMMITTED` |
| Before/during/after explicit rollback native write (including partial and record move) | 4 | `RECOVERY_REQUIRED` |
| Before/after rollback selector transition | 2 | `RECOVERY_REQUIRED` |
| Before/after rollback terminal receipt | 2 | Before `RECOVERY_REQUIRED`; after `ROLLED_BACK` |
| Abrupt death while **explicitly** finishing a pending promotion and explicitly rolling back | 2 | `RECOVERY_REQUIRED`, retains full anchor and original branch |
| **Total** | **23 per console, 46/46 PASS** | **Fresh JVM each case; no silent success** |

Additional JVM tests use injected storage failure including an `ENOSPC`-like partial canonical PLSV write; invalid confirmation; changed branch/canonical after proposal **and after approval**; foreign approval object; unsupported mode; path symlink; corrupt anchor; pending intent blocking cold load, native writer and ordinary selection; committed-operation idempotence; explicit rollback idempotence; refusal to overwrite canonical progress advanced after promotion. The tests are **host fault injection**, not a claim of physically filling an Android partition or surviving a hardware power cut.

**Explicit post-crash recovery execution, not just classification:** Ten further disposable scenarios (five for each pinned console) hard-kill after `BEFORE_ANCHOR`, `AFTER_PLSV_RECORD_MOVE` (promotion), `AFTER_SELECTOR`, `AFTER_PLSV_RECORD_MOVE` (rollback decision), and `ROLLBACK_AFTER_PLSV_RECORD_MOVE` (interrupted rollback). A new JVM separately makes the **explicit** finish/rollback choice, another new JVM repeats it without publishing a duplicate generation, and actual pinned mGBA/melonDS cold-boot the recovered result: `2` after finish, `1` after rollback. **10/10 recovered decisions and pinned-core cold checks passed.** The original source branch and complete original anchor are independently reverified throughout; no recovery action is invoked by normal cold launch. Evidence: `app/build/p4-d4/d4-extended-recovery-final.log` and its referenced ignored per-scenario logs.

### D4 implementation and independent verification

**Production:** `app/src/main/java/dev/plump/emulator/NativeSavePromotionStore.java` (new scoped transaction/anchor, confirmation, lock, crash-status and explicit recovery); `GameSaveStore.java` (strict byte-exact promotion snapshot and narrow injected v1 publication, no PLSV format change); `ActiveTimelineStore.java` (durable promotion cold/write guard and guarded selector commit); `EmulatorSessionOwner.java` (short-lived owner lease, paused-only internal API, retire previously loaded historical core on commit); `MainActivity.java`, `DsActivity.java` (canonical journal dependency only; no UI or enabled Android promotion mode). Existing host compilation scripts add the new class to their explicit Java dependency sets. Test files: `NativeSavePromotionStoreTest.java`, `PromotionD4HostCli.java`, `PromotionD4HardKillProbe.java`, `verify-p4-d4-real-core-promotion.py`, `scripts/test-p4-d4-promotion.sh`.

| Independent command | Observed result | Ignored log |
|---|---|---|
| `PLUMP_P4_D_D1_SOURCE=app/build/p4-d-d1-aNTyFn0z bash scripts/test-p4-d4-promotion.sh` | **PASS:** genuine mGBA+melonDS canonical1→promoted2→real-input canonical3, genuine cold old-anchor/branch reads, separate immediate explicit rollback; **46/46** separate-JVM hard-death tests plus **10/10** explicitly restarted, idempotent recoveries and real-core cold checks | `app/build/p4-d4/d4-extended-recovery-final.log`; ignored isolated stage roots in `app/build/` |
| `./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` (project-local TMPDIR) | **PASS**, **109/109 JVM**, 14 suites, zero failures/errors/skips; debug and unsigned release ARM64 assembled; lint **0 errors, 23 warnings** | `app/build/p4-d4/gradle-gate-final.log`; `app/build/test-results/testDebugUnitTest/` |
| `python3 scripts/test-p4-c-packaged-core-identity.py --negative-test` | **PASS**: actual debug/release APK-extracted/stripped ARM64 mGBA `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6`, melonDS `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30`; modified/unstripped unexpected binaries reject | `app/build/p4-d4/packaged-core-final.log` |
| `bash scripts/test-p4-c-checkpoints.sh` | **PASS**: genuine JNI pinned mGBA/melonDS state/native restore, original DS EEPROM raw rollback hazard, native-save recovery, owner/store | `app/build/p4-d4/regression/p4-c-checkpoints.log` |
| `bash scripts/test-p4-d-branch-publication.sh` | **PASS**: existing 5 branch-publication and 10 durable-selector fresh-JVM kills | `app/build/p4-d4/regression/p4-d-branch-publication.log` |
| `bash scripts/test-p4-d-real-branch-isolation.sh` | **PASS**: original D1 and fresh-process GBA/DS native branch progression; no canonical contamination | `app/build/p4-d4/regression/p4-d-real-branch-isolation.log` |
| `bash scripts/test-p4-br-ds-reload.sh` | **PASS**: retained DS reload/EEPROM integrity | `app/build/p4-d4/regression/p4-br-ds-reload.log` |
| `PLUMP_P4_D_D1_SOURCE=app/build/p4-d-d1-aNTyFn0z bash scripts/test-p4-d-real-restore-deaths.sh` | **PASS 8/8** genuine pinned-core restoration abrupt deaths | `app/build/p4-d4/regression/p4-d-real-restore-deaths.log` |

Last observed **whole** APK SHA-256 of this build (not an installation candidate): debug `536f1a7cec7eb6a7e257c0f8b1df87e5c25674dfccc32861a7ddd3d08a7141a9`; **unsigned** release `39746aedc4b9a148fd835d41b274d0eaa0ade3aaeee018dd300dbf6d95aba225`. Whole-APK hashes change across rebuilds despite stable pinned core ELF. Any future installation requires separately frozen, independently verified APK hashes and explicit phone authorization; **none occurred**.

### Remaining limits, acceptance proposal and Git gate

- **Not Android device-verified:** physical process death, hardware power-loss ordering, real on-device file/fsync timing, full-volume exhaustion, native system/SD behavior, phone lifecycle, and actual gameplay promotion on the owner's phone. Current Android Activities **do not authorize D4 cartridge promotion** by default, and no public UI or consent screen is introduced. All D4 tests are Linux JVM + real pinned **host** Libretro engines on disposable licensed fixtures.
- **Scope deliberately narrow:** only native cartridge SRAM 32 KiB and EEPROM 8 KiB, no implicit branch promotion, no machine-state/RTC merging, no DS homebrew DLDI/virtual SD, no DSi NAND/filesystem or 3DS state, no automatic branch GC, no cross-core migration and no multi-promotion chain for one game until an independently accepted extension. Partial/corrupt transactions preserve evidence and require explicit recovery; there is no new user-facing recovery chooser.
- **Safe rollback semantics:** explicit rollback to the old native payload works before subsequent legitimate canonical progress; it is refused once the canonical journal has advanced beyond the approved transaction, to avoid silently destroying new progress. Source branch and complete pre-promotion anchor remain accessible. Recovery after an interrupted transaction is internal and intentionally not presented as automatic success.
- **Host acceptance recommendation:** **D4 HOST ACCEPT CANDIDATE** on verified 109-JVM, genuine GBA/DS progression, complete original rollback preservation, 46/46 failure cuts and 10 explicit post-kill cold-core recovery executions, with above unsupported/device caveats. **Overall P4-D remains PARTIAL for integrated Android-device acceptance** and awaiting mastermind gate; no P4-E work or phone operations authorized.
- **Git closeout baseline:** `main` at `86c4a782f5b4c04deed4a8a4b6fa1e08141f194d`. Make one coherent local D4 commit after independent staged audit; its exact resulting SHA is provided in the execution handoff, not guessed in this committed report. Preserve unrelated pre-existing modified `docs/P3_TECHNICAL_REPORT.md` and untracked `docs/P4_ENGINEERING_PLAN.md` unmodified and unstaged. No GitHub push or release.
