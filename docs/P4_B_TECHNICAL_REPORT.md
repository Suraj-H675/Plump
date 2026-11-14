# P4-B — process session ownership and lifecycle receipt

**Date:** 2026-11-14.

**Status: implemented, full Android build/lint and host regression PASS;
physical-device lifecycle and JNI verification PENDING; mastermind acceptance
PENDING.** This report records P4-B on `main`, based on the accepted P4-A
commit `ecd72f51f5c29cae10b5c37484e201541bfb165b`. The final P4-B
local-commit identity is recorded in the handoff. No phone, personal ROM,
personal save, or private payload was accessed.

## Scope implemented

`EmulatorSessionOwner` is a small process-wide owner shared by the existing GBA
and DS Activities. It has separate resident GBA and DS game identities and one
exclusive active run/audio slot. A fair transition lock serializes initialize,
open/switch, resume, pause, close, native-save completion, and shutdown. Host
and run tokens reject stale Activity, worker, input, surface, diagnostic, and
error callbacks. Repeated initialize/resume requests are idempotent. Frame and
audio workers also require their current run token and each Activity rejects
start while either prior worker is still alive.

Pause releases virtual buttons and the DS stylus, requests loop/audio stop,
joins both workers independently, clears queued audio, writes the existing
native save, then publishes `PAUSED`. Switching or closing requires safe stop,
native-save durability, actual native unload, and then a durable `CLOSED`
receipt; Activity close additionally shuts down the core before `CLOSED` is
published. A join timeout, native-save
error, worker-start error, or receipt error leaves the session blocked from
resume, switch, and unload until a retry succeeds. A periodic save error also
invalidates the active run token so the workers exit and later lifecycle
operations must resolve it. A failed native core shutdown is quarantined for
the rest of that process, preventing new games, false clean receipts, and
unsafe reuse. GBA can remain paused in memory beneath DS; DS must
pause successfully before GBA resumes. Independent review added fail-closed
handling for exceptions without messages and failed partially loaded ROM
cleanup: an uncertain native host cannot open another game or be retired as
clean without process restart.

`SessionReceiptStore` stores two checksummed generations per console and ROM
SHA-256 under app-private `session-receipts/`. Each record contains only format
version, console, ROM digest, lifecycle state, generation, and SHA-256 checksum.
It stores no ROM URI, path, frame, save-state, or native-save payload. The store
writes a same-directory temporary file, forces file contents, atomically
renames over the older/invalid slot, and forces the containing directory. It
reports publication errors even after rename or directory sync failpoints.
Generation exhaustion refuses to overwrite valid history, and receipt reads
reject objects larger than 512 bytes before allocating their contents. A
missing or wholly corrupt identity reads as `UNKNOWN`; a valid older slot is
returned with a corruption warning if its peer is damaged. `RUNNING` is written
before worker creation. A cold read treats it as an interrupted or unresolved
run-start boundary; it does not prove that a frame ran or that an exact-frame
state exists.

The Activities require the user to select the ROM again after Activity or
process recreation. They then load the existing per-console native-save store
and report that exact-frame resume is unavailable. No URI grant is persisted
and no game auto-launches. Activity destruction durably saves before unloading,
shuts down the native core, then publishes `CLOSED`. If any transition fails,
the process owner retains
the unresolved host and rejects reattachment rather than reinitializing an
uncertain core. DS save-state loading remains absent. The DS Libretro adapter
now explicitly forces both `melonds_homebrew_sdcard` and
`melonds_homebrew_sync_sdcard_to_host` to `disabled`; the DS status identifies
games without a cartridge save region and says virtual SD is disabled.

Rendering, screen sizing, touch/button geometry, audio PCM setup, JNI save APIs,
`GameSaveStore`, and native save formats were not changed. No upstream emulator
engine source, APK version, P4-C/E work, or 3DS code was changed.

## LFC-01–05 evidence

| Gate | Implementation | Host evidence | Device status |
|---|---|---|---|
| **LFC-01 background pause/audio stop** | Pause stops frames/audio, releases controls/stylus, saves, and publishes before another session can start. Failed transitions block reuse. | JVM transition gates pass. Existing DS host test passes stylus release, simultaneous A+B, and non-silent PSG callbacks. | Pending. No Android lifecycle, AudioTrack, or physical input test was run. |
| **LFC-02 process death** | `RUNNING` is forced before workers start; only successful pause/close publishes clean state. Cold startup never auto-launches or claims exact-frame recovery. | Receipt reconstruction reads an interrupted `RUNNING` record; missing/corrupt reads `UNKNOWN`; write boundaries are injected. | Pending. No app process-kill or cold Android launch was run. |
| **LFC-03 Activity/surface recreation** | Host-generation checks gate surfaces and input. A successful destroy closes the core; the new Activity reinitializes and asks for ROM selection. An unresolved destroy blocks reattachment. | Stale host/run callbacks are rejected in JVM tests; all Android Java sources compile. | Pending. No physical surface destroy/recreate or Activity recreation test was run. |
| **LFC-04 cold start choice** | Startup asks the user to reselect the ROM; the existing native save is the only continuation path. No URI is stored and no automatic resume occurs. | Per-identity PAUSED/CLOSED/RUNNING/UNKNOWN receipt cases pass. | Pending. UI wording and file selection were not exercised on Android. |
| **LFC-05 mutation/switch safety** | Existing ROM replacement is serialized behind stop, native save, and receipt publication. Reset, menu, screenshot, and cheat actions were not added; no such controls are present in these Activities. | Switch ordering, stale tokens, receipt failures, stop timeout, and save failure pass in owner tests. | Pending. No device game-switch or optional-action mutation test was run. |

These are P4-B implementation results, not acceptance of the full LFC gates.
Host tests do not prove Android callback ordering, AudioTrack shutdown, or
physical surface behavior.

## Verification performed

### JVM tests

The original Codex implementation passed its 52-test standalone JUnit run.
Independent review added six negative regression cases and corrected their
failures. The **complete Android Gradle JVM suite now passes 58/58 tests,
zero errors/failures/skips**, including all 40 preexisting P4-A tests. The
new tests cover single ownership, repeated and
concurrent lifecycle requests, latch-controlled transition ordering, stop/save
failure and timeout blocking, GBA-under-DS ownership, game switch order,
repeated initialization, stale run/host tokens, interrupted and clean
receipts, unknown/corrupt checksums, disk-full/IO injection, and every
temp-write/file-fsync/rename/directory-fsync failpoint. Additional independent
cases exercised a message-less ROM loader exception, failure to clean up a
partially loaded core, failed unload (not reported `CLOSED`), failed native
core shutdown (subsequent game open remains blocked), receipt generation
exhaustion, and oversized corrupt receipts. All the unsafe retry paths and
generation overflow were reproduced in initially failing tests before fixes.

**Independent command, passed:**

```sh
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
TMPDIR="$PWD/app/build/p4-b-work/tmp" \
./gradlew --offline --no-daemon --console=plain :app:testDebugUnitTest --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 10s`; nine JUnit XML suites, **58 tests, 0
failures, 0 errors, 0 skipped**. Evidence:
`app/build/p4-b-work/independent-final-jvm.log` (ignored build output).

Command used (JUnit 4.13.2 and Hamcrest 1.3 from the existing local Gradle
cache; output and temp files stayed under ignored `app/build/p4-b-work/`):

```sh
TASK_TMP="$(mktemp -d "$PWD/app/build/p4-b-work/jvm-final.XXXXXX")"
mkdir -p "$TASK_TMP/classes" "$TASK_TMP/tmp"
JUNIT="$HOME/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"
HAMCREST="$HOME/.gradle/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar"
javac -source 17 -target 17 -d "$TASK_TMP/classes" -cp "$JUNIT:$HAMCREST" \
  app/src/main/java/dev/plump/emulator/{ButtonInputState,DsButtonGeometry,DsFrameTiming,DsPortraitSizing,DsTouchMapper,GameSaveStore,SaveRamSizeValidator,SessionReceiptStore,EmulatorSessionOwner}.java \
  app/src/test/java/dev/plump/emulator/{ButtonInputStateTest,DsButtonGeometryTest,DsFrameTimingTest,DsPortraitSizingTest,DsTouchMapperTest,GameSaveStoreTest,SaveRamSizeValidatorTest,SessionReceiptStoreTest,EmulatorSessionOwnerTest}.java
java -Djava.io.tmpdir="$TASK_TMP/tmp" -cp "$TASK_TMP/classes:$JUNIT:$HAMCREST" \
  org.junit.runner.JUnitCore \
  dev.plump.emulator.ButtonInputStateTest \
  dev.plump.emulator.DsButtonGeometryTest \
  dev.plump.emulator.DsFrameTimingTest \
  dev.plump.emulator.DsPortraitSizingTest \
  dev.plump.emulator.DsTouchMapperTest \
  dev.plump.emulator.GameSaveStoreTest \
  dev.plump.emulator.SaveRamSizeValidatorTest \
  dev.plump.emulator.SessionReceiptStoreTest \
  dev.plump.emulator.EmulatorSessionOwnerTest
```

Original worker result: `OK (52 tests)` before the independent fixes. The
58-test Gradle run above supersedes it as final evidence.

### Android Java and native adapter compilation

All `app/src/main/java` sources compiled against Android 35's `android.jar`
with Java 17:

```sh
TASK_TMP="$(mktemp -d "$PWD/app/build/p4-b-work/android-javac.XXXXXX")"
mkdir -p "$TASK_TMP/classes"
find app/src/main/java -name '*.java' -print0 | xargs -0 javac \
  -source 17 -target 17 \
  -cp "$HOME/Android/Sdk/platforms/android-35/android.jar" \
  -d "$TASK_TMP/classes"
```

The changed Android JNI adapter compiled for `arm64-v8a`, API 26, with NDK
28.2.13676358:

```sh
TASK_BUILD="$(mktemp -d "$PWD/app/build/p4-b-work/native-android.XXXXXX")"
cmake -S app/src/main/cpp -B "$TASK_BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$HOME/Android/Sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release
cmake --build "$TASK_BUILD" --target plump_ds_host
```

Both original worker commands passed. Independent verification also ran the
actual Android debug APK build and lint successfully:

```sh
ANDROID_HOME="$HOME/Android/Sdk" ANDROID_SDK_ROOT="$HOME/Android/Sdk" \
TMPDIR="$PWD/app/build/p4-b-work/tmp" \
./gradlew --offline --no-daemon --console=plain :app:assembleDebug :app:lintDebug
```

Final result: `BUILD SUCCESSFUL in 12s`; ARM64 native compilation, debug packaging
and lint completed. Local debug APK SHA-256:
`a832c0d5c9dd6166ebecec46a1aa556c53330c6496973332a42d46890d1c76d6`.
**This is a build artifact only.** It was neither installed nor distributed.
Evidence: ignored `app/build/p4-b-work/independent-final-build-lint.log`.

### Existing host regressions

- `./scripts/test-rgb565-renderer.sh` — PASS, RGB565 conversion and pitch/stride.
- `./scripts/test-nds-frame-split.sh` — PASS, DS dual-screen split and pixel address conversion.
- `tests/roms/verify-p3-input-diagnostic.py` against the existing pinned host
  melonDS core and checksum-validated CC0 diagnostic ROM — PASS for stylus
  `(32,32)`, `(128,96)`, `(224,160)`, release, simultaneous A+B, and non-silent
  PSG audio callbacks.
- `tests/roms/verify-p3-native-save-diagnostic.py` in separate `first` and
  `cold` processes — PASS for synthetic 8 KiB DS EEPROM persistence.
- The same DS diagnostic's `state-rewind` mode — PASS safety reproduction: an
  older DS state rewound newer in-memory EEPROM, so DS state loading remains
  disabled.
- Independent review also successfully ran the **real pinned mGBA** Libretro
  core against the existing licensed MIT `gba-tests/save/sram.gba` fixture in
  two fresh host processes: first wrote genuine in-game 32 KiB SRAM, cold
  process restored exactly the same bytes before its first frame and executed
  more frames. ROM SHA-256
  `a37ad99c31e3f805eb05a00e498b65bd78e6f43a0a139cd695bea1f88229af2c`;
  host core SHA-256
  `af229495b5cbbff309cfa49d50fbe7dbdce2e26aad1deb4f79e9bd7fa9e2ff7c`;
  disposable SRAM SHA-256
  `153085c624624d900f5856be1c95fefb00c97086c03b509d8544c0ade970783b`.
  Both processes printed `PASS`; verification source is
  `tests/roms/verify-p4-a-gba-host.py`.

The input/audio run used:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -u tests/roms/verify-p3-input-diagnostic.py \
  app/build/p3-melonds-host/src/libretro/melondsds_libretro.so \
  app/build/p3-input-diagnostic/p3_input_diagnostic.nds \
  app/build/p4-b-work/tmp/host-ds-input.FCixYF
```

The three save runs used separate process invocations and isolated directories:

```sh
TASK_TMP=app/build/p4-b-work/tmp/host-ds-save.trfo0c
mkdir -p "$TASK_TMP/session" "$TASK_TMP/state"
PYTHONDONTWRITEBYTECODE=1 python3 -u tests/roms/verify-p3-native-save-diagnostic.py \
  first app/build/p3-melonds-host/src/libretro/melondsds_libretro.so \
  app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds "$TASK_TMP/session"
PYTHONDONTWRITEBYTECODE=1 python3 -u tests/roms/verify-p3-native-save-diagnostic.py \
  cold app/build/p3-melonds-host/src/libretro/melondsds_libretro.so \
  app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds "$TASK_TMP/session"
PYTHONDONTWRITEBYTECODE=1 python3 -u tests/roms/verify-p3-native-save-diagnostic.py \
  state-rewind app/build/p3-melonds-host/src/libretro/melondsds_libretro.so \
  app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds "$TASK_TMP/state"
```

The direct DS verifier commands used the existing ignored fixtures and wrote
only under `app/build/p4-b-work/tmp/`. The normal input wrapper,
`./scripts/test-p3-input-diagnostic.sh`, was also attempted but stopped before
build because its pinned BlocksDS Docker image is not installed. The existing
CC0 ROM was run directly; its verifier checked the expected ROM SHA. Host
melonDS tests do not execute the Android adapter's forced-option callback.

## Blockers and limits

- The original Codex sandbox could not run the ordinary Gradle wrapper and
  did not locate the licensed MIT GBA fixture. Independent DevSpace checks
  **resolved both test-environment blockers**, ran the complete Gradle
  build/lint/58-test suite and independently cold-tested the verified fixture.
  This changes the host/build evidence, not the permission for phone testing.
- No APK install, phone test, real Activity callback test, Android process
  kill, or physical AudioTrack/surface reattachment validation was performed.
  A future, separately authorized **data-preserving** version upgrade and
  disposable-fixture device regression are required before LFC acceptance.
- The Java NIO receipt-directory `FileChannel.force` and Android lifecycle
  callback ordering have been **compiled but not independently proven on the
  target phone**. If a receipt publication fails on-device, the current
  fail-closed policy prevents starting or switching games rather than
  falsely claiming safe persistence.
- A clean receipt records orderly native shutdown only after its confirmed
  publication. A crash after physical core shutdown but before that
  publication may leave an older `RUNNING` or `PAUSED` receipt. Recovery
  classification remains conservative rather than fabricating a clean exit.
- A failed native core shutdown is conservatively quarantined for the
  remaining process lifetime; it cannot be automatically retried as though
  the core were known intact. The fallback is a fresh process and validated
  in-game native save, not an exact-frame state.
- If a stop/save/receipt cannot be resolved during destruction, the retained
  process host intentionally blocks a new Activity attachment. Recovery then
  requires successful cleanup in-process or process death followed by ROM
  reselection and native-save fallback. A failed *partial ROM-load cleanup*
  specifically requires a fresh process before another game may start, as the
  core's native ownership cannot be trusted. This slice does not offer
  exact-frame resume or a compatible machine-state recovery chooser.

## Change boundary

Changed source and tests are limited to the process owner, session receipt
store, existing GBA/DS Activity lifecycle/input callback routing, GBA control
callback routing, explicit DS homebrew-SD disable options, and the two new JVM
test classes. `GameSaveStore`, JNI save format, rendering geometry, audio
pipeline, private source documents, and existing P3/P4 planning reports were
not edited by this work. No push or phone install was made. The exact local
commit is recorded in the final mastermind handoff after staged review.

## P4-B engineering review verdict

**Host/build gate PASS, physical-device gate PENDING, mastermind decision
PENDING.** Save-state loading and virtual SD writes remain disabled; native
save directories and pinned engine revisions are unchanged. LFC-01 to LFC-05
have the partial implementation and host evidence in the matrix above, not
fully accepted real-phone survival guarantees. No P4-C, P4-E or 3DS work is
authorized or implied by this result.
