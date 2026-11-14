# Plump P4-A — native-save durability hardening report

**Date:** 2026-11-14

**Scope:** P4-A only. **Engineering outcome:** implemented, built, host/JVM verified; **not tested on the owner's phone; mastermind acceptance pending**.
**Basis:** `docs/P4_ENGINEERING_PLAN.md`, the authoritative project source pack, current P2/P3 evidence, pinned mGBA and melonDS source, and reproducible synthetic licensed fixtures.

## 1. What changed and what did not

| File | Purpose |
|---|---|
| `app/src/main/java/dev/plump/emulator/GameSaveStore.java` | Preserve existing PLSV v1 records and two-generation journal; select a target by actual valid slot rather than generation parity; refuse writes over the only recovery evidence; provide typed write failures, deterministic filesystem failpoints and a separate, checksum-protected **positive-history receipt**. |
| `app/src/test/java/dev/plump/emulator/GameSaveStoreTest.java` | Deterministic corruption, interruption, rename/fsync, receipt, identity, length, generation-limit and slot-safety tests. |
| `app/src/main/java/dev/plump/emulator/SaveRamSizeValidator.java` | Check complete GBA native-save lengths; permit one narrowly verified P2 legacy SRAM autodetection case. |
| `app/src/test/java/dev/plump/emulator/SaveRamSizeValidatorTest.java` | Exact 32/128 KiB tests and negative mismatch tests. |
| `app/src/main/java/dev/plump/emulator/NativeCore.java` | Validate restored GBA save bytes against the loaded core's reported save region before calling JNI. |
| `app/src/main/cpp/plump_host.cpp` | Require exact native-size match except **32 KiB SRAM → pinned mGBA's 128 KiB AUTODETECT buffer before the first frame**; initialize the remaining autodetect buffer to erased `0xFF`. Reject that exception after execution has started. |
| `app/src/main/java/dev/plump/emulator/MainActivity.java` | Require the same validated length relationship during pre-frame native-memory readback, not any arbitrary larger buffer. |
| `tests/roms/verify-p4-a-gba-host.py` | Reproducible two-process mGBA host test; admits only the exact licensed upstream GBA SRAM ROM SHA and writes only inside ignored `app/build/`. |
| `docs/P4_A_TECHNICAL_REPORT.md` | This evidence and known-limitations ledger. |

**Unchanged:** PLSV v1 magic/version, 84-byte header layout, two slot filenames (`<ROM SHA>.[01].sav`), save payloads, separate GBA/DS storage directories, pinned core revisions, DS JNI/core implementation, DS save-state disabled behavior, rendering/audio/input loops, Android permissions, app version code, and the accepted test ROMs. No database, branch manager, state UI or new engine was added. Existing uncommitted `docs/P3_TECHNICAL_REPORT.md` and untracked `docs/P4_ENGINEERING_PLAN.md` are intentionally excluded from this change.

## 2. Durability and recovery contract

- **Slot safety:** find the actual valid newest record and replace the *older or invalid* slot; with only one good record, write the other slot. Parity mismatches cannot overwrite the sole usable generation. Generation `Long.MAX_VALUE` refuses rollover, preserving old bytes.
- **PLSV v1 compatibility:** record encoding stays big-endian `PLSV` magic, format `1`, generation, payload length, game SHA-256, payload SHA-256 and bytes. Old files are read without modification or receipt-based migration. A new `.history` receipt sits **beside** legacy slots and contains only receipt magic/version, game SHA and its own checksum. It is positive evidence that a save record has existed, **not a third save copy or proof that every associated write was acknowledged successful**; a post-rename fsync failure can leave a readable but unconfirmed receipt. It cannot reconstruct missing bytes.
- **Never-saved versus missing:** no save slots and no independently valid history receipt means `UNKNOWN`, never a fabricated claim of “never saved.” A valid receipt plus no slots gives `KNOWN_MISSING` and blocks overwriting historical recovery evidence. Corrupt existing slots produce `CORRUPT`. Valid surviving slot produces `AVAILABLE`, with damaged-copy warning where appropriate.
- **No false write success:** data is flushed, file descriptor synced, atomically renamed, then parent directory sync is checked. Typed errors distinguish invalid length, damaged history, known missing data, generation exhaustion, interrupted write, storage-full, I/O, atomic publication and durability sync. A rename followed by a failed directory fsync **throws**, even though the new file might be visible. A valid older independent copy remains available.
- **Initial history receipt:** creating a verified `.history` is part of the first successful write of a game. Failure to publish or directory-sync the receipt causes `write()` to report failure, even if the save record was already renamed. A retry uses the valid record and can repair the receipt. The receipt is **positive evidence only**; losing both pre-P4-A legacy saves with no receipt cannot prove historical existence.
- **GBA autodetection:** an early exact-size-only change was rejected during review: the *pinned* mGBA core reports `131072` bytes at ROM load, but a genuine licensed SRAM cartridge resolves to `32768` bytes after emulation. Requiring pre-first-frame equality would break accepted P2 cold restore. The final implementation accepts only the exact legacy `32768 → 131072` pair **before the first core frame**; all other mismatches reject. JNI enforces the rule again and pre-fills the remainder with `0xFF`. This is not a general truncation exemption.

## 3. Deterministic JVM failure matrix — PASS

**Command:**

```bash
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
  ./gradlew --no-daemon --console=plain :app:testDebugUnitTest --rerun-tasks
```

**Result:** 40 tests, **0 failures, 0 errors, 0 skipped**; Gradle `BUILD SUCCESSFUL`.

| Fault or invariant | Evidence tested |
|---|---|
| Half-written temp, interrupted operation | `InterruptedIOException` typed `INTERRUPTED`; original good slot byte-identical; next slot unpublished. |
| Storage full / I/O | Inject `ENOSPC` and `EIO`, check typed `STORAGE_FULL`/`STORAGE_IO`, old save still loadable. |
| File sync and directory sync | Inject errors at each boundary; no successful return, older generation preserved. |
| Before/after atomic move | Before move: candidate absent. After move: `write()` fails despite new file being readable; older generation intact. |
| Receipt partial, fsync, pre/post move, directory sync | All fail without claiming durable success; record still readable and later healthy write can repair valid receipt. |
| Truncated generation / checksum damage | Select intact earlier generation; warning emitted. With both damaged, refuse write and preserve both corrupted originals. |
| Missing both / known missing / invalid receipt | Unknown when unproven; known missing when valid history exists; damaged receipt warning and fail-closed write. |
| Legacy format / two-slot selection | Independently encoded PLSV v1 loads without byte change; generation/slot parity mismatch writes the *other* slot. |
| Invalid identity, length and generation rollover | Wrong SHA/length refused, game identity path-safe, `Long.MAX_VALUE` generation never wraps or replaces good data. |
| mGBA native length | Exact 32/128 KiB accepted; narrowly verified 32→128 KiB autodetection accepted; shorter/oversized and unrelated 64 KiB-to-128 KiB rejected. |

Tests use `TemporaryFolder`, injected filesystem exceptions and deterministic synthetic bytes. They are **failure-injection tests**, not evidence that power was physically removed during an Android flash write.

## 4. Byte-for-byte disposable legacy save compatibility — PASS

An independent Java harness created *fresh disposable* PLSV v1 files under ignored `app/build/p4-a-work/disposable-v1-hash-proof/`. The new store loaded each, verified the original generation/bytes, wrote a successor, and independently compared the original slot's complete file SHA-256 before and afterward:

| Original PLSV v1 payload | Original file SHA-256 before | Original file SHA-256 after | Result |
|---|---|---|---|
| 32,768 bytes | `9f39fa76d420d00e784df40b6afb883cd1e9644447c79f23ecf182d5de8bfe1c` | same | Original unchanged; newer independent slot generation 2 |
| 131,072 bytes | `94cb3a056ca565049395921151934e84e758b640e78fb4ee5ba8ec974581a72c` | same | Original unchanged; newer independent slot generation 2 |

No personal save files or phone data were read or modified. This proves byte-level compatibility with independently encoded original-format records in these two legacy sizes; it does **not** claim live-device migration was tested.

## 5. Real pinned emulator host tests — PASS

**GBA mGBA:** cloned pinned upstream mGBA `d58ea8a86f30598fbf3958585de3d33028535072` into an ignored build directory, built Linux `mgba_libretro.so` (SHA-256 `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c`). Fixture is the existing licensed MIT `gba-tests/save/sram.gba`, SHA-256 `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`. Two **independent Python processes** used `tests/roms/verify-p4-a-gba-host.py`:

```text
first: mGBA SRAM size at load = 131072; after 200 real emulated frames = 32768
PASS FIRST: game wrote real SRAM, payload SHA256
  153085c624624d900f5856be1c95fefb00c97086c03b509d8544c0ade970783b
cold:  mGBA SRAM size before first frame = 131072
PASS COLD: restore 32768-byte legacy data before any frames, exact bytes match
PASS COLD: 24 real frames executed; core resolved to 32768-byte save region
          and preserved the original payload bytes
```

The host harness exercises the **same Libretro memory API and exact pre-frame memory semantics**, while Android JNI itself was compiled and separately size-validated, **not executed on a physical phone**. The runtime test also exposed and prevented the initial incorrect strict-only restore rule.

**DS melonDS:** pinned genuine melonDS Libretro DS core and original CC0 synthetic `p3_save_diagnostic.nds` ran in two independent Linux processes using `scripts/test-p3-native-save-diagnostic.sh`. First process wrote EEPROM through real DS SPI; second restored the complete 8,192-byte cartridge region before frame 1 and the running ROM detected the prior record. The separate host regression loading an old DS state still reproduced all-zero EEPROM rewind; **DS state loading remains disabled**.

**Other regressions:** `scripts/test-rgb565-renderer.sh` passed real GBA RGB565 conversion/stride; `scripts/test-nds-frame-split.sh` passed both DS display splitting; `scripts/test-p3-input-diagnostic.sh` passed exact stylus coordinates, A+B simultaneous input and actual PSG audio callbacks in the genuine core. No emulation renderer, audio or input code was changed.

## 6. Android build, safety boundary and known limitations

**Command:** `./gradlew --no-daemon --console=plain :app:assembleDebug :app:lintDebug` with the existing Android SDK environment. **Result:** BUILD SUCCESSFUL, ARM64 JNI compiled and lint completed. The generated debug APK was **not installed or released**; existing device version 6 and all existing app data were not touched in P4-A.

**Limitations deliberately not claimed as closed:**

- No P4-A physical-device install/revalidation was authorized. The new GBA JNI legacy-exception path is **host-semantics tested and Android-compiled**, not on-device verified; a separately approved disposable-fixture phone test is the next higher-assurance gate.
- Fault injection is at deterministic Java I/O boundaries, not genuine power-cut / flash-controller durability, kernel-terminated process during fsync, or storage-fault simulation on an Android filesystem. An after-rename sync failure returns an error even if the new file is visible; no durable-success claim is made, and the surviving older independent slot remains protected.
- The history receipt is a record of a previously acknowledged save, not a backup of the bytes; older PLSV files without receipts preserve `UNKNOWN` if every generation disappears. No retroactive evidence of prior-save existence is invented.
- This only hardens cartridge native SRAM/EEPROM. Homebrew virtual-SD storage, DS-07 core upgrades, state/native branch isolation, multi-file NAND, session ownership, SAF backup/restore and Android process-death recovery remain P4-B through P4-H work; no implementation is claimed here.
- Test harness builds a **Linux** pinned core, not a new Android emulator revision. A successful hash or cold core memory restoration does not claim broad retail-game compatibility or completion of the P4 save-state milestone.

**P4-A engineering gate:** **PASS at authorized host/JVM/build scope**, pending mastermind review and any later separately authorized on-device regression. No P4-B, P4-C, P4-E or 3DS implementation was started. No GitHub push was performed.
