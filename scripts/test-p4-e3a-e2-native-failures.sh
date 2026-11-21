#!/usr/bin/env bash
# Recheck accepted E2 genuine native fault hooks against real pinned Linux
# melonDS, never Android, backups, or user storage. Every process has its own
# disposable, isolated E2 generation store with a retained source baseline.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
core="$root/app/build/p4-e2-work/melondsds_libretro_e2.so"
rom="$root/app/build/p4-e1-b/rom-alpha/plump_e1b_alpha.nds"
baseline="$root/app/build/p4-e3a/baseline/dldi_sd_card.bin"
python_bin="$root/app/build/p4-e1-b/fat-inspector-venv/bin/python"
classes="app/build/intermediates/javac/productionDebug/compileProductionDebugJavaWithJavac/classes:app/build/intermediates/javac/productionDebugUnitTest/compileProductionDebugUnitTestJavaWithJavac/classes"
test "$(sha256sum "$core" | cut -d' ' -f1)" = 3737f32e0c438a2dd5836abe1786c58ff02e93525239027c5d78b4c6b9df38c9
test "$(sha256sum "$rom" | cut -d' ' -f1)" = 1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588
test "$(stat -c%s "$baseline")" -eq $((128*1024*1024))
test -x "$python_bin"
mkdir -p app/build/p4-e3a/regressions/native-failures
export PLUMP_E2_REAL_BASELINE_IMAGE="$baseline"
export PLUMP_E2_PYTHON="$python_bin"
baseline_sha="$(sha256sum "$baseline" | cut -d' ' -f1)"
for point in PLUMP_E2_FAIL_NEXT_DLDI_WRITE PLUMP_E2_FAIL_STREAM_FLUSH PLUMP_E2_FAIL_FILE_CLOSE; do
    work="$(mktemp -d "$root/app/build/p4-e3a/regressions/native-failures/${point}-XXXXXXXX")"
    rc=0
    env "$point=1" java -cp "$classes" dev.plump.emulator.VirtualSdRealCoreProbe \
        "$core" "$rom" "$work" > "$work/injected.log" 2>&1 || rc=$?
    if (( rc == 0 )); then
        echo "UNSAFE: injected $point unexpectedly committed both real game writes" >&2
        exit 1
    fi
    test -d "$work/canonical/generations"
    test "$(find "$work/canonical/generations" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 1
    test "$(find "$work/canonical/working" -mindepth 1 -maxdepth 1 -type d | wc -l)" -ge 1
    test ! -e "$work/canonical/HEAD.0" # no second-generation committed head
    test -f "$work/canonical/HEAD.1"
    grep -q '^sequence=1$' "$work/canonical/HEAD.1"
    head_image="$(find "$work/canonical/generations" -type f -name dldi_sd_card.bin | head -1)"
    test -n "$head_image"
    test "$(sha256sum "$head_image" | cut -d' ' -f1)" = "$baseline_sha"
    test "$(sha256sum "$baseline" | cut -d' ' -f1)" = "$baseline_sha"
    echo "PASS_E3_E2_NATIVE_FAULT_REJECTED point=$point process_exit=$rc canonical_sequence=1 original_sha256=$baseline_sha"
done
echo 'PASS_E3_REAL_E2_NATIVE_FAILURE_MATRIX: all three native defects block publication and preserve original canonical image'
