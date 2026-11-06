#!/usr/bin/env bash
# check.sh — the local verification loop.
#
# The steps are declared in `hk.pkl`, in the `check` hook: this file no longer
# says WHAT to check. It maps a lane to a profile, runs the loop, and reports
# what the loop cost.
#
# The same declaration is what CI can run with `hk check --all`
# (https://hk.jdx.dev/ci.html). See
# docs/decisions/2026-11-05-check-sh-is-an-hk-pipeline.md.
#
# The steps that need a device are not here: they are the `device` hook, run
# with `hk run device`. Their failure is not this loop's verdict, and CI does
# not run them.
#
# Usage: ./check.sh               # the whole loop
#        ./check.sh --fast        # the fast lane: only the checks this change can break
#        ./check.sh --affected    # the full loop over the tests this change can break
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
# the wall time of every step it runs and writes that record to the file
# $HK_TIMING_JSON names — after the hook, whether it passed or failed, so a
# step that broke the build still costs the time it took. The cost report
# reads that record as it is; nothing here translates it.
#
# The fast lane measures itself into its own file. The full loop's record is
# what the cost report publishes, and a fast run must not overwrite the
# measurement of the loop it is not.
COST_DIR="$ROOT/build/diagnostics"
if [[ "$LANE" == "fast" ]]; then
    COST_FILE="$COST_DIR/fast-lane-steps.json"
else
    COST_FILE="$COST_DIR/cycle-steps.json"
fi
mkdir -p "$COST_DIR"
rm -f "$COST_FILE"
export HK_TIMING_JSON="$COST_FILE"

echo -e "${YELLOW}=== check.sh [$LANE lane] — the steps are declared in hk.pkl ===${NC}"

hk check --all --profile "$LANE" || {
    echo -e "${RED}the ${LANE} lane FAILED — see the step named above${NC}"
    exit 1
}

echo ""
echo -e "${YELLOW}=== what the loop cost ===${NC}"
# The report, not a verdict: it names what got slower than the recorded
# baseline and exits zero either way. `./sg`, not `sgate`: the console script
# belongs to whichever checkout was installed last.
./sg gate-costs

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
