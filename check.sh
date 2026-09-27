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

echo -e "${YELLOW}=== [1/4] shared:jvmTest ===${NC}"
./gradlew :shared:jvmTest --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [2/4] desktopApp:test ===${NC}"
./gradlew :desktopApp:test --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [3/4] androidApp:assembleDebug ===${NC}"
./gradlew :androidApp:assembleDebug --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

echo -e "${GREEN}=== [4/4] detekt (enforcing, ignoreFailures=false) ===${NC}"
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

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
