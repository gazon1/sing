#!/usr/bin/env bash
#
# Runs Maestro UI flows against a connected Android device or emulator.
#
# Device pre-flight (adb discovery, wake, unlock, install, logcat triage) is
# the same procedure documented in
# .agents/skills/singularity-todo-adb-workflow/SKILL.md — this script automates
# that skill rather than replacing it.
#
# Usage:
#   scripts/run-maestro.sh                          # every flow
#   TAGS=smoke scripts/run-maestro.sh               # only flows tagged "smoke"
#   FLOW=Maestro/flows/tasks scripts/run-maestro.sh # one directory
#   SERIAL=emulator-5554 scripts/run-maestro.sh    # pin the device
#   SKIP_INSTALL=1 scripts/run-maestro.sh          # reuse the installed APK
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
if [[ -z "$SERIAL" ]]; then
    SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "$SERIAL" ]]; then
    echo -e "${RED}No device in state 'device' found.${NC}" >&2
    adb devices >&2 || true
    echo -e "${YELLOW}Connect a device or start an emulator, or set SERIAL=...${NC}" >&2
    exit 1
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

MAESTRO_ARGS=(test)
if [[ -n "$TAGS" ]]; then
    MAESTRO_ARGS+=(--include-tags "$TAGS")
    echo "Filtering to flows tagged: $TAGS"
fi
MAESTRO_ARGS+=("${FLOW_FILES[@]}")

set +e
(cd "$REPO_ROOT" && maestro "${MAESTRO_ARGS[@]}")
MAESTRO_STATUS=$?
set -e

# ── 6. Crash triage ──────────────────────────────────────────────────────────
# Maestro reports assertion failures; it does not always surface a process death
# as one, so scan logcat independently before calling the run green.
CRASHES="$(adb -s "$SERIAL" logcat -d 2>/dev/null \
    | grep -E "FATAL EXCEPTION|AndroidRuntime.*com\.singularity\.todo" \
    | head -20 || true)"

echo ""
if [[ -n "$CRASHES" ]]; then
    echo -e "${RED}=== App crashes detected in logcat ===${NC}"
    echo "$CRASHES"
    if [[ $MAESTRO_STATUS -eq 0 ]]; then
        MAESTRO_STATUS=1
    fi
fi

if [[ $MAESTRO_STATUS -eq 0 ]]; then
    echo -e "${GREEN}=== Maestro flows passed ===${NC}"
else
    echo -e "${RED}=== Maestro flows failed (exit $MAESTRO_STATUS) ===${NC}"
    echo -e "${YELLOW}Screenshots and view hierarchies: ~/.maestro/tests/${NC}"
fi

exit $MAESTRO_STATUS
