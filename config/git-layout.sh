#!/usr/bin/env bash
#
# config/git-layout.sh — the layout of the checkout the caller stands in.
#
# Sourced, not executed:
#
#     . "$(dirname "${BASH_SOURCE[0]}")/../config/git-layout.sh"
#
# Why this file exists
#
# Two scripts need the same facts about a checkout: scripts/doctor.sh, which
# judges it, and scripts/setup-worktree.sh, which prepares it. Both used to
# resolve those facts on their own, and the two copies did not agree in every
# layout. Knowledge that lives in two places rots silently: one script learns
# a new layout and the other does not, and nothing says which one is right.
#
# So the knowledge lives here, once, and both scripts read it.
#
# WHY WITHOUT GIT
#
# A checkout that Git already considers bare answers no questions:
# `git rev-parse --show-toplevel` refuses in it, and the worktree that
# scripts/setup-worktree.sh repairs may be exactly such a checkout. So this
# resolution reads the FILES, not Git:
#
#   - a linked worktree's `.git` is a FILE that names its git dir;
#   - the main worktree's `.git` IS that git dir (a directory);
#   - the bare repository's own main entry has no `.git` at all — the
#     directory IS the git dir, and it is recognised by what a git dir holds:
#     HEAD, objects/ and refs/;
#   - the common dir — where the shared config and every linked worktree's own
#     directory live — is named by the `commondir` file inside a linked
#     worktree's git dir.
#
# The resolution is CWD-independent: the same checkout resolves the same way
# from its root and from any subdirectory, because the walk upwards stops at
# the first directory that has a `.git` — or that is a git dir.
#
# It fails LOUDLY. A layout this file does not understand is a refusal with a
# message on stderr, not a guess: a silent wrong answer is worse than no
# answer, because the wrong answer gets acted upon.
#
# THE VARIABLES IT SETS (absolute paths)
#
#   GIT_LAYOUT_ROOT             the checkout root — the work tree root
#   GIT_LAYOUT_GIT_DIR          the git dir: HEAD, objects, refs
#   GIT_LAYOUT_COMMON_DIR       the common dir: shared by every worktree
#   GIT_LAYOUT_SHARED_CONFIG    $GIT_LAYOUT_COMMON_DIR/config
#   GIT_LAYOUT_WORKTREE_CONFIG  $GIT_LAYOUT_GIT_DIR/config.worktree
#   GIT_LAYOUT_MAIN_ROOT        the directory that holds the common dir
#   GIT_LAYOUT_CANONICAL_ROOTS  the candidates for the canonical checkout
#   GIT_LAYOUT_ROOT_IS_GIT_DIR  true when the root IS the git dir itself
#
# Two of them are the ones a reader is most likely to get wrong:
#
#   - GIT_LAYOUT_ROOT_IS_GIT_DIR is true for the bare repository's own main
#     entry (the directory IS the repository) and false for an ordinary
#     checkout, whose `.git` is a directory that belongs to a repository which
#     HAS a work tree. The two look alike from the outside — in both, the
#     git dir is the common dir — and the difference decides whether the
#     repository's bare flag may be touched.
#
#   - GIT_LAYOUT_MAIN_ROOT is the PARENT of the common dir. For the ordinary
#     layout (common dir = <checkout>/.git) it is the checkout itself; for a
#     separate git dir it is the directory that holds both. The canonical
#     checkout — the one place the repository's `.env` lives — is one of
#     GIT_LAYOUT_CANONICAL_ROOTS: the candidates, in the order they are
#     tried, because the common dir sits inside the canonical checkout
#     (…/checkout/.git), beside it (…/checkout.git) or, for the bare
#     repository's own main entry, in it.
#
# HOW IT IS CALLED
#
# Sourcing the file resolves the layout of the current directory and leaves
# the variables above set. A layout this file does not understand ends the
# shell with a message on stderr and a non-zero exit — a refusal, never a
# guess.
#
# A caller that wants to REPORT the refusal itself (scripts/doctor.sh prints
# it as a broken invariant) sets GIT_LAYOUT_DEFER=1 before sourcing: the file
# then only defines git_layout_resolve, and the caller calls it and decides
# what to do with the failure.
#
# The callers find this file next to themselves, in the checkout they live in
# (config/git-layout.sh). GIT_LAYOUT_HELPER names it when the checkout does
# not carry it — a checkout whose `config` is a FILE (the bare repository's
# own main entry) has no config DIRECTORY to put it in.
#
# See docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md
# See docs/decisions/2026-10-23-the-checkout-has-a-doctor-and-a-gate.md

# git_layout_looks_like_git_dir <dir> — whether the directory IS a git dir.
#
# A git dir holds HEAD, objects/ and refs/. A linked worktree's own git dir
# does not (its objects live in the common dir), so this tells the two apart:
# the first is the repository, the second is one worktree's share of it.
git_layout_looks_like_git_dir() {
    [[ -f "$1/HEAD" && -d "$1/objects" && -d "$1/refs" ]]
}

# git_layout_at_ceiling <dir> — whether the upward walk must stop here.
#
# Git's own ceiling (GIT_CEILING_DIRECTORIES, a colon-separated list) is
# honoured, so that this resolution stops where Git's would: the repository
# found above the ceiling is another one, and resolving it would be an answer
# to a question nobody asked.
git_layout_at_ceiling() {
    local dir="$1" ceiling
    [[ -n "${GIT_CEILING_DIRECTORIES:-}" ]] || return 1
    local -a ceilings=()
    IFS=':' read -ra ceilings <<< "$GIT_CEILING_DIRECTORIES"
    for ceiling in "${ceilings[@]}"; do
        [[ -n "$ceiling" ]] || continue
        [[ "$dir" == "$ceiling" ]] && return 0
    done
    return 1
}

# git_layout_resolve [start_dir] — resolve the layout, or refuse.
#
# The checkout is the one that CONTAINS start_dir (the current directory by
# default): the walk upwards stops at the first directory that has a `.git`
# entry, or that is a git dir. So the same checkout resolves the same way
# from its root and from any subdirectory.
#
# Returns 0 and sets the variables above, or prints the reason on stderr and
# returns 1.
git_layout_resolve() {
    local start="${1:-$PWD}"
    local dir entry gitdir_line relative_common common_base common_parent

    GIT_LAYOUT_ROOT=""
    GIT_LAYOUT_GIT_DIR=""
    GIT_LAYOUT_COMMON_DIR=""
    GIT_LAYOUT_SHARED_CONFIG=""
    GIT_LAYOUT_WORKTREE_CONFIG=""
    GIT_LAYOUT_MAIN_ROOT=""
    GIT_LAYOUT_CANONICAL_ROOTS=()
    GIT_LAYOUT_ROOT_IS_GIT_DIR=false

    if [[ ! -d "$start" ]]; then
        echo "ERROR: $start is not a directory" >&2
        return 1
    fi
    dir="$(cd "$start" 2>/dev/null && pwd)" || {
        echo "ERROR: cannot enter $start" >&2
        return 1
    }

    # ── The checkout root ───────────────────────────────────────────────────
    while :; do
        git_layout_at_ceiling "$dir" && break
        if [[ -e "$dir/.git" ]]; then
            GIT_LAYOUT_ROOT="$dir"
            break
        fi
        if git_layout_looks_like_git_dir "$dir"; then
            GIT_LAYOUT_ROOT="$dir"
            GIT_LAYOUT_ROOT_IS_GIT_DIR=true
            break
        fi
        [[ "$dir" == "/" ]] && break
        dir="$(dirname "$dir")"
    done

    if [[ -z "$GIT_LAYOUT_ROOT" ]]; then
        echo "ERROR: no .git here, and no checkout above $start — this is not a git checkout" >&2
        return 1
    fi

    # ── The git dir ─────────────────────────────────────────────────────────
    if [[ "$GIT_LAYOUT_ROOT_IS_GIT_DIR" == "true" ]]; then
        # The bare repository's own main entry: the directory IS the git dir.
        GIT_LAYOUT_GIT_DIR="$GIT_LAYOUT_ROOT"
    else
        entry="$GIT_LAYOUT_ROOT/.git"
        # The readability of `.git` is checked here, because the tools that read
        # it report the permission failure in their own words — `sed: can't
        # read ...: Permission denied` — and the operator is left guessing
        # which of the many permissions in a repository is the wrong one.
        if [[ ! -r "$entry" ]]; then
            echo "ERROR: $entry is not readable — check its permissions and run this again" >&2
            return 1
        fi
        if [[ -f "$entry" ]]; then
            gitdir_line="$(sed -n 's/^gitdir: //p' "$entry")"
            if [[ -z "$gitdir_line" ]]; then
                echo "ERROR: $entry does not name a git directory" >&2
                return 1
            fi
            [[ "$gitdir_line" != /* ]] && gitdir_line="$GIT_LAYOUT_ROOT/$gitdir_line"
            if [[ ! -d "$gitdir_line" ]]; then
                echo "ERROR: $entry names a git dir that does not exist: $gitdir_line" >&2
                return 1
            fi
            GIT_LAYOUT_GIT_DIR="$(cd "$gitdir_line" && pwd)"
        elif [[ -d "$entry" ]]; then
            GIT_LAYOUT_GIT_DIR="$(cd "$entry" && pwd)"
        else
            echo "ERROR: no .git here — run this inside a git worktree" >&2
            return 1
        fi
    fi

    # ── The common dir ──────────────────────────────────────────────────────
    #
    # The common dir is where the shared config and every linked worktree's own
    # directory live; a linked worktree's git dir names it in `commondir`.
    GIT_LAYOUT_COMMON_DIR="$GIT_LAYOUT_GIT_DIR"
    if [[ -f "$GIT_LAYOUT_GIT_DIR/commondir" ]]; then
        relative_common="$(tr -d '[:space:]' < "$GIT_LAYOUT_GIT_DIR/commondir")"
        [[ "$relative_common" != /* ]] && relative_common="$GIT_LAYOUT_GIT_DIR/$relative_common"
        if [[ ! -d "$relative_common" ]]; then
            echo "ERROR: the common dir named by $GIT_LAYOUT_GIT_DIR/commondir does not exist" >&2
            return 1
        fi
        GIT_LAYOUT_COMMON_DIR="$(cd "$relative_common" && pwd)"
    fi

    # ── The two configs, and the canonical checkout ─────────────────────────
    GIT_LAYOUT_SHARED_CONFIG="$GIT_LAYOUT_COMMON_DIR/config"
    GIT_LAYOUT_WORKTREE_CONFIG="$GIT_LAYOUT_GIT_DIR/config.worktree"

    common_base="$(basename "$GIT_LAYOUT_COMMON_DIR")"
    common_parent="$(cd "$(dirname "$GIT_LAYOUT_COMMON_DIR")" && pwd)"
    GIT_LAYOUT_MAIN_ROOT="$common_parent"
    GIT_LAYOUT_CANONICAL_ROOTS=("$common_parent")
    if [[ "$common_base" == *.git && "$common_base" != ".git" ]]; then
        GIT_LAYOUT_CANONICAL_ROOTS+=("${common_parent}/${common_base%.git}")
    fi
    if [[ "$GIT_LAYOUT_GIT_DIR" == "$GIT_LAYOUT_COMMON_DIR" ]]; then
        # This is the repository's own main entry, so this checkout IS the
        # canonical checkout.
        GIT_LAYOUT_CANONICAL_ROOTS+=("$GIT_LAYOUT_ROOT")
    fi

    return 0
}

# Sourcing the file resolves the layout — unless the caller asked to handle
# the refusal itself.
if [[ "${GIT_LAYOUT_DEFER:-0}" != "1" ]]; then
    git_layout_resolve || exit 1
fi
