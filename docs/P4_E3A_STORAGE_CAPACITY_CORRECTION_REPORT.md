# Plump P4-E3A — Android storage-capacity compatibility correction

**Date:** 2026-11-21
**Base commit:** `03790b3326a4848c04b5a74879602bbb3388048e` (`main`)
**Recommendation:** ACCEPT for host engineering review; physical Android retest pending separate mastermind authorization. Production writable DLDI remains disabled.

## 1. Observed failure and root cause

The mastermind's Android 14 foreground diagnostic evidence, `app/build/p4-e3b-device/foreground-WtZ0qseT/run1-ui.xml`, was inspected read-only. The captured diagnostic reported:

> PLUMP E3A — NOT COMMITTED
>
> SecurityException: getFileStore

The mastermind reported that installation, package isolation and launch passed, but no baseline FAT image, immutable generation or working session was created. Native DLDI execution was not reached. This is device evidence supplied by the mastermind, not a new device experiment by this agent.

The failing launch preflight used `Files.getFileStore(dir).getUsableSpace()`. The same incompatible query appeared in the generation store's baseline initialization and working-copy checks. Android libcore deliberately rejects `getFileStore(Path)` because SELinux restricts complete filesystem information; its Unix filesystem provider explicitly throws `SecurityException("getFileStore")`. There is no need to request storage permissions or relax device security.

References:

- [Android libcore change documenting the deliberate SecurityException](https://android.googlesource.com/platform/libcore/+/c8730653df8aaf1b45682f219b3273e20eb47fc5%5E1..c8730653df8aaf1b45682f219b3273e20eb47fc5/)
- [Android StatFs reference](https://developer.android.com/reference/android/os/StatFs#getAvailableBytes())
- [Android StorageManager reference](https://developer.android.com/reference/android/os/storage/StorageManager)

## 2. Narrow correction and call-site audit

Added `StorageCapacity.java`, an injectable, platform-neutral capacity gate. Linux host callers use `File.getUsableSpace()`, for which zero cannot authorize a positive-size copy. The Android experimental activity explicitly injects two independent probes: `StatFs.getAvailableBytes()` for the actual app-private filesystem and `StorageManager.getAllocatableBytes(getUuidForPath(...))` for Android's allocation preflight. The former is available from API 18, the latter APIs from API 26, matching the application's minimum SDK.

| Old failing call site | Corrected check |
|---|---|
| E3TestActivity.java:270 (old revision) | Both probes must satisfy the existing 768 MiB launch threshold, before E3 baseline creation. |
| VirtualSdGenerationStore.java:254 (old revision) | Both Android probes must satisfy the validated baseline image length immediately before copying it into an immutable generation. |
| VirtualSdGenerationStore.java:293 (old revision) | Both Android probes must satisfy the committed image length immediately before creating the isolated working copy. |

The Android policy is passed as an explicit constructor dependency into the store. Insufficient capacity, negative capacity, unavailable service, denied access, unsupported volume, and query exceptions are rejected. Query exceptions retain their cause in an IOException with operation-specific context. Neither a high allocatable value nor a successful second query can bypass insufficient physical capacity. No unlimited fallback, cache eviction, storage reservation or new permission was introduced. Preflights remain advisory rather than allocations; actual filesystem failures must still be handled transactionally.

A repository-wide source search found one additional executable `Files.getFileStore` reference in the existing one-argument `CheckpointStore(Path)` host convenience constructor. The Android GBA and DS activities actually call the injected four-argument constructor with `StatFs`, and E3 does not construct a CheckpointStore. That host-only constructor was left unchanged. No active E3 capacity path now calls `Files.getFileStore`.

## 3. Other filesystem APIs audited

Reviewed the experimental activity, generation store, FAT16 validator/record reader, and session receipt storage. App-private path resolution, NOFOLLOW_LINKS, symlink checks, ordinary directory creation/enumeration, file sizes, bounded reading, copying and hashing use Android's java.nio.file or java.io APIs. FileChannel performs ordinary file reads/writes, locking and force. The generation store continues to require atomic rename and injected Android parent-directory synchronization with Os.open/Os.fsync. SessionReceiptStore retains its existing FileChannel directory-force path. No filesystem-provider fallback weakens atomicity; errors still abort publication. These APIs are available on the target Android 14 platform, but their behavior on the target filesystem is not established by this source audit. In particular, native close, force, directory sync, atomic move, actual free-space reporting and lifecycle behavior still require the previously authorized-scope device retest to be authorized anew.

No changes to JNI, native engine, health receipt semantics, FAT validator, file formats, generation selection, transaction publication, 128 MiB image bounds, three-write demonstration cap, game fixtures, production application ID or DLDI enablement. No personal saves, installed package data or backup accessed.

## 4. Final verification

Ran the following host-side Gradle command with Android SDK configured and offline dependencies:

```sh
./gradlew --offline --no-daemon \
  :app:testProductionDebugUnitTest :app:testE3testDebugUnitTest \
  :app:assembleProductionDebug :app:assembleProductionRelease \
  :app:assembleE3testDebug :app:lintProductionDebug :app:lintE3testDebug
```

**BUILD SUCCESSFUL**; 158 actionable tasks, 48 executed and 110 up-to-date.

| Verification | Result |
|---|---|
| Production debug JVM suite | 136/136, 17 suites; zero failures/errors/skips |
| Experimental debug JVM suite | 136/136, 17 suites; zero failures/errors/skips |
| Six StorageCapacityTest cases | PASS: exact threshold, zero/low physical capacity, allocation rejection, denied filesystem query, unavailable allocation query, negative capacity |
| Two added VirtualSdGenerationStoreTest cases | PASS: refused bootstrap cannot publish; refused working copy cannot advance the head; retained bytes, lock release and retry verified |
| Production debug / unsigned release / E3 debug APK assembly | PASS |
| Production debug lint | 0 errors, 20 warnings |
| Experimental debug lint | 0 errors, 23 warnings |
| APK separation, exact core/ROM/baseline hashes, JNI symbols, launcher, permissions | PASS for all three artifacts; 3 negative identity substitutions rejected |
| E3 debug APK signature | PASS, APK Signature Scheme v2, Android Debug certificate |
| P4-C pinned mGBA/melonDS host JNI and checkpoint regression | PASS, exit 0 |
| E1-B original pinned DLDI writer | PASS, exit 0, genuine write and three cold-process progressions; ROM/branch separation and original image preservation |
| Java FAT16 checker against actual game-written image | PASS, counter 3/previous 2; corrupted alternate FAT replica rejected |
| E2 real pinned native write/flush/close failure matrix | PASS, 3/3, each retained original canonical sequence and image SHA |
| E2 genuine real-core generation publication | PASS, generations 1→2→3 |
| E2 abrupt separate-process owner exit and recovery | PASS, owner exit 87 as expected, new recovery process exit 0 |

The one additional lint warning in each flavor is Android lint's UsableSpace suggestion for the Linux-host-only `StorageCapacity.host()` factory. Android execution already explicitly uses both platform probes. No lint suppression was added.

The unmodified legacy DS host core with SHA-256 `e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616` was used for P4-C and E1-B via the existing core overrides. The accepted E2 Linux core SHA-256 is `3737f32e0c438a2dd5836abe1786c58ff02e93525239027c5d78b4c6b9df38c9`.

Logs are retained in ignored `app/build/p4-e3a-capacity-correction/`: `gradle.log`, `apk-isolation.log`, `apksigner.log`, and `regressions/{p4c,e1b,fat,native-faults,e2-generation,e2-crash-owner,e2-crash-recover}.log`. E2 disposable generation and crash roots in that same regressions directory are unique and retained. The E1-B evidence root is `app/build/p4-e1-b/runs/acceptance-z48uSf0P/`.

## 5. Frozen corrected candidate — do not install yet

**APK:** `app/build/p4-e3a-capacity-correction/plump-e3test-capacity-corrected.apk`

Byte-identical copy of `app/build/outputs/apk/e3test/debug/app-e3test-debug.apk`.

**APK SHA-256:** `0a121f5a5e12eabd2bc98338250dfcbb49573928e6527ae11e251d8e2baf02e7`

**Size:** 3,638,404 bytes.

**Application ID:** `dev.plump.emulator.e3test`. Production remains `dev.plump.emulator`.

**Android debug signing certificate SHA-256:** `c48b3d3b6e5913013476b3bf8f9de667dac6121a6c8e713da3ce986d7b09e016`. Signature verified on the frozen copy itself.

**Unchanged experimental ARM64 core SHA-256:** `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`.

**Unchanged production ARM64 melonDS core SHA-256:** `83e7b9389083576d70c05466911f4796a8099bc1b43f5d56381c493c0d950c30`.

The previous E3A candidate SHA-256 `11e4f6a934ff51e29c3838d1b3b9262ccddae4e70cc18c16444a30320691e541` is superseded for future review; it is not the corrected artifact.

## 6. Review boundary

The observed Android-incompatible API was removed from all three E3 call sites; the new build is a verified host candidate, not proof that the Android foreground run now succeeds. No ADB interaction, installation, application update, personal-save inspection, core revision change or GitHub push. The pre-existing modified P3 report and untracked P4 engineering plan remain outside this correction. The local commit identity and final working-tree status are stated in the engineering handoff. Stop at mastermind review and require separate authorization for the exact corrected experimental APK's device retest.
