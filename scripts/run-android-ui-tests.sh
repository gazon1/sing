#!/usr/bin/env bash
#
# Runs Android UI instrumentation tests on a real adb-connected device.
# Must be run from the project root directory.
#
# Usage:
#   ./scripts/run-android-ui-tests.sh              # auto-detect first adb device
#   ./scripts/run-android-ui-tests.sh <serial>     # use specific device serial
#
set -euo pipefail

SERIAL="${1:-}"

if [[ -z "$SERIAL" ]]; then
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi

if [[ -z "$SERIAL" ]]; then
    echo "ERROR: No adb device found. Connect a device or emulator and retry."
    echo "Usage: $0 [serial]"
    exit 1
fi

echo "Running Android UI tests on device: $SERIAL"
echo ""

./gradlew :androidApp:assembleDebug --quiet

./gradlew :androidApp:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.device="$SERIAL" \
    --no-build-cache

echo ""
echo "Done."
