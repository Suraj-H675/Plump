#!/usr/bin/env bash
# Reproduce the E2-instrumented melonDS Android ARM64 core in ignored app/build.
# Deliberately does NOT modify the packaged production core or touch Android devices.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
out="$repo/app/build/p4-e3a"
source_dir="$out/frontend"
deps="$out/deps"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
baseline="$repo/app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so"
test_core="$out/libmelondsds_libretro.so"
pins=(
  'melonds 16f127dbb587a73371827ce79689c5d237c4b59b'
  'libretro-common fa8a1b572a444fcb552007019f8caf68281108aa'
  'embed-binaries 078b62beba97e8192c99bfb16d5e17220cfc7598'
  'glm 6f14f4792a0cde5d0cf2c910506724d61cb95834'
  'libslirp e61dbd459c8c06607b3a84694489427e8ec60f17'
  'pntr a47fe167703b0ac3bfad00785b20b0dce6712509'
  'fmt 1be298e1bd68957e4cd352e1f676f00e07dcfb57'
  'yamc 4e015a7e8eb0d61c34e6928676c8c78881a72d73'
  'span-lite f63fe2974943b5f555b928b10d910e457fbb93d4'
  'date 179a6b921ccd6179b5b04dce812a89f71a51b4df'
  'zlib da607da739fa6047df13e66a2af6b8bec7c2a498'
)

test -s "$baseline"
test -x "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
test -x "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test "$(git -C "$HOME/.cache/plump-p1-dual.npaLOc/melonds-ds" rev-parse HEAD)" = f394adbacb5722ee97c1b37c8064da9a25818310
test -f "$repo/patches/p4-e2/melonds-dldi-health.patch"
test -f "$repo/patches/p4-e2/libretro-vfs-close-error.patch"
test -f "$repo/patches/p4-e2/libretro-melonds-status-api.patch"
mkdir -p "$out" "$deps" "$out/tmp"
export TMPDIR="$out/tmp"

# All source inputs are exported from exact locally pinned Git commits into
# independent ignored directories. The accepted cached source trees remain untouched.
if [[ ! -f "$source_dir/.e3-source-ready" ]]; then
  test ! -e "$source_dir" || { echo "Refusing an unknown partial E3 source directory" >&2; exit 1; }
  mkdir "$source_dir"
  git -C "$HOME/.cache/plump-p1-dual.npaLOc/melonds-ds" archive f394adbacb5722ee97c1b37c8064da9a25818310 | tar -xf - -C "$source_dir"
  for pin in "${pins[@]}"; do
    read -r name revision <<<"$pin"
    cached="$repo/app/build/p3-deps/$name-src"
    test "$(git -C "$cached" rev-parse HEAD)" = "$revision"
    dest="$deps/$name-src"
    test ! -e "$dest" || { echo "Refusing partial E3 dependency: $dest" >&2; exit 1; }
    mkdir "$dest"
    git -C "$cached" archive "$revision" | tar -xf - -C "$dest"
  done
  # Patch core sources only after CMake populates FetchContent, below.
  # CMake may replace a pre-populated -src directory during its first configure.
  patch --batch --forward -d "$source_dir" -p3 < "$repo/patches/p4-e2/libretro-melonds-status-api.patch"
  patch --batch --forward -d "$source_dir" -p1 < "$repo/patches/p4-e3a/arm64-e2-close-constness.patch"
  printf 'pinned-f394adbacb5722ee97c1b37c8064da9a25818310-e2-patches-v1\n' > "$source_dir/.e3-source-ready"
fi
if ! grep -q 'const_cast<melonDS::NDS\*>' "$source_dir/src/libretro/core/test.cpp"; then
  patch --batch --forward -d "$source_dir" -p1 < "$repo/patches/p4-e3a/arm64-e2-close-constness.patch"
fi

for symbol in melondsds_dldi_status_v1 melondsds_dldi_close_v1; do
  grep -q "$symbol" "$source_dir/src/libretro/core/test.cpp" || { echo "E3 source missing $symbol" >&2; exit 1; }
done
for pin in "${pins[@]}"; do
  read -r name revision <<<"$pin"
  cached="$repo/app/build/p3-deps/$name-src"
  test "$(git -C "$cached" rev-parse HEAD)" = "$revision"
done

args=()
for pin in "${pins[@]}"; do
  read -r name revision <<<"$pin"
  variable="$(printf '%s' "$name" | tr '[:lower:]-' '[:upper:]_')"
  args+=("-D${variable}_REPOSITORY_TAG=$revision")
done

cmake -S "$source_dir" -B "$out/android-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DENABLE_OPENGL=OFF -DENABLE_JIT=OFF \
  -DENABLE_COMPUTE_RENDERER=OFF -DENABLE_THREADED_RENDERER=OFF \
  -DENABLE_NETWORKING=ON -DBUILD_TESTING=OFF \
  -DFETCHCONTENT_BASE_DIR="$deps" "${args[@]}"
# Guard against FetchContent replacing sources during initial configure.
# Apply exactly the accepted E2 patches into E3's isolated fetched copies.
if ! grep -q 'GetStatus() const' "$deps/melonds-src/src/FATStorage.h"; then
  patch --batch --forward -d "$deps/melonds-src" -p1 < "$repo/patches/p4-e2/melonds-dldi-health.patch"
fi
# The old generic 'if (output == 0)' probe also matches the UNPATCHED file.
# The originally frozen E3A ELF consequently lacks VFS close-error propagation.
# Never silently rebuild/replace that accepted core with different bytes.
# E3F uses a completely separate source, build directory and package identity.
if ! grep -q 'PLUMP_E2_FAIL_FILE_CLOSE' "$deps/libretro-common-src/vfs/vfs_implementation.c"; then
  echo 'E3A source lacks the VFS close-error patch; keep its frozen binary unchanged and use scripts/build-p4-e3f-native-close-arm64.sh for corrected test builds.' >&2
  exit 1
fi
grep -q 'GetStatus() const' "$deps/melonds-src/src/FATStorage.h"
grep -q 'if (output == 0)' "$deps/libretro-common-src/streams/file_stream.c"
grep -q 'const_cast<melonDS::NDS\*>' "$source_dir/src/libretro/core/test.cpp"
cmake --build "$out/android-build" --parallel "${PLUMP_E3_JOBS:-2}"
built="$out/android-build/src/libretro/melondsds_libretro_android.so"
test -s "$built"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$test_core" "$built"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$test_core" | grep -q 'Machine:.*AArch64'
for symbol in melondsds_dldi_status_v1 melondsds_dldi_close_v1; do
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D "$test_core" | grep " $symbol$" > /dev/null || {
    echo "E3 ARM64 core is missing E2 health export $symbol" >&2
    exit 1
  }
done
test "$(sha256sum "$baseline" | awk '{print $1}')" = "$(sha256sum "$repo/app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so" | awk '{print $1}')"
sha256sum "$test_core" | tee "$out/android-core.sha256"
baseline="$out/baseline/dldi_sd_card.bin"
if [[ ! -e "$baseline" ]]; then
  mkdir -p "$(dirname "$baseline")"
  truncate -s $((128 * 1024 * 1024)) "$baseline"
  mkfs.fat -F 16 -i 504c554d -n PLUME3A "$baseline" > "$out/baseline-format.log" 2>&1
  fsck.fat -nv "$baseline" > "$out/baseline-fsck.log"
  chmod 0444 "$baseline"
fi
test -f "$baseline" && test ! -L "$baseline"
test "$(stat -c %s "$baseline")" -eq $((128 * 1024 * 1024))
sha256sum "$baseline" | tee "$out/baseline.sha256"
echo "E3 ARM64 E2-health core built in ignored path; production library unchanged."
