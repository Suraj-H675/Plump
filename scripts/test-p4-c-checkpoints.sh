#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$root/app/build/p4-c-work"
core="$root/app/build/p4-a-work/mgba-host/mgba_libretro.so"
rom="${PLUMP_GBA_SRAM_FIXTURE:-${HOME}/.cache/plump-p2-rom-research/gba-tests/save/sram.gba}"
java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
test -f "$core" && test -f "$rom" || {
  echo 'Pinned mGBA host core or licensed MIT SRAM fixture is missing.' >&2
  exit 1
}
mkdir -p "$work/mgba-host" "$work/mgba-unsupported" "$work/mgba-mutating"
ln -sfn "$core" "$work/mgba-host/libmgba_libretro.so"
probe_classes="$work/probe-classes"
mkdir -p "$probe_classes"
javac --release 17 -d "$probe_classes" \
  "$root/app/src/main/java/dev/plump/emulator/CheckpointStore.java" \
  "$root/app/src/main/java/dev/plump/emulator/GameSaveStore.java" \
  "$root/app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java" \
  "$root/app/src/main/java/dev/plump/emulator/ActiveTimelineStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/NativeSavePromotionStore.java" \
  "$root/app/src/main/java/dev/plump/emulator/SessionReceiptStore.java" \
  "$root/app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java" \
  "$root/app/src/test/java/dev/plump/emulator/HostCheckpointProbe.java"
export PLUMP_JVM_ERROR_FILE="$work/mgba-hs_err_pid%p.log"
compiler=(g++ -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$root/tests/native/android-host-stubs" \
  -I"$root/app/src/main/cpp" \
  -I"$java_home/include" -I"$java_home/include/linux")
libs=(-L"$java_home/lib/server" -Wl,-rpath,"$java_home/lib/server" -Wl,--export-dynamic -ldl -pthread -ljvm)
"${compiler[@]}" "$root/tests/native/mgba_checkpoint_test.cpp" \
  "${libs[@]}" -o "$work/mgba_checkpoint_test"
"${compiler[@]}" -shared -fPIC "$root/tests/native/unsupported_serializer_libretro.cpp" \
  -o "$work/mgba-unsupported/libmgba_libretro.so"
"${compiler[@]}" -shared -fPIC "$root/tests/native/mutating_serializer_libretro.cpp" \
  -o "$work/mgba-mutating/libmgba_libretro.so"
session_work="$work/session-owner-gba-$(date +%s%N)"
rom_sha="$(sha256sum "$rom" | awk '{print $1}')"
host_sha="$(sha256sum "$core" | awk '{print $1}')"
LD_LIBRARY_PATH="$work/mgba-host${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$work/mgba_checkpoint_test" "$rom" "$session_work" "$probe_classes" "$rom_sha" "$host_sha"
LD_LIBRARY_PATH="$work/mgba-unsupported${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$work/mgba_checkpoint_test" --unsupported-serializer
LD_LIBRARY_PATH="$work/mgba-mutating${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$work/mgba_checkpoint_test" --mutating-serializer
PLUMP_TEST_OVERSIZED_STATE=1 \
  LD_LIBRARY_PATH="$work/mgba-mutating${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "$work/mgba_checkpoint_test" --oversized-serializer

bash "$root/scripts/test-p4-br-ds-reload.sh"

python3 -u "$root/tests/roms/verify-p4-c-checkpoint.py" gba "$core" "$rom" "$work/metrics-gba"
ds_core="$root/app/build/p3-melonds-host/src/libretro/melondsds_libretro.so"
ds_rom="$root/app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds"
python3 -u "$root/tests/roms/verify-p4-c-checkpoint.py" ds "$ds_core" "$ds_rom" "$work/metrics-ds"

python3 -u "$root/tests/roms/verify-p2-input-audio.py" \
  "$core" "$root/app/build/p2-test-fixtures/p2-input-audio.gba"
python3 -u "$root/tests/roms/verify-p3-input-diagnostic.py" \
  "$ds_core" "$root/app/build/p3-input-diagnostic/p3_input_diagnostic.nds" "$work/ds-input"

save_session="$(mktemp -d "$work/ds-native-save-XXXXXXXX")"
python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" first "$ds_core" "$ds_rom" "$save_session"
python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" cold "$ds_core" "$ds_rom" "$save_session"
rewind_session="$(mktemp -d "$work/ds-raw-rewind-XXXXXXXX")"
python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" state-rewind "$ds_core" "$ds_rom" "$rewind_session"
