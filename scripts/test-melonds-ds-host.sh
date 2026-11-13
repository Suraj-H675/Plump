#!/usr/bin/env bash
# Real licensed .nds execution through pinned melonDS DS, never a mock renderer.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source="$root/app/build/p3-melonds-source"
deps="$root/app/build/p3-deps"
build="$root/app/build/p3-melonds-host"
test_root="$root/app/build/p3-tmp/host-test"
fixture="$source/test/nds/tests_sys_mem_regions.nds"
library="$build/src/libretro/melondsds_libretro.so"
export TMPDIR="$root/app/build/p3-tmp"

test -f "$source/cmake/FetchDependencies.cmake" || {
  echo 'First run scripts/build-melonds-ds-arm64.sh to fetch the pinned core sources.' >&2
  exit 1
}
test "$(sha256sum "$fixture" | cut -d' ' -f1)" = \
  9d7f86224faec8da2b22b103cb2ff9a84c9120cc33b5cc43498d108924b5813f || {
  echo 'Upstream GPLv3 Nintendo DS homebrew test ROM hash changed.' >&2
  exit 1
}

if [[ ! -f "$library" ]]; then
  args=()
  for name in melonds libretro-common embed-binaries glm libslirp pntr fmt yamc span-lite date zlib; do
    variable="$(printf '%s' "$name" | tr '[:lower:]-' '[:upper:]_')"
    test -d "$deps/$name-src" || { echo "Missing pinned source $name" >&2; exit 1; }
    args+=("-DFETCHCONTENT_SOURCE_DIR_${variable}=$deps/$name-src")
  done
  cmake -S "$source" -B "$build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DENABLE_OPENGL=OFF -DENABLE_JIT=OFF \
    -DENABLE_COMPUTE_RENDERER=OFF -DENABLE_THREADED_RENDERER=OFF \
    -DENABLE_NETWORKING=ON -DBUILD_TESTING=OFF \
    -DFETCHCONTENT_BASE_DIR="$root/app/build/p3-host-deps" "${args[@]}"
  cmake --build "$build" --parallel "${PLUMP_DS_JOBS:-4}"
fi

mkdir -p "$test_root"
python3 -u "$root/tests/roms/verify-melonds-ds-host.py" \
  "$library" "$fixture" "$test_root"
