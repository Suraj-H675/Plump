# P4-E3E — Controlled Android storage-capacity and publication faults

**Date:** 2026-11-21
**Base commit:** `2b403fd61ff3e6294913492f3450ffb7633db6d0`
**Device:** Stock Android 14, iQOO Neo 6 / I2202
**Result:** PASS for the exact Android failure-injection boundaries exercised below. This does not establish hardware power-cut durability or production writable virtual-SD readiness.

## Safety and experimental isolation

The user authorized direct ADB testing and explicitly permitted using free phone storage provided **at least 1–2 GiB remains free**. This milestone imposed a stricter **2 GiB free-space floor**, plus a further 768 MiB minimum margin before image-writing experiments. No actual shared-volume exhaustion, root, filesystem mutation outside the experimental app, device reset, permission alteration, or destructive user-data operation was performed.

Added the literal allowlisted Android application ID `dev.plump.emulator.e3fault` as a third disposable `e3interrupt` package option (distinct from production `dev.plump.emulator`, accepted E3B `dev.plump.emulator.e3test`, E3C `dev.plump.emulator.e3interrupt`, and E3D `dev.plump.emulator.e3rotate`). The fault mode can only activate when **both** the explicitly compiled `E3_DEVICE_FAULT_TEST` flag and the exact test application ID match. Each run displays its selected fault mode before the test. An ordinary build has fault injection disabled. No core SHA, test fixture, baseline image, production writable-DLDI policy, or personal-save path was changed.

Supported test-only launch intent extra: `dev.plump.emulator.E3_TEST_FAULT`; explicitly selected modes are `DENY_LAUNCH_CAPACITY`, `DENY_WORKING_COPY_CAPACITY`, `BEFORE_HEAD_RENAME`, `AFTER_HEAD_RENAME`, `DURING_HEAD_DIRECTORY_SYNC`, and `AFTER_HEAD_DIRECTORY_SYNC`. Only the listed modes below were actually exercised on the device. Fault callbacks are armed for **commit only**, not baseline publication, and fire at most once per run. Capacity probes return a simulated zero at the authorized gate without modifying real free space. The native ARM9 DLDI cartridge still performs the genuine test write before commit-stage failure injection.

## Frozen test artifacts

| Artifact | SHA-256 | Details |
|---|---|---|
| `app/build/p4-e3e-faults/plump-e3fault.apk` | `ee55a961b7d2e8f8f19686b7d74d5401398a092a2933edc1b02ba6fc336ac196` | Initial device test candidate |
| `app/build/p4-e3e-faults/plump-e3fault-detailed.apk` | `e47f7ec6c9869a68d537ebf2a6a2c6a56707dde211d9d81e073a67aba610fc48` | Follow-up build that truthfully displays the store's injected post-commit error alongside `COMMITTED` |

Both use the exact accepted experimental ARM64 core SHA-256 `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`, only CC0 homebrew data, no Internet permission, and Android v2 debug signing. The follow-up APK was installed **only as an update to `dev.plump.emulator.e3fault`**; the authoritative generation-3 HEAD was byte-identical before and after that update. The earlier candidate is retained for reproducibility. Both APKs and all test evidence are Git-ignored.

## Actual physical-device results

| Test | Observation | Verdict |
|---|---|---|
| `DENY_LAUNCH_CAPACITY` | Simulated filesystem capacity 0; required 805,306,368 bytes; `NO_PENDING`; no E3 storage objects created | **PASS** |
| Normal first write | Genuine ARM9 DLDI counter 0 → 1, verified generation 2; pinned seed generation 1 preserved | **PASS** |
| `DENY_WORKING_COPY_CAPACITY` | Simulated capacity 0 when creating 128 MiB working copy; `NO_PENDING`; authoritative generation-2 HEAD and both immutable image hashes exact | **PASS** |
| `BEFORE_HEAD_RENAME` | Genuine emulated write completed, fault at the precise store hook before HEAD rename; `NOT_COMMITTED`; generation-2 HEAD unchanged; unpublished candidate quarantined; pending marker cleared | **PASS** |
| `AFTER_HEAD_RENAME` | Genuine write completed, new candidate HEAD visible but directory sync not confirmed; `RECOVERY_REQUIRED` and native/storage admission blocked; precise checksummed pending record retained | **PASS** |
| Fresh Android process, strict reconcile | Exact candidate/parent/previous HEAD verified, containing directory synchronized; previously uncertain operation classified **COMMITTED** (generation 3); pending marker cleared; original prior generation preserved | **PASS** |
| `AFTER_HEAD_DIRECTORY_SYNC` | Genuine next write completed and HEAD directory sync had already succeeded; injected exception observed on screen as `Store operation detail: E3 device fault injected at AFTER_HEAD_DIRECTORY_SYNC`, while exact candidate reported **COMMITTED**, generation 4 | **PASS** |
| Cold restart, strict reconcile | `No pending E3 transaction was found; no metadata was changed`; final authoritative generation-4 HEAD byte-identical | **PASS** |
| Independent FAT and transaction integrity | Read-only `fsck.fat` accepted extracted generation 3 and 4 images; ARM9 saved binary/text records validated counter 2/previous 1 and counter 3/previous 2 with independent FNV checks; HEAD/manifest/parent/image SHA verified | **PASS** |

The visible fault classifications are **specific to these injected operations**. In particular, `AFTER_HEAD_RENAME` means the HEAD rename returned but directory sync had not been confirmed; the app correctly refused to infer durable success. Conversely, `AFTER_HEAD_DIRECTORY_SYNC` was deliberately injected after the real sync returned successfully, so the exact new committed generation remained authoritative despite the reported later error.

### Final immutable generation chain

| Generation | UUID | Image SHA-256 |
|---|---|---|
| 1 (seed) | `10859775-17ba-4f88-b8b9-30aeb1f23cdb` | `14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e` |
| 2 | `6e67563b-33ea-4014-a8c5-265dbf5ec627` | `3c91fe827b6027d10f40d5bd4626477c2a630e0e4e1a01568e1e8416541f68a1` |
| 3 (cold-reconciled) | `f87dfecc-4232-41ee-bbc6-90cf08277412` | `444485cf4444db4e28b06e0c913751d96e53de148464a4164a0fcb6f9f63393c` |
| 4 (post-sync fault) | `84f3270d-44b1-4fe0-bcd0-19efdbabfb0c` | `21437266f9986c586d8da0ceb404978e708346074f70c67f3b3ecaa86bf3b460` |

All four generations remained immutable, with exact previous HEAD and image digests checked at each failure and cold-recovery step. The two non-baseline images after injected faults were also read as ordinary FAT filesystems from the laptop, independent of the Android UI.

## Space budget and preservation

- **Before testing:** 8,222,348 KiB available on Android `/data` (approximately 7.84 GiB).
- **After testing:** 7,420,292 KiB available (approximately 7.08 GiB).
- Net available space decreased by approximately 783 MiB over the measured test interval; retained disposable seed, generations and quarantine account for most of this, but unrelated phone activity cannot be ruled out. No data was deleted to make room; more than 5 GiB remains above the requested 2 GiB minimum.
- The installed production v8, E3B, E3C disposable and E3D rotation-test APK hashes are identical to the pretest snapshots.
- All previous E3B, E3C and E3D authoritative HEAD files are byte-identical to the pretest snapshots. No data migration, reset, storage cleanup or personal ROM/save inspection occurred.
- The prior Plump v8 backup tar archive still passes SHA-256 verification.
- Original portrait rotation setting remained `lock 0`. The original Android launcher was returned to the foreground after testing.
- No GitHub push, production installation or release was performed.

## Host verification and evidence

The initial fault APK was built, linted, and tested with Gradle offline: **154/154** E3 interrupt variant JVM tests passed; all Android signing, test-only native ELF, package ID, launcher, asset and no-Internet isolation checks passed. The follow-up APK was rebuilt, linted and retested: **154/154** passed.

After the physical test, the unmodified *default* variant selection was rebuilt and regression-tested: **BUILD SUCCESSFUL**, with **154/154** production JVM tests, **154/154** E3B JVM tests, and **154/154** E3C disposable JVM tests; zero failures/errors/skips. Production debug and unsigned release, E3B and default E3C APK assembly and all three Android lint targets passed. The strict APK checker accepted all three correct identities and rejected all three deliberately wrong production/experimental comparisons. The frozen fault APK was independently re-audited. A deliberately invalid Gradle override to `dev.plump.emulator` was denied during configuration.

Separately reran the **genuine pinned Linux melonDS native failure matrix** (not Android): next DLDI write, stream flush and file close injection each aborted publication and preserved the original baseline image and sequence-1 HEAD. This is a regression check, not additional device evidence. No Gradle `clean` was used, preserving ignored safety backups and prior receipts.

**Ignored logs and Android evidence:** `app/build/p4-e3e-faults/` and `app/build/p4-e3e-faults/device-wven3PKW/`, including UI hierarchy results, original HEAD and candidate/pending records, frozen APKs, `final-default-regression.log`, `final-default-apk-isolation.log`, `native-failure-regression.log`, all read-only generation hashes, two device-exported FAT images, read-only filesystem checks, and final device-space receipt.

## Explicitly unverified

This is deterministic, Java-store-level **Android fault injection**, not a physical filesystem failure, thermal cut, device power loss or real low-storage condition. The `DURING_HEAD_DIRECTORY_SYNC` hook was not run on this device; an actual `Os.fsync` returning an error has not been forced at runtime. Native DLDI write/flush/close injected failures were covered previously by host regressions but were not introduced into this phone's core. Storage-availability preflight does not reserve bytes, so actual ENOSPC during a write and concurrent external storage usage still need additional safeguards/testing. No DSi/3DS NAND claims follow from these DS results. Production writable virtual SD remains **disabled** pending further gates.
