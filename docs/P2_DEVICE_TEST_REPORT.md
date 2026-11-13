# P2 — GBA Android device-test report for mastermind review

- **Date:** 2026-11-13
- **Status:** P2 bounded device tests completed; **mastermind acceptance pending**.
- **Device:** iQOO Neo 6 (I2202), Android 14 / API 34, ARM64.
- **App:** `dev.plump.emulator`, Android `0.1.3-p2-sram-verify` (versionCode 4).
- **APK SHA-256:** `ca4cf8795ad47d1a030c737cae7af8592b8916df2f04bd2ff98149ce185bc656`.
- **Core:** in-process mGBA Libretro, pinned upstream `d58ea8a86f30598fbf3958585de3d33028535072` (MPL-2.0).
**Source of device observations:** owner-reported touchscreen, image and audible output, cross-checked against Plump-process logcat, APK metadata, and read-only inspection of Plump's sandbox. No claim of automated visual/audio measurement.

## Gate outcomes

| Gate | Actual device evidence | Result |
|---|---|---|
| GBA-01 — actual emulation | Android file picker loaded MIT `hello.gba`; owner saw genuine in-game **“Hello world!”**; the mGBA video callback ran continuously | **PASS for test ROM** |
| GBA-02 — 3:2 video | Native 240×160 framebuffer, 1080×720 phone gameplay view; owner saw the full text without reported crop. Initial black screen was fixed by removing the solid `SurfaceView` background and rendering as RGBA8888 | **PASS for this layout/test** |
| GBA-04 — touchscreen buttons | Original ARM7TDMI test ROM polls actual GBA KEYINPUT; owner confirmed A/red, B/blue, A+B/yellow (two fingers), Up/green, R/white, L/yellow, Start/magenta, Select/gray, Left/orange, Right/purple, Down/cyan, and released gray | **PASS for tested button responses and A+B chord** |
| GBA-05 — actual audio | Original ROM produces PSG channel-1 tone while A is held and silence on release; owner reported audible tone and correct stop; host mGBA measured zero audio peak when idle and 11,520 peak during A | **PASS for audible tone/on-off; sustained synchronization/underruns unmeasured** |
| GBA-06 — real native save | `sram.gba` displayed **“All tests passed”**; two independent 32,768-byte SRAM records validated (magic, ROM identity, generations and SHA-256), with exact cartridge-written values. Following Plump-only `am force-stop` and **cold app launch**, loading the same ROM logged `Native SRAM restore readback VERIFIED: generation=2 bytes=32768` *before* running frames | **PASS for licensed SRAM fixture** |
| GBA-08 — Android lifecycle | Owner tapped Pause/Resume, Home and Recent Apps; frame counter resumed and **“Hello world!”** remained visible. Plump-only force-stop/cold start also used for save test | **PASS for tested flows** |
| Corruption recovery — supplemental | Only disposable `p2-input-audio.gba` SRAM generation 5's checksum was invalidated after backing up both generations. After cold launch and owner-opened ROM, log recorded `Native SRAM restore readback VERIFIED: generation=4 bytes=131072`; owner tested controls. Subsequent save generations 6 and 7 validated. Both original pre-test test-save generations were restored byte-for-byte and verified against the backups | **PASS for one corrupted newest-generation case** |

P2 specifically requires GBA-01/02/04/05/06/08. The device tests above exercise every **bounded P2 criterion**, but do not establish the full product specification or broad cartridge compatibility. **Mastermind decides acceptance.**

## Evidence and reproduction

- `hello.gba`: `38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264`.
- `sram.gba`: `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`.
- Both are from MIT-licensed `jsmolka/gba-tests` at commit `a7113b67e63f83a9b321696ddd7042ccfad6c881`.
- Original Plump `p2-input-audio.gba`: `b9ec1db3f1e0c91a4019e2058de0b08b6468bf7896baa9dc3279988fd6a0235a`. Built reproducibly by `./scripts/build-p2-input-audio-rom.sh` from original tracked ARM assembly, using the hash-verified MIT test ROM's standard 192-byte cartridge header. It is **not bundled in APK or committed as a binary**.
- All three ROMs lived only under isolated `/sdcard/Plump-P2-Test/`, hash-checked after transfer. Application data stayed within `dev.plump.emulator` sandbox. No user game files or other apps were inspected.
- Phone-specific log proof for successful cold SRAM load:

  ```text
  I/Plump: Native SRAM restore readback VERIFIED: generation=2 bytes=32768
  ```

- Phone-specific log proof for recovery of an older checksum-valid generation after corruption of the newer:

  ```text
  I/Plump: Native SRAM restore readback VERIFIED: generation=4 bytes=131072
  ```

- In both cases Plump compared **all saved bytes** against the SRAM exposed by the *actual running mGBA core*, before new emulation frames. The original test-save generation 5 was invalidated only in a disposable Plump-owned game. The untouched `sram.gba` generation hashes were rechecked after recovery.
- Native renderer diagnostics after actual device ROM run included `frames=180 posted=179 ... lockFail=0 ... fmt=1 size=240x160 err=0`; continued running for thousands of frames. The one initial rejected frame corresponds to the absent surface at startup, not a crash.
- Original control/audio fixture tested against host mGBA with `python3 tests/roms/verify-p2-input-audio.py <host-libmgba.so> app/build/p2-test-fixtures/p2-input-audio.gba`: individual and simultaneous KEYINPUT responses changed real emulated pixels; A-key PSG audio peak was 11,520 vs idle 0.
- `./scripts/test-rgb565-renderer.sh`: pixel color, mGBA source pitch and Android destination stride host test **PASS**.
- Clean Gradle `:app:clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: **BUILD SUCCESSFUL**; 10 JVM tests (7 save integrity, 3 independent touch pointer state), 0 failures; lint completed with nonfatal warnings. APK debug v2 signature verified; no Android permissions requested.

## Defects found and fixed during P2

1. Initial device GBA frame was entirely black even though emulation advanced. Added RGB565→RGBA8888 conversion and frame diagnostics; on the phone, frames were still hidden.
2. Removed an opaque `SurfaceView` background obscuring the Android native surface. Owner subsequently confirmed real GBA text visibly rendered.
3. Added a full SRAM byte-for-byte core readback check before running loaded games. This provided meaningful cold-restore evidence independent of a test ROM which would rewrite the same data after boot.
4. Added a licensed-header, original ARM7TDMI GBA input/PSG fixture with host integration assertions to avoid falsely marking static test ROM input/audio as verified.

Local changes were committed coherently to **`main`**. Do not conflate tests that passed with product or design acceptance; **no push, release APK, or DS/3DS implementation** was performed as part of this milestone.

## Safety / limitations / recommendation

- **Scope:** only Plump APK and its isolated test files and private sandbox. APK updates used `adb install -r`, never `pm clear`, uninstall or another app. Phone developer settings and unrelated storage were not changed.
- **Recovery cleanup:** after the corruption test, stopped Plump, copied both exact pre-corruption diagnostic save generations from the scoped backup back to their original slots and used `cmp` plus full SHA-256 equality to verify. Kept the extra safety backup within Plump's own private files. No unrelated save was modified. The app can be relaunched normally.
- **Unverified:** long-duration performance, frame-time distributions and frame pacing with demanding games, audio underruns/desynchronization, touch latency under load, offline runtime with system connectivity disabled, crash/power-loss save recovery, both-corrupt-save UX, save-state compatibility, GB/GBC, DS/DSi, 3DS/New3DS, and polished user-approved UI. Device audio audibility is *owner-observed*, not an automated acoustic measurement.
- **Recommendation:** submit this **bounded P2 vertical slice** for mastermind review. Accept the demonstrated GBA proof at the P2 level while explicitly retaining extended performance, recovery hardening, offline stress and full product requirements as subsequent gates. Do **not** start unrelated DS/3DS development without the mastermind's milestone decision.
