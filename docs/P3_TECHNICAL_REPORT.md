# P3 — Nintendo DS software-rendering technical checkpoint

**Date:** 2026-11-13. **Status:** partially implemented, host-tested, Android
APK installed with bounded real-device display and lifecycle proof;
**P3 is not accepted**. Scope ends at Nintendo DS;
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

### Initial physical-device DS proof (owner observed)

After separately scoped approval, Plump APK version 5 (`0.2.0-p3-ds-spike`)
was installed on the iQOO Neo 6 using `adb install -r`. All eight existing P2
app-private cartridge-save and recovery files were first backed up to a
private ignored local directory, and their SHA-256 hashes matched before and
after installation. No uninstall or package-data clearing was performed.

The owner supplied a screenshot showing actual homebrew memory-test output
on the lower DS panel and an all-white upper panel (normal for this test),
plus side controls. The on-screen emulator diagnostic reported:

```text
DS frames=1443 posted=1443 rejected=0 avgCore=8ms peakCore=17ms
```

The owner confirmed that lower-screen enlargement worked, and that Pause/
Resume and Android Home/Recents return preserved the test. Pressing virtual
START made both panels black. A reproducible host experiment with the same
ROM and START input logged `Stopping emulated console (Reason: PowerOff)`;
Plump's Android app process remained alive, consistent with normal test-ROM
exit rather than a crash. These observations prove the initial physical
dual-screen slice and tested lifecycle, **not** sustained performance, audio,
accurate touchscreen/drag or DS native cartridge saves.

### Interactive DS controls and audible PSG: physical phone validation

The owner then opened the independently SHA-256-verified CC0
`p3_input_diagnostic.nds` and confirmed **all four** scripted checks on the
iQOO Neo 6: the real upper/lower DS panels appeared; finger taps and drags
moved the native lower-screen marker while the game's X/Y values changed;
touch remained aligned after enlarging the lower display; and pressing A
produced an audible tone which stopped when released. The owner also confirmed
that simultaneous A+B and an active lower-screen finger kept the marker
visible and correctly displayed both button inputs.

**Evidence level:** owner-verified on the actual Android phone for this
diagnostic fixture, supported by independent host pointer/chord/audio tests.
This is not instrument-grade touch-error measurement, broad title support,
extended audio sync or thermally sustained performance validation. The APK
remained at approved version code 5; no update was needed.

### Interactive DS input/audio fixture: genuine host execution

A small, reproducible **CC0-licensed ARM9+ARM7 NDS program** was derived from
the official BlocksDS touch and PSG examples. Its source is tracked at
`tests/roms/p3-input-diagnostic/`, and its immutable, network-disabled
BlocksDS build is `scripts/build-p3-input-diagnostic-rom.sh`. The generated
cartridge remains only under ignored `app/build/`, not in the APK or Git.

`tests/roms/verify-p3-input-diagnostic.py` exercised the true pinned Linux
melonDS DS engine using the same signed Libretro pointer encoding as the
Android JNI adapter. Synthetic taps at (32,32), (128,96), and (224,160)
produced respectively the bounding boxes (28,28)-(36,36),
(124,92)-(132,100), and (220,156)-(228,164), with 65 nonblack marker
pixels each. Releasing the stylus left no marker. Holding A+B while touching
the center left the marker correctly positioned and changed the upper DS
console's button display checksum. Real PSG PCM output peaked at 19,945
while A was held and fell to 27 in the release interval. Earlier startup/
idle sound was nonzero; the report does **not** claim it was silent.

An initial disposable **host test harness** accidentally offset signed
pointer values by 32,768; it was corrected before creating the committed
regression test. This was not a defect in the P3 JNI native converter.
These are real core host results, not yet physical touchscreen/audio proof.

Diagnostic ROM SHA-256:
`109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d`.
It was transferred to the existing isolated
`/sdcard/Plump-P3-Test/p3_input_diagnostic.nds`, and its on-device hash was
verified. No new APK was built or installed. The owner subsequently reported
all four real-phone interaction checks passed (documented above).

### Native DS cartridge EEPROM: distinct synthetic host cold-load proof

To investigate DS-07 without using personal or copyrighted commercial ROMs,
Plump now builds an original **CC0-licensed ARM9 EEPROM test** with BlocksDS.
A private generated copy receives an invented `P3SV` gamecode and a decrypted
secure-area marker to exercise melonDS's ordinary retail-classified 8 KB
EEPROM emulation rather than the virtual SD card used by homebrew. It is an
explicitly **synthetic test cartridge**, not a retail game or general
compatibility test. No Nintendo system files were downloaded or installed.

The program uses real DS AUX SPI commands (`cardWriteEeprom` and
`cardReadEeprom`) to write and immediately read a 16-byte self-checked
diagnostic record at offset `0x180`. The fixture initially failed because
Slot-1 bus ownership had not been assigned to ARM9; the authentic hardware
call `sysSetCardOwner(BUS_OWNER_ARM9)` resolved the failure without weakening
the test or changing upstream melonDS. `scripts/test-p3-native-save-diagnostic.sh`
boots the genuine core in **two independent host processes**. The first
process writes EEPROM and extracts the core's `RETRO_MEMORY_SAVE_RAM`; a
second process loads exactly those saved 8 KB *before the first frame*, runs
the actual cartridge code, and checks distinct on-screen CRCs to confirm
the program saw its restored record. Test-only saves remain in ignored scratch.

```text
synthetic ROM SHA-256: 6d863811a7831129b06a0aa07db5955553b69b0fc4748f1fa6e3506de45d4f70
Stage=first synthetic cartridge SRAM bytes=8192
SRAM data at 0x180 504c4d502d44533301feac5301020304
PASS FIRST: in-game EEPROM write, immediate SPI readback, cartridge memory snapshot
Stage=cold synthetic cartridge SRAM bytes=8192
SRAM data at 0x180 504c4d502d44533301feac5301020304
PASS COLD: separate-process SRAM restored into core and read in-game
```

This supports the **host native EEPROM/save API integration only**. It does
not yet verify Android journal write/recovery, force-stop/cold restart,
power-loss handling, retail titles or cross-core save compatibility. A
separately scoped, non-personal phone test is still required for DS-07.

The synthetic `p3_save_diagnostic.nds` was subsequently copied **only** to
the isolated `/sdcard/Plump-P3-Test/` directory. Android-device SHA-256 matched
the exact generated ROM, with both earlier fixtures still unchanged. The
installed Plump APK stayed at version code 5; existing six P2 GBA save files
were confirmed present in read-only app-private metadata. The owner has not
yet performed the in-game EEPROM write, pause, or cold-open verification on
the target phone, so physical DS-07 remains **pending**.

## Android candidate and verification level

| Scope | Evidence | Verdict |
|---|---|---|
| DS-01 real execution and 2 screens | Host ran 100 frames; both 256×192 panels extracted; owner screenshot shows genuine DS test text and both physical screens with 1,443 presented frames | **Initial fixture device pass; broader games pending** |
| DS-02 portrait layout | Upper larger, smaller lower centered between controls in owner screenshot | **Default physical layout observed** |
| DS-03 touchscreen tap/drag | Host marker correct at three exact DS coordinates, owner verified taps, drag and X/Y updates on phone | **Fixture-level physical pass** |
| DS-04 concurrent touch and buttons | Host and phone both verified A+B while lower stylus marker stays active | **Fixture-level physical pass** |
| DS-05 lower enlargement | Owner confirmed correct finger-marker alignment before and after lower enlargement | **Fixture-level physical pass** |
| DS-06 layouts/insets | 4 lower display widths exercised in JVM tests; Android `fitsSystemWindows` set | **3 real layouts, safe areas and accessibility pending** |
| DS-07 native save persistence | Separate two-generation `nds-native-saves` journal; synthetic real DS SPI EEPROM write and in-game cold restoration pass in two host processes | **Host EEPROM proof; Android cold save still pending** |
| DS-08 save states | No DS save-state UI or serializing JNI exposed; no false save-state claims | **Not implemented; future P3 decision** |
| DS-09 DSi | Forced DS mode; no licensed DSi BIOS/firmware/NAND | **Not implemented; explicit gaps below** |
| Audio and lifecycle | Owner verified Pause/Resume, Home/Recents and audible PSG while holding A, which stops when released | **Fixture-level physical pass; extended synchronization pending** |
| Performance | Actual device screen showed 1,443/1,443 posted, zero rejected, average/peak 8/17 ms core time | **Short interval observed; sustained pacing unverified** |
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
game switching is blocked, not falsely marked successful. A synthetic
cartridge **has passed two-process host cold-load**, but Plump's Android
two-generation journal has **not** passed force-stop/reopen testing yet.

The earlier DS fixtures are **homebrew**, which uses the core's separate
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

## First controlled device experiment — **approved and executed**

The P3 Android debug candidate **was installed** after exact identity and
app-private backup verification. Installed identity:

- Package `dev.plump.emulator` (same as accepted P2); `versionCode=5`,
  `versionName=0.2.0-p3-ds-spike`; ARM64 only; min API 26, target API 35;
  requests no Android permissions.
- APK path relative to the canonical checkout:
  `app/build/outputs/apk/debug/app-debug.apk`.
  Current final build SHA-256:
  `55d1d8b8fb344213836ed797d7f669917c242ad5e18554849a7b8808f9f89489`.
  This hash was checked against the installed approved APK; reverify before
  any future separately authorized install and abort if it differs.
- Pre-update installed version was P2 `0.1.3-p2-sram-verify` (code 4).
  Installed version after the scoped update is code 5. The P2 test-save
  backup and post-install hashes matched for all eight existing files.

**Preserve P2 test data first:** with the explicit authorization, Plump was
closed; inspection was restricted to `dev.plump.emulator`'s own app-private
`files/native-saves/` and the disposable recovery backup. A byte-for-byte
copy was made privately using `run-as` with host/device SHA-256 agreement.
Only the approved APK was installed using `adb install -r` without uninstall,
`pm clear`, overwrite of saves, system settings changes or app data migration.
Previous GBA ROM checksums were preserved. Full physical GBA re-execution
remains a separate regression gate. Never automatically rewrite device saves.

**Licensed P3 fixture scope:** The approved experiment copied the pinned GPL-covered
upstream `.nds` test to a new isolated `/sdcard/Plump-P3-Test/` folder, verified
its SHA-256 on the phone and selected it using Plump's document picker.
The owner inspected both real displays, lower enlargement, controls, pause and
return. The subsequent CC0 input/audio fixture is a distinct, deliberately
licensed test program to cover the remaining physical stylus and chord gap;
it is available in the same isolated P3 folder. The current homebrew content
does **not** exercise native cartridge SRAM cold persistence. There were no
automated phone taps, private ROM/firmware reads or proprietary-file downloads.
Any new APK update after this first device experiment requires separate approval.

## Milestone recommendation to mastermind

Keep P3 **open**. The software Libretro route now has credible pinned Android
build and genuine host execution evidence, which avoids the complexity of
direct native melonDS for the moment. Review the bounded P3 source and the
device test plan. The first data-preserving APK update was approved and
performed. Use the now-prepared interactive CC0 fixture with real-device
evidence to decide whether the Libretro
software route meets DS-01..08, particularly touchscreen, audio, saves and
long-duration performance. Do **not** start 3DS or unrelated UI polishing.
