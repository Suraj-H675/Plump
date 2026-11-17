#!/usr/bin/env bash
# Host-only P4-E1 limited SD mount/sidecar/failure investigation.
# Deliberately NOT an emulated in-game filesystem write acceptance test.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
core=app/build/p3-melonds-host/src/libretro/melondsds_libretro.so
source=app/build/p3-melonds-source/test/nds/tests_sys_mem_regions.nds
second=app/build/p3-input-diagnostic/p3_input_diagnostic.nds
test "$(sha256sum "$core" | cut -d' ' -f1)" = e8c5fd156e9d1fe9fa861cd93cc0ef25ddc7eecb0ccf65a6a6a8c4695e577616
test "$(sha256sum "$source" | cut -d' ' -f1)" = 9d7f86224faec8da2b22b103cb2ff9a84c9120cc33b5cc43498d108924b5813f
test "$(sha256sum "$second" | cut -d' ' -f1)" = 109b7c302ade948e73b8b52081c37a380d8f1ea6a4c1be2cee062e1cd304482d
command -v mkfs.fat >/dev/null
command -v fsck.fat >/dev/null
work="$(mktemp -d "$repo/app/build/p4-e1-acceptance-XXXXXXXX")"
test "${work#"$repo/app/build/p4-e1-acceptance-"}" != "$work"
echo 'PLUMP_E1_TEST_ROOT' > "$work/.p4-e1-owned"
baseline="$work/immutable-preformatted"
mkdir -p "$baseline"
image="$baseline/dldi_sd_card.bin"
truncate -s 134217728 "$image"
mkfs.fat -F 16 -n PLE1TEST "$image" > "$work/mkfs.log" 2>&1
test "$(stat -c %s "$image")" -eq 134217728
sha256sum "$image" > "$work/immutable-before.sha256"
chmod 0444 "$image"
export PYTHONDONTWRITEBYTECODE=1

prepare() {
    local stage="$1" dir="$work/$1"; mkdir -p "$dir/save/melonDS DS" "$dir/system"
    cp --sparse=always "$image" "$dir/save/melonDS DS/dldi_sd_card.bin"
    chmod 0644 "$dir/save/melonDS DS/dldi_sd_card.bin"
}
ensure_bounded() {
    local stage="$1" path="$work/$1/save/melonDS DS/dldi_sd_card.bin"
    local apparent=$(stat -c %s "$path")
    local blocks=$(stat -c %b "$path")
    if (( apparent > 167772160 || blocks > 65536 )); then
        echo "FAIL: $stage virtual volume exceeded hard test limit; inspect ignored path" >&2
        exit 1
    fi
    printf 'E1_BOUNDED %-22s apparent=%d allocated=%d SHA-256=%s\n' \
        "$stage" "$apparent" "$((blocks * 512))" "$(sha256sum "$path" | cut -d' ' -f1)"
}
boot() {
    local stage="$1" game="$2" frames="$3"; shift 3
    PYTHONDONTWRITEBYTECODE=1 timeout 75 python3 -u \
        tests/roms/verify-p4-e1-virtual-sd.py "$work/$stage" "$game" "$frames" "$@" \
        > "$work/$stage/boot.log" 2>&1 || {
            echo "FAIL or unsupported core boot: $stage" >&2
            tail -n 35 "$work/$stage/boot.log" >&2
            return 1
        }
    local fsck_exit=0
    fsck.fat -nv "$work/$stage/save/melonDS DS/dldi_sd_card.bin" \
        > "$work/$stage/fsck-readonly.log" 2>&1 || fsck_exit=$?
    if (( fsck_exit != 0 )); then
        echo "E1_UNSAFE_READONLY_FSCK_FOUND_ERRORS stage=$stage exit=$fsck_exit (no repair attempted)"
    fi
    grep '^P4_E1_HOST_BOOT' "$work/$stage/boot.log" | cut -c1-195
    ensure_bounded "$stage"
}

prepare canonical
boot canonical upstream-memory 150
cp "$work/canonical/result.json" "$work/canonical/result-first.json"
canonical_image="$work/canonical/save/melonDS DS/dldi_sd_card.bin"
before_cold="$(sha256sum "$canonical_image" | cut -d' ' -f1)"
boot canonical upstream-memory 150 # Another real process, same independent working image.
echo "E1_SAME_IMAGE_COLD_REOPEN before=$before_cold after=$(sha256sum "$canonical_image" | cut -d' ' -f1)"

prepare other-rom
boot other-rom cc0-input 150
different_image="$work/other-rom/save/melonDS DS/dldi_sd_card.bin"
test "$(sha256sum "$canonical_image" | cut -d' ' -f1)" != "$(sha256sum "$different_image" | cut -d' ' -f1)"

prepare historical-branch
cp --sparse=always "$canonical_image" "$work/historical-branch/save/melonDS DS/dldi_sd_card.bin"
canonical_pre_branch="$(sha256sum "$canonical_image" | cut -d' ' -f1)"
boot historical-branch upstream-memory 150
test "$(sha256sum "$canonical_image" | cut -d' ' -f1)" = "$canonical_pre_branch"
echo 'E1_DISPOSABLE_WORKDIR_ISOLATION pinned core boot, distinct ROMs and same-ROM copied branch root; no game-level write yet'

prepare readonly-option
read_only_image="$work/readonly-option/save/melonDS DS/dldi_sd_card.bin"
read_only_before="$(sha256sum "$read_only_image" | cut -d' ' -f1)"
boot readonly-option upstream-memory 150 readonly
if [[ "$(sha256sum "$read_only_image" | cut -d' ' -f1)" != "$read_only_before" ]]; then
    echo 'E1_UNSAFE_CORE_READONLY_OPTION_DOES_NOT_PROTECT_IMAGE: actual pinned core changed FAT despite melonds_homebrew_readonly=enabled'
else
    echo 'E1_CORE_READONLY_OPTION_RETAINED_BASELINE_IMAGE'
fi

prepare missing-index
test ! -e "$work/missing-index/save/melonDS DS/dldi_sd_card.bin.idx"
boot missing-index upstream-memory 150
echo 'E1_MISSING_INDEX_BOOTED_WITH_SYNC_DISABLED; index is not required for this narrow mount path'

prepare malformed-index
printf 'SIZE not-a-number\nFILE invalid\n' > "$work/malformed-index/save/melonDS DS/dldi_sd_card.bin.idx"
boot malformed-index upstream-memory 150
echo 'E1_MALFORMED_INDEX_BOOTED_WITH_SYNC_DISABLED; absence of rejection is not proof of index safety'

prepare host-file-readonly
os_readonly_image="$work/host-file-readonly/save/melonDS DS/dldi_sd_card.bin"
os_readonly_sha="$(sha256sum "$os_readonly_image" | cut -d' ' -f1)"
chmod 0444 "$os_readonly_image"
boot host-file-readonly upstream-memory 90
test "$(sha256sum "$os_readonly_image" | cut -d' ' -f1)" = "$os_readonly_sha"
grep -E 'Attempted to open .*dldi_sd_card.bin.*but failed' "$work/host-file-readonly/boot.log" > /dev/null
echo 'E1_UNSAFE_CORE_GAME_BOOT_SUCCEEDS_EVEN_WITH_OS_READONLY_SD_OPEN_FAILURE'

# Corrupt an independently copied working image only. The Python probe enforces
# 160 MiB RLIMIT_FSIZE, preventing an uncontrolled format/allocation fallback.
prepare corrupt-image
corrupt_image="$work/corrupt-image/save/melonDS DS/dldi_sd_card.bin"
python3 - "$corrupt_image" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1]); assert path.is_file() and path.stat().st_size == 134217728
with path.open('r+b') as handle:
    handle.seek(0)
    handle.write(bytes(512))
PY
corrupt_exit=0
PYTHONDONTWRITEBYTECODE=1 timeout 45 python3 -u \
    tests/roms/verify-p4-e1-virtual-sd.py "$work/corrupt-image" upstream-memory 90 \
    > "$work/corrupt-image/boot.log" 2>&1 || corrupt_exit=$?
ensure_bounded corrupt-image
echo "E1_CORRUPT_IMAGE_CORE_BOOT_EXIT=$corrupt_exit"
if [[ -f "$corrupt_image.idx" ]]; then
    head -n 1 "$corrupt_image.idx" | sed 's/^/E1_CORRUPT_IMAGE_INDEX_/'
fi
fsck.fat -nv "$corrupt_image" > "$work/corrupt-image/fsck-readonly.log" 2>&1 || {
    echo 'E1_UNSAFE_CORRUPT_IMAGE_REMAINS_INVALID_AFTER_CORE_BOOT (no repair attempted)'
}

prepare no-unmount
rc=0
timeout 75 python3 -u tests/roms/verify-p4-e1-virtual-sd.py \
    "$work/no-unmount" upstream-memory 150 hard-stop \
    > "$work/no-unmount/boot.log" 2>&1 || rc=$?
if (( rc != 87 )); then echo "FAIL hard process death returned $rc" >&2; exit 1; fi
grep 'P4_E1_HARD_KILL_87_AFTER_CORE_FRAMES_BEFORE_UNLOAD' "$work/no-unmount/boot.log"
fsck.fat -nv "$work/no-unmount/save/melonDS DS/dldi_sd_card.bin" \
    > "$work/no-unmount/fsck-after-kill.log" 2>&1 || {
        echo 'E1_UNSAFE_CRASH_LEFT_FAT_INCONSISTENT (read-only fsck; no attempted repair)'
    }
ensure_bounded no-unmount
boot no-unmount upstream-memory 150
echo 'E1_ABRUPT_NO_UNMOUNT_REOPENED; not an in-sector write/close durability guarantee'

sha256sum --check "$work/immutable-before.sha256"
echo "E1_BASELINE_STILL_VALID sha256=$(sha256sum "$image" | cut -d' ' -f1)"
echo 'E1_HOST_DIAGNOSTIC_COMPLETE: pinned 128MiB mount, two homebrew boots, separate paths, cold path, readonly/index cases, hard kill; STILL NO EMULATED-GAME FILE-WRITE PROOF OR DURABLE SD ACCEPTANCE'
echo "Ignored evidence directory: $work"
