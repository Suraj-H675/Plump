#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$root/app/build/p4-d-branch-publication"
classes="$work/classes"
mkdir -p "$classes"
javac --release 17 -d "$classes" \
    "$root/app/src/main/java/dev/plump/emulator/GameSaveStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/CheckpointStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/HistoricalBranchStore.java" \
    "$root/app/src/main/java/dev/plump/emulator/ActiveTimelineStore.java" \
    "$root/tests/host/BranchPublicationProbe.java" \
    "$root/tests/host/TimelineRecoveryHardKillProbe.java"

for point in AFTER_MACHINE AFTER_JOURNAL BEFORE_PUBLISH AFTER_PUBLISH AFTER_READBACK; do
    dir="$(mktemp -d "$work/death-$point-XXXXXXXX")"
    java -cp "$classes" dev.plump.emulator.BranchPublicationProbe prepare "$dir"
    rc=0
    java -cp "$classes" dev.plump.emulator.BranchPublicationProbe kill "$dir" "$point" || rc=$?
    if [[ "$rc" -ne 87 ]]; then
        echo "FAIL: JVM hard exit at $point gave $rc, not 87" >&2
        exit 1
    fi
    java -cp "$classes" dev.plump.emulator.BranchPublicationProbe verify "$dir" "$point"
done
echo 'PASS P4-D: 5/5 separate-process hard exits preserve all canonical PLSV files and verified checkpoint pairs'

points=(BEFORE_BRANCH_SELECTION AFTER_BRANCH_PUBLICATION AFTER_SELECTOR_PREPARED BEFORE_CORE_INIT \
    AFTER_CORE_INIT AFTER_NATIVE_RESTORE AFTER_MACHINE_RESTORE BEFORE_RESUMABLE_COMMITTED \
    AFTER_RESUMABLE_COMMITTED DURING_SUBSEQUENT_JOURNAL_WRITE)
for point in "${points[@]}"; do
    dir="$(mktemp -d "$work/timeline-$point-XXXXXXXX")"
    java -cp "$classes" dev.plump.emulator.TimelineRecoveryHardKillProbe prepare "$dir" -
    rc=0
    java -cp "$classes" dev.plump.emulator.TimelineRecoveryHardKillProbe kill "$dir" "$point" || rc=$?
    if [[ "$rc" -ne 87 ]]; then
        echo "FAIL: JVM hard exit at $point gave $rc, not 87" >&2
        exit 1
    fi
    java -cp "$classes" dev.plump.emulator.TimelineRecoveryHardKillProbe verify "$dir" "$point"
done
echo 'PASS P4-D: 10/10 fresh-JVM hard exits recover classifications and preserve canonical PLSV/checkpoint evidence'
