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
#   2. core.hooksPath, when set, points at a directory that HOLDS the hooks
#      (git runs no hooks at all when the path does not exist);
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
set -uo pipefail

fix=0
for arg in "$@"; do
    case "$arg" in
        --fix) fix=1 ;;
        -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
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

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
git_dir="$(git rev-parse --git-dir 2>/dev/null || echo "")"
common_dir="$(git rev-parse --git-common-dir 2>/dev/null || echo "")"

# The main checkout, when this one is a worktree: the common dir's parent.
main_root="$repo_root"
if [[ "$common_dir" == */.git ]]; then
    main_root="$(dirname "$common_dir")"
elif [[ -n "$common_dir" ]]; then
    main_root="$(cd "$common_dir/.." && pwd)"
fi

echo "=== checkout doctor: $repo_root ==="
echo "  git dir:     $git_dir"
echo "  common dir:  $common_dir"
echo "  main root:   $main_root"
if [[ -n "$git_dir" && -f "$git_dir/config" ]]; then
    echo "  config:      $git_dir/config (last written $(stat -c '%y' "$git_dir/config" 2>/dev/null | cut -d. -f1))"
elif [[ -n "$common_dir" && -f "$common_dir/config" ]]; then
    echo "  config:      $common_dir/config (last written $(stat -c '%y' "$common_dir/config" 2>/dev/null | cut -d. -f1))"
fi
echo ""

# ── 1. the checkout is not bare ──────────────────────────────────────────────
bare="$(git rev-parse --is-bare-repository 2>/dev/null || echo unknown)"
if [[ "$bare" == "false" ]]; then
    report ok "not bare" ""
else
    report bad "not bare" "core.bare=$bare — every command in this checkout will refuse"
    if [[ "$fix" == "1" ]]; then
        git config core.bare false 2>/dev/null \
            && echo "        fixed: core.bare=false" \
            || echo "        NOT fixed: git refused the write"
    fi
fi

# ── 2. the hooks are where they say they are ─────────────────────────────────
hooks_path="$(git config --get core.hooksPath 2>/dev/null || echo "")"
if [[ -z "$hooks_path" ]]; then
    report bad "core.hooksPath" "not set — git runs NO hooks (the pre-push verdict never happens)"
    if [[ "$fix" == "1" && -x "$main_root/.githooks/pre-push" ]]; then
        git config core.hooksPath "$main_root/.githooks" 2>/dev/null \
            && echo "        fixed: core.hooksPath=$main_root/.githooks" \
            || echo "        NOT fixed: git refused the write"
    fi
elif [[ ! -x "$hooks_path/pre-push" ]]; then
    report bad "core.hooksPath" "$hooks_path — no executable pre-push there, so git runs no hooks"
    if [[ "$fix" == "1" && -x "$main_root/.githooks/pre-push" ]]; then
        git config core.hooksPath "$main_root/.githooks" 2>/dev/null \
            && echo "        fixed: core.hooksPath=$main_root/.githooks" \
            || echo "        NOT fixed: git refused the write"
    fi
else
    report ok "core.hooksPath" "$hooks_path"
fi

# ── 3. the SSH command carries the keepalive ─────────────────────────────────
ssh_command="$(git config --get core.sshCommand 2>/dev/null || echo "")"
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
    echo "  INFO    rtk for Bash                 not installed ($RTK_HOOKS) — Bash output is not compressed"
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
