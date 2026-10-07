# ==============================================================================
# 🌿 WORKTREE — parameterized worktree creation + mcode launch
#
#   just wt new backlog-02      # pull origin/main, branch feature/backlog-02
#                                # at /home/max/work/backlog-02
#   just wt code backlog-02     # the same, then hand the terminal to mcode
#   just wt code-in backlog-02  # just launch mcode in an existing worktree
#   just wt pull                # just `git pull origin main`
#   just wt list                # worktrees of this repo
#
# The bash helpers below live in one place and are the single source of truth for
# branch→dir naming and for mcode resolution. `new` and `code` both run CREATE,
# and both mcode entry points run LAUNCH_MCODE, so "create it" and "create it and
# start coding in it" differ by one line rather than by a drifting copy.
#
# Parameters are passed to the helpers as *shell arguments*, never as `{{ }}`
# inside a just variable: just interpolates variables at definition time, where a
# recipe parameter is not in scope, and the literal `{{branch}}` leaks through
# into a real branch and directory name.
# ==============================================================================

# Where worktrees live, and the branch namespace they get. Overridable because
# the host's work root is a personal layout, not a project constant.
WT_ROOT   := env_var_or_default("WT_ROOT", "/home/max/work")
WT_PREFIX := env_var_or_default("WT_PREFIX", "feature/")
# Base branch every new worktree forks from.
WT_BASE   := env_var_or_default("WT_BASE", "main")

# Branch → directory, mirroring how the existing trees are named: the dir is the
# branch tail without its namespace (feature/backlog-02 → backlog-02).
# Sets: full_branch, dir_name, wt_dir. Args: <branch> <root> <prefix>.
RESOLVE_NAME := '''
resolve_name() {
  local branch="$1" root="$2" prefix="$3"

  # Tolerate trailing slashes so `just wt new backlog-02/` never asks git for a
  # branch literally named `backlog-02/`.
  while [[ "$branch" == */ ]]; do branch="${branch%/}"; done

  # A fully qualified branch (someone types `fix/x`) is taken as-is; a bare tail
  # gets the namespace. Without this, `just wt new fix/x` would produce
  # feature/fix/x, which is never what was meant.
  case "$branch" in
    */*) full_branch="$branch" ;;
    *)   full_branch="${prefix}${branch}" ;;
  esac

  case "$full_branch" in
    */*) dir_name="${full_branch##*/}" ;;
    *)   dir_name="$full_branch" ;;
  esac
  wt_dir="${root}/${dir_name}"
}
'''

# Create the worktree. Args: <branch> <root> <prefix> <base>.
CREATE := RESOLVE_NAME + '''
create_worktree() {
  local branch="$1" root="$2" prefix="$3" base="$4"
  if [[ -z "$branch" ]]; then
    echo "ERROR: branch name required, e.g. just wt new backlog-02" >&2
    exit 2
  fi
  resolve_name "$branch" "$root" "$prefix"

  # Pre-flight both collisions before pulling: `git worktree add` would fail late
  # (after the pull) on either, and a stale branch reads much better here than in
  # git's own wording.
  if [[ -e "$wt_dir" ]]; then
    echo "ERROR: $wt_dir already exists." >&2
    echo "Attach instead with: just wt code-in $dir_name" >&2
    exit 1
  fi
  if git show-ref --verify --quiet "refs/heads/${full_branch}"; then
    echo "ERROR: branch '${full_branch}' already exists." >&2
    echo "Attach instead with: just wt code-in $dir_name" >&2
    exit 1
  fi

  echo "=== git pull origin $base ==="
  git pull origin "$base"

  echo "=== git worktree add -b $full_branch $wt_dir $base ==="
  mkdir -p "$(dirname "$wt_dir")"
  git worktree add -b "$full_branch" "$wt_dir" "$base"

  echo
  echo "=== Worktree ready: $wt_dir (branch $full_branch, base $base) ==="
  echo "  cd $wt_dir"
  echo "  just wt code-in $dir_name      # start mcode here"
}
'''

# cd into a worktree and exec mcode. Args: <wt_dir> [extra args...]
LAUNCH_MCODE := '''
launch_mcode() {
  local wt_dir="$1"; shift || true

  # Resolve the CLI instead of hardcoding one developer's install path: an explicit
  # override wins, then PATH. Same reasoning as os-validate.
  local mcode_bin="${MCODE_BIN:-}"
  if [[ -z "$mcode_bin" || ! -x "$mcode_bin" ]]; then
    mcode_bin="$(command -v mcode || true)"
  fi
  if [[ -z "$mcode_bin" ]]; then
    echo "ERROR: mcode not found on PATH (override with MCODE_BIN=/path/to/mcode)." >&2
    echo "Worktree is at $wt_dir — start it yourself with:" >&2
    echo "  cd $wt_dir && mcode" >&2
    exit 1
  fi

  echo "=== Launching mcode in $wt_dir ==="
  # exec: mcode owns the terminal from here, inheriting real stdio. Nothing after
  # it would run anyway.
  cd "$wt_dir"
  exec "$mcode_bin" "$@"
}
'''

[group('worktree')]
[doc('Fast-forward the local base branch to origin')]
[working-directory(justfile_directory())]
pull:
    #!/bin/bash
    set -euo pipefail
    echo "=== git pull origin {{WT_BASE}} ==="
    git pull origin {{WT_BASE}}

[group('worktree')]
[doc('List worktrees of this repo')]
[working-directory(justfile_directory())]
list:
    #!/bin/bash
    set -euo pipefail
    git worktree list

[group('worktree')]
[doc('Create a worktree: just wt new backlog-02  (branch feature/backlog-02)')]
[working-directory(justfile_directory())]
new branch:
    #!/bin/bash
    set -euo pipefail
    {{ CREATE }}
    create_worktree "{{branch}}" "{{WT_ROOT}}" "{{WT_PREFIX}}" "{{WT_BASE}}"

[group('worktree')]
[doc('Create a worktree and launch mcode inside it: just wt code backlog-02')]
[working-directory(justfile_directory())]
code branch *args:
    #!/bin/bash
    set -euo pipefail
    {{ CREATE }}
    create_worktree "{{branch}}" "{{WT_ROOT}}" "{{WT_PREFIX}}" "{{WT_BASE}}"
    {{ RESOLVE_NAME }}
    resolve_name "{{branch}}" "{{WT_ROOT}}" "{{WT_PREFIX}}"
    {{ LAUNCH_MCODE }}
    launch_mcode "$wt_dir" {{args}}

[group('worktree')]
[doc('Launch mcode in an existing worktree: just wt code-in backlog-02')]
[working-directory(justfile_directory())]
code-in name *args:
    #!/bin/bash
    set -euo pipefail
    wt_dir="{{WT_ROOT}}/{{name}}"
    if [[ ! -d "$wt_dir" ]]; then
        echo "ERROR: no worktree at $wt_dir" >&2
        echo "Usage: just wt code-in <dir-name>" >&2
        echo "Existing ones:" >&2
        git worktree list >&2
        exit 1
    fi
    {{ LAUNCH_MCODE }}
    launch_mcode "$wt_dir" {{args}}
