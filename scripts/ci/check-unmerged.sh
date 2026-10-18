#!/usr/bin/env bash
# check-unmerged.sh — fail if origin/main has commits not yet in the current branch.
#
# Use after `git fetch origin` to verify the local tree has not diverged from main.
# Exit codes:
#   0 — current branch is up-to-date with origin/main (or ahead)
#   1 — origin/main is strictly ahead of current branch (local needs merge/rebase)
#   2 — not on a branch, no remote, or other git error
#
# Intended use:
#   - As a pre-rebase sanity check (run before `git rebase` or `git merge origin/main`)
#   - As a CI gate that fails before a rebase-onto-main would create divergence
#   - As a daily reminder that the tree is stale

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

UPSTREAM="${1:-origin/main}"

# Verify upstream exists
if ! git rev-parse --verify "$UPSTREAM" >/dev/null 2>&1; then
    echo "check-unmerged: upstream '$UPSTREAM' does not exist. Run 'git fetch' first." >&2
    exit 2
fi

# Fetch first — this is the whole point of the gate
echo "check-unmerged: fetching $UPSTREAM ..."
if ! git fetch origin --quiet 2>&1; then
    echo "check-unmerged: fetch failed. Check network and remote configuration." >&2
    exit 2
fi

HEAD_SHA="$(git rev-parse HEAD)"
UPSTREAM_SHA="$(git rev-parse "$UPSTREAM")"

if [[ "$HEAD_SHA" == "$UPSTREAM_SHA" ]]; then
    echo "check-unmerged: up-to-date with $UPSTREAM ✓"
    exit 0
fi

# Check: is upstream strictly ahead of HEAD?
local_ahead=0 upstream_ahead=0
read local_ahead upstream_ahead <<<"$(git rev-list --left-right --count HEAD..."$UPSTREAM" 2>/dev/null || echo "0 0")"

if [[ "$upstream_ahead" -gt 0 && "$local_ahead" -eq 0 ]]; then
    echo "check-unmerged: FAIL — $UPSTREAM is $upstream_ahead commit(s) ahead of current branch." >&2
    echo "  Local branch needs to merge or rebase before continuing." >&2
    echo ""
    echo "  New commits on $UPSTREAM:"
    git log --oneline "$UPSTREAM" ^HEAD | sed 's/^/    /'
    echo ""
    echo "  To fix: git fetch origin && git rebase origin/main"
    exit 1
fi

if [[ "$local_ahead" -gt 0 && "$upstream_ahead" -gt 0 ]]; then
    echo "check-unmerged: WARNING — branches have diverged (local=$local_ahead, upstream=$upstream_ahead)." >&2
    echo "  A rebase will rewrite local commits; a merge preserves them." >&2
    exit 0
fi

if [[ "$local_ahead" -gt 0 && "$upstream_ahead" -eq 0 ]]; then
    echo "check-unmerged: current branch is $local_ahead commit(s) ahead of $UPSTREAM ✓"
    exit 0
fi

# Should not reach here, but treat as OK
exit 0
