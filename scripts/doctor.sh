#!/usr/bin/env bash
#
# scripts/doctor.sh — one call that says whether this checkout is sound.
#
# Usage:
#     scripts/doctor.sh          # diagnose: print every invariant and its verdict
#     scripts/doctor.sh --fix    # diagnose, then repair what can be repaired safely
#
# Why this exists
#
# The checkout is shared: one canonical clone, ~20 worktrees, all of them
# writing into the SAME common .git/config. Any `git config` run from any of
# them lands there — including a `git config` that a TEST runs inside a
# throwaway repository, because a test that inherits GIT_DIR writes to
# whatever GIT_DIR points at.
#
# The damage is invisible until it is expensive:
#
#   - `core.bare = true` makes every worktree of the clone a bare repository:
#     `git status` refuses, `git diff` refuses, and the tree cannot be judged;
#   - a lost `core.hooksPath` means git silently runs NO hooks — the pre-push
#     verdict does not happen, and nothing says so;
#   - a lost or replaced `core.sshCommand` takes the keepalive with it, and
#     the push dies with 141 after the hook already said the tree was clean.
#
# Each of these was diagnosed from scratch, three times, because the symptom
# (a push that sends nothing, a commit that refuses) does not name the cause.
# This script answers "is this checkout sound?" in one call.
#
# The invariants are deliberately few, and each one is a value whose absence
# or wrongness has already cost a session:
#
#   1. the checkout is not bare;
#   2. core.hooksPath, when set, is the relative path the repository declares
#      (config/checkout-invariants.json) and points at a directory that HOLDS
#      the hooks (git runs no hooks at all when the path does not exist);
#   3. core.sshCommand, when set, carries the keepalive;
#   4. the remote is configured;
#   5. the Gradle wrapper is private per worktree (./gw), so one worktree's
#      `./gradlew --stop` cannot kill another's daemons.
#
# Exit code: 0 when every invariant holds, 1 when at least one does not.
# `--fix` repairs the two that are safe to repair (bare, hooksPath) and only
# when the target is known to exist. It never touches core.sshCommand when a
# value is already there: a configured value may carry a different binary, a
# port or an identity, and overwriting it would break the push in a way
# nobody would read as this.
#
# WHERE THE REPAIRS ARE WRITTEN — per worktree, never the shared config
#
# Every repair lands in THIS worktree's own `config.worktree`, written with
# `git config --file <that file>`. The shared config (the common dir's
# `config`) is never written: it belongs to every worktree of the clone at
# once, so a repair written there is a repair applied to twenty checkouts,
# none of which asked for it.
#
# That is not a style preference; it is the difference between a repair and
# the damage the doctor exists to prevent:
#
#   - a `core.hooksPath` written into the shared config makes every worktree
#     run ONE checkout's hooks — the very thing scripts/setup-worktree.sh
#     exists to prevent (ADR: the hooks of a checkout you are not working in);
#   - a `core.bare = false` written into the shared config un-bares the
#     repository for every worktree at once (ADR
#     `docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md`);
#   - and an ABSOLUTE `core.hooksPath` is wrong by construction: it is right in
#     exactly one checkout. The repository declares the RELATIVE `.githooks`,
#     and the doctor reads that declaration instead of inventing a value — one
#     declaration, two readers (a gate and a doctor that disagree are two
#     policies).
#
# `--file` writes exactly the file it is given: no repository is resolved, the
# destination is this script's decision rather than Git's, and the shared
# config cannot be written by accident. `scripts/setup-worktree.sh` uses the
# same mechanism for the same reason, and this script resolves the git dir,
# the common dir and the per-worktree config file the same way — WITHOUT Git,
# because a worktree that Git already considers bare answers no questions.
#
# The bare repository ITSELF is never un-bared: a `core.bare = false` in the
# main entry of a bare repository turns that repository into a non-bare one,
# and nothing else in the tree would notice. The doctor says so instead.
#
# See docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md
set -uo pipefail

fix=0
for arg in "$@"; do
    case "$arg" in
        --fix) fix=1 ;;
        -h|--help) sed -n '2,83p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

problems=0

report() {
    # report <ok|bad> <what> <detail>
    local state="$1" what="$2" detail="${3:-}"
    if [[ "$state" == "ok" ]]; then
        printf '  OK    %-28s %s\n' "$what" "$detail"
    else
        printf '  BROKEN %-27s %s\n' "$what" "$detail"
        problems=$((problems + 1))
    fi
}

info() {
    # info <what> <detail> — a remark, not an invariant.
    printf '  INFO   %-28s %s\n' "$1" "${2:-}"
}

# Git exports these to a hook it runs. A doctor started inside a hook would
# then judge the repository the hook is about instead of the checkout it
# stands in — the gates scrub the same names (sgate.gatekit.gitenv.tree_env).
unset GIT_DIR GIT_WORK_TREE GIT_COMMON_DIR GIT_INDEX_FILE

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script_root="$(cd "$script_dir/.." && pwd)"

# ── Where this checkout is ──────────────────────────────────────────────────
#
# Resolved from the CURRENT DIRECTORY upwards, so that the answer does not
# depend on where the doctor was started from: the same checkout started from
# its root and from a subdirectory is one checkout, and must be judged and
# repaired identically.
#
# Git answers first — it is the one that knows where the work tree is — and
# the filesystem answers when Git cannot: a checkout that Git already
# considers bare refuses `--show-toplevel`, and the answer to "where am I" is
# then the directory that holds the `.git` of this checkout.
repo_root=""
if top="$(git rev-parse --show-toplevel 2>/dev/null)" && [[ -n "$top" ]]; then
    repo_root="$(cd "$top" 2>/dev/null && pwd)" || repo_root="$top"
fi
if [[ -z "$repo_root" ]]; then
    probe="$PWD"
    while :; do
        if [[ -e "$probe/.git" ]]; then
            repo_root="$(cd "$probe" && pwd)"
            break
        fi
        [[ "$probe" == "/" ]] && break
        probe="$(dirname "$probe")"
    done
fi
if [[ -z "$repo_root" ]]; then
    # A bare repository has no `.git` of its own: it IS the git dir.
    bare_git_dir="$(git rev-parse --absolute-git-dir 2>/dev/null || true)"
    [[ -n "$bare_git_dir" ]] && repo_root="$bare_git_dir"
fi

if [[ -z "$repo_root" ]]; then
    echo "=== checkout doctor: $PWD ==="
    echo ""
    report bad "repository" "no git repository here — the doctor judges a checkout"
    echo ""
    echo "checkout doctor: $problems broken invariant(s) — the lines above say which"
    exit 1
fi

# ── The git dir, the common dir and this worktree's own config ─────────────
#
# The same resolution scripts/setup-worktree.sh performs, and for the same
# reason: a linked worktree's `.git` is a FILE that names its git dir, the
# main worktree's `.git` IS that git dir, and the common dir — where the
# shared config and every linked worktree's own directory live — is named by
# the `commondir` file inside a linked worktree's git dir.
#
# The shared config is READ here and never written.
git_dir=""
if [[ -f "$repo_root/.git" ]]; then
    gitdir_line="$(sed -n 's/^gitdir: //p' "$repo_root/.git")"
    if [[ -n "$gitdir_line" ]]; then
        [[ "$gitdir_line" != /* ]] && gitdir_line="$repo_root/$gitdir_line"
        git_dir="$(cd "$gitdir_line" 2>/dev/null && pwd)" || git_dir="$gitdir_line"
    fi
elif [[ -d "$repo_root/.git" ]]; then
    git_dir="$(cd "$repo_root/.git" && pwd)"
fi
[[ -z "$git_dir" ]] && git_dir="$repo_root"

common_dir="$git_dir"
if [[ -f "$git_dir/commondir" ]]; then
    relative_common="$(tr -d '[:space:]' < "$git_dir/commondir")"
    common_dir="$(cd "$git_dir/$relative_common" 2>/dev/null && pwd)" \
        || common_dir="$git_dir/$relative_common"
fi

shared_config="$common_dir/config"
worktree_config="$git_dir/config.worktree"
main_root="$(cd "$(dirname "$common_dir")" && pwd)"

# Everything below runs in the checkout itself, so that no answer depends on
# the directory the doctor was started from.
cd "$repo_root" || {
    echo "checkout doctor: cannot enter $repo_root" >&2
    exit 1
}

# ── Reading the configuration ──────────────────────────────────────────────
#
# `extensions.worktreeConfig` is repo-wide: it is the switch that makes the
# per-worktree config visible to Git at all. Until it is on, a
# `config.worktree` is inert — and a repair written into an inert file is
# not a repair. The doctor therefore says so when it writes one.
worktree_config_on="$(git config --file "$shared_config" --get extensions.worktreeConfig 2>/dev/null || true)"

read_config() {
    # read_config <key> — the effective value, in the order Git reads them.
    local key="$1" value=""
    value="$(git config --get "$key" 2>/dev/null || true)"
    if [[ -z "$value" ]]; then
        if [[ "$worktree_config_on" == "true" && -f "$worktree_config" ]]; then
            value="$(git config --file "$worktree_config" --get "$key" 2>/dev/null || true)"
        fi
        if [[ -z "$value" ]]; then
            value="$(git config --file "$shared_config" --get "$key" 2>/dev/null || true)"
        fi
    fi
    printf '%s' "$value"
}

read_json_string() {
    # read_json_string <file> <key> — a string value out of a JSON file.
    local file="$1" key="$2" value=""
    if command -v python3 >/dev/null 2>&1; then
        value="$(python3 -c '
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
except Exception:
    sys.exit(1)
value = data.get(sys.argv[2])
print(value if isinstance(value, str) else "")
' "$file" "$key" 2>/dev/null || true)"
    fi
    if [[ -z "$value" ]] && command -v jq >/dev/null 2>&1; then
        value="$(jq -r --arg key "$key" '.[$key] // empty | select(type == "string")' \
            "$file" 2>/dev/null || true)"
    fi
    if [[ -z "$value" ]]; then
        value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" \
            "$file" 2>/dev/null | head -n 1)"
    fi
    printf '%s' "$value"
}

declared_hooks_path() {
    # The hooks path this repository declares. The declaration belongs to the
    # repository, so the checkout's own copy comes first; the copy that ships
    # with this script is the fallback for a checkout that does not have one.
    local file value=""
    for file in "$repo_root/config/checkout-invariants.json" \
                "$script_root/config/checkout-invariants.json"; do
        [[ -f "$file" ]] || continue
        value="$(read_json_string "$file" hooks_path)"
        if [[ -n "$value" ]]; then
            printf '%s' "$value"
            return 0
        fi
    done
    printf '%s' ".githooks"
}

resolve_against_root() {
    # A relative hooks path is resolved by Git against the top level of the
    # work tree — never against the directory the doctor happens to run in.
    local path="$1"
    if [[ "$path" == /* ]]; then
        printf '%s' "$path"
    else
        printf '%s' "$repo_root/$path"
    fi
}

write_worktree_config() {
    # write_worktree_config <key> <value> — into THIS worktree's own config.
    # `--file` writes exactly the file it is given: no repository is resolved,
    # no shared config is reachable, and a worktree that Git considers bare
    # cannot redirect the write somewhere else.
    mkdir -p "$(dirname "$worktree_config")" 2>/dev/null || true
    git config --file "$worktree_config" "$1" "$2"
}

note_inert_worktree_config() {
    if [[ "$worktree_config_on" != "true" ]]; then
        echo "        NOTE: extensions.worktreeConfig is off — a per-worktree config is inert until it is on;"
        echo "              scripts/setup-worktree.sh enables it, in the order that keeps every worktree working."
    fi
}

echo "=== checkout doctor: $repo_root ==="
echo "  git dir:     $git_dir"
echo "  common dir:  $common_dir"
echo "  main root:   $main_root"
echo "  config:      $shared_config (last written $(stat -c '%y' "$shared_config" 2>/dev/null | cut -d. -f1))"
echo "  worktree:    $worktree_config"
echo ""

# ── 1. the checkout is not bare ─────────────────────────────────────────────
bare="$(git rev-parse --is-bare-repository 2>/dev/null || echo unknown)"
if [[ "$bare" != "true" && "$bare" != "false" ]]; then
    bare_value="$(read_config core.bare)"
    [[ "$bare_value" == "true" ]] && bare="true"
    [[ "$bare_value" == "false" ]] && bare="false"
fi

# Is this the BARE REPOSITORY ITSELF rather than one of its worktrees?
#
# The two look alike from the outside: in both, the shared config says
# `core.bare = true` and the git dir equals the common dir. They differ in one
# thing — a bare repository IS its git dir (it holds HEAD, objects and refs,
# and no `.git` inside it), while an ordinary checkout that somebody broke has
# a `.git` directory inside it. That is the difference between "this is a bare
# repository, leave its flag alone" and "this is a checkout whose core.bare
# was corrupted, and it is repairable".
shared_bare="$(git config --file "$shared_config" --get core.bare 2>/dev/null || true)"
is_bare_main_entry=false
if [[ "$git_dir" == "$common_dir" && "$shared_bare" == "true" && ! -d "$repo_root/.git" ]]; then
    is_bare_main_entry=true
fi

if [[ "$bare" == "false" ]]; then
    report ok "not bare" ""
elif [[ "$is_bare_main_entry" == "true" ]]; then
    report bad "not bare" "core.bare=true — this is the bare repository ITSELF, not a worktree of it; the bare flag is left alone (a core.bare=false here would turn the bare repository into a non-bare one, and nothing else would notice)"
else
    report bad "not bare" "core.bare=$bare — every command in this checkout will refuse"
    if [[ "$fix" == "1" ]]; then
        if write_worktree_config core.bare false; then
            echo "        fixed: core.bare=false (in $worktree_config — the shared config is untouched)"
            note_inert_worktree_config
        else
            echo "        NOT fixed: git refused the write to $worktree_config"
        fi
    fi
fi

# ── 2. the hooks are where they say they are ─────────────────────────────────
expected_hooks="$(declared_hooks_path)"
hooks_path="$(read_config core.hooksPath)"
hooks_broken=false
if [[ -z "$hooks_path" ]]; then
    report bad "core.hooksPath" "not set — git runs NO hooks (the pre-push verdict never happens)"
    hooks_broken=true
elif [[ ! -x "$(resolve_against_root "$hooks_path")/pre-push" ]]; then
    report bad "core.hooksPath" "$hooks_path — no executable pre-push there (resolved against the repository root), so git runs no hooks"
    hooks_broken=true
elif [[ "$hooks_path" != "$expected_hooks" ]]; then
    report bad "core.hooksPath" "$hooks_path — the declaration says $expected_hooks, and only a relative value is right in every worktree"
    hooks_broken=true
else
    report ok "core.hooksPath" "$hooks_path"
fi

if [[ "$hooks_broken" == "true" && "$fix" == "1" ]]; then
    expected_dir="$(resolve_against_root "$expected_hooks")"
    if [[ -x "$expected_dir/pre-push" ]]; then
        if write_worktree_config core.hooksPath "$expected_hooks"; then
            echo "        fixed: core.hooksPath=$expected_hooks (in $worktree_config — the shared config is untouched)"
            note_inert_worktree_config
        else
            echo "        NOT fixed: git refused the write to $worktree_config"
        fi
    else
        echo "        NOT fixed: $expected_dir holds no executable pre-push — there is nothing safe to point at"
    fi
fi

# ── 3. the SSH command carries the keepalive ─────────────────────────────────
ssh_command="$(read_config core.sshCommand)"
if [[ -z "$ssh_command" ]]; then
    report bad "core.sshCommand" "not set — a long pre-push can lose the connection (141)"
elif [[ "$ssh_command" == *ServerAliveInterval* ]]; then
    report ok "core.sshCommand" "$ssh_command"
else
    report bad "core.sshCommand" "$ssh_command — no ServerAliveInterval, so the keepalive is gone"
fi

# ── 4. the remote is there ───────────────────────────────────────────────────
remote_url="$(git remote get-url origin 2>/dev/null || echo "")"
if [[ -z "$remote_url" ]]; then
    report bad "remote origin" "not configured — pushes have nowhere to go"
else
    report ok "remote origin" "$remote_url"
fi

# ── 5. the Gradle home is private ────────────────────────────────────────────
if [[ -x "$repo_root/gw" ]]; then
    report ok "private Gradle home" "$repo_root/gw"
else
    report bad "private Gradle home" "$repo_root/gw is missing or not executable"
fi

# ── 6. the agent's Bash goes through rtk ─────────────────────────────────────
# Not an invariant of the repository but of the machine: the runtime rewrites
# an agent's Bash call into its rtk equivalent before it runs, so the compact
# output is what reaches the context. The hook lives in the runtime's plugin
# directory, not here, so a machine without it is a machine whose agents pay
# full price for every command — worth saying, not worth failing over.
RTK_HOOKS="${RTK_HOOKS:-$HOME/.minimax/plugins/rtk/hooks/hooks.json}"
if [[ -f "$RTK_HOOKS" ]] && grep -q '"PreToolUse"' "$RTK_HOOKS"; then
    report ok "rtk for Bash" "$RTK_HOOKS"
else
    info "rtk for Bash" "not installed ($RTK_HOOKS) — Bash output is not compressed"
fi

if [[ "$worktree_config_on" != "true" ]]; then
    info "worktree config" "extensions.worktreeConfig is off — the repairs above land in a file Git does not read yet"
fi

echo ""
echo "  worktrees: $(git worktree list 2>/dev/null | wc -l)"
echo "  branch:    $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
echo "  upstream:  $(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || echo none)"

echo ""
if [[ "$problems" == "0" ]]; then
    echo "checkout doctor: every invariant holds"
    exit 0
fi

echo "checkout doctor: $problems broken invariant(s) — the lines above say which"
if [[ "$fix" != "1" ]]; then
    echo "  (run 'scripts/doctor.sh --fix' to repair the ones that are safe to repair)"
fi
exit 1
