#!/usr/bin/env bash
# Surface <failure>/<error> messages from Gradle test-result XML so the agent
# doesn't parse XML by hand. Usage:
#   scripts/ci/print-test-failures.sh [path-glob]   (default shared/build/test-results)
set -uo pipefail
ROOT="${1:-shared/build/test-results}"
if [ ! -d "$ROOT" ]; then
    echo "no test results at $ROOT"
    exit 0
fi
found=0
while IFS= read -r -d '' xml; do
    # extract testcase failures/errors with name
    python3 - "$xml" <<'PY'
import sys, xml.etree.ElementTree as ET
p=sys.argv[1]
try:
    t=ET.parse(p)
except Exception as e:
    print(f"  {p}: parse error {e}"); sys.exit(0)
root=t.getroot()
for tc in root.iter("testcase"):
    for node in list(tc):
        if node.tag in ("failure","error"):
            name=tc.get("name","?")
            cls=tc.get("classname","?")
            msg=node.get("message","")
            print(f"{cls}#{name}: {msg}")
            body=(node.text or "").strip()
            if body:
                print("  " + body[:500].replace("\n","\n  "))
PY
done < <(find "$ROOT" -name '*.xml' -print0)
echo "done"
