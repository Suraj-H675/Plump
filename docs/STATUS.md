# Plump development status

Last documented: 2026-11-13 (P2 mastermind accepted; P3 on-device screens/lifecycle, host-verified interactive DS stylus+chord+PSG, phone input/audio pending).

| Area | Status |
|---|---|
| Product scope and five discovery rounds | User decisions recorded in local source pack |
| Home/library concept | Provisional user approval; local OpenPencil editable design |
| GBA and DS portrait concept | GBA sketch implementation device-tested in P2; DS dual-screen portrait layout, controls flanking lower panel, and enlargement visually verified on phone with one licensed test ROM |
| Emulator core selections | P1 mGBA Libretro accepted; P3 uses pinned melonDS DS Libretro software renderer as a testable candidate, not a final architecture decision |
| Android build/APK | ARM64 `0.2.0-p3-ds-spike` (code 5) installed on iQOO Neo 6 (Android 14), with all eight existing P2 save/recovery files byte-identical after upgrade; accepted P2 code retained |
| Real game execution | Owner verified real `hello.gba` and `sram.gba` in P2; in P3, genuine GPL NDS memory-test game displayed real results with both independent screens and expected START shutdown. New interactive CC0 DS program host-verified, phone test pending |
| Actual phone performance | P3 owner screenshot reports 1,443 posted of 1,443 DS frames, zero rejected, 8-ms average/17-ms maximum core time for a short test interval. Sustained performance/thermal pacing unmeasured |
| Save and recovery integrity | Real `sram.gba` on iQOO wrote two valid 32-KiB SRAM generations with expected ROM writes; both independently read and checksummed. Plump force-stop/cold launch verified full 32,768-byte core restore of generation 2. A separate disposable diagnostic ROM recovered generation 4 after the newer generation 5 checksum was corrupted. Both test generations restored from backup afterward |
| Offline operation | Manifest declares no Internet permission and excludes platform backups; runtime offline behavior still unverified |
| GitHub remote | Existing public Suraj-H675/Plump; accepted P2 batch pushed at `d1cef15`; P3 local only |
| Local development | P2 GBA device tests accepted; pinned melonDS DS ARM64 and host core built. CC0 BlocksDS DS diagnostic runs real ARM9/ARM7 homebrew with precise 3-point stylus response, release, simultaneous A+B+touch and PSG sample audio in host melonDS; new ROM copied to isolated P3 device folder with matching checksum; no further APK update |

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

**The mastermind accepted P2 at `d1cef15b73c490eb2aa3e7a2c66a2e25ed8004a4`.** Extended
performance and audio synchronization measurement, power-loss/dual-corrupt-save
testing, and many future product guarantees remain unverified. The P2 APK is a
diagnostic vertical slice, not a product release or replacement for the owner's
provisional OpenPencil UI.

## P3 technical slice — local source and host verification only

The pinned melonDS DS Libretro source (revision `f394adba`) and pinned native
dependencies compile to a real Android ARM64 software core. A second native
build executed its GPL-covered `tests_sys_mem_regions.nds` homebrew on the host
for 100 real frames. Both native 256×192 display regions contained pixel data;
only the lower screen changed in that particular test. The Libretro callback
reported 54,705 audio sample frames and 100 input polls. These are **host**
results, not phone or audible-audio proof. `DsActivity`, `NativeDs`, JNI
surface split, touchscreen mapper, D-pad and A/B/X/Y/L/R/Start/Select side
controls, lower enlargement, audio and native SRAM persistence plumbing are
implemented. P2 GBA code and GBA save directory were kept intact.

The local version 5 (`0.2.0-p3-ds-spike`) debug APK compiles and passed lint,
16 JVM tests and native pixel assertions. **It has not been installed, touched
or tested on the owner's phone.** P3 DS-01..08 still need real device tests,
especially simultaneous stylus/button behavior, SD-card save protection,
native DS cartridge cold-save persistence and sustained performance. DSi mode
is disabled and requires separately lawful firmware/NAND and its own save
integrity process; DSi savestates and camera are unavailable in this core.
See [P3 technical report](P3_TECHNICAL_REPORT.md) and the source ledger.
