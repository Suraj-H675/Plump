#!/usr/bin/env bash
# Read-only Plump-only real DS performance sample. No device UI automation,
# APK install, personal app inspection, global log clear or system changes.
# Owner must first open a licensed DS game in the installed Plump app.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
serial="${PLUMP_ADB_SERIAL:-}"
seconds="${1:-180}"
[[ "$seconds" =~ ^[0-9]+$ ]] && (( seconds >= 60 && seconds <= 600 )) || {
    echo 'Use a measurement duration of 60-600 seconds.' >&2; exit 2;
}

if [[ -z "$serial" ]]; then
    # No personal device serial is committed to the public repository. Fail
    # closed when multiple devices are attached rather than choosing one.
    mapfile -t available < <("$adb" devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if (( ${#available[@]} != 1 )); then
        echo 'Exactly one authorized ADB device required, or set PLUMP_ADB_SERIAL.' >&2
        exit 2
    fi
    serial="${available[0]}"
fi
test "$("$adb" -s "$serial" get-state)" = device
test "$("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r')" = I2202
foreground="$("$adb" -s "$serial" shell dumpsys activity activities | grep -m1 topResumedActivity)"
if [[ "$foreground" != *dev.plump.emulator/.DsActivity* ]]; then
    echo 'Plump DS is not foreground. Open the licensed test ROM manually, then rerun.' >&2
    exit 2
fi
pid="$("$adb" -s "$serial" shell pidof dev.plump.emulator | tr -d '\r')"
test -n "$pid"

directory="$(mktemp -d "$root/app/build/p3-perf-XXXXXXXX")"
chmod 700 "$directory"
start="$("$adb" -s "$serial" shell date +%s | tr -d '\r')"
"$adb" -s "$serial" shell dumpsys battery | grep -E 'level:|temperature:' > "$directory/battery-before.txt"
"$adb" -s "$serial" shell dumpsys meminfo dev.plump.emulator > "$directory/app-memory-before.txt"
"$adb" -s "$serial" shell dumpsys package dev.plump.emulator | grep -m2 -E 'versionCode=|versionName=' > "$directory/app-version.txt"
printf 'Read-only Plump DS sampling: %ss, PID %s. Keep game foreground; no UI actions will be sent.\n' "$seconds" "$pid"
sleep "$seconds"
end="$("$adb" -s "$serial" shell date +%s | tr -d '\r')"
"$adb" -s "$serial" logcat -d --pid="$pid" -v epoch -s PlumpDS:I > "$directory/plump-only-logcat.txt"
"$adb" -s "$serial" shell dumpsys battery | grep -E 'level:|temperature:' > "$directory/battery-after.txt"
"$adb" -s "$serial" shell dumpsys meminfo dev.plump.emulator > "$directory/app-memory-after.txt"
foreground_after="$("$adb" -s "$serial" shell dumpsys activity activities | grep -m1 topResumedActivity)"
pid_after="$("$adb" -s "$serial" shell pidof dev.plump.emulator | tr -d '\r')"
if [[ "$foreground_after" != *dev.plump.emulator/.DsActivity* || "$pid_after" != "$pid" ]]; then
    echo 'Plump was not foreground or its process restarted; benchmark interrupted.' >&2
    echo "Logs retained privately: $directory" >&2
    exit 1
fi
python3 "$root/tests/device/parse-p3-performance.py" \
    "$directory/plump-only-logcat.txt" "$start" "$end" "$directory/summary.json"
echo "App-only, private benchmark evidence: $directory"
