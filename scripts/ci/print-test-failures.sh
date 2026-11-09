#!/usr/bin/env bash
# Surface <failure>/<error> messages from Gradle test-result XML so the agent
# doesn't parse XML by hand. Usage:
#   scripts/ci/print-test-failures.sh [path-glob]   (default shared/build/test-results)
#
# The parsing lives in scripts/print-test-failures.py, where it can be tested.
# This file stays because hk.pkl and the CI workflows call it by this name.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$ROOT/scripts/print-test-failures.py" "$@"
