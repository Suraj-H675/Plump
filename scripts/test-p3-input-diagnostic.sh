#!/usr/bin/env bash
# Rebuild and test real, CC0 licensed Nintendo DS homebrew in pinned melonDS.
# Does not install any APK or alter the Android phone.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp" PYTHONDONTWRITEBYTECODE=1

"$root/scripts/build-p3-input-diagnostic-rom.sh"
core="$root/app/build/p3-melonds-host/src/libretro/melondsds_libretro.so"
test -f "$core" || {
    echo 'Host melonDS core missing; run scripts/test-melonds-ds-host.sh first.' >&2
    exit 1
}

python3 -u "$root/tests/roms/verify-p3-input-diagnostic.py" \
    "$core" "$root/app/build/p3-input-diagnostic/p3_input_diagnostic.nds" \
    "$root/app/build/p3-tmp/host-p3-input-diagnostic"
