#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

core="${PLUMP_E2_CORE:-app/build/p4-e2-work/melondsds_libretro_e2.so}"
core_sha="${PLUMP_E2_CORE_SHA256:-3737f32e0c438a2dd5836abe1786c58ff02e93525239027c5d78b4c6b9df38c9}"
rom="${PLUMP_E2_ROM:-}"
baseline="${PLUMP_E2_REAL_BASELINE_IMAGE:-}"
python_bin="${PLUMP_E2_PYTHON:-app/build/p4-e1-b/fat-inspector-venv/bin/python}"
out="${PLUMP_E2_CRASH_OUTPUT:-app/build/p4-e2-work/process-crash}"

[[ -f "$core" && -f "$rom" && -f "$baseline" && -x "$python_bin" ]] || {
    echo "Set PLUMP_E2_CORE, PLUMP_E2_ROM, PLUMP_E2_REAL_BASELINE_IMAGE and PLUMP_E2_PYTHON to the verified host fixtures." >&2
    exit 1
}
[[ "$(sha256sum "$core" | awk '{print $1}')" == "$core_sha" ]] || { echo "E2 core SHA-256 mismatch" >&2; exit 1; }
[[ "$(sha256sum "$rom" | awk '{print $1}')" == "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588" ]] || { echo "E2 ROM SHA-256 mismatch" >&2; exit 1; }
[[ "$(stat -c '%s' "$baseline")" == "$((128 * 1024 * 1024))" ]] || { echo "E2 baseline is not 128 MiB" >&2; exit 1; }

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}" \
    ./gradlew :app:testDebugUnitTest --no-daemon >/dev/null

rm -rf "$out"
mkdir -p "$out"
classes="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:app/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"

set +e
PLUMP_E2_REAL_BASELINE_IMAGE="$baseline" PLUMP_E2_PYTHON="$python_bin" \
    java -cp "$classes" dev.plump.emulator.VirtualSdCrashRecoveryProbe owner "$core" "$rom" "$out"
owner_rc=$?
set -e
[[ "$owner_rc" == 87 ]] || { echo "Crash owner did not terminate at the expected hard-stop boundary: $owner_rc" >&2; exit 1; }

PLUMP_E2_REAL_BASELINE_IMAGE="$baseline" PLUMP_E2_PYTHON="$python_bin" \
    java -cp "$classes" dev.plump.emulator.VirtualSdCrashRecoveryProbe recover "$core" "$rom" "$out"

echo "P4_E2_REAL_CORE_PROCESS_CRASH_RECOVERY_PASS"
echo "core_sha256=$core_sha"
echo "evidence=$out"
