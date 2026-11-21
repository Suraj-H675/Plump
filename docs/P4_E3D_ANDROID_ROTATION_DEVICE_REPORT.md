# P4-E3D — Isolated Android rotation and activity-recreation check

**Date:** 2026-11-21
**Starting commit:** `9a19423cfb83fa6d9533bfbc00840c4240707934`
**Device:** iQOO Neo 6 / I2202, stock Android 14, connected via scoped ADB
**Verdict:** PASS for the observed foreground-rotation cancellation and cold-recovery paths; NOT proof of physical power-cut durability or a rotation precisely during native DLDI close.

## Isolation and build

The previously accepted disposable `dev.plump.emulator.e3interrupt` already has its own four-generation history. Its data was not reused or reset. Instead, the `e3interrupt` Gradle flavor permits one additional, explicitly allowlisted application ID `dev.plump.emulator.e3rotate` through `-PplumpE3DisposablePackage=dev.plump.emulator.e3rotate`. The original `dev.plump.emulator.e3interrupt` remains the default. Production (`dev.plump.emulator`) and the accepted E3B package (`dev.plump.emulator.e3test`) are not accepted as overrides; the production-package override was tested and rejected during Gradle configuration.

The same pinned experimental ARM64 core (`9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`), CC0 DLDI writer and 128 MiB FAT16 seed were reused without native changes. Both BuildConfig package identity and APK manifest identity were checked. This is another independent Android sandbox, **not** a production feature or player-save migration.

Build command:

```sh
./gradlew --offline --no-daemon --console=plain \
  -PplumpE3DisposablePackage=dev.plump.emulator.e3rotate \
  :app:assembleE3interruptDebug :app:testE3interruptDebugUnitTest \
  :app:lintE3interruptDebug
```

**Results:** BUILD SUCCESSFUL; 154/154 JVM tests, zero failures/errors/skips; Android lint completed. APK identity, native ELF, assets, JNI exports, no Internet permission, backup-disabled manifest, and v2 debug signature checked independently. Three invalid APK app-ID substitutions were rejected, plus the Gradle production-ID override test.

**Frozen ignored APK:** `app/build/p4-e3d-rotation/plump-e3rotate.apk`

**SHA-256:** `092da70bbac81b2381fbcef2ab6528080a42bb2037a4c39dc346840cdef8eee8`

## Real-device procedure and observations

Before installation, captured all existing three Plump package APK digests, the E3B/E3C HEAD.0 files, original foreground application and the user's orientation state (`lock 0`, auto-rotate disabled). After installing *only* `dev.plump.emulator.e3rotate`, the following bounded runs were made.

| Procedure | Observed result | Scope-limited verdict |
|---|---|---|
| Fresh package: tap Run; set Android display rotation to landscape ~1.2 seconds later; restore portrait | New test activity view; `NO_PENDING`, cancelled before publication authorization; no HEAD existed | PASS |
| Retest normally after rotation cancellation | Genuine DLDI record 0 → 1; immutable generation 2 committed, correct baseline parent | PASS |
| Starting from generation 2, tap Run; rotate ~1.4 seconds later; restore portrait | `NO_PENDING`, same generation-2 HEAD bytes and image SHA; cancelled working image quarantined; no pending marker | PASS |
| Explicit `Reconcile exact pending transaction` | "No pending E3 transaction was found; no metadata was changed" | PASS |
| Force-stop and cold-start **only** the rotation-test package; Run again in portrait | Genuine DLDI record 1 → 2; immutable generation 3 committed with generation 2 as exact parent | PASS |
| Independently extract final 128 MiB image via `run-as` and `tar`; inspect using read-only `fsck.fat` and PyFatFS | FAT valid; `A001.BIN` checksum coherent, `A001.TXT` `PREVIOUS=1`, `COUNTER=2`, `CHECKSUM=60868817` | PASS |
| Verify final HEAD, manifest, image, exact parent and older hashes | All cross-hashes and parent IDs consistent | PASS |

### Final rotation-test generations

| Sequence | Generation ID | Immutable image SHA-256 |
|---|---|---|
| 1 | `20bc3c39-7ff7-4d70-81ae-64341a498a88` | `14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e` |
| 2 | `efb7bbd4-5836-49e2-acde-6480dcfd76b0` | `70d2168b4830ccf3597fb4f50b6682655d422f5fb8755a0f1771d5fc17f08e96` |
| 3 | `2a94cb32-430d-42e9-a1b3-ff28e883a632` | `fe2a7728902fce9f1e0f898b3ce402a6af2ff245413ce6f95ba4354056d8fac4` |

## Existing data preservation and final environment

- The original production APK, accepted E3B APK, and accepted E3C interruption-test APK were byte-identical to their pretest digests.
- Existing E3B and E3C HEAD.0 records were byte-identical before/after this test; no personal game saves were accessed.
- Prior private Plump v8 archive SHA-256 check passed.
- The original system rotation setting (`lock 0`) was restored and verified.
- The original YouTube foreground task was returned to the front after testing.
- Approximately 7.8 GiB free remained on `/data` at the end; deliberately filling the phone was **not** attempted.
- No other project, core revision, production writable DLDI setting, release, GitHub remote or personal save was changed.

**Ignored evidence root:** `app/build/p4-e3d-rotation/device-UksGZzBH/`, including both UI XML snapshots, HEAD/manifest readbacks, original/after APK digests, quarantine observation, tar-extracted final FAT image, read-only FAT scan and game-level record verification.

## Limitations and next gate

The Android orientation changes were initiated at observed wall-clock delays, **not by a deterministic native phase hook**. They caused real activity recreation and cancellation, but do not prove interception specifically inside ROM load, `NativeDs.frame`, DLDI close, atomic HEAD rename or directory fsync. The cancelled runs are therefore not claimed as evidence for those exact boundaries. Display rotation was restored after each test; no global rotation setting remains changed.

Remaining work before production writable virtual SD includes targeted Android fault injection at exact transaction boundaries, safe simulated capacity denial (not filling shared device storage), and broader activity/surface tests under genuine gameplay. Hardware power-cut durability is still unverified. Preserve all existing test generations and require a fresh isolated identity for experiments that need additional writes.
