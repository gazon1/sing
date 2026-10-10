#!/usr/bin/env bash
# kover-verify-gate.sh — blocking gate: kover branch coverage floor for detekt-rules.
#
# Generates the kover XML report then enforces the branch coverage floor (60%).
#
# koverXmlReport depends on :detekt-rules:test, so if tests fail the report is not
# produced. In that case this gate fails with "coverage unknown" — the correct
# behaviour for a blocking coverage gate when the test suite itself is broken.
# The pre-existing DetektConfigWiringTest failure is tracked separately in GitHub.
#
# Note on kover 0.9.x DSL: the verify{} block is not accessible in the project-level
# Kotlin DSL for this version (Java API VerifyExtension.verify exists but has no
# Kotlin binding), so coverage enforcement uses a Python parser of the XML report.
set -uo pipefail
cd "$(dirname "$0")/../.."

REPORT="detekt-rules/build/reports/kover/report.xml"
FLOOR=60.0

echo "=== Kover verify (detekt-rules branch coverage floor: ${FLOOR}%) ==="

# koverXmlReport depends on :detekt-rules:test — if tests fail, the report is skipped.
# We use --continue so Gradle finishes the invocation rather than aborting early.
./gradlew :detekt-rules:koverXmlReport --continue >/dev/null 2>&1

if [[ ! -f "$REPORT" ]]; then
    echo ""
    echo "FAIL: Coverage report not found — :detekt-rules:test did not produce coverage data."
    echo ""
    echo "Likely cause: tests are failing (see test report at detekt-rules/build/reports/tests/test/)."
    echo "Coverage cannot be measured until the test suite passes."
    echo ""
    echo "Run locally to diagnose:"
    echo "  ./gradlew :detekt-rules:test :detekt-rules:koverXmlReport"
    exit 1
fi

python3 - <<'PYEOF'
import sys
import xml.etree.ElementTree as ET

report = "detekt-rules/build/reports/kover/report.xml"
floor = 60.0

try:
    root = ET.parse(report).getroot()
except ET.ParseError as e:
    print(f"FAIL: could not parse {report}: {e}", file=sys.stderr)
    sys.exit(1)

total_covered = 0
total_missed = 0
for counter in root.iter("counter"):
    if counter.get("type") == "BRANCH":
        total_covered += int(counter.get("covered", 0))
        total_missed += int(counter.get("missed", 0))

total = total_covered + total_missed
coverage = (total_covered / total * 100) if total > 0 else 0.0

print(f"Branch coverage: {total_covered}/{total} = {coverage:.1f}%")
print(f"Floor: {floor}%")

if total == 0:
    print("\nFAIL: 0 branches measured — tests did not produce coverage data.", file=sys.stderr)
    print("Run: ./gradlew :detekt-rules:test :detekt-rules:koverXmlReport", file=sys.stderr)
    sys.exit(1)

if coverage < floor:
    print(f"\nFAIL: branch coverage {coverage:.1f}% < floor {floor}%", file=sys.stderr)
    print("Fix: add tests for uncovered branches, then raise the floor.", file=sys.stderr)
    sys.exit(1)

print(f"\nPASS: branch coverage {coverage:.1f}% >= floor {floor}%")
PYEOF
