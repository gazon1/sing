#!/usr/bin/env bash
# post-checkout tasks (called by hk)
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Only regenerate when checking out a branch (not individual files).
# $1 = previous HEAD, $2 = new HEAD, $3 = 1 if checking out branch, 0 if checking out file
if [[ "${3:-0}" != "1" ]]; then
    exit 0
fi

# Run only when DIGEST.md exists locally (repo was cloned before it was gitignored)
if [[ -f "docs/decisions/DIGEST.md" ]]; then
    echo "=== Post-checkout: regenerating DIGEST.md ==="
    ./scripts/refresh-decisions-digest.sh
fi

# Prepare this worktree's private Gradle user home.
if ! home="$(./scripts/setup-gradle-home.sh 2>&1)"; then
    echo "post-checkout: could not set up the private Gradle user home:" >&2
    echo "$home" >&2
    echo "post-checkout: builds still work, but they will share ~/.gradle with other worktrees." >&2
    exit 0
fi

if [[ "${1:-}" =~ ^0+$ ]]; then
    echo "=== Post-checkout: new worktree, Gradle user home at $home ==="
    echo "    Use ./gw instead of ./gradlew, and ./gw --stop instead of ./gradlew --stop."
fi
