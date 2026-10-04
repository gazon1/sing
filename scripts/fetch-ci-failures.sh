#!/usr/bin/env bash
# Download a CI run's test artifacts and print a compact failure summary.
#
# A failing gradle test task in CI only says "See the report at
# <workspace path>". That path lives on the runner, so without this helper
# the only way to learn why a build went red is to reproduce it locally.
#
# Usage:
#   scripts/fetch-ci-failures.sh [run-id] [--keep] [--depth N]
#
#   run-id   CI run id (default: latest CI run on the current branch)
#   --keep   do not delete the extracted artifact directory
#   --depth  stack frames to print per failure (default 8)
#
# Exit codes:
#   0  no failing tests found
#   1  at least one failing test (the summary is still printed)
#   2  usage / tooling error
set -euo pipefail

RUN_ID=""
KEEP=0
DEPTH=8

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --depth) DEPTH="${2:?--depth needs a number}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) RUN_ID="$1"; shift ;;
  esac
done

command -v gh >/dev/null || { echo "gh is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated" >&2; exit 2; }

if [[ -z "$RUN_ID" ]]; then
  RUN_ID="$(gh run list --workflow CI --limit 1 \
    --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
  [[ -n "$RUN_ID" ]] || { echo "could not resolve a run id; pass one explicitly" >&2; exit 2; }
  echo "latest CI run: $RUN_ID"
fi

DEST="$(mktemp -d "${TMPDIR:-/tmp}/ci-failures-${RUN_ID}.XXXXXX")"
cleanup() { [[ "$KEEP" -eq 1 ]] || rm -rf "$DEST"; }
trap cleanup EXIT

echo "downloading artifacts of run $RUN_ID into $DEST ..."
if ! gh run download "$RUN_ID" --dir "$DEST" 2>/dev/null; then
  echo "run $RUN_ID has no downloadable artifacts." >&2
  echo "HTML and JUnit reports are only uploaded for runs that include the" >&2
  echo "test-report upload steps in .github/workflows/ci.yml." >&2
  exit 2
fi

# The test HTML report is the browsable form; point at it before the summary.
INDEX="$(find "$DEST" -path '*reports/tests/*/index.html' | sort | head -5 || true)"
if [[ -n "$INDEX" ]]; then
  echo
  echo "HTML reports:"
  while IFS= read -r f; do echo "  $f"; done <<< "$INDEX"
fi

echo
# `|| STATUS=$?` keeps errexit from swallowing the parser's exit code.
STATUS=0
python3 - "$DEST" "$DEPTH" <<'PY' || STATUS=$?
import glob, os, sys
import xml.etree.ElementTree as ET

dest, depth = sys.argv[1], int(sys.argv[2])
files = glob.glob(os.path.join(dest, "**", "TEST-*.xml"), recursive=True)
if not files:
    print("no JUnit XML found in the downloaded artifacts")
    raise SystemExit(0)

total = failed = skipped = 0
reports = []

for path in sorted(files):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        reports.append((os.path.basename(path), [f"unparseable XML: {exc}"], 0, 0, 0))
        continue
    suites = root.findall("testsuite") or [root]
    for suite in suites:
        n = int(suite.get("tests", 0))
        f = int(suite.get("failures", 0)) + int(suite.get("errors", 0))
        s = int(suite.get("skipped", 0))
        total += n
        failed += f
        skipped += s
        if f == 0:
            continue
        for case in suite.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                text = (node.text or "").strip()
                lines = text.splitlines() or ["<no message>"]
                head = [l for l in lines if l.strip()][:depth]
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                reports.append((name, head, 1, 0, 0))

if not reports:
    print(f"no failing tests — {total} tests, {skipped} skipped")
    raise SystemExit(0)

for name, lines, *_ in reports:
    print("=" * 72)
    print(name)
    for line in lines:
        print("   ", line)

print("=" * 72)
print(f"summary: {failed} failing / {total} tests ({skipped} skipped)")
raise SystemExit(1)
PY

if [[ "$KEEP" -eq 0 ]]; then
  echo "extracted files removed; re-run with --keep to inspect them"
fi
exit "$STATUS"
