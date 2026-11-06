#!/usr/bin/env bash
# check.sh — the local verification loop.
#
# The steps are declared in `hk.pkl`, in the `check` hook: this file no longer
# says WHAT to check. It maps a lane to a profile, hands hk's timing record to
# the cost report, and runs the steps that need a device.
#
# The same declaration is what CI can run with `hk check --all`
# (https://hk.jdx.dev/ci.html). See
# docs/decisions/2026-11-05-check-sh-is-an-hk-pipeline.md.
#
# Usage: SKIP_ADB=1 ./check.sh   # skip adb tests
#        ./check.sh --fast      # the fast lane: only the checks this change can break
#        ./check.sh --affected  # the full loop over the tests this change can break
#
# NOTE: no --no-daemon here on purpose. A warm Gradle+Kotlin daemon keeps
# incremental compilation across runs; --no-daemon forces a cold JVM every time.
#
# Gradle is invoked through ./gw, which gives this worktree a private
# GRADLE_USER_HOME (its own daemon registry) while keeping the module cache and
# the toolchain shared. See docs/decisions/2026-10-04-gradle-daemon-isolation.md

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Toolchain: install the external binaries mise pins (actionlint, shellcheck, ...).
# Optional: a checkout without mise keeps using whatever is already on PATH.
if command -v mise >/dev/null 2>&1; then
    mise install --quiet || true
fi

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# ── The lanes ─────────────────────────────────────────────────────────────────
#
# Which steps belong to which lane is declared in `hk.pkl` with profiles. This
# file picks one and passes it; it does not name a step.
LANE=full
for arg in "$@"; do
    case "$arg" in
        --affected) LANE=affected ;;
        --fast) LANE=fast ;;
        *)
            echo "unknown option: $arg" >&2
            echo "usage: ./check.sh [--affected] [--fast]" >&2
            exit 64
            ;;
    esac
done

# ── What this loop costs ──────────────────────────────────────────────────────
#
# The cost of the local loop is the sum of decisions nobody priced. hk records
# the wall time of every step it runs; the cost report reads `name<TAB>ms`, so
# this file converts once, on every exit path: a step that broke the build
# still cost the time it took.
#
# The fast lane measures itself into its own file. The full loop's record is
# what the cost report publishes, and a fast run must not overwrite the
# measurement of the loop it is not.
COST_DIR="$ROOT/build/diagnostics"
if [[ "$LANE" == "fast" ]]; then
    COST_FILE="$COST_DIR/fast-lane-steps.txt"
else
    COST_FILE="$COST_DIR/cycle-steps.txt"
fi
TIMING_FILE="$COST_DIR/hk-timing.json"
mkdir -p "$COST_DIR"
: >"$COST_FILE"
rm -f "$TIMING_FILE"

record_costs() {
    [ -f "$TIMING_FILE" ] || return 0
    python3 - "$TIMING_FILE" "$COST_FILE" <<'PY'
import json
import pathlib
import sys

timing, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
steps = json.loads(timing.read_text(encoding='utf-8')).get('steps', {})
out.write_text(
    ''.join(f'{name}\t{entry["wall_time_ms"]}\n' for name, entry in steps.items()),
    encoding='utf-8',
)
PY
}
trap record_costs EXIT

echo -e "${YELLOW}=== check.sh [$LANE lane] — the steps are declared in hk.pkl ===${NC}"

HK_TIMING_JSON="$TIMING_FILE" hk check --all --profile "$LANE" || {
    echo -e "${RED}the ${LANE} lane FAILED — see the step named above${NC}"
    exit 1
}

# ── Optional: Android instrumentation on a real device ────────────────────────
#
# The instrumentation itself is optional here — its failure is not this
# script's verdict — but the floor of the instrumented tier is checked where the
# report is: a run that does not match what the tier is recorded to deliver is a
# finding, and the report is the only place that fact exists.
if [[ "${SKIP_ADB:-0}" != "1" ]] && adb devices | grep -q "device$"; then
    echo ""
    echo -e "${YELLOW}=== [opt] Android instrumentation on adb ===${NC}"
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [[ -n "$SERIAL" ]]; then
        echo "Running on device: $SERIAL"
        ./gw :androidApp:connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.device="$SERIAL" \
            --quiet || true
        echo -e "${GREEN}Android instrumentation completed${NC}"
        if [[ -d androidApp/build/outputs/androidTest-results/connected ]]; then
            python3 scripts/check-instrumented-test-floor.py || {
                echo -e "${RED}the instrumented run and its floor disagree — see the gate's output above${NC}"
                exit 1
            }
            echo -e "${GREEN}the instrumented run matches its floor${NC}"
        fi
    fi
fi

# ── Optional: Maestro UI flows ────────────────────────────────────────────────
#
# Opt-in (RUN_MAESTRO=1) rather than default: these need a booted device, and
# the emulator cold-start path is not reliable enough to gate a local check on.
if [[ "${RUN_MAESTRO:-0}" == "1" ]]; then
    echo ""
    echo -e "${YELLOW}=== [opt] Maestro UI flows ===${NC}"
    if command -v maestro >/dev/null 2>&1; then
        # `./rm`, not `run-maestro`: the console script is an editable install
        # and belongs to whichever checkout was installed last. SKIP_INSTALL is
        # the caller's decision: it defaults to installing, and suppressing that
        # can green a run against a binary the device already held.
        SERIAL="${SERIAL:-}" TAGS="${MAESTRO_TAGS:-smoke}" SKIP_INSTALL="${SKIP_INSTALL:-0}" \
            ./rm || {
            echo -e "${RED}Maestro flows FAILED${NC}"
            exit 1
        }
    else
        echo -e "${YELLOW}maestro CLI not on PATH — skipping${NC}"
    fi
fi

echo ""
echo -e "${YELLOW}=== what the loop cost ===${NC}"
# The report, not a verdict: it names what got slower than the recorded
# baseline and exits zero either way. `./sg`, not `sgate`: the console script
# belongs to whichever checkout was installed last.
./sg gate-costs

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
