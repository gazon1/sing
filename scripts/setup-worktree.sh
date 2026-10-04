#!/usr/bin/env bash
#
# Make a worktree use its OWN .githooks, and prepare its private Gradle home.
#
# Run once per worktree, right after creating it:
#
#     git worktree add ../my-worktree -b my-branch
#     ./scripts/setup-worktree.sh
#
# Why the second step is manual
#
# Git resolves `core.hooksPath` before any hook runs, so a hook cannot fix its
# own path. Worse, the config that *should* be correct is usually not: a single
# absolute `core.hooksPath` in the shared config makes every worktree execute the
# hooks of whichever checkout that path names, and a hook that acts on the wrong
# worktree is silent — no error, just the wrong directory being modified.
#
# The obvious global fix is `core.hooksPath = .githooks` (relative, resolved per
# worktree). It is deliberately not applied here: 7 of this repository's 38
# worktrees are on branches that predate the .githooks directory, and switching
# the shared value would silently leave those with no hooks at all — trading one
# silent misdirection for a larger silent absence.
#
# So the value is set per worktree, through the worktree-scoped config Git
# provides for exactly this. It overrides the shared value here and touches
# nothing else.
#
# Idempotent: safe to re-run. Never starts Gradle, never touches the network.
#
# See docs/decisions/2026-10-04-gradle-daemon-isolation.md
#
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

# --worktree writes to .git/worktrees/<name>/config.worktree, which requires this
# extension. It is repo-wide but inert on its own: without a --worktree value,
# every worktree still reads the shared config exactly as before.
if [[ "$(git config --get extensions.worktreeConfig || true)" != "true" ]]; then
    git config extensions.worktreeConfig true
    echo "enabled extensions.worktreeConfig (repo-wide, affects nothing on its own)"
fi

git config --worktree core.hooksPath .githooks
echo "core.hooksPath -> .githooks (this worktree only; shared config untouched)"

if [[ ! -d .githooks ]]; then
    echo "WARNING: this branch has no .githooks directory — hooks will not run here." >&2
    echo "         That is the case for older branches, not a failure of this script." >&2
fi

guh="$("$root/scripts/setup-gradle-home.sh")"
echo "Gradle user home -> $guh"
echo
echo "Use ./gw instead of ./gradlew, and ./gw --stop instead of ./gradlew --stop."
