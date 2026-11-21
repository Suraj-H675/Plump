#!/usr/bin/env bash
# Rebuild only an original disposable ARM7 SRAM test cartridge. The cartridge
# header comes from the separately pinned, MIT-licensed gba-tests hello ROM.
# Exercise genuine Linux mGBA and melonDS cores plus the actual PLSV v1 store.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$root/app/build"
export PLUMP_P4_D_WORK="$(mktemp -d "$root/app/build/p4-d-d1-XXXXXXXX")"
export PLUMP_P4_D_JAVA_CLASSES="$PLUMP_P4_D_WORK/java-classes"
export PLUMP_P4_D_GBA_FIXTURE="$PLUMP_P4_D_WORK/gba-button-sram.gba"
header="${PLUMP_GBA_HEADER_FIXTURE:-$HOME/.cache/plump-p2-rom-research/gba-tests/ppu/hello.gba}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
tools="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -f "$header" || { echo 'Pinned MIT GBA header test fixture unavailable' >&2; exit 1; }
test -x "$tools/clang" && test -x "$tools/llvm-objcopy" || {
    echo 'Pinned NDK 28.2.13676358 ARM assembler unavailable' >&2; exit 1;
}
"$tools/clang" --target=arm-none-eabi -mcpu=arm7tdmi -marm -nostdlib \
    -fuse-ld=lld -Wl,--build-id=none -Wl,-Ttext=0x08000000 -Wl,-e,_start \
    "$root/tests/roms/p4-d-gba-sram-counter.s" -o "$PLUMP_P4_D_WORK/counter.elf"
"$tools/llvm-objcopy" -O binary "$PLUMP_P4_D_WORK/counter.elf" "$PLUMP_P4_D_WORK/counter.bin"
python3 - "$header" "$PLUMP_P4_D_WORK/counter.bin" "$PLUMP_P4_D_GBA_FIXTURE" <<'PY'
import hashlib
import pathlib
import struct
import sys

header = pathlib.Path(sys.argv[1]).read_bytes()
if hashlib.sha256(header).hexdigest() != '38aed48b67bc0f701e8aa222b0c3334bd306bd29888707bb7224d81f5576c264':
    raise SystemExit('MIT gba-tests header provenance checksum mismatch')
if struct.unpack_from('<I', header)[0] != 0xea00002e:
    raise SystemExit('GBA cartridge header branch opcode mismatch')
binary = bytearray(pathlib.Path(sys.argv[2]).read_bytes())
binary[:192] = header[:192]
binary[0xa0:0xac] = b'PLUMP SRAM4 '
binary[0xac:0xb0] = b'P4D1'
binary[0xb0:0xb2] = b'PL'
binary[0xbd] = (-sum(binary[0xa0:0xbd]) - 0x19) & 255
out = pathlib.Path(sys.argv[3])
out.write_bytes(binary)
print('PASS disposable original GBA SRAM-counter ROM:', len(binary), 'bytes, SHA-256',
      hashlib.sha256(binary).hexdigest(), 'MIT header provenance verified')
PY
mkdir -p "$PLUMP_P4_D_JAVA_CLASSES"
javac --release 17 -d "$PLUMP_P4_D_JAVA_CLASSES" \
    "$root/app/src/main/java/dev/plump/emulator/CheckpointStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/GameSaveStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/ActiveTimelineStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/NativeSavePromotionStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/SessionReceiptStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/StorageCapacity.java" \
    "$root/app/src/main/java/dev/plump/emulator/VirtualSdGenerationStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java" \
    "$root/tests/host/JournalCli.java" \
    "$root/tests/host/ActiveTimelineHostCli.java"
export PYTHONDONTWRITEBYTECODE=1
python3 -u "$root/tests/roms/verify-p4-d-gba-isolation.py"
python3 -u "$root/tests/roms/verify-p4-d-ds-isolation.py"
echo "PASS P4-D D1 real-core, real PLSV v1 canonical/branch cold isolation ($PLUMP_P4_D_WORK)"
