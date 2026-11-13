#!/usr/bin/env bash
# A synthetic retail-classified test cartridge for DS SRAM integration only.
# Emulates real EEPROM hardware writes; it is NOT a retail game compatibility
# or full save-integrity certificate. No Nintendo game, firmware or saved data.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sources="$root/tests/roms/p3-native-save-diagnostic"
output="$root/app/build/p3-native-save-diagnostic"
image='skylyrac/blocksds@sha256:c5f19b95d9c2f312e6a68ea244d2ed5120203b4b82af9785937a349096f8e5e4'

mkdir -p "$output/source" "$root/app/build/p3-tmp"
export TMPDIR="$root/app/build/p3-tmp"
cp "$sources/Makefile" "$output/Makefile"
cp "$sources/source/main.c" "$output/source/main.c"
docker image inspect "$image" >/dev/null

docker run --rm --network none --read-only --tmpfs /tmp \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$output,dst=/work" \
    --workdir /work --entrypoint /bin/sh "$image" -c 'make -j2'

rom="$output/p3_save_diagnostic.nds"
test -s "$rom"

# BlocksDS identifies homebrew by the gamecode '####'. The pinned melonDS
# core deliberately gives homebrew an SD card instead of cartridge SRAM.
# This original CC0 diagnostic must exercise the DIFFERENT DS retail SPI path.
# Change only our own test game's classification to a made-up gamecode,
# and make its zero-filled, unused secure-area marker appear decrypted for
# FreeBIOS direct boot. No commercial game/firmware/source is copied.
python3 - "$rom" <<'PY'
import hashlib
from pathlib import Path
import struct
import sys

rom = Path(sys.argv[1])
contents = bytearray(rom.read_bytes())
assert contents[12:16] == b'####', 'Unexpected homebrew identifier'
assert contents[0x15c:0x15e] == b'\x56\xcf', 'Unexpected valid DS test header CRC marker'
arm9_offset = struct.unpack_from('<I', contents, 0x20)[0]
arm9_entry = struct.unpack_from('<I', contents, 0x24)[0]
assert arm9_offset == 0x4000 and arm9_entry == 0x02000800
assert contents[arm9_offset:arm9_offset + 4] == bytes(4)
assert contents[arm9_offset + 0x10:arm9_offset + 0x14] == bytes(4)
contents[12:16] = b'P3SV'  # Invented test ID; not an actual retail title ID.
struct.pack_into('<I', contents, arm9_offset, 0xE7FFDEFF)
rom.write_bytes(contents)
print('Synthetic EEPROM test:', len(contents), 'bytes, SHA256',
      hashlib.sha256(contents).hexdigest())
PY
