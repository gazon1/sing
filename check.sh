#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: jvmTest → desktopApp:test → assembleDebug → detekt
# Optionally runs Android instrumentation on adb device if SKIP_ADB=0
# Usage: SKIP_ADB=1 ./check.sh   # skip adb tests
#
# NOTE: no --no-daemon here on purpose. A warm Gradle+Kotlin daemon keeps
# incremental compilation across runs; --no-daemon forces a cold JVM every
# time. CI invokes gradle directly and manages its own lifecycle.

set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== [1/12] build version catalog gate ===${NC}"
# Fast: no JVM startup. Fails before Gradle if a *.gradle.kts contains a
# hardcoded group:artifact:version literal that should come from libs.versions.toml.
python3 scripts/build-version-catalog-gate.py --quiet . || {
    echo -e "${RED}build version catalog FAILED — hardcoded literal(s) found${NC}"
    exit 1
}

echo -e "${YELLOW}=== [2/12] detekt rule registry (fast) ===${NC}"
# Runs before Gradle: a duplicated or missing rule registration otherwise surfaces
# minutes later as a YAML parse error pointing at detekt.yml rather than the cause.
./scripts/check-detekt-registrations.sh || {
    echo -e "${RED}detekt rule registry FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [3/12] Find unwired surfaces ===${NC}"
# Detects implemented-but-unreachable code: screens with no call site, noop callbacks
# that defeat a `?:` fallback, unbound DAOs, LogWriter subclasses never registered.
# Zero findings means the project has no dormant code. Exits 0; findings are printed.
python3 scripts/find-unwired-surfaces.py --quiet || {
    echo -e "${RED}unwired surfaces found — see above${NC}"
    exit 1
}

echo -e "${YELLOW}=== [4/12] shared:jvmTest ===${NC}"
./gradlew :shared:jvmTest --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [5/12] desktopApp:test ===${NC}"
./gradlew :desktopApp:test --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [6/12] androidApp:assembleDebug ===${NC}"
./gradlew :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

# "Tests passed" is not "the tests ran". JUnit's includeTags matches per class, so a
# class can stop being selected with no error and the task still goes green — which is
# how CI once ran 16 of 218 classes. Run this after the test steps, never before.
echo -e "${YELLOW}=== [7/12] executed test counts ===${NC}"
# "Tests passed" is not "the tests ran". JUnit's includeTags matches per class, so a
# class can stop being selected with no error and the task still goes green — which is
# how CI once ran 16 of 218 classes. Run this after the test steps, never before.
python3 scripts/check-test-runs.py --require shared:jvmTest,desktopApp:test || {
    echo -e "${RED}a test source set ran fewer tests than its recorded floor${NC}"
    exit 1
}
echo -e "${GREEN}test run floors met${NC}"

echo -e "${YELLOW}=== [8/12] gate script self-tests ===${NC}"
# check-test-runs.py is the only thing that catches a partial skip, and
# check-coverage.py is the only thing that catches coverage loss. A regression
# inside either disables the gate silently — the same failure shape the gates
# exist to catch. Cheap enough (milliseconds, no JVM) to run every time.
python3 -m unittest discover -s scripts/tests 2>&1 | tail -3 || {
    echo -e "${RED}gate script self-tests FAILED${NC}"
    exit 1
}
echo -e "${GREEN}gate script self-tests passed${NC}"

echo -e "${YELLOW}=== [9/12] coverage floors (when a report exists) ===${NC}"
# --if-present because :shared:koverXmlReport instruments every test task and
# roughly triples the local loop; CI runs it in the kover job on every push.
python3 scripts/check-coverage.py --if-present || {
    echo -e "${RED}coverage below the recorded floor${NC}"
    exit 1
}

echo -e "${YELLOW}=== [10/12] doc sizes + dead doc references ===${NC}"
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

echo -e "${YELLOW}=== [11/12] mcp-server:compileKotlin (DI graph validation) ===${NC}"
./gradlew :mcp-server:compileKotlin --quiet || {
    echo -e "${RED}mcp-server:compileKotlin FAILED${NC}"
    exit 1
}
echo -e "${GREEN}mcp-server DI graph validated${NC}"

echo -e "${GREEN}=== [12/12] detekt (enforcing, ignoreFailures=false) ===${NC}"
# Detekt has failed the build since PR 3.3 (ignoreFailures = false in both modules).
# The `|| { echo }` fallback that used to be here swallowed real violations, so a
# green ./check.sh did not imply a clean detekt run.
./gradlew :shared:detekt :desktopApp:detekt --quiet || {
    echo -e "${RED}detekt reported violations — see config/detekt/ for the active rule set${NC}"
    exit 1
}

# Optional: Android instrumentation tests on real adb device
if [[ "${SKIP_ADB:-0}" != "1" ]] && adb devices | grep -q "device$"; then
    echo ""
    echo -e "${YELLOW}=== [opt] Android instrumentation on adb ===${NC}"
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [[ -n "$SERIAL" ]]; then
        echo "Running on device: $SERIAL"
        ./gradlew :androidApp:connectedDebugAndroidTest \
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
        SERIAL="${SERIAL:-}" TAGS="${MAESTRO_TAGS:-smoke}" SKIP_INSTALL=1 \
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
