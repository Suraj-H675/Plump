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
    "$root/tests/host/BranchPublicationProbe.java"

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
