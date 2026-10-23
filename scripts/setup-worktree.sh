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
# worktree). It is deliberately not applied here: some of this repository's
# worktrees are on branches that predate the .githooks directory, and switching
# the shared value would silently leave those with no hooks at all — trading one
# silent misdirection for a larger silent absence.
#
# So the value is set per worktree, through the worktree-scoped config Git
# provides for exactly this. It overrides the shared value here and touches
# nothing else.
#
# The bare flag — the other half of the same switch
#
# `extensions.worktreeConfig` is repo-wide, and it is the switch that makes the
# per-worktree config visible at all. Once it is on, Git reads `core.bare` from
# the per-worktree config, and a worktree whose per-worktree config does not say
# `core.bare = false` is taken for a bare repository: every command that needs a
# work tree dies with "fatal: this operation must be run in a work tree" —
# `git status`, `git add`, `git commit`, `git rebase`, all of them.
#
# `git worktree add` does NOT write that line. Measured on git 2.56: the
# command copies the MAIN worktree's `config.worktree` into the new worktree's
# — and the main worktree of a bare repository has no such file, so a new
# worktree gets nothing and is born broken. A worktree created before the
# switch never gets the line either. Every worktree needs the repair, and a
# worktree created afterwards needs it just as much.
#
# Therefore the order here is not cosmetic:
#
#   1. every worktree's per-worktree config gets `core.bare = false` FIRST,
#      while the extension is still off and those files are inert;
#   2. this worktree's per-worktree config also gets `core.hooksPath`;
#   3. the extension is switched on LAST — nothing is broken at any point.
#
# The per-worktree config is written with `git config --file <path>`, never
# with `git config --worktree`. The two commands do not write to the same
# place, and the destination depends on the command:
#
#   - `git config --worktree` resolves its target through Git. In a linked
#     worktree it lands in that worktree's own `config.worktree`; in the MAIN
#     entry of a bare repository it lands in the main entry's `config.worktree`,
#     where a `core.bare = false` un-bares the repository — `git rev-parse
#     --is-bare-repository` answers `false` there. Without the extension it
#     lands in the shared config, or is refused outright when the repository
#     has more than one working tree.
#   - plain `git config` always lands in the SHARED config, from any worktree
#     — including a worktree Git already considers bare. That is how a
#     `core.bare = false` reached the shared config of a bare repository and
#     un-bared it for every worktree at once.
#
# `--file` writes exactly the file it is given: no repository is resolved, the
# destination is this script's decision rather than Git's, and a file that must
# not be written cannot be written by accident.
#
# Idempotent: safe to re-run. Never starts Gradle.
#
# It DOES touch the network, once: the Python environment is installed with uv
# (the same installer CI uses), and uv resolves packages. Everything else here
# is local. Pass SINGULARITY_TOOLS_VENV to choose where the venv goes.

#
# See docs/decisions/2026-10-04-gradle-daemon-isolation.md
# See docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md
#
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

# ── Where this worktree's config lives ──────────────────────────────────────
#
# Resolved without Git, on purpose: the worktree this script repairs may be one
# that Git already considers bare, and a bare worktree answers no questions.
#
# A linked worktree's `.git` is a FILE that names its git dir; the main
# worktree's `.git` is that git dir itself. The common dir — where the shared
# config and every linked worktree's own directory live — is named by the
# `commondir` file inside a linked worktree's git dir.
#
# The readability of `.git` is checked here, because the tools that read it
# report the permission failure in their own words — `sed: can't read ...:
# Permission denied` — and the operator is left guessing which of the many
# permissions in a repository is the one that is wrong.
if [[ -e "$root/.git" && ! -r "$root/.git" ]]; then
    die "$root/.git is not readable — check its permissions and run this again"
fi
if [[ -f "$root/.git" ]]; then
    gitdir_line="$(sed -n 's/^gitdir: //p' "$root/.git")"
    [[ -n "$gitdir_line" ]] || die "$root/.git does not name a git directory"
    if [[ "$gitdir_line" != /* ]]; then
        gitdir_line="$root/$gitdir_line"
    fi
    [[ -d "$gitdir_line" ]] || die "$root/.git names a git dir that does not exist: $gitdir_line"
    git_dir="$(cd "$gitdir_line" && pwd)"
elif [[ -d "$root/.git" ]]; then
    git_dir="$(cd "$root/.git" && pwd)"
else
    die "no .git here — run this inside a git worktree"
fi

# A shared `core.worktree` makes every worktree of this repository resolve its
# files somewhere else — and it fails silently: `git status` reports files that
# are present on disk as deleted, and a commit can record a tree that is not
# the one in front of the author. It gets there when something writes the
# config without `--worktree` while the extension was off, which is why the
# extension is switched on at the end of this script and why the value is
# checked here.
#
# The value belongs to the worktree it names, and a worktree names itself.
shared_worktree="$(git config --get core.worktree || true)"
if [[ -n "$shared_worktree" && "$shared_worktree" != "$root" ]]; then
    git config --unset core.worktree
    echo "removed a shared core.worktree that pointed at $shared_worktree"
    echo "  (it made every other worktree of this repository read someone else's files)"
fi

common_dir="$git_dir"
if [[ -f "$git_dir/commondir" ]]; then
    relative_common="$(tr -d '[:space:]' < "$git_dir/commondir")"
    [[ -d "$git_dir/$relative_common" ]] || die "the common dir named by $git_dir/commondir does not exist"
    common_dir="$(cd "$git_dir/$relative_common" && pwd)"
fi

shared_config="$common_dir/config"
worktree_config="$git_dir/config.worktree"

# ── Write the per-worktree config by file ───────────────────────────────────
#
# `git config --file` touches exactly the file it is given: no repo is resolved,
# no shared config is reachable, and a worktree that Git considers bare cannot
# redirect the write somewhere else.
set_value() {  # <file> <key> <value>
    mkdir -p "$(dirname "$1")"
    git config --file "$1" "$2" "$3"
}

shared_bare_before="$(git config --file "$shared_config" --get core.bare || true)"

# Is this the BARE REPOSITORY ITSELF rather than one of its worktrees?
#
# The two look alike from the outside: in both, the `.git` is a directory and
# `git_dir` equals `common_dir`. They differ in the shared config: a bare
# repository says `core.bare = true`, and the main worktree of an ordinary
# repository does not.
#
# It matters, because a `core.bare = false` written in the main entry's
# per-worktree config turns the bare repository into a non-bare one — and
# nothing else in the tree would notice. The bare repository's main entry is
# not a worktree, so it is not repaired here: it is left as it is.
is_bare_main_entry=false
if [[ "$git_dir" == "$common_dir" && "$shared_bare_before" == "true" ]]; then
    is_bare_main_entry=true
fi

# This worktree: the bare flag first (a bare worktree cannot run Git at all),
# then the hooks path.
if [[ "$is_bare_main_entry" == "true" ]]; then
    set_value "$worktree_config" core.hooksPath .githooks
    echo "this is the bare repository itself, not a worktree of it — its bare flag is left alone"
else
    set_value "$worktree_config" core.bare false
    set_value "$worktree_config" core.hooksPath .githooks
fi

# Every OTHER worktree of the repository needs the bare flag too, and it needs
# it before the extension is switched on: a worktree that Git considers bare
# cannot run `git config --worktree` either, so it cannot be asked to fix
# itself after the fact. The main worktree is left alone on purpose — Git
# reports it as `bare` in that case, and a `core.bare = false` written there
# would turn the bare repository itself into a non-bare one.
for linked_git_dir in "$common_dir"/worktrees/*/; do
    [[ -d "$linked_git_dir" ]] || continue
    set_value "$linked_git_dir/config.worktree" core.bare false
done

# ── Switch the extension on — last ───────────────────────────────────────────
#
# It is repo-wide but inert on its own: without a --worktree value, every
# worktree still reads the shared config exactly as before. With the bare flag
# already in place, it stays inert: every worktree reads `core.bare = false`
# from its own config, which is what it said before the switch.
if [[ "$(git config --file "$shared_config" --get extensions.worktreeConfig || true)" != "true" ]]; then
    git config extensions.worktreeConfig true
    echo "enabled extensions.worktreeConfig (repo-wide; every worktree kept core.bare = false)"
fi

# ── Check the result, in Git's own words ────────────────────────────────────
# The bare repository's main entry is EXPECTED to answer `true` — it is the
# repository, not a worktree of it. Every other worktree has to answer
# `false`: that is what "a worktree that works" looks like in Git's words.
if [[ "$is_bare_main_entry" == "true" ]]; then
    if [[ "$(git rev-parse --is-bare-repository 2>/dev/null || echo unknown)" != "true" ]]; then
        die "the bare repository's main entry does not answer 'true' — check $shared_config"
    fi
elif [[ "$(git rev-parse --is-bare-repository 2>/dev/null || echo unknown)" != "false" ]]; then
    die "this worktree is still treated as a bare repository — check $worktree_config"
fi

effective_hooks="$(git config --get core.hooksPath 2>/dev/null || true)"
if [[ "$effective_hooks" != ".githooks" ]]; then
    die "core.hooksPath is '${effective_hooks:-<unset>}' here, expected .githooks"
fi

shared_bare_after="$(git config --file "$shared_config" --get core.bare || true)"
if [[ "$shared_bare_before" != "$shared_bare_after" ]]; then
    die "the shared config's core.bare changed ('$shared_bare_before' -> '$shared_bare_after')"
fi

echo "core.bare -> false (every worktree), core.hooksPath -> .githooks (this worktree); shared config untouched"

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
# It is the one this script already resolved above — without Git, because a
# worktree Git considers bare answers no questions.
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
