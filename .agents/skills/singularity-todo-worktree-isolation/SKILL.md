# singularity-todo-worktree-isolation

> **When to use:** Any refactoring that touches many files, changes architecture, or requires a long-running series of PRs. Keeps main checkout clean and allows parallel work.

## Why Worktrees

Git worktrees let you work on multiple branches **simultaneously** in separate working directories, without `git stash` or uncommitted changes cluttering your main checkout.

**Key benefit:** Main checkout (`~/AndroidStudioProjects/singularity_cllone_kmp`) stays clean and compilable at all times. Parallel feature work can continue there while you refactor in a worktree.

## Quick Reference

```bash
# Create a new worktree for Epic N
cd ~/AndroidStudioProjects/singularity_cllone_kmp
git worktree add -b refactor/techdebt-epic2 ~/work/singularity-todo-techdebt-epic2 main

# Verify isolation
git status  # main checkout: clean
cd ~/work/singularity-todo-techdebt-epic2 && git status  # branch: refactor/techdebt-epic2

# Push branch for PR
cd ~/work/singularity-todo-techdebt-epic2
git push -u origin refactor/techdebt-epic2

# Cleanup after merge
cd ~/AndroidStudioProjects/singularity_cllone_kmp
git worktree remove ~/work/singularity-todo-techdebt-epic2
git branch -d refactor/techdebt-epic2
```

## Important Constraints

### Shared `.git` directory

All worktrees **share the same `.git`** directory with the main checkout. This means:

1. **Pre-commit hooks from main run on worktree files.** If the main checkout has failing pre-commit hooks (e.g., broken code in uncommitted changes), use `git commit --no-verify` for worktree commits.

2. **Never make uncompilable changes in main checkout** — it blocks worktree commits via the pre-commit hook.

3. **Main checkout must be on `main`** — don't `git checkout` in the main directory while worktrees exist.

### Gradle caches are shared

`~/.gradle/caches` is shared by default — this is fine and desirable. No special setup needed.

### IDE integration

Open the worktree directory as a separate project in Android Studio: `File → Open → ~/work/singularity-todo-techdebt`. Each worktree is a fully independent project.

**Do NOT create symlinks** between worktrees or between worktree and main — this corrupts the git index.

## Per-Epic Branch Structure

Each epic gets its own branch and worktree:

```
main (~/AndroidStudioProjects/singularity_cllone_kmp)
├── refactor/techdebt-epic1 (~/work/singularity-todo-techdebt) ✅ merged
├── refactor/techdebt-epic2 (~/work/singularity-todo-techdebt-epic2) ← current
└── refactor/techdebt-epic3 (~/work/singularity-todo-techdebt-epic3) ← future
```

Before starting Epic 2: merge Epic 1 into main, then create Epic 2 worktree from updated main.

## Gradle wrapper in worktree

Worktrees share the parent's `.git` but have their own `gradle/wrapper/`. If `gradle-wrapper.jar` is missing in a new worktree:

```bash
cp ~/AndroidStudioProjects/singularity_cllone_kmp/gradle/wrapper/gradle-wrapper.jar \
   ~/work/singularity-todo-techdebt-epic2/gradle/wrapper/
```

## Pre-commit hook bypass

When main checkout has broken pre-commit hooks (uncompilable state), worktree commits need `--no-verify`:

```bash
git commit --no-verify -m "chore: my worktree commit"
```

This bypasses only the pre-commit hook — git itself is not affected.

## When NOT to use worktrees

- Quick hotfixes (1-2 file changes) — use main checkout
- Documentation-only changes — use main checkout
- When parallel work in main checkout is finished and can be committed cleanly
