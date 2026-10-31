#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: static gates → androidApp:compileDebugKotlin → jvmTest → desktopApp:test
#      → assembleDebug → detekt
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

# ── The affected lane ─────────────────────────────────────────────────────────
#
# `--affected` runs the tests this change can break and the detekt of the files
# it touches, instead of the whole suite and the whole module sweep. It is a
# LANE, not a smaller loop: every gate still runs, and the full loop is still
# what CI runs and what `just tests::check` runs.
#
# The two tools it uses are the ones CI's "Affected tests" job uses — one
# answer to "what does this change require?", asked once in one place.
AFFECTED=0
for arg in "$@"; do
    case "$arg" in
        --affected) AFFECTED=1 ;;
        *)
            echo "unknown option: $arg" >&2
            echo "usage: ./check.sh [--affected]" >&2
            exit 64
            ;;
    esac
done

# Freshness window for the test-run gate below. It is a window, not a run stamp,
# because an UP-TO-DATE test task does not rewrite its results directory — a strict
# "produced by this run" check would fail the second consecutive check.sh for reusing
# results that are still correct. CI uses the strict form, where every test task runs.
RESULT_MAX_AGE=21600

# ── What this loop costs ───────────────────────────────────────────────────────
# The cost of the local loop is the sum of decisions nobody priced: a wave added an
# Android host test, another added a module to the detekt sweep, and each of those
# is minutes on a machine that was already slow. Nobody could say so, because
# nothing measured it.
#
# So every step below is timed, and the numbers are handed to `sgate gate-costs`,
# which publishes them (docs/testing/gate-costs.md) and compares them with the
# recorded baseline. Nothing is run twice to be timed: these are the runs that
# happened anyway.
COST_DIR="$ROOT/build/diagnostics"
COST_FILE="$COST_DIR/cycle-steps.txt"
mkdir -p "$COST_DIR"
: >"$COST_FILE"

# Milliseconds since the epoch. `date +%s%3N` is GNU-only and bash's SECONDS is
# whole seconds, which is too coarse for a gate that takes ten of them.
now_ms() {
    python3 -c 'import time; print(int(time.time() * 1000))'
}

# timed <name> <command...> — run the command, record how long it took, and hand
# back its exit code. The record is written even when the command failed: a step
# that broke the build still cost the time it took.
timed() {
    local name=$1
    shift
    local start end rc
    start=$(now_ms)
    set +e
    "$@"
    rc=$?
    set -e
    end=$(now_ms)
    printf '%s\t%s\n' "$name" "$((end - start))" >>"$COST_FILE"
    return $rc
}

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# The numbered steps are 1..11; the lettered ones (2b, 2c, 3a, 3b, 4b, 7b, 7c, 8a, 8b, 11b, 11c)
# are extra checks that hang off a numbered step and are not counted here.
TOTAL=11

# The coverage agent multiplies every test class and nothing in this loop reads the
# report it produces, so every test step switches it off. The floor is measured and
# judged where the measurement is taken: the coverage steps of CI's `tests` job, and
# `just ci::coverage` here. See
# docs/decisions/2026-10-28-the-local-loop-measures-nothing-it-does-not-use.md
COVERAGE_OFF="-Pcoverage.enabled=false"

echo -e "${YELLOW}=== [1/$TOTAL] static gates (the shared registry) ===${NC}"
# Every script gate that needs no JVM and no build output lives in
# scripts/ci/static-gates.sh, which the `static` job of ci.yml also runs.
# sgate run-all reads that registry and dispatches each gate to its native
# command (sgate <name>), inline shell, or inline Python — running all gates
# in the registry in parallel. One registry, one interface, is the only
# arrangement where a gate cannot silently vanish between surfaces.
#
# The preflight comes first, and it is not ceremony: a gate that cannot run
# looks exactly like a gate that has nothing to report. A missing `sgate`
# install used to surface as the gate scripts' own unit tests failing with
# ModuleNotFoundError, which reads like a broken test rather than a missing
# install. The preflight names the tool and the command that installs it.
bash scripts/ci/preflight-gate-tools.sh || {
    echo -e "${RED}the gate tooling is not installed — see the message above${NC}"
    exit 1
}

timed "static gates" sgate run-all || {
    echo -e "${RED}a static gate failed — see sgate run-all output above${NC}"
    exit 1
}

# Deliberately NOT in the registry: backlog bookkeeping changes with every commit,
# and a PR must not be blocked by how the work queue is annotated. Declared
# `local` in scripts/check-gate-wiring.py's GATE_PARITY, so the asymmetry is
# visible rather than silent.
echo -e "${YELLOW}=== [2/$TOTAL] androidApp:compileDebugKotlin ===${NC}"
# Cheapest thing that compiles `androidMain`, run early and unconditionally.
#
# `:androidApp:assembleDebug` below also compiles androidMain, but it links and
# packages a 44 MB APK. Under pressure that is the step that gets dropped, and
# androidMain then has no compiler in the loop at all — which is how it collected
# two compile errors in three days (739e7c05, then 276a70e3), neither noticed
# until somebody ran the expensive gate by hand. This task compiles without
# packaging, so skipping it saves minutes rather than a minute, and it runs before
# the long test steps so a broken androidMain costs seconds instead of ten minutes.
# See docs/decisions/2026-10-07-android-maintenance-needs-a-ci-gate.md
#
# `compileDebugAndroidTestKotlin` comes along for the ride: the instrumented
# source set is compiled by nothing else in the pull-request pipeline either,
# and a broken instrumented test is otherwise discovered by the next nightly —
# days later, and blamed on the nightly. CI names the same task
# (`.github/workflows/ci.yml`, the free leg of the Android matrix), and
# `scripts/check-gate-wiring.py`'s Gradle-task parity check is what keeps the
# two surfaces from drifting apart.
timed "androidApp:compileDebugKotlin" ./gw :androidApp:compileDebugKotlin :androidApp:compileDebugAndroidTestKotlin --quiet || {
    echo -e "${RED}androidApp:compileDebugKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}androidMain compiled${NC}"

# The tool catalogue is a generated file, and the JVM test that checks it lives in a
# twenty-minute suite. This step is the fast half: it exports the catalogue to a
# scratch file and compares, so a tool whose schema or description changed shape is
# found here rather than in CI. It runs before the long test step because it costs
# seconds and its failure has a one-line fix.
echo -e "${YELLOW}=== [2b/$TOTAL] shared:verifyToolSurface ===${NC}"
timed "shared:verifyToolSurface" ./gw :shared:verifyToolSurface --quiet || {
    echo -e "${RED}the committed tool catalogue is not what the code exports${NC}"
    exit 1
}
echo -e "${GREEN}the tool catalogue is current${NC}"

# The derived field lists are a generated file too, and the seed and the gate
# read them — so a model field added without re-exporting would leave the seed
# and the export agreeing with each other and with nothing. This step is the
# fast half: it asks the exporter whether the committed file says what the
# models declare — the same question the JVM test asks, through the same
# function.
echo -e "${YELLOW}=== [2c/$TOTAL] shared:verifySyncFieldAllowlist ===${NC}"
timed "shared:verifySyncFieldAllowlist" ./gw :shared:verifySyncFieldAllowlist --quiet || {
    echo -e "${RED}the committed field lists are not what the models declare${NC}"
    exit 1
}
echo -e "${GREEN}the derived field lists are current${NC}"

if (( AFFECTED )); then
    echo -e "${YELLOW}=== [3/$TOTAL] affected tests ===${NC}"
else
    echo -e "${YELLOW}=== [3/$TOTAL] shared:jvmTest ===${NC}"
fi
# `-Pcoverage.enabled=false` — the local loop takes no coverage measurement, so it
# does not attach the agent that produces one. Measured 2026-10-28 on this tree:
# 1241 s with the agent, 771 s without it, for the same tests. The agent is a
# multiplier on every class, and nothing here reads the report it produces;
# producing it is `koverReport`'s job and this script does not run it.
if (( AFFECTED )); then
    # The affected lane: the classes this change can break, and nothing else.
    #
    # The map is the one CI's "Affected tests" job asks, and it names a
    # POPULATION rather than a tag list, so the command cannot select a
    # population the repository has not declared. An empty selection is an
    # ANSWER — "this change claims no test class" — and the map reports it on
    # stdout, where this step reads it.
    #
    # THREE answers, and each of them says what ran. The line "shared:jvmTest
    # passed" used to stand AFTER this branch, so it was printed whatever the
    # branch did: a lane that ran four thousand tests and a lane that ran
    # NOTHING read exactly the same. The line a reader trusts has to name the
    # tests that ran.
    echo -e "${YELLOW}    (affected lane: only the tests this change can break)${NC}"
    selection="$(python3 scripts/test-impact-map.py --command --population fast --gradle ./gw)" || {
        echo -e "${RED}the affected test selection could not be computed — see the message above${NC}"
        exit 1
    }
    if grep -q '^test-impact-map: the change is already contained in the base' <<<"$selection"; then
        echo -e "${GREEN}the affected lane: the change is already in the base — there is nothing to compare${NC}"
    elif grep -q '^test-impact-map: the change selects no test class$' <<<"$selection"; then
        echo -e "${GREEN}the affected lane: the change claims no test class — there is nothing to run, and that is an answer${NC}"
    else
        ran=0
        while IFS= read -r command; do
            [ -n "$command" ] || continue
            classes="$(grep -o -- '--tests ' <<<"$command" | wc -l)"
            ran=$(( ran + classes ))
            # `--rerun`: a task whose inputs did not change is UP-TO-DATE, and
            # an UP-TO-DATE test task runs NO test. The line below names the
            # classes that ran, so they have to run — a second `./check.sh
            # --affected` over the same tree would otherwise print "N test
            # class(es) ran" about a run that never happened.
            echo "running: $command --rerun $COVERAGE_OFF"
            eval "$command --rerun $COVERAGE_OFF" || {
                echo -e "${RED}an affected test FAILED${NC}"
                scripts/ci/print-test-failures.sh shared/build/test-results || true
                exit 1
            }
        done <<<"$selection"
        echo -e "${GREEN}the affected lane: $ran test class(es) ran${NC}"
    fi
else
    timed "shared:jvmTest" ./gw :shared:jvmTest --quiet "${COVERAGE_OFF}" || {
        echo -e "${RED}shared:jvmTest FAILED${NC}"
        scripts/ci/print-test-failures.sh shared/build/test-results || true
        exit 1
    }
    echo -e "${GREEN}shared:jvmTest passed${NC}"
fi

echo -e "${YELLOW}=== [3a/$TOTAL] shared:archTest ===${NC}"
# The architecture gates have a task of their own: they read the whole tree, and
# the Konsist scope they build is per-JVM — four forks built it four times. One
# fork here builds it once.
timed "shared:archTest" ./gw :shared:archTest --quiet "${COVERAGE_OFF}" || {
    echo -e "${RED}shared:archTest FAILED${NC}"
    scripts/ci/print-test-failures.sh shared/build/test-results/archTest || true
    exit 1
}
echo -e "${GREEN}shared:archTest passed${NC}"
# The Android source set is compiled by neither the JVM tests nor detekt: detekt runs
# without type resolution, and `:shared:jvmTest` never touches `src/androidMain`. A
# call that does not exist in `kotlinx-datetime 0.8` passed a green detekt and 3266
# green JVM tests in this repository, and was caught only by the Android build.
#
# The host test is a Robolectric test — no emulator, no device — so it belongs in the
# local loop, not only in CI.
echo -e "${YELLOW}=== [3b/$TOTAL] shared:testAndroidHostTest ===${NC}"
timed "shared:testAndroidHostTest" ./gw :shared:testAndroidHostTest --quiet "${COVERAGE_OFF}" || {
    echo -e "${RED}shared:testAndroidHostTest FAILED — the Android source set does not compile, " \
        "and nothing else in this script would have said so${NC}"
    exit 1
}
echo -e "${GREEN}shared:testAndroidHostTest passed${NC}"

echo -e "${YELLOW}=== [4/$TOTAL] desktopApp:test ===${NC}"
timed "desktopApp:test" ./gw :desktopApp:test --quiet "${COVERAGE_OFF}" || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

# The two ADR modules. Each holds a `@Tag("slow")` class, and neither test task
# ran anywhere — in CI or here — which is the shape the slow-tests rule exists
# to refuse: a test the local loop never runs, and CI never runs either, is a
# test whose breakage nobody ever sees. They are named on both surfaces, and
# the parity gate insists on that: a task CI runs and this script does not is
# how a local loop stays green on a module CI would fail.
echo -e "${YELLOW}=== [4b/$TOTAL] adr-cli:test + adr-core:jvmTest ===${NC}"
timed "adr-cli:test" ./gw :adr-cli:test :adr-core:jvmTest --quiet "${COVERAGE_OFF}" || {
    echo -e "${RED}adr-cli:test / adr-core:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}adr-cli:test + adr-core:jvmTest passed${NC}"

echo -e "${YELLOW}=== [5/$TOTAL] androidApp:assembleDebug ===${NC}"
timed "androidApp:assembleDebug" ./gw :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

# "Tests passed" is not "the tests ran". JUnit's includeTags matches per class, so a
# class can stop being selected with no error and the task still goes green — which is
# how CI once ran 16 of 218 classes. Run this after the test steps, never before.
echo -e "${YELLOW}=== [6/$TOTAL] executed test counts ===${NC}"
if (( AFFECTED )); then
    # The floor describes the FULL suite: it is the count of the tests that must
    # have run, so a run that lost coverage is caught. The affected lane runs a
    # SUBSET on purpose, so a count below the floor is the lane working, not
    # coverage lost — comparing the two would fail every affected run.
    echo -e "${GREEN}the affected lane runs a subset — the full-suite floor does not apply to it${NC}"
else
    # Two verdicts, two exit codes, two messages (check-test-runs.py documents the
    # boundary): 3 = "no JUnit report at all", so nothing ran and no floor was
    # compared; 1 = "a report exists and is below the floor", so the suite ran and
    # lost coverage. One message for both sent the reader to re-run the tests when
    # the suite had actually lost coverage.
    set +e
    python3 scripts/check-test-runs.py --require shared:jvmTest,shared:archTest,desktopApp:test \
        --max-age 21600
    TEST_RUNS_EXIT=$?
    set -e
    case "$TEST_RUNS_EXIT" in
        0)
            echo -e "${GREEN}test run floors met${NC}"
            ;;
        3)
            echo -e "${RED}no JUnit report found — nothing ran, so no floor was compared${NC}"
            exit 3
            ;;
        *)
            echo -e "${RED}a test source set ran fewer tests than its recorded floor${NC}"
            exit 1
            ;;
    esac
fi

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
# GATE_WIRING_GRADLE=1 because step 8b's koverVerify control is gradle-backed
# and the daemon is already warm here. The harness skips gradle controls
# without it: a casual `python3 scripts/check-gate-wiring.py` must not
# cold-boot a daemon, hold the shared cache lock, and freeze the host — which
# is exactly what it did once, with no timeout to bound the damage.
timed "gate wiring" env GATE_WIRING_GRADLE=1 python3 scripts/check-gate-wiring.py || {
    echo -e "${RED}gate wiring check FAILED — a gate is unreachable or cannot fail${NC}"
    exit 1
}

echo -e "${YELLOW}=== [7b/$TOTAL] gate scripts' own unit tests ===${NC}"
# The regression tests for the machinery above: the wiring gate, the liveness
# probe, the controls. They are not a line of the static registry any more —
# CI runs them in the `tests` job, next to the Gradle run they would otherwise
# compete with — but a local loop that skipped them would not rehearse what CI
# runs, and the local loop is the one a developer can iterate on.
timed "gate script tests" python3 -m unittest discover -s scripts/tests || {
    echo -e "${RED}gate script unit tests FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [7c/$TOTAL] gates are alive ===${NC}"
# The probe that runs every registered gate once and reports the ones that die.
# It is in the `tests` job in CI for the same reason as 7b: it is a second pass
# over the gate corpus, and it belongs where there is slack.
#
# It probes the gates named in scripts/ci/static-gates.sh, and it is not one of
# them: a probe that probed itself would launch every gate, and every gate would
# launch the probe.
timed "gate liveness" python3 scripts/check-gate-liveness.py || {
    echo -e "${RED}a gate died when it was probed${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8/$TOTAL] detekt rule unit tests ===${NC}"
# A custom rule that cannot fire is indistinguishable from a rule that has
# nothing to match. These tests are the only evidence either way.
timed "detekt-rules:test" ./gw :detekt-rules:test --quiet || {
    echo -e "${RED}detekt rule unit tests FAILED - a rule cannot be trusted without them${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8a/$TOTAL] the unwritten-properties tool's own tests ===${NC}"
# The module that checks that a state property is written when it is read was
# judged by nobody until 2026-10-23: not by CI, not by the hook, not even by
# the module graph. CI runs its tests now, and this local loop runs them too
# — a task that only one of the two surfaces names is exactly how a local
# loop stays green on a module CI would fail (check-gate-wiring.py Part I).
#
# The tests are plain JUnit over the analysis, with no compiler — they run in
# milliseconds, which is why they belong in the loop and not only in CI.
timed "tools:unwritten-properties:test" ./gw :tools:unwritten-properties:test --quiet "${COVERAGE_OFF}" || {
    echo -e "${RED}the unwritten-properties tool's tests FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [8b/$TOTAL] detekt-rule branch coverage floor ===${NC}"
# The floor is declared in settings.gradle.kts (DETEKT_RULE_BRANCH_FLOOR) and
# bound by the module's own rule, which Kover's verify task enforces. Naming
# both tasks here is what keeps CI's identical steps honest —
# check-gate-wiring.py Part I fails a Gradle task that only one of the two
# surfaces names.
#
# koverLog first, so a local run shows the number it is about to be judged by.
# It exits non-zero when the measurement itself could not happen, which is a
# failure, not a missing number.
./gw -q :detekt-rules:koverLog || {
    echo -e "${RED}could not measure detekt-rule branch coverage${NC}"
    exit 1
}
timed "detekt-rules:koverVerify" ./gw :detekt-rules:koverVerify || {
    echo -e "${RED}detekt-rule branch coverage is below the floor (see DETEKT_RULE_BRANCH_FLOOR in settings.gradle.kts)${NC}"
    exit 1
}
echo -e "${GREEN}detekt-rule branch coverage floor met${NC}"

echo -e "${YELLOW}=== [9/$TOTAL] mcp-server:compileKotlin (DI graph validation) ===${NC}"
timed "mcp-server:compileKotlin" ./gw :mcp-server:compileKotlin --quiet || {
    echo -e "${RED}mcp-server:compileKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}mcp-server DI graph validated${NC}"

echo -e "${GREEN}=== [10/$TOTAL] detekt (enforcing, ignoreFailures=false) ===${NC}"
# Detekt has failed the build since PR 3.3 (ignoreFailures = false in both modules).
# The `|| { echo }` fallback that used to be here swallowed real violations, so a
# green ./check.sh did not imply a clean detekt run.
#
# `:androidApp:detekt` and `:detekt-rules:detekt` were missing here while ci.yml
# ran both. That is the defect Part I of check-gate-wiring.py was written for: the
# local loop was green on two modules whose lint it had never executed, so a
# violation in either passed `./check.sh` and failed CI. detekt-rules matters
# most — it is the module holding the project's own custom rules, and a rule file
# that does not lint is indistinguishable from one that has no findings.
if (( AFFECTED )); then
    # The affected lane lints the files the change touches: the same config, the
    # same baseline and the same rules as the module's own detekt task, over a
    # smaller input. A mechanical finding is a finding about the file that was
    # just written, and the whole-module sweep answers a question about every
    # file in it.
    #
    # The modules that have no changed file are skipped by the tool itself, so
    # a change in one module does not lint the others.
    timed "detekt (changed files only)" python3 scripts/lint-changed.py || {
        echo -e "${RED}detekt reported violations in the changed files — see config/detekt/ for the active rule set${NC}"
        exit 1
    }
else
    timed "detekt" ./gw :shared:detekt :desktopApp:detekt :androidApp:detekt :detekt-rules:detekt --quiet || {
        echo -e "${RED}detekt reported violations — see config/detekt/ for the active rule set${NC}"
        exit 1
    }
fi

echo -e "${GREEN}=== [11/$TOTAL] mcp-server:detekt ===${NC}"
# This task was invoked by ci.yml with `ignoreFailures = true`, so it could not
# fail there, and it was not in check.sh at all, so it did not run here either.
# Both halves mattered: nothing enforced it and nothing ran it.
timed "mcp-server:detekt" ./gw :mcp-server:detekt --quiet || {
    echo -e "${RED}mcp-server:detekt reported violations${NC}"
    exit 1
}

echo -e "${GREEN}=== [11b/$TOTAL] adr-cli:detekt ===${NC}"
# `:adr-cli` declares `detekt` with `ignoreFailures = false`. A configured detekt task
# that no surface invokes reports success forever, which is the exact defect the block
# above was written for — applied one module over.
timed "adr-cli:detekt" ./gw :adr-cli:detekt --quiet || {
    echo -e "${RED}adr-cli:detekt reported violations${NC}"
    exit 1
}

echo -e "${GREEN}=== [11c/$TOTAL] adr-core + core-base:detekt ===${NC}"
# The two modules the CLI was refactored onto (#542). Their baselines are empty, so a
# finding here is a finding about code written today — which is the only state a new
# module should ever be in. Left uninvoked they would report success forever.
timed "adr-core+core-base:detekt" ./gw :adr-core:detekt :core-base:detekt --quiet || {
    echo -e "${RED}adr-core/core-base detekt reported violations${NC}"
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
        # The floor of the instrumented tier, checked where the report is. The
        # instrumentation itself is optional here — its failure is not this
        # script's verdict — but a run that does not match what the tier is
        # recorded to deliver is a finding, and the report is the only place
        # that fact exists.
        #
        # "Does not match" rather than "delivered fewer": the floor ratchets in
        # both directions, so a run that delivered *more* than the floor is
        # also a finding — the floor is behind the suite. The gate's own output
        # says which of the two it was; this line must not contradict it.
        if [[ -d androidApp/build/outputs/androidTest-results/connected ]]; then
            python3 scripts/check-instrumented-test-floor.py || {
                echo -e "${RED}the instrumented run and its floor disagree — see the gate's output above${NC}"
                exit 1
            }
            echo -e "${GREEN}the instrumented run matches its floor${NC}"
        fi
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
        # emulator dies mid-run, run-maestro relaunches it from an AVD
        # snapshot, and the reinstall that would follow is exactly what
        # SKIP_INSTALL=1 suppresses. The flows that then "pass" are running
        # whatever binary the snapshot happened to hold — which is how a gate
        # ends up green against a build that does not contain the fix it is
        # supposed to be verifying. Set SKIP_INSTALL=1 only when you know the
        # device is intact and the APK on it is the one you just built.
        SERIAL="${SERIAL:-}" TAGS="${MAESTRO_TAGS:-smoke}" SKIP_INSTALL="${SKIP_INSTALL:-0}" \
            run-maestro || {
            echo -e "${RED}Maestro flows FAILED${NC}"
            exit 1
        }
    else
        echo -e "${YELLOW}maestro CLI not on PATH — skipping${NC}"
    fi
fi

echo ""
echo -e "${YELLOW}=== what the loop cost ===${NC}"
# The report, not a verdict: it names what got slower than the recorded baseline and
# exits zero either way. The baseline itself lives in config/docs/gate-benchmarks.json
# and is published in docs/testing/gate-costs.md.
sgate gate-costs

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"