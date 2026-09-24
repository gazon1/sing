# =============================================================================
# Singularity Todo KMP — Build recipes
# Run `just --list` for all available commands.
# Run `just tests::check` before every commit.
#
# Module recipes: just <module>::<recipe>
#   just android::build release
#   just android::run serial=emulator-5554
#   just desktop::build deb
#   just desktop::run-headless
#   just tests::check
# =============================================================================

mod config   '.just/config'
mod android  '.just/android'
mod desktop  '.just/desktop'
mod tests    '.just/tests'
mod scripts  '.just/scripts'

set shell := ["bash", "-uc"]
set unstable

# ==============================================================================
# 🎯 DEFAULT
# ==============================================================================
[doc('Show available commands')]
default:
    @just --list --list-heading $'🎯 Available Commands:\n' --list-prefix '  • '

# ==============================================================================
# Super-aliases
# ==============================================================================

# ----- Android shortcuts -----
alias aapk   := android::build-debug
alias arel   := android::build-release
alias arun   := android::run
alias at     := android::test
alias al     := android::logs
alias adb-d  := android::devices
alias adb-stop := android::force-stop

# ----- Desktop shortcuts -----
alias dr     := desktop::build-run
alias drh    := desktop::run-headless
alias ddist  := desktop::build-dist
alias dpkg   := desktop::build-deb
alias dinst  := desktop::install-deb

# ----- Tests shortcuts -----
alias tc     := tests::common
alias tj     := tests::jvm
alias tah    := tests::android-host
alias tcheck := tests::check
alias tclean := tests::clean

# ----- Lint shortcuts -----
alias lint       := tests::lint
alias detekt-fix := tests::detekt-fix

# ----- Docs shortcuts -----
alias docs-audit := tests::docs-audit

# ----- Coverage shortcuts -----
alias coverage := tests::coverage

# ----- DB shortcuts -----
alias db-a   := android::db-schema
alias db-d   := desktop::db-schema

# ----- Scripts shortcuts -----
alias bench  := scripts::bench
alias rd     := scripts::refresh-decisions

# ==============================================================================
# 🔧 Setup
# ==============================================================================

[doc('Install git hooks (pre-commit, pre-push, post-checkout)')]
[group('setup')]
setup-hooks:
    #!/bin/bash
    set -euo pipefail

    # Detect if we're in a worktree — hooks live in the main checkout's .githooks/
    REPO_ROOT="$(git rev-parse --show-toplevel)"
    GITDIR="$(git rev-parse --git-dir)"
    if [[ "$GITDIR" == */worktrees/* ]]; then
        # Worktree: hooks are in the main checkout
        HOOKS_SOURCE="$(dirname "$(dirname "$GITDIR")")/.githooks"
    else
        # Main checkout: hooks live in .githooks/ next to .git/
        HOOKS_SOURCE="$REPO_ROOT/.githooks"
    fi

    echo "=== Installing hooks from $HOOKS_SOURCE ==="

    # Set for current repo (works for both main checkout and worktrees)
    git config core.hooksPath "$HOOKS_SOURCE"

    # Also configure any attached worktrees to use the same hooks path.
    # This handles the case where `just setup-hooks` is run from the main checkout —
    # all worktrees get the same shared hooks.
    for WT in $(git worktree list --porcelain 2>/dev/null | awk '{if ($1=="gitdir") print $2}'); do
        WT_GITDIR="$(dirname "$WT/.git")"
        git -C "$WT_GITDIR" config core.hooksPath "$HOOKS_SOURCE" 2>/dev/null || true
    done

    echo "=== Hooks installed: $HOOKS_SOURCE ==="
    ls -la "$HOOKS_SOURCE"/pre-* 2>/dev/null | awk '{print "  " $NF}'
