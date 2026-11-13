# Plump development status

Last documented: 2026-11-13 (P2 verified GBA display, background behavior and SRAM-write generations on iQOO Neo 6; cold-load pending).

| Area | Status |
|---|---|
| Product scope and five discovery rounds | User decisions recorded in local source pack |
| Home/library concept | Provisional user approval; local OpenPencil editable design |
| GBA and DS portrait concept | User-supplied sketch reference; implementation untested |
| Emulator core selections | P1 accepted mGBA Libretro for GBA spike, pinned SHA in upstream ledger; DS/3DS architecture not locked |
| Android build/APK | Real ARM64 debug APK `0.1.2-p2-surface` installed and visually verified on iQOO Neo 6 (Android 14); candidate `0.1.3-p2-sram-verify` adds native SRAM restore readback check |
| Real game execution | Owner visually verified `hello.gba` renders actual “Hello world!” on iQOO Neo 6; `sram.gba` displayed “All tests passed” |
| Actual phone performance | Not measured |
| Save and recovery integrity | Real `sram.gba` on iQOO wrote two valid 32-KiB SRAM generations containing expected ROM test writes; both independently read, format/hash verified. Cold-restart restore readback, corruption recovery, and force-stop still pending |
| Offline operation | Manifest declares no Internet permission and excludes platform backups; runtime offline behavior still unverified |
| GitHub remote | Existing Suraj-H675/Plump, currently public |
| Local development | Pin-checked mGBA ARM64 `.so` and Android host build; first real Android GBA display, basic pause/resume, background roundtrip and cartridge SRAM write established, not full compatibility |

The owner authorized initial installation of APK SHA-256
`93b3fa9e4fbe2dd398f388c0bc72c9b2f96e652d8460a6217ee73825c72da612`
and copying two specified MIT fixtures to `/sdcard/Plump-P2-Test`.
Both transfers and startup passed, but **the first on-device `hello.gba` display
remained black**. Local source changes convert mGBA RGB565 into an Android
RGBA8888 window, expose received/posted/pixel counters, and have a separate
host-side pixel/stride unit test. This is a candidate diagnosis, not a proven
device fix. The owner subsequently authorized routine Plump-only P2 test
updates, and `0.1.1-p2-render` was installed preserving app data. Android
reported more than 1,200 emulator frames and nearly as many successfully
posted, with 209 non-black pixels in the source buffer, but the owner-provided
screenshot still showed an entirely black gameplay region. The likely cause
is the solid black View background on the SurfaceView, drawn over the native
surface through Android's window composition. That background was removed in
`0.1.2-p2-surface`; owner confirmed visible “Hello world!” and successfully
tested pause/resume, Android Home and Recent Apps return. The licensed
`sram.gba` displayed “All tests passed”; two 32,768-byte native SRAM saves in
the app sandbox were independently verified for correct magic, game identity,
generation, SHA-256 envelope and ROM-written markers. To avoid mistaking a
freshly initialized SRAM for restored bytes, `0.1.3-p2-sram-verify` adds a
full SRAM readback comparison and Plump-only diagnostic before executing frames.
The restore and cold restart have not yet been observed on the device.
Remaining acceptance gates include
GBA-01/02/04/05/06/08 on the actual device: native frame, audio, multi-touch,
save persistence and Android background/force-stop correctness. The P2 APK
is a diagnostic vertical slice, not a release or a replacement for the owner's
provisional OpenPencil UI. DS/3DS implementation is out of this checkpoint.
