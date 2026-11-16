#!/usr/bin/env bash
# JNI adapter regression against real pinned Linux melonDS, not a mocked core.
# Compile host Android surfaces/log shims solely for this desktop test.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$root/app/build/p4-br-ds-reload"
core="$root/app/build/p3-melonds-host/src/libretro/melondsds_libretro.so"
rom1="$root/app/build/p3-input-diagnostic/p3_input_diagnostic.nds"
rom2="$root/app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds"
saved="$root/app/build/p4-b-native-crash-review/fixture/saved-sram.bin"
java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
test -f "$core" && test -f "$rom1" && test -f "$rom2" && test -f "$saved"
mkdir -p "$work/system" "$work/save" "$work/unsupported" "$work/mutating"
probe_classes="$work/probe-classes"
mkdir -p "$probe_classes"
javac --release 17 -d "$probe_classes" \
    "$root/app/src/main/java/dev/plump/emulator/CheckpointStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/GameSaveStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/ActiveTimelineStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/SessionReceiptStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java" \
    "$root/app/src/test/java/dev/plump/emulator/HostCheckpointProbe.java"
export PLUMP_JVM_ERROR_FILE="$work/hs_err_pid%p.log"
ln -sfn "$core" "$work/libmelondsds_libretro.so"
compiler=(g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    -I"$root/tests/native/android-host-stubs" \
    -I"$root/app/src/main/cpp" \
    -I"$java_home/include" -I"$java_home/include/linux")
libs=(-L"$java_home/lib/server" -Wl,-rpath,"$java_home/lib/server" -Wl,--export-dynamic -ldl -pthread -ljvm)
"${compiler[@]}" "$root/tests/native/nds_host_lifecycle_test.cpp" \
    "${libs[@]}" -o "$work/nds_host_lifecycle_test"

if [[ "${1:-}" == '--prove-before-after' ]]; then
    # Use the exact installed-v7 base source, isolated in an ignored build path.
    # The old adapter MUST reproduce SIGSEGV; never run it on an Android phone.
    git -C "$root" show 6eced08981487da515ca3d603f69c30d0f840173:app/src/main/cpp/nds_host.cpp \
        > "$work/nds_host_unsafe_pre_p4br.cpp"
    "${compiler[@]}" -DPLUMP_TEST_OLD_ADAPTER -DPLUMP_DS_HOST_SOURCE="\"$work/nds_host_unsafe_pre_p4br.cpp\"" \
        "$root/tests/native/nds_host_lifecycle_test.cpp" "${libs[@]}" -o "$work/nds_host_pre_p4br_test"
    set +e
    (ulimit -c 0; LD_LIBRARY_PATH="$work${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
       "$work/nds_host_pre_p4br_test" "$rom1" "$rom2" "$saved" "$work") \
        > "$work/before-unsafe.log" 2>&1
    baseline_status=$?
    set -e
    if [[ "$baseline_status" != 134 && "$baseline_status" != 139 ]] ||
        ! grep -q 'SIGSEGV' "$work/before-unsafe.log" ||
        ! grep -q 'RenderStateWrapper::UpdateRenderer' "$work/before-unsafe.log"; then
        echo "Expected old adapter UpdateRenderer SIGSEGV; process exit $baseline_status" >&2
        exit 1
    fi
    echo "BEFORE: installed-v7 JNI host SIGSEGV in UpdateRenderer on second DS load (exit $baseline_status)"
fi
session_work="$work/session-owner-$(date +%s%N)"
mkdir -p "$session_work/system" "$session_work/save"
rom_sha="$(sha256sum "$rom2" | awk '{print $1}')"
host_sha="$(sha256sum "$core" | awk '{print $1}')"
LD_LIBRARY_PATH="$work${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$work/nds_host_lifecycle_test" "$rom1" "$rom2" "$saved" "$session_work" \
    "$probe_classes" "$rom_sha" "$host_sha"

# A deliberately incomplete Libretro library exercises early dlopen/dlsym
# cleanup independently of the real-core multi-game test above.
mkdir -p "$work/missing-symbols"
printf '%s\n' 'extern "C" void retro_set_environment(void*) {}' | \
    g++ -shared -fPIC -x c++ - -o "$work/missing-symbols/libmelondsds_libretro.so"
LD_LIBRARY_PATH="$work/missing-symbols${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$work/nds_host_lifecycle_test" --missing-symbols

g++ -std=c++17 -Wall -Wextra -Werror -shared -fPIC \
    "$root/tests/native/unsupported_serializer_libretro.cpp" \
    -o "$work/unsupported/libmelondsds_libretro.so"
LD_LIBRARY_PATH="$work/unsupported${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$work/nds_host_lifecycle_test" --unsupported-serializer

g++ -std=c++17 -Wall -Wextra -Werror -shared -fPIC \
    "$root/tests/native/mutating_serializer_libretro.cpp" \
    -o "$work/mutating/libmelondsds_libretro.so"
LD_LIBRARY_PATH="$work/mutating${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$work/nds_host_lifecycle_test" --mutating-serializer
PLUMP_TEST_OVERSIZED_STATE=1 \
    LD_LIBRARY_PATH="$work/mutating${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
    "$work/nds_host_lifecycle_test" --oversized-serializer
