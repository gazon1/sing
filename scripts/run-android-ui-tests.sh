#!/usr/bin/env bash
#
# Runs Android UI instrumentation tests on a real adb-connected device.
# Must be run from the project root directory.
#
# Device selection goes through scripts/ensure-emulator.sh rather than a local
# `adb devices | awk`. That script is the repo's single recovery primitive: it
# returns a ready serial when one exists, and otherwise starts the AVD with the
# verified flags and waits for boot. A hand-rolled discovery here could only
# ever find a device that was *already* up, so every emulator crash surfaced as
# "no adb device found" — the message says "connect a device", when the actual
# remedy is one command the runner already knows how to run.
#
# Usage:
#   ./scripts/run-android-ui-tests.sh              # ensure a device, starting the AVD if needed
#   ./scripts/run-android-ui-tests.sh <serial>     # pin a specific device
#
set -euo pipefail

SERIAL="${1:-}"

if [[ -n "$SERIAL" ]]; then
    # Pinned. ensure-emulator.sh still validates readiness, and restarts the AVD
    # if a pinned *emulator* is merely crashed; a pinned physical device that is
    # not ready stays a hard error.
    SERIAL="$(SERIAL="$SERIAL" ./scripts/ensure-emulator.sh)"
else
    SERIAL="$(./scripts/ensure-emulator.sh)"
fi

echo "Running Android UI tests on device: $SERIAL"
echo ""

./gradlew :androidApp:assembleDebug --quiet

./gradlew :androidApp:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.device="$SERIAL" \
    --no-build-cache

echo ""
echo "Done."
