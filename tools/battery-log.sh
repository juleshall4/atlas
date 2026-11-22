#!/usr/bin/env bash
# Copies the Atlas Debug battery log from the phone. Pass --clear to delete it on the phone after.
set -euo pipefail
cd "$(dirname "$0")/.."

dir=/sdcard/Android/data/com.atlas.debug/files
out=dist/battery-$(date +%Y%m%d-%H%M%S).csv
mkdir -p dist
adb pull "$dir/battery.csv" "$out" >/dev/null
echo "Saved $out ($(($(wc -l < "$out") - 1)) rows)"
if [[ "${1:-}" == "--clear" ]]; then adb shell rm -f "$dir/battery.csv" "$dir/battery.old.csv"; fi
