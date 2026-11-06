#!/usr/bin/env bash
# post-merge tasks (called by hk)
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Run only when DIGEST.md exists locally (repo was cloned before it was gitignored)
if [[ -f "docs/decisions/DIGEST.md" ]]; then
    echo "=== Post-merge: regenerating DIGEST.md ==="
    python3 scripts/refresh-decisions-digest.py
fi
