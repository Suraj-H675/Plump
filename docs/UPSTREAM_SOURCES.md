# Upstream source/provenance — P2 and P3

Plump uses genuine in-process emulation. It **does not** launch an installed
third-party emulator app. This ledger records the exact native dependency for
the P2 proof build; it does not choose a final app-wide redistribution license.

| Field | P2 mGBA source |
|---|---|
| Repository | https://github.com/mgba-emu/mgba |
| Immutable revision | `d58ea8a86f30598fbf3958585de3d33028535072` |
| License | Mozilla Public License 2.0; upstream `LICENSE` and original notices retained upstream |
| Source location | Local-only checkout in `$HOME/.cache/plump/upstream/mgba` or `PLUMP_MGBA_SOURCE` |
| Upstream integration | `src/platform/libretro/libretro.c`; public Libretro ABI |
| Modifications | None to mGBA; Plump owns only JNI, Android UI and save coordination |
| Build recipe | `scripts/build-mgba-arm64.sh`; Android NDK 28.2.13676358, API 26, `arm64-v8a` |
| Generated destination | Ignored `app/src/main/jniLibs/arm64-v8a/libmgba_libretro.so` |
| Save format | Libretro `RETRO_MEMORY_SAVE_RAM`; Plump checksummed two-generation envelope, not a foreign savestate |
| Upgrade policy | No revision change before native-save backup and compatibility retests |

The Libretro ABI is defined by [libretro.h](https://github.com/libretro/libretro-common/blob/master/include/libretro.h).
Only the essential ABI declarations used by Plump are provided in the JNI host.
The app contains no mGBA emulator CPU/GPU/audio implementation written by Plump.

## Licensed P2 test fixtures (not bundled or tracked)

`https://github.com/jsmolka/gba-tests`, commit
`a7113b67e63f83a9b321696ddd7042ccfad6c881`, MIT license,
copyright 2019 Julian Smolka. Local-only test files:
`ppu/hello.gba` (SHA-256 `38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264`)
and `save/sram.gba` (SHA-256 `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`).
The ROMs are not distributed in the public repository or the standard Plump APK.
They are loaded explicitly through Android's system document picker after the
owner authorizes the appropriate device test.

## Original P2 interactive test ROM (not bundled or tracked)

`tests/roms/p2-input-audio.s` is a tiny original ARM7TDMI GBA cartridge test,
not an emulator or rendered simulation. It reads actual GBA hardware KEYINPUT,
changes the mode-3 framebuffer for each key and for simultaneous A+B, and
unmutes a real GBA PSG channel-1 test tone while A is held.
`scripts/build-p2-input-audio-rom.sh` builds it with the pinned NDK ARM assembler
and appends the **first 192 bytes of the MIT `ppu/hello.gba` header** (pinned
SHA-256 and commit above) to make a valid test cartridge. The header's title,
game code, and checksum are recomputed for Plump. The standard cartridge
header's original MIT license attribution is preserved here and in the script.
The generated `app/build/p2-test-fixtures/p2-input-audio.gba` is local-only.
`tests/roms/verify-p2-input-audio.py` verifies real input-dependent video
colors, simultaneous A+B and non-silent PSG samples against Linux-host mGBA;
this does *not* establish Android physical speaker or touch correctness.

## P3 pinned Nintendo DS software core

| Field | P3 melonDS DS source |
|---|---|
| Libretro project | https://github.com/JesseTG/melonds-ds |
| Immutable revision | `f394adbacb5722ee97c1b37c8064da9a25818310` |
| License | GPL-3.0-or-later (upstream `LICENSE`, file copyrights and component notices apply) |
| Core engine | Upstream `JesseTG/melonDS` native dependency, revision `16f127dbb587a73371827ce79689c5d237c4b59b`; **not** a Plump rewrite |
| P1 standalone alternative inspected | `melonDS-emu/melonDS` revision `497aa6cdd26f45f8064b78916b00a773eab4ded7`; not integrated |
| Render integration | Libretro software `RETRO_PIXEL_FORMAT_XRGB8888` in 256×384 top-bottom layout, split into two 256×192 RGBA8888 Android SurfaceViews |
| Android build recipe | `scripts/build-melonds-ds-arm64.sh`, NDK 28.2.13676358, Android API 26, ARM64, no OpenGL, no JIT, optional networking compiled only to satisfy upstream headers |
| Runtime network policy | Frontend forces core option `melonds_network_mode=disabled`; app requests no Android permissions, including no Internet; offline device exercise pending |
| Build artifacts | Generated library at ignored `app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so`; generated code/deps under ignored `app/build/p3-*` |
| Save boundary | Libretro `RETRO_MEMORY_SAVE_RAM` for supported DS carts, isolated `nds-native-saves` checksum generations; homebrew uses distinct virtual SD storage, not yet covered by this guarantee |
| Unsupported | DSi requires user-lawful system firmware/BIOS/NAND, DSi savestates and cameras unavailable in this adapter/core; homebrew savestates are not supported. No firmware or ROM content is bundled |

Third-party dependency commits are fixed in the P3 build script, rather than
tracking upstream's moving branch/tag defaults:

| Library | Pinned SHA |
|---|---|
| libretro-common | `fa8a1b572a444fcb552007019f8caf68281108aa` |
| embed-binaries | `078b62beba97e8192c99bfb16d5e17220cfc7598` |
| glm | `6f14f4792a0cde5d0cf2c910506724d61cb95834` |
| libslirp | `e61dbd459c8c06607b3a84694489427e8ec60f17` |
| pntr | `a47fe167703b0ac3bfad00785b20b0dce6712509` |
| fmt | `1be298e1bd68957e4cd352e1f676f00e07dcfb57` |
| yamc | `4e015a7e8eb0d61c34e6928676c8c78881a72d73` |
| span-lite | `f63fe2974943b5f555b928b10d910e457fbb93d4` |
| date | `179a6b921ccd6179b5b04dce812a89f71a51b4df` |
| zlib | `da607da739fa6047df13e66a2af6b8bec7c2a498` |

**Licensed P3 homebrew test content:**
`test/nds/tests_sys_mem_regions.nds` belongs to the pinned upstream
`JesseTG/melonds-ds` test suite (repository GPL license; built with BlocksDS),
SHA-256 `9d7f86224faec8da2b22b103cb2ff9a84c9120cc33b5cc43498d108924b5813f`.
It remains only in local Git-ignored development material. This upstream
fixture exercises real DS boot, frames, input polling and audio callbacks; it
does **not** prove touch/game response, audible sound or cartridge SRAM cold
persistence, because this homebrew uses a separate virtual SD and exposes no
`RETRO_MEMORY_SAVE_RAM` region.

The combined P3 diagnostic APK includes unmodified GPL-covered melonDS native
code and upstream assets. No public APK distribution or final application
license has been authorized. Any subsequent distribution would require a
separate license/compliance and source-availability review, including upstream
notices, dependency licenses and GPL obligations. The personal offline
engineering build is not a release.
