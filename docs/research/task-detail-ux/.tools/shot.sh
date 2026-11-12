#!/usr/bin/env bash
# shot.sh <out-path.png> [ui-dump-path.xml]
# Screenshot the connected device; optionally dump the accessibility hierarchy.
set -euo pipefail
S=0O65321F32204DD5
out="$1"
adb -s $S shell screencap -p /sdcard/uxr.png
adb -s $S pull /sdcard/uxr.png "$out" >/dev/null
if [[ ${2:-} != "" ]]; then
  adb -s $S shell uiautomator dump /sdcard/uxr.xml >/dev/null 2>&1 || true
  adb -s $S pull /sdcard/uxr.xml "$2" >/dev/null 2>&1 || true
fi
echo "$out"
