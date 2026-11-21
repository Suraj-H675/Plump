#!/usr/bin/env bash
# Prove the actual Android-compatible byte-level checker on genuine disposable
# pinned melonDS FAT16 output, with independent Linux fsck and negative cases.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
image="${PLUMP_E3_FAT_IMAGE:-app/build/p4-e1-b/runs/acceptance-mJnYMKhR/canonical/save/melonDS DS/dldi_sd_card.bin}"
test -f "$image" && test ! -L "$image"
test "$(stat -c '%s' "$image")" = 134217728
case "$(realpath "$image")" in "$repo"/app/build/*) ;; *) echo 'Refuse non-test image' >&2; exit 1;; esac
source_sha="$(sha256sum "$image" | cut -d' ' -f1)"
classes=app/build/p4-e3a/host-fat-classes
mkdir -p "$classes"
javac --release 17 -d "$classes" -sourcepath app/src/main/java \
    app/src/main/java/dev/plump/emulator/VirtualSdFat16Validator.java \
    app/src/e3test/java/dev/plump/emulator/E3DiagnosticFatRecord.java \
    tests/host/E3FatValidatorCli.java
java -cp "$classes" dev.plump.emulator.E3FatValidatorCli "$image"
java -cp "$classes" dev.plump.emulator.E3FatValidatorCli "$image" --record
fsck.fat -nv "$image" > app/build/p4-e3a/android-fat-positive-fsck.log
test "$(sha256sum "$image" | cut -d' ' -f1)" = "$source_sha"

workspace="$(mktemp -d "$repo/app/build/p4-e3a/fat-negative-XXXXXXXX")"
copy="$workspace/damaged-second-fat.bin"
cp --sparse=always "$image" "$copy"
python3 - "$copy" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); assert p.is_file() and p.stat().st_size==128*1024*1024
with p.open('r+b') as f:
    f.seek((4+256)*512+4)
    prev=f.read(1)
    f.seek((4+256)*512+4)
    f.write(bytes((prev[0]^1,)))
PY
if java -cp "$classes" dev.plump.emulator.E3FatValidatorCli "$copy" \
        > "$workspace/negative.log" 2>&1; then
    echo 'FAIL E3 Java FAT checker accepted a mismatched FAT replica' >&2
    exit 1
fi
grep -q 'allocation tables disagree' "$workspace/negative.log"
test "$(sha256sum "$image" | cut -d' ' -f1)" = "$source_sha"
echo 'PASS_E3_ANDROID_FAT16_NEGATIVE: mismatched redundant FAT rejected'
echo 'PASS_E3_ANDROID_FAT16_REAL_CORE_HOST: valid 128 MiB DLDI writer FAT accepted, corrupt copy rejected'
