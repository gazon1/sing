---
title: The commit hook compiles in a worktree too — the wait is the price of knowing
date: 2026-10-23
status: superseded
superseded-by: 2026-10-23-the-commit-compiles-what-the-change-reaches
tags: [hooks, worktrees, git, tooling]
---

# The commit hook compiles in a worktree too

> **Superseded on 2026-10-23.** The decision that the hook compiles in a
> worktree, exactly as it does in the main checkout, **stands** — it is carried
> forward in the Consequences of
> `2026-10-23-the-commit-compiles-what-the-change-reaches`. What is superseded
> is the *set*: this ADR fixed it to `:shared` and `:desktopApp`, and the set is
> now computed from the module graph. The consequences below are therefore
> history — read the newer ADR for what the hook does today.

## Context

`.githooks/pre-commit` compiled the modules that were about to be committed — and it
skipped exactly where this repository does its work. A linked worktree exited 0 without
compiling anything:

```bash
if [ "$(git rev-parse --git-dir)" != "$(git rev-parse --git-common-dir)" ]; then
    echo "=== Pre-commit: skipped (worktree) ==="
    exit 0
fi
```

A linked worktree is not the exception here; it is the main working mode. The repository
is built around worktrees — every session takes one, `./gw` gives it a private
`GRADLE_USER_HOME`, and the isolation ADR
(`2026-09-25-git-hooks-worktree-isolation`) exists to make that mode work. The skip
therefore switched the check off in the common case and left it on in the rare one.

The ADR of 2026-09-25 says what a worktree was supposed to get: a "compile-only" check,
because full test runs belong in CI. The implementation gave it nothing, and the gap was
invisible in the way a gap in a hook is invisible — the only thing that tested the hook
was the hook, and the hook skipped.

The cost was real, and it was the reason the skip was there: a compile of `:shared` and
`:desktopApp` is minutes on a cold cache. But the compile is incremental, and the
worktree's own Gradle home keeps its configuration cache warm
(`org.gradle.configuration-cache=true`, `org.gradle.caching=true`). Once the worktree is
warm, a commit that changes no Kotlin source costs seconds.

What the skip bought was a commit that does not compile. It was found in CI — or, when a
merge went through without CI, not at all: a Kotlin test was changed in a worktree, the
commit passed because nothing was compiled, and the merge reached `main` unverified.

## Decision

**The hook compiles in a worktree, exactly as it does in the main checkout.**

- The skip is gone. There is no context in which the hook exits 0 without compiling.
- The lint step above the compile (`scripts/lint-changed.py`) runs there too: it is
  seconds, and it is the check that catches a mechanical finding in the file that was
  just written.
- The compile stays the same one the main checkout runs — `:shared:compileKotlinJvm`,
  `:shared:compileTestKotlinJvm` and `:desktopApp:compileKotlin` — through `./gw`, with
  the slot pool off (`SINGULARITY_GRADLE_SLOTS_DIR=0`), so a commit waits for a CPU slot
  but never for the pool.
- The price is the commit's wait, and it is written down in the hook itself: the wait
  is the price of knowing the commit compiles. The cold price is paid once, by the first
  commit in a new worktree.
- `git commit --no-verify` remains the way out. It is now the only way out, and that is
  the point: before, a worktree commit took it by default.

## Rationale

A check that is switched off in the mode most commits are made in is not a check. The
gate suite's own doctrine applies here as much as to a Gradle task: "a gate nobody has
seen fail is a gate whose verdict nobody can trust" — and this one had never been seen
fail, because it had never run.

The incremental argument settles the cost question that the skip was built on. Gradle's
up-to-date checks make a warm worktree's compile a matter of seconds, and the
configuration cache removes the configuration cost as well. The minutes the skip was
avoiding are the minutes of a COLD worktree — paid once, and already paid by the first
compile that the developer runs anyway.

The alternative — "CI compiles anyway" — is the argument the hook's own comment used,
and it is the argument that let a broken merge through: CI is a place failures are
discovered, not a place they are prevented. The check that prevents it is the one that
runs at the moment the change is made, while the author is still looking at it.

## Consequences

- A commit in a worktree compiles before it is made. The first commit in a new worktree
  pays the cold cost; after that the wait is seconds.
- A commit that does not compile does not happen — in a worktree as in the main
  checkout. The regression test drives the real hook through a real `git commit` in a
  temporary linked worktree and says so in both directions.
- **The compile is of whole modules, not of the diff.** `:shared` and `:desktopApp` are
  compiled whatever the change touches — the lint step above it is the one that looks at
  the changed files. So a breakage that is NOT yours blocks your commits in that
  worktree, and the only way past it is `--no-verify`. That is the price, stated
  plainly: the hook judges the module, not the line.
- **Parallel commits do not queue.** The hook switches the slot pool OFF
  (`SINGULARITY_GRADLE_SLOTS_DIR=0`), so a commit waits for a CPU slot but never for the
  pool (ADR `2026-10-22-gradle-memory-budget`). With the compile now running in every
  worktree, N concurrent commits on one machine are N concurrent builds rather than a
  queue of one. The trade is deliberate and it was made for the hook: a commit must not
  sit behind somebody else's build for half an hour. The price is the host's.
- The trade-off the 2026-09-25 ADR recorded ("compile-only in worktrees since full test
  runs belong in CI") is now what the hook does, rather than what it promised.
- The full test suite still belongs to CI. This is the compile, not the suite.

## Links

- `.githooks/pre-commit` — the hook
- `scripts/tests/test_pre_commit_hook.py` — the regression test
- ADR `2026-09-25-git-hooks-worktree-isolation` — the worktree isolation this sits in
- ADR `2026-10-22-gradle-memory-budget` — why the slot pool is off in the hook
