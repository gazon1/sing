#!/usr/bin/env bash
# check.sh — Full local verification for Singularity Todo KMP project
# Runs: jvmTest → desktopApp:test → assembleDebug
# Usage: ./check.sh

set -e

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

YELLOW='\033[1;33m'
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== [1/3] shared:jvmTest ===${NC}"
./gradlew :shared:jvmTest --no-daemon --quiet || {
    echo -e "${RED}shared:jvmTest FAILED${NC}"
    exit 1
}
echo -e "${GREEN}shared:jvmTest passed${NC}"

echo -e "${YELLOW}=== [2/3] desktopApp:test ===${NC}"
./gradlew :desktopApp:test --no-daemon --quiet || {
    echo -e "${RED}desktopApp:test FAILED${NC}"
    exit 1
}
echo -e "${GREEN}desktopApp:test passed${NC}"

echo -e "${YELLOW}=== [3/3] assembleDebug ===${NC}"
./gradlew :androidApp:assembleDebug --no-daemon --quiet || {
    echo -e "${RED}assembleDebug FAILED${NC}"
    exit 1
}
echo -e "${GREEN}assembleDebug passed${NC}"

echo ""
echo -e "${GREEN}=== ALL CHECKS PASSED ===${NC}"
