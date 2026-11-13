# Plump development status

Last documented: 2026-11-13 (P2 first device-rendering failure and local diagnostic correction).

| Area | Status |
|---|---|
| Product scope and five discovery rounds | User decisions recorded in local source pack |
| Home/library concept | Provisional user approval; local OpenPencil editable design |
| GBA and DS portrait concept | User-supplied sketch reference; implementation untested |
| Emulator core selections | P1 accepted mGBA Libretro for GBA spike, pinned SHA in upstream ledger; DS/3DS architecture not locked |
| Android build/APK | First GBA ARM64 debug APK `0.1.0-p2` installed and launched on iQOO Neo 6 (Android 14); renderer did not display `hello.gba`. Diagnostic renderer update `0.1.1-p2-render` built locally only; no reinstall authorized yet |
| Real game execution | Actual MIT test ROMs ran through pinned mGBA on Linux host; device reported black video with `hello.gba`, so GBA-01/GBA-02 remain FAILED/PENDING |
| Actual phone performance | Not measured |
| Save and recovery integrity | Two-generation SHA-256 native SRAM store implemented; 7 JVM save tests passed, plus 3 independent multi-touch state tests; device force-stop, real game save, and recovery UX unverified |
| Offline operation | Manifest declares no Internet permission and excludes platform backups; runtime offline behavior still unverified |
| GitHub remote | Existing Suraj-H675/Plump, currently public |
| Local development | Pin-checked mGBA ARM64 `.so` and Android host build; no actual phone tests, compatibility or performance claims |

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
`0.1.2-p2-surface`; the revised APK must still be tested on the target phone.
Remaining acceptance gates include
GBA-01/02/04/05/06/08 on the actual device: native frame, audio, multi-touch,
save persistence and Android background/force-stop correctness. The P2 APK
is a diagnostic vertical slice, not a release or a replacement for the owner's
provisional OpenPencil UI. DS/3DS implementation is out of this checkpoint.
