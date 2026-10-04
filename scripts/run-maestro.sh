#!/usr/bin/env bash
#
# Runs Maestro UI flows against a connected Android device or emulator.
#
# Device pre-flight (adb discovery, wake, unlock, install, logcat triage) is
# the same procedure documented in
# .agents/skills/singularity-todo-adb-workflow/SKILL.md — this script automates
# that skill rather than replacing it.
#
# Flows are run one per Maestro invocation and the emulator is relaunched when it
# dies mid-suite, because the host emulator has an unfixed gfxstream crash that a
# single lost device would otherwise turn into a cascade of false failures. See
# scripts/ensure-emulator.sh and
# docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md.
#
# Usage:
#   scripts/run-maestro.sh                          # every flow
#   TAGS=smoke scripts/run-maestro.sh               # only flows tagged "smoke"
#   FLOW=Maestro/flows/tasks scripts/run-maestro.sh # one directory
#   SERIAL=emulator-5554 scripts/run-maestro.sh    # pin the device
#   SKIP_INSTALL=1 scripts/run-maestro.sh          # reuse the installed APK
#   MAESTRO_MAX_RETRIES=0 scripts/run-maestro.sh   # do not retry a lost device
set -euo pipefail

APP_ID="com.singularity.todo"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Maestro 2.x does not descend into subdirectories when handed a bare directory
# and has no --include flag, so the flow list is expanded here instead.
# FLOW may be a directory, a glob, or a single file.
FLOW="${FLOW:-Maestro/flows}"
TAGS="${TAGS:-}"
SERIAL="${SERIAL:-}"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; NC=$'\033[0m'

# ── 1. Locate a usable device ────────────────────────────────────────────────
# `adb devices` reports state in column 2. Anything other than "device" means
# unauthorized/offline, which must not be mistaken for a usable target.
#
# If nothing usable is there, try to recover rather than stopping: the host
# emulator crashes on its own (gfxstream), so "no device" is an expected state
# between flows, not only a setup mistake. ensure-emulator.sh prints a serial if
# one is already up and otherwise starts the AVD and waits for boot.
if [[ -z "$SERIAL" ]]; then
    SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "$SERIAL" ]] || ! adb -s "$SERIAL" get-state 2>/dev/null | grep -q "^device$"; then
    # A serial that adb knows but cannot reach is a leftover from a crashed
    # emulator; ensure-emulator.sh is what decides whether it is recoverable.
    echo -e "${YELLOW}No ready device; attempting recovery.${NC}" >&2
    if ! SERIAL="$("$REPO_ROOT/scripts/ensure-emulator.sh" | tail -1)"; then
        echo -e "${RED}Could not obtain a device.${NC}" >&2
        adb devices >&2 || true
        exit 1
    fi
fi

if ! adb -s "$SERIAL" get-state 2>/dev/null | grep -q "^device$"; then
    echo -e "${RED}Device '$SERIAL' is not ready (state: $(adb -s "$SERIAL" get-state 2>/dev/null || echo unknown)).${NC}" >&2
    exit 1
fi

echo "=== Maestro on $SERIAL ($(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')) ==="

# ── 2. Wake + unlock ─────────────────────────────────────────────────────────
# Emulator screens blank between runs; KEYCODE_MENU unlocks a non-PIN lock screen.
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1 || true

# ── 2b. Keep the soft IME off the screen ─────────────────────────────────────
# The emulator's gfxstream render thread segfaults inside
# gfxstream::host::gl::TextureResize while creating a ColorBuffer, which is what
# the guest asks the host to do every time a new surface appears — and the soft
# keyboard is exactly such a surface. With a hardware keyboard attached the IME
# never shows, so the crash path is never taken.
#
# This is a workaround for a host-side emulator bug, not an app setting: the app
# behaves identically either way. See
# docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md
adb -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1 || true

# ── 3. Install unless told to skip ───────────────────────────────────────────
if [[ "${SKIP_INSTALL:-0}" == "1" ]]; then
    echo "Skipping install (SKIP_INSTALL=1)"
else
    echo "=== Installing debug APK ==="
    (cd "$REPO_ROOT" && ./gradlew :androidApp:installDebug -Pandroid.device="$SERIAL" --quiet)
fi

# ── 4. Clear logcat so a post-run FATAL scan is attributable ────────────────
adb -s "$SERIAL" logcat -c

# ── 5. Run flows ─────────────────────────────────────────────────────────────
# Expand FLOW into a concrete list. Directory -> every *.yaml beneath it;
# anything else is passed through so globs and single files still work.
collect_flows() {
    local target="$1"
    if [[ -d "$target" ]]; then
        find "$target" -name '*.yaml' -type f | sort
    elif [[ -f "$target" ]]; then
        echo "$target"
    else
        # Treat as a glob pattern.
        # shellcheck disable=SC2086
        compgen -G "$target" || true
    fi
}

mapfile -t FLOW_FILES < <(collect_flows "$FLOW")

if [[ ${#FLOW_FILES[@]} -eq 0 ]]; then
    echo -e "${RED}No flow files matched '$FLOW' under $REPO_ROOT${NC}" >&2
    exit 1
fi

echo "Discovered ${#FLOW_FILES[@]} flow file(s):"
printf '  %s\n' "${FLOW_FILES[@]}"

# Maestro's --include-tags is ignored when a single file is passed, so the tag
# filter is applied here. A flow matches when its header carries the tag.
flow_has_tag() {
    local file="$1" tag="$2"
    awk -v want="$tag" '
        /^---[[:space:]]*$/ { in_tags = 0 }
        /^tags:/ { in_tags = 1; next }
        in_tags && /^[[:space:]]*-/ {
            line = $0
            sub(/^[[:space:]]*-[[:space:]]*/, "", line)
            if (line == want) { found = 1; exit }
        }
        END { exit found ? 0 : 1 }
    ' "$file"
}

if [[ -n "$TAGS" ]]; then
    FILTERED=()
    for f in "${FLOW_FILES[@]}"; do
        # A comma-separated TAGS is treated as "any of".
        IFS=',' read -ra WANTED <<< "$TAGS"
        for want in "${WANTED[@]}"; do
            want="$(echo "$want" | tr -d ' ')"
            [[ -z "$want" ]] && continue
            if flow_has_tag "$f" "$want"; then
                FILTERED+=("$f")
                break
            fi
        done
    done
    if [[ ${#FILTERED[@]} -eq 0 ]]; then
        echo -e "${RED}No flow files carry tag(s): $TAGS${NC}" >&2
        exit 1
    fi
    FLOW_FILES=("${FILTERED[@]}")
    echo "Filtering to flows tagged: $TAGS (${#FLOW_FILES[@]} file(s))"
fi

# ── 5b. Run each flow in its own Maestro invocation ───────────────────────────
# The emulator's gfxstream render thread segfaults while creating a ColorBuffer —
# a host-side emulator bug this project cannot fix, and no renderer setting
# avoids it (see scripts/ensure-emulator.sh). The crash takes the whole device
# with it, so a single batched `maestro test a.yaml b.yaml c.yaml` turns one
# crash into a cascade: every later flow fails in milliseconds because there is
# no device left, which reads as a wall of selector failures.
#
# Running one flow per invocation keeps the blast radius to one flow, and a lost
# device is recoverable: relaunch the AVD, reinstall, and retry that flow.
# Measured crash rate on this host: ~1 device loss per 2-3 smoke passes
# (~0.4/pass), so a pass can contain two losses; the default covers that.
MAX_RETRIES="${MAESTRO_MAX_RETRIES:-2}"

PASSED=(); FAILED=()

device_alive() {
    # Both checks, not just adb state: after the emulator's render thread dies,
    # adb keeps listing the serial as "device" for a while before it flips to
    # "offline", and a half-dead adbd can still answer a trivial shell command.
    adb -s "$SERIAL" get-state 2>/dev/null | grep -q "^device$" || return 1
    adb -s "$SERIAL" shell echo ping 2>/dev/null | grep -q ping || return 1
}

# Waits until the app's main activity is actually resumed.
#
# The condition, not a duration: "resumed" is what a flow's first command
# needs, and it is observable. A sleep is a guess that fails silently on a slow
# machine and wastes a minute on a fast one. Bounded so a genuinely wedged app
# cannot hang the suite — a timeout is reported and the flow runs anyway, which
# is the same position we were in before the wait existed, minus the hang.
APP_RESUME_TIMEOUT="${APP_RESUME_TIMEOUT:-20}"
wait_for_app_resumed() {
    local waited=0
    while (( waited < APP_RESUME_TIMEOUT )); do
        if adb -s "$SERIAL" shell dumpsys activity activities 2>/dev/null \
            | grep -q "topResumedActivity=.*${APP_ID}/"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    echo -e "${YELLOW}(app did not report resumed within ${APP_RESUME_TIMEOUT}s; running the flow anyway)${NC}" >&2
    return 1
}

# The decisive signal is Maestro's own report that it could not talk to the
# device, not our inference from adb: the render thread can die while adb still
# answers, and the window is long enough that a post-hoc probe gives the wrong
# answer.
#
# Two distinct device-side failures have been observed:
#   * the device disappears  -> "0 devices connected" / "Not enough devices"
#   * the device is alive but adbd/DADB stops serving -> RawAdbSocket / gRPC
#     UNAVAILABLE, or a closed socket, from deviceInfo
# Both cost the run; neither is a flow regression.
maestro_saw_device_failure() {
    grep -qiE "0 devices connected|Not enough devices|device '[^']*' not found|RawAdbSocket|AdbSocketFactory|AdbServer|Device server died|UNAVAILABLE|Connection refused" "$1"
}

# adbd can wedge while the emulator process survives. Resetting the host adb
# server is much cheaper than a relaunch and often enough on its own.
reset_adb_server() {
    adb kill-server >/dev/null 2>&1 || true
    sleep 2
    adb start-server >/dev/null 2>&1 || true
    sleep 2
}

run_one_flow() {
    local flow="$1" attempt=0 log
    log="$(mktemp)"
    while (( attempt <= MAX_RETRIES )); do
        set +e
        (cd "$REPO_ROOT" && maestro test "$flow") >"$log" 2>&1
        local status=$?
        set -e
        cat "$log"

        if [[ $status -eq 0 ]]; then
            rm -f "$log"
            return 0
        fi
        # A failure the device did not cause is a real failure; retrying it
        # would only hide a regression behind a flake.
        if ! maestro_saw_device_failure "$log" && device_alive; then
            rm -f "$log"
            return 1
        fi
        attempt=$((attempt + 1))
        if (( attempt > MAX_RETRIES )); then
            rm -f "$log"
            return 1
        fi
        echo -e "${YELLOW}Device unusable during $(basename "$flow"); recovering (attempt $((attempt+1))).${NC}" >&2
        reset_adb_server
        # Resetting adb is enough when the emulator survived; only relaunch when
        # it did not.
        if ! device_alive; then
            if ! SERIAL="$("$REPO_ROOT/scripts/ensure-emulator.sh" | tail -1)"; then
                rm -f "$log"
                return 1
            fi
        else
            SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
        fi
        adb -s "$SERIAL" shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1 || true
        if [[ "${SKIP_INSTALL:-0}" != "1" ]]; then
            (cd "$REPO_ROOT" && ./gradlew :androidApp:installDebug -Pandroid.device="$SERIAL" --quiet) || {
                rm -f "$log"; return 1; }
        else
            # The device just came back from a relaunch. SKIP_INSTALL=1 was
            # chosen when the device still held the APK we built; after a
            # relaunch that assumption is false, and a flow that "passes" here
            # may be proving nothing about the current code. Say so — a silent
            # stale binary turns "unknown" into "green".
            echo -e "${YELLOW}WARNING: device was relaunched and SKIP_INSTALL=1, so the binary on it is whatever the snapshot held — these results may not reflect the current build.${NC}" >&2
        fi
    done
    rm -f "$log"
    return 1
}

for flow_file in "${FLOW_FILES[@]}"; do
    flow_name="$(basename "$flow_file" .yaml)"
    echo -e "\n=== $flow_name ==="
    # Stop the app between flows. Flows share one app instance otherwise, and
    # whatever the previous one left behind — a modal bottom sheet, a snackbar,
    # a half-typed editor — is still on screen when the next one starts. The
    # failure then looks like the next flow's bug when it is really the
    # previous flow's residue: on 2026-10-04 ten `smoke` flows failed for
    # exactly this reason, and the one that left the sheet open was not
    # obviously the guilty one.
    #
    # `force-stop` rather than a data clear: clearing would destroy the seeded
    # profile/task a flow depends on, and flows that need a clean slate say so
    # themselves by running helpers/launch-clean.yaml.
    #
    # The app is then brought back up *without* clearing state, because
    # DebugSeedActivity resolves its Koin graph from the running process —
    # a deep link into a stopped app seeds nothing and the flow fails at the
    # next assertion for a reason that has nothing to do with the flow. This is
    # the same reasoning as helpers/relaunch.yaml, applied to every flow.
    #
    # `am start` on the main activity, then wait for it to be *resumed* — not
    # `monkey` and a fixed sleep. The first version of this used monkey, and the
    # suite went from 9/19 to 8/19: monkey returns as soon as it has dispatched
    # the intent, the flow's first command then runs against a cold process, and
    # `nav_tab_today` is missing for reasons that have nothing to do with it.
    # A bounded poll on the resumed activity is the condition that actually
    # matters, and it cannot pass vacuously the way a sleep can.
    if device_alive; then
        adb -s "$SERIAL" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
        sleep 1
        adb -s "$SERIAL" shell am start -n "$APP_ID/.MainActivity" >/dev/null 2>&1 || true
        wait_for_app_resumed
    fi
    if run_one_flow "$flow_file"; then
        PASSED+=("$flow_name")
    else
        if ! device_alive; then
            echo -e "${YELLOW}(device is gone after retries)${NC}"
        fi
        FAILED+=("$flow_name")
    fi
done

MAESTRO_STATUS=0
if [[ ${#PASSED[@]} -gt 0 ]]; then
    echo -e "\n${GREEN}Passed (${#PASSED[@]}):${NC} ${PASSED[*]}"
fi
if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo -e "${RED}Failed (${#FAILED[@]}):${NC} ${FAILED[*]}"
    MAESTRO_STATUS=1
fi

# ── 6. Crash triage ──────────────────────────────────────────────────────────
# Maestro reports assertion failures; it does not always surface a process death
# as one, so scan logcat independently before calling the run green. Only
# meaningful while a device is still attached — a lost emulator has no logcat.
if device_alive; then
    CRASHES="$(adb -s "$SERIAL" logcat -d 2>/dev/null \
        | grep -E "FATAL EXCEPTION|AndroidRuntime.*com\.singularity\.todo" \
        | head -20 || true)"

    if [[ -n "$CRASHES" ]]; then
        echo -e "${RED}=== App crashes detected in logcat ===${NC}"
        echo "$CRASHES"
        MAESTRO_STATUS=1
    fi
else
    echo -e "${RED}=== No device at the end of the run; crash triage skipped ===${NC}"
    MAESTRO_STATUS=1
fi

if [[ $MAESTRO_STATUS -eq 0 ]]; then
    echo -e "${GREEN}=== Maestro flows passed ===${NC}"
else
    echo -e "${RED}=== Maestro flows failed (exit $MAESTRO_STATUS) ===${NC}"
    echo -e "${YELLOW}Screenshots and view hierarchies: ~/.maestro/tests/${NC}"
fi

exit $MAESTRO_STATUS
