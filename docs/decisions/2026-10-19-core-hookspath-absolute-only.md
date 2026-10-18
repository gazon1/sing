---
created: 2026-10-19
tags: [git, hooks, worktree, infrastructure]
status: accepted
---

# ADR: `core.hooksPath` — absolute path only, machine-specific by design

## Context

Git's `core.hooksPath` config accepts only absolute paths. Relative paths are silently
resolved by Git at hook-execution time from the **repository root**, not from CWD. This
creates a subtle failure mode in worktrees:

```
# Main checkout:  /home/max/AndroidStudioProjects/singularity_cllone_kmp/
# Worktree:       /home/max/worktrees/singularity-mr-1/
# Hooks stored at: /home/max/AndroidStudioProjects/singularity_cllone_kmp/.githooks/

# In worktree, git resolves core.hooksPath from repo root:
#   → /home/max/worktrees/singularity-mr-1/.githooks  (DOES NOT EXIST)
#   → Git silently runs 0 hooks
```

The fix (session 2026-10-18) was to store an **absolute path** in `core.hooksPath`
and use `realpath` to normalise it before writing:

```bash
# justfile setup-hooks recipe (lines 163–168):
HOOKS_SOURCE="$(realpath "$HOOKS_SOURCE")"
git config core.hooksPath "$HOOKS_SOURCE"
```

`realpath` is necessary because the candidate path might contain `..` or a symlink,
and what gets stored in `.git/config` must be the canonical absolute form.

## Problem

The absolute path `/home/max/AndroidStudioProjects/singularity_cllone_kmp/.githooks` is
**machine-specific**. If the main repository checkout is moved to a different location:

1. All registered worktrees retain the old absolute path in their `.git/config`
2. Git silently runs no hooks (non-existent hooksPath is not an error)
3. Divergence-prevention hooks (`pre-rebase`, `check-unmerged.sh`) are bypassed
4. No warning is emitted until a colleague pulls and finds divergent history

The same applies if the machine is reimaged, the home directory path changes, or the
project is checked out under a different path on a different machine.

## Decision

1. **Absolute path is the only correct option.** Relative `core.hooksPath` fails
   silently in worktrees — there is no portable alternative within Git's constraints.

2. **Recovery procedure is documented here.** When the main repo moves:

   ```bash
   # Run from any worktree or the main checkout:
   cd /path/to/new/location/singularity_cllone_kmp
   just setup-hooks

   # This rewrites core.hooksPath for:
   #   - the current repo
   #   - all attached worktrees (via git worktree list --porcelain)
   ```

3. **CI gate verifies hooks are accessible.** `static-gates.sh` includes an advisory
   gate that reads `git config --get core.hooksPath` and checks that the directory
   exists and is executable. This catches the case where hooks are missing but does
   not detect a stale absolute path pointing to a different (still-existing) location.

4. **`setup-hooks` is idempotent and safe to re-run.** Re-running `just setup-hooks`
   from the main checkout refreshes the path for all worktrees. There is no harm
   in running it multiple times.

## Consequences

- **Positive:** Hooks work correctly in all worktrees today.
- **Negative:** Hooks silently break if the main checkout moves. The CI gate does not
  detect this specific case (only the general "hooks dir missing" case).
- **Mitigation:** The recovery step (`just setup-hooks` from the new location) is
  a single command and takes < 1 second. The user whose checkout moves must remember
  to run it — there is no automated detection of the relocation.
- **Alternative explored — `git rev-parse --git-dir` relative path:** This returns
  different values in worktrees vs. main checkout, so a single `core.hooksPath`
  value cannot be expressed portably as a relative path from it.

## Links

- `justfile:163–168` — `realpath` normalisation before writing `core.hooksPath`
- `justfile:178–182` — `git -C "$WT" rev-parse --git-dir` for worktree gitdir
- `.githooks/pre-rebase` — divergence-prevention hook relying on `core.hooksPath`
- `scripts/ci/static-gates.sh` — advisory gate: `git hooks path is per-worktree`
