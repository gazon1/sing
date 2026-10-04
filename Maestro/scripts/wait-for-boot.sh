#!/usr/bin/env bash
#
# Waits for an Android device/emulator to finish booting.
#
# Used as the `script:` hook of reactivecircus/android-emulator-runner in CI.
# That action returns once the emulator process is up, which is not the same as
# the device being usable: `adb devices` can already list it while the package
# manager is still starting, and installing on a half-booted device fails with
# an error that looks nothing like "not ready yet".
#
# Two signals, both required:
#   - `sys.boot_completed` is 1
#   - `pm path android` resolves, i.e. the package manager answers
#
# Exits non-zero on timeout so the step fails loudly instead of hanging.

set -euo pipefail

TIMEOUT_SECONDS="${BOOT_TIMEOUT:-300}"
SERIAL="${ANDROID_SERIAL:-${SERIAL:-}}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB=(adb -s "$SERIAL")
fi

echo "Waiting up to ${TIMEOUT_SECONDS}s for boot (serial='${SERIAL:-auto}')…"
deadline=$((SECONDS + TIMEOUT_SECONDS))

# Note: `"${ADB[@]}"` is a command, not a value, so it cannot appear inside
# [[ … ]]. A shell function keeps the call site readable without the array.
adb_cmd() { "${ADB[@]}" "$@"; }

until adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; do
    if (( SECONDS > deadline )); then
        echo "TIMEOUT: sys.boot_completed never became 1" >&2
        adb_cmd devices >&2 || true
        exit 1
    fi
    sleep 3
done

until adb_cmd shell pm path android >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then
        echo "TIMEOUT: package manager never answered" >&2
        exit 1
    fi
    sleep 2
done

# The boot animation keeps the screen asleep on first launch sometimes;
# run-maestro.sh handles wake/unlock itself, so this is only a sanity gate.
adb_cmd devices >&2 || true
echo "Device booted."
