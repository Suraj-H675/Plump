#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$root/app/build/native-render-tests"
mkdir -p "$output/tmp"
export TMPDIR="$output/tmp"

"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror \
    "$root/tests/native/rgb565_blit_test.cpp" \
    -o "$output/rgb565_blit_test"
"$output/rgb565_blit_test"
echo 'RGB565 -> RGBA8888 conversion, source pitch and destination stride: PASS'
