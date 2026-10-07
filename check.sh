#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: static gates → jvmTest → desktopApp:test → assembleDebug → detekt
# Optionally runs Android instrumentation on adb device if SKIP_ADB=0
# Usage: SKIP_ADB=1 ./check.sh   # skip adb tests
#
# NOTE: no --no-daemon here on purpose. A warm Gradle+Kotlin daemon keeps
# incremental compilation across runs; --no-daemon forces a cold JVM every
# time. CI invokes gradle directly and manages its own lifecycle.
#
# Gradle is invoked through ./gw, which gives this worktree a private
# GRADLE_USER_HOME (its own daemon registry) while keeping the module cache and
# the toolchain shared. Without it, a parallel worktree's `./gradlew --stop`
# would kill the daemon this script depends on. ./gw is a pass-through when
# GRADLE_USER_HOME is already set, which is the case in CI.
# See docs/decisions/2026-10-04-gradle-daemon-isolation.md

set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# Freshness window for the test-run gate below. It is a window, not a run stamp,
# because an UP-TO-DATE test task does not rewrite its results directory — a strict
# "produced by this run" check would fail the second consecutive check.sh for reusing
# results that are still correct. CI uses the strict form, where every test task runs.
RESULT_MAX_AGE=21600

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

TOTAL=13

echo -e "${YELLOW}=== [1/$TOTAL] static gates (the shared registry) ===${NC}"
# Every script gate that needs no JVM and no build output lives in
# scripts/ci/static-gates.sh, which the `static` job of ci.yml also runs.
#
# Delegating here is the point of that file, not a convenience. The two failures
# this repository had were a gate that lived only in ci.yml and a gate that lived
# only in check.sh — each real, each green, each invisible to the other surface.
# One registry, two callers, is the only arrangement where that class cannot recur.
bash scripts/ci/static-gates.sh || {
    echo -e "${RED}a static gate failed — see scripts/ci/static-gates.sh${NC}"
    exit 1
}

# Deliberately NOT in the registry: backlog bookkeeping changes with every commit,
# and a PR must not be blocked by how the work queue is annotated. Declared
# `local` in scripts/check-gate-wiring.py's GATE_PARITY, so the asymmetry is
# visible rather than silent.
echo -e "${YELLOW}=== [2/$TOTAL] backlog entries are classifiable and within budget ===${NC}"
# 42 of this file's entries carried no status line, and two independently
# written regexes counted the resolved ones as 18 and 26 — each silently
# classifying what it could and skipping the rest. An entry nobody can classify
# is an entry nobody can triage, and the file's own
# `an-open-backlog-entry-does-not-mean-the-work-is-still-open` records what
# happens next: it stops being read.
python3 scripts/check-backlog-status.py || {
    echo -e "${RED}a backlog entry has no readable status, or an open one is untracked${NC}"
    exit 1
}

echo -e "${YELLOW}=== [3/$TOTAL] shared:jvmTest ===${NC}"
./gw :shared:jvmTest --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [4/$TOTAL] desktopApp:test ===${NC}"
./gw :desktopApp:test --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [5/$TOTAL] androidApp:assembleDebug ===${NC}"
./gw :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

# "Tests passed" is not "the tests ran". JUnit's includeTags matches per class, so a
# class can stop being selected with no error and the task still goes green — which is
# how CI once ran 16 of 218 classes. Run this after the test steps, never before.
echo -e "${YELLOW}=== [6/$TOTAL] executed test counts ===${NC}"
python3 scripts/check-test-runs.py --require shared:jvmTest,desktopApp:test \
    --max-age 21600 || {
    echo -e "${RED}a test source set ran fewer tests than its recorded floor${NC}"
    exit 1
}
echo -e "${GREEN}test run floors met${NC}"

# NOT in the registry either, and for a related reason. Part A asks whether every
# configured Gradle check task is named by a gate. Part B proves each registered
# script gate can fail, and the `test-runs` control does that by sabotaging
# config/docs/test-runs-baseline.txt and re-running the gate — which reads JUnit XML
# that only exists once steps 3-5 have run. A registry is static by definition; in
# CI's `static` job there is no build output at all. Calling it from there
# reproduces #206 exactly: "already fails on a clean tree", a true statement that
# reads as a false alarm.
#
# A gate that proves another gate works inherits that gate's preconditions and runs
# them at its own time. Ordering them wrongly is the bug; skipping the control when
# the XML is absent would teach the reader that "no results" is acceptable, which is
# the reading this project is removing.
echo -e "${YELLOW}=== [7/$TOTAL] gates are wired and can fail ===${NC}"
python3 scripts/check-gate-wiring.py || {
    echo -e "${RED}gate wiring check FAILED — a gate is unreachable or cannot fail${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8/$TOTAL] detekt rule unit tests ===${NC}"
# A custom rule that cannot fire is indistinguishable from a rule that has
# nothing to match. These tests are the only evidence either way.
./gw :detekt-rules:test --quiet || {
    echo -e "${RED}detekt rule unit tests FAILED - a rule cannot be trusted without them${NC}"
    exit 1
}

echo -e "${YELLOW}=== [9/$TOTAL] workflow YAML parses ===${NC}"
# A malformed workflow is invisible: not a test failure, not a lint error, just a
# workflow that silently does not exist. `docs-audit.yml` once was exactly that.
python3 -c "
import sys, yaml, glob
bad = []
for f in sorted(glob.glob('.github/workflows/*.yml')):
    try:
        yaml.safe_load(open(f, encoding='utf-8'))
    except Exception as e:
        bad.append(f'{f}: {e}')
if bad:
    print(chr(10).join(bad))
    sys.exit(1)
print('all workflow files parse')
" || {
    echo -e "${RED}workflow YAML is invalid - that workflow cannot run${NC}"
    exit 1
}

echo -e "${YELLOW}=== [10/$TOTAL] coverage floors (when a report exists) ===${NC}"
# --if-present because koverReport instruments every test task and roughly
# triples the local loop; CI runs it in the same job as the tests on every push.
#
# Deliberately NOT --since: this report is produced by a separate, earlier task, so the
# run-start stamp would make it permanently "stale" and skip the check in silence. Its
# age is printed instead, and CI — where the report is generated in the same job —
# enforces freshness with --since.
if [ -f build/reports/kover/report.xml ]; then
    REPORT_AGE=$(( $(date +%s) - $(stat -c %Y build/reports/kover/report.xml) ))
    echo "    report age: $((REPORT_AGE / 60)) min (re-run ./gw koverReport for a current figure)"
    python3 scripts/check-coverage.py || {
        echo -e "${RED}coverage below the recorded floor${NC}"
        exit 1
    }
else
    echo "    no Kover report — run ./gw koverReport (skipped)"
fi

echo -e "${YELLOW}=== [11/$TOTAL] mcp-server:compileKotlin (DI graph validation) ===${NC}"
./gw :mcp-server:compileKotlin --quiet || {
    echo -e "${RED}mcp-server:compileKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}mcp-server DI graph validated${NC}"

echo -e "${GREEN}=== [12/$TOTAL] detekt (enforcing, ignoreFailures=false) ===${NC}"
# Detekt has failed the build since PR 3.3 (ignoreFailures = false in both modules).
# The `|| { echo }` fallback that used to be here swallowed real violations, so a
# green ./check.sh did not imply a clean detekt run.
./gw :shared:detekt :desktopApp:detekt --quiet || {
    echo -e "${RED}detekt reported violations — see config/detekt/ for the active rule set${NC}"
    exit 1
}

echo -e "${GREEN}=== [13/$TOTAL] mcp-server:detekt ===${NC}"
# This task was invoked by ci.yml with `ignoreFailures = true`, so it could not
# fail there, and it was not in check.sh at all, so it did not run here either.
# Both halves mattered: nothing enforced it and nothing ran it.
./gw :mcp-server:detekt --quiet || {
    echo -e "${RED}mcp-server:detekt reported violations${NC}"
    exit 1
}

# Optional: Android instrumentation tests on real adb device
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
    fi
fi

# Optional: Maestro UI flows. Opt-in (RUN_MAESTRO=1) rather than default —
# these need a booted device, and the emulator cold-start path is not yet
# reliable enough to gate a local check on. See
# docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md.
if [[ "${RUN_MAESTRO:-0}" == "1" ]]; then
    echo ""
    echo -e "${YELLOW}=== [opt] Maestro UI flows ===${NC}"
    if command -v maestro >/dev/null 2>&1; then
        # SKIP_INSTALL is the caller's decision, and it defaults to installing.
        # Hardcoding SKIP_INSTALL=1 here looked free and was not: when the
        # emulator dies mid-run, run-maestro.sh relaunches it from an AVD
        # snapshot, and the reinstall that would follow is exactly what
        # SKIP_INSTALL=1 suppresses. The flows that then "pass" are running
        # whatever binary the snapshot happened to hold — which is how a gate
        # ends up green against a build that does not contain the fix it is
        # supposed to be verifying. Set SKIP_INSTALL=1 only when you know the
        # device is intact and the APK on it is the one you just built.
        SERIAL="${SERIAL:-}" TAGS="${MAESTRO_TAGS:-smoke}" SKIP_INSTALL="${SKIP_INSTALL:-0}" \
            bash scripts/run-maestro.sh || {
            echo -e "${RED}Maestro flows FAILED${NC}"
            exit 1
        }
    else
        echo -e "${YELLOW}maestro CLI not on PATH — skipping${NC}"
    fi
fi

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"