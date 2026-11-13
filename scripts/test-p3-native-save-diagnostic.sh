#!/usr/bin/env bash
# Isolated, genuine DS EEPROM host test in two independent Linux processes.
# Does NOT access any installed APK, personal ROM, phone or user save.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp" PYTHONDONTWRITEBYTECODE=1
"$root/scripts/build-p3-native-save-diagnostic-rom.sh"

core="$root/app/build/p3-melonds-host/src/libretro/melondsds_libretro.so"
rom="$root/app/build/p3-native-save-diagnostic/p3_save_diagnostic.nds"
test -s "$core"
session="$(mktemp -d "$root/app/build/p3-tmp/p3-save-host-XXXXXXXX")"

python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" first "$core" "$rom" "$session"
python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" cold "$core" "$rom" "$session"
echo 'PASS: Synthetic DS cartridge writes real emulated EEPROM and cold-reloads in two separate processes.'

# Safety proof in a third, independent ephemeral game: a state taken before
# an in-game cartridge write rewinds EEPROM when loaded afterward. Therefore
# the Android P3 build must NOT offer state loading until a guarded native-save
# backup/rollback protocol is designed and device-tested.
state_session="$(mktemp -d "$root/app/build/p3-tmp/p3-state-host-XXXXXXXX")"
python3 -u "$root/tests/roms/verify-p3-native-save-diagnostic.py" state-rewind "$core" "$rom" "$state_session"
echo "Test-only artifacts retained at $session (ignored by Git)."
