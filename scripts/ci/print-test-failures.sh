#!/usr/bin/env bash
# Surface <failure>/<error> messages from Gradle test-result XML so the agent
# doesn't parse XML by hand. Usage:
#   scripts/ci/print-test-failures.sh [path-glob]   (default shared/build/test-results)
#
# The XML is read by scripts/src/traceability/junit_xml.py — the repository's
# one JUnit reader. This script used to parse the XML inline, which is how it
# came to disagree with scripts/junit-annotations.py: the inline parser looked
# for 'failure' and 'error' children (right) while the annotation script
# checked for junitparser.Failure instances (which misses <error>), and only
# one of them printed the terminal failure of a retried test.
#
# One reader, two renderings: this one prints to the console, the other emits
# GitHub annotations.
set -uo pipefail
ROOT="${1:-shared/build/test-results}"
if [ ! -d "$ROOT" ]; then
    echo "no test results at $ROOT"
    exit 0
fi
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export REPO_ROOT
found=0
while IFS= read -r -d '' xml; do
    python3 - "$xml" <<'PY'
import os
import pathlib
import sys

repo_root = pathlib.Path(os.environ["REPO_ROOT"])
sys.path.insert(0, str(repo_root / "scripts" / "src"))

from traceability.junit_xml import parse_junit_file_strict

path = pathlib.Path(sys.argv[1])
try:
    results = parse_junit_file_strict(path)
except Exception as exc:  # noqa: BLE001 — a malformed report must not abort the sweep
    print(f"  {path}: parse error {exc}")
    sys.exit(0)

for case in results:
    if not case.failed_like:
        continue
    print(f"{case.classname}#{case.name}: {case.message}")
    body = case.stacktrace
    if body:
        print("  " + body.replace("\n", "\n  "))
    if case.retried:
        print(
            f"  (also failed {case.flaky_attempts} earlier attempt(s) "
            "before the terminal failure)"
        )
PY
done < <(find "$ROOT" -name '*.xml' -print0)
echo "done"
