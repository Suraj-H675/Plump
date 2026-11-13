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
not by itself verify Android journal write/recovery, force-stop/cold restart,
power-loss handling, retail titles or cross-core save compatibility. A
separately scoped, non-personal phone test is documented below.

The synthetic `p3_save_diagnostic.nds` was subsequently copied **only** to
the isolated `/sdcard/Plump-P3-Test/` directory. Android-device SHA-256 matched
the exact generated ROM, with both earlier fixtures still unchanged. The
installed Plump APK stayed at version code 5; existing six P2 GBA save files
were confirmed present in read-only app-private metadata.

### Physical DS EEPROM cold-restart test: passed at synthetic-fixture level

The owner opened the isolated `p3_save_diagnostic.nds` on the iQOO Neo 6,
verified that the game initially displayed `Initial save: NEW`, pressed A
once, observed `Write: VERIFIED` and `Current counter: 1`, and pressed PAUSE
to flush the save. Read-only app-scoped extraction verified **both** private
`nds-native-saves` generations, 1 and 2: 8,192-byte payload, correct
`PLSV` version-1 envelope, exact cartridge SHA-256 identity, SHA-256 checksum
and authentic in-game EEPROM record at offset `0x180`:

```text
504c4d502d44533301feac5301020304
```

Before cold restart, both journal files were backed up byte-for-byte in an
ignored, private host directory and their hashes matched the originals on
the phone. A controlled `adb am force-stop dev.plump.emulator` stopped **only
Plump**. The two save hashes remained identical. Android reported the new
Plump startup as `LaunchState: COLD`, with a new app process (PID changed).
The owner opened the *same* synthetic fixture through the DS picker and
reported `Initial save: RESTORED` and `Initial counter: 1` on the actual DS
screen. App-specific logcat independently recorded:

```text
I PlumpDS : DS loaded: geometry 256x384, fps 59.826
I PlumpDS : DS cartridge save restored and byte-verified: generation=2
I PlumpDS : frames=1082 posted=1082 rejected=0 avgCore=12ms peakCore=22ms
```

After normal continued play/foreground transitions, the journal contained
valid generations 2 and 3, both with the original **counter=1** record and
identical 8,192-byte payload SHA-256
`4cd149f96fd40ac5c9c314f4222d3ad3ab6e3036f69b48517292b46f5b41d8ad`.
The original six GBA saves were still present; no Android APK update,
`pm clear`, personal file access, system modification, or proprietary ROM was
involved in this test.

**Acceptance meaning:** Native DS EEPROM write, app journal persistence,
Plump-only force-stop/cold relaunch and genuine *in-game* EEPROM restoration
are **phone-verified for this synthetic 8 KB cartridge**. This does **not**
prove retail-game save compatibility, the DS-07 *engine change workflow*,
multi-file NAND/DSi saves, dual-corrupted-generation recovery or sudden power
loss safety. The P3 DS-07 gate is therefore **partially covered**, not
unconditionally accepted.

## Android candidate and verification level

| Scope | Evidence | Verdict |
|---|---|---|
| DS-01 real execution and 2 screens | Host ran 100 frames; both 256×192 panels extracted; owner screenshot shows genuine DS test text and both physical screens with 1,443 presented frames | **Initial fixture device pass; broader games pending** |
| DS-02 portrait layout | Upper larger, smaller lower centered between controls in owner screenshot | **Default physical layout observed** |
| DS-03 touchscreen tap/drag | Host marker correct at three exact DS coordinates, owner verified taps, drag and X/Y updates on phone | **Fixture-level physical pass** |
| DS-04 concurrent touch and buttons | Host and phone both verified A+B while lower stylus marker stays active | **Fixture-level physical pass** |
| DS-05 lower enlargement | Owner confirmed correct finger-marker alignment before and after lower enlargement | **Fixture-level physical pass** |
| DS-06 layouts/insets | Owner saw default and expanded portrait; new version-6 adaptive layout fits available inset-safe content height in 5 JVM sizing tests across iQOO, compact, wide and short viewports | **Geometry tested; revised APK not yet installed or physically validated in 3 cases** |
| DS-07 native save persistence | Synthetic DS SPI EEPROM write, two valid Android save generations, force-stop/cold relaunch and core readback plus game showing `RESTORED`, counter 1 | **Phone fixture cold-save pass; engine-change workflow and other cartridges pending** |
| DS-08 save states | Host serialization succeeded for synthetic DS cart (19,228,045 bytes), but restoring pre-write state **reverted newer EEPROM to all zero**; no state load UI/JNI offered | **Deliberately disabled due demonstrated loss risk; requires future guarded state design** |
| DS-09 DSi | Forced DS mode; no licensed DSi BIOS/firmware/NAND | **Not implemented; explicit gaps below** |
| Audio and lifecycle | Owner verified Pause/Resume, Home/Recents and audible PSG while holding A, which stops when released | **Fixture-level physical pass; extended synchronization pending** |
| Performance | Version-5 actual device screen: 1,443/1,443 frames, zero rejected, average/peak 8/17ms core. Version-6 local candidate adds measured FPS, 1ms-bucket p95/p99 core timing, audio-underrun count, plus read-only device measurement script | **Short interval observed; new telemetry and sustained test not yet verified on phone** |
| GBA regression | mGBA native core and `native-saves` directory unchanged; host GBA pixel test, existing save/button JVM tests and APK build pass | **Source/build regression check only; device retest pending** |

**Original version-5 test counts:** 16 JVM tests pass (7 native-save integrity,
3 GBA pointer state, 3 DS touch geometry, 3 DS button geometry). The local
version-6 candidate adds 5 responsive geometry tests and 3 frame-latency
tests for **24 JVM tests**, and 4 separate Python device-log-parser tests.
Host pixel split and
GBA RGB565 tests pass. Gradle `assembleDebug`, `testDebugUnitTest`, `lintDebug`
pass. Lint has nonblocking diagnostic/compatibility warnings. The APK contains both ARM64 cores; it
includes **no ROM, BIOS, NAND or proprietary game files**. Upstream GPL code
and asset notices must be reviewed before any public binary distribution.

### Save-integrity limitation

DS cartridge save RAM, when exposed by `RETRO_MEMORY_SAVE_RAM`, uses Plump's
existing checksum-generation journal, in a directory separate from P2 GBA
data. A failed save or unsupported size greater than 1 MiB is reported and
game switching is blocked, not falsely marked successful. The synthetic
8 KB cartridge **has passed both two-process host cold-load and real Android
force-stop/cold relaunch with in-game save verification**. The save engine-
change workflow and multi-title compatibility remain unverified.

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
  **Historical installed version-5 build** SHA-256:
  `55d1d8b8fb344213836ed797d7f669917c242ad5e18554849a7b8808f9f89489`.
  This hash was checked before its approved install. The current local
  `app/build/outputs/apk/debug/app-debug.apk` is now a **distinct version-6
  candidate**, and must never be mistaken for that older installed APK.
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

## P3 bounded closure investigation — layout, states, engine updates and performance

### Version-6 local portrait-safe layout and telemetry

Code `6`, `0.2.1-p3-layout-measure` is **built locally only**, not installed
or authorized for installation. It retains the approved top/lower portrait
composition, separate DS 4:3 surfaces and independent lower touchscreen.
The signed debug APK is a **candidate, not a release**:

```text
Project-relative path: app/build/outputs/apk/debug/app-debug.apk
Package: dev.plump.emulator
versionCode: 6
versionName: 0.2.1-p3-layout-measure
ABI: arm64-v8a
Length: 2,865,330 bytes
SHA-256: 71aed3148a7b08627176e9cb5a74480c6f13184fb2b402e417283e6ea4a71795
```

Its APK signature verified; no Android permissions, ROM, BIOS, firmware or
personal source files are bundled. The *currently installed* Plump on the
phone remains **version code 5** with its tested native saves untouched.
Any authorized future install must recheck the precise APK identity/hash and
first preserve private GBA **and** DS journal generations.
`DsPortraitSizing` now measures the space **after Android system/cutout
insets**, leaves room for the toolbar and controls, and scales the panels
without distorting their native aspect ratios when the enlarged bottom panel
would otherwise run off-screen. Top remains larger by default; enlarged mode
prioritizes bottom touch readability and can shrink the top as needed. The
user-approved lower enlargement affordance and control grouping remain.

Five JVM layout tests verify geometry and midpoint touch mapping for the
observed iQOO Neo 6 native screen (1080×2400, 440dpi), an inset-safe content
area, a compact phone, a wide/high-density phone, and very short content.
This is **mathematical/host validation only** until the owner approves a new
APK test and physically checks multiple touch-safe layouts on the target.

`DsFrameTiming` collects bounded, allocation-free 1ms latency buckets
with p95/p99 and max (64ms+ grouped in the last bucket). The DS runner now
reports **measured emulator FPS versus the actual melonDS core FPS** and
Android AudioTrack underruns alongside the existing posted/rejected counters.
Three JVM timing tests and four independent Python log-parser tests passed.
`scripts/measure-p3-device.sh` is a read-only, fail-closed 60–600s ADB
measurement: it requires Plump DS already in the foreground and never opens
a ROM, sends taps, changes system configuration or clears global logcat. It
captures only the Plump process's log tag, app memory and battery/temperature
metadata. The parser measures actual frame-counter/time deltas, rather than
interpreting a hardcoded 60fps as real performance. It reports explicitly
that core timing is **not GPU presentation latency or acoustic audio sync**.

No sustained real-device run has been performed with the version-6 candidate.
The phone was running another app during the preflight check; the measurement
script correctly rejected the session instead of interfering with it.

### State capability and proven native-save rollback hazard

The pinned core exposes working `retro_serialize_size` and `retro_serialize`
for the **synthetic DS cartridge**: host serialized 19,228,045 bytes. A third
independent synthetic host session in
`scripts/test-p3-native-save-diagnostic.sh` demonstrated a critical hazard:

```text
DS state serialized before in-game EEPROM write: 19228045 bytes
Save-state rollback:
  EEPROM before=504c4d502d44533301feac5301020304
  EEPROM after =00000000000000000000000000000000
PASS STATE SAFETY: loading an older DS state erased newer in-memory EEPROM
```

The state restores successfully but **rewinds cartridge memory to its old
value**; a subsequent autosave could overwrite current progress. Plump's DS
frontend consequently exposes **no manual/automatic save-state creation or
loading** while it lacks a tested native-save snapshot, conflict choice,
compatibility manifest, and rollback-safe transaction. Native in-game saves
still work independently. This is an explicit *safe disabled state*, **not**
a claim that DS save states are implemented. The pinned core separately
documents unsafe homebrew virtual-SD state limitations and unavailable DSi
serialization. State-system feature completion belongs to mastermind/P4
review, without weakening the P3 `DS-08` truthfulness requirement.

### Engine-change/save-update workflow — still unverified

The current `GameSaveStore` isolates DS native saves by ROM SHA-256 and
two protected journal generations, independent of GBA, and the synthetic
8KB cartridge's normal force-stop/cold-load path passed on the phone. An
**actual change to a different melonDS engine revision or save format has
not been performed**. A safe future upgrade must:

1. Preserve **both** original per-game generations in a private, externally
   verified backup and record original core revision, ROM ID and payload
   hashes, without touching unrelated personal games or saves.
2. Stage the proposed core/version in a separate test-only context and
   confirm `RETRO_MEMORY_SAVE_RAM` size/format and cold byte-exact readback;
   use licensed diagnostic content and exercise a real in-game read.
3. Confirm new saves cannot replace the last good old-engine generation
   before candidate validation. Include failed-upgrade and rollback cases.
4. If incompatible, keep the old journal, report the incompatibility and
   offer native-save/old-engine fallback **only when verified**; never silently
   rewrite schema metadata or auto-import a savestate.

This is a **proposed gate/runbook**, not an implemented migration or an
approval to replace the chosen core. The mastermind must decide how to stage
and accept a future engine update. DS-07's upgrade aspect is still open.

### Remaining P3 acceptance obligations

- Run controlled version-6 installation **only after fresh, explicitly scoped
  permission**, with pre-update hashes/backups of P2 GBA saves **and** DS
  fixture save generations; use `adb install -r`, never uninstall/clear.
- Verify at least three actual panel/safe-area combinations and tap/drag
  mapping, including expanded controls on the iQOO Neo 6; rerun GBA smoke
  rendering, buttons, audio and SRAM restore after the update.
- Measure >=3 minutes of real DS fixture speed, p95/p99 core timing,
  audio underruns, posted/rejected frames, app PSS and battery temperature;
  collect sustained/thermal evidence on a representative load before any
  broader performance or compatibility claim.
- Complete a separately reviewed engine-update/save compatibility experiment,
  or request an explicit mastermind decision on P3's scope/acceptance. Do not
  manufacture a pass by repeating the same engine's cold-load test.
- Keep DSi and unprotected homebrew SD persistence documented as capability
  gaps. No 3DS work, private ROM access, or UI polishing is authorized here.

## Milestone recommendation to mastermind

Keep P3 **open**. The software Libretro route now has credible pinned Android
build and genuine host execution evidence, which avoids the complexity of
direct native melonDS for the moment. Review the bounded P3 source and the
device test plan. The first data-preserving APK update was approved and
performed. Use the now-prepared interactive CC0 fixture with real-device
evidence to decide whether the Libretro
software route meets DS-01..08, particularly touchscreen, audio, saves and
long-duration performance. Do **not** start 3DS or unrelated UI polishing.
