# Upstream source/provenance — P2

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
