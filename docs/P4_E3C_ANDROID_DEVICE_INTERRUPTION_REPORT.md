# P4-E3C — Isolated Android lifecycle and process-death evidence

**Date:** 2026-11-21
**Engineering baseline:** `2bf1ca9c53d97e2cd52bbf4870508dd6bee737e3`
**Scope:** User-approved, disposable application test on iQOO Neo 6 (Android 14), not production enablement or a power-loss durability acceptance.

## Evidence-preserving test package

The existing `dev.plump.emulator.e3test` application contains the accepted P4-E3B sequence-4 history and intentionally refuses a fourth diagnostic write. Rather than clear it or reset the data, this change adds a **distinct** experimental Gradle flavor with package ID `dev.plump.emulator.e3interrupt`, separate Android UID/private storage, and launcher label **Plump E3C • DISPOSABLE**.

The disposable flavor uses the exact E3C test Activity, E2-patched ARM64 core, licensed BlocksDS Alpha cartridge, and pinned 128 MiB FAT16 seed. Both test flavors bind their **literal** expected Android package identity into BuildConfig, checked before any operation. Production continues to use its original app ID, native ELF, default-disabled writable DLDI, and separate source set. No network or backup permission was added. The new APK was independently inspected for its launcher, app ID, ABI, test-only assets, JNI exports, absence of Internet permission, `allowBackup=false`, and v2 debug signature. Wrong production/E3B IDs were explicitly rejected by the host APK audit.

**Frozen disposable APK:** `app/build/p4-e3c-interrupt/plump-e3c-disposable.apk` (ignored generated artifact)

**SHA-256:** `40a0510fbfd4f3df48e20b99a449ad3d35df08e4373794f271ea55e58e07d939`

**Exact experimental core ELF SHA-256:** `9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9`. No upstream core changes.

## Android observations

Each experiment used only `dev.plump.emulator.e3interrupt` and the embedded CC0 DLDI cartridge. Disposable records were read through this debuggable package's own private storage. All test UI captures, manifest/HEAD copies, native-generated image digests, independent FAT inspection, and result receipts are under ignored `app/build/p4-e3c-interrupt/device-6LAoJcQs/`.

| Test | Physical-device observation | Result |
|---|---|---|
| Install and cold launch | New package installed without replacing either existing Plump package; standalone E3C launcher displayed | **PASS** |
| Initial foreground write | Genuine native DLDI counter 0 → 1, committed immutable generation 2, exact parent generation 1, image checksum verified | **PASS** |
| Immediate Home interruption | `NO_PENDING`; generation 2 unchanged; no pending transaction or new authoritative generation | **PASS for observed interruption** |
| Delayed Home interruption (1.5 s) | `NO_PENDING`; generation 2 unchanged; aborted working image moved into `quarantine/cancelled-session-*` | **PASS for observed interruption** |
| Explicit strict reconciliation | "No pending E3 transaction was found; no metadata was changed" | **PASS** |
| Cold process restart following cancellation | Next native DLDI counter 1 → 2, generation 3 committed; earlier image digests intact | **PASS** |
| Abrupt disposable process `am force-stop` during next attempted write (1.5 s) | No generation-4 HEAD; verified generation 3 remained authoritative. An abandoned working session was present for cold recovery | **PASS for observed stop boundary** |
| Cold process restart and next write | Abandoned session quarantined, real counter 2 → 3, immutable generation 4 committed and prior generations preserved | **PASS** |
| Independent final FAT + in-game record inspection | Host read-only FAT check accepted extracted 128 MiB Android image; ARM9 A001.BIN/TXT record had `PREVIOUS=2`, `COUNTER=3`, valid FNV checksum `105A4906` | **PASS** |
| Final HEAD / manifest / image verification | Exact HEAD checksum, manifest SHA and parent UUID; published image bytes SHA match; all four immutable generations retain original hashes | **PASS** |

### Disposable generation identities

| Generation | ID | Image SHA-256 |
|---|---|---|
| 1 (baseline) | `fa24cab5-930b-4a34-ab64-40cb11eea17e` | `14788027f3601eafd227b15fd1ce50563f134d82ba47d3746b5f72d84539bd8e` |
| 2 | `e30e0823-db39-4d69-89ac-cc7dbd43283a` | `a9ba66a6d6dce39ffc6a6faa0581e13c4f39b455b7f6380cdac20b1f8bbde016` |
| 3 | `8554751e-8902-415e-81a8-e79776b6401c` | `0f5a3bdbf791b664d60aefab4d1ccc80c4b31d26dcdb90519d1125c253e4c348` |
| 4 | `cd920e5d-0535-4095-b409-56bbfc4dc1b0` | `760b4d808b082180addb320693b6ac88927017d6acbaad8ce8cfbe9306b6c32d` |

## Isolation and preservation

- Original installed `dev.plump.emulator` APK remained byte-identical: SHA-256 `8be596f0258579fcc8463243fc20ccbb27b77eb33a9a6270159667bc2645e38c`.
- Accepted E3B app `dev.plump.emulator.e3test` remained the previously accepted capacity-corrected APK: SHA-256 `0a121f5a5e12eabd2bc98338250dfcbb49573928e6527ae11e251d8e2baf02e7`.
- The accepted E3B HEAD at sequence 4 was read only and compared byte-for-byte before and after all interventions; it was **unchanged**. The existing backup archive's SHA-256 check passed.
- The original test app's private data was never cleared, reset, migrated, overwritten, or used as failure-injection input. Neither app's personal game saves were read.
- No production APK was installed, no release created, no GitHub push, no new upstream core, no production writable DLDI.

## Host verification and remaining limits

Built and linted `:app:assembleE3interruptDebug`, `:app:testE3interruptDebugUnitTest`, and `:app:lintE3interruptDebug` with offline Android Gradle. All **154/154** disposable-flavor JVM tests passed, no skips/errors/failures. The independent APK audit and signer checks passed. E3C's original production/E3test 154-test suites and their APK audit passed separately before this isolated flavor was added; final package assembly/regression checks are recorded in the ignored build logs.

This evidence proves the tested Android foreground, cancellation, verified cold-recovery, and abrupt-process-stop behaviours **at the observed timing only**. A Home interruption is not a hardware power cut; the forced stop was not injected at a proven native write/HEAD rename instant. Physical power-loss durability, precise every-boundary Android rename/fsync fault injection, low-storage behaviour, rotation during native load, and 3DS/DSi saves remain unverified. Do not treat this as authorization to enable production writable DLDI or to reset the preserved E3B/disposable histories.
