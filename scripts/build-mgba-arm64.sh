#!/usr/bin/env bash
set -euo pipefail

# No core source or generated emulator binary is tracked by Plump.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ndk="$sdk/ndk/28.2.13676358"
sha=d58ea8a86f30598fbf3958585de3d33028535072
source_dir="${PLUMP_MGBA_SOURCE:-$HOME/.cache/plump/upstream/mgba}"
output="$root/app/build/upstream/mgba-arm64"

test -f "$ndk/build/cmake/android.toolchain.cmake" || {
  echo "Required pinned Android NDK missing: $ndk" >&2
  exit 1
}

if ! test -d "$source_dir/.git"; then
  mkdir -p "$(dirname "$source_dir")"
  git clone --filter=blob:none --no-checkout https://github.com/mgba-emu/mgba.git "$source_dir"
fi
test -z "$(git -C "$source_dir" status --porcelain)" || {
  echo "Refusing to change or build a dirty mGBA source checkout" >&2
  exit 1
}
if ! git -C "$source_dir" cat-file -e "$sha^{commit}" 2>/dev/null; then
  git -C "$source_dir" fetch --depth=1 origin "$sha"
fi
git -C "$source_dir" sparse-checkout init --cone
git -C "$source_dir" sparse-checkout set src include res
git -C "$source_dir" checkout --detach "$sha"
test "$(git -C "$source_dir" rev-parse HEAD)" = "$sha"
test -z "$(git -C "$source_dir" status --porcelain)" || {
  echo "Refusing to build modified mGBA source" >&2
  exit 1
}

# Keep scratch compilation output off /tmp, which may have a user quota.
mkdir -p "$output/tmp"
export TMPDIR="$output/tmp"
cmake -S "$source_dir" -B "$output" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON \
  -DDISABLE_DEPS=ON -DBUILD_SDL=OFF -DBUILD_QT=OFF \
  -DBUILD_TEST=OFF -DBUILD_SUITE=OFF -DBUILD_CINEMA=OFF \
  -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF \
  -DBUILD_PERF=OFF -DBUILD_PYTHON=OFF -DBUILD_EXAMPLE=OFF
cmake --build "$output" --target mgba_libretro --parallel 4

destination="$root/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$destination"
install -m 0644 "$output/mgba_libretro.so" "$destination/libmgba_libretro.so"
echo "Built mGBA $sha for Android arm64-v8a (API 26)"
sha256sum "$destination/libmgba_libretro.so"
