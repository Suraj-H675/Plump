#!/usr/bin/env bash
# P4-D4 explicit cartridge-native promotion, actual pinned Libretro cold game
# execution and fresh-JVM interruption matrix. Only accepted disposable D1 files.
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
source="${PLUMP_P4_D_D1_SOURCE:-}"
if [[ -z "$source" ]]; then
    echo 'Set PLUMP_P4_D_D1_SOURCE to an accepted, ignored D1 fixture under app/build.' >&2
    exit 2
fi
source="$(realpath "$source")"
[[ "$source" == "$repo/app/build/"* ]] || { echo 'D1 fixture must be under ignored app/build' >&2; exit 2; }
for f in "$source/gba/s_old.bin" "$source/gba/n_state.bin" "$source/gba/n_latest.bin" "$source/gba/n_branch.bin" "$source/ds/s_old.bin" "$source/ds/n_state.bin" "$source/ds/n_latest.bin" "$source/ds/n_branch.bin" "$source/gba-button-sram.gba"; do
    [[ -f "$f" && ! -L "$f" ]] || { echo "Missing or unsafe disposable fixture: $f" >&2; exit 2; }
done
root="$(mktemp -d "$repo/app/build/p4-d4-acceptance-XXXXXXXX")"
classes="$root/classes"
mkdir -p "$classes"
javac --release 17 -d "$classes" \
    app/src/main/java/dev/plump/emulator/{GameSaveStore,CheckpointStore,HistoricalBranchStore,ActiveTimelineStore,NativeSavePromotionStore,EmulatorSessionOwner,SessionReceiptStore}.java \
    tests/host/{PromotionD4HostCli,PromotionD4HardKillProbe}.java
export PYTHONDONTWRITEBYTECODE=1
host=dev.plump.emulator.PromotionD4HostCli
killprobe=dev.plump.emulator.PromotionD4HardKillProbe
# Every Java invocation runs in a fresh process; each core game stage also runs
# in a fresh Python process and consumes real pinned mGBA/melonDS Libretro.
for console in gba ds; do
    proof="$root/$console-success"
    mkdir -p "$proof"
    java -cp "$classes" "$host" seed "$proof" "$console" "$source/$console"
    java -cp "$classes" "$host" promote "$proof" "$console"
    java -cp "$classes" "$host" extract "$proof" "$console" "$source/$console"
    for stage in cold2 anchor1 branch2; do
        python3 -u tests/roms/verify-p4-d4-real-core-promotion.py "$console" "$stage" "$proof" "$source" > "$root/$console-$stage.log" 2>&1
        grep '^PASS ACTUAL PINNED REAL D4' "$root/$console-$stage.log"
    done
    java -cp "$classes" "$host" write-next "$proof" "$console" "$proof/canonical3.bin"
    java -cp "$classes" "$host" extract-latest "$proof" "$console" "$source/$console"
    python3 -u tests/roms/verify-p4-d4-real-core-promotion.py "$console" cold3 "$proof" "$source" > "$root/$console-cold3.log" 2>&1
    grep '^PASS ACTUAL PINNED REAL D4' "$root/$console-cold3.log"

    # Separate explicit rollback from the successful continued counter3 history;
    # reversing that later progress without anchoring it must NOT be done.
    undo="$root/$console-explicit-rollback"
    mkdir -p "$undo"
    java -cp "$classes" "$host" seed "$undo" "$console" "$source/$console"
    java -cp "$classes" "$host" promote "$undo" "$console"
    java -cp "$classes" "$host" rollback "$undo" "$console" "$source/$console"
    java -cp "$classes" "$host" verify-rollback "$undo" "$console" "$source/$console"
    python3 -u tests/roms/verify-p4-d4-real-core-promotion.py "$console" rollback1 "$undo" "$source" > "$root/$console-rollback1.log" 2>&1
    grep '^PASS ACTUAL PINNED REAL D4' "$root/$console-rollback1.log"

    cases=(BEFORE_ANCHOR AFTER_ANCHOR BEFORE_INTENT AFTER_INTENT BEFORE_PLSV_WRITE
           DURING_PLSV_WRITE DURING_PLSV_PARTIAL_WRITE AFTER_PLSV_RECORD_MOVE
           AFTER_PLSV_WRITE BEFORE_SELECTOR AFTER_SELECTOR BEFORE_FINAL_COMMIT
           AFTER_FINAL_COMMIT ROLLBACK_BEFORE_ROLLBACK_WRITE ROLLBACK_AFTER_ROLLBACK_WRITE
           ROLLBACK_DURING_PLSV_PARTIAL_WRITE ROLLBACK_AFTER_PLSV_RECORD_MOVE
           ROLLBACK_BEFORE_ROLLBACK_SELECTOR ROLLBACK_AFTER_ROLLBACK_SELECTOR
           ROLLBACK_BEFORE_ROLLED_BACK_RECEIPT ROLLBACK_AFTER_ROLLED_BACK_RECEIPT
           RECOVER_FINISH_AFTER_PLSV_WRITE RECOVER_ROLLBACK_AFTER_WRITE)
    passed=0
    for point in "${cases[@]}"; do
        stage="$root/$console-kill-$point"
        mkdir -p "$stage"
        out="$root/$console-kill-$point.log"
        java -cp "$classes" "$killprobe" prepare "$stage" "$console" "$point" "$source/$console" > "$out" 2>&1
        rc=0
        java -cp "$classes" "$killprobe" kill "$stage" "$console" "$point" "$source/$console" >> "$out" 2>&1 || rc=$?
        if (( rc != 87 )); then
            echo "FAIL $console $point: expected abrupt exit 87, got $rc" >&2
            tail -n 38 "$out" >&2
            exit 1
        fi
        if ! java -cp "$classes" "$killprobe" verify "$stage" "$console" "$point" "$source/$console" >> "$out" 2>&1; then
            echo "FAIL $console $point: fresh-JVM recovery verification failed" >&2
            tail -n 38 "$out" >&2
            exit 1
        fi
        grep '^PASS D4 FRESH-JVM HARD-KILL RECOVERY' "$out" | tail -1
        passed=$((passed+1))
    done
    echo "PASS D4 $console: $passed/${#cases[@]} fresh-JVM abrupt kill and independent recovery cases"

    # Recovery is NEVER automatic. Independently reopen a killed transaction,
    # explicitly choose finish or rollback, retry idempotently in another JVM,
    # and actually cold-load the resulting SRAM/EEPROM in the pinned core.
    for scenario in \
        finish:BEFORE_ANCHOR:cold2 \
        finish:AFTER_PLSV_RECORD_MOVE:cold2 \
        finish:AFTER_SELECTOR:cold2 \
        rollback:AFTER_PLSV_RECORD_MOVE:rollback1 \
        rollback:ROLLBACK_AFTER_PLSV_RECORD_MOVE:rollback1; do
        IFS=: read -r decision cut_point cold_stage <<< "$scenario"
        stage="$root/$console-explicit-$decision-after-$cut_point"
        mkdir -p "$stage"
        out="$root/$console-explicit-$decision-$cut_point.log"
        java -cp "$classes" "$killprobe" prepare "$stage" "$console" "$cut_point" "$source/$console" > "$out" 2>&1
        rc=0
        java -cp "$classes" "$killprobe" kill "$stage" "$console" "$cut_point" "$source/$console" >> "$out" 2>&1 || rc=$?
        if (( rc != 87 )); then echo "FAIL $console explicit $decision kill exit=$rc" >&2; tail -n 30 "$out" >&2; exit 1; fi
        java -cp "$classes" "$killprobe" verify "$stage" "$console" "$cut_point" "$source/$console" >> "$out" 2>&1
        java -cp "$classes" "$host" "recover-$decision" "$stage" "$console" "$source/$console" >> "$out" 2>&1
        java -cp "$classes" "$host" "recover-$decision" "$stage" "$console" "$source/$console" >> "$out" 2>&1
        if [[ "$decision" == finish ]]; then
            java -cp "$classes" "$host" extract "$stage" "$console" "$source/$console" >> "$out" 2>&1
        else
            java -cp "$classes" "$host" verify-rollback "$stage" "$console" "$source/$console" >> "$out" 2>&1
        fi
        python3 -u tests/roms/verify-p4-d4-real-core-promotion.py "$console" "$cold_stage" "$stage" "$source" >> "$out" 2>&1
        grep '^PASS D4 EXPLICIT POST-KILL' "$out"
        grep '^PASS ACTUAL PINNED REAL D4' "$out"
    done
    echo "PASS D4 $console: five explicit fresh-JVM recoveries, repeated-idempotent recovery and actual pinned real-core cold load"
done
echo 'PASS P4-D4 REAL PINNED MGBA/MELONDS PROMOTION, ORIGINAL THREE-FILE ROLLBACK, COUNTER3 CONTINUATION, 46 PROCESS-DEATH BOUNDARIES AND TEN EXPLICIT REAL-CORE POST-KILL RECOVERIES'
echo "Ignored evidence: $root"
