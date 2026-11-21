# Plump P4-E3A — isolated Android DLDI integration candidate

**Date:** 2026-11-19. **Mastermind gate:** E3A host-side implementation and verification only.
**Baseline HEAD:** `0df2ee8e497e2938d8626224eb4ef386f838642d` (`main`, canonical `/home/suraj/projects/Plump`).
**Recommendation:** **PARTIAL / HOST-COMPILED TEST APK CANDIDATE**; no device acceptance, installation, Android filesystem durability proof or E2 production authorization. The candidate may be independently reviewed for a *separately authorized* isolated device test. **Do not install it under the current authorization.**

## 1. Scope and independent preflight

Read authoritative private project sources beginning with `00_READ_FIRST.md`, relevant decision/engineering/architecture/save-integrity/acceptance/workflow documents, `docs/P4_ENGINEERING_PLAN.md` §5, `docs/P4_E1B_DLDI_WRITER_VALIDATION_REPORT.md`, `docs/P4_E2_VIRTUAL_SD_DURABILITY_REPORT.md`, and actual owner, Android JNI, DS activity, generation-store, pinned source and three E2 patches. Independently verified root, `main`, exact starting HEAD, origin `https://github.com/Suraj-H675/Plump.git`, canonical worktree and unrelated workers. Did not touch another project's Codex job. Preserved the unrelated **modified** `docs/P3_TECHNICAL_REPORT.md` and **untracked** `docs/P4_ENGINEERING_PLAN.md`.

The owner reported a connected Android 14 ARM64 iQOO Neo 6 with an existing installed v8 and verified private backup. **No ADB command of any kind was run during E3A.** In particular: no install, update, uninstall, force-stop, settings modification, package/data inspection, personal game/save/firmware access or use, inspection, or mutation of that backup. Android hardware/storage attributes are **user-provided context, not independently device-tested results**.

## 2. Separate Android application identity and native package boundary

Android Gradle flavors in `app/build.gradle`:

| Source flavor and build | Application ID | Launcher/label | DLDI/core packaging |
|---|---|---|---|
| `productionDebug` and `productionRelease` | `dev.plump.emulator` | `dev.plump.emulator.MainActivity`; `Plump` | Original accepted ARM64 production melonDS core; E3 JNI exports absent and DLDI forcibly disabled |
| `e3testDebug` | **`dev.plump.emulator.e3test`** | `dev.plump.emulator.E3TestActivity`; **`Plump E3A • ISOLATED TEST`** | Separate verified ARM64 E2-instrumented core and embedded original CC0 DLDI Alpha test cartridge; experimental JNI exposed only behind `PLUMP_E3_DLDI_TEST` |

`app/src/e3test/AndroidManifest.xml` revokes the normal library launcher and exposes only the narrow experimental diagnostic activity. The experimental app has separate Android package identity, UID and private data directory; the activity checks both its BuildConfig flag and the **actual runtime package ID** before doing anything. It does not import, copy, request or reference original v8 storage. Backup is disabled; no Internet permission requested. The production Java source contains harmless declarations of E3 JNI functions but **the production compiled native ELF lacks both corresponding JNI exports** and production option forcing remains `melonds_homebrew_sdcard=disabled`.

Production debug and unsigned release APKs passed binary-inspection tests for exact package/launcher/source core. Test-only ROM and FAT assets were absent from both production APKs. The E3 debug APK passed the opposite checks and the verifier **deliberately rejected three wrong production/test ID/core combinations**. All APKs use ARM64 native libraries only.

## 3. Exact experimental ARM64 core provenance and reproducibility

`scripts/build-p4-e3a-melonds-arm64.sh` reconstructs a **new ignored** frontend source tree by `git archive` at upstream `JesseTG/melonds-ds` **`f394adbacb5722ee97c1b37c8064da9a25818310`** and copies each verified pinned dependency tree into `app/build/p4-e3a/deps/`. The engine revision is still **`16f127dbb587a73371827ce79689c5d237c4b59b`**; other upstream dependency pins are listed and checked in the script. It applies the three already accepted E2 source patches in new ignored copies, not the canonical upstream trees:

- `patches/p4-e2/melonds-dldi-health.patch` SHA-256 `c3a242046dbe8091a460042f72056a9c2a0135f0271957bee6cccade6b0053ee`;
- `patches/p4-e2/libretro-vfs-close-error.patch` SHA-256 `c51cb8881e5722c73139831f5054a823a085be8090f9f6f1775aefaaf4d658f8`;
- `patches/p4-e2/libretro-melonds-status-api.patch` SHA-256 `87deb94294f4f6306bb1fc00b698139bc68a42c3a9e94336f41e70d5f4546b60`.

The additional **narrow E3 Android-only** `patches/p4-e3a/arm64-e2-close-constness.patch` corrects an Android Clang C++ const qualifier on the accepted E2 frontend's explicit-close export. It is applied *after* E2 patches, only to the ignored experimental source copy; no upstream Git revision changed. Android NDK `28.2.13676358`, ARM64 `android-26`, Release, software rendering and exact accepted CMake/FetchedContent versions were used. Raw Android frontend ELF built and an independent **stripped** artifact copied under ignored `app/build/p4-e3a`.

| Core/native artifact | Exact SHA-256 |
|---|---|
| Original production packaged melonDS `libmelondsds_libretro.so` (unchanged) | `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` |
| Experimental stripped ARM64 E2 `libmelondsds_libretro.so` | **`9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`** |
| Embedded original licensed BlocksDS Alpha `.nds` | `1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588` |
| Host-preformatted FAT16 baseline (128 MiB) | **`14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e`** |

Actual Android ELF `llvm-readelf` and exported dynamic symbol tables verified `AArch64`, `melondsds_dldi_status_v1`, `melondsds_dldi_close_v1`. Original app core SHA remained unchanged. Both BuildConfig and Gradle staging logic pin the **literal** expected experimental ELF and baseline SHA-256; changing a receipt file alone cannot whitelist new bytes. An absent or mismatched experimental core/fixture/baseline causes a build failure; no fallback to the production core. The generated stage dir `app/build/e3test-core` is Git-ignored.

## 4. JNI close/health protocol, session ownership and publication

The JNI code in `app/src/main/cpp/nds_host.cpp` wraps E3-only additions in `#ifdef PLUMP_E3_DLDI_TEST`; the production variant compiles with that flag absent. Experimental `NativeDs.initializeE3()` requires both exact versioned E2 core exports before enabling `melonds_homebrew_sdcard`. The pinned E2 health structure is fixed at 128 native bytes, 21 fields; JNI returns those fields plus its own `close()` boolean (22 Java `long`s). The host validates version=1, state=2 at the pre-close boundary and exact 128 MiB. Following explicit native close it re-reads the status, and `NativeDs.e3Receipt()` maps the error counters, FAT result, dirty/flush/close status and mode flags into the existing production-side `VirtualSdGenerationStore.CoreCloseReceipt`.

The **internal diagnostic-only** `E3TestActivity` uses `EmulatorSessionOwner`'s exclusive transition lock and run token, plus the JNI `coreMutex`. Only its SHA-pinned bundled DLDI Alpha ROM may load. A separate worker executes up to 180 real frames. Before publication, the worker must complete or quiesce; frame/audio workers cannot step concurrently with native close. Owner `stopAndSave()` invokes E2 close, accepts the receipt only if `permitsCommit(134217728)` passes, then independently FAT-checks the image and verifies the ARM9-created A001.BIN and A001.TXT report/record including exact old→new counter and FNV32 checksum. **Only then** can `VirtualSdGenerationStore.commit()` hash/copy/fsync and durably publish immutable image+manifest and a dual-slot checked head, retaining earlier generations. No cartridge EEPROM PLSV operation is used as a proxy for FAT progress.

On interrupted UI/lifecycle execution the activity attempts to stop the native frame worker and fails closed rather than committing an incomplete run. If it cannot quiesce, it does **not** unlock the working image or shut down the core while native frames can still touch FAT. The E2 store isolates/quarantines interrupted working sessions on subsequent cold access; an incomplete transaction is never implicitly promoted. This integration is **host source/compile reviewed, not exercised on a physical Android lifecycle**.

## 5. Android-compatible FAT16 validation and storage boundaries

`VirtualSdFat16Validator` uses **read-only Java `FileChannel`**, not a nonexistent Android `fsck.fat` binary. The implementation accepts only the bounded **134,217,728-byte** approved FAT16 image geometry, checks boot signature/BPB, total/data clusters, FAT16 type, both redundant FATs byte-for-byte, reserved allocation entries, allocation consistency, orphaned clusters, cycles/cross-links and directory/file chains. Corrupt/mismatched/oversized/symlinked or unsupported formats raise an error. No automatic repair, formatting or changed input bytes. `E3DiagnosticFatRecord` separately parses the actual diagnostic directory and binary record/report. E2's generation store separately compares SHA-256 of **every committed image** with its checksummed manifest.

`VirtualSdFat16ValidatorTest` covers a valid sparse FAT16 layout and multiple deliberate negative conditions; `scripts/test-p4-e3a-android-fat-host.sh` runs the **actual Java checker** against a genuine accepted pinned melonDS DLDI-writer-generated FAT16 image. It independently parsed actual counter **3**, previous **2**, and rejected a separate corrupted FAT replica. Linux `fsck.fat -nv` independently checked the same positive fixture; the Java checker does not invoke it. Host-side validations passed and left the source SHA unchanged. Actual Android filesystem and SQLite/provider semantics are **not** inferred from Linux success.

The experiment stores all new data under the *test package's own* app-private `files/e3-only` path, partitioned by exact ROM SHA-256 and canonical generation ownership. It explicitly preflights both Java `FileStore.getUsableSpace` and Android `StorageManager.getAllocatableBytes` for at least **768 MiB** before creating any 128 MiB working/generation data. The initial FAT16 volume is preformatted on the host, compressed as `assets/e3-fat16-baseline.e3seed`, then size/SHA-checked on extraction; **no 4 GiB image fallback or on-device formatter exists**. All working copies and immutable images have size caps and are app-private. The E3 test refuses to create generations beyond a fixed three-write demonstration (baseline sequence 1, maximum sequence 4) and performs **no automatic GC of old generations**. On Linux the baseline is sparse; on Android a 128 MiB copy or force may physically consume the entire logical size. 768 MiB is a cautious per-run free-space *preflight*, not a verified Android quota or performance bound.

Android implementation invokes `FileOutputStream.getFD().sync()` for initial decompression, `Files.move(... ATOMIC_MOVE)` for seed publication, and `android.system.Os.open` / `Os.fsync` for actual parent-directory flush. E2 uses `FileChannel.force(true)` and checked atomic rename for individual generation/HEAD receipts. **Actual force/rename/fsync behavior on the target iQOO/Android filesystem cannot be verified without the separately authorized install/device experiment.** The source fails closed on syscall error; physical power-cut guarantees remain unproven.

## 6. Independent verification results

All evidence paths below are ignored and contain only licensed diagnostics or generated data. No personal game/save/firmware or owner backup was opened.

| Test / command | Observed result / evidence |
|---|---|
| `PLUMP_E3_JOBS=2 bash scripts/build-p4-e3a-melonds-arm64.sh` | **PASS** pinned ARM64 E2 core, both versioned native health/close exports; ignored `app/build/p4-e3a-build-attempt-3.log`, ELF and SHA above |
| `./gradlew --offline --no-daemon :app:assembleProductionDebug :app:assembleProductionRelease :app:assembleE3testDebug :app:lintProductionDebug :app:lintE3testDebug :app:testProductionDebugUnitTest :app:testE3testDebugUnitTest` | **PASS**; final full transcript `app/build/p4-e3a-final-freeze-gradle.log` |
| JVM production debug | **128/128** tests, 16 suites, 0 failed/errors/skipped, including E2 immutable/fault-store tests and 8 FAT16/receipt tests |
| JVM E3 debug | **128/128** tests, 16 suites, 0 failed/errors/skipped |
| Android lint | **0 errors, 19 warnings** production debug; **0 errors, 22 warnings** E3 debug. No lint baseline weakened |
| `python3 scripts/verify-p4-e3a-apk-isolation.py --negative-test` | **PASS** three production/test APK ids, distinct launchers, offline/backup flags, exact packaged original and experimental ELFs, E3 JNI export isolation, exact licensed ROM and **stream-decompressed** 128 MiB FAT baseline SHA; rejects 3 incorrect ID/core combinations. Log `app/build/p4-e3a-apk-audit-frozen.log` |
| Android `apksigner verify --verbose --print-certs` | **PASS** experimental debug APK signed by existing Android debug certificate, APK Signature Scheme v2 valid; not a release/signing acceptance |
| `bash scripts/test-p4-e3a-android-fat-host.sh` | **PASS** genuine writer image/counter3 in host Java read-only FAT checker; corrupt alternate FAT replica rejected; `app/build/p4-e3a-android-fat-host-final.log` |
| `bash scripts/test-p4-e1b-real-dldi-writer.sh` with original pinned legacy core override | **PASS** real ARM9 file open/write/reopen/cold launch, 2-ROM/branch isolation, corruption and readonly rejection; `app/build/p4-e3a/regressions/e1b-real-writer.log` |
| `VirtualSdRealCoreProbe` using exact E2 pinned Linux core | **PASS** two real DLDI progressions, explicit native close, FAT-checked immutable generations 1→2→3; `app/build/p4-e3a/regressions/e2-real-generation.log` |
| `VirtualSdCrashRecoveryProbe` separate real core and Java owner hard exits | **PASS** each exit **87**, abandoned working image quarantined, canonical baseline generation1 preserved across a new owner process; `app/build/p4-e3a/regressions/e2-real-crash-*` |
| `bash scripts/test-p4-e3a-e2-native-failures.sh` | **PASS** three real pinned engine fault toggles (`PLUMP_E2_FAIL_NEXT_DLDI_WRITE`, `PLUMP_E2_FAIL_STREAM_FLUSH`, `PLUMP_E2_FAIL_FILE_CLOSE`), each rejects publication, retains original canonical sequence1 and exact protected image checksum; `app/build/p4-e3a/regressions/e2-three-native-injected-failures.log` |
| P4-C genuine GBA/DS host JNI save, checkpoint and DS rollback test | **PASS** with explicitly selected **original pinned** DS core (the default host path currently contains E2-instrumented host core); `app/build/p4-e3a/regressions/p4-c-jni-checkpoints-final.log` |
| P4-D1 real SRAM/EEPROM canonical/branch isolation | **PASS** actual pinned cores, original PLSV files SHA unchanged; `app/build/p4-e3a/regressions/p4-d-real-branch-isolation.log` |
| P4-D2 five branch-publication and ten selector hard process exits | **PASS 15/15**, canonical PLSV checkpoint evidence intact; `app/build/p4-e3a/regressions/p4-d-selector-hard-kill.log` |
| P4-D3 eight real-core restoration interruption tests | **PASS 8/8** with new disposable D1 source snapshots; `app/build/p4-e3a/regressions/p4-d-real-restore-deaths.log` |

**Accepted regression compatibility adjustment:** the E2 milestone placed an E2-instrumented *Linux host* `.so` at the historical p3-host path. The P4-C/D1/D3 regressions require the original SHA-pinned melonDS host core `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616`, preserved under ignored `app/build/p4-e2-work/baseline/melondsds_libretro.so`. Added a narrowly optional `PLUMP_P4_LEGACY_DS_CORE` override to four host-test entry points, preserving their original default and SHA assertions. **This is not a reimplementation or test weakening**; it makes the core identity explicit and avoids overwriting the accepted E2 host ELF. The first run correctly failed when the wrong host path was used; the independent rerun passed against the exact original pinned SHA.

## 7. Frozen experimental APK candidate (DO NOT INSTALL YET)

**Exact candidate APK:**

`app/build/outputs/apk/e3test/debug/app-e3test-debug.apk`

**APK SHA-256:** **`11e4f6a934ff51e29c3838d1b3b9262ccddae4e70cc18c16444a30320691e541`**
**APK bytes:** **3,638,404**
**Application ID:** **`dev.plump.emulator.e3test`**; launcher `dev.plump.emulator.E3TestActivity`.
**Packaged native melonDS ELF SHA:** **`9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`**.
**Manifest:** Android minSdk 26/targetSdk 35; test-only label; allowBackup false; no INTERNET.
**Signature:** APK v2 signature verified, Android debug signer only.

For comparison, independently rechecked unmodified production-core packaging:

| Produced artifact | Package ID | APK SHA-256 |
|---|---|---|
| `app/build/outputs/apk/production/debug/app-production-debug.apk` | `dev.plump.emulator` | `90c49349be3df4bc8ff2024dc1c41d6873d08f02c8ad29157bb8861f8b89351d` |
| `app/build/outputs/apk/production/release/app-production-release-unsigned.apk` | `dev.plump.emulator` | `e685d8cf8835118695215b3146365536bff2a1bbc3ccdfb5f5ac3581b5c5f7db` |

**These are host-produced artifacts, NOT installed package versions.** Full test archive bytes (including the gzip-compressed baseline and original licensed diagnostic ROM) and ELF exports were inspected directly; the APK file hash must be reverified before any separately authorized device operation. Never substitute an earlier candidate even if the filename is the same.

## 8. Expected next gate: separately authorized manual Android device test

No installation is authorized in E3A. For an *E3B/on-device scope only after mastermind approval*:

1. Independently confirm the exact APK SHA and `dev.plump.emulator.e3test` ID; reject if they differ from the frozen candidate. Confirm the ordinary installed `dev.plump.emulator` app remains v8 and untouched; no private data export/import.
2. Install **only** the distinct E3 test package after explicit permission, without uninstalling, updating, launching or stopping the production app. Verify test app data location is different by package identity; do not read the owner's saves.
3. Launch the clearly labeled isolated test app. Check app-private available space. Tap once for the **embedded licensed Alpha ROM only**, expecting genuine in-game `0→1`, a valid native close/health receipt, verified FAT16 and a newly committed immutable generation2. Capture the exact progress SHA/status; no need to access production storage.
4. Fully terminate only the test app and cold launch it again. Tap for **`1→2`**, and repeat for **`2→3`**; verify the previous immutable generation and original test baseline remain present across restarts. No historical machine-state restoration or promotion.
5. Exercise separately authorized interruptions only after successful device proof: background/pause, abrupt E3-only process exit and invalid image recovery; ensure the UI reports uncommitted or recovery-required instead of manufacturing success. Recheck canonical generation and native close/force/rename/dir-fsync evidence with the test app's own storage/logging only.
6. Freeze results and present back to the mastermind before enabling anything in production. Do not assume Android power-loss durability from successful lifecycle tests.

## 9. Unverified conditions, risk and handoff

**Not verified on an actual Android device:** Libretro stepping/audio and rendering integration, DLDI file writes, native close or unload/failed-close behavior under Android's linker/filesystem, `Os.fsync` directory behavior, atomic rename under Android's installed storage, actual storage allocation/copy throughput, lifecycle interruption recovery, process death during a live write, physical power cuts or low-space behavior. These remain E3B or later **acceptance gates**, not production guarantees. The host acceptance of the E2 protocol and current compile success cannot substitute for those results.

**Production guarantee retained:** ordinary Plump DLDI is still forced disabled, no existing v8 APK/data/save/backup inspected or modified, no UI restoration/promotion, no DS homebrew SD auto-enable, no cloud/networking, no upstream revision changes, no P4-F, 3DS or DSi NAND scope. Existing user P3 report modification and untracked P4 plan must remain unstaged. Generated ELF, binary ROM, FAT images, screenshots, APKs, logs and personal backup are never to be committed. The completed local commit ID and exact Git status are recorded in the engineering handoff (Git hash cannot be precomputed inside its own committed report).

**Recommendation:** **E3A host engineering candidate ready for mastermind review; stop here.** A separately authorized Android-device test and explicit acceptance are required before any E3B operation or production integration.
