# Plump P4-E2 — Virtual-SD durability and native storage-health report

## Status

**E2 host acceptance candidate. Production writable DS virtual-SD remains disabled.**

This milestone establishes a host-only transaction boundary between the pinned melonDS DLDI writer and Plump's immutable virtual-SD generations. No APK was installed, no Android device was accessed, no personal save was touched, and no GitHub push was performed.

The pinned melonDS and libretro-common revisions are unchanged. E2 uses local, explicitly recorded patches against those revisions for host verification only.

## 1. Safety model implemented

The production-side Java storage primitive is `VirtualSdGenerationStore`.

Each committed generation contains exactly:

- `melonDS DS/dldi_sd_card.bin`
- a checksummed manifest

No `.idx` sidecar, host-folder synchronization tree, symlink, or unexpected file is accepted.

The transaction is:

1. Verify the current canonical generation and its ROM/core identity.
2. Acquire the per-store exclusive writer lock.
3. Copy the immutable image into a private writable working session.
4. Run the real emulator only against that working image.
5. Require a native DLDI close receipt with zero read/write/close errors, successful flush/close, correct image size, and writable/folder-sync-disabled mode.
6. Re-hash and FAT-validate the working image.
7. Publish a checksummed immutable generation directory using same-filesystem atomic rename.
8. Publish a dual-slot checksummed `HEAD` record and fsync the containing directory.
9. On interrupted work, quarantine the abandoned working session instead of promoting it.

Historical working copies therefore cannot become canonical merely because the emulator wrote data to them.

## 2. Native storage-health boundary

The host E2 melonDS patch exposes a versioned `DldiStatusV1` snapshot and an explicit close operation through the libretro frontend.

The receipt reports:

- image size
- sector and DLDI read/write counts
- read/write failure counts
- first/last storage error
- read-only and folder-sync mode
- dirty state
- flush attempted/succeeded
- close attempted/succeeded

The libretro-common patch also preserves file-close failure information instead of discarding it.

The experimental host core is:

`app/build/p4-e2-work/melondsds_libretro_e2.so`

SHA-256:

`3737f32e0c438a2dd5836abe1786c58ff02e93525239027c5d78b4c6b9df38c9`

The accepted unmodified host core remains:

`e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616`

The E2 patches are retained under `patches/p4-e2/` rather than silently modifying the pinned core provenance.

## 3. Genuine real-core generation test

The reproducible entry point is:

`scripts/test-p4-e2-real-core-generation.sh`

It drives the actual production `VirtualSdGenerationStore` classes and the actual experimental melonDS core against the verified BlocksDS DLDI diagnostic.

Two independent emulator processes completed 180 frames each and explicitly closed the DLDI image before Plump publication.

### Generation 1 → 2

- Native image: 128 MiB
- Sectors written: 534
- DLDI write calls: 18
- Read failures: 0
- Write failures: 0
- DLDI read/write failures: 0
- Dirty: yes
- Flush: success
- Close: success
- FAT validation: pass
- Real diagnostic counter after cold process: **1**

Published generation image SHA-256:

`15e6ec22c9544416a236180333f526f06c5dc83902be2a99e3a14b605ac98a3e`

### Generation 2 → 3

- Native image: 128 MiB
- Sectors written: 532
- DLDI write calls: 14
- Read failures: 0
- Write failures: 0
- DLDI read/write failures: 0
- Dirty: yes
- Flush: success
- Close: success
- FAT validation: pass
- Real diagnostic counter after second cold process: **2**

Published generation image SHA-256:

`ed20b2f24c8cffcb3ef289d77df282802da8acaeb4c979223d49880286dc1ab7`

The original baseline image remained unchanged at counter 0.

This is the decisive E2 result: genuine game-initiated DLDI writes now pass through the native health boundary and only become canonical after successful close, validation, hashing, and transactional publication.

## 4. Combined emulator + owner-process crash recovery

The reproducible entry point is:

`scripts/test-p4-e2-real-core-crash-recovery.sh`

The test deliberately performs the following sequence:

1. Create a canonical generation from the protected baseline.
2. Open a real writable working session.
3. Run the real pinned experimental melonDS core for 180 frames.
4. Abruptly terminate the emulator child before DLDI close.
5. Abruptly terminate the Java owner process while it still owns the store lock and working session.
6. Start a fresh owner process.
7. Re-read the canonical head.
8. Open a new session and verify it is copied from the original canonical generation.

Result: **PASS**.

The abandoned working session was quarantined. The canonical generation remained generation 1 with its original SHA-256. No partially written DLDI image was promoted.

This is stronger than a storage-only fault injection because the real melonDS process was executing against the working FAT image when the owner process was terminated.

## 5. Regression coverage

Current JVM suite:

**120/120 passed, 0 failures, 0 errors, 0 skipped.**

The suite covers, among other cases:

- checksummed manifests and dual-slot heads
- interrupted generation publication
- interrupted head publication
- stale temporary-head quarantine
- corrupt newest generation fallback
- native-close rejection
- symlink rejection
- unexpected `.idx` rejection
- read-only immutable baseline → writable working copy
- branch isolation
- writer locking
- orphan-generation handling
- exact ROM/core identity

The current Android project also passed the previously executed debug/release assembly and lint checkpoint:

- ARM64 debug assembly: PASS
- ARM64 unsigned release assembly: PASS
- Android lint: PASS, 0 errors, 23 warnings

These builds contain the normal pinned production core. They do **not** mean the experimental E2 core has been installed on Android.

## 6. Reproducibility and provenance

The host health implementation is represented by three explicit patches:

- `patches/p4-e2/melonds-dldi-health.patch`
- `patches/p4-e2/libretro-vfs-close-error.patch`
- `patches/p4-e2/libretro-melonds-status-api.patch`

Their SHA-256 values are recorded in the local evidence and can be regenerated from the pinned source trees.

The E1-B regression runner was extended to select a specific host core and verify its SHA-256, allowing the original E1-B real DLDI test to be rerun unchanged against the E2 health instrumented core. That regression passed.

## 7. Important limitations

E2 is **not** a production Android acceptance.

Still unaccepted:

- Android JNI wiring of the native DLDI status/close API.
- Android device execution of writable virtual SD.
- Android filesystem semantics for directory fsync/atomic rename on the target iQOO Neo 6.
- Physical power-loss testing.
- Real Android storage exhaustion.
- Production UI/error handling for a failed virtual-SD commit.
- Public/consumer-facing virtual-SD import/export behavior.
- Production enablement of writable DLDI.

Android's app-specific internal storage is the intended eventual location for Plump-owned persistent virtual-SD data. The Android integration should also use the platform's storage-allocation facilities for preflight/guaranteed allocation rather than relying only on a best-effort free-space query.

The host implementation therefore intentionally does **not** claim that Linux `FileChannel.force()` plus directory fsync proves physical Android power-loss durability. Directory synchronization is part of the host publication protocol, but formal power-loss proof still requires device-specific testing.

## 8. Acceptance boundary

**Recommendation: ACCEPT P4-E2 as a host-only engineering foundation / acceptance candidate.**

The core technical invariant is now demonstrated:

> A real melonDS game can write a FAT16 virtual-SD image, the native layer can prove that the image closed cleanly, and Plump can publish that image as an immutable generation without exposing an interrupted working image as canonical.

The next milestone is Android integration and device validation, not another redesign of the host transaction model.

No phone interaction, APK installation, ADB access, personal-save access, GitHub push, or production writable-SD enablement occurred during E2.
