# Plump

Plump is a personal, offline-first Android Nintendo handheld emulator project.
The design direction is a native, polished phone experience with two main
gameplay layouts: a single-screen layout for Game Boy-family systems and a
dual-screen layout for DS/3DS-family systems.

## Product goals

- One on-device game library with user-selected recently played or alphabetical sorting.
- GB/GBC/GBA, DS/DSi, and 3DS/New 3DS emulation targets.
- Separate virtual controls, native console aspect ratios, direct DS/3DS touch input,
  console-appropriate layouts, and customizable touchscreen controls.
- Reliability first: in-game saves, manual states where supported, recovery
  checkpoints, independent per-game resume, and optional local-folder backups.
- No online account, cloud services, telemetry, or multiplayer.

**Status (2026-11-13):** P1 and the bounded P2 GBA device milestone were
**accepted by the mastermind**. The P2 ARM64 Android app is **running real mGBA
test ROMs on the owner's iQOO Neo 6**.
The owner visually verified `hello.gba` rendering and `sram.gba` execution,
plus basic pause/resume and Android backgrounding. The 32-KiB native cartridge
save survived a Plump force-stop and cold restart: the core verified it
byte-for-byte before new frames. The diagnostic APK is `0.1.3-p2-sram-verify`.
The owner also verified all mapped GBA buttons including two-finger A+B, audible
PSG tone response, and recovery of an older cartridge-save generation after a
newer disposable generation's checksum was deliberately damaged. See
[P2 device-test report](docs/P2_DEVICE_TEST_REPORT.md). **Long-running audio
synchronization, performance, broad game compatibility, crash-hardening and
product-quality UX are not yet verified.** This is not product-ready.
The accepted P2 HEAD `d1cef15` was pushed to the existing public source
repository; no APK was released. **P3 DS work is a local unaccepted technical
slice**: a pinned melonDS DS software core built for Android ARM64, and the
same core executed an upstream-licensed DS homebrew test on the development
computer. Android DS APK `0.2.0-p3-ds-spike` (code 5) **is installed on the
owner's phone** under a separate scoped approval, with all eight preexisting
Plump save/recovery files preserved and hash-checked. The owner verified real
dual-screen output, lower enlargement, pause/resume, Android background return,
and normal START-controlled test-ROM power-off. A new CC0 interactive DS
homebrew fixture passed host checks for exact stylus coordinates, simultaneous
A+B+stylus and native PSG tone samples; the generated ROM was transferred
to the existing isolated P3 fixture folder and its interactions were also
verified by the owner on the actual phone.
**P3 is not accepted**: the owner has now confirmed physical touchscreen
accuracy, simultaneous touch+A+B, enlarged-screen mapping, and audible PSG
for the CC0 fixture. Additionally, a distinct original CC0 synthetic DS
cartridge passed authentic emulated EEPROM writes and in-game cold recovery
in two separate host core processes **and on the owner's actual phone**:
two valid 8-KiB cartridge-save generations survived a Plump-only force-stop,
the emulator byte-verified restored generation 2, and the game reported
`Initial save: RESTORED` with counter 1 after cold relaunch. **Engine-change
save handling, additional safe-area layouts, supported save-state behavior,
multi-title compatibility and sustained performance remain unverified.** See
[P3 technical report](docs/P3_TECHNICAL_REPORT.md).

## P2 development build (Android ARM64 only)

Install Android SDK platform 35, Build Tools 35.0.0, NDK 28.2.13676358,
CMake 3.22.1 and a JDK compatible with the pinned Gradle/AGP versions.
The Gradle 8.13 wrapper and Android Gradle Plugin 8.13.2 are pinned.
Run the following from the repository root:

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/build-mgba-arm64.sh
./scripts/test-rgb565-renderer.sh
./scripts/build-p2-input-audio-rom.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

This generates `app/build/outputs/apk/debug/app-debug.apk`; upstream native
source and compiled libraries are excluded from Git. The APK contains no game
ROMs and requests no network or storage-wide Android permissions. Select an
appropriately licensed local test ROM through Android's document picker.
Prior P2 testing does **not** authorize installing a P3 APK. Any further
device operation requires new, separately scoped user authorization.
The input/audio test ROM is generated only in ignored `app/build/` using the
pinned NDK assembler and a checksum-verified MIT-licensed GBA cartridge header;
the ROM is never bundled into the APK or checked into Git.
See `docs/UPSTREAM_SOURCES.md` for exact upstream revision and test-fixture
provenance, and `docs/STATUS.md` for acceptance limitations.

## P3 DS software-rendering experiment (Android ARM64)

Use the existing, pinned melonDS DS checkout described in
`docs/UPSTREAM_SOURCES.md`. Configure Android SDK/NDK as above. Due to an
unrelated `/tmp` user-quota limit on the development machine, scratch files
are owned by ignored `app/build/p3-tmp` instead of `/tmp`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export TMPDIR="$PWD/app/build/p3-tmp"
./scripts/build-melonds-ds-arm64.sh
./scripts/test-melonds-ds-host.sh
./scripts/test-p3-input-diagnostic.sh
./scripts/test-p3-native-save-diagnostic.sh
./scripts/test-nds-frame-split.sh
./scripts/test-rgb565-renderer.sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The host integration test executes genuine pinned GPL-covered Nintendo DS
homebrew in real melonDS, with separate top/bottom pixel checksums and input
and audio callbacks. The firmware-free DS mode uses upstream FreeBIOS.
The separately built CC0 input/audio diagnostic also runs a real DS game,
checking three precise stylus locations, release, simultaneous A+B and a
hardware PSG sample stream. Build requires the immutable official BlocksDS
image listed in the provenance ledger; the container is network-disabled.
Generated test ROMs remain under ignored `app/build/` and are **not
packaged** in the Android APK or committed to this repository. An APK build is
not proof of Android display composition, touchscreen behavior, audio
audibility, lifecycle, native save recovery, or sustained performance. The
P3 APK will not be installed without explicit permission.

## Planning and execution

The comprehensive decision register, development workflow, engineering standard,
architecture evaluation, acceptance gates, and approved design references are kept
in a **local, Git-ignored source pack** at:

    .plump-private/project-sources/

The user's existing OpenPencil design references remain local. Public design
screenshots or source may be added later only after review.

Start at docs/OVERVIEW.md and docs/STATUS.md. Repo agents must read AGENTS.md and
the authoritative local source pack when accessible.

This project is not affiliated with Nintendo or any upstream emulator project.
The initial GBA test app uses a reproducibly built, Git-ignored third-party
core. See the provenance ledger for its license and exact source revision.
A public source repository does not imply a published APK or an agreed
overall project license.
