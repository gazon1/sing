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

# A shared `core.worktree` makes every worktree of this repository resolve its
# files somewhere else — and it fails silently: `git status` reports files that
# are present on disk as deleted, and a commit can record a tree that is not
# the one in front of the author. It gets there when something writes the
# config without `--worktree` while the extension was off, which is why the
# extension is enabled above and why the value is checked here.
#
# The value belongs to the worktree it names, and a worktree names itself.
shared_worktree="$(git config --get core.worktree || true)"
if [[ -n "$shared_worktree" && "$shared_worktree" != "$root" ]]; then
    git config --unset core.worktree
    echo "removed a shared core.worktree that pointed at $shared_worktree"
    echo "  (it made every other worktree of this repository read someone else's files)"
fi

git config --worktree core.hooksPath .githooks
echo "core.hooksPath -> .githooks (this worktree only; shared config untouched)"

# The SSH keepalive for the pre-push hook is NOT set here: it lives in the
# SHARED config, written by `just setup-hooks`, because it is harmless in every
# worktree and a per-worktree copy had to be applied by hand — so a fresh
# worktree could still lose the connection during a long pre-push (git's SSH
# connection idles while the hook runs detekt, GitHub closes it, and the push
# fails with 141). A worktree that already has its own core.sshCommand keeps
# it: the value may carry a different binary, a port or an identity.

if [[ ! -d .githooks ]]; then
    echo "WARNING: this branch has no .githooks directory — hooks will not run here." >&2
    echo "         That is the case for older branches, not a failure of this script." >&2
fi

guh="$("$root/scripts/setup-gradle-home.sh")"
echo "Gradle user home -> $guh"
echo
echo "Use ./gw instead of ./gradlew, and ./gw --stop instead of ./gradlew --stop."
