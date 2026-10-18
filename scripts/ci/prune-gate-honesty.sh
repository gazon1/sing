#!/usr/bin/env bash
# prune-gate-honesty.sh — safely remove stale gate-honesty probe worktrees.
#
# check-gate-honesty.py creates worktrees in /tmp/gate-honesty-*/probe-tree.
# When the script is interrupted (Ctrl+C, crash, OOM), the `finally` block does not
# run and the worktree entries remain registered in the main repository's git-dir.
#
# Over time these accumulate (934 observed on 2026-10-05) and slow down `git worktree
# list` and the repository open time.
#
# This script removes ONLY worktrees that:
#   1. Have a reason matching "gate-honesty probe *"
#   2. Point to a directory that no longer exists (stale entries)
#
# Coordination: before pruning, checks whether any probe is currently running by
# looking for active gate-honesty processes and locked worktree entries.  If any
# are found, refuses to prune with a clear message.
#
# Usage:
#   bash scripts/ci/prune-gate-honesty.sh [--dry-run] [--force]
#
# Exit codes:
#   0 — nothing to prune, or all stale entries removed
#   1 — refused to prune (probes running or --force not given)
#   2 — git error

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

DRY_RUN=false
FORCE=false
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
elif [[ "${1:-}" == "--force" ]]; then
    FORCE=true
fi

echo "=== gate-honesty probe worktree prune ==="

# ── 1. Find all gate-honesty worktree entries ───────────────────────────────
# Each worktree line in `git worktree list --porcelain` looks like:
#   worktree /tmp/gate-honesty-xxx/probe-tree
#   gitdir /path/to/.git/worktrees/gate-honesty-xxx
#   reason gate-honesty probe <name>
#   detached
#   locked
#   (or just: reason gate-honesty probe <name> with no locked)

mapfile -t WT_LINES < <(git worktree list --porcelain)

CURRENT_WT=""
declare -a STALE_WTS=()    # list of stale worktree paths
declare -A WT_REASON=()    # path → reason

while IFS= read -r line; do
    key="${line%% *}"
    val="${line#* }"
    case "$key" in
        worktree)
            CURRENT_WT="$val"
            ;;
        reason)
            if [[ "$val" == gate-honesty\ probe\ * ]]; then
                WT_REASON["$CURRENT_WT"]="$val"
            else
                # Not a gate-honesty entry; reset
                CURRENT_WT=""
            fi
            ;;
    esac
done < <(git worktree list --porcelain)

# Build list of stale worktrees (reason present + dir missing)
for wt_path in "${!WT_REASON[@]}"; do
    if [[ ! -d "$wt_path" ]]; then
        STALE_WTS+=("$wt_path")
    fi
done

if [[ ${#STALE_WTS[@]} -eq 0 ]]; then
    echo "No stale gate-honesty probe worktrees found."
    exit 0
fi

echo "Found ${#STALE_WTS[@]} stale probe worktree(s):"
for wt in "${STALE_WTS[@]}"; do
    echo "  $wt  (${WT_REASON[$wt]})"
done

# ── 2. Coordination check — are any probes currently running? ─────────────────
# Check for:
#   a) locked worktree entries (git worktree add --locks them) where the directory
#      still exists — a locked + stale worktree is unreachable, not a live probe
#   b) active probe_once processes
LOCKED_COUNT=0
while IFS= read -r wt; do
    # Skip stale entries (dir gone) — they cannot be live probes
    [[ -d "$wt" ]] || continue
    ((LOCKED_COUNT++))
done < <(git worktree list --porcelain | awk '
    $1 == "worktree" { cur=$2 }
    $1 == "locked" && cur != "" { print cur }
')

PROBE_PIDS=$(pgrep -f "check-gate-honesty" 2>/dev/null || true)
if [[ -n "$PROBE_PIDS" ]] && [[ ${#STALE_WTS[@]} -gt 0 ]]; then
    echo ""
    echo "WARNING: gate-honesty process(es) may be running: PID $PROBE_PIDS" >&2
    echo "Refusing to prune while probes are active." >&2
    echo "Kill the process(es) first, or re-run with --force." >&2
    if [[ "$FORCE" != "true" ]]; then
        echo "Aborted." >&2
        exit 1
    fi
    echo "(forced — continuing)"
fi

# ── 3. Prune ────────────────────────────────────────────────────────────────
echo ""

if [[ "$DRY_RUN" == "true" ]]; then
    echo "[DRY RUN] Would remove:"
    for wt in "${STALE_WTS[@]}"; do
        echo "  git -C $(git rev-parse --git-dir) worktree remove --force \"$wt\""
    done
    echo "Would also run: git -C $(git rev-parse --git-dir) worktree prune"
    echo "[DRY RUN] No changes made."
    exit 0
fi

REMOVED=0
for wt in "${STALE_WTS[@]}"; do
    echo "Removing stale worktree: $wt"
    if git -C "$(git rev-parse --git-dir)" worktree remove --force "$wt" 2>/dev/null; then
        ((REMOVED++))
    else
        echo "  (already gone or git error — running worktree prune)"
    fi
done

# Final prune to clean up any remaining stale entries
git -C "$(git rev-parse --git-dir)" worktree prune

echo "Removed $REMOVED stale probe worktree entry/entries."
exit 0
