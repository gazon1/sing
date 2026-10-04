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
mod kiwi     '.just/kiwi'

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
alias tkr   := tests::kover-rules
alias tcheck := tests::check
alias tclean := tests::clean
alias tm     := tests::ui-maestro
alias gm     := tests::gate-maestro
alias gate   := tests::gate

# ----- Lint shortcuts -----
alias lint       := tests::lint
alias detekt-fix := tests::detekt-fix
# The name is the point: `honesty` reads as "is this gate lying to me?" at the moment
# someone is about to believe a green one. Slow on purpose — see the recipe.
alias honesty    := tests::gate-honesty

# ----- Docs shortcuts -----
alias docs-audit  := tests::docs-audit
alias docs-regen  := tests::docs-regen

# ----- Agent workflow evals -----
alias tcheck-evals := tests::tcheck-evals

# ----- OpenSpec -----
alias os-validate := scripts::os-validate

# ----- Coverage shortcuts -----
alias coverage := tests::coverage
alias cr       := tests::coverage-ratchet

# ----- DB shortcuts -----
alias db-a   := android::db-schema
alias db-d   := desktop::db-schema

# ----- Kiwi TCMS shortcuts -----
# start/stop — фоновый режим: контейнеры живут между вызовами just.
alias kiwi-start   := kiwi::start
alias kiwi-stop    := kiwi::stop
alias kiwi-wait    := kiwi::wait
alias kiwi-status  := kiwi::status
alias kiwi-logs    := kiwi::logs
alias kiwi-up      := kiwi::up
alias kiwi-down    := kiwi::stop
alias kiwi-restart := kiwi::restart
alias kiwi-purge   := kiwi::purge
alias ksync        := kiwi::sync-plan
alias kresults     := kiwi::sync-results
alias kgaps        := kiwi::gaps
alias kprune       := kiwi::prune
alias kfloor       := kiwi::floor

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
    ls -la "$HOOKS_SOURCE"/pre-* "$HOOKS_SOURCE"/post-* | awk '{print "  " $NF}'
