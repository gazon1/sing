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
# Idempotent: safe to re-run. Never starts Gradle.
#
# It DOES touch the network, once: the Python environment is installed with uv
# (the same installer CI uses), and uv resolves packages. Everything else here
# is local. Pass SINGULARITY_TOOLS_VENV to choose where the venv goes.
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

# ── The local environment file ───────────────────────────────────────────────
#
# `.env` holds the secrets a local run needs (API tokens, the Android signing
# material) and it is gitignored, so it lives in ONE place — the canonical
# checkout — and every worktree needs its own copy of it. A worktree without
# it fails at the first command that reads a token, and the failure names the
# missing variable, not the missing file.
#
# The copy is not tracked (`.env` is in .gitignore) and it is not overwritten:
# a file that already exists here was put there by hand, and replacing it with
# the canonical one would swap one set of secrets for another.
# The common dir is the shared .git; its parent is the canonical checkout.
# --path-format=absolute answers with an absolute path in every worktree;
# without it, the main checkout answers with the relative ".git".
common_dir="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || git rev-parse --git-common-dir)"
main_root="$(cd "$(dirname "$common_dir")" && pwd)"
if [[ -f "$main_root/.env" ]]; then
    if [[ -f "$root/.env" ]]; then
        echo ".env        -> already here (kept as it is)"
    else
        cp "$main_root/.env" "$root/.env"
        chmod 600 "$root/.env"
        echo ".env        -> copied from $main_root"
    fi
else
    echo ".env        -> not found in $main_root (nothing to copy)"
fi

# ── The local Python environment ─────────────────────────────────────────────
#
# The gate suite is Python, and the gate CLI is an EDITABLE install of
# scripts/src/: a venv made in one checkout resolves `import sgate` to the tree
# it was installed from. A shared venv therefore makes every worktree judge
# the tree that venv was made for — which is how a gate reports about a
# checkout that is not in front of you.
#
# So this worktree gets its own venv, named after the worktree, and the
# installer is the one CI uses (scripts/ci/setup-local-tools.sh) — one
# arrangement, so a local run and a CI run of the same gate agree.
#
# The install needs the network (uv resolves the packages; actionlint is a
# release download). A failure here is reported and the worktree stays usable:
# the gates that need the venv will say which tool is missing.
venv="$HOME/.venvs/singularity-todo-$(basename "$root")"
if [[ -x "$venv/bin/python" ]]; then
    echo "Python venv  -> $venv (already there)"
else
    echo "Python venv  -> $venv"
    if SINGULARITY_TOOLS_VENV="$venv" bash "$root/scripts/ci/setup-local-tools.sh" >/dev/null 2>&1; then
        echo "                ready: export PATH=\"$venv/bin:\$PATH\""
    else
        echo "                could not be created — the gate suite's tools are missing here." >&2
        echo "                Fix it with:" >&2
        echo "                  SINGULARITY_TOOLS_VENV=\"$venv\" bash scripts/ci/setup-local-tools.sh" >&2
    fi
fi

guh="$("$root/scripts/setup-gradle-home.sh")"
echo "Gradle user home -> $guh"
echo
echo "Use ./gw instead of ./gradlew, and ./gw --stop instead of ./gradlew --stop."
