#!/usr/bin/env bash
#
# Ensures a usable Android device is available and prints its serial on stdout.
#
# The emulator on this host crashes intermittently: its gfxstream render thread
# segfaults inside gfxstream::host::gl::TextureResize while the guest asks the
# host to create a new ColorBuffer — which happens whenever a new surface
# appears (a bottom sheet, a dialog, a navigation push). The crash is a host
# emulator bug, not an app fault, and there is no renderer configuration that
# avoids it: `-gpu software` and `-gpu swangle` both die during startup on this
# API 36 AVD, leaving `-gpu auto` as the only bootable option.
#
# So the fix is recovery rather than prevention. This script is the recovery
# primitive: if a device is already up it prints its serial and exits; otherwise
# it starts the AVD, waits for boot, and prints the serial. Callers re-run the
# work that was interrupted.
#
# Usage:
#   scripts/ensure-emulator.sh                 # print serial, starting AVD if needed
#   AVD=Pixel_7 scripts/ensure-emulator.sh     # a different AVD
#   EMULATOR_TIMEOUT=420 scripts/ensure-emulator.sh
#   SERIAL=emulator-5554 scripts/ensure-emulator.sh   # pin and never start one
set -euo pipefail

AVD="${AVD:-Medium_Phone}"
SERIAL="${SERIAL:-}"
EMULATOR_TIMEOUT="${EMULATOR_TIMEOUT:-300}"
LOG_FILE="${EMULATOR_LOG:-/tmp/singularity-emulator.log}"

RED=$'\033[0;31m'; YELLOW=$'\033[0;33m'; NC=$'\033[0m'

first_ready_device() {
    adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'
}

# A serial that adb still knows about but has not finished booting is not usable.
device_is_ready() {
    local serial="$1"
    [[ -n "$serial" ]] || return 1
    adb -s "$serial" get-state 2>/dev/null | grep -q "^device$" || return 1
    [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]
}

# ── 1. Already up? ────────────────────────────────────────────────────────────
if [[ -n "$SERIAL" ]] && device_is_ready "$SERIAL"; then
    echo "$SERIAL"
    exit 0
fi

if [[ -z "$SERIAL" ]]; then
    SERIAL="$(first_ready_device || true)"
    if [[ -n "$SERIAL" ]] && device_is_ready "$SERIAL"; then
        echo "$SERIAL"
        exit 0
    fi
fi

if [[ -n "$SERIAL" ]]; then
    # The caller pinned a serial that is not usable. Starting a second AVD would
    # land on emulator-5554 and not match, so this is a hard error rather than
    # something to paper over by launching something else.
    echo -e "${RED}Device '$SERIAL' is present but not ready.${NC}" >&2
    adb devices >&2 || true
    exit 1
fi

# ── 2. Start the AVD ──────────────────────────────────────────────────────────
echo -e "${YELLOW}No ready device; starting AVD '$AVD'.${NC}" >&2

# A crashed run leaves the AVD locked; a second qemu would refuse to start.
pkill -9 -f "qemu-system-x86_64.*$AVD" >/dev/null 2>&1 || true
sleep 2
rm -f "$HOME/.android/avd/$AVD.avd/"*.lock >/dev/null 2>&1 || true

# Flags, and why each is here:
#   -no-snapshot-load  the stored snapshot was saved under a different renderer;
#                      loading it across a renderer change floods the log and aborts
#   -camera-* none     the virtual camera's WebRTC thread aborts the process after
#                      ~9 minutes of uptime
#   -no-audio          removes an unrelated host-audio failure mode
# Not used, all ruled out by experiment on this host:
#   -no-window         SIGSEGV before the window is created
#   -gpu software      dies during startup (API 36)
#   -gpu swangle       dies during startup
emulator -avd "$AVD" \
    -no-snapshot-load -no-boot-anim \
    -camera-back none -camera-front none -no-audio \
    >"$LOG_FILE" 2>&1 &

# `&` alone is not enough: a child dies with the shell that spawned it, so the
# process is held alive here for a few seconds past emulator start-up.
sleep 10

emulator_running() {
    # Safe inside a script file: the invoking process's command line is just
    # "bash ensure-emulator.sh", so pgrep -f cannot match this pattern the way
    # an interactive one-liner would.
    pgrep -f "qemu-system-x86_64 -avd $AVD" >/dev/null 2>&1
}

# ── 3. Wait for boot ──────────────────────────────────────────────────────────
deadline=$((SECONDS + EMULATOR_TIMEOUT))
# Start-up is not instant; treat a missing process as fatal only after the guest
# has had time to register. A cold boot of this AVD completes in ~12-60s.
grace_until=$((SECONDS + 45))

while (( SECONDS < deadline )); do
    if (( SECONDS > grace_until )) && ! emulator_running; then
        echo -e "${RED}Emulator process exited during start-up.${NC}" >&2
        echo -e "${YELLOW}See $LOG_FILE${NC}" >&2
        tail -20 "$LOG_FILE" >&2 || true
        exit 1
    fi
    SERIAL="$(first_ready_device || true)"
    if [[ -n "$SERIAL" ]] && device_is_ready "$SERIAL"; then
        echo "$SERIAL"
        exit 0
    fi
    sleep 5
done

echo -e "${RED}Emulator did not finish booting within ${EMULATOR_TIMEOUT}s.${NC}" >&2
echo -e "${YELLOW}See $LOG_FILE${NC}" >&2
exit 1
