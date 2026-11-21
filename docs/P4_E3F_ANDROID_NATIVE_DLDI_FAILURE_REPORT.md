# P4-E3F — Native Android DLDI failure and cold-recovery evidence

**Date:** 2026-11-21
**Starting commit:** `48fbac7bf602b6837d5f250f1ce67096a2ab3d5e`
**Device:** iQOO Neo 6 / I2202, stock Android 14
**Result:** ACCEPT for the three **injected native failure modes** and subsequent recovery. Production writable virtual SD remains disabled; this is not a power-loss durability claim.

## Evidence boundary and issue discovered

The earlier E3E milestone verified Android storage-store failures. E3F set out to exercise **actual Android ARM64 melonDS DLDI write, stream flush and file close failures**, not mocked Java receipts or a Linux-only core. It reused only the CC0 BlocksDS Alpha DLDI counter ROM and pinned FAT16 baseline.

In the new, isolated `dev.plump.emulator.e3native` application, the originally accepted E3A core (`9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`) produced:

- `DLDI_WRITE`: native error **10**, DLDI/write failures nonzero; application reported `RECOVERY_REQUIRED`; no new generation.
- `STREAM_FLUSH`: native error **12**, `flushAttempted=1`, `flushSucceeded=0`, close attempted; application reported `RECOVERY_REQUIRED`; no new generation.
- **Unexpected result** for `FILE_CLOSE`: native error **0**, `closeSucceeded=1`, `closeReturned=1`; a legitimate generation **3** committed. This was **not** counted as a passed close-failure test. Its unchanged prior generation and new 128 MiB FAT image were subsequently independently verified using read-only FAT, binary record and checksum inspection. No test data was cleared to conceal the result.

**Root cause:** the original E3A ARM64 source-build script tested for a generic `if (output == 0)` line in `streams/file_stream.c`. That line exists **before** application of `patches/p4-e2/libretro-vfs-close-error.patch`, so the conditional skipped the actual patch. Source and ELF inspection showed that `PLUMP_E2_FAIL_FILE_CLOSE` was absent from the frozen E3A Android core, even though the other two native hooks were present. A proper close-error propagation check must inspect the unique marker in `vfs/vfs_implementation.c`, not a generic line. The old E3A build script now **fails closed** on the missing marker instead of silently treating the unpatched source as a fully instrumented close-error build.

## Corrected core without modifying accepted core identities

`scripts/build-p4-e3f-native-close-arm64.sh` clones the already pinned E3A frontend and pinned dependency source trees into ignored `app/build/p4-e3f-close-core/`, applies the **existing** accepted close-error patch only in that isolated copy, and builds an ARM64 core with the accepted NDK and original build options. The new build uses the same pinned melonDS/Libretro frontend revisions, and it changes neither production source/runtime nor the previously accepted E3A binary. The unique VFS patch marker is verified in source and in the compiled ELF. A second no-work rebuild reproduced the exact SHA below.

| Core | Exact SHA-256 | Status |
|---|---|---|
| Original production DS core | `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30` | Byte-identical, untouched |
| Frozen original E3A Android core | `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9` | Byte-identical, retained for reproducibility |
| New E3F corrected Android core | `abedb9ee7c1fcdc91a6b4451d3fba7fc9679f922e8b665c745e07a589eab27ad` | Distinct test-only core, signed into E3F package |

The separate `e3close` Gradle flavor has the literal Android package ID **`dev.plump.emulator.e3close`**, a matching runtime package-identity check, and its own native-library staging directory, BuildConfig core hash and private Android data directory. This is intentionally **not an update** to `e3native`: two cores with different identity hashes must not share one authoritative virtual-SD history. The new JNI fault-configurator performs a separate literal package allowlist check, refuses core reconfiguration while initialized, and sets only the specifically selected existing native E2 environment switch. The JNI test exports are entirely absent from the production host library. Fault injection cannot be activated by the production application.

**Frozen E3F corrected APK:** `app/build/p4-e3f-native/plump-e3close.apk`

**APK SHA-256:** `97d4de76edfacd5ab69c3e03aba560dea71b954f517575b9366a3b2ac6400e40`

The APK was inspected for its exact package ID/launcher, native ELF hash and ABI, preserved CC0 assets and seed, test-only JNI symbols, disabled backup, no Internet permission and debug signature. Wrong package-ID substitutions were independently rejected. `assembleE3closeDebug`, `testE3closeDebugUnitTest` (**154/154**), and `lintE3closeDebug` passed before installation.

## Corrected-core physical device test matrix

Each run used a distinct cold Android process (force-stopping **only** `dev.plump.emulator.e3close`), inspected its on-screen selected fault, and checked the native status ABI and exact immutable generation hashes. No unverified state was promoted or migrated between test packages.

| Test | Actual native evidence / observed disposition | Result |
|---|---|---|
| Normal baseline | 18 DLDI write calls; zero native errors; flush/close both succeeded; genuine ARM9 counter 0→1, generation 2 committed | **PASS** |
| `FILE_CLOSE` | 14 DLDI write calls; flush succeeded; `closeAttempted=1`, `closeSucceeded=0`, `closeReturned=0`, **firstError=13**; `RECOVERY_REQUIRED`, no generation 3; prior HEAD/image hashes unchanged | **PASS** |
| `DLDI_WRITE` | **firstError=10**, write and DLDI failure counters nonzero; `RECOVERY_REQUIRED`, no new generation, prior images exact | **PASS** |
| `STREAM_FLUSH` | **firstError=12**, `flushAttempted=1`, `flushSucceeded=0`, close attempted; `RECOVERY_REQUIRED`, no new generation, prior images exact | **PASS** |
| Cold recovery, no faults | Three abandoned working sessions quarantined; real ARM9 counter 1→2, generation 3 committed; normal successful native flush/close | **PASS** |
| Independent validation | Android-exported 128 MiB image accepted by read-only `fsck.fat`; PyFatFS binary/text records report `PREVIOUS=1`, `COUNTER=2`, valid `CHECKSUM=60868817`; strict HEAD, manifest SHA, image digest, parent UUID and previous HEAD checked | **PASS** |
| Final cold strict reconciliation | No pending operation or metadata mutation; generation-3 HEAD remained byte-identical | **PASS** |

### Final E3F immutable generation chain

| Sequence | Generation UUID | Image SHA-256 |
|---|---|---|
| 1 (seed) | `2911f574-ae31-4fb1-8486-3466d26a8afd` | `14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e` |
| 2 | `c7f83bbd-c469-4334-a7e4-9d2f0b9e9c74` | `5483cb6926bc0a01ed2689f7bfcc5e9c7d1c73ed2369b8c98fc801ccb4251ca0` |
| 3 (post-failure recovery) | `3252a37f-6dd9-4afa-aee1-88af301692e7` | `f8f735ec5e79ed3b76971eaf9c02055dd1516de582923772057cc632f3b6612b` |

## Isolation, device-space budget and preservation

- Original installed Plump production v8 and **all five pre-existing experimental app packages** were checked before/after and remained APK-byte-identical.
- All **five previously accepted experimental HEAD histories** remained byte-identical, including the earlier `e3native` package and its unexpectedly committed generation 3. The older E3B history was never reused or reset.
- The existing Plump v8 app-private-files backup archive passed its SHA-256 verification. Personal ROMs, saves and app data were not opened or modified.
- The corrected test's new Android UID/private directory contains only disposable test artifacts; none of its image data was copied to production or another test package.
- The user's safety floor was **2 GiB free**. Before each image run, available data-partition space exceeded 2 GiB plus 768 MiB; the final check showed **5,649,120 KiB free** (about **5.39 GiB**). No storage-filling experiment was attempted.
- The phone's portrait orientation remained `lock 0`; the user's original YouTube task was returned to the foreground.
- There was no bootloader/root action, system-level modification, GitHub push or public app release.

## Host regression and durable evidence

The E3F local device logs, UI XML, native status readings, immutable HEAD/manifests, FAT extraction and source-build receipts are held under ignored `app/build/p4-e3f-native/` and `app/build/p4-e3f-close-core/`. The `e3close` physical evidence is under `app/build/p4-e3f-native/device-close-VL0J4FAt/`. The earlier unexpected result is retained under `app/build/p4-e3f-native/device-hVxOB4jh/`.

**Final independent host verification completed after device testing:** the combined offline Gradle run reported `BUILD SUCCESSFUL` (261 tasks, 88 executed and 173 up-to-date). Production, E3B, default E3C and corrected E3F each passed **154/154 JVM tests** (616 total, zero failures/errors/skips). Production debug and unsigned release, E3B, E3C and E3F debug APK assembly, plus all four Android lint targets passed. The existing strict E3A APK audit passed its three negative package/core substitutions; `scripts/verify-p4-e3f-close-isolation.py` independently accepted the corrected frozen APK and rejected all four wrong package IDs, checked the corrected VFS patch and core SHA, and reconfirmed both older core identities. A deliberate attempt to build `dev.plump.emulator.e3close` via the old `e3interrupt` package override was rejected during Gradle configuration.

The three original pinned **Linux native-core** error-injection regressions also passed independently (DLDI write, stream flush and close); each rejected publication and preserved its immutable sequence-1 head. This is complementary host evidence, not a substitute for the real Android E3F runs. Shell-script syntax and Python audit-module compilation checks passed. All logs are under ignored `app/build/p4-e3f-native/` (`final-gradle-regression.log`, `final-old-apk-audit.log`, `final-e3f-apk-audit.log`, `final-host-native-regression.log`, `deny-e3close-alias.log`). No `gradlew clean` was used.

## Not established by E3F

The E3F switches deliberately inject faults **inside the native test core**. They do not prove actual OS `close(2)` failure, disk hardware write failure, eMMC/UFS power loss, or Android kernel directory sync durability under sudden power removal. In particular, a failed native close returned `RECOVERY_REQUIRED` and needed a fresh Android process; the test does not prove arbitrary live-session retry is safe. Exact post-HEAD fsync failures, concurrent low-storage races, normal DS save-data UX across multiple games, DSi NAND and 3DS persistence remain separate gates. Production writable DLDI is still **disabled**.
