# P3 — Nintendo DS software-rendering technical checkpoint

**Date:** 2026-11-13. **Status:** partially implemented, host-tested, Android
APK built, **not device-verified or P3-accepted**. Scope ends at Nintendo DS;
no DSi or 3DS game was enabled, and no owner's personal content was accessed.

## Authority and environment

- Canonical Plump checkout, `main`, accepted P2 base
  `d1cef15b73c490eb2aa3e7a2c66a2e25ed8004a4`, one worktree, public
  `origin`. P2's six commits were reviewed and pushed as one normal non-force
  update **before P3 changes**. P3 changes are intentionally **not pushed**.
- Main filesystem has approximately 125 GiB available. `/tmp` is a separate
  `tmpfs` with `usrquota` and writing to it returned `EDQUOT` despite free
  tmpfs blocks. Small writes to `app/build/p3-tmp` succeeded. No broad cache
  deletion, system setting change, or unrelated project modification was made.
- Main Codex GPT-6 Luna xhigh was verified and poteto-mode attempted for P3.
  The tool's run did not produce code (it encountered quota writes and
  excessive skill exploration). The bounded core build and integration were
  executed directly in the canonical checkout, retaining all safety limits.
- Android SDK platform 35, NDK 28.2.13676358, Gradle 8.13, AGP 8.13.2,
  minSdk 26, targetSdk 35, `arm64-v8a` only.

## Core selection evidence

The previously investigated `JesseTG/melonds-ds` Libretro source is pinned at
`f394adbacb5722ee97c1b37c8064da9a25818310` (GPLv3-or-later). Its native
melonDS dependency and ten supporting dependencies are pinned to full commits
in `scripts/build-melonds-ds-arm64.sh`; see the provenance ledger.

1. The pinned source's Android CMake defaults OpenGL to **OFF**, and attempts
   to select OpenGL ES hit a fatal unsupported-profile check. This is not a
   desktop OpenGL renderer repackaged as Android-compatible.
2. With OpenGL, compute renderer, JIT and threaded renderer disabled, CMake
   configured successfully for ARM64. `ENABLE_NETWORKING=OFF` then failed:
   `config/config.cpp` includes `Net_PCap.h` unconditionally, and other files
   reference networking-only types. Re-enabling the upstream networking build
   option compiled successfully **without modifying upstream source**. The
   runtime frontend explicitly sets `melonds_network_mode=disabled`; core
   source `net/net.cpp` handles `NetworkMode::None` by clearing its driver.
   The Android APK requests **zero permissions**, including no Internet.
3. The software core delivers one 256×384 XRGB8888 Libretro framebuffer in
   top-bottom order. `nds_pixels.hpp` converts its upper and lower 256×192
   regions into independently positioned native Android RGBA8888 windows.
   Each viewport preserves its 4:3 ratio.
4. The touchscreen host converts local lower-panel coordinates into the
   **combined** Libretro pointer coordinate space and sets the pressed state
   independently of joypad bitmasks. Changing the lower view's size does not
   change the normalized native 256×192 touch coordinates. The native core's
   `screenlayout.cpp` inverse matrix consumes the same coordinates.
5. The direct-native melonDS candidate was inspected at
   `497aa6cdd26f45f8064b78916b00a773eab4ded7`. It has not been embedded:
   the Libretro path passed the source, build and host execution checkpoints,
   so adding a second emulator integration would be unnecessary complexity.
   **If phone performance, touchscreen or composition fails, reconsider.**

## Real execution (Linux development host, not Android phone)

Run from the repository root:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export TMPDIR="$PWD/app/build/p3-tmp"
./scripts/build-melonds-ds-arm64.sh
./scripts/test-melonds-ds-host.sh
./scripts/test-nds-frame-split.sh
./scripts/test-rgb565-renderer.sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The GPL-covered upstream DS fixture `test/nds/tests_sys_mem_regions.nds`
is present only in ignored upstream source cache (`JesseTG/melonds-ds` at
the pinned revision), SHA-256:
`9d7f86224faec8da2b22b103cb2ff9a84c9120cc33b5cc43498d108924b5813f`.

Observed `tests/roms/verify-melonds-ds-host.py` output:

```text
DS native software AV: 256 384 fps: 59.82609828808083 audio Hz: 32728.498046875
frames=100 distinct_frame_crc32=3 distinct_top_crc32=1 distinct_lower_crc32=3
input_polls=100 audio_frames=54705 seconds_for_100_frames=0.129
cart_sram_bytes=0
requested_core_option_count=74 missing_required=[]
PASS: genuine melonDS DS game execution ... on host
```

Both panels held nonzero real pixels; at least one frame's panel checksums
differed, and the lower panel changed while the upper stayed static for this
particular program. Input and audio callbacks occurred. This fixture did not
test audible audio, actual button-dependent game response or actual stylus
response. The approximately 0.13 seconds for 100 frames is a desktop-host
execution figure, **not a phone performance estimate**. There is no claim of
full-speed game compatibility or sustained battery/thermal performance.

## Android candidate and verification level

| Scope | Evidence | Verdict |
|---|---|---|
| DS-01 real execution and 2 screens | Host ran 100 frames; two distinct 256×192 panels extracted; Android binary compiled | **Host verified; phone pending** |
| DS-02 portrait layout | Separate upper/lower SurfaceViews, upper wider, lower centered between side buttons in default; correct native ratios | **Implemented, phone UX unverified** |
| DS-03 touchscreen tap/drag | Pointer-ID tracking; local inverse coordinates mapped to combined Libretro bottom viewport; geometry JVM tests | **Implementation and arithmetic tested, game response unverified** |
| DS-04 concurrent touch and buttons | Separate touch and button views plus independent atomic state; all 12 DS button hit regions and simultaneous masks in JVM tests | **Implementation/test only; real multitouch unverified** |
| DS-05 lower enlargement | Toggle to full-width lower with side controls moved below; releases old pointer states and reuses touch mapper | **Implemented, on-device transform unverified** |
| DS-06 layouts/insets | 4 lower display widths exercised in JVM tests; Android `fitsSystemWindows` set | **3 real layouts, safe areas and accessibility pending** |
| DS-07 native save persistence | Existing checksummed two-generation `GameSaveStore` reused for separate `nds-native-saves` and SHA-256 game ID; restore readback before frames | **Store tests pass; no DS cartridge SRAM cold test** |
| DS-08 save states | No DS save-state UI or serializing JNI exposed; no false save-state claims | **Not implemented; future P3 decision** |
| DS-09 DSi | Forced DS mode; no licensed DSi BIOS/firmware/NAND | **Not implemented; explicit gaps below** |
| Audio and lifecycle | Separate AudioTrack emulation path, pause/back/foreground join+flush; host audio callbacks | **Compiles; real-device audible/interrupt proof pending** |
| Performance | Native frame posted/rejected counters and `avgCore`/`peakCore` timing in Android log | **Instrumentation only; zero Android measurements** |
| GBA regression | mGBA native core and `native-saves` directory unchanged; host GBA pixel test, existing save/button JVM tests and APK build pass | **Source/build regression check only; device retest pending** |

**Test counts:** 16 JVM tests pass (7 native-save integrity, 3 GBA pointer
state, 3 DS touch geometry, 3 DS button geometry); host pixel split and
GBA RGB565 tests pass. Gradle `assembleDebug`, `testDebugUnitTest`, `lintDebug`
pass. Lint has no blocking findings. The APK contains both ARM64 cores; it
includes **no ROM, BIOS, NAND or proprietary game files**. Upstream GPL code
and asset notices must be reviewed before any public binary distribution.

### Save-integrity limitation

DS cartridge save RAM, when exposed by `RETRO_MEMORY_SAVE_RAM`, uses Plump's
existing checksum-generation journal, in a directory separate from P2 GBA
data. A failed save or unsupported size greater than 1 MiB is reported and
game switching is blocked, not falsely marked successful. The actual DS
cartridge save path has **not** survived a cold restart test yet.

The selected DS fixture is **homebrew**, which uses the core's separate
virtual SD-card image instead of cartridge SRAM (`cart_sram_bytes=0`). The
virtual SD card is currently **not backed by the two-generation save store**.
Homebrew save persistence and corruption recovery are NOT verified and must
be protected before the app is trusted for personal homebrew progress. Do not
treat the existing `GameSaveStore` tests as a substitute for that work.

### DSi capability/gap report

At this pinned core revision, DS mode can direct-boot compatible homebrew
using upstream-generated FreeBIOS/default firmware. DSi mode requires
appropriately sourced BIOS, firmware and NAND, which Plump does not import or
acquire automatically. The Libretro core does not support **DSi savestates**,
rewind or runahead. It does not integrate **DSi cameras** and does not safely
support homebrew savestates with virtual SD data. Native DSi NAND save-data
integrity requires separate multi-file transaction design and testing, not
the cartridge SRAM envelope. Microphone support requires a frontend API not
exposed by the current P3 Android host; camera/mic features must never be
claimed. Upstream DSiWare metadata-fetch behavior also conflicts with
Plump's entirely offline guarantee and would need to be disabled or replaced
before DSi enablement. DSi mode remains deliberately inaccessible.

## Proposed controlled device experiment — **requires new authorization**

The P3 Android debug candidate is **not yet installed**. Intended identity:

- Package `dev.plump.emulator` (same as accepted P2); `versionCode=5`,
  `versionName=0.2.0-p3-ds-spike`; ARM64 only; min API 26, target API 35;
  requests no Android permissions.
- APK path relative to the canonical checkout:
  `app/build/outputs/apk/debug/app-debug.apk`.
  Current final build SHA-256:
  `55d1d8b8fb344213836ed797d7f669917c242ad5e18554849a7b8808f9f89489`.
  Reverify this exact hash immediately before any authorized install; abort if
  it differs.
- Current accepted installed version is P2 `0.1.3-p2-sram-verify` (code 4)
  according to the P2 device report. Recheck package/device identity and
  installed version **read-only** before any operation; abort on mismatch.

**Preserve P2 test data first:** with new, specific approval, keep Plump
closed, inspect only `dev.plump.emulator`'s own app-private
`files/native-saves/`, take a byte-for-byte copy to a private, local,
non-Git backup outside the phone using `run-as`, verify its hashes, and abort
the update if the backup fails. Back up any existing Plump diagnostic save
data without accessing ROMs, unrelated files or apps. Install only the exact
approved APK using `adb install -r` (no uninstall, `pm clear`, overwrite of
saves, system changes or app data migration). Recheck the unchanged GBA files
and run P2 hello, input/audio and SRAM cold-readback regression tests before
counting DS tests as success. Restore only with a separate recovery decision;
never automatically rewrite device saves.

**Licensed P3 fixture scope:** if approved, copy only the pinned GPL-covered
upstream `.nds` test to a new isolated `/sdcard/Plump-P3-Test/` folder, verify
its SHA-256 on the phone and select it using Plump's document picker. Observe
two independently displayed game screens, native ratios, lower enlargement
and pointer alignment; log real `frames`, `posted`, `rejected` and core-time
measurements, audio device behavior, pause/return, and touchscreen pointer
release. To complete the strict stylus/button/game-response and cartridge
save gates, acquire or construct **additional appropriately licensed test
content** before claiming those checks passed; this homebrew fixture is not
sufficient. No automated phone taps, no private ROM/firmware access, no
proprietary system file acquisition. Any further APK update after the first
device experiment needs fresh approval.

## Milestone recommendation to mastermind

Keep P3 **open**. The software Libretro route now has credible pinned Android
build and genuine host execution evidence, which avoids the complexity of
direct native melonDS for the moment. Review the bounded P3 source and the
device test plan. Permit a tightly scoped Plump-only, data-preserving APK
update only when the owner explicitly approves. Use subsequent real-device
evidence and an interactive licensed fixture to decide whether the Libretro
software route meets DS-01..08, particularly touchscreen, audio, saves and
long-duration performance. Do **not** start 3DS or unrelated UI polishing.
