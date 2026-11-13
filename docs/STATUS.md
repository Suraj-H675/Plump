# Plump development status

Last documented: 2026-11-13 (P2 verified GBA display, backgrounding and cold-restart SRAM readback on iQOO Neo 6; interactive input/audio pending).

| Area | Status |
|---|---|
| Product scope and five discovery rounds | User decisions recorded in local source pack |
| Home/library concept | Provisional user approval; local OpenPencil editable design |
| GBA and DS portrait concept | User-supplied sketch reference; implementation untested |
| Emulator core selections | P1 accepted mGBA Libretro for GBA spike, pinned SHA in upstream ledger; DS/3DS architecture not locked |
| Android build/APK | Real ARM64 debug APK `0.1.3-p2-sram-verify` installed and running on iQOO Neo 6 (Android 14), preserving app data |
| Real game execution | Owner visually verified `hello.gba` renders actual “Hello world!” on iQOO Neo 6; `sram.gba` displayed “All tests passed” |
| Actual phone performance | Not measured |
| Save and recovery integrity | Real `sram.gba` on iQOO wrote two valid 32-KiB SRAM generations with expected ROM writes; both independently read and checksummed. After Plump-only force-stop and cold launch, the core logged successful full 32,768-byte SRAM readback of generation 2 before executing frames. Corruption recovery remains pending |
| Offline operation | Manifest declares no Internet permission and excludes platform backups; runtime offline behavior still unverified |
| GitHub remote | Existing Suraj-H675/Plump, currently public |
| Local development | Pin-checked mGBA ARM64 `.so`, Android host build; real Android GBA display, pause/resume, Home/Recent Apps return, cartridge SRAM cold-load established. Original licensed-header ARM keypad/PSG test ROM built and verified with host mGBA; phone input and audio remain pending |

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
On November 13, the installed `0.1.3-p2-sram-verify` passed a Plump-only
`am force-stop` and fresh launch. After the owner opened the same test ROM,
Plump logged `Native SRAM restore readback VERIFIED: generation=2 bytes=32768`,
and rendered frames continued. The complete generated save matched in-memory
SRAM before the first new emulation frame; this constitutes GBA-06 evidence
for the tested fixture, not for arbitrary commercial cartridges.
An original GBA ARM7TDMI test fixture now exercises real KEYINPUT-controlled
framebuffer colors and PSG audio: host mGBA showed distinct A/B/UP/R colors,
yellow simultaneous A+B and nonzero A-pressed audio peaks. Physical Android
input and audible speaker output are still to be verified with that fixture.
Remaining acceptance gates include
GBA-04/05 on the actual device: native button/multitouch and audible audio,
plus safe corrupt-save recovery and stability. The P2 APK
is a diagnostic vertical slice, not a release or a replacement for the owner's
provisional OpenPencil UI. DS/3DS implementation is out of this checkpoint.
