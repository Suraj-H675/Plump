#!/usr/bin/env bash
# Build a DISTINCT disposable Android ARM64 E3F core with the missing VFS
# close-error patch. Never overwrite/rebuild the accepted E3A core or source.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
old="$repo/app/build/p4-e3a"
out="$repo/app/build/p4-e3f-close-core"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
old_sha=9e7a84190385b8aa526f17ffcbbbd2f709d8b1ec3dfc608aa34cb8d6bbeba5f9
original_library="$repo/app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so"
old_prod_sha="$(sha256sum "$original_library" | cut -d' ' -f1)"

test "$(sha256sum "$old/libmelondsds_libretro.so" | cut -d' ' -f1)" = "$old_sha"
test -f "$repo/patches/p4-e2/libretro-vfs-close-error.patch"
test -f "$old/frontend/.e3-source-ready"
test ! -e "$out/frontend" || test -f "$out/.native-close-sources-ready"
test -x "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

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
  'date 179a6b921ccd6179d5b04dce812a89f71a51b4df'
  'zlib da607da739fa6047df13e66a2af6b8bec7c2a498'
)

if [[ ! -f "$out/.native-close-sources-ready" ]]; then
  test ! -e "$out/frontend"
  test ! -e "$out/deps"
  mkdir -p "$out" "$out/deps"
  cp -a --reflink=auto "$old/frontend" "$out/frontend"
  for item in "${pins[@]}"; do
    read -r name revision <<< "$item"
    src="$old/deps/$name-src"
    test -d "$src"
    cp -a --reflink=auto "$src" "$out/deps/$name-src"
  done
  # The previously accepted E3A builder's generic 'if (output == 0)'
  # test matched even the UNPATCHED file. Check the two unique markers.
  ! grep -q 'PLUMP_E2_FAIL_FILE_CLOSE' "$old/deps/libretro-common-src/vfs/vfs_implementation.c"
  patch --batch --forward -d "$out/deps/libretro-common-src" -p1 \
    < "$repo/patches/p4-e2/libretro-vfs-close-error.patch"
  grep -q 'PLUMP_E2_FAIL_FILE_CLOSE' "$out/deps/libretro-common-src/vfs/vfs_implementation.c"
  grep -q 'if (output == 0)' "$out/deps/libretro-common-src/streams/file_stream.c"
  grep -q 'GetStatus() const' "$out/deps/melonds-src/src/FATStorage.h"
  grep -q 'const_cast<melonDS::NDS\*>' "$out/frontend/src/libretro/core/test.cpp"
  touch "$out/.native-close-sources-ready"
fi

test -f "$out/.native-close-sources-ready"
grep -q 'PLUMP_E2_FAIL_FILE_CLOSE' "$out/deps/libretro-common-src/vfs/vfs_implementation.c"
! grep -q 'PLUMP_E2_FAIL_FILE_CLOSE' "$old/deps/libretro-common-src/vfs/vfs_implementation.c"

args=()
for item in "${pins[@]}"; do
  read -r name revision <<< "$item"
  variable="$(printf '%s' "$name" | tr '[:lower:]-' '[:upper:]_')"
  args+=("-D${variable}_REPOSITORY_TAG=$revision")
done
mkdir -p "$out/tmp"
export TMPDIR="$out/tmp"
cmake -S "$out/frontend" -B "$out/android-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DENABLE_OPENGL=OFF -DENABLE_JIT=OFF \
  -DENABLE_COMPUTE_RENDERER=OFF -DENABLE_THREADED_RENDERER=OFF \
  -DENABLE_NETWORKING=ON -DBUILD_TESTING=OFF \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
  -DFETCHCONTENT_BASE_DIR="$out/deps" "${args[@]}"
cmake --build "$out/android-build" --parallel "${PLUMP_E3F_JOBS:-2}"
built="$out/android-build/src/libretro/melondsds_libretro_android.so"
test -s "$built"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  --strip-unneeded -o "$out/libmelondsds_libretro.so" "$built"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$out/libmelondsds_libretro.so" \
  | grep 'Machine:.*AArch64' >/dev/null
for symbol in melondsds_dldi_status_v1 melondsds_dldi_close_v1; do
  "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D \
    "$out/libmelondsds_libretro.so" | grep " $symbol$" >/dev/null
done
strings "$out/libmelondsds_libretro.so" | grep -x 'PLUMP_E2_FAIL_FILE_CLOSE' >/dev/null
sha256sum "$out/libmelondsds_libretro.so" | tee "$out/core.sha256"
test "$(sha256sum "$old/libmelondsds_libretro.so" | cut -d' ' -f1)" = "$old_sha"
test "$(sha256sum "$original_library" | cut -d' ' -f1)" = "$old_prod_sha"
echo 'PASS_E3F_ANDROID_NATIVE_CLOSE_PATCH_PRESENT_AND_EXISTING_CORES_UNCHANGED'
