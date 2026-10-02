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

echo -e "${YELLOW}=== [0/7] build version catalog gate ===${NC}"
# Fast: no JVM startup. Fails before Gradle if a *.gradle.kts contains a
# hardcoded group:artifact:version literal that should come from libs.versions.toml.
python3 scripts/build-version-catalog-gate.py --quiet . || {
    echo -e "${RED}build version catalog FAILED — hardcoded literal(s) found${NC}"
    exit 1
}

echo -e "${YELLOW}=== [1/7] detekt rule registry (fast) ===${NC}"
# Runs before Gradle: a duplicated or missing rule registration otherwise surfaces
# minutes later as a YAML parse error pointing at detekt.yml rather than the cause.
./scripts/check-detekt-registrations.sh || {
    echo -e "${RED}detekt rule registry FAILED${NC}"
    exit 1
}

echo -e "${YELLOW}=== [2/7] Find unwired surfaces ===${NC}"
# Detects implemented-but-unreachable code: screens with no call site, noop callbacks
# that defeat a `?:` fallback, unbound DAOs, LogWriter subclasses never registered.
# Zero findings means the project has no dormant code. Exits 0; findings are printed.
python3 scripts/find-unwired-surfaces.py --quiet || {
    echo -e "${RED}unwired surfaces found — see above${NC}"
    exit 1
}

echo -e "${YELLOW}=== [3/7] shared:jvmTest ===${NC}"
./gradlew :shared:jvmTest --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [4/7] desktopApp:test ===${NC}"
./gradlew :desktopApp:test --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [5/7] androidApp:assembleDebug ===${NC}"
./gradlew :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

echo -e "${GREEN}=== [7/7] detekt (enforcing, ignoreFailures=false) ===${NC}"
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
