#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

core="${PLUMP_E2_CORE:-app/build/p4-e2-work/melondsds_libretro_e2.so}"
core_sha="${PLUMP_E2_CORE_SHA256:-3737f32e0c438a2dd5836abe1786c58ff02e93525239027c5d78b4c6b9df38c9}"
rom="${PLUMP_E2_ROM:-}"
baseline="${PLUMP_E2_REAL_BASELINE_IMAGE:-}"
python_bin="${PLUMP_E2_PYTHON:-app/build/p4-e1-b/fat-inspector-venv/bin/python}"
out="${PLUMP_E2_OUTPUT:-app/build/p4-e2-work/real-core-store}"

[[ -f "$core" ]] || { echo "missing E2 core: $core" >&2; exit 1; }
[[ -f "$rom" ]] || { echo "set PLUMP_E2_ROM to the verified E1-B diagnostic ROM" >&2; exit 1; }
[[ -f "$baseline" ]] || { echo "set PLUMP_E2_REAL_BASELINE_IMAGE to a verified 128 MiB FAT16 baseline" >&2; exit 1; }
[[ -x "$python_bin" ]] || { echo "missing E2 FAT-verifier Python: $python_bin" >&2; exit 1; }
[[ "$(sha256sum "$core" | awk '{print $1}')" == "$core_sha" ]] || {
    echo "E2 core SHA-256 mismatch" >&2
    exit 1
}

rom_sha="$(sha256sum "$rom" | awk '{print $1}')"
[[ "$rom_sha" == "1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588" ]] || {
    echo "E2 ROM SHA-256 mismatch" >&2
    exit 1
}
[[ "$(stat -c '%s' "$baseline")" == "$((128 * 1024 * 1024))" ]] || {
    echo "E2 baseline is not exactly 128 MiB" >&2
    exit 1
}

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}" \
    ./gradlew :app:testDebugUnitTest --no-daemon >/dev/null

rm -rf "$out"
mkdir -p "$out"
classes="app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:app/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"
PLUMP_E2_REAL_BASELINE_IMAGE="$baseline" \
PLUMP_E2_PYTHON="$python_bin" \
    java -cp "$classes" dev.plump.emulator.VirtualSdRealCoreProbe "$core" "$rom" "$out"

echo "P4_E2_REAL_CORE_GENERATION_PASS"
echo "core_sha256=$core_sha"
echo "rom_sha256=$rom_sha"
echo "evidence=$out"
