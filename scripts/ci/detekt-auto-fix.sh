#!/bin/bash
# ---------------------------------------------------------------------------
# detekt-auto-fix.sh — runs detekt --auto-correct on all modules, commits the
# result to a dedicated branch, and pushes it.
#
# Exit codes
#   0  — always.  The CI verdict is produced by the separate `git diff` step;
#        this script's job is only to materialise the fix and make it visible.
#   1  — Git push failed (nontrivial; the commit still exists locally).
#
# Required env vars
#   GITHUB_TOKEN  — fine-grained token with repo:write scope (or the standard
#                    ACTIONS_ID_TOKEN_NO_SCOPE secret which suffices for
#                    GITHUB_REF pushes).
# ---------------------------------------------------------------------------
set -euo pipefail

JOB_NAME="${1:-}"
RUN_ID="${2:-}"
BRANCH_NAME="detekt-auto-fix/${JOB_NAME:-unknown}/${RUN_ID:-$(date +%s)}"

echo "detekt-auto-fix: branch = $BRANCH_NAME"

# -----------------------------------------------------------------------
# 1.  Run detekt --auto-correct on every module.
#     Using a single Gradle invocation (all modules at once) is faster than
#     looping, and the --auto-correct flag is idempotent — re-runs are no-ops
#     on already-clean files.
#
#     The LIST of modules is read from the build, not written here. It used to
#     be written here — eight module names on one line — and in the CI
#     workflow, and in the hook's record: three copies of one fact, and a
#     module added to settings.gradle.kts had to be remembered in three
#     places. The graph reads the build, so it is the list; this script asks
#     for it. A module the build declares and this list does not name is a
#     module nobody judges, and the gate that keeps the record and the code
#     together is what says so.
# -----------------------------------------------------------------------
echo "::group::Running detekt --auto-correct"
# Using || true so a zero-finding run (clean tree) still exits 0.
# The separate `git diff` check after this script is what drives the CI verdict.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mapfile -t DETEKT_TASKS < <(python3 "$ROOT/scripts/changed-modules.py" --detekt-tasks)
if [[ "${#DETEKT_TASKS[@]}" == "0" ]]; then
    echo "detekt-auto-fix: the module graph names no detekt task — nothing to judge" >&2
    exit 1
fi
./gradlew \
  "${DETEKT_TASKS[@]}" \
  --auto-correct \
  --no-build-cache \
  --console=plain \
  2>&1 || true
echo "::endgroup::"

# -----------------------------------------------------------------------
# 2.  Commit and push — but ONLY if there is something to commit.
# -----------------------------------------------------------------------
if git diff --quiet; then
  echo "detekt-auto-fix: no files changed — nothing to commit."
  exit 0
fi

echo "detekt-auto-fix: changes detected — committing and pushing."

# git-config-shared: the CI clone has one checkout and no worktree to damage;
# the identity is the bot identity the commit needs, and the shared config of
# a throwaway runner clone is where it belongs.
git config user.email "github-actions[bot]@users.noreply.github.com"
# git-config-shared: same clone, same reason — one marker per write, because a
# marker is only read where it sits.
git config user.name  "github-actions[bot]"

# Amend the commit we're about to make with a conventional footer so it can
# be discovered later.  Keep the subject clean — it should look like a normal
# auto-fix commit from `just detekt-fix`.
git add -A
git commit -m "style: apply detekt auto-correct

[detekt-auto-fix: job=$JOB_NAME run=$RUN_ID]" \
  --allow-empty

# Force-push to the dedicated auto-fix branch.
# We intentionally do NOT use --force-with-lease here because the branch
# is unique to this run-id and is never updated by anything else — a re-run
# of the same job is a fresh run-id anyway.
git push \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  "HEAD:refs/heads/${BRANCH_NAME}" \
  2>&1 || {
    echo "::error::Failed to push auto-fix branch $BRANCH_NAME"
    echo "The commit exists locally; the push can be retried or done manually."
    exit 1
  }

echo "detekt-auto-fix: pushed to $BRANCH_NAME"
exit 0
