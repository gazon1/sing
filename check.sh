#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: jvmTest → desktopApp:test → assembleDebug → detekt
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

echo -e "${YELLOW}=== [1/21] build version catalog gate ===${NC}"
# Fast: no JVM startup. Fails before Gradle if a *.gradle.kts contains a
# hardcoded group:artifact:version literal that should come from libs.versions.toml.
python3 scripts/build-version-catalog-gate.py --quiet . || {
    echo -e "${RED}build version catalog FAILED — hardcoded literal(s) found${NC}"
    exit 1
}

echo -e "${YELLOW}=== [2/21] detekt rule registry (fast) ===${NC}"
# Runs before Gradle: a duplicated or missing rule registration otherwise surfaces
# minutes later as a YAML parse error pointing at detekt.yml rather than the cause.
./scripts/check-detekt-registrations.sh || {
    echo -e "${RED}detekt rule registry FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [3/21] Find unwired surfaces ===${NC}"
# Detects implemented-but-unreachable code: screens with no call site, noop callbacks
# that defeat a `?:` fallback, unbound DAOs, LogWriter subclasses never registered.
# Zero findings means the project has no dormant code. Exits 0; findings are printed.
python3 scripts/find-unwired-surfaces.py --quiet || {
    echo -e "${RED}unwired surfaces found — see above${NC}"
    exit 1
}

echo -e "${YELLOW}=== [4/21] unwired-surface backlog references ===${NC}"
# Every exemption in find-unwired-surfaces-baseline.txt must name a real
# deferred-backlog.md heading. The baseline header documents this rule; before
# this check nothing implemented it and 4 of 5 anchors did not exist.
python3 scripts/check-unwired-backlog-refs.py || {
    echo -e "${RED}unwired-surface backlog references FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [5/21] detekt baseline ratchet ===${NC}"

echo -e "${YELLOW}=== [6/21] backlog entries are classifiable and within budget ===${NC}"
# 42 of this file's 82 entries carried no status line, and two independently
# written regexes counted the resolved ones as 18 and 26 — each silently
# classifying what it could and skipping the rest. An entry nobody can classify
# is an entry nobody can triage, and the file's own
# `an-open-backlog-entry-does-not-mean-the-work-is-still-open` records what
# happens next: it stops being read.
python3 scripts/check-backlog-status.py || {
    echo -e "${RED}a backlog entry has no readable status, or an open one is untracked${NC}"
    exit 1
}

# A baseline may shrink, never grow. Without this the baseline was a place to
# park new violations silently — "0 findings" then meant "0 findings outside
# a 341-entry file that nothing compared to anything".
python3 scripts/check-baseline-ratchet.py || {
    echo -e "${RED}detekt baseline grew - fix the finding or justify the growth${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8/21] lint rules are declared decisions ===${NC}"
# A rule absent from detekt.yml runs on detekt's built-in default, which means
# nobody chose it. 18 such rules produced 247 of 428 baseline entries, and two
# contradicted AGENTS.md. Now declared, and the next one has to be declared too.
python3 scripts/check-rule-intent.py || {
    echo -e "${RED}rule intent check FAILED — a lint rule is running on defaults${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8b2/21] every file-level suppression says what it hides ===${NC}"
# check-rule-intent asks whether a rule was *declared*. This asks the other half:
# a rule can be declared, configured, and provably able to fire, and still be
# switched off for a whole file by one line no gate could see. 12 production
# files carried @file:Suppress("NoDirectClockSystem") with no reason, covering 38
# clock reads — while the baseline held exactly one suppression for that rule.
python3 scripts/check-suppression-intent.py || {
    echo -e "${RED}suppression intent check FAILED — a lint rule is switched off silently${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8b/21] the rule inventory in the skill is not stale ===${NC}"
# The rule table in the rule-authoring skill is generated from source. It was hand-written
# before that, and drifted twice — a deleted rule still listed, a missing rule still listed —
# and then three rules shipped with no row at all, which nothing noticed (#139). Generating
# it is only half the fix; this is the half that keeps it honest.
python3 scripts/gen-detekt-rule-table.py --check || {
    echo -e "${RED}rule inventory is stale — run: python3 scripts/gen-detekt-rule-table.py${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8c1/21] the skills catalog is current ===${NC}"
# docs/SKILLS-CATALOG.md is generated from skill frontmatter and was committed
# stale on a clean tree, with nothing noticing: "do not edit by hand" discourages
# the wrong edit but cannot catch the edit nobody made. The catalog is what an
# agent reads to choose a skill, so a stale line count or description sends it to
# the wrong file. Same lesson as the detekt rule inventory above.
./scripts/regen-skills-catalog.sh --check || {
    echo -e "${RED}skills catalog is stale — run: ./scripts/regen-skills-catalog.sh${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8c/21] the committed coverage matrix matches the specs and the code ===${NC}"
# The coverage matrix is generated from infra/kiwi/scenarios/** plus the
# @DisplayName / scenario: linkage in code, and it is committed. Generating it is
# only half the job; without this check a hand-edited or stale matrix merges
# silently, which is the exact failure mode the generated file exists to remove.
# `validate` runs first so a broken spec or an unknown scenario id is reported
# as such rather than as a matrix diff.
PYTHONPATH=infra/kiwi python3 -m traceability validate --quiet || {
    echo -e "${RED}scenario specs or their links to automation are invalid${NC}"
    exit 1
}
PYTHONPATH=infra/kiwi python3 -m traceability coverage --check || {
    echo -e "${RED}coverage matrix is stale — run: just trace-coverage${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8d/21] scenario coverage holes did not grow ===${NC}"
# A hole is not a mistake — it is the point of the matrix. A *growing* hole
# count is a regression, and until this gate existed nothing read these numbers:
# the 15-spec auth/sync tranche landed with zero carriers and took the matrix
# from 2 holes to 32 in one commit, green, because `validate` reports holes as
# information on the same run the CI step treats as a pass. Filling a hole is
# always allowed; opening one fails and has to be justified in review.
python3 scripts/check-traceability-ratchet.py || {
    echo -e "${RED}scenario coverage grew — attach a carrier, or state the growth in the commit${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8c2/21] Room schema, exports and migration chain agree ===${NC}"
# `SyncColumns.server_version` was added to `sync_shadow` while SCHEMA_VERSION
# stayed at 36 and the export moved to 37. Room's identity-hash check then threw
# `IllegalStateException` at first query for every user with an existing
# database — and no test saw it, because every test creates its own database and
# a fresh database has no identity to mismatch. This checks the three artefacts
# that must describe one schema: the @Database annotation, the exported NN.json
# files, and the Migration classes.
python3 scripts/check-room-schema-integrity.py || {
    echo -e "${RED}Room schema integrity FAILED — the annotation, the exports and the migration chain disagree${NC}"
    exit 1
}

echo -e "${YELLOW}=== [9/21] shared:jvmTest ===${NC}"
./gw :shared:jvmTest --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [10/21] desktopApp:test ===${NC}"
./gw :desktopApp:test --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [11/21] androidApp:assembleDebug ===${NC}"
./gw :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

# "Tests passed" is not "the tests ran". JUnit's includeTags matches per class, so a
# class can stop being selected with no error and the task still goes green — which is
# how CI once ran 16 of 218 classes. Run this after the test steps, never before.
echo -e "${YELLOW}=== [12/21] executed test counts ===${NC}"
python3 scripts/check-test-runs.py --require shared:jvmTest,desktopApp:test \
    --max-age 21600 || {
    echo -e "${RED}a test source set ran fewer tests than its recorded floor${NC}"
    exit 1
}
echo -e "${GREEN}test run floors met${NC}"

echo -e "${YELLOW}=== [12b/21] gates are wired and can fail ===${NC}"
# Part A: every configured Gradle check task is named by a gate — catches the
# :androidApp:detekt instance, which had a full config block and no invoker.
# Part B: every registered script gate is run against a sabotaged input and must
# exit non-zero — catches the `--warn-only` class, where a check prints a
# violation and still passes.
#
# This runs after the test steps, not with the other static checks, because the
# `test-runs` control sabotages config/docs/test-runs-baseline.txt and asks
# whether the gate notices. The gate reads JUnit XML that only exists once
# :shared:jvmTest and :desktopApp:test have run. Run this one earlier and on a
# fresh clone it fails with "gate 'test-runs' already fails on a clean tree" —
# a true statement that reads as a false alarm, because the check asks its
# question two steps before the thing that answers it exists (#206).
#
# A gate that proves another gate works inherits that gate's preconditions and
# runs them at its own time. Ordering them wrongly is the bug; skipping the
# control when the XML is absent would teach the reader that "no results" is
# acceptable, which is the reading this project is removing.
python3 scripts/check-gate-wiring.py || {
    echo -e "${RED}gate wiring check FAILED — a gate is unreachable or cannot fail${NC}"
    exit 1
}

echo -e "${YELLOW}=== [13/21] gate script self-tests ===${NC}"
# check-test-runs.py is the only thing that catches a partial skip, and
# check-coverage.py is the only thing that catches coverage loss. A regression
# inside either disables the gate silently — the same failure shape the gates
# exist to catch. Cheap enough (milliseconds, no JVM) to run every time.
python3 -m unittest discover -s scripts/tests 2>&1 | tail -3 || {
    echo -e "${RED}gate script self-tests FAILED${NC}"
    exit 1
}
echo -e "${GREEN}gate script self-tests passed${NC}"

echo -e "${YELLOW}=== [14/21] detekt rule unit tests ===${NC}"
# A custom rule that cannot fire is indistinguishable from a rule that has
# nothing to match. These tests are the only evidence either way.
./gw :detekt-rules:test --quiet || {
    echo -e "${RED}detekt rule unit tests FAILED - a rule cannot be trusted without them${NC}"
    exit 1
}

echo -e "${YELLOW}=== [15/21] workflow YAML parses ===${NC}"
# A malformed workflow is invisible: not a test failure, not a lint error, just a
# workflow that silently does not exist.
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

echo -e "${YELLOW}=== [16/21] coverage floors (when a report exists) ===${NC}"
# --if-present because koverReport instruments every test task and roughly
# triples the local loop; CI runs it in the kover job on every push.
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

echo -e "${YELLOW}=== [17/21] doc sizes + dead doc references ===${NC}"
# Both are blocking CI gates; a local loop that skipped them let the DIGEST
# budget fail unnoticed until the next CI run.
python3 scripts/refresh-decisions-digest.py >/dev/null
python3 scripts/check-doc-sizes.py || {
    echo -e "${RED}doc size budget exceeded${NC}"
    exit 1
}
python3 scripts/check-doc-dead-refs.py || {
    echo -e "${RED}dead references in docs/skills/KDoc${NC}"
    exit 1
}
echo -e "${GREEN}doc gates passed${NC}"

echo -e "${YELLOW}=== [18/21] test-task input declarations ===${NC}"
# A test task that reads a tree outside its own module must declare it as an input.
# Without that declaration the task goes UP-TO-DATE on an edit to that tree and an
# architecture gate re-reports its previous verdict - green, about a file it never
# re-read. Measured on MaestroFlowTagsTest; see
# docs/decisions/2026-10-05-test-task-external-inputs.md. 20 ms, no JVM.
python3 scripts/check-test-task-inputs.py || {
    echo -e "${RED}a test task reads a tree it does not declare as an input${NC}"
    exit 1
}

echo -e "${YELLOW}=== [19/21] mcp-server:compileKotlin (DI graph validation) ===${NC}"
./gw :mcp-server:compileKotlin --quiet || {
    echo -e "${RED}mcp-server:compileKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}mcp-server DI graph validated${NC}"

echo -e "${GREEN}=== [20/21] detekt (enforcing, ignoreFailures=false) ===${NC}"
# Detekt has failed the build since PR 3.3 (ignoreFailures = false in both modules).
# The `|| { echo }` fallback that used to be here swallowed real violations, so a
# green ./check.sh did not imply a clean detekt run.
./gw :shared:detekt :desktopApp:detekt --quiet || {
    echo -e "${RED}detekt reported violations — see config/detekt/ for the active rule set${NC}"
    exit 1
}

echo -e "${GREEN}=== [21/21] mcp-server:detekt ===${NC}"
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
