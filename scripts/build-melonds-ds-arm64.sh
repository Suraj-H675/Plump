#!/usr/bin/env bash
# Reproducible P3 software-renderer experiment. No BIOS, ROM, APK or device actions.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
upstream="${PLUMP_MELONDS_DS_SOURCE:-$HOME/.cache/plump-p1-dual.npaLOc/melonds-ds}"
revision=f394adbacb5722ee97c1b37c8064da9a25818310
work="$root/app/build/p3-melonds-source"
build="$root/app/build/p3-melonds-android"
deps="$root/app/build/p3-deps"
target="$root/app/src/main/jniLibs/arm64-v8a/libmelondsds_libretro.so"

mkdir -p "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp"
test "$(git -C "$upstream" rev-parse HEAD)" = "$revision" || {
  echo "Wrong melonDS DS revision. Expected $revision" >&2; exit 1;
}
if [[ ! -f "$work/cmake/FetchDependencies.cmake" ]]; then
  mkdir -p "$work"
  git -C "$upstream" archive "$revision" | tar -xf - -C "$work"
fi

# Pin the actual fetched revisions, including melonDS, which upstream's CMake
# currently references by a mutable branch name. A build from a moving branch
# would not be a reproducible P3 test even when the Libretro wrapper is pinned.
dependencies=(melonds libretro-common embed-binaries glm libslirp pntr fmt yamc span-lite date zlib)
revisions=(
  16f127dbb587a73371827ce79689c5d237c4b59b
  fa8a1b572a444fcb552007019f8caf68281108aa
  078b62beba97e8192c99bfb16d5e17220cfc7598
  6f14f4792a0cde5d0cf2c910506724d61cb95834
  e61dbd459c8c06607b3a84694489427e8ec60f17
  a47fe167703b0ac3bfad00785b20b0dce6712509
  1be298e1bd68957e4cd352e1f676f00e07dcfb57
  4e015a7e8eb0d61c34e6928676c8c78881a72d73
  f63fe2974943b5f555b928b10d910e457fbb93d4
  179a6b921ccd6179b5b04dce812a89f71a51b4df
  da607da739fa6047df13e66a2af6b8bec7c2a498
)
pins=()
for i in "${!dependencies[@]}"; do
  variable="$(printf '%s' "${dependencies[$i]}" | tr '[:lower:]-' '[:upper:]_')"
  pins+=("-D${variable}_REPOSITORY_TAG=${revisions[$i]}")
done

cmake -S "$work" -B "$build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release -DENABLE_OPENGL=OFF -DENABLE_JIT=OFF \
  -DENABLE_COMPUTE_RENDERER=OFF -DENABLE_THREADED_RENDERER=OFF \
  -DENABLE_NETWORKING=ON -DBUILD_TESTING=OFF \
  -DFETCHCONTENT_BASE_DIR="$deps" "${pins[@]}"

for i in "${!dependencies[@]}"; do
  actual="$(git -C "$deps/${dependencies[$i]}-src" rev-parse HEAD)"
  if [[ "$actual" != "${revisions[$i]}" ]]; then
    echo "Core dependency ${dependencies[$i]} changed: $actual" >&2
    exit 1
  fi
done

# Upstream's no-networking configuration does not compile at this pinned
# revision because unrelated headers reference networking-only types.
# Compile upstream's supported codepath, but the frontend MUST force the
# emulated network mode to 'disabled' and request no Internet permission.
cmake --build "$build" --parallel "${PLUMP_DS_JOBS:-4}"
library="$build/src/libretro/melondsds_libretro_android.so"
test -s "$library"
mkdir -p "$(dirname "$target")"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$target" "$library"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$target" | grep -q 'Machine:.*AArch64'
sha256sum "$target"
echo 'Built the pinned Android ARM64 melonDS DS Libretro software core (GPLv3).'
