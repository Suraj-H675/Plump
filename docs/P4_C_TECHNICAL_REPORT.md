# P4-C technical report — host serializer and immutable checkpoint capture

**Result: ACCEPT CANDIDATE for the explicitly host-only P4-C capture slice; P4 overall remains PARTIAL.**
Both pinned cores passed genuine JNI/owner/store checkpoint captures and isolated
cold replay. Independent Android ARM64 APK assembly, 77 JVM tests, and Android
lint passed from the canonical host shell. No APK was installed and no Android
device checkpoint performance or power-loss verification was authorized. This
report does not claim full SAV or P4 acceptance.

**Mastermind P4-C targeted REWORK (2026-11-16): corrected and host-verified.**
The earlier candidate incorrectly pinned the *unstripped mGBA build input* as
the installed binary. Gradle strips mGBA before APK packaging. The production
pin is now the independently measured APK-packaged ELF digest; explicit debug
and release pins are individually checked against rebuilt APKs, without relaxing
runtime hashing. The final rebuild passed **80/80 JVM tests**, both ARM64
assemblies, lint and both real pinned-core host integration suites. Details and
remaining Android-device limitations are recorded below.

## Scope and safety boundary

The change binds the optional Libretro serializer API in the existing mGBA and
melonDS JNI adapters, captures the machine state and cartridge SRAM/EEPROM
under the adapter's core mutex, and stores the pair with a versioned manifest.
`EmulatorSessionOwner` runs automatic captures only after its existing stop,
audio/frame worker joins and native-save flush have succeeded. It keeps the
exclusive console slot and transition lock through capture and publication.
Manual capture is available through the owner only for its loaded, paused,
exclusive session.

The store writes bounded blobs into a unique pending directory, fsyncs each
file, independently reads and hashes the data, fsyncs the pending directory,
then atomically renames the complete directory into its published name and
fsyncs the root. The committed manifest is part of that final atomic
publication. The manifest has no external paths. Its identity includes format,
commit status, generation, manual/automatic kind, console, ROM SHA-256, core ID,
source revisions, binary SHA-256, ABI, Libretro API version, blob lengths and
blob hashes, `branch_id=canonical` (metadata only; NOT a writable P4-D branch),
and the owner's session run token. The manifest has an appended SHA-256 over
all preceding metadata bytes, including manual/auto kind and generation;
verification checks it before using any record for discovery or retention.
This detects torn or unintentionally altered metadata, but is **not** a keyed
authenticity signature against an actor able to rewrite files and hashes.
Verification rejects malformed, incomplete, mismatched, tampered, symlinked or
unsupported records. Checkpoint creation has no restore method.

Manual records are retained without a count limit, subject to storage. Automatic
records retain the three newest verified generations and one oldest anchor per
game/core identity. Retention runs only after publication and verification;
retention sync errors do not turn a committed record into a reported failed
capture. Failed staging is not listed as committed. A complete slot already
atomically renamed remains independently verifiable if a post-rename sync
failure is injected; it is not described as durably confirmed by that failed
call.

Serializer capability is optional: absent symbols leave the existing core
initialization path usable while capture rejects the unsupported capability.
Capture requires all four capability bits and Libretro API v1, an exact
positive advertised size no larger than 64 MiB, and a nonempty native save no
larger than 1 MiB. JNI allocation failures and native `bad_alloc` fail capture.
The adapter compares native cartridge memory before and after serialization;
if serialization changed it, the adapter restores the original bytes and
rejects the capture. PLSV v1, session receipts, renderer, audio, and existing
native-save code/format were not changed.

No production path invokes `retro_unserialize`; there is no state-load API or
state UI. The only new restore check is an isolated child-process test using a
disposable synthetic fixture. The existing raw DS EEPROM rewind regression
continues to run only in its disposable host test directory. No canonical
player save was read or restored.

## Pinned cores and measured host results

The Android identity code verifies the actual file in `nativeLibraryDir`
against the **packaged variant's** pinned ELF SHA-256 before recording a
checkpoint. The former mGBA input SHA was not the installed-file digest, causing
a genuine Android GBA checkpoint to be rejected. Under `useLegacyPackaging true`
the APK native libraries are extracted for installation. Both current debug
and release ELF bytes were independently measured; a future change in either
variant must update its own verified pin after explicit review. Host integration
uses source-built Linux cores from the same pinned revisions; their digests
are unrelated to the Android packaged ELF bytes.

| Console/core | Source revision(s) | Unstripped arm64 input SHA-256 (provenance only) | Installed debug **and verified release** SHA-256 | Tested Linux-host binary SHA-256 | Fixture |
|---|---|---|---|---|---|
| GBA / mGBA Libretro | `d58ea8a86f30598fbf3958585de3d33028535072` | `05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429` | `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6` | `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c` | Licensed MIT SRAM fixture; ROM SHA-256 `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c` |
| DS / melonDS Libretro | core `16f127dbb587a73371827ce79689c5d237c4b59b`; Libretro frontend `f394adbacb5722ee97c1b37c8064da9a25818310` | `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` | `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` | `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` | CC0 synthetic EEPROM fixture; ROM SHA-256 `6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70` |

Each test ran real frames before capture. Five same-boundary serializations
produced identical bytes, serialization left SRAM/EEPROM unchanged, and a
separate disposable child process accepted the state and observed the paired
native save bytes after restore.

| Host core | Frames | Machine state | Native save | Payload total | Size query median | Serialize median | Peak process RSS delta |
|---|---:|---:|---:|---:|---:|---:|---:|
| mGBA | 200 | 430,144 B | 32,768 B | 462,912 B | 0.189 ms | 0.175 ms | 1,765,376 B |
| melonDS DS | 220 | 19,228,045 B | 8,192 B | 19,236,237 B | 8.494 ms | 3.012 ms | 57,651,200 B |

The RSS figures are Linux process measurements from the Python/ctypes harness;
they include Python, core, and test allocations and are not Android-only or
native-only memory measurements. They should not be used as Android heap
forecasts. The exact payload sizes are the stored machine and native files;
after checksum and session metadata, independently observed manifests were
736 B for GBA and 778 B for DS: 463,648 B and 19,237,015 B total
checkpoint-file storage respectively. A live JNI capture temporarily holds the bounded
native serialization buffer and Java byte arrays; Android peak memory and
checkpoint latency remain to be measured on device.

## Verification performed

| Command or harness | Result |
|---|---|
| `bash scripts/test-p4-c-checkpoints.sh` | PASS, exit 0. Real JNI/store/owner integration for both cores; supported, unsupported, mutating and oversized serializer paths; disposable state readback; P2 GBA keys/audio; P3 DS stylus/audio; DS EEPROM first/cold/raw-rewind regression. Full output: ignored `app/build/p4-c-work/final-acceptance.log`. |
| Worker Java/JUnitCore direct compile against Android API 35 / JUnit 4.13.2 | PASS, 74 tests before independent hardening (four existing Window color deprecations). Exact worker commands below. |
| **Independent** `ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK_ROOT=$HOME/Android/Sdk TMPDIR=$PWD/app/build/p4-c-review/tmp ./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` (environment exports applied before invocation) | **PASS**; `BUILD SUCCESSFUL in 14s`; **77 JVM tests, 0 failures, 0 errors, 0 skipped**; ARM64 native CMake build and signed debug APK assembled locally, never installed; Android lint **0 errors, 23 warnings**. Log `app/build/p4-c-review/gradle-post-gc.log`. |
| `cmake -S app/src/main/cpp -B app/build/p4-c-work/android-arm64 -G Ninja -DCMAKE_TOOLCHAIN_FILE=${HOME}/Android/Sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release && cmake --build app/build/p4-c-work/android-arm64 --parallel 4` | PASS for arm64-v8a Android native adapter compilation/link. Final invocation reported no work after the successful build. |
| `bash scripts/test-melonds-ds-host.sh` | PASS, 100 real DS frames with video, input and audio host regression; log at ignored `app/build/p4-c-work/melonds-ds-host-regression.log`. |
| Worker sandbox `GRADLE_USER_HOME=/tmp/plump-gradle-p4c ./gradlew --offline :app:lintDebug` | BLOCKED before Gradle configured due to socket restrictions. **Not a build failure**: the independent canonical DevSpace shell successfully ran all three Gradle tasks above with the installed SDK paths exported. |

The worker's direct `javac` checks are separate from the successful independent
Gradle build. The resulting local debug APK has SHA-256
`b4e27e62709fbdda36e769609fbfc62f5f1c282e9673c1c3963da1bff144588b`;
no APK was installed or run. There was **no phone, ADB, device force-stop or
on-device process-kill test**. Five independently executed **host JVM** hard
process exits during checkpoint publication passed (details below). Actual
Android filesystem fsync behavior and device timing remain unverified.

```sh
javac --release 17 -Xlint:all -cp ${HOME}/Android/Sdk/platforms/android-35/android.jar:${HOME}/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar:${HOME}/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar -d /tmp/p4c-acceptance-final-jvm $(rg --files app/src/main/java app/src/test/java -g '*.java')
java -Xmx1g -cp /tmp/p4c-acceptance-final-jvm:${HOME}/Android/Sdk/platforms/android-35/android.jar:${HOME}/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar:${HOME}/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar org.junit.runner.JUnitCore dev.plump.emulator.ButtonInputStateTest dev.plump.emulator.CheckpointStoreTest dev.plump.emulator.DsButtonGeometryTest dev.plump.emulator.DsFrameTimingTest dev.plump.emulator.DsPortraitSizingTest dev.plump.emulator.DsTouchMapperTest dev.plump.emulator.EmulatorSessionOwnerTest dev.plump.emulator.GameSaveStoreTest dev.plump.emulator.SaveRamSizeValidatorTest dev.plump.emulator.SessionReceiptStoreTest
```

## Failure and acceptance ledger

The JVM store suite injects failures before temporary writes, after a partial
machine/native/manifest file writes (three separate partial-write fault positions),
after the completed temp write, before/after file fsync,
before/after manifest write, before/after pending-directory fsync, before/after
atomic rename, before/after published-root fsync, and before/after manifest
publication. It verifies that the old record remains valid, a partial pending
slot is never enumerated, and any visible new slot is complete and independently
verifiable. Tests also reject altered/truncated blobs, wrong format, ROM/core/
revision/frontend/binary/ABI/API identity, unsupported capability, oversized
buffers, and zero available space. A symlinked slot blob is rejected without
altering the external target. Automatic retention, manual preservation and
per-game independence are tested.

| Acceptance area | P4-C evidence and status |
|---|---|
| Serializer capability and same-boundary native snapshot | PASS on both actual pinned-source host cores and JNI adapters. Unsupported, mutating and oversized serializers fail closed. |
| Immutable capture publication and compatibility metadata | PASS in JVM filesystem tests and real host owner/store integration. |
| Manual and automatic retention | PASS in host tests: six manual records retained; eight autos reduced to the oldest anchor plus three newest. Real owner tests create one auto and two manual records for each core. |
| Existing GBA/DS frame, audio, input, native-save and owner lifecycle regressions | PASS on host/JVM fixtures, including unchanged raw DS EEPROM rewind behavior and positive JNI owner session-token metadata. No Android device proof. |
| SAV-01 historical-state/canonical-save isolation | NOT IMPLEMENTED in this slice; production restore is intentionally absent. |
| SAV-02 manual cold-load, named slots and user deletion | PARTIAL: uncapped storage behavior is tested, but there is no UI, label model, deletion flow or production state-load path. |
| SAV-03 background cadence and durable autos | PARTIAL: safe pause/switch/close triggers and retention are implemented; cadence is event-based, not configurable or measured on Android. |
| SAV-04 crash recovery | PARTIAL overall: stage failpoints and orphan-pending rejection pass; **five real Linux JVM hard process exits** preserve the original verified checkpoint. No physical power-loss, Android process-kill/checkpoint-chooser test. |
| SAV-05 chooser/fallback and crash-loop handling | NOT IMPLEMENTED; outside P4-C. |
| SAV-06 failure semantics | PARTIAL: bounded-space preflight and injected storage errors pass; physical ENOSPC/EIO, unsupported-filesystem rename and Android UI reporting were not exercised. |
| SAV-07 pinned-version mismatch rejection | PARTIAL: revision, binary, ABI, API and version mismatches reject; no core-update/revert workflow was tested. |
| SAV-08–09 backup/export/import | NOT IMPLEMENTED; outside P4-C. |
| SAV-10 independent game state through cold restart | PARTIAL: owner/store identity and repeated host game-switch tests pass; cold Android restart flow is not implemented. |

This is a **host capture and immutable storage slice**, not acceptance of the
larger P4 save/recovery milestone. P4-D historical load/branching remains
unimplemented and unverified. Manual checkpoints can be created but not loaded
in production. Auto triggers currently occur on safe lifecycle events, not on
an elapsed-play timer. Orphan `.pending-*` directories are ignored safely but
have no automatic reclaim pass; interrupted publication can consume storage.
Unsupported DSi/3DS modes, filesystem-backed save trees and all state loading
remain disabled. Core/device compatibility is not generalized beyond the
pinned source revisions and synthetic fixture modes.

## Changed code and repository boundary

P4-C changes are limited to the two JNI adapters, `NativeCore`/`NativeDs`, the
two existing activities' storage/capture host wiring, `EmulatorSessionOwner`,
the new `CheckpointStore` and `PinnedCheckpointIdentity`, their JVM tests,
real-core host checkpoint probes, and the P4-C test scripts. The implementation
does not alter `GameSaveStore`, PLSV v1, session-receipt format, renderer/audio
loops, or native save/restore formats.

The test ROMs, serialized states, native save blobs, logs, and binaries remain
under ignored build/test locations; none are staged or committed. The checkout
started on `main` at `5b786c1644419ae0cdd48fadaa04023e6a49bbdd` with the
pre-existing modified `docs/P3_TECHNICAL_REPORT.md` and untracked
`docs/P4_ENGINEERING_PLAN.md`. Neither document nor `.plump-private/` is included
in P4-C. The worker's sandbox had a read-only `.git/index` restriction; the
canonical DevSpace shell independently confirmed that `.git/index` is writable
and performed post-worker source and staging reconciliation. No push or release.

## Exact takeover and independently completed work

- **Takeover:** canonical `main`, single checkout, HEAD `5b786c1644419ae0cdd48fadaa04023e6a49bbdd`;
  only the two pre-existing document changes above. No active worker/process was
  left from the interrupted first conversation. The previous Poteto parent log,
  interrupted transcript, pinned-core audit and host measurement probes survived
  under ignored `app/build/p4-c-work/`; no initial P4-C source edits survived.
- **Reuse:** resumed original Codex session `01a1e331-9b2b-7070-b22f-d4e793b2dbcd`
  with requested Main / GPT-6 Luna xhigh routing. One writer, no additional
  worktree. That worker implemented the adapters, owner coupling, storage,
  fixtures and tests, then completed the host integration evidence and
  initial report. An independent read-only safety reviewer examined its source.
- **Independent fixes afterward:** metadata SHA-256 was added because a
  disposable probe proved that altering `kind=manual` to `kind=auto` was
  previously accepted and could compromise retention. Manifest now also records
  canonical branch and owning session run token. A separate probe proved that
  initial recursive cleanup followed a symlink outside a slot; the worker
  hardened `NOFOLLOW_LINKS` handling. Both independent probes now pass, including
  preservation of an external disposable sentinel and of the oldest auto anchor.
  Checkpoint cleanup was additionally restricted to recognized regular files
  under a Plump-owned slot or pending directory; unknown files are deliberately
  preserved instead of being recursively deleted, with a JVM regression.
  The public host script's private absolute home path was replaced by an
  overrideable `$HOME` fixture path. JVM tests exercise tampered metadata,
  missing checksum, generation/session mutation and partial writes into machine,
  native and manifest objects.
- **No unrelated changes:** no modifications to PLSV v1 `GameSaveStore`,
  session-receipt schema, pinned `.so` binary revisions or the owner's installed
  version-code-8 application. GBA/DS historical load is still inaccessible.

## Independent durability, size and performance proof

| Probe (all ignored project-local data) | Observed result |
|---|---|
| Five child JVM hard exits using `Runtime.halt(87)` at `AFTER_TEMP_WRITE`, `BEFORE_MANIFEST_WRITE`, `BEFORE_RENAME`, `AFTER_RENAME`, `AFTER_MANIFEST_PUBLICATION` | **5/5 PASS**. Previously verified manual record survives fresh process. Pre-rename leaves exactly one published record; post-rename leaves two complete records. Log `app/build/p4-c-review/process-crash-post-gc.log`. Not a power-loss or on-device test. |
| Direct external metadata mutation probe | Formerly accepted manual→auto tamper; after fix **rejected**. External symlink sentinel preserved, oldest auto anchor remains. `app/build/p4-c-review/integrity-fixed-proof.log`. |
| Fresh store publication including file+directory fsync and readback; GBA | State 430,144 B + 32,768 B native + 736 B manifest = **463,648 B** total; single Linux sample publish **44.797 ms**, independent verification **1.368 ms**. |
| Fresh store publication including file+directory fsync and readback; DS | State 19,228,045 B + 8,192 B native + 778 B manifest = **19,237,015 B**; single Linux sample publish **111.048 ms**, independent verification **15.855 ms**. |
| Real source-pinned GBA/DS cold SRAM/EEPROM reload | **PASS** independently, separate emulator processes; same 32 KiB GBA SRAM and 8 KiB DS EEPROM verified. Logs `app/build/p4-c-review/native-cold-*-{first,cold}.log`. |
| RGB565 renderer, DS frame split, melonDS host, P3 performance parser regressions | **PASS** independently under `app/build/p4-c-review/`. |
| End-to-end real JNI/owner/store integration rerun after metadata fix | **PASS**, both pinned cores with one auto and two manuals each; unsupported/mutating/oversized serializers reject, cold disposable replay passes, DS EEPROM rollback hazard re-confirmed; log `app/build/p4-c-review/full-post-gc-host.log`. |

All allocation and I/O measurements are Linux host-only. Storage preflight and
fault-injected failures are tested; a genuine full disk, SELinux/SAF provider
failure, physical power interruption and Android lifecycle state interruption
have **not** been demonstrated for these new checkpoints. Android lint reported
23 warnings and zero errors; none was silently waived. BlocksDS/Docker fixture
rebuilding was not required for these tests and is not claimed as successful:
only previously verified licensed synthetic fixture binaries were used.

## Exact P4-C source and test file list

- JNI native capture: `app/src/main/cpp/plump_host.cpp`,
  `app/src/main/cpp/nds_host.cpp`.
- Android native binding and ownership: `app/src/main/java/dev/plump/emulator/NativeCore.java`,
  `NativeDs.java`, `EmulatorSessionOwner.java`, `MainActivity.java`,
  `DsActivity.java`, `CheckpointStore.java`, and `PinnedCheckpointIdentity.java`
  (all under that same package directory).
- JVM tests: `app/src/test/java/dev/plump/emulator/CheckpointStoreTest.java`,
  `EmulatorSessionOwnerTest.java`, `HostCheckpointProbe.java`.
- Licensed synthetic host harness/test scripts: `scripts/test-p4-c-checkpoints.sh`,
  `scripts/test-p4-br-ds-reload.sh`, `tests/native/mgba_checkpoint_test.cpp`,
  `tests/native/nds_host_lifecycle_test.cpp`,
  `tests/native/unsupported_serializer_libretro.cpp`,
  `tests/native/mutating_serializer_libretro.cpp`,
  `tests/roms/verify-p4-c-checkpoint.py`.
- This report: `docs/P4_C_TECHNICAL_REPORT.md`.

No core binary/ROM/state/save fixture, P3 report or private project-source file
belongs in the public commit. The existing two pre-takeover document changes
remain exclusively in the working tree.

## Final verification and mastermind gate recommendation

**P4-C (bounded host-only capture): ACCEPT CANDIDATE**, conditional on mastermind
review of the source, fixtures, checksum/retention policy, and acceptable
remaining Android timing/lifecycle limitations. **Whole P4: PARTIAL**, since
historical-state loading, P4-D isolation, user chooser, automatic periodic
cadence, local backup/export and target-phone state behavior are outstanding.
Do not install the local APK and do not begin P4-D without a separate milestone
assignment. There is no blanket DSi/3DS state capability claim.

**Exact implementation commit:** `98c4010dee7da9912db640ea3f833d4ef83b5efc`
(`feat(p4-c): capture immutable pinned-core checkpoints with durable manifests`)
on `main`, based on `5b786c1644419ae0cdd48fadaa04023e6a49bbdd`.
It contains the 20 explicitly staged and source-reviewed P4-C paths, including
the original version of this report. The present evidence reconciliation is a
separate small documentation-only commit (visible as the succeeding `main` HEAD
in `git log`; a commit cannot embed its own Git hash). **Final known working-tree
status:** pre-existing ` M docs/P3_TECHNICAL_REPORT.md` and
`?? docs/P4_ENGINEERING_PLAN.md` only. No remote push, APK publication,
APK installation, core revision change or phone operation.

## 2026-11-16 mastermind REWORK closeout — packaged core identity

**Defect:** the P4-C baseline `PinnedCheckpointIdentity.MGBA_BINARY_SHA256`
matched `app/src/main/jniLibs/arm64-v8a/libmgba_libretro.so` but not the
stripped ELF installed from either Android APK. `gba()` reads the actual ELF
under `context.getApplicationInfo().nativeLibraryDir`, so the original
implementation would reject the valid shipped mGBA and prevent GBA checkpoints.
The DS binary was unaffected.

**Correction:** production now separates `MGBA_UNSTRIPPED_INPUT_SHA256` and
`MELONDS_UNSTRIPPED_INPUT_SHA256` (source provenance only) from the independently
pinned debug/release **APK-packaged** hashes. `ApplicationInfo.FLAG_DEBUGGABLE`
selects the expected debug or release digest. `verifyInstalledBinary` continues
to SHA-256-hash the *actual* installed file; mismatched or missing native files
fail closed. A future release binary is **not** assumed identical to debug:
each variant has its own constant and must independently pass the APK regression
after every toolchain/native-core/packaging change. These two builds happen to
produce byte-identical core ELFs. The original mGBA and melonDS source Git
revisions, native serializer binding, checkpoint manifest schema, PLSV v1 and
P4-BR reload logic remain unchanged.

| Source / packaging stage | mGBA length and SHA-256 | melonDS length and SHA-256 |
|---|---|---|
| Unstripped `app/src/main/jniLibs/arm64-v8a/` input | 7,063,880 B · `05c9a63ed92b9dee8ac802992d9852c02a03515dd99b42d8b654b03434db4429` | 5,590,904 B · `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` |
| Gradle `stripped_native_libs/debug` and `stripped_native_libs/release` | 3,071,512 B · `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6` | 5,590,904 B · `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` |
| Both new APK `lib/arm64-v8a/*.so` ZIP entries, fully decompressed | 3,071,512 B · `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6` | 5,590,904 B · `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` |
| Frozen accepted v8 debug APK ZIP entries (independent historical comparison) | 3,071,512 B · `10353b6c5eb8ace7a7ed1205173554b597b9b76738a2363b69e1de12c40991b6` | 5,590,904 B · `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` |

**Exact APK hashes:** frozen accepted v8 debug
`8be596f0258579fcc8463243fc20ccbb27b77eb33a9a6270159667bc2645e38c`;
new local P4-C repair debug
`0779fa416c8b92314c92b4afe9fe3cdd5d0627a901be716faa31b6362016224c`;
new local **unsigned** release
`9d3b5f19fd8c5c2768bed7a30478f4e7b1083614831a1de419b7be6779ed950e`.
The three whole-APK hashes differ, while the pinned extracted ELF hashes agree.
The debug APK is built, **not installed**, and the release APK is unsigned.

**Automated packaging regression:**

```sh
export ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
python3 scripts/test-p4-c-packaged-core-identity.py --negative-test
bash scripts/test-p4-c-checkpoints.sh
```

The Python regression reads the literal production pin constants and checks
each rebuilt APK's **real decompressed ELF bytes**, Gradle stripped intermediates,
and unstripped upstream inputs. It extracts each APK ELF into an ignored
project-local scratch directory and invokes the **actual compiled production
Java** `packagedDigestForVariant` and `verifyInstalledBinary` methods. Both
variants and both cores passed positive runtime checks. A one-byte-modified
copy of mGBA was **rejected by the production Java method** for both variants.
The original **unstripped mGBA input ELF** was also rejected when supplied
as the supposed installed binary, directly reproducing and closing the
mastermind's defect rather than merely checking a synthetic digest.
The separate `PinnedCheckpointIdentityTest` adds three JVM tests proving exact
variant pin selection, unexpected binary rejection, and rejection of a nonpinned
file. No digest verification was bypassed or relaxed.

The regression also checks compiled APK manifests with Android `aapt`:
debug has `android:debuggable=true`, release does not, and both have
`android:extractNativeLibs=true`. This links runtime selection via
`FLAG_DEBUGGABLE` to the actual installed-file extraction policy, rather than
inferring either attribute from APK filenames alone.

| Post-repair verification | Result / ignored evidence |
|---|---|
| JVM suite | **80 tests, 0 failures, 0 errors, 0 skips** (11 suites), including three new identity tests. `app/build/test-results/testDebugUnitTest/` |
| ARM64 APK assembly | **PASS** `:app:assembleDebug` and `:app:assembleRelease`; no APK installed. |
| Android lint | **PASS**, 0 errors, 23 warnings. |
| Combined Gradle | **BUILD SUCCESSFUL in 16s**, 102 tasks. `app/build/p4-c-identity-review/final-gradle.log` |
| Source / Gradle-strip / actual APK / Java production digest match | **PASS** for debug and release, mGBA and melonDS; altered-mGBA rejection **PASS** for both. `app/build/p4-c-identity-review/production-runtime-probe.log` |
| Real pinned-core JNI / session owner / store | **PASS** mGBA and melonDS; genuine machine/native paired capture, unsupported/mutating/oversized rejection and isolated state readback. `app/build/p4-c-identity-review/final-host-integration.log` |
| Native-save and DS historical rollback regressions | **PASS** genuine synthetic GBA and DS save behavior and DS first/cold EEPROM recovery; older DS state demonstrably rewinds disposable EEPROM, so production historical loading remains unavailable. Same host integration log. |
| Independent GBA SRAM first/cold process recovery | **PASS**, 32 KiB verified across fresh mGBA processes, including pre-first-frame restoration. `app/build/p4-c-identity-review/gba-native-{first,cold}.log`. |
| Manifest integrity / symlink-safe GC / session ownership | **PASS** unchanged previous regression suites within the 80-test JVM run. |

**Scope and acceptance:** recommend **ACCEPT for the narrowly repaired, host-
verified P4-C creation milestone**, subject to mastermind review. This is not
on-device verification of Android's native-library extraction, filesystem fsync,
checkpoint latency, or historical-state isolation during production loading.
Those device/runtime and P4-D gates remain **PARTIAL / NOT IMPLEMENTED** as
previously reported. We did not access the phone, change its installed APK,
push to GitHub, alter personal saves, build new core revisions, or expose
historical-state loading. All new test artifacts remain ignored under
`app/build/p4-c-identity-review/`.

**Rework files:** `app/src/main/java/dev/plump/emulator/PinnedCheckpointIdentity.java`,
`app/src/test/java/dev/plump/emulator/PinnedCheckpointIdentityTest.java`,
`scripts/test-p4-c-packaged-core-identity.py`,
`tests/identity/PackagedCheckpointIdentityProbe.java`, and this report.
One local repair commit on `main` follows independent final validation;
its exact SHA is recorded in the handoff (a commit cannot embed its own ID).
