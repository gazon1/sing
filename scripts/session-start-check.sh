#!/usr/bin/env bash
# Automated session-start checks (replaces the manual AGENTS.md reminders).
# Runs: git fetch + sgate checkout-state. Reports divergence.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo "=== session-start-check ==="
git fetch origin 2>&1 | tail -3
echo "--- divergence ---"
behind=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo "?")
echo "commits behind origin/main: $behind"
if [ "$behind" != "0" ] && [ "$behind" != "?" ]; then
    echo "WARNING: branch is $behind commits behind main. Consider rebase/cherry-pick."
fi
echo "--- checkout state ---"
PYTHONPATH="scripts/src" python3 -m sgate checkout-state 2>&1 | tail -20 || true
echo "=== done ==="
