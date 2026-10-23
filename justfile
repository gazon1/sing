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
mod gradle   '.just/gradle'
mod tests    '.just/tests'
mod scripts  '.just/scripts'
mod traceability '.just/traceability'

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
alias tdr    := tests::detekt-rules
alias tgates := tests::gate-scripts
alias tcheck := tests::check
alias tclean := tests::clean
alias tm     := tests::ui-maestro
alias gm     := tests::gate-maestro

# `gma` = gate-maestro with agenda (the common case).
# Just does not support named-arg style `gm tags=agenda`; this recipe
# exists so the two-argument form (`gma`) works instead of `gm agenda`.
[doc('Run gate-maestro with agenda tag (alias for `just gm agenda`)')]
gma:
    just tests::gate-maestro agenda
alias gate   := tests::gate

# ----- Lint shortcuts -----
alias lint       := tests::lint
alias detekt-fix := tests::detekt-fix

# ----- Docs shortcuts -----
alias docs-audit  := tests::docs-audit
alias docs-regen  := tests::docs-regen

# ----- Agent workflow evals -----
alias tcheck-evals := tests::tcheck-evals

# ----- OpenSpec -----
alias os-validate := scripts::os-validate

# ----- Backlog↔issues -----
alias issues-refresh := scripts::issues-refresh

# ----- Coverage shortcuts -----
alias coverage := tests::coverage

# ----- DB shortcuts -----
alias db-a   := android::db-schema
alias db-d   := desktop::db-schema

# ----- Spec-first traceability (scenarios → coverage/result matrices) -----
# Coverage is committed and CI-checked; results are a per-commit CI artifact.
# The long forms (`just traceability::trace-coverage-check`) stay available; the
# check variant is deliberately not aliased, so "did you check or just
# regenerate?" is visible in the command rather than in the diff.
alias trace-validate := traceability::trace-validate
alias trace-coverage := traceability::trace-coverage
alias trace-results  := traceability::trace-results
# Probe first, test second. Passing the scenario through as an argument rather
# than reading it from a file keeps the id in the shell history: a carrier queue
# walked id by id is then legible after the fact, and the generated probe's own
# header repeats it anyway.
alias trace-carrier  := traceability::trace-carrier

# ----- Scripts shortcuts -----
alias bench  := scripts::bench
alias rd     := scripts::refresh-decisions
alias baseline := scripts::baseline-composition

# ==============================================================================
# 🔧 Setup
# ==============================================================================

[doc('Install git hooks (pre-commit, pre-push, post-checkout)')]
[group('setup')]
setup-hooks:
    #!/bin/bash
    set -euo pipefail

    # Hooks are version-controlled in .githooks/ at the main checkout and shared by
    # every worktree through core.hooksPath. Pointing core.hooksPath at a directory
    # that does not exist does NOT error — git silently runs no hooks at all — so
    # the existence check below is the load-bearing part of this recipe.
    GITDIR="$(git rev-parse --git-dir)"
    if [[ "$GITDIR" == */worktrees/* ]]; then
        # Worktree: --git-dir is <main-root>/.git/worktrees/<name>, so the main
        # checkout is three levels up from the worktree git-dir.
        MAIN_ROOT="$(dirname "$(dirname "$(dirname "$GITDIR")")")"
    else
        MAIN_ROOT="$(git rev-parse --show-toplevel)"
    fi

    # Prefer the version-controlled .githooks/; fall back to the live .git/hooks/
    # until the versioned copy has landed everywhere. Either way, verify the
    # directory actually holds the hook before pointing core.hooksPath at it —
    # git silently runs NO hooks when hooksPath does not exist.
    HOOKS_SOURCE=""
    for CANDIDATE in "$MAIN_ROOT/.githooks" "$MAIN_ROOT/.git/hooks"; do
        if [[ -x "$CANDIDATE/pre-commit" ]]; then
            HOOKS_SOURCE="$CANDIDATE"
            break
        fi
    done

    if [[ -z "$HOOKS_SOURCE" ]]; then
        echo "ERROR: no hook directory with an executable pre-commit found under $MAIN_ROOT." >&2
        echo "Refusing to set core.hooksPath: git would silently run NO hooks." >&2
        exit 1
    fi

    # Resolve to absolute path: a relative core.hooksPath is resolved from the
    # *repository root* at hook-execution time, not from CWD.  In a worktree,
    # repository root != MAIN_ROOT, so ".githooks" would silently resolve to
    # <worktree-root>/.githooks (which does not exist) instead of the shared
    # <main-root>/.githooks.  Storing an absolute path avoids this entirely.
    HOOKS_SOURCE="$(realpath "$HOOKS_SOURCE")"

    echo "=== Installing hooks from $HOOKS_SOURCE ==="

    # Set for current repo (works for both main checkout and worktrees)
    git config core.hooksPath "$HOOKS_SOURCE"

    # Also configure any attached worktrees to use the same hooks path.
    # This handles the case where `just setup-hooks` is run from the main checkout —
    # all worktrees get the same shared hooks.
    for WT in $(git worktree list --porcelain 2>/dev/null | awk '{if ($1=="gitdir") print $2}'); do
        # For a worktree, .git is a FILE (not a directory) containing
        # "gitdir: <gitdir-path>", so --git-dir returns the actual gitdir.
        WT_GITDIR="$(git -C "$WT" rev-parse --git-dir)"
        git -C "$WT_GITDIR" config core.hooksPath "$HOOKS_SOURCE" 2>/dev/null || true
    done

    echo "=== Hooks installed: $HOOKS_SOURCE ==="
    ls -la "$HOOKS_SOURCE"/pre-* "$HOOKS_SOURCE"/post-* | awk '{print "  " $NF}'

    # A long pre-push holds git's SSH connection to the remote open and idle.
    # Git opens the connection BEFORE the hook runs, and the detekt verdict in
    # the hook takes minutes — so the connection sits there with nothing on it,
    # GitHub closes it, and the push fails with 141 *after* the hook printed its
    # own verdict and said the tree was clean.
    #
    # The keepalive belongs to the SHARED config, not to a worktree: it is
    # harmless everywhere, and the per-worktree copy had to be applied by hand
    # in every worktree — so a fresh worktree or the canonical checkout could
    # still lose the connection. This recipe already writes the shared config
    # (core.hooksPath above), so the keepalive is written next to it.
    #
    # Only set it when the shared config has no core.sshCommand: a configured
    # value may carry a different binary, a port or an identity, and
    # overwriting it would break the push in a way nobody would read as this.
    # --local is the SHARED config file (the worktree file is --worktree).
    if [[ -z "$(git config --local --get core.sshCommand || true)" ]]; then
        git config --local core.sshCommand \
            "ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=10"
        echo "core.sshCommand -> ssh with a keepalive (a long pre-push must not outlive the connection)"
    else
        echo "core.sshCommand already configured -> left alone (it may carry a binary, port or identity)"
    fi
