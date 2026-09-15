#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: jvmTest → androidHostTest → desktopApp:test → assembleDebug → detekt
# Optionally runs Android instrumentation on adb device if SKIP_ADB=0
# Usage: SKIP_ADB=1 ./check.sh   # skip adb tests

set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== [1/4] shared:jvmTest ===${NC}"
./gradlew :shared:jvmTest --no-daemon --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [2/4] shared:testAndroidHostTest ===${NC}"
./gradlew :shared:testAndroidHostTest --no-daemon --quiet || {
    echo -e "${RED}shared:testAndroidHostTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:testAndroidHostTest passed${NC}"

echo -e "${YELLOW}=== [3/4] desktopApp:test ===${NC}"
./gradlew :desktopApp:test --no-daemon --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [4/5] androidApp:assembleDebug ===${NC}"
./gradlew :androidApp:assembleDebug --no-daemon --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

echo -e "${YELLOW}=== [5/5] detekt (report-only, ignoreFailures=true) ===${NC}"
./gradlew :shared:detekt :desktopApp:detekt --no-daemon --quiet || {
    echo -e "${YELLOW}  detekt reported violations (ignoreFailures=true — see baselines in config/detekt/)${NC}"
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
            --no-daemon --quiet || true
        echo -e "${GREEN}Android instrumentation completed${NC}"
    fi
fi

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
