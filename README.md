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

**Status (2026-11-13):** P1 technical feasibility was accepted. The bounded
P2 ARM64 Android app is **running real mGBA test ROMs on the owner's iQOO Neo 6**.
The owner visually verified `hello.gba` rendering and `sram.gba` execution,
plus basic pause/resume and Android backgrounding. The 32-KiB native cartridge
save survived a Plump force-stop and cold restart: the core verified it
byte-for-byte before new frames. The diagnostic APK is `0.1.3-p2-sram-verify`.
**Audio audibility, actual touchscreen input mapping, corruption recovery and
real-game performance are not yet device verified.** This is not product-ready;
DS/3DS integrations remain unselected.

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
The owner's approved P2 device test permits Plump-only APK updates with app
data preserved, and isolated licensed/original test ROMs. Device operations
outside that test scope still require authorization.
The input/audio test ROM is generated only in ignored `app/build/` using the
pinned NDK assembler and a checksum-verified MIT-licensed GBA cartridge header;
the ROM is never bundled into the APK or checked into Git.
See `docs/UPSTREAM_SOURCES.md` for exact upstream revision and test-fixture
provenance, and `docs/STATUS.md` for acceptance limitations.

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
