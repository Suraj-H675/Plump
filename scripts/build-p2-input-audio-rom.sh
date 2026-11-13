#!/usr/bin/env bash
# Build an original ARM7TDMI test ROM; generated cartridge is ignored by Git.
# The first 192 bytes use jsmolka/gba-tests MIT-licensed cartridge header
# (upstream a7113b67e63f83a9b321696ddd7042ccfad6c881).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ndk="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
header="${PLUMP_MIT_HEADER_ROM:-$HOME/.cache/plump-p2-rom-research/gba-tests/ppu/hello.gba}"
output="$root/app/build/p2-test-fixtures"
mkdir -p "$output/tmp"
export TMPDIR="$output/tmp"

"$tools/clang" --target=arm-none-eabi -mcpu=arm7tdmi -marm -nostdlib \
    -fuse-ld=lld -Wl,--build-id=none -Wl,-Ttext=0x08000000 -Wl,-e,_start \
    "$root/tests/roms/p2-input-audio.s" -o "$output/p2-input-audio.elf"
"$tools/llvm-objcopy" -O binary "$output/p2-input-audio.elf" "$output/code.bin"

python3 - "$header" "$output/code.bin" "$output/p2-input-audio.gba" <<'PY'
import hashlib
import pathlib
import struct
import sys

header, source, destination = map(pathlib.Path, sys.argv[1:])
expected_header_hash = '38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264'
original = header.read_bytes()
assert hashlib.sha256(original).hexdigest() == expected_header_hash, 'untrusted GBA header fixture'
program = source.read_bytes()
assert len(program) > 192, 'no ARM cartridge code produced'
out = bytearray(program)
out[:192] = original[:192]
assert struct.unpack_from('<I', out)[0] == 0xea00002e, 'header entrypoint not 0xC0'
out[0xa0:0xac] = b'PLUMP P2 I/O'
out[0xac:0xb0] = b'P2IO'
out[0xb0:0xb2] = b'PL'
out[0xbd] = (-sum(out[0xa0:0xbd]) - 0x19) & 0xff
assert len(out) < 32768, 'test code unexpectedly large'
destination.write_bytes(out)
print('Built original Plump P2 control+sound cartridge:', destination,
      'size', len(out), 'sha256', hashlib.sha256(out).hexdigest())
PY
