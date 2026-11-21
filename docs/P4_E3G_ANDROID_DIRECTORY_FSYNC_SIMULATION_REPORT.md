# P4-E3G — Android directory-sync error simulation and strict recovery

**Date:** 2026-11-21
**Baseline commit:** `00c3eeb5a1cc3f8af7911be2bca5900effe899aa`
**Device:** iQOO Neo 6 (I2202), Android 14, ARM64; ordinary unrooted phone
**Verdict:** PASS for a **synthetic Android `Os.fsync` EIO** at the exact HEAD directory callback, followed by cold reconciliation and independent FAT verification. NOT a real kernel `fsync` failure or power-loss test.

## Scope, isolation and controls

The approved E3F milestone proved real-core DLDI write/flush/close error handling. E3G tests Android Java directory-sync failure semantics at the **authoritative HEAD rename-to-fsync boundary**, without imposing real filesystem exhaustion or modifying device-wide settings.

- A new, explicitly allowlisted `e3interrupt` test-package ID, `dev.plump.emulator.e3sync`, provides a unique Android UID/private data directory. It is **not** an upgrade to production, E3B, E3C, rotation, earlier storage-fault, or native-fault test installations.
- The accepted original ARM64 E3A melonDS core SHA-256 `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9` is unchanged; the CC0 BlocksDS DLDI writer and immutable FAT16 baseline are unchanged.
- The existing BuildConfig `E3_DEVICE_FAULT_TEST` flag is enabled only for the disposable `e3fault` and `e3sync` package identities. The simulated EIO mode additionally requires the literal `dev.plump.emulator.e3sync` package ID. The default experimental package, ordinary production and E3B cannot activate this mode.
- The injected mode, `SIMULATED_FSYNC_EIO`, is a **deterministic Java test fault**. After the native frame worker finishes and publication reaches `DURING_HEAD_DIRECTORY_SYNC`, the test opens the actual Android private directory but returns a constructed `IOException` caused by `ErrnoException("fsync", EIO)` **instead of calling** `Os.fsync`. It does not claim that the Android kernel produced EIO. All unaffected directory operations perform the real `Os.fsync`.
- Before each write the host checked that available device data space exceeded a 2 GiB preservation floor **plus 768 MiB test headroom**. No intentionally full disk, root, raw partition writes, package data clearing, or APK replacement of existing Plump applications occurred.

## First attempt: fault was selected but not executed

The first, independently signed `dev.plump.emulator.e3sync` APK passed its 154 E3interrupt JVM tests and isolated-core/package audit. It correctly made its first normal native save from counter 0→1 as generation 2.

On the initial `SIMULATED_FSYNC_EIO` run, the UI displayed the selected fault but the game wrote and published **generation 3 normally**. This run was **NOT** accepted as an injected-failure test. The working data and committed generation were retained for investigation, not reset.

**Cause:** commit admission was armed only when `requestedCommitFault != null`, which worked for directly thrown store fault points. The new *callback-based* synthetic errno mode has no direct throw-point and therefore never armed. The error was in the diagnostic harness, not evidence that the virtual-SD store had ignored an actual failing `fsync`.

**Correction:** explicitly arm the one-shot commit gate when the exact `e3sync` package selects `SIMULATED_FSYNC_EIO`; add pure-Java `E3SyncFaultPolicy` and three dedicated `testE3interrupt` unit tests proving correct arming, package isolation, and disabled/normal-mode rejection; add a conspicuous `E3G FAULT NOT EXECUTED` diagnostic if this gate does not fire. The test APK was rebuilt/retested and installed as an **in-place upgrade to only e3sync**, retaining its original generation-3 HEAD and all image hashes exactly.

| Artifact | SHA-256 |
|---|---|
| Initial, inadequate test APK: `app/build/p4-e3g-sync/plump-e3sync-first-attempt.apk` | `61238693be911118a8acf70885dd5dd8bab4f07e6de655e008502fd98198c9fd` |
| Corrected, device-accepted APK: `app/build/p4-e3g-sync/plump-e3sync-corrected.apk` | `463421a73b8010def8ae50fd1bce44ab6512b92ea239b62a2e22362e63bc1e04` |

## Corrected Android device observations

| Stage | Observed outcome | Verdict |
|---|---|---|
| Corrected app update | Existing generation 3 and every immutable-image SHA identical before/after install | **PASS** |
| Select synthetic EIO on cold process | Mode shown explicitly on-screen; successful real native ARM9 write attempted | **PASS** |
| New generation 4 HEAD rename followed by synthetic directory-sync EIO | `RECOVERY_REQUIRED` (not `COMMITTED`); actual diagnostic includes `E3G simulated Android Os.fsync EIO after canonical HEAD rename`; further process writes blocked | **PASS** |
| Pending authority | Signed/checksummed pending record names exact candidate 4 and prior generation 3; candidate HEAD visible; parent HEAD remained byte-exact | **PASS** |
| New Android process, fault disabled, strict pending reconciliation | Exact candidate and parent validated; **real Android directory sync** on recovery succeeded; authoritative generation 4 verified, pending marker cleared | **PASS** |
| Next cold start, no pending transaction | `No pending E3 transaction was found; no metadata was changed`; generation 4 HEAD byte-exact | **PASS** |
| Independent recovered FAT image and in-game save | 128 MiB image passed read-only `fsck.fat`; read-only PyFatFS checked genuine ARM9 binary/text record `PREVIOUS=2`, `COUNTER=3`, `CHECKSUM=105A4906`; head, manifest, image SHA and parent all independently verified | **PASS** |

The test demonstrates that a synthetic `fsync` error at the specific Java directory-sync callback does **not** cause the app to claim a completed save before durability is established. A later fresh-process reconciliation can safely prove and synchronize the exact candidate. This does not prove all possible genuine Android kernel/physical device failures.

### Final disposable generation chain

| Sequence | UUID | Image SHA-256 |
|---|---|---|
| 1, immutable seed | `be134c43-b7de-42d5-8d38-8c1aa0e1af81` | `14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e` |
| 2, first genuine save | `f9db21d4-b401-48a1-9c2c-69c18fad4923` | `c66e6d6b584991e9be9720c5d1997ea7c1af8c1f84f8239604d45aece28cc839` |
| 3, unexpected but valid commit | `dd39edd8-9190-4dcf-bb30-42a95bdac7aa` | `c9aa8de1c0f32a9ea6c34847539e0cdcbda614f61f2e5ab64decc74911ffd713` |
| 4, strict recovery after synthetic EIO | `ddd70cb0-1a46-4d57-b6fe-fd382e5d2907` | `d3bd6274d017f4baf0b6a2245c862be16175de546ad24de246884f886e13608f` |

No generation or HEAD was overwritten or reset to make any test pass. The disposable package has reached its three-write limit; **do not attempt another write** against this history.

## Preservation and final device state

- All **seven pre-existing installed Plump APKs**, including original production, retained byte-identical package hashes.
- All **six previously accepted E3 experimental HEAD histories** remained byte-identical before and after the test. No data from other applications was read or modified.
- The prior Plump v8 app-private-files safety backup passed SHA-256 verification; no save archive was overwritten or restored.
- The user's original YouTube task was returned to the foreground; Android portrait rotation stayed `lock 0`.
- Final Android `/data` available space at the last post-test check **5,003,724 KiB**, about **4.77 GiB**, well above the required 2 GiB floor. No space-filling was performed; minor variation from the immediate device-test receipt reflects other device activity.
- No root, power cut, production writable DLDI, GitHub release, or migration of experimental saves to production.

## Verification and limitations

The first APK had **154/154** E3interrupt JVM tests; the corrected build had **157/157** (three dedicated E3G fault-gate tests), with Android lint and APK/core identity checks passing. **After the device run**, the four default Gradle flavors passed a combined offline build and test run: production **154/154**, E3B **154/154**, default E3C **157/157**, corrected-core E3F **154/154** — **619/619** in total, zero errors, failures or skips. Production debug/unsigned release and all E3B/E3C/E3F test APK assemblies passed, as did lint for every variant. The independent E3A/E3F APK-core identity audits passed; a deliberately invalid attempt to build the production identity through the disposable `e3interrupt` flavor was rejected. All **three** pinned Linux native-core failure regressions also passed, each preserving the original canonical image and rejecting publication (host evidence only, not additional Android proof). The Android phone was not used during these post-device host checks.

Fresh logs and receipts are retained under ignored `app/build/p4-e3g-sync/` (`final-default-regression.log`, `final-default-apk-audit.log`, `final-corrected-core-audit.log`, `final-native-host-faults.log`, `deny-production-alias.log`), including `device-2J7qfOaI/` UI XML, pending and HEAD copies, on-device hashes, and independently extracted FAT image. No `gradlew clean` or device cleanup/reset was used.

This testing injects a **synthetic Java exception in place of `Os.fsync`**, not a failure from the kernel's actual syscall. It does not address real concurrent-allocator races or actual ENOSPC during an in-flight image copy. Those remain distinct engineering gates. No power-loss durability or production virtual-SD release is authorized by this result.
