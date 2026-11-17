#!/usr/bin/env bash
# P4-E1-B only: real ARM9 libfat/DLDI game writes on disposable pinned melonDS.
# The run root and generated ROMs/images are Git-ignored. No Android access,
# production SD enablement, virtual-SD mutation of the owner's files, or core rebuild.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"

core=app/build/p3-melonds-host/src/libretro/melondsds_libretro.so
core_sha=e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616
alpha_rom_sha=1dbbf7e6dea0068a4495b5d3fff5d20f61b099ba48bf9fc0dc0512b136c40588
beta_rom_sha=e01a1934e49732c6266bef6b51e64ae74ae5d0d5a426d802cf311f29854823e5
expected_blocksds='blocksds-toolchain 1.25.0-1'
expected_gcc='toolchain-gcc-arm-none-eabi-gcc 1:16.2.0.r229124.be93d9d35bf-1'
source=tests/roms/p4-e1b-dldi-writer
inspector=app/build/p4-e1-b/fat-inspector-venv/bin/python
stage_root="$repo/app/build/p4-e1-b/runs"
image_name='save/melonDS DS/dldi_sd_card.bin'
size=134217728
max_blocks=65536 # 32 MiB actual allocation, despite 128 MiB logical size.

command -v mkfs.fat >/dev/null
command -v fsck.fat >/dev/null
command -v timeout >/dev/null
test -x /opt/wonderful/bin/wf-config
test -x /opt/wonderful/bin/wf-pacman
test -x "$inspector" || {
    echo "Missing isolated PyFatFS verifier environment. Consult the E1-B report." >&2
    exit 1
}
test "$(sha256sum "$core" | cut -d' ' -f1)" = "$core_sha"
test "$(/opt/wonderful/bin/wf-pacman -Q blocksds-toolchain)" = "$expected_blocksds"
test "$(/opt/wonderful/bin/wf-pacman -Q toolchain-gcc-arm-none-eabi-gcc)" = "$expected_gcc"
eval "$(/opt/wonderful/bin/wf-config env generate -a)"
test "$BLOCKSDS" = /opt/wonderful/thirdparty/blocksds/core
test "$WONDERFUL_TOOLCHAIN" = /opt/wonderful
test -x "$BLOCKSDS/tools/dldipatch/dldipatch"
test -x "$BLOCKSDS/tools/ndstool/ndstool"
test "$(arm-none-eabi-gcc -dumpfullversion)" = 16.2.0
test -f "$source/source/main.c" && test -f "$source/Makefile"
test ! -L "$stage_root"

mkdir -p "$stage_root"
work="$(mktemp -d "$stage_root/acceptance-XXXXXXXX")"
test "$work" != "$stage_root"
test ! -L "$work"
printf 'PLUMP_E1B_OWNED_RUN_ROOT\n' > "$work/.e1b-owned"
export PLUMP_E1B_ROM_ROOT="$work/build"
export PYTHONDONTWRITEBYTECODE=1
echo "P4_E1B_EVIDENCE_ROOT=$work"

snapshot() {
    sha256sum "$1" | cut -d' ' -f1
}

require_image_bounded() {
    local path="$1" blocks
    test -f "$path" && test ! -L "$path"
    test "$(stat -c %s "$path")" -eq "$size"
    blocks="$(stat -c %b "$path")"
    if (( blocks > max_blocks )); then
        echo "Unsafe virtual image allocation: $path, $((blocks * 512)) bytes" >&2
        exit 1
    fi
}

build_fixture() {
    local kind="$1" rom_id="$2" sha="$3"
    local dir="$work/build/rom-$kind" name="plump_e1b_$kind"
    mkdir -p "$dir/source"
    cp -- "$source/Makefile" "$dir/Makefile"
    cp -- "$source/source/main.c" "$dir/source/main.c"
    make -C "$dir" -j2 "NAME=$name" "ROM_ID=$rom_id" > "$work/build-$kind.log" 2>&1
    make -C "$dir" dldipatch "NAME=$name" "ROM_ID=$rom_id" >> "$work/build-$kind.log" 2>&1
    local rom="$dir/$name.nds"
    test "$(snapshot "$rom")" = "$sha" || {
        echo "Non-reproducible or unexpected $kind ROM. See $work/build-$kind.log" >&2
        exit 1
    }
    # Do not assume melonDS can patch the BlocksDS unexpanded default DLDI stub.
    python3 - "$rom" <<'PY'
from pathlib import Path
import sys
raw = Path(sys.argv[1]).read_bytes()
offset = raw.find(bytes.fromhex("eda58dbf2043686973686d00"))
assert offset >= 0
assert raw[offset + 0x0f] >= 10
assert raw[offset + 0x60:offset + 0x64] == b"R4TF"
PY
    echo "P4_E1B_BUILT_$kind sha256=$sha bytes=$(stat -c %s "$rom")"
}

baseline="$work/immutable/dldi_sd_card.bin"
mkdir -p "$(dirname "$baseline")"
truncate -s "$size" "$baseline"
mkfs.fat -F 16 -n PLUME1B "$baseline" > "$work/mkfs.log" 2>&1
require_image_bounded "$baseline"
fsck.fat -nv "$baseline" > "$work/immutable-fsck.log"
chmod 0444 "$baseline"
baseline_sha="$(snapshot "$baseline")"
# mkfs.fat may choose a nondeterministic volume ID; validate each immutable
# source against its *own* recorded SHA, never another run's baseline digest.
printf '%s  %s\n' "$baseline_sha" "$baseline" > "$work/immutable.sha256"
echo "P4_E1B_BASELINE sha256=$baseline_sha apparent=$size allocated=$(($(stat -c %b "$baseline") * 512))"

prepare() {
    local stage="$1" from="${2:-$baseline}"
    local dest="$work/$stage/$image_name"
    test -f "$from" && test ! -L "$from"
    require_image_bounded "$from"
    mkdir -p "$(dirname "$dest")" "$work/$stage/system"
    test ! -e "$dest"
    cp --sparse=always "$from" "$dest"
    chmod 0644 "$dest"
    require_image_bounded "$dest"
    test "$(snapshot "$dest")" = "$(snapshot "$from")"
    test ! -e "$dest.idx"
    echo "P4_E1B_PREPARE stage=$stage image_sha256=$(snapshot "$dest")"
}

check_fat() {
    local stage="$1" generation="$2" identity="$3"; shift 3
    local dest="$work/$stage"
    local image="$dest/$image_name"
    PYTHONWARNINGS=ignore::UserWarning "$inspector" \
        tests/roms/inspect-p4-e1b-fat.py "$image" "$identity" "$@" \
        > "$dest/fat-$generation.json"
    python3 - "$dest/fat-$generation.json" "$stage" "$generation" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
print("P4_E1B_FAT", "stage="+sys.argv[2], "generation="+sys.argv[3],
      "identity="+d["identity"], "valid="+str(d["record_valid"]),
      "counter="+str(d["record"]["counter"] if d["record"] else None),
      "report="+str(d["report"]["PLUMP_E1B_RESULT"] if d["report"] else None),
      "checksum="+str(d["record"]["checksum"] if d["record"] else None),
      "state_sha256="+str(d["state_sha256"]),
      "report_sha256="+str(d["report_sha256"]))
PY
}

boot() {
    local stage="$1" game="$2" generation="$3"
    local dest="$work/$stage"
    local img="$dest/$image_name"
    require_image_bounded "$img"
    timeout 75 python3 -u tests/roms/verify-p4-e1-virtual-sd.py \
        "$dest" "$game" 180 > "$dest/boot-$generation.log" 2>&1 || {
            echo "FAILED pinned-core boot stage=$stage generation=$generation" >&2
            tail -n 90 "$dest/boot-$generation.log" >&2
            exit 1
        }
    test -f "$dest/result.json"
    mv "$dest/result.json" "$dest/result-$generation.json"
    if test -f "$dest/last-frame.xrgb8888"; then
        python3 tests/roms/render-p4-e1b-frame.py \
            "$dest/last-frame.xrgb8888" "$dest/last-frame.json" \
            "$dest/last-frame-$generation.png" > "$dest/frame-$generation.log"
        mv "$dest/last-frame.xrgb8888" "$dest/last-frame-$generation.xrgb8888"
        mv "$dest/last-frame.json" "$dest/last-frame-$generation.json"
    fi
    # The game and virtual FAT must have been cleanly stopped and closed
    # before any host-side read-only inspection or future cold process.
    grep -F 'retro_unload_game()' "$dest/boot-$generation.log" > /dev/null
    if [[ "$stage" == os-readonly ]]; then
        # The core refused its initial R/W open, so no native FAT handle
        # exists to close. We must not invent successful storage closure.
        grep -F "Attempted to open \"$img\"" "$dest/boot-$generation.log" | \
            grep -F 'but failed' > /dev/null
    else
        grep -F "Closing \"$img\"" "$dest/boot-$generation.log" > /dev/null
        grep -F 'applied DLDI patch' "$dest/boot-$generation.log" > /dev/null
    fi
    fsck.fat -nv "$img" > "$dest/fsck-$generation.log"
    require_image_bounded "$img"
    test ! -e "$img.idx" || {
        echo "Unexpected .idx under disabled host-folder synchronization" >&2
        exit 1
    }
    python3 - "$dest/result-$generation.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert d["frames_rendered"] == 180, d
assert d["dldi_enabled_requested"], d
assert d["host_folder_sync_disabled_requested"], d
assert d["diagnostic_frame_captured"], d
assert not d["folder_sync_directory_present"], d
assert d["image_logical_bytes"] == 128*1024*1024, d
assert d["image_allocated_bytes"] <= 32*1024*1024, d
PY
    echo "P4_E1B_REAL_CORE stage=$stage generation=$generation game=$game image_sha256=$(snapshot "$img") allocated=$(($(stat -c %b "$img") * 512))"
}

build_fixture alpha 0xA1A10001 "$alpha_rom_sha"
build_fixture beta 0xB2B20002 "$beta_rom_sha"

# A fresh FAT image initially has no game-created record. Only the ARM9
# diagnostic is allowed to create these positive-test files.
prepare canonical
canonical="$work/canonical/$image_name"
check_fat canonical 0 ALPHA --expect-missing
boot canonical plump-alpha 1
check_fat canonical 1 ALPHA --expect-result PASS --expect-previous 0 --expect-counter 1 --expect-valid-record
boot canonical plump-alpha 2
check_fat canonical 2 ALPHA --expect-result PASS --expect-previous 1 --expect-counter 2 --expect-valid-record
boot canonical plump-alpha 3
check_fat canonical 3 ALPHA --expect-result PASS --expect-previous 2 --expect-counter 3 --expect-valid-record
canonical_sha="$(snapshot "$canonical")"
check_fat canonical no-beta BETA --expect-missing

# Two different verified ARM9 ROM identities must not write to the same FAT
# volume by accident. No persistent authority is shared between these roots.
prepare second-rom
other="$work/second-rom/$image_name"
check_fat second-rom 0 BETA --expect-missing
boot second-rom plump-beta 1
check_fat second-rom 1 BETA --expect-result PASS --expect-previous 0 --expect-counter 1 --expect-valid-record
boot second-rom plump-beta 2
check_fat second-rom 2 BETA --expect-result PASS --expect-previous 1 --expect-counter 2 --expect-valid-record
check_fat second-rom no-alpha ALPHA --expect-missing
test "$(snapshot "$canonical")" = "$canonical_sha"
check_fat canonical after-second-rom ALPHA --expect-result PASS --expect-previous 2 --expect-counter 3 --expect-valid-record
echo "P4_E1B_TWO_ROM_ISOLATION canonical_sha256=$canonical_sha second_rom_sha256=$(snapshot "$other")"

# A disposable historical *working copy* may advance independent of its
# originating canonical image. Do not restore a machine state into either.
prepare historical-branch "$canonical"
historical="$work/historical-branch/$image_name"
test "$(snapshot "$historical")" = "$canonical_sha"
boot historical-branch plump-alpha 4
check_fat historical-branch 4 ALPHA --expect-result PASS --expect-previous 3 --expect-counter 4 --expect-valid-record
test "$(snapshot "$canonical")" = "$canonical_sha"
check_fat canonical after-branch ALPHA --expect-result PASS --expect-previous 2 --expect-counter 3 --expect-valid-record
echo "P4_E1B_BRANCH_ISOLATION canonical_sha256=$canonical_sha branch_sha256=$(snapshot "$historical")"

# Malformed diagnostic record: corrupt a *disposable copy only* via an
# independent FAT library, then require ARM9 to reject it without overwriting
# that invalid record. The previously valid canonical image stays untouched.
prepare corrupt-record "$canonical"
corrupt="$work/corrupt-record/$image_name"
check_fat corrupt-record corrupted ALPHA --corrupt-checksum --expect-invalid-record
corrupt_state_sha="$(python3 - "$work/corrupt-record/fat-corrupted.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))["state_sha256"])
PY
)"
boot corrupt-record plump-alpha rejection
check_fat corrupt-record rejection ALPHA --expect-result ERROR --expect-error BAD_RECORD --expect-previous 0 --expect-counter 0 --expect-invalid-record
after_rejection_sha="$(python3 - "$work/corrupt-record/fat-rejection.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))["state_sha256"])
PY
)"
test "$corrupt_state_sha" = "$after_rejection_sha"
test "$(snapshot "$canonical")" = "$canonical_sha"
echo "P4_E1B_CORRUPT_RECORD_REJECTED unchanged_state_sha256=$corrupt_state_sha"

# The OS may deny image writes even if the core appears to boot successfully.
# The host read-only experiment is contained to a new copy, does not test
# durability, and does not claim that the pinned core's own read-only option
# protects committed images (E1 proved that it does not).
prepare os-readonly
readonly="$work/os-readonly/$image_name"
readonly_sha="$(snapshot "$readonly")"
chmod 0444 "$readonly"
boot os-readonly plump-alpha blocked
test "$(snapshot "$readonly")" = "$readonly_sha"
check_fat os-readonly blocked ALPHA --expect-missing
grep -F 'Attempted to open' "$work/os-readonly/boot-blocked.log" | \
    grep -F 'dldi_sd_card.bin' | grep -F 'but failed' > /dev/null
echo "P4_E1B_READONLY_STORAGE_NOT_WRITABLE sha256=$readonly_sha"

sha256sum --check "$work/immutable.sha256"
test "$(snapshot "$baseline")" = "$baseline_sha"
test "$(snapshot "$canonical")" = "$canonical_sha"
test "$(snapshot "$other")" != "$canonical_sha"
test "$(snapshot "$historical")" != "$canonical_sha"
test "$(snapshot "$corrupt")" != "$canonical_sha"
echo "P4_E1B_IMMUTABLE_BASELINE_PRESERVED sha256=$baseline_sha"
echo "P4_E1B_CANONICAL_PRESERVED_DURING_OTHER_TESTS sha256=$canonical_sha"
echo "P4_E1B_GENUINE_DLDI_WRITER_PASS: first write, close/reopen, 3 process cold progressions, distinct ROM, copied branch, bad record and readonly denial"
echo "P4_E1B_EVIDENCE_ROOT=$work"
