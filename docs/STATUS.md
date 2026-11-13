# Plump development status

Last documented: 2026-11-13 (P2 GBA device-test suite executed; awaiting mastermind milestone acceptance).

| Area | Status |
|---|---|
| Product scope and five discovery rounds | User decisions recorded in local source pack |
| Home/library concept | Provisional user approval; local OpenPencil editable design |
| GBA and DS portrait concept | User-supplied sketch reference; implementation untested |
| Emulator core selections | P1 accepted mGBA Libretro for GBA spike, pinned SHA in upstream ledger; DS/3DS architecture not locked |
| Android build/APK | Real ARM64 debug APK `0.1.3-p2-sram-verify` installed and running on iQOO Neo 6 (Android 14), preserving app data |
| Real game execution | Owner visually verified `hello.gba` renders actual “Hello world!” on iQOO Neo 6; `sram.gba` displayed “All tests passed” |
| Actual phone performance | Not measured |
| Save and recovery integrity | Real `sram.gba` on iQOO wrote two valid 32-KiB SRAM generations with expected ROM writes; both independently read and checksummed. Plump force-stop/cold launch verified full 32,768-byte core restore of generation 2. A separate disposable diagnostic ROM recovered generation 4 after the newer generation 5 checksum was corrupted. Both test generations restored from backup afterward |
| Offline operation | Manifest declares no Internet permission and excludes platform backups; runtime offline behavior still unverified |
| GitHub remote | Existing Suraj-H675/Plump, currently public |
| Local development | Pin-checked mGBA ARM64 `.so`, Android host build; owner confirmed real Android GBA display, all mapped control responses including A+B, audible test tone on A, pause/resume, Home/Recent Apps return, cartridge SRAM cold-load. Local licensed-header ARM keypad/PSG test ROM and host assertions pass |

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
framebuffer colors and PSG audio: host mGBA showed distinct key colors,
yellow simultaneous A+B and nonzero A-pressed audio peaks. On the actual
phone the owner confirmed **all** mapped controls (including L/R and Start/Select),
A+B multitouch, and audible PSG tone on A with silence on release.

In a disposable diagnostic game only, generation 5 was deliberately made
checksum-invalid after preserving two exact backup copies. The subsequent
cold app launch and game load logged `Native SRAM restore readback VERIFIED:
generation=4 bytes=131072`, while game frames and all remaining button tests
worked. Subsequent generations 6 and 7 had valid checksums. The original two
pre-test generations were restored byte-for-byte after stopping Plump; another
game's SRAM files retained their original SHA-256 hashes. Full evidence and
remaining limitations are in [P2_DEVICE_TEST_REPORT.md](P2_DEVICE_TEST_REPORT.md).

**P2 bounded acceptance evidence is ready for mastermind review.** Extended
performance and audio synchronization measurement, power-loss/dual-corrupt-save
testing, and many future product guarantees remain unverified. The P2 APK is a
diagnostic vertical slice, not a product release or replacement for the owner's
provisional OpenPencil UI. DS/3DS implementation is out of this checkpoint.
