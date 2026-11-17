#!/usr/bin/env bash
# Uses previously verified, disposable D1 real-core checkpoint pairs. No ROMs or
# native-save blobs are committed, copied into source, or read from personal data.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
source_root="${PLUMP_P4_D_D1_SOURCE:-}"
if [[ -z "$source_root" || ! -f "$source_root/gba/s_old.bin" || ! -f "$source_root/ds/s_old.bin" || ! -f "$source_root/gba-button-sram.gba" ]]; then
    echo 'Supply PLUMP_P4_D_D1_SOURCE=/absolute/ignored/app/build/p4-d-d1-XXXX from the accepted D1 host script.' >&2
    exit 2
fi
source_root="$(realpath "$source_root")"
[[ "$source_root" == "$root/app/build/"* ]] || {
    echo 'Host restoration-death fixture directory must be disposable and under this checkout app/build/' >&2
    exit 2
}
gba_sha="$(sha256sum "$source_root/gba-button-sram.gba" | awk '{print $1}')"
[[ "$gba_sha" == c0bd6d6e77f94edba1bd14eb215f2e35590a1e2ad5edfe3d1774c88d998fd2f5 ]] || {
    echo 'Generated source-pinned GBA test ROM hash mismatch' >&2; exit 2
}
work="$(mktemp -d "$root/app/build/p4-d-actual-core-deaths-XXXXXXXX")"
classes="$work/classes"
mkdir -p "$classes"
javac --release 17 -d "$classes" \
    "$root/app/src/main/java/dev/plump/emulator/GameSaveStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/CheckpointStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/ActiveTimelineStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/NativeSavePromotionStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/SessionReceiptStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/EmulatorSessionOwner.java" \
    "$root/tests/host/RealCoreDeathProbe.java"
export PYTHONDONTWRITEBYTECODE=1
for console in gba ds; do
  for point in AFTER_CORE_INIT AFTER_NATIVE_RESTORE AFTER_MACHINE_RESTORE AFTER_FINAL_NATIVE_READBACK; do
    stage_dir="$(mktemp -d "$work/${console}-${point}-XXXXXXXX")"
    log="$work/${console}-${point}.log"
    java -cp "$classes" dev.plump.emulator.RealCoreDeathProbe prepare "$console" "$source_root/$console" "$stage_dir" > "$log"
    rc=0
    python3 -u "$root/tests/roms/verify-p4-d-real-restore-death.py" "$console" "$point" "$stage_dir" "$source_root/$console" >> "$log" 2>&1 || rc=$?
    if [[ "$rc" -ne 87 ]]; then
       echo "FAIL: actual pinned $console core at $point exited with $rc, not 87" >&2
       tail -n 20 "$log" >&2
       exit 1
    fi
    java -cp "$classes" dev.plump.emulator.RealCoreDeathProbe verify "$console" "$source_root/$console" "$stage_dir" >> "$log"
    grep '^PASS REAL CORE KILL POST-RESTART' "$log"
  done
done
echo 'PASS 8/8 actual pinned mGBA/melonDS core restoration hard exits; ambiguous selector, source pair and complete canonical PLSV preserved'
echo "Ignored host evidence: $work"
