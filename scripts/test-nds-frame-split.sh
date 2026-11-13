#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp"
"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror \
  "$root/tests/native/nds_pixels_test.cpp" -o "$root/app/build/p3-tmp/nds_pixels_test"
"$root/app/build/p3-tmp/nds_pixels_test"
echo 'DS XRGB8888 dual-screen split and pixel-address conversion: PASS'
